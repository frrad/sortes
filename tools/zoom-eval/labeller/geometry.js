// Window geometry for the labeller, shared by the page view and the tablet preview, so the two
// can't disagree. Pure functions of a page's exported geometry (paper.json): no DOM, testable with
// `node --test tools/zoom-eval/labeller/geometry.test.js`. Points are page points from the page's top-left; a
// page's frame is the page padded to the screen's aspect ratio, centred.
"use strict";

(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.LabellerGeometry = factory();
})(typeof self !== "undefined" ? self : this, function () {
  function clamp(v, lo, hi) {
    return hi < lo ? (lo + hi) / 2 : Math.min(hi, Math.max(lo, v));
  }

  /** The padding around [page] in its frame: from the frame's top-left to the page's. */
  function padding(page) {
    return { x: (page.frame.width - page.page.width) / 2, y: (page.frame.height - page.page.height) / 2 };
  }

  /** The centre of the model's window on [page]. */
  function modelCentre(page) {
    const r = page.region;
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  }

  /**
   * The owner's window on [page]: [scale] of the frame, centred at [centre] (page points; the
   * model's centre if null), kept inside the frame. Its left/top/width/height are page points.
   */
  function windowFor(page, centre, scale) {
    const pad = padding(page);
    const w = scale * page.frame.width;
    const h = scale * page.frame.height;
    const c = centre || modelCentre(page);
    const cx = clamp(c.x + pad.x, w / 2, page.frame.width - w / 2) - pad.x;
    const cy = clamp(c.y + pad.y, h / 2, page.frame.height - h / 2) - pad.y;
    return { left: cx - w / 2, top: cy - h / 2, width: w, height: h, centre: { x: cx, y: cy } };
  }

  /** [rect] (page points) in frame points, as the overlay draws it. */
  function inFrame(page, rect) {
    const pad = padding(page);
    return { left: rect.left + pad.x, top: rect.top + pad.y, width: rect.width, height: rect.height };
  }

  /** [rect] (page points) in the pixels of the page's frame image, [imageWidth] wide: what the preview draws. */
  function inImage(page, rect, imageWidth) {
    const k = imageWidth / page.frame.width;
    const f = inFrame(page, rect);
    return { left: f.left * k, top: f.top * k, width: f.width * k, height: f.height * k };
  }

  return { clamp, padding, modelCentre, windowFor, inFrame, inImage };
});
