const FUND_METRICS = [
  ["per", "PER(업종 내 비교)", "낮을수록"], ["pbr", "PBR(업종 내 비교)", "낮을수록"], ["roe", "ROE", "높을수록"],
  ["rev_cagr", "매출 성장률(최근 2년 연평균)", "높을수록"], ["op_cagr", "영업이익 성장률(최근 2년 연평균)", "높을수록"],
  ["debt_ratio", "부채비율", "낮을수록"], ["fcf", "FCF 수익률(잉여현금흐름 ÷ 시가총액)", "높을수록"],
];
const TECH_LABELS = {
  trend: "추세: 200일선 위 15 · 50일선>200일선 10 · 50일선 위 5",
  momentum: "모멘텀: 12-1개월 수익률 후보 내 백분위",
  rsi: "RSI(14): 45~70 만점, 70~75 절반, 35~45 일부, 극단 0",
  macd: "MACD: 시그널 위 5 · 히스토그램 확대 5",
  bollinger: "볼린저: 밴드 수축(스퀴즈) 뒤 중심선 위",
  volume: "거래량: 최근 20일 상승일/하락일 거래량 ≥1.2 만점, ≥1.0 절반",
  flow: "수급: 20일 외국인 순매수 5 · 기관 순매수 5",
};
const SIGNAL_CLASS = { "매수 관심": "buy", "관망": "wait", "과열 주의": "hot", "추세 이탈": "broken", "데이터 부족": "wait" };

const $ = (sel) => document.querySelector(sel);
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
const dash = "–";
let current = null;
let view = "summary";

function num(v, digits = 1) {
  return v == null ? dash : v.toLocaleString("ko-KR", { maximumFractionDigits: digits, minimumFractionDigits: digits });
}
function pct(v, digits = 1) { return v == null ? dash : `${num(v * 100, digits)}%`; }
function signedPct(v, digits = 1) {
  if (v == null) return dash;
  const cls = v > 0 ? "up" : v < 0 ? "down" : "";
  return `<span class="${cls}">${v > 0 ? "+" : ""}${num(v * 100, digits)}%</span>`;
}
function won(v) {
  if (v == null) return dash;
  const abs = Math.abs(v);
  if (abs >= 1e12) return `${v < 0 ? "-" : ""}${num(abs / 1e12, 1)}조`;
  const eok = Math.round(abs / 1e8);
  return `${v < 0 && eok ? "-" : ""}${eok.toLocaleString("ko-KR")}억`;
}
function runLabel(id) {
  const [y, m, d, s] = id.split("-");
  return `${y}.${m}.${d} ${s === "am" ? "오전" : "오후"}`;
}
const chip = (text, cls) => `<span class="chip ${cls}">${esc(text)}</span>`;

function nameCell(s) {
  const extra = [s.code, s.market, s.sector].filter(Boolean).map(esc).join(" · ");
  return `<td class="left name"><a href="https://m.stock.naver.com/domestic/stock/${esc(s.code)}" target="_blank" rel="noopener">${esc(s.name)}</a>
    <small>${extra}${s.fs_div === "OFS" ? " · 별도" : ""}</small></td>`;
}
function rankCell(s) {
  let delta = '<small class="new">NEW</small>';
  if (s.prev_rank != null) {
    const diff = s.prev_rank - s.rank;
    delta = diff > 0 ? `<small class="up">▲${diff}</small>`
      : diff < 0 ? `<small class="down">▼${-diff}</small>` : "<small>–</small>";
  }
  return `<td class="rank">${s.rank}${delta}</td>`;
}
function priceCell(s) {
  const chg = s.change_pct ?? 0;
  const cls = chg > 0 ? "up" : chg < 0 ? "down" : "";
  return `<td>${num(s.price, 0)}<small class="${cls} block">${chg > 0 ? "+" : ""}${num(chg, 2)}%</small></td>`;
}
function bar(v, cls = "") {
  return `<span class="track ${cls}"><span class="fill" style="width:${Math.max(0, Math.min(100, v ?? 0))}%"></span></span>`;
}
function flagsHtml(s) {
  const good = new Set(["흑자전환"]);
  const all = [...(s.flags || [])];
  if (s.golden_recent) all.push("골든크로스");
  if (s.squeeze_breakout) all.push("변동성 돌파");
  const extraGood = new Set(["골든크로스", "변동성 돌파"]);
  return all.map((f) => `<span class="flag${good.has(f) || extraGood.has(f) ? " good" : ""}">${esc(f)}</span>`).join("");
}
function stopCell(s) {
  return s.stop == null ? `<td>${dash}</td>` : `<td>${num(s.stop, 0)}<small class="block muted">-${num(s.stop_pct * 100, 1)}%</small></td>`;
}

