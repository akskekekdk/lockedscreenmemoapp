import math

import numpy as np
import pandas as pd
import pytest

from screener import scoring
from screener import technical as ta
from tests.test_screener import trending_daily


def test_rsi_extremes():
    up = pd.Series(np.arange(1, 60, dtype=float))
    down = up[::-1].reset_index(drop=True)
    assert ta.rsi(up).iloc[-1] == 100
    assert ta.rsi(down).iloc[-1] < 1


def test_indicators_need_enough_history():
    assert ta.indicators(trending_daily(n=100)) is None


def test_uptrend_scores_high_and_is_buy_or_hot():
    ind = ta.indicators(trending_daily())
    assert ind["close"] > ind["ma200"] and ind["ma50"] > ind["ma200"]
    pts = ta.tech_points(ind, 0.9, {"foreign": 1, "institution": 1})
    assert pts["trend"] == 30 and pts["flow"] == 10
    score = sum(pts.values())
    assert ta.signal(ind, score) in (ta.SIGNAL_BUY, ta.SIGNAL_HOT, ta.SIGNAL_WAIT)


def test_downtrend_is_broken():
    ind = ta.indicators(trending_daily(step=-0.004))
    assert ta.signal(ind, 90) == ta.SIGNAL_BROKEN
    assert ta.tech_points(ind, 0.1, None)["trend"] == 0


def test_overheated_signal():
    ind = ta.indicators(trending_daily())
    ind.update(rsi=80)
    assert ta.signal(ind, 90) == ta.SIGNAL_HOT


def test_stop_loss_bounded():
    ind = {"close": 100.0, "atr": 1.0}
    assert ta.stop_loss(ind) == (pytest.approx(95.0), 0.05)  # 2×ATR=2% → 최소 5%
    ind = {"close": 100.0, "atr": 10.0}
    assert ta.stop_loss(ind) == (pytest.approx(90.0), 0.10)  # 20% → 최대 10%


def test_position_sizes_respect_exposure_and_caps():
    picks = [{"code": str(i), "stop_pct": 0.05} for i in range(12)]
    sized = ta.position_sizes(picks, 0.6)
    assert len(sized) == ta.MAX_HOLDINGS
    assert sum(p["weight"] for p in sized) == pytest.approx(0.6)
    assert max(p["weight"] for p in sized) <= ta.MAX_WEIGHT
    one = ta.position_sizes([{"code": "a", "stop_pct": 0.08}], 1.0)
    assert one[0]["weight"] == pytest.approx(0.125)  # 1% ÷ 8%


def test_market_regime():
    up, down = trending_daily(seed=1), trending_daily(step=-0.004, seed=2)
    fx_flat = pd.Series([1350.0] * 30)
    assert ta.market_regime({"KOSPI": up, "KOSDAQ": up}, fx_flat, None)["regime"] == "상승장"
    assert ta.market_regime({"KOSPI": down, "KOSDAQ": down}, fx_flat, None)["exposure"] == 0.3
    assert ta.market_regime({"KOSPI": up, "KOSDAQ": down}, fx_flat, None)["regime"] == "중립"
    fx_spike = pd.Series(np.linspace(1300, 1400, 30))
    r = ta.market_regime({"KOSPI": up, "KOSDAQ": up}, fx_spike, None)
    assert r["exposure"] == pytest.approx(0.8) and "warning" in r["usdkrw"]


def test_sector_relative_valuation():
    df = pd.DataFrame({
        "sector": ["은행"] * 5 + ["바이오"] * 5,
        "per": [3, 4, 5, 6, 7, 30, 40, 50, 60, 70],
    })
    s = scoring._sector_percentile(df, "per")
    # 각 업종에서 가장 싼 종목이 똑같이 최고점
    assert s.iloc[0] == s.iloc[5] == 100
    small = pd.DataFrame({"sector": ["a", "a", "b"], "per": [1, 2, 3]})
    assert list(scoring._sector_percentile(small, "per")) == list(scoring._percentile(small["per"], True))
