"""백테스트로 정한 점수 모델과 보유 종목 관리 규칙.

검증(2024-04 ~ 2026-07, 매달 말 리밸런싱, 거래비용 0.3% 반영, `screener/backtest.py`)에서
다음 1개월 수익률을 가장 꾸준히 설명한 세 가지만 쓴다.

  점수 = 가치 40% + 모멘텀 40% + 저변동성 20%   (각 지표는 조건 통과 종목 안에서의 백분위)
    가치     : 업종 안에서 PBR·PER이 낮은 순 (두 백분위의 평균)
    모멘텀   : 12-1개월 수익률 (최근 1개월은 단기 반전이 강해 제외)
    저변동성 : 최근 60거래일 일간 수익률 표준편차가 낮은 순

효과가 없거나 역효과였던 것(그래서 점수에서 뺀 것)
  RSI·MACD·볼린저·200일선 추세·거래량 급증·최근 1~3개월 급등, ROE·성장률·부채비율,
  시장 국면에 따른 비중 축소, 2×ATR 손절 → 한국 시장의 단기 반전 때문에 대부분 손해였다.

보유 규칙
  점수 상위 HOLD개를 동일 비중으로 보유하고, 보유 종목은 순위가 KEEP_RANK 밖으로 밀리거나
  매수가 대비 STOP만큼 떨어질 때만 교체한다(매매 횟수와 비용을 줄인다).
"""
from __future__ import annotations

import math

import numpy as np
import pandas as pd

from . import scoring

WEIGHTS = {"value": 40, "momentum": 40, "low_vol": 20}
HOLD = 10
KEEP_RANK = 20
STOP = 0.15

SIGNAL_HOLD = "매수 관심"   # 보유 목록(동일 비중)
SIGNAL_NEXT = "후보"        # 상위권이지만 아직 보유 목록에 빈자리가 없음
SIGNAL_WAIT = "관망"


def price_factors(daily: pd.DataFrame) -> dict:
    """일봉(오래된 → 최근)으로 모멘텀·변동성과 참고 지표를 계산한다."""
    c = daily["close"].astype(float).reset_index(drop=True)
    n = len(c)
    nan = math.nan
    out = {
        "mom_12_1": c.iloc[-22] / c.iloc[-253] - 1 if n >= 253 else nan,
        "vol_60": c.pct_change().iloc[-60:].std() if n >= 61 else nan,
        "ret_1m": c.iloc[-1] / c.iloc[-22] - 1 if n >= 22 else nan,
        "ret_3m": c.iloc[-1] / c.iloc[-64] - 1 if n >= 64 else nan,
        "high_52w": c.iloc[-1] / c.iloc[-252:].max() if n >= 20 else nan,
        "dist_ma200": c.iloc[-1] / c.iloc[-200:].mean() - 1 if n >= 200 else nan,
    }
    return {k: (float(v) if v is not None and np.isfinite(v) else nan) for k, v in out.items()}


def _pct(s: pd.Series, lower_is_better: bool) -> pd.Series:
    """0~1 백분위, 값이 없으면 0.5(중립) — 백테스트와 같은 처리."""
    return s.rank(pct=True, ascending=not lower_is_better).fillna(0.5)


def score(df: pd.DataFrame) -> pd.DataFrame:
    """조건을 통과한 종목 전체에 점수(0~100)를 매긴다. df 열: sector, per, pbr, mom_12_1, vol_60."""
    df = df.copy()
    per = scoring._sector_percentile(df, "per") / 100
    pbr = scoring._sector_percentile(df, "pbr") / 100
    df["s_value"] = ((per.where(df["per"].notna(), 0.5) + pbr.where(df["pbr"].notna(), 0.5)) / 2) * 100
    df["s_momentum"] = _pct(df["mom_12_1"], lower_is_better=False) * 100
    df["s_low_vol"] = _pct(df["vol_60"], lower_is_better=True) * 100
    total = sum(WEIGHTS.values())
    df["score"] = (df["s_value"] * WEIGHTS["value"] + df["s_momentum"] * WEIGHTS["momentum"]
                   + df["s_low_vol"] * WEIGHTS["low_vol"]) / total
    df = df.sort_values("score", ascending=False)
    df["rank"] = range(1, len(df) + 1)
    return df


def update_holdings(prev: list[dict], ranked: pd.DataFrame, today: str) -> tuple[list[dict], list[dict]]:
    """보유 목록을 갱신한다. ranked: 종목코드 인덱스, 열 rank·name·price.

    반환: (새 보유 목록, 이번에 일어난 변경 목록)
    """
    held, events = [], []
    for h in prev:
        code = h["code"]
        if code not in ranked.index:
            events.append({"type": "out", "code": code, "name": h["name"], "reason": "조건 탈락(적자·거래정지 등)"})
            continue
        row = ranked.loc[code]
        price = float(row["price"])
        if price <= h["entry_price"] * (1 - STOP):
            events.append({"type": "out", "code": code, "name": h["name"],
                           "reason": f"손절(매수가 대비 -{int(STOP * 100)}%)", "return": price / h["entry_price"] - 1})
            continue
        if row["rank"] > KEEP_RANK:
            events.append({"type": "out", "code": code, "name": h["name"],
                           "reason": f"순위 {int(row['rank'])}위로 하락", "return": price / h["entry_price"] - 1})
            continue
        held.append(h)
    held_codes = {h["code"] for h in held}
    for code, row in ranked.iterrows():
        if len(held) >= HOLD:
            break
        if code in held_codes:
            continue
        held.append({"code": code, "name": row["name"], "entry_price": float(row["price"]), "entry_date": today})
        events.append({"type": "in", "code": code, "name": row["name"], "reason": f"점수 {int(row['rank'])}위 편입"})
    return held, events


def signals(ranked: pd.DataFrame, held_codes: set[str]) -> pd.Series:
    return pd.Series([
        SIGNAL_HOLD if code in held_codes else SIGNAL_NEXT if r <= KEEP_RANK else SIGNAL_WAIT
        for code, r in zip(ranked.index, ranked["rank"])
    ], index=ranked.index)
