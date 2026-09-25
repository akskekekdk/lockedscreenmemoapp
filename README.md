# 국내주식 지표 스크리너

코스피·코스닥 전 종목을 **평일 오전(09:50)·오후(15:50)** 에 한 번씩 분석해서, 5가지 핵심 지표 점수가 높은 종목을 웹 대시보드(GitHub Pages)에 보여줍니다.

> 정해진 규칙으로 종목을 정렬하는 참고용 도구이며 투자 권유가 아닙니다.

## 무엇을 보나

| 구분 | 지표 | 방향 | 가중치 |
|---|---|---|---|
| 가격 | PER (시가총액 ÷ 당기순이익) | 낮을수록 | 20 |
| 가격 | PBR (시가총액 ÷ 자본총계) | 낮을수록 | 10 |
| 수익성 | ROE (당기순이익 ÷ 평균 자본) | 높을수록 | 25 |
| 성장 | 매출 성장률 (최근 2년 연평균) | 높을수록 | 10 |
| 성장 | 영업이익 성장률 (최근 2년 연평균) | 높을수록 | 15 |
| 안정성 | 부채비율 | 낮을수록 | 10 |
| 안정성 | FCF 수익률 (영업현금흐름 − 설비투자) ÷ 시가총액 | 높을수록 | 10 |

- 조건을 통과한 종목 안에서 지표별 **백분위(0~100점)** 를 매겨 가중합합니다.
- **제외 대상**: 우선주, 스팩, 리츠, 시가총액 1,000억 미만, 거래정지, 순이익·영업이익 적자, 자본잠식, 매출 정보가 없는 회사(은행·보험 같은 금융업이 여기에 해당).
- FCF는 DART 호출을 아끼려고 1차 점수 상위 60개 후보만 조회합니다.
- 경고 표시: `가치함정 주의`(PBR < 1이면서 ROE < 8%), `고부채`(부채비율 > 200%), `FCF 적자`, `흑자전환`.

## 데이터 출처

- **시세**: 네이버 증권 모바일 API(장중 실시간). 실패하면 FinanceDataReader의 KRX 목록(전일 종가)으로 대체합니다. 네이버 API는 비공식이라 예고 없이 바뀔 수 있습니다.
- **재무**: 금융감독원 OpenDART의 사업보고서(연간). 4월부터는 직전 연도 보고서를 쓰고, 아직 공시하지 않은 회사는 그 전 연도 보고서를 씁니다. 연결재무제표를 우선하고, 없으면 별도재무제표를 씁니다.

재무 수치는 1년에 한 번 바뀌므로 오전과 오후 결과의 차이는 대부분 **주가 변동에 따른 PER·PBR·FCF 수익률 변화**에서 생깁니다.

## 설정 (처음 한 번)

1. **DART 키 등록**: 저장소 Settings → Secrets and variables → Actions → New repository secret → 이름 `DART_API_KEY`, 값에 OpenDART 인증키를 넣습니다.
2. **첫 실행**: Actions 탭 → "주식 스크리너" → Run workflow. 주말이나 휴장일에는 `휴장일이어도 실행`을 체크합니다. 실행이 끝나면 `gh-pages` 브랜치가 생깁니다.
3. **Pages 켜기**: Settings → Pages → Source를 "Deploy from a branch", 브랜치를 `gh-pages` / `(root)`로 지정합니다. 몇 분 뒤 `https://<계정>.github.io/<저장소>/`에서 대시보드를 볼 수 있습니다.
4. 예약 실행(cron)은 **기본 브랜치**에 있는 워크플로만 돌아갑니다. 이 코드가 기본 브랜치에 있어야 합니다.

GitHub Actions의 예약 실행은 붐비는 시간에 10~20분 늦어질 수 있습니다. 휴장일에는 네이버 시세의 마지막 체결일을 보고 자동으로 건너뜁니다.

## 로컬 실행

```bash
pip install -r requirements.txt pytest
python -m pytest -q
DART_API_KEY=발급받은키 python -m screener.main --out public --force
python -m http.server -d public 8000   # http://localhost:8000
```

옵션: `--top 30`(발표 종목 수), `--fcf-candidates 60`, `--min-market-cap 1e11`(원 단위), `--min-trading-value 1e8`.

## 구조

```
screener/market.py   시세 수집 (네이버 → FDR 대체)
screener/dart.py     OpenDART 수집, 계정 추출, FCF 계산
screener/scoring.py  지표 계산, 필터, 백분위 점수
screener/main.py     실행 진입점, 결과 JSON·히스토리 저장
web/                 정적 대시보드 (index.html, app.js, style.css)
.github/workflows/screener.yml  평일 2회 실행 후 gh-pages에 게시
```
