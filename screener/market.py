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
