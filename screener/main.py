"""스크리너 실행: 시세·재무 수집 → 점수화 → 정적 사이트용 JSON 출력.

사용법: DART_API_KEY=... python -m screener.main --out public
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import shutil
import sys
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import pandas as pd

from . import scoring
from .dart import DartClient, fundamentals_from_rows, latest_fiscal_year
from .market import fetch_market

log = logging.getLogger("screener")
KST = ZoneInfo("Asia/Seoul")
WEB_DIR = Path(__file__).resolve().parent.parent / "web"
HISTORY_KEEP = 120  # 하루 2회 × 약 3달

OUTPUT_FIELDS = [
    "code", "name", "market", "price", "change_pct", "market_cap",
    "per", "pbr", "roe", "rev_cagr", "op_cagr", "debt_ratio", "fcf", "fcf_yield",
    "s_per", "s_pbr", "s_roe", "s_rev_cagr", "s_op_cagr", "s_debt_ratio", "s_fcf",
    "score", "flags", "fs_div", "year",
]


def parse_args(argv=None):
    p = argparse.ArgumentParser(description="국내주식 지표 스크리너")
    p.add_argument("--out", default="public", help="사이트 출력 폴더")
    p.add_argument("--top", type=int, default=30, help="발표할 종목 수")
    p.add_argument("--fcf-candidates", type=int, default=60, help="FCF를 조회할 1차 상위 후보 수")
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


def load_fundamentals(dart: DartClient, corp_by_code: dict[str, str], codes: list[str], year: int) -> pd.DataFrame:
    """최신 사업연도로 조회하고, 아직 공시가 없는 회사는 전년도로 보충한다."""
    frames = []
    remaining = [c for c in codes if c in corp_by_code]
    for y in (year, year - 1):
        if not remaining:
            break
        f = fundamentals_from_rows(dart.multi_accounts([corp_by_code[c] for c in remaining], y))
        log.info("DART %d년 사업보고서: %d개사", y, len(f))
        frames.append(f)
        found = set(f["code"]) if not f.empty else set()
        remaining = [c for c in remaining if c not in found]
    return pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()


def previous_ranks(out: Path) -> dict[str, int]:
    try:
        prev = json.loads((out / "data" / "latest.json").read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return {}
    return {s["code"]: s["rank"] for s in prev.get("stocks", [])}


def build_report(args, now: datetime, market: pd.DataFrame, source: str, dart: DartClient, prev: dict[str, int]) -> dict:
    universe = market[[scoring.is_common_stock(c, n) for c, n in zip(market["code"], market["name"])]]
    universe = universe[universe["market_cap"] >= args.min_market_cap]
    log.info("시세 %d종목 → 보통주·시총 필터 후 %d종목", len(market), len(universe))

    corp_by_code = dart.corp_codes()
    year = latest_fiscal_year(now)
    fundamentals = load_fundamentals(dart, corp_by_code, universe["code"].tolist(), year)
    if fundamentals.empty:
        raise RuntimeError("DART 재무 데이터를 하나도 받지 못했습니다")

    df = universe.merge(fundamentals, on="code")
    df = scoring.add_metrics(df)
    passed = scoring.apply_filters(df, args.min_market_cap, args.min_trading_value)
    log.info("재무 매칭 %d종목 → 흑자·유동성 필터 후 %d종목", len(df), len(passed))

    ranked = scoring.preliminary_score(passed.set_index("code", drop=False))
    candidates = ranked.head(args.fcf_candidates)
    fcf = pd.Series({
        code: dart.free_cash_flow(corp_by_code[code], int(row["year"]))
        for code, row in candidates.iterrows()
    }, dtype=float)
    final = scoring.final_score(candidates, fcf).head(args.top)

    stocks = []
    for rank, (_, row) in enumerate(final.iterrows(), start=1):
        item = {f: scoring.clean(row.get(f)) for f in OUTPUT_FIELDS}
        item["rank"] = rank
        item["prev_rank"] = prev.get(row["code"])
        stocks.append(item)

    return {
        "generated_at": now.isoformat(timespec="minutes"),
        "session": "am" if now.hour < 12 else "pm",
        "price_source": source,
        "fiscal_year": year,
        "counts": {"market": len(market), "universe": len(universe), "matched": len(df), "passed": len(passed)},
        "weights": {**scoring.WEIGHTS, "fcf": scoring.FCF_WEIGHT},
        "filters": {"min_market_cap": args.min_market_cap, "min_trading_value": args.min_trading_value},
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

    market, source = fetch_market()
    log.info("시세 출처: %s, %d종목", source, len(market))
    if not args.force and not is_market_day(market, source, now):
        log.info("오늘(%s)은 휴장일이라 건너뜁니다", now.date())
        return 0

    dart = DartClient(os.environ.get("DART_API_KEY", ""))
    report = build_report(args, now, market, source, dart, previous_ranks(out))
    write_site(out, report, now)
    if os.environ.get("GITHUB_OUTPUT"):  # 휴장일에는 게시 단계를 건너뛰도록 알린다
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as f:
            f.write("updated=true\n")
    log.info("완료: 상위 %d종목을 %s에 저장", len(report["stocks"]), out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
