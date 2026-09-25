"""과거 구간 백테스트: 각 지표가 실제로 '이후 주가 상승'을 얼마나 잘 골랐는지 측정하고 가중치를 정한다.

방법
- 매달 말(리밸런싱일)마다, 그날까지 알 수 있었던 정보만으로 지표를 계산한다.
  · 재무: 그 시점에 공시돼 있던 사업보고서 (2025-04~2026-03 → 2024년, 2026-04~ → 2025년 보고서)
  · 가격·기술: 그날 종가까지의 일봉
- 다음 1개월(21거래일) 수익률과의 순위 상관(IC)을 지표별로 구한다.
- 앞 구간(학습)에서 IC가 꾸준히 양수였던 지표만 IC/변동성(ICIR)에 비례해 가중치를 주고,
  뒤 구간(검증)에서 상위 종목 포트폴리오 수익률로 확인한다.

한계
- 현재 상장 종목만 대상(상장폐지 종목 빠짐 → 결과가 실제보다 좋게 나올 수 있음)
- 과거 시가총액 = 현재 상장주식수 × 과거 주가 (증자·감자 무시)
- 외국인·기관 수급과 FCF는 과거 데이터가 없어 검증하지 못함

사용법: python -m screener.backtest --dart-cache dart-2025.json --out backtest
"""
from __future__ import annotations

import argparse
import json
import logging
import math
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
import pandas as pd

from . import market as mk
from . import scoring
from . import technical as ta

log = logging.getLogger("backtest")

FWD_DAYS = 21
MIN_MCAP = 1000e8
MIN_TRADING_VALUE = 1e8  # 20일 평균 거래대금

# 지표 → 방향(+1: 클수록 좋음, -1: 작을수록 좋음)
FUND_FACTORS = {"per": -1, "pbr": -1, "roe": 1, "rev_growth": 1, "op_growth": 1, "debt_ratio": -1}
TECH_FACTORS = {
    "trend": 1,          # 200일선 위·50>200·50일선 위 (0~3)
    "dist_ma200": 1,     # 200일선 대비 이격
    "mom_12_1": 1,       # 12-1개월 수익률
    "ret_3m": 1,         # 3개월 수익률
    "ret_1m": 1,         # 1개월 수익률 (단기 반전 여부 확인용)
    "rsi": 1,            # RSI(14)
    "rsi_band": 1,       # 45~70이면 1
    "macd_above": 1,     # MACD > 시그널
    "macd_rising": 1,    # 히스토그램 확대
    "squeeze": 1,        # 볼린저 스퀴즈 후 중심선 위
    "up_down_vol": 1,    # 상승일/하락일 거래량
    "low_vol": 1,        # 변동성 낮을수록(부호 반전된 60일 변동성)
    "high_52w": 1,       # 52주 고가 대비 위치
}


# ---------------------------------------------------------------- 데이터

def load_prices(codes: list[str], cache_dir: Path, start: str, end: str, workers: int = 8) -> dict[str, pd.DataFrame]:
    cache_dir.mkdir(parents=True, exist_ok=True)

    def one(code):
        path = cache_dir / f"{code}.pkl"
        if path.exists():
            return code, pd.read_pickle(path)
        for attempt in range(3):
            try:
                rows = mk._get(None, mk.CHART_URL.format(kind="item", code=code),
                               startDateTime=f"{start}0000", endDateTime=f"{end}2359")
                break
            except Exception:  # noqa: BLE001
                time.sleep(2 * (attempt + 1))
        else:
            return code, None
        df = pd.DataFrame(rows)
        if df.empty:
            return code, None
        df = pd.DataFrame({
            "date": pd.to_datetime(df["localDate"]),
            "open": df["openPrice"].astype(float), "high": df["highPrice"].astype(float),
            "low": df["lowPrice"].astype(float), "close": df["closePrice"].astype(float),
            "volume": df["accumulatedTradingVolume"].astype(float),
        }).set_index("date")
        df.to_pickle(path)
        return code, df

    out = {}
    with ThreadPoolExecutor(workers) as ex:
        for i, (code, df) in enumerate(ex.map(one, codes), start=1):
            if df is not None and len(df) > 60:
                out[code] = df
            if i % 200 == 0:
                log.info("일봉 %d/%d", i, len(codes))
    return out


