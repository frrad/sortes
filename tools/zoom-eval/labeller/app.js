// Sortes crop labeller: shows the reader's crops page by page and records the owner's verdicts,
// tags, comments, ideal window positions and the paper's ideal zoom. Labels are saved through
// the server's /api/labels/<paper> as tools/zoom-eval/labels/<paper>.json (schema 1, see
// tools/zoom-eval/labeller/README.md).
"use strict";

const TAGS = [
  ["top-cut", "Top cut"],
  ["bottom-cut", "Bottom cut"],
  ["side-cut", "Side cut"],
  ["too-loose", "Too loose"],
  ["off-centre", "Off-centre"],
  ["window-jumped", "Window jumped"],
  ["other", "Other"],
];
const SVG = "http://www.w3.org/2000/svg";
const SAVE_DELAY_MS = 600;

const $ = (id) => document.getElementById(id);
const state = {
  papers: [],
  paper: null,   // paper.json of the current paper
  labels: null,  // its labels, in the saved schema
  page: 0,       // index into paper.pages
  saveTimer: null,
  saving: null,  // the in-flight save, if any
  dirty: false,
};

// ---- Labels ----

function emptyLabels(id) {
  return { schema: 1, paper: id, labelledAgainst: null, idealScale: null, comment: "", pages: {} };
}

function pageLabel(n) {
  return state.labels.pages[String(n)] || { verdict: null, tags: [], comment: "", idealCentre: null };
}

function isEmpty(label) {
  return !label.verdict && label.tags.length === 0 && !label.comment && !label.idealCentre;
}

function setPageLabel(n, change) {
  const label = Object.assign({}, pageLabel(n), change);
  label.tags = [...new Set(label.tags)].sort();
  if (isEmpty(label)) delete state.labels.pages[String(n)];
  else state.labels.pages[String(n)] = label;
  changed();
}

function changed() {
  state.dirty = true;
  setStatus("Unsaved changes…");
  clearTimeout(state.saveTimer);
  state.saveTimer = setTimeout(save, SAVE_DELAY_MS);
  render();
}

async function save() {
  clearTimeout(state.saveTimer);
  if (!state.dirty || !state.labels) return;
  // The crops these labels judge: the ones being shown.
  state.labels.labelledAgainst = state.paper.labelledAgainst;
  const body = JSON.stringify(state.labels);
  state.dirty = false;
  setStatus("Saving…");
  const id = state.labels.paper;
  state.saving = fetch(`/api/labels/${encodeURIComponent(id)}`, { method: "PUT", body })
    .then(async (r) => {
      if (!r.ok) throw new Error(await r.text());
      setStatus(`Saved ${new Date().toLocaleTimeString()}`);
    })
    .catch((e) => {
      state.dirty = true;
      setStatus(`Not saved: ${e.message}. Retrying on your next change.`, true);
    })
    .finally(() => { state.saving = null; });
  await state.saving;
}

async function flush() {
  if (state.saving) await state.saving;
  if (state.dirty) await save();
}

function setStatus(text, error = false) {
  const s = $("status");
  s.textContent = text;
  s.classList.toggle("error", error);
}

// ---- Geometry: everything in page points; the overlay's viewBox is the padded frame. ----

function geometry(p) {
  const pad = { x: (p.frame.width - p.page.width) / 2, y: (p.frame.height - p.page.height) / 2 };
  return { frame: p.frame, pad };
}

function modelScale() {
  return state.paper.labelledAgainst.scale;
}

function idealScale() {
  return state.labels.idealScale ?? modelScale();
}

function modelCentre(p) {
  const r = p.region;
  return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
}

/** The owner's window on page [p]: its centre (page points), size from the paper's zoom, kept in the frame. */
function myWindow(p) {
  const { frame, pad } = geometry(p);
  const scale = idealScale();
  const w = scale * frame.width;
  const h = scale * frame.height;
  const label = pageLabel(p.number);
  const centre = label.idealCentre || modelCentre(p);
  const cx = clamp(centre.x + pad.x, w / 2, frame.width - w / 2);
  const cy = clamp(centre.y + pad.y, h / 2, frame.height - h / 2);
  return { left: cx - w / 2 - pad.x, top: cy - h / 2 - pad.y, width: w, height: h, centre: { x: cx - pad.x, y: cy - pad.y } };
}

