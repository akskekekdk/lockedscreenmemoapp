"""국장 스크리너 실행.

1) 시세·재무 수집 → 흑자·유동성 조건 통과 종목 선정
2) 통과 종목 전체의 일봉으로 모멘텀·변동성 계산
3) 백테스트로 정한 점수(가치 40 + 모멘텀 40 + 저변동 20, screener/model.py)로 순위
4) 보유 목록(10종목 동일 비중) 갱신: 20위 밖·-15% 손절일 때만 교체
5) 정적 사이트용 JSON 출력 (시장 국면·수급·재무 지표는 참고 정보)

사용법: DART_API_KEY=... python -m screener.main --out public
"""
from __future__ import annotations

import argparse
import json
import logging
import math
import os
import shutil
import sys
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import pandas as pd

from . import market as mk
from . import scoring
from . import model
from . import technical as ta
from .dart import MULTI_BATCH, DartClient, fundamentals_from_rows, latest_fiscal_year

log = logging.getLogger("screener")
KST = ZoneInfo("Asia/Seoul")
WEB_DIR = Path(__file__).resolve().parent.parent / "web"
HISTORY_KEEP = 120  # 하루 2회 × 약 3달
CACHE_MAX_AGE = timedelta(days=7)  # 연간 재무는 자주 안 바뀌므로 1주일간 재사용

OUTPUT_FIELDS = [
    "code", "name", "market", "sector", "price", "change_pct", "market_cap",
    "per", "pbr", "roe", "rev_cagr", "op_cagr", "debt_ratio", "fcf", "fcf_yield",
    "flags", "fs_div", "year",
]


def parse_args(argv=None):
    p = argparse.ArgumentParser(description="국장 스크리너")
    p.add_argument("--out", default="public", help="사이트 출력 폴더")
    p.add_argument("--top", type=int, default=30, help="발표할 종목 수")
    p.add_argument("--min-market-cap", type=float, default=1000e8, help="최소 시가총액(원)")
    p.add_argument("--min-trading-value", type=float, default=1e8, help="최소 당일 거래대금(원)")
    p.add_argument("--force", action="store_true", help="휴장일이어도 실행")
    return p.parse_args(argv)


def is_market_day(market: pd.DataFrame, source: str, now: datetime) -> bool:
    """네이버 시세의 마지막 체결일이 오늘인지로 휴장일을 판단한다."""
    if now.weekday() >= 5:
        return False
    if source != "naver":
        return True
    dates = market["traded_at"].dropna().str[:10]
    return not dates.empty and dates.mode().iloc[0] == now.strftime("%Y-%m-%d")


class NaverFeeds:
    """기술적 분석·시장 국면용 데이터. 테스트에서는 가짜로 바꿔 끼운다."""

    def __init__(self, today):
        self.today = pd.Timestamp(today.date())

    def sectors(self):
        return mk.fetch_industries()

    def daily(self, code):
        return mk.fetch_daily(code, self.today)

    def index_daily(self, name):
        return mk.fetch_daily(name, self.today, kind="index")

    def flow(self, code):
        return mk.fetch_investor_flow(code)

    def usdkrw(self):
        return mk.fetch_usdkrw()

    def market_flow(self):
        return mk.fetch_market_flow()


def _safe(what: str, fn, default=None):
    """보조 데이터는 실패해도 전체 분석을 멈추지 않는다."""
    try:
        return fn()
    except Exception as e:  # noqa: BLE001
        log.warning("%s 실패: %s", what, e)
        return default