def daily_features(df: pd.DataFrame) -> pd.DataFrame:
    """종목 하나의 일별 기술 지표(그날 종가까지의 정보만 사용)."""
    c, v = df["close"], df["volume"]
    ma50, ma200 = ta.sma(c, 50), ta.sma(c, 200)
    line, sig, hist = ta.macd(c)
    _, mid, _, width = ta.bollinger(c)
    width_rank = width.rolling(120).rank(pct=True)
    chg = c.diff()
    up_vol = v.where(chg > 0, 0).rolling(20).sum()
    down_vol = v.where(chg < 0, 0).rolling(20).sum()
    r = ta.rsi(c)
    f = pd.DataFrame(index=df.index)
    f["close"] = c
    f["trading_value_20"] = (c * v).rolling(20).mean()
    f["trend"] = (c > ma200).astype(float) + (ma50 > ma200).astype(float) + (c > ma50).astype(float)
    f.loc[ma200.isna(), "trend"] = np.nan
    f["dist_ma200"] = c / ma200 - 1
    f["mom_12_1"] = c.shift(21) / c.shift(252) - 1
    f["ret_3m"] = c / c.shift(63) - 1
    f["ret_1m"] = c / c.shift(21) - 1
    f["rsi"] = r
    f["rsi_band"] = ((r >= 45) & (r <= 70)).astype(float)
    f["macd_above"] = (line > sig).astype(float)
    f["macd_rising"] = (hist > hist.shift(3)).astype(float)
    f["squeeze"] = ((width_rank.rolling(20).min() <= 0.2) & (c > mid)).astype(float)
    f["up_down_vol"] = up_vol / down_vol.replace(0, np.nan)
    f["low_vol"] = -c.pct_change().rolling(60).std()
    f["high_52w"] = c / c.rolling(252).max()
    f["fwd"] = c.shift(-FWD_DAYS) / c - 1
    # 현재 규칙의 '매수 관심' 신호 재현 (기술 점수 조건은 아래에서 별도 계산)
    f["buy_rule"] = ((c > ma200) & (ma50 > ma200) & (r >= 45) & (r <= 72) & (line > sig)).astype(float)
    return f


def fundamentals_asof(row: dict, date: pd.Timestamp) -> dict | None:
    """그 날짜에 공시돼 있던 사업보고서 기준 재무. 없으면 None."""
    if date >= pd.Timestamp("2026-04-01"):
        cur, prev = "", "_prev"          # 2025년 보고서
    elif date >= pd.Timestamp("2025-04-01"):
        cur, prev = "_prev", "_prev2"    # 2024년 보고서
    else:
        return None
    g = lambda k: row.get(k)  # noqa: E731
    out = {
        "net_income": g("net_income" + cur), "op_income": g("op_income" + cur),
        "op_income_prev": g("op_income" + prev), "revenue": g("revenue" + cur),
        "revenue_prev": g("revenue" + prev), "equity": g("equity" + cur),
        "equity_prev": g("equity" + prev), "liabilities": g("liabilities" + cur),
    }
    if any(v is None or (isinstance(v, float) and math.isnan(v)) for k, v in out.items() if k != "equity_prev"):
        return None
    return out


# ---------------------------------------------------------------- 패널

def month_ends(index: pd.DatetimeIndex, start: str, end: str) -> list[pd.Timestamp]:
    s = pd.Series(index, index=index)
    s = s[(s >= start) & (s <= end)]
    return list(s.groupby([s.index.year, s.index.month]).max())


