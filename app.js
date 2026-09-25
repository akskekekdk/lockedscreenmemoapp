const SIGNAL_CLASS = { "매수 관심": "buy", "후보": "hot", "관망": "wait", "과열 주의": "hot", "추세 이탈": "broken", "데이터 부족": "wait" };

const $ = (sel) => document.querySelector(sel);
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
const dash = "–";
let current = null;
let view = "summary";
let sort = "score";
// 매수 관심 우선 정렬 순서 (안드로이드 TopStocks.SIGNAL_ORDER와 같게 유지)
const SIGNAL_ORDER = ["매수 관심", "후보", "관망", "과열 주의", "추세 이탈"];
const signalRank = (s) => { const i = SIGNAL_ORDER.indexOf(s); return i < 0 ? SIGNAL_ORDER.length : i; };

function sortedStocks(stocks) {
  if (sort !== "buy") return stocks;  // 기본은 종합 점수순(원래 순서)
  return stocks.slice().sort((a, b) => signalRank(a.signal) - signalRank(b.signal) || a.rank - b.rank);
}

function tellApp() {
  // 안드로이드 앱 안이면 알림창 TOP3도 같은 정렬로 보여주게 알린다.
  try { if (window.JosangwonApp) window.JosangwonApp.setSort(sort); } catch (_) { /* 앱 밖이면 무시 */ }
}

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
  return (s.flags || []).map((f) => `<span class="flag${f === "흑자전환" ? " good" : ""}">${esc(f)}</span>`).join("");
}
function naverLink(code, name) {
  return `<a href="https://m.stock.naver.com/domestic/stock/${esc(code)}" target="_blank" rel="noopener">${esc(name)}</a>`;
}

const VIEWS = {
  summary: {
    head: ["순위", "종목", "현재가", "신호", "점수", "가치", "모멘텀", "저변동", "체크"],
    left: new Set([1, 4, 8]),
    row: (s) => `${rankCell(s)}${nameCell(s)}${priceCell(s)}
      <td>${chip(s.signal, SIGNAL_CLASS[s.signal] || "wait")}</td>
      <td class="left score">${bar(s.score)}<b>${num(s.score)}</b></td>
      <td>${num(s.s_value, 0)}</td><td>${num(s.s_momentum, 0)}</td><td>${num(s.s_low_vol, 0)}</td>
      <td class="left">${flagsHtml(s)}</td>`,
  },
  fund: {
    head: ["순위", "종목", "시가총액", "PER", "PBR", "ROE", "매출성장", "영업이익성장", "부채비율", "FCF수익률"],
    left: new Set([1]),
    row: (s) => `${rankCell(s)}${nameCell(s)}<td>${won(s.market_cap)}</td>
      <td>${num(s.per)}</td><td>${num(s.pbr, 2)}</td><td>${pct(s.roe)}</td>
      <td>${pct(s.rev_cagr)}</td><td>${pct(s.op_cagr)}</td>
      <td>${s.debt_ratio == null ? dash : `${num(s.debt_ratio, 0)}%`}</td><td>${pct(s.fcf_yield)}</td>`,
  },
  tech: {
    head: ["순위", "종목", "12-1개월", "3개월", "1개월", "변동성(연)", "52주고가 대비", "200일선 대비", "외국인 20일", "기관 20일"],
    left: new Set([1]),
    row: (s) => `${rankCell(s)}${nameCell(s)}
      <td>${signedPct(s.mom_12_1)}</td><td>${signedPct(s.ret_3m)}</td><td>${signedPct(s.ret_1m)}</td>
      <td>${s.vol_60 == null ? dash : pct(s.vol_60 * Math.sqrt(250), 0)}</td>
      <td>${s.high_52w == null ? dash : signedPct(s.high_52w - 1)}</td><td>${signedPct(s.dist_ma200)}</td>
      <td>${won(s.foreign_20d)}</td><td>${won(s.institution_20d)}</td>`,
  },
};