class DartCache:
    """DART 결과를 출력 폴더(gh-pages)에 저장해 다음 실행에서 재사용한다."""

    def __init__(self, dart: DartClient, cache_dir: Path, year: int, now: datetime):
        self.dart, self.year, self.now = dart, year, now
        self.path = cache_dir / f"dart-{year}.json"
        self._corp: dict[str, str] | None = None
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            fresh = now - datetime.fromisoformat(data["fetched_at"]) < CACHE_MAX_AGE
        except (FileNotFoundError, json.JSONDecodeError, KeyError, ValueError):
            data, fresh = {}, False
        if not fresh:
            data = {"fetched_at": now.isoformat(timespec="minutes"), "fundamentals": [], "missing": [], "cash": {}}
        self.data = data

    def corp(self) -> dict[str, str]:
        if self._corp is None:
            self._corp = self.dart.corp_codes()
        return self._corp

    def fundamentals(self, codes: list[str]) -> pd.DataFrame:
        """캐시에 없는 회사만 묶음 단위로 조회한다. 최신 사업연도에 없으면 전년도로 보충.

        묶음마다 결과를 캐시에 넣으므로 중간에 끊겨도 받은 만큼은 남는다.
        """
        known = {r["code"] for r in self.data["fundamentals"]} | set(self.data["missing"])
        todo = [c for c in codes if c not in known]
        if not todo:
            log.info("DART 재무: 캐시 사용(%s 조회분)", self.data["fetched_at"])
        else:
            corp = self.corp()
            self.data["missing"] += [c for c in todo if c not in corp]
            todo = [c for c in todo if c in corp]
            batches = range(0, len(todo), MULTI_BATCH)
            for n, i in enumerate(batches, start=1):
                remaining = todo[i:i + MULTI_BATCH]
                for y in (self.year, self.year - 1):
                    if not remaining:
                        break
                    f = fundamentals_from_rows(self.dart.multi_accounts([corp[c] for c in remaining], y))
                    if not f.empty:
                        self.data["fundamentals"] += json.loads(f.to_json(orient="records"))
                        remaining = [c for c in remaining if c not in set(f["code"])]
                self.data["missing"] += remaining
                if n % 5 == 0 or n == len(batches):
                    log.info("DART 재무 %d/%d 묶음 (누적 %d개사)", n, len(batches), len(self.data["fundamentals"]))
        wanted = set(codes)
        return pd.DataFrame([r for r in self.data["fundamentals"] if r["code"] in wanted])

    def cash_flows(self, rows: pd.DataFrame) -> pd.DataFrame:
        out = {}
        for code, row in rows.iterrows():
            if code not in self.data["cash"]:
                ocf, fcf = self.dart.cash_flow(self.corp()[code], int(row["year"]))
                self.data["cash"][code] = [None if math.isnan(ocf) else ocf, None if math.isnan(fcf) else fcf]
            ocf, fcf = self.data["cash"][code]
            out[code] = {"ocf": math.nan if ocf is None else ocf, "fcf": math.nan if fcf is None else fcf}
        return pd.DataFrame.from_dict(out, orient="index", columns=["ocf", "fcf"])

    def save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(self.data, ensure_ascii=False), encoding="utf-8")


