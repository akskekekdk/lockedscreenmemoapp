import numpy as np
import pandas as pd
import pytest

from screener import model


def test_price_factors_match_backtest_definitions():
    close = pd.Series(np.arange(1, 301, dtype=float))
    f = model.price_factors(pd.DataFrame({"close": close}))
    assert f["mom_12_1"] == pytest.approx(close.iloc[-22] / close.iloc[-253] - 1)
    assert f["vol_60"] == pytest.approx(close.pct_change().iloc[-60:].std())
    short = model.price_factors(pd.DataFrame({"close": close[:100]}))
    assert np.isnan(short["mom_12_1"]) and not np.isnan(short["vol_60"])


def test_score_weights_and_directions():
    df = pd.DataFrame({
        "sector": ["a"] * 6,
        "per": [5, 10, 15, 20, 25, 30], "pbr": [0.5, 1, 1.5, 2, 2.5, 3],
        "mom_12_1": [0.6, 0.5, 0.4, 0.3, 0.2, 0.1], "vol_60": [0.01, 0.02, 0.03, 0.04, 0.05, 0.06],
    }, index=list("ABCDEF"))
    r = model.score(df)
    assert list(r.index) == list("ABCDEF") and list(r["rank"]) == [1, 2, 3, 4, 5, 6]
    assert r.loc["A", "score"] == pytest.approx(100)
    assert r["score"].between(0, 100).all()
    # 값이 없으면 중립(50점)
    df.loc["F", "mom_12_1"] = np.nan
    assert model.score(df).loc["F", "s_momentum"] == 50


def ranked(prices, ranks):
    return pd.DataFrame({"name": list(prices), "price": list(prices.values()), "rank": ranks}, index=list(prices))


def test_update_holdings_keeps_until_rank_or_stop():
    r = ranked({c: 100.0 for c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ"}, list(range(1, 27)))
    held, ev = model.update_holdings([], r, "2026-09-29")
    assert [h["code"] for h in held] == list("ABCDEFGHIJ") and len(ev) == 10

    # A는 25위로 밀림(교체), B는 -15% 손절, C는 18위(유지), 새로 2종목 편입
    prices = {c: 100.0 for c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ"}
    prices["B"] = 84.0
    order = list("KLDEFGHIJMNOPQRBSCTUVWXAYZ")
    r2 = pd.DataFrame({"name": order, "price": [prices[c] for c in order], "rank": range(1, 27)}, index=order)
    held2, ev2 = model.update_holdings(held, r2, "2026-10-30")
    codes = [h["code"] for h in held2]
    assert "A" not in codes and "B" not in codes and "C" in codes and codes[-2:] == ["K", "L"]
    reasons = {e["code"]: e["reason"] for e in ev2}
    assert "순위" in reasons["A"] and "손절" in reasons["B"]
    assert next(h for h in held2 if h["code"] == "C")["entry_date"] == "2026-09-29"


def test_signals():
    r = ranked({"A": 1, "B": 1, "C": 1}, [1, 15, 30])
    assert list(model.signals(r, {"A"})) == ["매수 관심", "후보", "관망"]
