// Label state for the labeller, as pure functions of the saved schema (labels/README.md), shared
// by the page and its tests (`node --test tools/zoom-eval/labeller/labels.test.js`).
"use strict";

(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.LabellerLabels = factory();
})(typeof self !== "undefined" ? self : this, function () {
  function emptyLabels(id) {
    return { schema: 1, paper: id, labelledAgainst: null, idealScale: null, comment: "", pages: {} };
  }

  function emptyPage() {
    return { verdict: null, tags: [], comment: "", idealCentre: null };
  }

  function pageLabel(labels, n) {
    return labels.pages[String(n)] || emptyPage();
  }

  function isEmpty(label) {
    return !label.verdict && label.tags.length === 0 && !label.comment && !label.idealCentre;
  }

  /** How close (points) a window centre must be to the model's to count as not moved. */
  const SAME_CENTRE = 0.5;

  /**
   * [labels] with page [n] changed by [change]. An ideal centre within [SAME_CENTRE] of
   * [modelCentre] is no move at all: nudging the window off and back again records nothing.
   */
  function withPage(labels, n, change, modelCentre) {
    const label = Object.assign({}, pageLabel(labels, n), change);
    label.tags = [...new Set(label.tags)].sort();
    const c = label.idealCentre;
    if (c && modelCentre && Math.hypot(c.x - modelCentre.x, c.y - modelCentre.y) <= SAME_CENTRE) {
      label.idealCentre = null;
    }
    const pages = Object.assign({}, labels.pages);
    if (isEmpty(label)) delete pages[String(n)];
    else pages[String(n)] = label;
    return Object.assign({}, labels, { pages });
  }

  /** What this tab changed and hasn't saved yet: page numbers, and paper-level fields. */
  function noChanges() {
    return { pages: [], paper: [] };
  }

  function addChange(changes, kind, key) {
    const list = changes[kind].includes(key) ? changes[kind] : changes[kind].concat([key]);
    return Object.assign({}, changes, { [kind]: list });
  }

  /**
   * The labels to save when the server's copy ([server]) is newer than the one this tab started
   * from: the server's, with this tab's unsaved [changes] taken from [local] on top. Pages and
   * fields this tab didn't touch keep what the server has, so a stale tab can't overwrite them.
   */
  function merge(server, local, changes) {
    const merged = Object.assign({}, server, { pages: Object.assign({}, server.pages) });
    for (const field of changes.paper) merged[field] = local[field];
    for (const n of changes.pages) {
      const key = String(n);
      if (local.pages[key]) merged.pages[key] = local.pages[key];
      else delete merged.pages[key];
    }
    return merged;
  }

  return { emptyLabels, emptyPage, pageLabel, isEmpty, withPage, noChanges, addChange, merge, SAME_CENTRE };
});