def previous_state(out: Path) -> tuple[dict[str, int], list[dict]]:
    """직전 결과의 (종목별 순위, 보유 목록)."""
    try:
        prev = json.loads((out / "data" / "latest.json").read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return {}, []
    holdings = prev.get("holdings") or []
    return {s["code"]: s["rank"] for s in prev.get("stocks", [])}, holdings


def load_price_factors(codes: list[str], feeds, workers: int = 8) -> pd.DataFrame:
    """조건 통과 종목 전체의 일봉을 병렬로 받아 모멘텀·변동성 등을 계산한다."""
    def one(code):
        daily = _safe(f"{code} 일봉", lambda: feeds.daily(code))
        if daily is None or len(daily) < 2:
            return code, None
        return code, model.price_factors(daily)

    rows = {}
    with ThreadPoolExecutor(workers) as ex:
        for code, f in ex.map(one, codes):
            if f:
                rows[code] = f
    return pd.DataFrame.from_dict(rows, orient="index")


def build_regime(feeds) -> dict:
    indices = {}
    for name in ("KOSPI", "KOSDAQ"):
        df = _safe(f"{name} 지수", lambda: feeds.index_daily(name))
        if df is not None and not df.empty:
            indices[name] = df
    regime = ta.market_regime(indices, _safe("환율", feeds.usdkrw), _safe("시장 수급", feeds.market_flow))
    # 백테스트에서 국면에 따른 비중 축소는 수익만 깎았다 → 참고 정보로만 보여주고 비중은 줄이지 않는다.
    regime["exposure"] = 1.0
    if regime.get("usdkrw"):
        regime["usdkrw"].pop("warning", None)
    return regime


def build_report(args, now: datetime, market: pd.DataFrame, source: str, dart: DartClient,
                 prev_ranks: dict[str, int], feeds, cache_dir: Path, prev_holdings: list[dict] | None = None) -> dict:
    universe = market[[scoring.is_common_stock(c, n) for c, n in zip(market["code"], market["name"])]]
    universe = universe[universe["market_cap"] >= args.min_market_cap]
    log.info("시세 %d종목 → 보통주·시총 필터 후 %d종목", len(market), len(universe))

    year = latest_fiscal_year(now)
    cache = DartCache(dart, cache_dir, year, now)
    try:
        fundamentals = cache.fundamentals(universe["code"].tolist())
    finally:
        cache.save()  # 중간에 끊겨도 받은 만큼은 다음 실행에 쓴다
    if fundamentals.empty:
        raise RuntimeError("DART 재무 데이터를 하나도 받지 못했습니다")

    sectors = _safe("업종 분류", feeds.sectors, {}) or {}
    df = universe.merge(fundamentals, on="code")
    df["sector"] = df["code"].map(sectors)
    df = scoring.add_metrics(df)
    passed = scoring.apply_filters(df, args.min_market_cap, args.min_trading_value).set_index("code", drop=False)
    log.info("재무 매칭 %d종목 → 흑자·유동성 필터 후 %d종목", len(df), len(passed))

    factors = load_price_factors(passed.index.tolist(), feeds)
    log.info("가격 지표 %d/%d종목", len(factors), len(passed))
    ranked = model.score(passed.join(factors, how="left"))

    # 상위 종목만 FCF(참고·경고용)와 수급(참고용)을 조회한다.
    top = ranked.head(args.top)
    try:
        cash = cache.cash_flows(top)
    finally:
        cache.save()
    top = scoring.add_cash_flags(top, cash)

    holdings, events = model.update_holdings(prev_holdings or [], ranked, now.strftime("%Y-%m-%d"))
    held = {h["code"] for h in holdings}
    sig = model.signals(ranked, held)
    regime = build_regime(feeds)

    stocks = []
    for code, row in top.iterrows():
        flow = _safe(f"{code} 수급", lambda: feeds.flow(code)) or {}
        item = {f: scoring.clean(row.get(f)) for f in OUTPUT_FIELDS}
        for f in ("score", "s_value", "s_momentum", "s_low_vol", "mom_12_1", "vol_60", "ret_1m", "ret_3m",
                  "high_52w", "dist_ma200"):
            item[f] = scoring.clean(row.get(f))
        item.update(rank=int(row["rank"]), prev_rank=prev_ranks.get(code), signal=sig[code],
                    foreign_20d=scoring.clean(flow.get("foreign")), institution_20d=scoring.clean(flow.get("institution")))
        stocks.append(item)

    price = ranked["price"]
    portfolio = []
    for h in holdings:
        p = float(price.get(h["code"], h["entry_price"]))
        portfolio.append({**h, "price": p, "return": p / h["entry_price"] - 1, "rank": int(ranked.loc[h["code"], "rank"]),
                          "stop": h["entry_price"] * (1 - model.STOP), "weight": round(1 / model.HOLD, 4)})

    return {
        "generated_at": now.isoformat(timespec="minutes"),
        "session": "am" if now.hour < 12 else "pm",
        "price_source": source,
        "fiscal_year": year,
        "model": {"weights": model.WEIGHTS, "hold": model.HOLD, "keep_rank": model.KEEP_RANK, "stop": model.STOP},
        "counts": {"market": len(market), "universe": len(universe), "matched": len(df),
                   "passed": len(passed), "priced": len(factors)},
        "filters": {"min_market_cap": args.min_market_cap, "min_trading_value": args.min_trading_value},
        "regime": regime,
        "holdings": holdings,
        "events": events,
        # 점수를 매긴 전체 종목(내 보유 종목이 30위 밖이어도 추적할 수 있게): 코드 → [순위, 점수, 신호, 가격, 이름]
        "all": {code: [int(r["rank"]), round(float(r["score"]), 1), sig[code], float(r["price"]), r["name"]]
                for code, r in ranked.iterrows()},
        "portfolio": portfolio,
        "stocks": stocks,
    }


def write_site(out: Path, report: dict, now: datetime) -> None:
    data = out / "data"
    (data / "history").mkdir(parents=True, exist_ok=True)
    for f in WEB_DIR.iterdir():
        shutil.copy2(f, out / f.name)

    run_id = f"{now:%Y-%m-%d}-{report['session']}"
    text = json.dumps(report, ensure_ascii=False, indent=1, allow_nan=False)
    (data / "latest.json").write_text(text, encoding="utf-8")
    (data / "history" / f"{run_id}.json").write_text(text, encoding="utf-8")

    index_path = data / "index.json"
    try:
        runs = json.loads(index_path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        runs = []
    runs = [r for r in runs if r != run_id] + [run_id]
    for old in runs[:-HISTORY_KEEP]:
        (data / "history" / f"{old}.json").unlink(missing_ok=True)
    index_path.write_text(json.dumps(runs[-HISTORY_KEEP:]), encoding="utf-8")


def main(argv=None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    args = parse_args(argv)
    now = datetime.now(KST)
    out = Path(args.out)

    market, source = mk.fetch_market()
    log.info("시세 출처: %s, %d종목", source, len(market))
    if not args.force and not is_market_day(market, source, now):
        log.info("오늘(%s)은 휴장일이라 건너뜁니다", now.date())
        return 0

    dart = DartClient(os.environ.get("DART_API_KEY", ""))
    prev_ranks, prev_holdings = previous_state(out)
    report = build_report(args, now, market, source, dart, prev_ranks, NaverFeeds(now), out / "cache", prev_holdings)
    write_site(out, report, now)
    if os.environ.get("GITHUB_OUTPUT"):  # 휴장일에는 게시 단계를 건너뛰도록 알린다
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as f:
            f.write("updated=true\n")
    log.info("완료: 상위 %d종목, 보유 %d종목, 변경 %d건", len(report["stocks"]),
             len(report["portfolio"]), len(report["events"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
