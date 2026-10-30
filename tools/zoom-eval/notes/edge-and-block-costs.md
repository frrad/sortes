# Edge-crossing and block-aware crop costs: handover notes

Status: stopped before any code. These are design notes from reading the model, for whoever
picks this up.

## Facts that shape the design

- **Mask resolution.** Masks are coarse: the padded frame's long side is 256 cells
  (`ANALYSIS_LONG_SIDE`), about 3.2 pt per cell on a letter page.
  - A text line is about 3–4 cells tall, and the white space between lines is often under one
    cell, so consecutive lines usually touch in the mask.
  - Gaps between paragraphs (6 pt or more) are about 2 cells.
  - So an edge "between lines" mostly doesn't exist at this resolution. "Between paragraphs" or
    "between blocks" does. Consider segmenting at a higher resolution (e.g. render a second mask
    at 2×) if line-level edges matter.
- **Where the cost is computed.** Each page's cost at each window position comes from
  `CropModel.Solver.precompute()` (`cost[p * positions + pos]`).
  - Any new term must go there, and also into `ownCrop`, `assign` and `pageCrop`/`score`, which
    each compute the page cost separately.
  - `PaddedPage` holds the summed-area table in frame coordinates (the mask centred in padding).
- **The automatic zoom.** It only counts cut cells (`withinBudget`), so a placement-only term
  won't move a paper's zoom. Adam's zoom (0.77 against 0.795) would need the zoom budget to see
  breaks too.

## Planned implementation

1. **Weights.** Add `lambda` (edge crossing) and `kappa` (block break) to `CropModelWeights`,
   both defaulting to 0, so READER is unchanged. Also add them to `with`/`WEIGHT_NAMES`,
   `sampleWeights` and `toMap`. Old labels without the keys read as 0.
2. **Edge crossing.** For a window at (x, y, w, h), use `EdgeSlices`' definition per edge: an edge
   cell that is inked with ink directly beyond it.
   - Build four boolean grids in frame coordinates (top: ink(x, y) and ink(x, y − 1); bottom,
     left and right likewise), with prefix sums along rows (top, bottom) and columns (left,
     right). That makes each position O(1).
   - Term: λ × (top/w + bottom/w + left/h + right/h), the fraction of each edge that slices ink.
3. **Blocks.** Use connected components of the mask after a dilation of d cells. Measure counts
   and sizes at d = 0, 1, 2 on real pages before choosing.
   - Expect text columns to merge into one block at d ≥ 1. Then κ only tells small blocks apart
     (figures, captions, footnotes, furniture), which is what Adam pp. 6–7 and the title pages
     need.
   - A block is broken by a window if the window intersects its bounding box without containing
     it.
   - For every position at once: each block adds +1 over the window positions that intersect its
     box and −1 over those that contain it, in a 2-D difference array. That's O(blocks +
     positions).
   - Term: κ × (number of broken blocks). The cut ink is already counted by α.
   - Segment once per page, lazily when κ > 0.
4. **Variants.** Run `furniture+marks`, and `marks` without furniture, with λ and κ to test
   whether blocks can replace `PageFurniture`.
   - Furniture probably can't be dropped outright: the head mostly hurts through `offCentre`
     (kept ink's bounding box) and the zoom budget, not through the cut term. A block-based
     `offCentre` (ignoring small whole blocks at the edge) might replace it.
5. **Measures.** Fit rounds 1–3 with leave-one-round-out (as in #35), `EdgeSlices` at auto and at
   0.7 (`summary.tsv` `slicedLinePages`), and the specific pages: Adam pp. 6–7, Attention p1,
   DDPM p1, BatchNorm p11. Before/after renders go in `~/sortes-review/model/`.
