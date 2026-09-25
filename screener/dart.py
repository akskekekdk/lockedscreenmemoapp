"""OpenDART 재무 데이터 수집과 지표 계산에 필요한 계정 추출."""
from __future__ import annotations

import io
import logging
import math
import time
import zipfile
from xml.etree import ElementTree

import pandas as pd
import requests

log = logging.getLogger(__name__)

BASE_URL = "https://opendart.fss.or.kr/api"
ANNUAL_REPORT = "11011"  # 사업보고서
MULTI_BATCH = 100  # fnlttMultiAcnt 한 번에 조회 가능한 회사 수

# 다중회사 주요계정(fnlttMultiAcnt) 계정명 → 내부 필드
ACCOUNT_NAMES = {
    "revenue": ("매출액", "수익(매출액)"),
    "op_income": ("영업이익", "영업이익(손실)"),
    "net_income": ("당기순이익", "당기순이익(손실)"),
    "liabilities": ("부채총계",),
    "equity": ("자본총계",),
}

OCF_IDS = ("ifrs-full_CashFlowsFromUsedInOperatingActivities",)
CAPEX_IDS = ("ifrs-full_PurchaseOfPropertyPlantAndEquipment",)
CAPEX_NAMES = ("유형자산의취득", "유형자산취득")


class DartError(RuntimeError):
    pass


def parse_amount(value) -> float:
    if value is None:
        return math.nan
    s = str(value).replace(",", "").strip()
    if s in ("", "-"):
        return math.nan
    negative = s.startswith("(") and s.endswith(")")
    try:
        n = float(s.strip("()"))
    except ValueError:
        return math.nan
    return -n if negative else n


class DartClient:
    def __init__(self, api_key: str, session: requests.Session | None = None, pause: float = 0.1):
        if not api_key:
            raise DartError("DART_API_KEY가 비어 있습니다")
        self.api_key = api_key
        self.session = session or requests.Session()
        self.pause = pause

    def _get_json(self, endpoint: str, **params) -> list[dict]:
        r = self.session.get(
            f"{BASE_URL}/{endpoint}",
            params={"crtfc_key": self.api_key, **params},
            timeout=30,
        )
        r.raise_for_status()
        data = r.json()
        time.sleep(self.pause)
        status = data.get("status")
        if status == "000":
            return data.get("list") or []
        if status == "013":  # 조회된 데이터 없음
            return []
        raise DartError(f"{endpoint} 실패: {status} {data.get('message')}")

    def corp_codes(self) -> dict[str, str]:
        """상장사 종목코드(6자리) → DART 고유번호(8자리)."""
        r = self.session.get(f"{BASE_URL}/corpCode.xml", params={"crtfc_key": self.api_key}, timeout=60)
        r.raise_for_status()
        if not r.content.startswith(b"PK"):
            raise DartError(f"corpCode.xml 실패: {r.text[:200]}")
        with zipfile.ZipFile(io.BytesIO(r.content)) as z:
            root = ElementTree.fromstring(z.read(z.namelist()[0]))
        mapping = {}
        for item in root.iter("list"):
            stock_code = (item.findtext("stock_code") or "").strip()
            if stock_code:
                mapping[stock_code] = item.findtext("corp_code").strip()
        return mapping

    def multi_accounts(self, corp_codes: list[str], year: int) -> list[dict]:
        rows = []
        for i in range(0, len(corp_codes), MULTI_BATCH):
            batch = corp_codes[i:i + MULTI_BATCH]
            rows += self._get_json(
                "fnlttMultiAcnt.json",
                corp_code=",".join(batch),
                bsns_year=str(year),
                reprt_code=ANNUAL_REPORT,
            )
        return rows

    def free_cash_flow(self, corp_code: str, year: int) -> float:
        """영업활동현금흐름 − 유형자산 취득. 연결(CFS)이 없으면 별도(OFS)."""
        for fs_div in ("CFS", "OFS"):
            rows = self._get_json(
                "fnlttSinglAcntAll.json",
                corp_code=corp_code,
                bsns_year=str(year),
                reprt_code=ANNUAL_REPORT,
                fs_div=fs_div,
            )
            fcf = fcf_from_statement(rows)
            if not math.isnan(fcf):
                return fcf
        return math.nan


def fcf_from_statement(rows: list[dict]) -> float:
    cf = [r for r in rows if r.get("sj_div") == "CF"]
    ocf = capex = math.nan
    for r in cf:
        name = (r.get("account_nm") or "").replace(" ", "")
        if math.isnan(ocf) and (r.get("account_id") in OCF_IDS or (name.startswith("영업활동") and "현금흐름" in name)):
            ocf = parse_amount(r.get("thstrm_amount"))
        if math.isnan(capex) and (r.get("account_id") in CAPEX_IDS or name in CAPEX_NAMES):
            capex = parse_amount(r.get("thstrm_amount"))
    if math.isnan(ocf):
        return math.nan
    return ocf - (0 if math.isnan(capex) else abs(capex))


def fundamentals_from_rows(rows: list[dict]) -> pd.DataFrame:
    """fnlttMultiAcnt 결과를 종목코드별 한 줄로 정리한다. 연결재무제표를 우선한다."""
    name_to_field = {n: f for f, names in ACCOUNT_NAMES.items() for n in names}
    by_stock: dict[str, dict[str, dict]] = {}
    for r in rows:
        field = name_to_field.get((r.get("account_nm") or "").strip())
        if not field:
            continue
        stmt = by_stock.setdefault(r["stock_code"], {}).setdefault(r.get("fs_div"), {"year": r.get("bsns_year")})
        stmt.setdefault(field, parse_amount(r.get("thstrm_amount")))
        stmt.setdefault(f"{field}_prev", parse_amount(r.get("frmtrm_amount")))
        stmt.setdefault(f"{field}_prev2", parse_amount(r.get("bfefrmtrm_amount")))

    records = []
    for code, stmts in by_stock.items():
        stmt = stmts.get("CFS") if "net_income" in stmts.get("CFS", {}) else stmts.get("OFS")
        if stmt:
            records.append({"code": code, "fs_div": "CFS" if stmt is stmts.get("CFS") else "OFS", **stmt})
    return pd.DataFrame(records)


def latest_fiscal_year(today) -> int:
    """사업보고서는 3월 말까지 제출되므로 4월부터 직전 연도 보고서를 쓴다."""
    return today.year - 1 if today.month >= 4 else today.year - 2