function clamp(v, lo, hi) {
  return hi < lo ? (lo + hi) / 2 : Math.min(hi, Math.max(lo, v));
}

const round1 = (v) => Math.round(v * 10) / 10;

function setCentre(p, x, y) {
  const { frame, pad } = geometry(p);
  const scale = idealScale();
  const w = scale * frame.width;
  const h = scale * frame.height;
  const cx = clamp(x + pad.x, w / 2, frame.width - w / 2) - pad.x;
  const cy = clamp(y + pad.y, h / 2, frame.height - h / 2) - pad.y;
  setPageLabel(p.number, { idealCentre: { x: round1(cx), y: round1(cy) } });
}

// ---- Rendering ----

function render() {
  if (!state.paper) return;
  const p = state.paper.pages[state.page];
  renderPaperPanel();
  renderPage(p);
  renderSide(p);
  renderStrip();
  const judged = state.paper.pages.filter((q) => pageLabel(q.number).verdict).length;
  $("progress").textContent = `${judged} of ${state.paper.pages.length} pages judged`;
}

function zoomText(scale) {
  return `${(1 / scale).toFixed(2)}×`;
}

function renderPaperPanel() {
  const a = state.paper.labelledAgainst;
  $("model-info").innerHTML =
    `<b>${escapeHtml(state.paper.title)}</b> · ${state.paper.pages.length} pages<br>` +
    `Reader's crops at <code>${escapeHtml(a.commit)}</code>: zoom ${zoomText(a.scale)}, ` +
    `${state.paper.windows} window${state.paper.windows === 1 ? "" : "s"}, ` +
    `cuts ${state.paper.cutPercent.toFixed(2)}% of ink`;
  const scales = state.paper.scales;
  const zoom = $("zoom");
  zoom.max = scales.length - 1;
  zoom.value = nearestIndex(scales, idealScale());
  const own = state.labels.idealScale != null;
  $("zoom-value").textContent = own
    ? `${zoomText(idealScale())} (model ${zoomText(modelScale())})`
    : `${zoomText(modelScale())} (the model's)`;
  $("zoom-reset").disabled = !own;
  const comment = $("paper-comment");
  if (document.activeElement !== comment) comment.value = state.labels.comment;
}

function nearestIndex(scales, s) {
  let best = 0;
  scales.forEach((v, i) => { if (Math.abs(v - s) < Math.abs(scales[best] - s)) best = i; });
  return best;
}