const VIEWS = {
  summary: {
    head: ["순위", "종목", "현재가", "신호", "종합 점수", "재무", "기술", "손절가", "체크"],
    left: new Set([1, 4, 8]),
    row: (s) => `${rankCell(s)}${nameCell(s)}${priceCell(s)}
      <td>${chip(s.signal, SIGNAL_CLASS[s.signal] || "wait")}</td>
      <td class="left score">${bar(s.score)}<b>${num(s.score)}</b></td>
      <td>${num(s.fund_score, 0)}</td><td>${num(s.tech_score, 0)}</td>
      ${stopCell(s)}<td class="left">${flagsHtml(s)}</td>`,
  },
  fund: {
    head: ["순위", "종목", "시가총액", "PER", "PBR", "ROE", "매출성장", "영업이익성장", "부채비율", "FCF수익률", "재무 점수"],
    left: new Set([1]),
    row: (s) => {
      const title = FUND_METRICS.map(([k, l]) => `${l.split("(")[0]} ${Math.round(s[`s_${k}`] ?? 0)}점`).join(" · ");
      return `${rankCell(s)}${nameCell(s)}<td>${won(s.market_cap)}</td>
      <td>${num(s.per)}</td><td>${num(s.pbr, 2)}</td><td>${pct(s.roe)}</td>
      <td>${pct(s.rev_cagr)}</td><td>${pct(s.op_cagr)}</td>
      <td>${s.debt_ratio == null ? dash : `${num(s.debt_ratio, 0)}%`}</td><td>${pct(s.fcf_yield)}</td>
      <td title="${esc(title)}"><b>${num(s.fund_score)}</b></td>`;
    },
  },
  tech: {
    head: ["순위", "종목", "신호", "200일선 대비", "RSI", "12-1개월", "3개월", "상승/하락 거래량", "외국인 20일", "기관 20일", "기술 점수"],
    left: new Set([1]),
    row: (s) => {
      const p = s.tech_points || {};
      const title = Object.entries(p).map(([k, v]) => `${k} ${v}`).join(" · ");
      return `${rankCell(s)}${nameCell(s)}
      <td>${chip(s.signal, SIGNAL_CLASS[s.signal] || "wait")}</td>
      <td>${signedPct(s.dist_ma200)}</td><td>${num(s.rsi, 0)}</td>
      <td>${signedPct(s.mom_12_1)}</td><td>${signedPct(s.ret_3m)}</td>
      <td>${s.up_down_volume == null ? dash : num(s.up_down_volume, 2)}</td>
      <td>${won(s.foreign_20d)}</td><td>${won(s.institution_20d)}</td>
      <td title="${esc(title)}"><b>${num(s.tech_score, 0)}</b></td>`;
    },
  },
};

function renderTable(report) {
  const v = VIEWS[view];
  $("#table thead").innerHTML = `<tr>${v.head.map((h, i) => `<th class="${v.left.has(i) ? "left" : ""}">${h}</th>`).join("")}</tr>`;
  $("#table tbody").innerHTML = report.stocks.length
    ? report.stocks.map((s) => `<tr>${v.row(s)}</tr>`).join("")
    : `<tr><td colspan="${v.head.length}" class="empty">조건을 통과한 종목이 없습니다.</td></tr>`;
  document.querySelectorAll(".tabs button").forEach((b) => b.classList.toggle("on", b.dataset.view === view));
}