function renderTable(report) {
  const v = VIEWS[view];
  $("#table thead").innerHTML = `<tr>${v.head.map((h, i) => `<th class="${v.left.has(i) ? "left" : ""}">${h}</th>`).join("")}</tr>`;
  $("#table tbody").innerHTML = report.stocks.length
    ? sortedStocks(report.stocks).map((s) => `<tr>${v.row(s)}</tr>`).join("")
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
      <span>1개월 ${signedPct(r.usdkrw.change_1m)}</span></div>` : "";
  const ff = r.foreign_flow ? `<div class="stat"><span>코스피 당일 수급(억원)</span>
      <b class="${r.foreign_flow.foreign > 0 ? "up" : "down"}">외국인 ${num(r.foreign_flow.foreign, 0)}</b>
      <span>기관 ${num(r.foreign_flow.institution, 0)} · 개인 ${num(r.foreign_flow.individual, 0)}</span></div>` : "";
  $("#regime").innerHTML = `
    <div class="regime-head">
      <div>시장 상황(참고) ${chip(r.regime, cls)}</div>
      <p class="hint">백테스트에서 시장 국면에 따라 비중을 줄이는 규칙은 수익만 깎아서, 비중 조절에는 쓰지 않고 참고로만 보여줍니다.</p>
    </div>
    <div class="stats">${idx}${fx}${ff}</div>`;
}

function renderPortfolio(report) {
  const p = report.portfolio || [];
  const m = report.model || { hold: 10, keep_rank: 20, stop: 0.15 };
  $("#portfolio-hint").textContent = `점수 상위 ${m.hold}종목을 ${Math.round(100 / m.hold)}%씩 똑같이 담고, 순위가 ${m.keep_rank}위 밖으로 밀리거나 매수가 대비 -${Math.round(m.stop * 100)}%가 되면 교체합니다.`;
  if (!p.length) {
    $("#portfolio").innerHTML = '<div class="empty card">보유 종목이 없습니다.</div>';
    return;
  }
  const rows = p.map((x) => `<tr>
      <td class="left name">${naverLink(x.code, x.name)}<small>${esc(x.code)} · ${x.rank ?? dash}위</small></td>
      <td>${x.entry_date ? esc(x.entry_date.slice(5).replace("-", ".")) : dash}<small class="block muted">${num(x.entry_price, 0)}</small></td>
      <td>${num(x.price, 0)}</td>
      <td>${x.return == null ? dash : signedPct(x.return)}</td>
      <td>${num(x.stop, 0)}</td>
      <td>${num(x.weight * 100, 0)}%</td></tr>`).join("");
  const ev = (report.events || []).map((e) => `<li>${e.type === "in" ? chip("편입", "buy") : chip("교체", "broken")} ${esc(e.name)} — ${esc(e.reason)}${e.return != null ? ` (${(e.return * 100).toFixed(1)}%)` : ""}</li>`).join("");
  $("#portfolio").innerHTML = `<div class="table-scroll"><table>
    <thead><tr><th class="left">종목</th><th>편입일·가</th><th>현재가</th><th>수익률</th><th>손절가</th><th>비중</th></tr></thead>
    <tbody>${rows}</tbody></table></div>
    ${ev ? `<h3>이번 회차 변경</h3><ul class="events">${ev}</ul>` : ""}`;
}

function renderMethod(report) {
  const m = report.model || { weights: { value: 40, momentum: 40, low_vol: 20 }, hold: 10, keep_rank: 20, stop: 0.15 };
  const w = m.weights;
  $("#method").innerHTML = `
    <p>조건을 통과한 종목 안에서 세 가지 지표의 백분위(0~100점)를 가중합합니다. 가중치는 과거 데이터 백테스트로 정했습니다(아래 "검증 결과").</p>
    <ul>
      <li><b>가치 ${w.value}%</b> — 같은 업종 안에서 PBR·PER이 낮을수록 높은 점수(업종 종목 5개 미만이면 전체와 비교)</li>
      <li><b>모멘텀 ${w.momentum}%</b> — 12-1개월 수익률(최근 1개월은 단기 반전이 강해 제외)</li>
      <li><b>저변동성 ${w.low_vol}%</b> — 최근 60거래일 주가 변동이 작을수록 높은 점수</li>
    </ul>
    <p>대상: 우선주·스팩·리츠를 뺀 보통주 중 시가총액 ${won(report.filters.min_market_cap)} 이상, 거래정지 아님, 순이익·영업이익 흑자, 자본잠식 아님, 매출 정보 있음(은행·보험 등 금융업은 제외됨).</p>
    <p>신호: ${chip("매수 관심", "buy")} 보유 목록(${m.hold}종목) · ${chip("후보", "hot")} ${m.keep_rank}위 안이지만 보유 목록에 빈자리가 없음 · ${chip("관망", "wait")} 그 외</p>
    <p>ROE·성장률·부채비율·FCF·외국인·기관 수급은 점수에 넣지 않고 참고로만 보여줍니다(검증에서 효과가 없었거나 과거 데이터가 없음).</p>
    <p>표시: <span class="flag">가치함정 주의</span> PBR<1인데 ROE<8% · <span class="flag">고부채</span> 부채비율>200% ·
    <span class="flag">FCF 적자</span> · <span class="flag">일회성 이익 의심</span> 순이익이 영업이익의 1.5배 초과 ·
    <span class="flag">이익의 질 낮음</span> 영업현금흐름<순이익 · <span class="flag good">흑자전환</span></p>`;
}

function equityChart(bt) {
  const series = [["new", "새 모델", "var(--accent)"], ["old", "이전 모델", "var(--warn)"], ["bench", "벤치마크", "var(--muted)"]];
  const eq = {};
  let max = 1, min = 1;
  for (const [k] of series) {
    let v = 1;
    eq[k] = [1, ...bt.monthly[k].map((r) => (v *= 1 + (r ?? 0)))];
    max = Math.max(max, ...eq[k]); min = Math.min(min, ...eq[k]);
  }
  const W = 640, H = 220, pad = 30, n = eq.new.length;
  const x = (i) => pad + (i / (n - 1)) * (W - pad * 2);
  const y = (v) => H - pad - ((v - min) / (max - min)) * (H - pad * 2);
  const lines = series.map(([k, , c]) => `<polyline fill="none" stroke="${c}" stroke-width="${k === "new" ? 2.5 : 1.5}" points="${eq[k].map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(" ")}"/>`).join("");
  const base = `<line x1="${pad}" x2="${W - pad}" y1="${y(1)}" y2="${y(1)}" stroke="var(--line)"/>`;
  const legend = series.map(([k, name, c]) => `<span><i style="background:${c}"></i>${name} ${((eq[k][n - 1] - 1) * 100).toFixed(0)}%</span>`).join("");
  return `<div class="legend">${legend}</div><svg viewBox="0 0 ${W} ${H}" class="chart" role="img" aria-label="누적 수익률">${base}${lines}
    <text x="${pad}" y="${H - 8}" class="axis">${bt.monthly.dates[0]}</text><text x="${W - pad}" y="${H - 8}" text-anchor="end" class="axis">${bt.monthly.dates[n - 2]}</text></svg>`;
}

async function renderBacktest() {
  let bt;
  try {
    const res = await fetch("backtest.json", { cache: "no-store" });
    if (!res.ok) return;
    bt = await res.json();
  } catch (_) { return; }
  const SHORT = { new: "새 모델", old: "이전 모델", value: "가치만", bench: "벤치마크" };
  const rows = ["new", "old", "value", "bench"].map((k) => {
    const m = bt.models[k];
    return `<tr${k === "new" ? ' class="total"' : ""}><td class="left" title="${esc(m.name)}">${SHORT[k]}</td><td>${num(m["ann%"])}%</td><td>${num(m.sharpe, 2)}</td><td>${num(m["mdd%"])}%</td><td>${num(m["hit%"], 0)}%</td></tr>`;
  }).join("");
  const ic = (list) => list.map((r) => `<tr><td class="left">${esc(r.factor)}</td><td class="${r.ic > 0 ? "up" : "down"}">${r.ic > 0 ? "+" : ""}${r.ic.toFixed(3)}</td><td>${r.t.toFixed(1)}</td></tr>`).join("");
  $("#backtest").innerHTML = `
    <p>${esc(bt.method)} 기간 ${bt.period[0]} ~ ${bt.period[1]} (월 ${bt.models.new.n}회). 대상: ${esc(bt.universe)}.</p>
    ${equityChart(bt)}
    <div class="table-scroll"><table>
      <thead><tr><th class="left">방식</th><th>연수익</th><th>샤프</th><th>최대낙폭</th><th>월 승률</th></tr></thead>
      <tbody>${rows}</tbody></table></div>
    <ul class="hint">${["new", "old", "value", "bench"].map((k) => `<li>${SHORT[k]}: ${esc(bt.models[k].name.replace(/^[^:]+: ?/, ""))}</li>`).join("")}</ul>
    <h3>알게 된 것</h3><ul>${bt.findings.map((f) => `<li>${esc(f)}</li>`).join("")}</ul>
    <details><summary>지표별 예측력(IC: 다음 달 수익률 순위와의 상관, t값 2 이상이면 의미 있음)</summary>
      <div class="ic-grid">
        <div><h3>가격 지표 · ${bt.tech_period[0].slice(0, 4)}~${bt.tech_period[1].slice(0, 4)}</h3><table><tbody>${ic(bt.ic_long)}</tbody></table></div>
        <div><h3>재무+가격 · ${bt.period[0].slice(0, 7)}~</h3><table><tbody>${ic(bt.ic_fund)}</tbody></table></div>
      </div></details>
    <p class="hint">한계: ${bt.limits.map(esc).join(" · ")}</p>`;
}

function render(report) {
  current = report;
  const d = new Date(report.generated_at);
  const src = report.price_source === "naver" ? "네이버 증권(실시간)" : "KRX(전일 종가)";
  $("#meta").textContent = `기준 ${d.toLocaleString("ko-KR", { timeZone: "Asia/Seoul" })} · 시세 ${src} · 재무 ${report.fiscal_year}년 사업보고서(OpenDART)`;

  const c = report.counts;
  const stats = [
    [c.universe.toLocaleString(), `보통주 · 시총 ${won(report.filters.min_market_cap)} 이상`],
    [c.passed.toLocaleString(), "흑자·유동성 조건 통과"],
    [(c.priced ?? c.candidates ?? dash).toLocaleString(), "점수 계산 종목"],
    [(report.portfolio || []).length, "보유(매수 관심) 종목"],
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
  // 앱(WebView) 안에서는 APK 받기 링크를 숨긴다.
  if (/; wv\)/.test(navigator.userAgent)) $("#apk").hidden = true;
  document.querySelectorAll(".tabs button").forEach((b) => b.addEventListener("click", () => {
    view = b.dataset.view;
    try { localStorage.setItem("view", view); } catch (_) { /* 저장 안 돼도 무방 */ }
    if (current) renderTable(current);
  }));
  try { view = localStorage.getItem("view") || view; } catch (_) { /* 무시 */ }
  try { sort = localStorage.getItem("sort") || sort; } catch (_) { /* 무시 */ }
  if (sort !== "buy") sort = "score";
  $("#sort").value = sort;
  tellApp();
  $("#sort").addEventListener("change", () => {
    sort = $("#sort").value;
    try { localStorage.setItem("sort", sort); } catch (_) { /* 저장 안 돼도 무방 */ }
    tellApp();
    if (current) renderTable(current);
  });
  if (!VIEWS[view]) view = "summary";
  try {
    const runs = await fetch("data/index.json", { cache: "no-store" }).then((r) => (r.ok ? r.json() : []));
    const sel = $("#run");
    sel.innerHTML = runs.slice().reverse().map((id) => `<option value="${esc(id)}">${runLabel(id)}</option>`).join("");
    sel.addEventListener("change", () => load(sel.value).catch(showError));
    renderBacktest();
    await load(runs.length ? runs[runs.length - 1] : null);
  } catch (e) {
    showError(e);
  }
}

init();
