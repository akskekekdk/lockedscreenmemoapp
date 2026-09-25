const METRICS = [
  ["per", "PER", "낮을수록"], ["pbr", "PBR", "낮을수록"], ["roe", "ROE", "높을수록"],
  ["rev_cagr", "매출 성장률(최근 2년 연평균)", "높을수록"], ["op_cagr", "영업이익 성장률(최근 2년 연평균)", "높을수록"],
  ["debt_ratio", "부채비율", "낮을수록"], ["fcf", "FCF 수익률(잉여현금흐름 ÷ 시가총액)", "높을수록"],
];

const $ = (sel) => document.querySelector(sel);
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
const dash = "–";

function num(v, digits = 1) {
  return v == null ? dash : v.toLocaleString("ko-KR", { maximumFractionDigits: digits, minimumFractionDigits: digits });
}
function pct(v, digits = 1) { return v == null ? dash : `${num(v * 100, digits)}%`; }
function won(v) {
  if (v == null) return dash;
  const jo = v / 1e12;
  return jo >= 1 ? `${num(jo, 1)}조` : `${Math.round(v / 1e8).toLocaleString("ko-KR")}억`;
}
function runLabel(id) {
  const [y, m, d, s] = id.split("-");
  return `${y}.${m}.${d} ${s === "am" ? "오전" : "오후"}`;
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

function row(s) {
  const chg = s.change_pct ?? 0;
  const cls = chg > 0 ? "up" : chg < 0 ? "down" : "";
  const flags = (s.flags || []).map((f) => `<span class="flag${f === "흑자전환" ? " good" : ""}">${esc(f)}</span>`).join("");
  const title = METRICS.map(([k, label]) => `${label.split("(")[0]} ${Math.round(s[`s_${k}`] ?? 0)}점`).join(" · ");
  return `<tr>
    ${rankCell(s)}
    <td class="left name"><a href="https://m.stock.naver.com/domestic/stock/${esc(s.code)}" target="_blank" rel="noopener">${esc(s.name)}</a>
      <small>${esc(s.code)} · ${esc(s.market)}${s.fs_div === "OFS" ? " · 별도" : ""}</small></td>
    <td>${num(s.price, 0)}<small class="${cls}" style="display:block">${chg > 0 ? "+" : ""}${num(chg, 2)}%</small></td>
    <td>${won(s.market_cap)}</td>
    <td>${num(s.per)}</td>
    <td>${num(s.pbr, 2)}</td>
    <td>${pct(s.roe)}</td>
    <td>${pct(s.rev_cagr)}</td>
    <td>${pct(s.op_cagr)}</td>
    <td>${s.debt_ratio == null ? dash : `${num(s.debt_ratio, 0)}%`}</td>
    <td>${pct(s.fcf_yield)}</td>
    <td class="left score" title="${esc(title)}">
      <span class="track"><span class="fill" style="width:${s.score}%"></span></span><b>${num(s.score)}</b>
      <div>${flags}</div></td>
  </tr>`;
}

function render(report) {
  const d = new Date(report.generated_at);
  const src = report.price_source === "naver" ? "네이버 증권(실시간)" : "KRX(전일 종가)";
  $("#meta").textContent = `기준 ${d.toLocaleString("ko-KR", { timeZone: "Asia/Seoul" })} · 시세 ${src} · 재무 ${report.fiscal_year}년 사업보고서(OpenDART)`;

  const c = report.counts;
  const stats = [
    [c.universe.toLocaleString(), `보통주 · 시총 ${won(report.filters.min_market_cap)} 이상`],
    [c.passed.toLocaleString(), "흑자·유동성 조건 통과"],
    [report.stocks.length, "상위 종목 발표"],
    [report.stocks[0] ? esc(report.stocks[0].name) : dash, "오늘의 1위"],
  ];
  $("#summary").innerHTML = stats.map(([v, l]) => `<div class="stat"><b>${v}</b><span>${l}</span></div>`).join("");

  $("#table tbody").innerHTML = report.stocks.length
    ? report.stocks.map(row).join("")
    : '<tr><td colspan="12" class="empty">조건을 통과한 종목이 없습니다.</td></tr>';

  const w = report.weights;
  $("#method").innerHTML = `
    <p>조건을 통과한 종목 안에서 각 지표를 백분위(0~100점)로 바꾼 뒤 아래 가중치로 합산합니다. 점수 막대에 마우스를 올리면 지표별 점수가 보입니다.</p>
    <ul>${METRICS.map(([k, label, dir]) => `<li><b>${label}</b> ${dir} 좋음, 가중치 ${w[k]}%</li>`).join("")}</ul>
    <p>제외 대상: 우선주·스팩·리츠, 시가총액 ${won(report.filters.min_market_cap)} 미만, 거래정지, 당기순이익·영업이익 적자, 자본잠식, 매출 정보 없는 회사(은행·보험 등 금융업 포함).
    FCF는 1차 점수 상위 후보만 조회하며 FCF가 적자면 해당 항목은 0점입니다.</p>
    <p>표시: <span class="flag">가치함정 주의</span> PBR 1 미만인데 ROE 8% 미만 ·
    <span class="flag">고부채</span> 부채비율 200% 초과 · <span class="flag">FCF 적자</span> ·
    <span class="flag good">흑자전환</span> 비교 기간 영업이익 적자에서 흑자로 전환</p>`;
}

async function load(id) {
  const url = id ? `data/history/${id}.json` : "data/latest.json";
  const res = await fetch(url, { cache: "no-store" });
  if (!res.ok) throw new Error(`${url}: ${res.status}`);
  render(await res.json());
}

async function init() {
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

function showError(e) {
  $("#table tbody").innerHTML = `<tr><td colspan="12" class="empty">아직 분석 결과가 없거나 불러오지 못했습니다. (${esc(e.message)})</td></tr>`;
}

init();
