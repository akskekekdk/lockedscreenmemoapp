"""지표 계산, 필터링, 점수화.

각 지표를 걸러진 종목들 안에서 백분위(0~100)로 바꾼 뒤 가중합한다.
  가격   PER 20 · PBR 10
  수익성 ROE 25
  성장   매출 CAGR 10 · 영업이익 CAGR 15
  안정성 부채비율 10 · FCF 수익률 10 (FCF는 1차 상위 후보에만 조회)
"""
from __future__ import annotations

import math

import numpy as np
import pandas as pd

WEIGHTS = {"per": 20, "pbr": 10, "roe": 25, "rev_cagr": 10, "op_cagr": 15, "debt_ratio": 10}
FCF_WEIGHT = 10
LOWER_IS_BETTER = {"per", "pbr", "debt_ratio"}
TURNAROUND_GROWTH = 1.0  # 적자→흑자 전환은 성장률 100%로 간주


def cagr(current: float, base: float, years: int) -> float:
    if any(map(_missing, (current, base))):
        return math.nan
    if base > 0 and current > 0:
        return (current / base) ** (1 / years) - 1
    if base <= 0 < current:
        return TURNAROUND_GROWTH
    return -1.0


def _missing(x) -> bool:
    return x is None or (isinstance(x, float) and math.isnan(x))


def _growth(row, field: str) -> float:
    if not _missing(row.get(f"{field}_prev2")):
        return cagr(row[field], row[f"{field}_prev2"], 2)
    return cagr(row[field], row.get(f"{field}_prev"), 1)


def add_metrics(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()
    avg_equity = df[["equity", "equity_prev"]].mean(axis=1)  # 전기 자본이 없으면 당기 자본만
    df["roe"] = df["net_income"] / avg_equity
    df["per"] = df["market_cap"] / df["net_income"]
    df["pbr"] = df["market_cap"] / df["equity"]
    df["debt_ratio"] = df["liabilities"] / df["equity"] * 100
    df["rev_cagr"] = df.apply(_growth, axis=1, field="revenue")
    df["op_cagr"] = df.apply(_growth, axis=1, field="op_income")
    return df


def is_common_stock(code: str, name: str) -> bool:
    """보통주만: 종목코드 끝자리 0, 스팩·리츠 제외."""
    return code.endswith("0") and "스팩" not in name and not name.endswith("리츠")


def apply_filters(df: pd.DataFrame, min_market_cap: float, min_trading_value: float) -> pd.DataFrame:
    return df[
        df["trading"]
        & (df["market_cap"] >= min_market_cap)
        & (df["trading_value"] >= min_trading_value)
        & (df["net_income"] > 0)
        & (df["op_income"] > 0)
        & (df["equity"] > 0)
        & (df["revenue"] > 0)
    ]


def _percentile(series: pd.Series, lower_is_better: bool) -> pd.Series:
    """0~100 백분위. 값이 없으면 0점."""
    return (series.rank(pct=True, ascending=not lower_is_better) * 100).fillna(0)


def preliminary_score(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()
    for m in WEIGHTS:
        df[f"s_{m}"] = _percentile(df[m], m in LOWER_IS_BETTER)
    total = sum(WEIGHTS.values())
    df["prelim_score"] = sum(df[f"s_{m}"] * w for m, w in WEIGHTS.items()) / total
    return df.sort_values("prelim_score", ascending=False)


def final_score(candidates: pd.DataFrame, fcf: pd.Series) -> pd.DataFrame:
    """1차 상위 후보에 FCF 수익률을 더해 최종 점수(0~100)를 낸다. FCF 적자는 0점."""
    df = candidates.copy()
    df["fcf"] = fcf.reindex(df.index)
    df["fcf_yield"] = df["fcf"] / df["market_cap"]
    s_fcf = _percentile(df["fcf_yield"], False)
    df["s_fcf"] = s_fcf.where(df["fcf"] > 0, 0)
    base = sum(WEIGHTS.values())
    df["score"] = (df["prelim_score"] * base + df["s_fcf"] * FCF_WEIGHT) / (base + FCF_WEIGHT)
    df["flags"] = df.apply(flags, axis=1)
    return df.sort_values("score", ascending=False)


def flags(row) -> list[str]:
    out = []
    if row["pbr"] < 1 and row["roe"] < 0.08:
        out.append("가치함정 주의")
    if not _missing(row.get("fcf")) and row["fcf"] <= 0:
        out.append("FCF 적자")
    if row["op_cagr"] == TURNAROUND_GROWTH:
        out.append("흑자전환")
    if row["debt_ratio"] > 200:
        out.append("고부채")
    return out


def clean(value):
    """JSON으로 내보낼 수 있게 NaN/inf/numpy 타입을 정리한다."""
    if isinstance(value, (np.floating, float)):
        return None if not np.isfinite(value) else round(float(value), 4)
    if isinstance(value, np.integer):
        return int(value)
    if isinstance(value, np.bool_):
        return bool(value)
    return value
