import json
import math
from datetime import datetime

import numpy as np
import pandas as pd
import pytest

from screener import main as app
from screener import scoring
from screener.dart import cash_flow_from_statement, fundamentals_from_rows, latest_fiscal_year, parse_amount


def dart_row(code, account, cur, prev, prev2="", fs_div="CFS", year="2025"):
    return {
        "stock_code": code, "account_nm": account, "fs_div": fs_div, "bsns_year": year,
        "thstrm_amount": cur, "frmtrm_amount": prev, "bfefrmtrm_amount": prev2,
    }


def company(code, revenue, op, ni, liab, equity, fs_div="CFS"):
    """(당기, 전기, 전전기) 튜플로 한 회사의 DART 행을 만든다."""
    fmt = lambda v: f"{v:,}"  # noqa: E731
    return [
        dart_row(code, "매출액", *map(fmt, revenue), fs_div=fs_div),
        dart_row(code, "영업이익", *map(fmt, op), fs_div=fs_div),
        dart_row(code, "당기순이익(손실)", *map(fmt, ni), fs_div=fs_div),
        dart_row(code, "부채총계", fmt(liab[0]), fmt(liab[1]), fs_div=fs_div),
        dart_row(code, "자본총계", fmt(equity[0]), fmt(equity[1]), fs_div=fs_div),
    ]


def test_parse_amount():
    assert parse_amount("1,234") == 1234
    assert parse_amount("-500") == -500
    assert parse_amount("(300)") == -300
    assert math.isnan(parse_amount("-"))
    assert math.isnan(parse_amount(None))


def test_fundamentals_prefers_consolidated():
    rows = company("000010", (100, 90, 80), (10, 9, 8), (7, 6, 5), (50, 40), (100, 90), fs_div="OFS")
    rows += company("000010", (200, 180, 160), (20, 18, 16), (14, 12, 10), (60, 50), (120, 110))
    f = fundamentals_from_rows(rows).set_index("code")
    assert f.loc["000010", "fs_div"] == "CFS"
    assert f.loc["000010", "revenue"] == 200
    assert f.loc["000010", "revenue_prev2"] == 160
    assert f.loc["000010", "equity_prev"] == 110


def test_fundamentals_falls_back_to_separate():
    rows = company("000020", (100, 90, 80), (10, 9, 8), (7, 6, 5), (50, 40), (100, 90), fs_div="OFS")
    f = fundamentals_from_rows(rows)
    assert f.iloc[0]["fs_div"] == "OFS"


def test_cash_flow_from_statement():
    rows = [
        {"sj_div": "CF", "account_id": "ifrs-full_CashFlowsFromUsedInOperatingActivities", "account_nm": "영업활동현금흐름", "thstrm_amount": "1,000"},
        {"sj_div": "CF", "account_id": "-표준계정코드 미사용-", "account_nm": "유형자산의 취득", "thstrm_amount": "(300)"},
    ]
    assert cash_flow_from_statement(rows) == (1000, 700)
    assert math.isnan(cash_flow_from_statement([])[0])


def test_cagr_cases():
    assert scoring.cagr(121, 100, 2) == pytest.approx(0.1)
    assert scoring.cagr(10, -5, 2) == scoring.TURNAROUND_GROWTH
    assert scoring.cagr(-10, 5, 2) == -1.0
    assert math.isnan(scoring.cagr(10, math.nan, 2))


def test_latest_fiscal_year():
    assert latest_fiscal_year(datetime(2026, 3, 31)) == 2024
    assert latest_fiscal_year(datetime(2026, 4, 1)) == 2025


def test_is_common_stock():
    assert scoring.is_common_stock("005930", "삼성전자")
    assert not scoring.is_common_stock("005935", "삼성전자우")
    assert not scoring.is_common_stock("123450", "하나스팩10호")
    assert not scoring.is_common_stock("330590", "롯데리츠")