def build_panel(prices, dart_rows, shares, sectors, dates) -> pd.DataFrame:
    fund_by_code = {r["code"]: r for r in dart_rows}
    rows = []
    for code, df in prices.items():
        f = daily_features(df)
        f = f.reindex(f.index.union(dates)).ffill(limit=3).loc[dates]
        for d, x in f.iterrows():
            if not np.isfinite(x["close"]) or not np.isfinite(x["fwd"]):
                continue
            rec = {"date": d, "code": code, "sector": sectors.get(code), **x.to_dict()}
            rec["market_cap"] = shares.get(code, np.nan) * x["close"]
            fr = fund_by_code.get(code)
            fa = fundamentals_asof(fr, d) if fr else None
            if fa:
                eq_avg = np.nanmean([fa["equity"], fa["equity_prev"] if fa["equity_prev"] is not None else np.nan])
                rec.update(
                    has_fund=True,
                    net_income=fa["net_income"], op_income=fa["op_income"], revenue=fa["revenue"], equity=fa["equity"],
                    per=rec["market_cap"] / fa["net_income"], pbr=rec["market_cap"] / fa["equity"],
                    roe=fa["net_income"] / eq_avg, debt_ratio=fa["liabilities"] / fa["equity"] * 100,
                    rev_growth=scoring.cagr(fa["revenue"], fa["revenue_prev"], 1),
                    op_growth=scoring.cagr(fa["op_income"], fa["op_income_prev"], 1),
                )
            rows.append(rec)
    return pd.DataFrame(rows)


def universe_filter(p: pd.DataFrame, need_fund: bool) -> pd.DataFrame:
    m = (p["market_cap"] >= MIN_MCAP) & (p["trading_value_20"] >= MIN_TRADING_VALUE)
    if need_fund:
        m &= p.get("has_fund", False) == True  # noqa: E712
        m &= (p["net_income"] > 0) & (p["op_income"] > 0) & (p["equity"] > 0) & (p["revenue"] > 0)
    return p[m.fillna(False)]


# ---------------------------------------------------------------- 평가

def factor_scores(p: pd.DataFrame, factors: dict[str, int], sector_relative=("per", "pbr")) -> pd.DataFrame:
    """날짜별 백분위(0~1, 방향 반영). 없으면 0.5(중립)."""
    out = pd.DataFrame(index=p.index)
    for f, sign in factors.items():
        by = ["date", "sector"] if f in sector_relative else ["date"]
        pct = p.groupby(by)[f].rank(pct=True, ascending=sign > 0)
        if f in sector_relative:  # 업종 종목이 적으면 전체 기준
            size = p.groupby(by)[f].transform("count")
            overall = p.groupby("date")[f].rank(pct=True, ascending=sign > 0)
            pct = pct.where(size >= 5, overall)
        out[f] = pct.fillna(0.5)
    return out


def ic_table(p: pd.DataFrame, scores: pd.DataFrame) -> pd.DataFrame:
    fwd_rank = p.groupby("date")["fwd"].rank(pct=True)
    rows = []
    for f in scores.columns:
        ics = pd.concat([scores[f], fwd_rank, p["date"]], axis=1, keys=["s", "r", "date"]).groupby("date").apply(
            lambda g: g["s"].corr(g["r"]) if g["s"].nunique() > 1 else np.nan, include_groups=False).dropna()
        q = pd.concat([scores[f], p["fwd"], p["date"]], axis=1, keys=["s", "fwd", "date"])
        q["bucket"] = q.groupby("date")["s"].transform(lambda s: pd.qcut(s.rank(method="first"), 5, labels=False))
        spread = q.groupby(["date", "bucket"])["fwd"].mean().unstack()
        ls = (spread[4] - spread[0]) if 4 in spread and 0 in spread else pd.Series(dtype=float)
        rows.append({
            "factor": f, "ic_mean": ics.mean(), "ic_std": ics.std(), "icir": ics.mean() / ics.std() if ics.std() > 0 else np.nan,
            "t": ics.mean() / ics.std() * math.sqrt(len(ics)) if ics.std() > 0 else np.nan,
            "hit": (ics > 0).mean(), "months": len(ics),
            "q5_minus_q1_monthly": ls.mean(),
        })
    return pd.DataFrame(rows).set_index("factor").sort_values("icir", ascending=False)


def fit_weights(ic: pd.DataFrame, min_t: float = 1.0) -> dict[str, float]:
    """학습 구간에서 t값이 min_t 이상인 양의 IC 지표만, ICIR에 비례한 가중치(합 100)."""
    good = ic[(ic["icir"] > 0) & (ic["t"] >= min_t)]["icir"]
    if good.empty:
        return {}
    w = good / good.sum() * 100
    return {k: round(float(v), 1) for k, v in w.items()}


