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
  reviewed: {},  // paper id -> pages reviewed, for the tabs
  revision: "none",  // the server's revision of these labels, as this tab last saw it
  changes: { pages: [], paper: [] },  // what this tab changed and the server doesn't have yet
  edits: 0,      // changes so far, to tell whether a save covered them all
  failures: 0,   // saves failed in a row
  failed: false, // the last save failed
};

// ---- Labels ----
// The label logic is in labels.js (LabellerLabels), unit-tested. Saving must never lose work:
//  - every change is also kept in this browser (localStorage) until the server has it, so a
//    reload or a closed tab while the server is down loses nothing;
//  - a failed save says NOT SAVED loudly and retries on its own;
//  - saves carry the revision this tab last saw, and if another tab saved meanwhile the server
//    refuses (409): this tab then merges its own unsaved changes onto the server's labels;
//  - leaving the page sends any unsaved changes with a keepalive request.

const L = LabellerLabels;
const RETRY_DELAYS_MS = [1000, 2000, 5000, 10000, 30000];

function emptyLabels(id) {
  return L.emptyLabels(id);
}

function pageLabel(n) {
  return L.pageLabel(state.labels, n);
}

function isEmpty(label) {
  return L.isEmpty(label);
}

function modelCentreOf(n) {
  const p = state.paper.pages.find((q) => q.number === n);
  return p ? G.modelCentre(p) : null;
}

function setPageLabel(n, change) {
  state.labels = L.withPage(state.labels, n, change, modelCentreOf(n));
  state.changes = L.addChange(state.changes, "pages", n);
  changed();
}

function setPaperField(field, value) {
  state.labels = Object.assign({}, state.labels, { [field]: value });
  state.changes = L.addChange(state.changes, "paper", field);
  changed();
}

// Backups are per tab (the id survives a reload of the tab), so two tabs can't overwrite or
// clear each other's.
const TAB_ID = (() => {
  try {
    let id = sessionStorage.getItem("sortes-labeller-tab");
    if (!id) {
      id = Math.random().toString(36).slice(2, 10);
      sessionStorage.setItem("sortes-labeller-tab", id);
    }
    return id;
  } catch (e) {
    return Math.random().toString(36).slice(2, 10);
  }
})();

function backupPrefix(id) {
  return `sortes-labeller:${id}:`;
}

/** Keeps this tab's unsaved changes in the browser until the server has them. */
function backUp() {
  try {
    localStorage.setItem(backupPrefix(state.labels.paper) + TAB_ID, JSON.stringify({
      labels: state.labels, changes: state.changes, at: Date.now(),
    }));
  } catch (e) { /* storage full or disabled: the server copy is all there is */ }
}

function clearBackup(id, keys = [backupPrefix(id) + TAB_ID]) {
  try { for (const k of keys) localStorage.removeItem(k); } catch (e) { /* nothing to clear */ }
}

/**
 * Every backup of [id]'s labels in this browser (this tab's and any closed tab's), oldest first,
 * with the keys they're under.
 */
function readBackups(id) {
  const found = [];
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (!key || !key.startsWith(backupPrefix(id))) continue;
      try {
        const b = JSON.parse(localStorage.getItem(key));
        if (b && b.labels && b.labels.paper === id && b.changes) found.push(Object.assign({ key }, b));
      } catch (e) { /* unreadable: skip */ }
    }
  } catch (e) { /* storage disabled */ }
  return found.sort((x, y) => x.at - y.at);
}

/** [server] labels with every backup's unsaved changes on top (later ones winning), and what they changed. */
function withBackups(server, backups) {
  let labels = server;
  let changes = L.noChanges();
  for (const b of backups) {
    labels = L.merge(labels, b.labels, b.changes);
    for (const n of b.changes.pages) changes = L.addChange(changes, "pages", n);
    for (const f of b.changes.paper) changes = L.addChange(changes, "paper", f);
  }
  return { labels, changes };
}

function changed() {
  state.dirty = true;
  state.edits++;
  backUp();
  if (!state.failed) setStatus("Unsaved changes…");
  clearTimeout(state.saveTimer);
  state.saveTimer = setTimeout(save, SAVE_DELAY_MS);
  render();
}

