// Checks, in a real browser, that the labeller's tablet preview shows exactly the dashed window
// drawn on the page at every step: loading, turning pages (also faster than images arrive),
// switching papers and changing the zoom. Each step compares the preview's pixels with the drawn
// window rendered afresh from the page image.
//
// Usage: [SLOW=1] node preview-check.mjs <chromium binary> <port>
//   Run it against a test server with a *copy* of the labels (it labels ViT as it goes), e.g.
//     zoom-eval labeller-serve --labels=/tmp/labels-copy --port=8767 ...
//   SLOW=1 throttles the network and disables the cache, like a remote labeller.
// Needs papers 2010.11929 (with saved windows on pages 1-3) and 1506.02640 exported.
import { spawn } from "node:child_process";
const [bin, port] = process.argv.slice(2);
const chrome = spawn(bin, ["--remote-debugging-port=9335", "--no-sandbox", "about:blank"], { stdio: "ignore" });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;
try {
  let t; for (let i = 0; i < 50; i++) { try { t = await (await fetch("http://127.0.0.1:9335/json/list")).json(); break; } catch { await sleep(200); } }
  const ws = new WebSocket(t.find((x) => x.type === "page").webSocketDebuggerUrl);
  await new Promise((r) => ws.addEventListener("open", r));
  let id = 0; const pend = new Map();
  ws.addEventListener("message", (m) => { const d = JSON.parse(m.data); if (pend.has(d.id)) { pend.get(d.id)(d); pend.delete(d.id); } });
  const send = (method, params = {}) => new Promise((r) => { const i = ++id; pend.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
  const evalJs = async (e) => (await send("Runtime.evaluate", { expression: e, returnByValue: true, awaitPromise: true })).result.result.value;
  const key = async (k, code) => { await send("Input.dispatchKeyEvent", { type: "keyDown", key: k, code, text: k }); await send("Input.dispatchKeyEvent", { type: "keyUp", key: k, code }); };
  await send("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  // Like the owner's: over the internet, so page images take a while to arrive.
  if (process.env.SLOW) {
    await send("Network.enable");
    await send("Network.setCacheDisabled", { cacheDisabled: true });
    await send("Network.emulateNetworkConditions", { offline: false, latency: 400, downloadThroughput: 250000, uploadThroughput: 250000 });
  }
  // The drawn dashed window, rendered from a fresh copy of the page image, against the preview.
  const compare = `(async () => {
    const svg = document.getElementById('overlay');
    const mine = document.getElementById('mine');
    const vb = svg.viewBox.baseVal;
    const src = document.getElementById('page-image').src;
    const img = new Image(); img.src = src; await img.decode();
    const k = img.naturalWidth / vb.width;
    const preview = document.getElementById('preview');
    const c = document.createElement('canvas'); c.width = preview.width; c.height = preview.height;
    const ctx = c.getContext('2d'); ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height);
    const x = +mine.getAttribute('x'), y = +mine.getAttribute('y'), w = +mine.getAttribute('width'), h = +mine.getAttribute('height');
    ctx.imageSmoothingQuality = 'high';
    ctx.drawImage(img, x * k, y * k, w * k, h * k, 0, 0, c.width, c.height);
    const a = ctx.getImageData(0, 0, c.width, c.height).data;
    const b = preview.getContext('2d').getImageData(0, 0, c.width, c.height).data;
    let diff = 0; for (let i = 0; i < a.length; i += 4) diff += Math.abs(a[i] - b[i]);
    return { page: location.hash, diff: +(diff / (a.length / 4)).toFixed(2) };
  })()`;
  const check = async (step) => {
    await sleep(process.env.SLOW ? 6000 : 1200);
    const r = await evalJs(compare);
    const ok = r.diff < 1.0;
    if (!ok) failures++;
    console.log(`${ok ? "ok  " : "FAIL"} ${step.padEnd(34)} ${r.page} mean pixel diff ${r.diff}`);
  };
  await send("Page.navigate", { url: `http://127.0.0.1:${port}/#paper=2010.11929&page=1` });
  if (process.env.SLOW) await sleep(8000);
  await check("load a page with a saved window");
  await key("j", "KeyJ"); await check("next page (saved window)");
  await key("j", "KeyJ"); await check("next page (saved window)");
  await key("j", "KeyJ"); await check("next page (no label)");
  await key("k", "KeyK"); await check("previous page");
  await evalJs("(() => { const z = document.getElementById('zoom'); z.value = Number(z.value) + 8; z.dispatchEvent(new Event('input')); })()");
  await check("zoom changed");
  await key("n", "KeyN"); await check("next paper");
  await key("j", "KeyJ"); await check("next page on the new paper");
  await send("Page.navigate", { url: `http://127.0.0.1:${port}/#paper=2010.11929&page=2` });
  await check("hash change to another paper");
  // Quicker than the images arrive.
  await key("j", "KeyJ"); await sleep(150); await key("j", "KeyJ"); await sleep(150); await key("k", "KeyK");
  await check("pages turned faster than they load");
  await key("n", "KeyN"); await sleep(100); await key("j", "KeyJ");
  await check("paper switched then page turned at once");
  await evalJs("(() => { const z = document.getElementById('zoom'); z.value = Number(z.value) - 5; z.dispatchEvent(new Event('input')); })()");
  await key("j", "KeyJ");
  await check("zoom then page turn at once");
  ws.close();
} finally { chrome.kill(); }
console.log(failures ? `${failures} step(s) failed` : "all steps match");
process.exit(failures ? 1 : 0);