def portfolio(p: pd.DataFrame, score: pd.Series, top: int = 20) -> pd.Series:
    """날짜별 점수 상위 top 종목 동일가중 다음 달 수익률."""
    q = pd.concat([score, p["fwd"], p["date"]], axis=1, keys=["s", "fwd", "date"])
    return q.groupby("date").apply(lambda g: g.nlargest(top, "s")["fwd"].mean(), include_groups=False)


def summarize(r: pd.Series) -> dict:
    r = r.dropna()
    if r.empty:
        return {}
    cum = float((1 + r).prod() - 1)
    return {"months": int(len(r)), "avg_monthly": float(r.mean()), "cum": cum,
            "hit": float((r > 0).mean()), "worst": float(r.min())}


def combined(scores: pd.DataFrame, weights: dict[str, float]) -> pd.Series:
    if not weights:
        return pd.Series(0.5, index=scores.index)
    tot = sum(weights.values())
    return sum(scores[k] * w for k, w in weights.items()) / tot


def current_model_score(p: pd.DataFrame) -> pd.Series:
    """지금 앱에 들어간 규칙(재무 60 + 기술 40)을 백테스트 데이터로 재현."""
    fund_w = {k: scoring.WEIGHTS[k] for k in ("per", "pbr", "roe", "debt_ratio")}
    fund_w.update(rev_growth=scoring.WEIGHTS["rev_cagr"], op_growth=scoring.WEIGHTS["op_cagr"])
    fs = combined(factor_scores(p, {k: FUND_FACTORS[k] for k in fund_w}), fund_w)
    trend = p["trend"].fillna(0)
    tech = (np.where(p["close"] > p["close"] / (1 + p["dist_ma200"]), 15, 0)
            + np.where(trend >= 2, 10, 0) + np.where(trend >= 3, 5, 0))
    tech = tech + 25 * p.groupby("date")["mom_12_1"].rank(pct=True).fillna(0)
    r = p["rsi"]
    tech = tech + np.select([(r >= 45) & (r <= 70), (r > 70) & (r <= 75), (r >= 35) & (r < 45)], [10, 5, 3], 0)
    tech = tech + 5 * p["macd_above"] + 5 * p["macd_rising"] + 5 * p["squeeze"]
    udv = p["up_down_vol"]
    tech = tech + np.where(udv >= 1.2, 10, np.where(udv >= 1.0, 5, 0))
    # 수급(10점)은 과거 데이터가 없어 0점
    return 0.6 * fs * 100 + 0.4 * tech


# ---------------------------------------------------------------- 실행