async function save() {
  clearTimeout(state.saveTimer);
  if (!state.labels) return;
  if (state.saving) {
    // One save at a time; this one follows.
    await state.saving;
    if (state.dirty) return save();
    return;
  }
  if (!state.dirty) return;
  // The crops these labels judge: the ones being shown.
  state.labels = Object.assign({}, state.labels, { labelledAgainst: state.paper.labelledAgainst });
  const id = state.labels.paper;
  const edits = state.edits;
  if (!state.failed) setStatus("Saving…");
  state.saving = (async () => {
    const r = await fetch(`/api/labels/${encodeURIComponent(id)}`, {
      method: "PUT",
      body: JSON.stringify(state.labels),
      headers: { "If-Match": state.revision },
    });
    if (r.status === 409) {
      // Another tab saved since this one loaded: keep its work, put this tab's changes on top.
      const server = await r.json();
      state.revision = r.headers.get("X-Labels-Revision");
      if (state.labels && state.labels.paper === id) {
        state.labels = L.merge(server, state.labels, state.changes);
        backUp();
        render();
      }
      return "merged";
    }
    if (!r.ok) throw new Error(`${r.status} ${await r.text()}`);
    state.revision = r.headers.get("X-Labels-Revision");
    return "saved";
  })().then((outcome) => {
    if (outcome === "saved" && state.edits === edits && state.labels.paper === id) {
      state.dirty = false;
      state.changes = L.noChanges();
      clearBackup(id);
    }
    state.failures = 0;
    setFailed(false);
    if (outcome === "saved") setStatus(`Saved ✓ ${new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}`, false, true);
  }).catch((e) => {
    // Kept in this browser meanwhile; tried again shortly.
    const delay = RETRY_DELAYS_MS[Math.min(state.failures, RETRY_DELAYS_MS.length - 1)];
    state.failures++;
    setFailed(true, `${e.message || e}`, delay);
    clearTimeout(state.saveTimer);
    state.saveTimer = setTimeout(save, delay);
  }).finally(() => {
    state.saving = null;
  });
  await state.saving;
  // Changes made while that save was out (or a merge to send) go next.
  if (state.dirty && !state.failed) await save();
}

/** Shows or hides the loud NOT SAVED banner. */
function setFailed(failed, why = "", retryInMs = 0) {
  state.failed = failed;
  const banner = $("not-saved");
  banner.hidden = !failed;
  if (failed) {
    $("not-saved-why").textContent =
      `Your changes are kept in this browser, and saving is retried automatically (next in ${Math.round(retryInMs / 1000)} s). ` +
      `Don't clear this browser's data. (${why})`;
    setStatus("NOT SAVED", true);
  }
}

async function flush() {
  if (state.saving) await state.saving;
  if (state.dirty) await save();
}

function setStatus(text, error = false, saved = false) {
  const s = $("status");
  s.textContent = text;
  s.classList.toggle("error", error);
  s.classList.toggle("saved", saved);
}

// ---- Geometry: everything in page points; the overlay's viewBox is the padded frame. ----
// The maths is in geometry.js (LabellerGeometry), shared with the preview and unit-tested.

const G = LabellerGeometry;

function geometry(p) {
  return { frame: p.frame, pad: G.padding(p) };
}

function modelScale() {
  return state.paper.labelledAgainst.scale;
}

function idealScale() {
  return state.labels.idealScale ?? modelScale();
}

/** The owner's window on page [p]: the drawn dashed window and the preview both use exactly this. */
function myWindow(p) {
  return G.windowFor(p, pageLabel(p.number).idealCentre, idealScale());
}

const clamp = G.clamp;

const round1 = (v) => Math.round(v * 10) / 10;

function setCentre(p, x, y) {
  const c = G.windowFor(p, { x, y }, idealScale()).centre;
  setPageLabel(p.number, { idealCentre: { x: round1(c.x), y: round1(c.y) } });
}

// ---- Rendering ----

function render() {
  if (!state.paper) return;
  const p = state.paper.pages[state.page];
  renderPaperPanel();
  renderPage(p);
  renderSide(p);
  renderStrip();
  const { reviewed, verdicts } = counts(state.labels, state.paper.pages.length);
  $("progress").textContent =
    `${reviewed} of ${state.paper.pages.length} pages reviewed (${verdicts} marked good/bad)`;
  state.reviewed[state.paper.id] = reviewed;
  renderPaperTabs();
}

/** Pages with any label at all, and pages with a verdict. */
function counts(labels, pageCount) {
  const pages = Object.entries(labels.pages).filter(([n, l]) => Number(n) <= pageCount && !isEmpty(l));
  return { reviewed: pages.length, verdicts: pages.filter(([, l]) => l.verdict).length };
}