function renderRegime(report) {
  const r = report.regime;
  if (!r) { $("#regime").innerHTML = ""; return; }
  const cls = { "상승장": "buy", "중립": "wait", "하락장": "broken" }[r.regime] || "wait";
  const idx = Object.entries(r.indices || {}).map(([name, d]) => `
    <div class="stat"><span>${esc(name)}</span><b>${num(d.close, 2)}</b>
      <span>200일선 ${signedPct(d.dist_ma200)} · 1개월 ${signedPct(d.ret_1m)} · ${esc(d.trend)}</span></div>`).join("");
  const fx = r.usdkrw ? `<div class="stat"><span>원·달러</span><b>${num(r.usdkrw.close, 1)}</b>
      <span>1개월 ${signedPct(r.usdkrw.change_1m)}${r.usdkrw.warning ? ` · <em class="warn">${esc(r.usdkrw.warning)}</em>` : ""}</span></div>` : "";
  const ff = r.foreign_flow ? `<div class="stat"><span>코스피 당일 수급(억원)</span>
      <b class="${r.foreign_flow.foreign > 0 ? "up" : "down"}">외국인 ${num(r.foreign_flow.foreign, 0)}</b>
      <span>기관 ${num(r.foreign_flow.institution, 0)} · 개인 ${num(r.foreign_flow.individual, 0)}</span></div>` : "";
  $("#regime").innerHTML = `
    <div class="regime-head">
      <div>${chip(r.regime, cls)} <span class="big">권장 주식 비중 ${Math.round(r.exposure * 100)}%</span></div>
      <p class="hint">코스피·코스닥이 모두 200일선 위이고 50일선이 200일선 위면 상승장(100%), 둘 다 아래면 하락장(30%), 그 외 중립(60%). 원화가 한 달 새 3% 넘게 약해지면 20%p 줄입니다.</p>
    </div>
    <div class="stats">${idx}${fx}${ff}</div>`;
}

function renderPortfolio(report) {
  const p = report.portfolio || [];
  if (!p.length) {
    $("#portfolio").innerHTML = '<div class="empty card">이번 회차에는 재무·기술 조건을 모두 만족한 종목이 없습니다. 무리하게 사지 말고 관망하세요.</div>';
    return;
  }
  const total = p.reduce((a, x) => a + x.weight, 0);
  const rows = p.map((x) => `<tr>
      <td class="left name"><a href="https://m.stock.naver.com/domestic/stock/${esc(x.code)}" target="_blank" rel="noopener">${esc(x.name)}</a><small>${esc(x.code)}</small></td>
      <td>${num(x.price, 0)}</td>
      <td class="left score">${bar((x.weight / 0.15) * 100)}<b>${num(x.weight * 100, 1)}%</b></td>
      <td>${num(x.stop, 0)}<small class="block muted">-${num(x.stop_pct * 100, 1)}%</small></td></tr>`).join("");
  $("#portfolio").innerHTML = `<div class="table-scroll"><table>
    <thead><tr><th class="left">종목</th><th>현재가</th><th class="left">제안 비중</th><th>손절가</th></tr></thead>
    <tbody>${rows}<tr class="total"><td class="left">현금</td><td></td><td class="left"><b>${num((1 - total) * 100, 1)}%</b></td><td></td></tr></tbody>
    </table></div>`;
}