def run(args) -> dict:
    dart = json.loads(Path(args.dart_cache).read_text(encoding="utf-8"))
    market, _ = mk.fetch_market()
    market = market[[scoring.is_common_stock(c, n) for c, n in zip(market["code"], market["name"])]]
    shares = (market.set_index("code")["market_cap"] / market.set_index("code")["price"]).to_dict()
    codes = sorted(set(market["code"]))
    sectors = mk.fetch_industries()

    log.info("일봉 수집 %d종목", len(codes))
    prices = load_prices(codes, Path(args.cache), args.start, args.end)
    ref = prices.get("005930")
    all_dates = month_ends(ref.index, args.tech_from, args.end)
    dates = [d for d in all_dates if d + pd.Timedelta(days=35) <= ref.index[-1]]  # 1개월 뒤 수익률이 있어야 함

    log.info("패널 생성: %d개월", len(dates))
    panel = build_panel(prices, dart["fundamentals"], shares, sectors, pd.DatetimeIndex(dates))

    report = {"generated": pd.Timestamp.now(tz="Asia/Seoul").isoformat(timespec="minutes"),
              "fwd_days": FWD_DAYS, "universe": "현재 상장 보통주, 시총 1,000억·20일 평균 거래대금 1억 이상"}

    # 1) 기술 지표: 긴 구간 (재무 없이)
    tp = universe_filter(panel, need_fund=False)
    ts = factor_scores(tp, TECH_FACTORS, sector_relative=())
    tech_ic = ic_table(tp, ts)
    report["tech_period"] = [str(tp["date"].min().date()), str(tp["date"].max().date())]
    report["tech_ic"] = tech_ic.round(4).reset_index().to_dict("records")

    # 2) 재무+기술: 재무 공시가 있는 구간
    fp = universe_filter(panel, need_fund=True)
    all_factors = {**FUND_FACTORS, **TECH_FACTORS}
    fs = factor_scores(fp, all_factors)
    full_ic = ic_table(fp, fs)
    report["full_period"] = [str(fp["date"].min().date()), str(fp["date"].max().date())]
    report["full_ic"] = full_ic.round(4).reset_index().to_dict("records")

    # 3) 학습/검증 분할: 기술은 긴 구간 앞부분, 재무는 짧은 구간 앞부분으로 가중치 학습
    split = pd.Timestamp(args.split)
    tech_train_ic = ic_table(tp[tp["date"] < split], ts[tp["date"] < split])
    full_train = fp["date"] < split
    full_train_ic = ic_table(fp[full_train], fs[full_train]) if full_train.sum() else full_ic
    w_tech = fit_weights(tech_train_ic)
    w_fund = fit_weights(full_train_ic.loc[list(FUND_FACTORS)], min_t=0.5)
    report["split"] = str(split.date())

    # 재무:기술 비율은 학습 구간 포트폴리오 성과로 선택
    best, blend_rows = None, []
    for fw in [i / 10 for i in range(11)]:
        s = fw * combined(fs, w_fund) + (1 - fw) * combined(fs, w_tech)
        r = portfolio(fp[full_train], s[full_train], args.top) if full_train.sum() else portfolio(fp, s, args.top)
        m = summarize(r)
        blend_rows.append({"fund": fw, **m})
        if m and (best is None or m["avg_monthly"] > best[1]):
            best = (fw, m["avg_monthly"])
    report["blend_search_train"] = blend_rows
    fund_share = best[0] if best else 0.5
    weights = {k: round(v * fund_share, 1) for k, v in w_fund.items()}
    weights.update({k: round(v * (1 - fund_share), 1) for k, v in w_tech.items()})
    report["weights"] = weights
    report["fund_share"] = fund_share

    # 4) 검증 구간 성과 비교
    test = fp["date"] >= split
    bench = fp[test].groupby("date")["fwd"].mean()
    new_score = combined(fs, weights)
    old_score = current_model_score(fp)
    report["test"] = {
        "benchmark_equal_weight": summarize(bench),
        "current_model_top": summarize(portfolio(fp[test], old_score[test], args.top)),
        "calibrated_top": summarize(portfolio(fp[test], new_score[test], args.top)),
    }
    report["full_period_perf"] = {
        "benchmark_equal_weight": summarize(fp.groupby("date")["fwd"].mean()),
        "current_model_top": summarize(portfolio(fp, old_score, args.top)),
        "calibrated_top": summarize(portfolio(fp, new_score, args.top)),
    }
    # 기술 신호(매수 관심 규칙) 긴 구간 검증
    rule = tp.groupby(["date", "buy_rule"])["fwd"].mean().unstack()
    report["buy_rule_long"] = {"with_rule": summarize(rule.get(1.0, pd.Series(dtype=float))),
                               "without_rule": summarize(rule.get(0.0, pd.Series(dtype=float)))}
    return report


def main(argv=None):
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    p = argparse.ArgumentParser()
    p.add_argument("--dart-cache", required=True)
    p.add_argument("--cache", default=".bt_cache")
    p.add_argument("--start", default="20180101")
    p.add_argument("--end", default=pd.Timestamp.now().strftime("%Y%m%d"))
    p.add_argument("--tech-from", default="2019-01-01")
    p.add_argument("--split", default="2026-01-01", help="이 날짜 전은 학습, 이후는 검증")
    p.add_argument("--top", type=int, default=20)
    p.add_argument("--out", default="backtest")
    args = p.parse_args(argv)
    report = run(args)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=1, default=float), encoding="utf-8")
    log.info("저장: %s", out / "report.json")


if __name__ == "__main__":
    main()