def frame(**overrides):
    base = dict(
        code=["A00000", "B00000", "C00000", "D00000"], market_cap=[1e12] * 4, trading=[True] * 4,
        trading_value=[1e9] * 4, revenue=[100.0] * 4, revenue_prev=[90.0] * 4, revenue_prev2=[80.0] * 4,
        op_income=[10.0] * 4, op_income_prev=[9.0] * 4, op_income_prev2=[8.0] * 4,
        net_income=[1e11, 5e10, 2e10, -1e10], equity=[5e11, 5e11, 5e11, 5e11], equity_prev=[5e11] * 4,
        liabilities=[1e11, 5e11, 2e12, 1e11],
    )
    base.update(overrides)
    return pd.DataFrame(base)


def test_scoring_ranks_better_company_first():
    df = scoring.add_metrics(frame())
    passed = scoring.apply_filters(df, 5e11, 1e8)
    assert "D00000" not in set(passed["code"])  # 적자 제외

    ranked = scoring.preliminary_score(passed.set_index("code", drop=False))
    assert list(ranked.index) == ["A00000", "B00000", "C00000"]

    cash = pd.DataFrame({"ocf": [2e11, 1e10, 3e10], "fcf": [5e10, -1e10, 1e10]}, index=["A00000", "B00000", "C00000"])
    final = scoring.final_score(ranked, cash)
    assert final.index[0] == "A00000"
    assert final.loc["B00000", "s_fcf"] == 0
    assert "FCF 적자" in final.loc["B00000", "flags"]
    assert "고부채" in final.loc["C00000", "flags"]
    assert "이익의 질 낮음" in final.loc["B00000", "flags"]  # 영업현금흐름 < 순이익
    assert final["score"].between(0, 100).all()


def trending_daily(n=300, start=100.0, step=0.004, seed=0):
    """완만한 우상향 + 잡음 일봉."""
    rng = np.random.default_rng(seed)
    close = start * np.cumprod(1 + step + rng.normal(0, 0.01, n))
    return pd.DataFrame({
        "date": pd.date_range("2025-06-01", periods=n).strftime("%Y%m%d"),
        "open": close * 0.995, "high": close * 1.01, "low": close * 0.99, "close": close,
        "volume": rng.integers(1_000, 2_000, n).astype(float),
    })


class FakeFeeds:
    def __init__(self, daily=None):
        self._daily = daily or {}

    def sectors(self):
        return {"005930": "반도체", "000660": "반도체"}

    def daily(self, code):
        return self._daily.get(code, trending_daily())

    def index_daily(self, name):
        return trending_daily(seed=1)

    def flow(self, code):
        return {"foreign": 1e9, "institution": -1e9, "days": 20}

    def usdkrw(self):
        return pd.Series([1350.0] * 30)

    def market_flow(self):
        return {"date": "20260925", "foreign": 100.0, "institution": 0.0, "individual": -100.0}


class FakeDart:
    def __init__(self):
        self.multi_calls = 0

    def corp_codes(self):
        return {"005930": "00126380", "000660": "00164779", "035720": "00258801"}

    def multi_accounts(self, corp_codes, year):
        self.multi_calls += 1
        if year != 2025:
            return []
        rows = company("005930", (300, 280, 250), (30, 25, 20), (25, 20, 18), (100, 90), (400, 380))
        rows += company("000660", (60, 40, 30), (20, 5, -3), (15, 3, -4), (40, 50), (60, 50))
        rows += company("035720", (80, 80, 80), (-1, 2, 3), (-5, 1, 2), (30, 30), (70, 75))
        for r in rows:  # 단위를 조 단위로 키운다
            for k in ("thstrm_amount", "frmtrm_amount", "bfefrmtrm_amount"):
                if r[k]:
                    r[k] = f"{parse_amount(r[k]) * 1e12:,.0f}"
        return rows

    def cash_flow(self, corp_code, year):
        return 5e13, 1e13