function renderPaperTabs() {
  const nav = $("papers");
  if (nav.children.length !== state.papers.length) {
    nav.replaceChildren(...state.papers.map((p) => {
      const b = document.createElement("button");
      b.className = "paper-tab";
      b.dataset.id = p.id;
      b.title = `${p.title} (${p.id})`;
      b.addEventListener("click", () => loadPaper(p.id));
      return b;
    }));
  }
  [...nav.children].forEach((b, i) => {
    const p = state.papers[i];
    const done = state.reviewed[p.id];
    b.innerHTML = `${escapeHtml(shortTitle(p.title))}<small>${done == null ? "…" : done} of ${p.pageCount} reviewed</small>`;
    b.classList.toggle("current", state.paper && p.id === state.paper.id);
  });
}

function shortTitle(title) {
  return title.length > 28 ? `${title.slice(0, 26)}…` : title;
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
  if (document.activeElement !== comment || comment.dataset.paper !== state.paper.id) {
    comment.value = state.labels.comment;
    comment.dataset.paper = state.paper.id;
  }
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
  // The model's window label, just outside its top-left corner (below it if there's no room),
  // so it doesn't cover the page.
  const label = `W${p.window} · model`;
  const size = frame.width / 50;
  const boxHeight = size * 1.4;
  const lx = p.region.left + pad.x;
  const above = p.region.top + pad.y - boxHeight;
  const ly = above >= 0 ? above : p.region.top + pad.y + p.region.height;
  const bg = document.createElementNS(SVG, "rect");
  bg.setAttribute("x", lx); bg.setAttribute("y", ly);
  bg.setAttribute("width", size * label.length * 0.62); bg.setAttribute("height", boxHeight);
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
  // The box belongs to one page. Refresh it on a page change even while it has focus (on a Mac,
  // clicking a button or thumbnail leaves the focus in the box): otherwise the old page's text
  // would stay in it and the next keystroke would write it onto the new page.
  const comment = $("page-comment");
  const owner = `${state.paper.id}#${p.number}`;
  if (document.activeElement !== comment || comment.dataset.page !== owner) {
    comment.value = label.comment;
    comment.dataset.page = owner;
    comment.dataset.number = p.number;
  }
  const moved = label.idealCentre != null;
  $("window-state").textContent = moved
    ? `Your window: moved (centre ${label.idealCentre.x.toFixed(0)}, ${label.idealCentre.y.toFixed(0)} pt)`
    : "Your window: at the model's position";
  $("reset-window").disabled = !moved;
  renderPreview(p, moved || state.labels.idealScale != null);
}

// Page images, loaded once each, independent of the <img> on the page: browsers differ in what
// an <img> reports while its src is changing (Safari can still report the previous image as
// complete), which once left the preview showing a stale crop until the window was nudged.
const images = new Map();

function loadImage(src) {
  if (!images.has(src)) {
    const img = new Image();
    img.src = src;
    images.set(src, img.decode().then(() => img, (e) => { images.delete(src); throw e; }));
  }
  return images.get(src);
}

let previewToken = 0;

/**
 * The crop as the tablet would show it: exactly the dashed window drawn on the page (the owner's
 * if they moved it or set a zoom, which is otherwise the model's crop). Redrawn on every render;
 * if the page image is still loading, it's drawn when it arrives, unless the state has moved on.
 */
