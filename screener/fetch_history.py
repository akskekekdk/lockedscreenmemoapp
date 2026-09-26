"""백테스트용 과거 사업보고서 재무를 받아 저장한다 (GitHub Actions에서 DART_API_KEY로 실행).

각 사업연도 Y의 사업보고서(그해 3월 말 공시분)에서 매출·영업이익·순이익·자본·부채를 받는다.
그 시점에 실제로 공개됐던 숫자이므로, 나중에 정정된 값을 미리 쓰는 오류(미래 정보 사용)를 피한다.

사용법: DART_API_KEY=... python -m screener.fetch_history --years 2018-2024 --out public/cache/dart-history.json
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from . import scoring
from .dart import MULTI_BATCH, DartClient, fundamentals_from_rows
from .market import fetch_market

log = logging.getLogger("fetch_history")


def main(argv=None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    p = argparse.ArgumentParser()
    p.add_argument("--years", default="2018-2024")
    p.add_argument("--out", default="public/cache/dart-history.json")
    args = p.parse_args(argv)
    first, last = map(int, args.years.split("-"))

    market, _ = fetch_market()
    codes = [c for c, n in zip(market["code"], market["name"]) if scoring.is_common_stock(c, n)]
    dart = DartClient(os.environ.get("DART_API_KEY", ""))
    corp = dart.corp_codes()
    targets = [c for c in codes if c in corp]
    log.info("대상 %d개사, %d~%d년", len(targets), first, last)

    out_path = Path(args.out)
    try:
        data = json.loads(out_path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        data = {}

    lock = threading.Lock()
    todo = [y for y in range(first, last + 1)
            if not (str(y) in data and len(data[str(y)]) > 0.5 * len(targets))]
    log.info("받을 연도: %s (이미 있는 연도는 건너뜀)", todo)

    def one_year(year):
        # 해외 서버에서는 DART 응답이 느려서, 연도별로 동시에 받는다.
        client = DartClient(dart.api_key)
        records = []
        for i in range(0, len(targets), MULTI_BATCH):
            batch = targets[i:i + MULTI_BATCH]
            f = fundamentals_from_rows(client.multi_accounts([corp[c] for c in batch], year))
            if not f.empty:
                records += json.loads(f.to_json(orient="records"))
        with lock:
            data[str(year)] = records
            out_path.parent.mkdir(parents=True, exist_ok=True)
            out_path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")  # 연도마다 저장
        log.info("%d년 사업보고서: %d개사", year, len(records))

    with ThreadPoolExecutor(max(1, len(todo))) as ex:
        list(ex.map(one_year, todo))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
