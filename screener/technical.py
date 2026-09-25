"""기술적 지표, 매매 신호, 시장 국면, 손절·비중 계산.

일봉 DataFrame 열: date, open, high, low, close, volume (오래된 날짜 → 최근 순)
"""
from __future__ import annotations

import math

import numpy as np
import pandas as pd

MIN_BARS = 220  # 200일선 + 여유

# 기술 점수 배점 (합계 100)
TECH_POINTS = {
    "trend": 30,      # 200일선·50일선 위, 정배열
    "momentum": 25,   # 12-1개월 수익률(후보 내 백분위)
    "rsi": 10,        # 극단이 아닌 구간
    "macd": 10,       # 시그널 상향 + 히스토그램 확대
    "bollinger": 5,   # 스퀴즈 후 상향 돌파
    "volume": 10,     # 상승일 거래량 우위
    "flow": 10,       # 외국인·기관 20일 순매수
}

SIGNAL_BUY = "매수 관심"
SIGNAL_HOT = "과열 주의"
SIGNAL_BROKEN = "추세 이탈"
SIGNAL_WAIT = "관망"

# 국면별 권장 주식 비중
REGIME_EXPOSURE = {"상승장": 1.0, "중립": 0.6, "하락장": 0.3}
RISK_PER_TRADE = 0.01    # 한 종목 손절 시 전체 자산 손실 한도 1%
MAX_WEIGHT = 0.15        # 한 종목 최대 비중
MAX_HOLDINGS = 10
STOP_MIN, STOP_MAX = 0.05, 0.10  # 손절폭 범위(매수가 대비)


def sma(s: pd.Series, n: int) -> pd.Series:
    return s.rolling(n).mean()


def ema(s: pd.Series, n: int) -> pd.Series:
    return s.ewm(span=n, adjust=False).mean()


def rsi(close: pd.Series, n: int = 14) -> pd.Series:
    """Wilder 방식 RSI."""
    delta = close.diff()
    gain = delta.clip(lower=0).ewm(alpha=1 / n, adjust=False, min_periods=n).mean()
    loss = (-delta.clip(upper=0)).ewm(alpha=1 / n, adjust=False, min_periods=n).mean()
    rs = gain / loss
    return (100 - 100 / (1 + rs)).where(loss > 0, 100.0)


def macd(close: pd.Series, fast: int = 12, slow: int = 26, signal: int = 9):
    line = ema(close, fast) - ema(close, slow)
    sig = ema(line, signal)
    return line, sig, line - sig


def bollinger(close: pd.Series, n: int = 20, k: float = 2.0):
    mid = sma(close, n)
    sd = close.rolling(n).std(ddof=0)
    upper, lower = mid + k * sd, mid - k * sd
    return upper, mid, lower, (upper - lower) / mid


def atr(df: pd.DataFrame, n: int = 14) -> pd.Series:
    prev = df["close"].shift()
    tr = pd.concat([df["high"] - df["low"], (df["high"] - prev).abs(), (df["low"] - prev).abs()], axis=1).max(axis=1)
    return tr.ewm(alpha=1 / n, adjust=False, min_periods=n).mean()


def _f(x) -> float:
    return float(x) if x is not None and np.isfinite(x) else math.nan


def indicators(df: pd.DataFrame) -> dict | None:
    """최신 봉 기준 지표 값. 데이터가 모자라면 None."""
    if len(df) < MIN_BARS:
        return None
    c, v = df["close"], df["volume"]
    ma20, ma50, ma200 = sma(c, 20), sma(c, 50), sma(c, 200)
    r = rsi(c)
    m_line, m_sig, m_hist = macd(c)
    upper, mid, _, width = bollinger(c)
    a = atr(df)

    # 최근 20일 안에 50일선이 200일선을 상향 돌파했나
    above = ma50 > ma200
    golden_recent = bool((above & ~above.shift(fill_value=True)).iloc[-20:].any())

    # 볼린저 스퀴즈: 최근 20일 중 밴드폭이 120일 기준 하위 20% 이하였던 날이 있고, 지금 중심선 위
    width_rank = width.rolling(120).rank(pct=True)
    squeeze_breakout = bool((width_rank.iloc[-20:] <= 0.2).any() and c.iloc[-1] > mid.iloc[-1])

    # 상승일·하락일 거래량 비율 (최근 20일)
    chg = c.diff().iloc[-20:]
    vol = v.iloc[-20:]
    up_vol, down_vol = vol[chg > 0].sum(), vol[chg < 0].sum()
    up_down_volume = up_vol / down_vol if down_vol > 0 else (2.0 if up_vol > 0 else 1.0)

    price = c.iloc[-1]
    return {
        "close": _f(price),
        "ma20": _f(ma20.iloc[-1]), "ma50": _f(ma50.iloc[-1]), "ma200": _f(ma200.iloc[-1]),
        "dist_ma200": _f(price / ma200.iloc[-1] - 1),
        "dist_ma20": _f(price / ma20.iloc[-1] - 1),
        "golden_recent": golden_recent,
        "rsi": _f(r.iloc[-1]),
        "macd_above": bool(m_line.iloc[-1] > m_sig.iloc[-1]),
        "macd_hist_rising": bool(m_hist.iloc[-1] > m_hist.iloc[-4]),
        "bb_upper": _f(upper.iloc[-1]),
        "squeeze_breakout": squeeze_breakout,
        "up_down_volume": _f(up_down_volume),
        "atr": _f(a.iloc[-1]),
        "mom_12_1": _f(c.iloc[-21] / c.iloc[-252] - 1) if len(c) >= 252 else _f(c.iloc[-21] / c.iloc[0] - 1),
        "ret_3m": _f(price / c.iloc[-63] - 1),
    }