function renderPreview(p, mine) {
  const canvas = $("preview");
  const aspect = state.paper.labelledAgainst.aspect;
  // As big as fits: the side panel's width, or 36% of the window's height.
  const cssHeight = Math.min(window.innerHeight * 0.36, ($("side").clientWidth - 32) / aspect);
  const cssWidth = cssHeight * aspect;
  const dpr = window.devicePixelRatio || 1;
  const width = Math.round(cssWidth * dpr);
  const height = Math.round(cssHeight * dpr);
  const rect = myWindow(p);
  const src = `/data/${encodeURIComponent(state.paper.id)}/${p.image}`;
  const token = ++previewToken;
  $("preview-which").textContent = mine ? "your window" : "the model's crop";
  loadImage(src).then((img) => {
    if (token !== previewToken) return; // a newer render has drawn, or will
    canvas.width = width;
    canvas.height = height;
    canvas.style.width = `${cssWidth}px`;
    canvas.style.height = `${cssHeight}px`;
    canvas.dataset.src = src;
    canvas.dataset.rect = JSON.stringify(rect);
    const ctx = canvas.getContext("2d");
    ctx.fillStyle = "#fff";
    ctx.fillRect(0, 0, width, height);
    ctx.imageSmoothingQuality = "high";
    const r = G.inImage(p, rect, img.naturalWidth);
    ctx.drawImage(img, r.left, r.top, r.width, r.height, 0, 0, width, height);
  }).catch(() => {
    if (token === previewToken) $("preview-which").textContent += " (image failed to load)";
  });
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
    mark.textContent = label.verdict === "good" ? "✓" : label.verdict === "bad" ? "✗"
      : (label.comment || label.tags.length) ? "✎" : label.idealCentre ? "⤧" : "";
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

/** The next page with no label at all. */
function nextUnjudged() {
  const pages = state.paper.pages;
  for (let k = 1; k <= pages.length; k++) {
    const i = (state.page + k) % pages.length;
    if (isEmpty(pageLabel(pages[i].number))) return goTo(i);
  }
}

async function loadPaper(id, pageNumber) {
  await flush();
  setStatus("Loading…");
  let paper;
  let labels;
  let revision;
  try {
    const [pr, lr] = await Promise.all([
      fetch(`/data/${encodeURIComponent(id)}/paper.json`),
      fetch(`/api/labels/${encodeURIComponent(id)}`, { cache: "no-store" }),
    ]);
    if (!pr.ok) throw new Error(`paper ${pr.status}`);
    if (!lr.ok) throw new Error(`labels ${lr.status}`);
    paper = await pr.json();
    labels = await lr.json();
    revision = lr.headers.get("X-Labels-Revision") || "none";
  } catch (e) {
    // Never start from empty labels: they'd look like nothing was ever said.
    setStatus(`Couldn't load ${id} (${e.message || e}); retrying…`, true);
    setTimeout(() => loadPaper(id, pageNumber), 3000);
    return;
  }
  state.paper = paper;
  state.labels = labels;
  state.revision = revision;
  state.changes = L.noChanges();
  state.dirty = false;
  state.edits++;
  // Changes made in this browser that never reached the server: put them back and save them.
  const backups = readBackups(id);
  const restored = withBackups(labels, backups);
  const unsaved = restored.changes.pages.length + restored.changes.paper.length;
  if (unsaved) {
    state.labels = restored.labels;
    state.changes = restored.changes;
    state.dirty = true;
    // This tab now holds them: one backup, under its own key.
    clearBackup(id, backups.map((b) => b.key));
    backUp();
  }
  const index = paper.pages.findIndex((p) => p.number === pageNumber);
  const old = labels.labelledAgainst;
  setStatus(old && old.commit !== paper.labelledAgainst.commit
    ? `Labels were made against ${old.commit}; these crops are ${paper.labelledAgainst.commit}.`
    : "All saved ✓", false, !(old && old.commit !== paper.labelledAgainst.commit));
  goTo(index >= 0 ? index : 0);
  if (unsaved) {
    setStatus(`Restored ${unsaved} unsaved change${unsaved === 1 ? "" : "s"} from this browser; saving…`);
    await save();
  }
}

/** Saves a paper's changes left in this browser by an earlier visit, without opening it. */
async function saveBackup(id) {
  const backups = readBackups(id);
  if (!backups.length) return;
  try {
    for (let attempt = 0; attempt < 3; attempt++) {
      const lr = await fetch(`/api/labels/${encodeURIComponent(id)}`, { cache: "no-store" });
      if (!lr.ok) return;
      const merged = withBackups(await lr.json(), backups).labels;
      const r = await fetch(`/api/labels/${encodeURIComponent(id)}`, {
        method: "PUT",
        body: JSON.stringify(merged),
        headers: { "If-Match": lr.headers.get("X-Labels-Revision") || "none" },
      });
      if (r.ok) {
        clearBackup(id, backups.map((b) => b.key));
        return;
      }
      if (r.status !== 409) return;
    }
  } catch (e) { /* kept for the next visit */ }
}

/** Done with this paper: the next one's first page. */
async function nextPaper() {
  const i = state.papers.findIndex((p) => p.id === state.paper.id);
  const next = state.papers[(i + 1) % state.papers.length];
  await loadPaper(next.id, 1);
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
  // Exported papers, in the order of the labelling set (papers.txt); others aren't shown.
  const exported = await fetch("/data/papers.json").then((r) => r.json());
  const set = (await fetch("/papers.txt").then((r) => r.ok ? r.text() : ""))
    .split("\n").map((l) => l.trim()).filter((l) => l && !l.startsWith("#"));
  state.papers = set.length
    ? set.map((id) => exported.find((p) => p.id === id)).filter(Boolean)
    : exported;
  renderPaperTabs();
  // Anything left unsaved in this browser on an earlier visit goes to the server first.
  for (const p of state.papers) await saveBackup(p.id);
  // Each paper's reviewed count, for the tabs.
  await Promise.all(state.papers.map(async (p) => {
    const labels = await fetch(`/api/labels/${encodeURIComponent(p.id)}`).then((r) => r.ok ? r.json() : emptyLabels(p.id));
    state.reviewed[p.id] = counts(labels, p.pageCount).reviewed;
  }));
  renderPaperTabs();
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

// Dragging your window: grab it and move it. Nothing changes until the pointer has moved a few
// pixels, so a plain click (to focus the page, say) never marks a page as moved.
const DRAG_THRESHOLD_PX = 4;
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
    if (!state.paper || e.target.id !== "mine") return;
    const at = toPage(e);
    const c = myWindow(state.paper.pages[state.page]).centre;
    drag = { dx: c.x - at.x, dy: c.y - at.y, x: e.clientX, y: e.clientY, moving: false };
    svg.setPointerCapture(e.pointerId);
  });
  svg.addEventListener("pointermove", (e) => {
    if (!drag) return;
    if (!drag.moving && Math.hypot(e.clientX - drag.x, e.clientY - drag.y) < DRAG_THRESHOLD_PX) return;
    drag.moving = true;
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
$("done").addEventListener("click", nextPaper);
$("reset-window").addEventListener("click", () => setPageLabel(state.paper.pages[state.page].number, { idealCentre: null }));
// Typing goes to the page (and paper) the box is showing, never another.
$("page-comment").addEventListener("input", (e) => {
  if (e.target.dataset.page !== `${state.paper.id}#${state.paper.pages[state.page].number}`) return;
  setPageLabel(Number(e.target.dataset.number), { comment: e.target.value });
});
$("paper-comment").addEventListener("input", (e) => {
  if (e.target.dataset.paper !== state.paper.id) return;
  setPaperField("comment", e.target.value);
});
$("zoom").addEventListener("input", (e) => setPaperField("idealScale", state.paper.scales[Number(e.target.value)]));
$("zoom-reset").addEventListener("click", () => setPaperField("idealScale", null));
$("help-button").addEventListener("click", () => { $("help").hidden = false; });
$("help-close").addEventListener("click", () => { $("help").hidden = true; });
window.addEventListener("resize", render);
// Back, forward or an edited address: follow the hash.
window.addEventListener("hashchange", () => {
  const params = hashParams();
  if (!state.paper) return;
  if (params.paper && params.paper !== state.paper.id) return loadPaper(params.paper, Number(params.page));
  const i = state.paper.pages.findIndex((p) => p.number === Number(params.page));
  if (i >= 0 && i !== state.page) goTo(i);
});
// Leaving with unsaved changes: send them in a request that outlives the page (they're also in
// this browser's storage, restored on the next visit if this doesn't arrive), and ask to stay.
window.addEventListener("beforeunload", (e) => {
  if (!state.dirty || !state.labels) return;
  const labels = Object.assign({}, state.labels, { labelledAgainst: state.paper.labelledAgainst });
  fetch(`/api/labels/${encodeURIComponent(labels.paper)}`, {
    method: "PUT", body: JSON.stringify(labels), headers: { "If-Match": state.revision }, keepalive: true,
  }).catch(() => {});
  e.preventDefault();
});
window.addEventListener("online", () => { if (state.dirty) save(); });
window.addEventListener("focus", () => { if (state.dirty && state.failed) save(); });

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
  else if (key === "n") nextPaper();
  else if (key === "r") setPageLabel(state.paper.pages[state.page].number, { idealCentre: null });
  else if (key === "c") $("page-comment").focus();
  else if (e.key === "?") $("help").hidden = false;
  else if (/^[1-7]$/.test(e.key)) toggleTag(TAGS[Number(e.key) - 1][0]);
  else return;
  e.preventDefault();
});

start().catch((e) => setStatus(`Couldn't start: ${e.message}`, true));