function renderMethod(report) {
  const w = report.weights;
  const blend = report.blend || { fund: 0.6, tech: 0.4 };
  const risk = report.risk;
  $("#method").innerHTML = `
    <h3>1. 재무로 후보 고르기 (종합 점수의 ${Math.round(blend.fund * 100)}%)</h3>
    <p>조건을 통과한 종목 안에서 지표별 백분위(0~100점)를 가중합합니다. PER·PBR은 <b>같은 업종 안에서</b> 비교합니다(업종 종목 5개 미만이면 전체와 비교).</p>
    <ul>${FUND_METRICS.map(([k, label, dir]) => `<li><b>${label}</b> ${dir} 좋음, 가중치 ${w[k]}%</li>`).join("")}</ul>
    <p>제외: 우선주·스팩·리츠, 시가총액 ${won(report.filters.min_market_cap)} 미만, 거래정지, 순이익·영업이익 적자, 자본잠식, 매출 정보 없는 회사(은행·보험 등 금융업).</p>
    <h3>2. 차트·수급으로 타이밍 보기 (${Math.round(blend.tech * 100)}%)</h3>
    <p>재무 상위 ${report.counts.candidates ?? ""}개 후보의 일봉·수급을 분석합니다. 한 지표만 보지 않고 여러 지표가 같은 방향일 때 점수가 높아집니다.</p>
    <ul>${Object.entries(report.tech_points || {}).map(([k, v]) => `<li><b>${v}점</b> ${esc(TECH_LABELS[k] || k)}</li>`).join("")}</ul>
    <p>신호: ${chip("매수 관심", "buy")} 200일선 위 + 50일선>200일선 + RSI 45~72 + MACD 시그널 위 + 기술 점수 60 이상 ·
      ${chip("과열 주의", "hot")} RSI 75 이상, 20일선보다 15% 넘게 위, 볼린저 상단 크게 돌파 ·
      ${chip("추세 이탈", "broken")} 200일선 아래 · ${chip("관망", "wait")} 그 외</p>
    <h3>3. 시장 국면과 리스크</h3>
    <ul>
      <li>손절가: 2×ATR(14), 매수가 대비 ${risk ? `${risk.stop_range[0] * 100}~${risk.stop_range[1] * 100}` : "5~10"}% 범위. 종가가 손절가 아래로 내려가면 매도.</li>
      <li>비중: 손절 시 자산의 ${risk ? risk.risk_per_trade * 100 : 1}%만 잃도록 (1% ÷ 손절폭), 종목당 최대 ${risk ? risk.max_weight * 100 : 15}%, 최대 ${risk ? risk.max_holdings : 10}종목.</li>
      <li>전체 주식 비중은 시장 국면의 권장 비중을 넘지 않게 줄입니다. 나머지는 현금.</li>
    </ul>
    <p>표시: <span class="flag">가치함정 주의</span> PBR<1인데 ROE<8% · <span class="flag">고부채</span> 부채비율>200% ·
    <span class="flag">FCF 적자</span> · <span class="flag">일회성 이익 의심</span> 순이익이 영업이익의 1.5배 초과 ·
    <span class="flag">이익의 질 낮음</span> 영업현금흐름<순이익 · <span class="flag good">흑자전환</span> ·
    <span class="flag good">골든크로스</span> 최근 20일 내 50일선이 200일선 돌파 · <span class="flag good">변동성 돌파</span> 볼린저 스퀴즈 후 상승</p>`;
}

function render(report) {
  current = report;
  const d = new Date(report.generated_at);
  const src = report.price_source === "naver" ? "네이버 증권(실시간)" : "KRX(전일 종가)";
  $("#meta").textContent = `기준 ${d.toLocaleString("ko-KR", { timeZone: "Asia/Seoul" })} · 시세 ${src} · 재무 ${report.fiscal_year}년 사업보고서(OpenDART)`;

  const c = report.counts;
  const buys = report.stocks.filter((s) => s.signal === "매수 관심").length;
  const stats = [
    [c.universe.toLocaleString(), `보통주 · 시총 ${won(report.filters.min_market_cap)} 이상`],
    [c.passed.toLocaleString(), "흑자·유동성 조건 통과"],
    [c.candidates ?? dash, "기술적 분석 후보"],
    [buys, `상위 ${report.stocks.length}개 중 매수 관심`],
  ];
  $("#summary").innerHTML = stats.map(([v, l]) => `<div class="stat"><b>${v}</b><span>${l}</span></div>`).join("");

  renderRegime(report);
  renderPortfolio(report);
  renderTable(report);
  renderMethod(report);
}

async function load(id) {
  const url = id ? `data/history/${id}.json` : "data/latest.json";
  const res = await fetch(url, { cache: "no-store" });
  if (!res.ok) throw new Error(`${url}: ${res.status}`);
  render(await res.json());
}

function showError(e) {
  $("#table tbody").innerHTML = `<tr><td class="empty">아직 분석 결과가 없거나 불러오지 못했습니다. (${esc(e.message)})</td></tr>`;
}

async function init() {
  document.querySelectorAll(".tabs button").forEach((b) => b.addEventListener("click", () => {
    view = b.dataset.view;
    try { localStorage.setItem("view", view); } catch (_) { /* 저장 안 돼도 무방 */ }
    if (current) renderTable(current);
  }));
  try { view = localStorage.getItem("view") || view; } catch (_) { /* 무시 */ }
  if (!VIEWS[view]) view = "summary";
  try {
    const runs = await fetch("data/index.json", { cache: "no-store" }).then((r) => (r.ok ? r.json() : []));
    const sel = $("#run");
    sel.innerHTML = runs.slice().reverse().map((id) => `<option value="${esc(id)}">${runLabel(id)}</option>`).join("");
    sel.addEventListener("change", () => load(sel.value).catch(showError));
    await load(runs.length ? runs[runs.length - 1] : null);
  } catch (e) {
    showError(e);
  }
}

init();
