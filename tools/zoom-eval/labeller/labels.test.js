// Run: node --test tools/zoom-eval/labeller/labels.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const L = require("./labels.js");

const model = { x: 306, y: 407.16 };

test("a comment is kept, and an emptied page is dropped", () => {
  let labels = L.emptyLabels("p");
  labels = L.withPage(labels, 3, { comment: "too tight on top" }, model);
  assert.strictEqual(L.pageLabel(labels, 3).comment, "too tight on top");
  labels = L.withPage(labels, 3, { comment: "" }, model);
  assert.deepStrictEqual(labels.pages, {});
});

test("labels are changed as a copy", () => {
  const before = L.emptyLabels("p");
  const after = L.withPage(before, 1, { verdict: "good" }, model);
  assert.deepStrictEqual(before.pages, {});
  assert.strictEqual(after.pages["1"].verdict, "good");
});

test("a window nudged back to the model's centre is no move", () => {
  // ViT pages 1-3 were saved like this: the model's own centre, rounded to 0.1 pt.
  const labels = L.withPage(L.emptyLabels("p"), 1, { idealCentre: { x: 306, y: 407.2 } }, model);
  assert.deepStrictEqual(labels.pages, {});
  const moved = L.withPage(L.emptyLabels("p"), 1, { idealCentre: { x: 306, y: 402.2 } }, model);
  assert.deepStrictEqual(moved.pages["1"].idealCentre, { x: 306, y: 402.2 });
  // ...but a comment on the page survives either way.
  const commented = L.withPage(L.emptyLabels("p"), 1, { comment: "ok", idealCentre: { x: 306, y: 407.2 } }, model);
  assert.strictEqual(commented.pages["1"].idealCentre, null);
  assert.strictEqual(commented.pages["1"].comment, "ok");
});

test("tags are kept unique and sorted", () => {
  const labels = L.withPage(L.emptyLabels("p"), 2, { tags: ["side-cut", "top-cut", "side-cut"] }, model);
  assert.deepStrictEqual(labels.pages["2"].tags, ["side-cut", "top-cut"]);
});

test("a stale tab's save keeps what it didn't touch", () => {
  // The server has comments from another tab on pages 1-2; this tab only changed page 3 and the zoom.
  const server = L.withPage(L.withPage(L.emptyLabels("p"), 1, { comment: "from the other tab" }), 2, { verdict: "bad" });
  let local = L.withPage(L.emptyLabels("p"), 3, { comment: "mine" }, model);
  local = Object.assign({}, local, { idealScale: 0.8 });
  let changes = L.addChange(L.addChange(L.noChanges(), "pages", 3), "paper", "idealScale");
  const merged = L.merge(server, local, changes);
  assert.strictEqual(merged.pages["1"].comment, "from the other tab");
  assert.strictEqual(merged.pages["2"].verdict, "bad");
  assert.strictEqual(merged.pages["3"].comment, "mine");
  assert.strictEqual(merged.idealScale, 0.8);
  // A page this tab cleared is cleared on the server too.
  changes = L.addChange(L.noChanges(), "pages", 1);
  assert.strictEqual(L.merge(server, L.emptyLabels("p"), changes).pages["1"], undefined);
});

test("changes are recorded once", () => {
  const c = L.addChange(L.addChange(L.noChanges(), "pages", 3), "pages", 3);
  assert.deepStrictEqual(c.pages, [3]);
});
