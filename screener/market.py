"""시세 데이터 수집.

1순위: 네이버 증권 모바일 API (장중 실시간 가격·시가총액)
2순위: FinanceDataReader KRX 목록 (전 영업일 종가 기준)
"""
from __future__ import annotations

import logging
import time

import pandas as pd
import requests

log = logging.getLogger(__name__)

NAVER_URL = "https://m.stock.naver.com/api/stocks/marketValue/{market}"
HEADERS = {"User-Agent": "Mozilla/5.0 (compatible; kr-stock-screener)"}
MARKETS = ("KOSPI", "KOSDAQ")
MAX_PAGES = 60

COLUMNS = [
    "code", "name", "market", "price", "change_pct",
    "trading_value", "market_cap", "trading", "traded_at",
]


def _num(value) -> float:
    try:
        return float(str(value).replace(",", ""))
    except (TypeError, ValueError):
        return float("nan")


def fetch_naver(session: requests.Session | None = None, pause: float = 0.2) -> pd.DataFrame:
    s = session or requests.Session()
    rows = []
    for market in MARKETS:
        for page in range(1, MAX_PAGES + 1):
            r = s.get(
                NAVER_URL.format(market=market),
                params={"page": page, "pageSize": 100},
                headers=HEADERS,
                timeout=15,
            )
            r.raise_for_status()
            stocks = r.json().get("stocks") or []
            if not stocks:
                break
            for x in stocks:
                if x.get("stockEndType") != "stock":  # ETF/ETN 제외
                    continue
                rows.append({
                    "code": x["itemCode"],
                    "name": x["stockName"],
                    "market": market,
                    "price": _num(x.get("closePriceRaw")),
                    "change_pct": _num(x.get("fluctuationsRatio")),
                    "trading_value": _num(x.get("accumulatedTradingValueRaw")),
                    "market_cap": _num(x.get("marketValueRaw")),
                    "trading": (x.get("tradeStopType") or {}).get("name") == "TRADING",
                    "traded_at": x.get("localTradedAt"),
                })
            time.sleep(pause)
    return pd.DataFrame(rows, columns=COLUMNS)


def fetch_fdr() -> pd.DataFrame:
    import FinanceDataReader as fdr

    df = fdr.StockListing("KRX")
    df = df[df["Market"].isin(["KOSPI", "KOSDAQ", "KOSDAQ GLOBAL"])]
    return pd.DataFrame({
        "code": df["Code"].astype(str),
        "name": df["Name"],
        "market": df["Market"].replace({"KOSDAQ GLOBAL": "KOSDAQ"}),
        "price": df["Close"].astype(float),
        "change_pct": df["ChagesRatio"].astype(float),
        "trading_value": df["Amount"].astype(float),
        "market_cap": df["Marcap"].astype(float),
        "trading": True,
        "traded_at": None,
    }, columns=COLUMNS).reset_index(drop=True)


def fetch_market() -> tuple[pd.DataFrame, str]:
    """(시세 DataFrame, 출처 이름)을 돌려준다."""
    try:
        df = fetch_naver()
        if len(df) > 1000:
            return df, "naver"
        log.warning("네이버 시세가 %d건뿐이라 KRX(FDR)로 대체합니다", len(df))
    except Exception as e:  # noqa: BLE001 - 어떤 실패든 예비 소스로 넘어간다
        log.warning("네이버 시세 수집 실패(%s), KRX(FDR)로 대체합니다", e)
    return fetch_fdr(), "krx"


# ---- 기술적 분석·시장 국면용 데이터 (네이버) ----

CHART_URL = "https://api.stock.naver.com/chart/domestic/{kind}/{code}/day"
TREND_URL = "https://m.stock.naver.com/api/stock/{code}/trend"
INDEX_TREND_URL = "https://m.stock.naver.com/api/index/{index}/trend"
INDUSTRY_LIST_URL = "https://m.stock.naver.com/api/stocks/industry"
INDUSTRY_URL = "https://m.stock.naver.com/api/stocks/industry/{no}"
FX_URL = "https://m.stock.naver.com/front-api/marketIndex/prices"


def _get(session: requests.Session | None, url: str, **params):
    r = (session or requests).get(url, params=params, headers=HEADERS, timeout=15)
    r.raise_for_status()
    return r.json()


def fetch_daily(code: str, today, kind: str = "item", days: int = 420,
                session: requests.Session | None = None) -> pd.DataFrame:
    """일봉. kind='item'(종목) 또는 'index'(KOSPI/KOSDAQ). 달력 기준 days일 전부터."""
    start = today - pd.Timedelta(days=days)
    rows = _get(session, CHART_URL.format(kind=kind, code=code),
                startDateTime=f"{start:%Y%m%d}0000", endDateTime=f"{today:%Y%m%d}2359")
    df = pd.DataFrame(rows)
    if df.empty:
        return pd.DataFrame(columns=["date", "open", "high", "low", "close", "volume"])
    return pd.DataFrame({
        "date": df["localDate"],
        "open": df["openPrice"].astype(float),
        "high": df["highPrice"].astype(float),
        "low": df["lowPrice"].astype(float),
        "close": df["closePrice"].astype(float),
        "volume": df["accumulatedTradingVolume"].astype(float),
    })


def fetch_investor_flow(code: str, session: requests.Session | None = None) -> dict:
    """최근 20영업일 외국인·기관 순매수 금액(원, 순매수 수량 × 종가 합)."""
    rows = _get(session, TREND_URL.format(code=code), pageSize=20)
    foreign = institution = 0.0
    for r in rows:
        close = _num(r.get("closePrice"))
        foreign += _num(r.get("foreignerPureBuyQuant")) * close
        institution += _num(r.get("organPureBuyQuant")) * close
    return {"foreign": foreign, "institution": institution, "days": len(rows)}


def fetch_market_flow(session: requests.Session | None = None) -> dict:
    """코스피 당일 투자자별 순매수(억원)."""
    d = _get(session, INDEX_TREND_URL.format(index="KOSPI"))
    return {
        "date": d.get("bizdate"),
        "foreign": _num(d.get("foreignValue")),
        "institution": _num(d.get("institutionalValue")),
        "individual": _num(d.get("personalValue")),
    }


def fetch_industries(session: requests.Session | None = None, pause: float = 0.1) -> dict[str, str]:
    """종목코드 → 네이버 업종명."""
    s = session or requests.Session()
    groups = _get(s, INDUSTRY_LIST_URL, page=1, pageSize=100).get("groups") or []
    sector = {}
    for g in groups:
        for page in range(1, MAX_PAGES + 1):
            stocks = _get(s, INDUSTRY_URL.format(no=g["no"]), page=page, pageSize=100).get("stocks") or []
            for x in stocks:
                sector.setdefault(x["itemCode"], g["name"])
            if len(stocks) < 100:
                break
            time.sleep(pause)
    return sector


def fetch_usdkrw(session: requests.Session | None = None) -> pd.Series:
    """원·달러 환율 최근 30영업일 (오래된 → 최근)."""
    rows = _get(session, FX_URL, category="exchange", reutersCode="FX_USDKRW", page=1, pageSize=30).get("result") or []
    s = pd.Series({r["localTradedAt"]: _num(r["closePrice"]) for r in rows})
    return s.sort_index()