function renderPage(p) {
  const img = $("page-image");
  const src = `/data/${encodeURIComponent(state.paper.id)}/${p.image}`;
  if (img.getAttribute("src") !== src) img.src = src;
  const { frame, pad } = geometry(p);
  const svg = $("overlay");
  svg.setAttribute("viewBox", `0 0 ${frame.width} ${frame.height}`);
  svg.setAttribute("preserveAspectRatio", "none");
  svg.replaceChildren();
  const rect = (r, cls) => {
    const e = document.createElementNS(SVG, "rect");
    e.setAttribute("x", r.left + pad.x);
    e.setAttribute("y", r.top + pad.y);
    e.setAttribute("width", r.width);
    e.setAttribute("height", r.height);
    e.setAttribute("class", cls);
    e.setAttribute("vector-effect", "non-scaling-stroke");
    svg.append(e);
    return e;
  };
  const mine = myWindow(p);
  // Shade what your window leaves off screen.
  const shade = document.createElementNS(SVG, "path");
  const x = mine.left + pad.x, y = mine.top + pad.y;
  shade.setAttribute("d", `M0 0H${frame.width}V${frame.height}H0Z M${x} ${y}V${y + mine.height}H${x + mine.width}V${y}Z`);
  shade.setAttribute("fill-rule", "evenodd");
  shade.setAttribute("class", "shade");
  svg.append(shade);
  for (const c of p.cut) rect(c, "cut");
  rect(p.region, "model");
  // The model's window label, in its top-left corner.
  const label = `W${p.window} · model`;
  const lx = p.region.left + pad.x, ly = p.region.top + pad.y;
  const bg = document.createElementNS(SVG, "rect");
  const size = frame.width / 45;
  bg.setAttribute("x", lx); bg.setAttribute("y", ly);
  bg.setAttribute("width", size * label.length * 0.62); bg.setAttribute("height", size * 1.4);
  bg.setAttribute("class", "model-label-bg");
  svg.append(bg);
  const text = document.createElementNS(SVG, "text");
  text.setAttribute("x", lx + size * 0.3); text.setAttribute("y", ly + size * 1.05);
  text.setAttribute("class", "model-label");
  text.setAttribute("style", `font-size:${size}px`);
  text.textContent = label;
  svg.append(text);
  rect(mine, "mine-halo");
  const mineRect = rect(mine, "mine");
  mineRect.id = "mine";
  const dot = document.createElementNS(SVG, "circle");
  dot.setAttribute("cx", mine.centre.x + pad.x);
  dot.setAttribute("cy", mine.centre.y + pad.y);
  dot.setAttribute("r", frame.width / 150);
  dot.setAttribute("class", "centre");
  svg.append(dot);
}

function renderSide(p) {
  const label = pageLabel(p.number);
  $("page-number").textContent = `Page ${p.number} of ${state.paper.pages.length}`;
  $("page-facts").textContent =
    `· W${p.window} · cuts ${p.cutPercent.toFixed(2)}% of this page's ink`;
  $("good").classList.toggle("on", label.verdict === "good");
  $("bad").classList.toggle("on", label.verdict === "bad");
  for (const [tag] of TAGS) $(`tag-${tag}`).classList.toggle("on", label.tags.includes(tag));
  const comment = $("page-comment");
  if (document.activeElement !== comment) comment.value = label.comment;
  const moved = label.idealCentre != null;
  $("window-state").textContent = moved
    ? `Your window: moved (centre ${label.idealCentre.x.toFixed(0)}, ${label.idealCentre.y.toFixed(0)} pt)`
    : "Your window: at the model's position";
  $("reset-window").disabled = !moved;
  renderPreview(p, moved || state.labels.idealScale != null);
}

/** The crop as the tablet would show it: yours if you've changed it, else the model's. */
function renderPreview(p, mine) {
  const canvas = $("preview");
  const aspect = state.paper.labelledAgainst.aspect;
  const cssWidth = canvas.clientWidth || 380;
  const dpr = window.devicePixelRatio || 1;
  canvas.width = Math.round(cssWidth * dpr);
  canvas.height = Math.round((cssWidth / aspect) * dpr);
  canvas.style.height = `${cssWidth / aspect}px`;
  const ctx = canvas.getContext("2d");
  ctx.fillStyle = "#fff";
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  const img = $("page-image");
  const draw = () => {
    const { frame, pad } = geometry(p);
    const k = img.naturalWidth / frame.width;
    const r = mine ? myWindow(p) : p.region;
    ctx.imageSmoothingQuality = "high";
    ctx.drawImage(img, (r.left + pad.x) * k, (r.top + pad.y) * k, r.width * k, r.height * k, 0, 0, canvas.width, canvas.height);
  };
  if (img.complete && img.naturalWidth) draw(); else img.onload = draw;
  $("preview-which").textContent = mine ? "your window" : "the model's crop";
}

