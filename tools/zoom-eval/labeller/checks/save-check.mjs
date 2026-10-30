// Checks, in a real browser, that the labeller never loses what the owner types. It runs its own
// labeller server against a *copy* of a labels folder, so it can stop and start it:
//  - a comment, then moving on with the Next button, the filmstrip, a reload, or a paper switch;
//  - the server down while typing: the page must say NOT SAVED, keep the work, and save it by
//    itself once the server is back; and work typed while it was down, with the tab then closed,
//    must be restored and saved on the next visit;
//  - two tabs: a stale tab's save must not overwrite the other tab's comments;
//  - a window nudged off and back again is no move.
//
// Usage: node save-check.mjs <chromium binary> <zoom-eval install dir> <labels folder to copy>
// Needs ViT (2010.11929) and YOLO (1506.02640) exported in ~/sortes-review/labeller/data.
import { spawn } from "node:child_process";
import { cpSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

const [chromeBin, dist, labelsSource] = process.argv.slice(2);
const web = resolve(new URL(".", import.meta.url).pathname, "..");
const data = join(process.env.HOME, "sortes-review/labeller/data");
const PORT = 8768;
const BASE = `http://127.0.0.1:${PORT}`;
const PAPER = "2010.11929";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const labels = mkdtempSync(join(tmpdir(), "labeller-save-check-"));
cpSync(labelsSource, labels, { recursive: true });

let server = null;
async function startServer() {
  server = spawn(join(dist, "bin/zoom-eval"), [
    "labeller-serve", `--web=${web}`, `--data=${data}`, `--labels=${labels}`, `--port=${PORT}`,
  ], { stdio: "ignore", env: { ...process.env, JAVA_OPTS: "-Xmx96m" } });
  for (let i = 0; i < 100; i++) {
    try { if ((await fetch(`${BASE}/`)).ok) return; } catch { /* starting */ }
    await sleep(100);
  }
  throw new Error("server didn't start");
}
async function stopServer() {
  const s = server;
  server = null;
  const exited = new Promise((r) => s.on("exit", r));
  s.kill();
  await exited;
}
const saved = () => JSON.parse(readFileSync(join(labels, `${PAPER}.json`), "utf8"));
const savedComment = (n) => saved().pages[String(n)]?.comment ?? null;

/** One browser tab over CDP. */
async function tab(url) {
  // Opened blank, then navigated: the endpoint would drop the URL's #fragment.
  const t = await (await fetch("http://127.0.0.1:9336/json/new?about:blank", { method: "PUT" })).json();
  const ws = new WebSocket(t.webSocketDebuggerUrl);
  await new Promise((r) => ws.addEventListener("open", r));
  let id = 0;
  const pending = new Map();
  ws.addEventListener("message", (m) => {
    const d = JSON.parse(m.data);
    if (pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); }
  });
  const send = (method, params = {}) => new Promise((r) => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
  await send("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await send("Page.navigate", { url });
  const api = {
    send,
    eval: async (e) => (await send("Runtime.evaluate", { expression: e, returnByValue: true, awaitPromise: true })).result.result?.value,
    key: async (key, code, modifiers = 0) => {
      await send("Input.dispatchKeyEvent", { type: "keyDown", key, code, modifiers, text: key.length === 1 ? key : undefined });
      await send("Input.dispatchKeyEvent", { type: "keyUp", key, code, modifiers });
    },
    type: (text) => send("Input.insertText", { text }),
    click: async (selector) => {
      const r = await api.eval(`(() => { const e = document.querySelector(${JSON.stringify(selector)}); e.scrollIntoView({ block: "center", inline: "center" }); const b = e.getBoundingClientRect(); return [b.x + b.width / 2, b.y + b.height / 2]; })()`);
      await send("Input.dispatchMouseEvent", { type: "mousePressed", x: r[0], y: r[1], button: "left", clickCount: 1 });
      await send("Input.dispatchMouseEvent", { type: "mouseReleased", x: r[0], y: r[1], button: "left", clickCount: 1 });
    },
    page: () => api.eval("location.hash"),
    close: () => fetch(`http://127.0.0.1:9336/json/close/${t.id}`),
  };
  await sleep(2500);
  return api;
}

let failures = 0;
function expect(what, ok, detail = "") {
  if (!ok) failures++;
  console.log(`${ok ? "ok  " : "FAIL"} ${what}${detail ? ` (${detail})` : ""}`);
}
async function commentOn(t, text) {
  await t.click("#page-comment");
  await t.type(text);
}

const chrome = spawn(chromeBin, ["--remote-debugging-port=9336", "--no-sandbox", "about:blank"], { stdio: "ignore" });
try {
  for (let i = 0; i < 50; i++) { try { await fetch("http://127.0.0.1:9336/json/version"); break; } catch { await sleep(200); } }
  await startServer();
  const a = await tab(`${BASE}/#paper=${PAPER}&page=5`);

  // (a) A comment, then the Next button.
  await commentOn(a, "five");
  await a.click("#next");
  await sleep(1500);
  expect("comment, then Next", savedComment(5) === "five", savedComment(5));

  // (b) A comment, then a page in the filmstrip.
  await commentOn(a, "six");
  await a.click("#strip .thumb:nth-child(9)");
  await sleep(1500);
  expect("comment, then the filmstrip", savedComment(6) === "six", `${savedComment(6)}; now ${await a.page()}`);

  // (b2) A comment, then another page while the box keeps the focus (as on a Mac): the box
  // must show the new page's comment, and typing must go to the new page only.
  await a.eval(`location.hash = '#paper=${PAPER}&page=14'`);
  await sleep(800);
  await commentOn(a, "fourteen");
  await sleep(1000);
  await a.eval(`location.hash = '#paper=${PAPER}&page=15'`);
  await sleep(800);
  expect("focused box follows the page", (await a.eval("document.activeElement.id + '|' + document.getElementById('page-comment').value")) === "page-comment|");
  await a.type("fifteen");
  await sleep(1500);
  expect("...and typing goes to the new page", savedComment(15) === "fifteen" && savedComment(14) === "fourteen",
    `${savedComment(14)} / ${savedComment(15)}`);

  // (c) A comment, then reloading straight away (before the save delay).
  await a.eval("location.hash = '#paper=2010.11929&page=7'");
  await sleep(500);
  await commentOn(a, "seven");
  await a.send("Page.reload");
  await sleep(3500);
  expect("comment, then an immediate reload", savedComment(7) === "seven", savedComment(7));

  // (d) A comment, then Escape and N to the next paper.
  await a.eval("location.hash = '#paper=2010.11929&page=8'");
  await sleep(500);
  await commentOn(a, "eight");
  await a.key("Escape", "Escape");
  await a.key("n", "KeyN");
  await sleep(2000);
  expect("comment, then N to the next paper", savedComment(8) === "eight", savedComment(8));
  expect("...and N did move on", (await a.page()).includes("1506.02640"), await a.page());

  // (e) The server goes down while typing: loud, kept, and saved once it's back.
  await a.eval(`location.hash = '#paper=${PAPER}&page=9'`);
  await sleep(2500);
  await stopServer();
  await commentOn(a, "nine");
  await sleep(2500);
  const banner = await a.eval("!document.getElementById('not-saved').hidden && document.getElementById('status').textContent");
  expect("server down: NOT SAVED shown", banner === "NOT SAVED", banner);
  expect("server down: not on disk yet", savedComment(9) === null);
  await startServer();
  await sleep(12000);
  expect("server back: saved by itself", savedComment(9) === "nine", savedComment(9));
  expect("server back: banner gone", await a.eval("document.getElementById('not-saved').hidden"));

  // (e2) Down while typing, and the tab closed before it came back: restored on the next visit.
  await stopServer();
  await a.eval(`location.hash = '#paper=${PAPER}&page=10'`);
  await sleep(500);
  await commentOn(a, "ten");
  await sleep(1500);
  await a.close();
  await startServer();
  const b = await tab(`${BASE}/#paper=${PAPER}&page=10`);
  await sleep(2500);
  expect("closed while down: restored and saved on the next visit", savedComment(10) === "ten", savedComment(10));
  expect("...and shown", (await b.eval("document.getElementById('page-comment').value")) === "ten");

  // (f) Two tabs: the stale one mustn't overwrite the other's comment.
  const c = await tab(`${BASE}/#paper=${PAPER}&page=12`);
  await b.eval(`location.hash = '#paper=${PAPER}&page=11'`);
  await sleep(500);
  await commentOn(b, "eleven, from tab B");
  await sleep(1500);
  await commentOn(c, "twelve, from tab C");
  await sleep(2500);
  expect("two tabs: B's comment survives C's save", savedComment(11) === "eleven, from tab B", savedComment(11));
  expect("two tabs: C's comment saved too", savedComment(12) === "twelve, from tab C", savedComment(12));
  expect("two tabs: earlier comments intact", savedComment(5) === "five" && savedComment(9) === "nine",
    [5, 6, 7, 8, 9, 10, 14, 15].map((n) => `${n}:${savedComment(n)}`).join(" "));

  // (f2) Two tabs both type while the server is down, then both close: the next visit must save
  // both tabs' work (each tab keeps its own backup, so neither clears the other's).
  await stopServer();
  await b.eval(`location.hash = '#paper=${PAPER}&page=16'`);
  await c.eval(`location.hash = '#paper=${PAPER}&page=17'`);
  await sleep(500);
  await commentOn(b, "sixteen, offline in B");
  await commentOn(c, "seventeen, offline in C");
  await sleep(1500);
  await b.close();
  await c.close();
  await startServer();
  const d = await tab(`${BASE}/#paper=${PAPER}&page=16`);
  await sleep(3000);
  expect("two tabs offline, both closed: both restored and saved",
    savedComment(16) === "sixteen, offline in B" && savedComment(17) === "seventeen, offline in C",
    `${savedComment(16)} / ${savedComment(17)}`);
  expect("...and their backups cleared", (await d.eval("Object.keys(localStorage).filter((k) => k.startsWith('sortes-labeller:2010.11929:')).length")) <= 1);

  // (g) A window nudged off and back again is no move.
  await d.eval(`location.hash = '#paper=${PAPER}&page=13'`);
  await sleep(1500);
  await d.click("#page-image");
  await d.key("ArrowDown", "ArrowDown", 8);
  await d.key("ArrowUp", "ArrowUp", 8);
  await sleep(1500);
  expect("nudged off and back: nothing saved", saved().pages["13"] === undefined, JSON.stringify(saved().pages["13"]));
} finally {
  if (server) await stopServer();
  chrome.kill();
  rmSync(labels, { recursive: true, force: true });
}
console.log(failures ? `${failures} check(s) failed` : "all checks pass");
process.exit(failures ? 1 : 0);