MARKET = pd.DataFrame({
    "code": ["005930", "000660", "005935", "035720"],
    "name": ["삼성전자", "SK하이닉스", "삼성전자우", "카카오"],
    "market": ["KOSPI"] * 4, "price": [1.0] * 4, "change_pct": [1.0, -2.0, 0.0, 0.5],
    "trading_value": [1e10] * 4, "market_cap": [4e14, 1e14, 5e13, 2e13], "trading": [True] * 4,
    "traded_at": ["2026-09-25T15:30:00+09:00"] * 4,
})


def test_build_report_end_to_end(tmp_path):
    args = app.parse_args(["--out", str(tmp_path), "--top", "10"])
    now = datetime(2026, 9, 25, 15, 45, tzinfo=app.KST)
    dart = FakeDart()
    report = app.build_report(args, now, MARKET, "naver", dart, {"000660": 1}, FakeFeeds(), tmp_path / "cache")
    app.write_site(tmp_path, report, now)

    assert report["session"] == "pm"
    codes = [s["code"] for s in report["stocks"]]
    assert set(codes) == {"005930", "000660"}  # 우선주·적자 제외
    hynix = next(s for s in report["stocks"] if s["code"] == "000660")
    assert "흑자전환" in hynix["flags"] and hynix["prev_rank"] == 1
    assert hynix["sector"] == "반도체"
    for s in report["stocks"]:
        assert 0 <= s["score"] <= 100 and s["signal"] == "매수 관심"  # 통과 종목이 2개뿐이라 둘 다 보유
        assert s["score"] == pytest.approx(0.4 * s["s_value"] + 0.4 * s["s_momentum"] + 0.2 * s["s_low_vol"], abs=1e-3)
    assert report["regime"]["regime"] == "상승장" and report["regime"]["exposure"] == 1.0
    assert {h["code"] for h in report["holdings"]} == {"005930", "000660"}
    assert all(p["weight"] == 0.1 and p["stop"] == pytest.approx(p["entry_price"] * 0.85) for p in report["portfolio"])
    assert [e["type"] for e in report["events"]] == ["in", "in"]

    saved = json.loads((tmp_path / "data" / "latest.json").read_text(encoding="utf-8"))
    assert saved == json.loads((tmp_path / "data" / "history" / "2026-09-25-pm.json").read_text(encoding="utf-8"))
    assert json.loads((tmp_path / "data" / "index.json").read_text()) == ["2026-09-25-pm"]
    for f in ("index.html", "manifest.json", "icon-192.png"):
        assert (tmp_path / f).exists()

    # 두 번째 실행은 캐시를 써서 DART 주요계정을 다시 부르지 않고, 보유 목록을 이어받는다
    calls = dart.multi_calls
    ranks, holdings = app.previous_state(tmp_path)
    again = app.build_report(args, now, MARKET, "naver", dart, ranks, FakeFeeds(), tmp_path / "cache", holdings)
    assert dart.multi_calls == calls
    assert again["events"] == [] and again["holdings"] == report["holdings"]


def test_cache_keeps_partial_progress(tmp_path):
    class FlakyDart(FakeDart):
        def multi_accounts(self, corp_codes, year):
            raise RuntimeError("연결 끊김")

    args = app.parse_args([])
    now = datetime(2026, 9, 25, 15, 45, tzinfo=app.KST)
    with pytest.raises(RuntimeError):
        app.build_report(args, now, MARKET, "naver", FlakyDart(), {}, FakeFeeds(), tmp_path)
    assert (tmp_path / "dart-2025.json").exists()


def test_is_market_day():
    now = datetime(2026, 9, 25, 10, 0, tzinfo=app.KST)  # 금요일
    today = pd.DataFrame({"traded_at": ["2026-09-25T09:59:00+09:00"]})
    stale = pd.DataFrame({"traded_at": ["2026-09-24T15:30:00+09:00"]})
    assert app.is_market_day(today, "naver", now)
    assert not app.is_market_day(stale, "naver", now)
    assert not app.is_market_day(today, "naver", datetime(2026, 9, 26, 10, 0, tzinfo=app.KST))