function renderStrip() {
  const strip = $("strip");
  if (strip.dataset.paper !== state.paper.id) {
    strip.dataset.paper = state.paper.id;
    strip.replaceChildren(...state.paper.pages.map((p, i) => {
      const b = document.createElement("button");
      b.className = "thumb";
      b.title = `Page ${p.number}`;
      b.innerHTML = `<img loading="lazy" alt="" src="/data/${encodeURIComponent(state.paper.id)}/${p.image}">` +
        `<span class="mark"></span><span class="num">${p.number}</span>`;
      b.addEventListener("click", () => goTo(i));
      return b;
    }));
  }
  [...strip.children].forEach((b, i) => {
    const label = pageLabel(state.paper.pages[i].number);
    const mark = b.querySelector(".mark");
    mark.textContent = label.verdict === "good" ? "✓" : label.verdict === "bad" ? "✗" : isEmpty(label) ? "" : "•";
    mark.hidden = !mark.textContent;
    b.classList.toggle("current", i === state.page);
  });
  strip.children[state.page]?.scrollIntoView({ block: "nearest", inline: "nearest" });
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

// ---- Navigation and loading ----

function goTo(i) {
  if (!state.paper) return;
  state.page = clamp(i, 0, state.paper.pages.length - 1);
  history.replaceState(null, "", `#paper=${encodeURIComponent(state.paper.id)}&page=${state.paper.pages[state.page].number}`);
  render();
}

function nextUnjudged() {
  const pages = state.paper.pages;
  for (let k = 1; k <= pages.length; k++) {
    const i = (state.page + k) % pages.length;
    if (!pageLabel(pages[i].number).verdict) return goTo(i);
  }
}

async function loadPaper(id, pageNumber) {
  await flush();
  setStatus("Loading…");
  const [paper, labels] = await Promise.all([
    fetch(`/data/${encodeURIComponent(id)}/paper.json`).then((r) => r.json()),
    fetch(`/api/labels/${encodeURIComponent(id)}`).then((r) => r.ok ? r.json() : emptyLabels(id)),
  ]);
  state.paper = paper;
  state.labels = labels;
  state.dirty = false;
  $("paper").value = id;
  const index = paper.pages.findIndex((p) => p.number === pageNumber);
  const old = labels.labelledAgainst;
  setStatus(old && old.commit !== paper.labelledAgainst.commit
    ? `Labels were made against ${old.commit}; these crops are ${paper.labelledAgainst.commit}.`
    : "Loaded");
  goTo(index >= 0 ? index : Math.max(0, paper.pages.findIndex((p) => !pageLabel(p.number).verdict)));
}

function hashParams() {
  return Object.fromEntries(location.hash.slice(1).split("&").filter(Boolean).map((kv) => kv.split("=").map(decodeURIComponent)));
}

async function start() {
  // Tag buttons, keyed 1-7.
  $("tags").replaceChildren(...TAGS.map(([tag, name], i) => {
    const b = document.createElement("button");
    b.id = `tag-${tag}`;
    b.className = "tag";
    b.innerHTML = `${name} <kbd>${i + 1}</kbd>`;
    b.addEventListener("click", () => toggleTag(tag));
    return b;
  }));
  state.papers = await fetch("/data/papers.json").then((r) => r.json());
  const select = $("paper");
  select.replaceChildren(...state.papers.map((p) => {
    const o = document.createElement("option");
    o.value = p.id;
    o.textContent = `${p.title} (${p.id}, ${p.pageCount} pages)`;
    return o;
  }));
  select.addEventListener("change", () => loadPaper(select.value));
  const params = hashParams();
  const first = state.papers.find((p) => p.id === params.paper) || state.papers[0];
  if (first) await loadPaper(first.id, Number(params.page));
  else setStatus("No papers exported yet: run zoom-eval labeller-export.", true);
}

// ---- Editing ----

function toggleVerdict(v) {
  const p = state.paper.pages[state.page];
  setPageLabel(p.number, { verdict: pageLabel(p.number).verdict === v ? null : v });
}

function toggleTag(tag) {
  const p = state.paper.pages[state.page];
  const tags = pageLabel(p.number).tags;
  setPageLabel(p.number, { tags: tags.includes(tag) ? tags.filter((t) => t !== tag) : [...tags, tag] });
}

function nudge(dx, dy) {
  const p = state.paper.pages[state.page];
  const c = myWindow(p).centre;
  setCentre(p, c.x + dx, c.y + dy);
}

// Dragging your window: grab it, or click anywhere to put its centre there.
(() => {
  let drag = null;
  const svg = $("overlay");
  const toPage = (e) => {
    const p = state.paper.pages[state.page];
    const { frame, pad } = geometry(p);
    const box = svg.getBoundingClientRect();
    return {
      x: ((e.clientX - box.left) / box.width) * frame.width - pad.x,
      y: ((e.clientY - box.top) / box.height) * frame.height - pad.y,
    };
  };
  svg.addEventListener("pointerdown", (e) => {
    if (!state.paper) return;
    const p = state.paper.pages[state.page];
    const at = toPage(e);
    const c = myWindow(p).centre;
    drag = e.target.id === "mine" ? { dx: c.x - at.x, dy: c.y - at.y } : { dx: 0, dy: 0 };
    svg.setPointerCapture(e.pointerId);
    setCentre(p, at.x + drag.dx, at.y + drag.dy);
  });
  svg.addEventListener("pointermove", (e) => {
    if (!drag) return;
    const at = toPage(e);
    setCentre(state.paper.pages[state.page], at.x + drag.dx, at.y + drag.dy);
  });
  const end = () => { drag = null; };
  svg.addEventListener("pointerup", end);
  svg.addEventListener("pointercancel", end);
})();

$("good").addEventListener("click", () => toggleVerdict("good"));
$("bad").addEventListener("click", () => toggleVerdict("bad"));
$("prev").addEventListener("click", () => goTo(state.page - 1));
$("next").addEventListener("click", () => goTo(state.page + 1));
$("next-unjudged").addEventListener("click", nextUnjudged);
$("reset-window").addEventListener("click", () => setPageLabel(state.paper.pages[state.page].number, { idealCentre: null }));
$("page-comment").addEventListener("input", (e) => setPageLabel(state.paper.pages[state.page].number, { comment: e.target.value }));
$("paper-comment").addEventListener("input", (e) => { state.labels.comment = e.target.value; changed(); });
$("zoom").addEventListener("input", (e) => { state.labels.idealScale = state.paper.scales[Number(e.target.value)]; changed(); });
$("zoom-reset").addEventListener("click", () => { state.labels.idealScale = null; changed(); });
$("help-button").addEventListener("click", () => { $("help").hidden = false; });
$("help-close").addEventListener("click", () => { $("help").hidden = true; });
window.addEventListener("resize", render);
window.addEventListener("beforeunload", (e) => { if (state.dirty || state.saving) { save(); e.preventDefault(); } });

document.addEventListener("keydown", (e) => {
  const typing = ["TEXTAREA", "INPUT", "SELECT"].includes(e.target.tagName) && e.target.type !== "range";
  if (typing) {
    if (e.key === "Escape") e.target.blur();
    return;
  }
  if (!state.paper || e.ctrlKey || e.metaKey) return;
  if (!$("help").hidden) {
    if (e.key === "Escape" || e.key === "?") $("help").hidden = true;
    return;
  }
  const step = e.altKey ? 1 : 5;
  if (e.shiftKey && e.key.startsWith("Arrow")) {
    const [dx, dy] = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] }[e.key];
    nudge(dx, dy);
    e.preventDefault();
    return;
  }
  const key = e.key.toLowerCase();
  if (key === "g") toggleVerdict("good");
  else if (key === "b") toggleVerdict("bad");
  else if (key === "j" || e.key === "ArrowRight") goTo(state.page + 1);
  else if (key === "k" || e.key === "ArrowLeft") goTo(state.page - 1);
  else if (key === "u") nextUnjudged();
  else if (key === "r") setPageLabel(state.paper.pages[state.page].number, { idealCentre: null });
  else if (key === "c") $("page-comment").focus();
  else if (e.key === "?") $("help").hidden = false;
  else if (/^[1-7]$/.test(e.key)) toggleTag(TAGS[Number(e.key) - 1][0]);
  else return;
  e.preventDefault();
});

start().catch((e) => setStatus(`Couldn't start: ${e.message}`, true));
