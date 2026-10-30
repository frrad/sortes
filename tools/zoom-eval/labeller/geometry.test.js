// Run: node --test tools/zoom-eval/labeller/geometry.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const G = require("./geometry.js");

// A US-letter page padded to a 3:4 screen: 612 x 816 frame, page 12 pt down.
const page = {
  page: { width: 612, height: 792 },
  frame: { width: 612, height: 816 },
  region: { left: 56, top: 70, width: 500, height: (500 / 612) * 816 },
};

const close = (a, b, msg) => assert.ok(Math.abs(a - b) < 1e-9, `${msg}: ${a} vs ${b}`);

test("padding centres the page in its frame", () => {
  assert.deepStrictEqual(G.padding(page), { x: 0, y: 12 });
});

test("without a moved window, the owner's window is the model's", () => {
  const w = G.windowFor(page, null, 500 / 612);
  close(w.left, 56, "left");
  close(w.top, 70, "top");
  close(w.width, 500, "width");
  close(w.height, (500 / 612) * 816, "height");
  assert.deepStrictEqual(w.centre, G.modelCentre(page));
});

test("a moved window is centred where the owner put it", () => {
  const w = G.windowFor(page, { x: 300, y: 380 }, 0.8);
  close(w.centre.x, 300, "x");
  close(w.centre.y, 380, "y");
  close(w.left, 300 - 0.4 * 612, "left");
});

test("a window never leaves the frame", () => {
  const w = G.windowFor(page, { x: -500, y: 2000 }, 0.8);
  const f = G.inFrame(page, w);
  close(f.left, 0, "left edge");
  close(f.top + f.height, 816, "bottom edge");
});

test("the preview draws exactly the drawn window, in image pixels", () => {
  const w = G.windowFor(page, { x: 310, y: 400 }, 0.8);
  const drawn = G.inFrame(page, w);
  const px = G.inImage(page, w, 900);
  const k = 900 / 612;
  close(px.left, drawn.left * k, "left");
  close(px.top, drawn.top * k, "top");
  close(px.width, drawn.width * k, "width");
  close(px.height, drawn.height * k, "height");
});

test("a zoom change resizes about the same centre", () => {
  const a = G.windowFor(page, { x: 300, y: 400 }, 0.8);
  const b = G.windowFor(page, { x: 300, y: 400 }, 0.7);
  assert.deepStrictEqual(a.centre, b.centre);
  close(b.width, 0.7 * 612, "width");
});