def tech_points(ind: dict, momentum_pct: float, flow: dict | None) -> dict:
    """항목별 점수. momentum_pct는 후보 안에서의 12-1개월 수익률 백분위(0~1)."""
    close, ma50, ma200 = ind["close"], ind["ma50"], ind["ma200"]
    trend = (15 if close > ma200 else 0) + (10 if ma50 > ma200 else 0) + (5 if close > ma50 else 0)

    r = ind["rsi"]
    if 45 <= r <= 70:
        rsi_pts = 10
    elif 70 < r <= 75:
        rsi_pts = 5
    elif 35 <= r < 45:
        rsi_pts = 3
    else:
        rsi_pts = 0

    udv = ind["up_down_volume"]
    flow_pts = 0
    if flow:
        flow_pts = (5 if flow.get("foreign", 0) > 0 else 0) + (5 if flow.get("institution", 0) > 0 else 0)

    return {
        "trend": trend,
        "momentum": round(TECH_POINTS["momentum"] * (0 if math.isnan(momentum_pct) else momentum_pct), 1),
        "rsi": rsi_pts,
        "macd": (5 if ind["macd_above"] else 0) + (5 if ind["macd_hist_rising"] else 0),
        "bollinger": 5 if ind["squeeze_breakout"] else 0,
        "volume": 10 if udv >= 1.2 else 5 if udv >= 1.0 else 0,
        "flow": flow_pts,
    }


def signal(ind: dict, tech_score: float) -> str:
    if ind["close"] < ind["ma200"]:
        return SIGNAL_BROKEN
    if ind["rsi"] >= 75 or ind["dist_ma20"] >= 0.15 or ind["close"] > ind["bb_upper"] * 1.03:
        return SIGNAL_HOT
    if ind["ma50"] > ind["ma200"] and 45 <= ind["rsi"] <= 72 and ind["macd_above"] and tech_score >= 60:
        return SIGNAL_BUY
    return SIGNAL_WAIT


def stop_loss(ind: dict) -> tuple[float, float]:
    """(손절가, 손절폭). 2×ATR을 기본으로 하되 매수가 대비 5~10% 안으로 제한."""
    pct = min(max(2 * ind["atr"] / ind["close"], STOP_MIN), STOP_MAX)
    return ind["close"] * (1 - pct), pct


def market_regime(indices: dict[str, pd.DataFrame], usdkrw: pd.Series | None, foreign_flow: dict | None) -> dict:
    """지수 추세 + 환율로 시장 국면과 권장 주식 비중을 정한다."""
    detail, votes = {}, []
    for name, df in indices.items():
        c = df["close"]
        if len(c) < 200:
            continue
        ma50, ma200 = sma(c, 50).iloc[-1], sma(c, 200).iloc[-1]
        up = c.iloc[-1] > ma200 and ma50 > ma200
        down = c.iloc[-1] < ma200 and ma50 < ma200
        votes.append(1 if up else -1 if down else 0)
        detail[name] = {
            "close": _f(c.iloc[-1]), "ma200": _f(ma200), "dist_ma200": _f(c.iloc[-1] / ma200 - 1),
            "ret_1m": _f(c.iloc[-1] / c.iloc[-21] - 1), "trend": "상승" if up else "하락" if down else "중립",
        }

    total = sum(votes)
    regime = "상승장" if votes and total == len(votes) else "하락장" if total < 0 else "중립"
    exposure = REGIME_EXPOSURE[regime]

    fx = None
    if usdkrw is not None and len(usdkrw) >= 21:
        change = usdkrw.iloc[-1] / usdkrw.iloc[-21] - 1
        fx = {"close": _f(usdkrw.iloc[-1]), "change_1m": _f(change)}
        if change > 0.03:  # 원화 급약세 = 외국인 이탈 위험
            exposure = max(exposure - 0.2, 0.2)
            fx["warning"] = "원화 약세 급격(1개월 +3% 초과) → 비중 20%p 축소"

    return {
        "regime": regime, "exposure": round(exposure, 2), "indices": detail,
        "usdkrw": fx, "foreign_flow": foreign_flow,
    }


def position_sizes(picks: list[dict], exposure: float) -> list[dict]:
    """변동성(손절폭) 기반 비중: 손절 시 자산의 1%만 잃도록, 종목당 15% 이하, 합계는 권장 비중 이하."""
    picks = picks[:MAX_HOLDINGS]
    raw = [min(RISK_PER_TRADE / p["stop_pct"], MAX_WEIGHT) for p in picks]
    total = sum(raw)
    scale = min(1.0, exposure / total) if total > 0 else 0
    return [{**p, "weight": round(w * scale, 4)} for p, w in zip(picks, raw)]
