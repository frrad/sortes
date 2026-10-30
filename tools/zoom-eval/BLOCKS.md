# The block-aware crop model (`CropConfig.TUNED`)

What `CropConfig.TUNED` is since this round, why, and how it scores. The reader still runs
`CropConfig.READER`; adopting `TUNED` is the one-line change in `analyzePaper`'s default.
`SPEC.md` describes the cost and solver this builds on.

## The problem

The cost in `SPEC.md` counts *ink area cut*. It can't tell "dropped a whole small thing cleanly"
(a conference footer, a page number: fine) from "sliced through a thing" (the top of a figure,
half a line of text: bad). On the owner's labels that showed up as:

- Adam pp. 6–7: the auto zoom (0.770) cut the figure titles and plot frames, only 0.5 % of the
  page's ink. The owner zoomed out to 0.795 and moved the window up: "figure was cropped at the
  top".
- BatchNorm p11: the shared window cut the right columns of the wide table (1.4 %): "cut off
  some table unnecessarily!".
- Shannon (a scan): 14 of 45 pages had a window edge through a line of text.
- At scale 0.7, `EdgeSlices` found 74 % of corpus pages with a line sliced.

The auto zoom had the same blind spot: its budget (cut ≤ 0.5 % of the ink, then +0.02) is
also pure area, so nothing made it loosen for a sliced figure, and its +0.02 margin was the only
thing keeping text off the screen edge.

## The model

Two ideas, both general, no paper- or page-specific rules.

### Blocks: keep or drop whole things

Each page is segmented into **blocks** (`PageBlocks`): connected pieces of ink, where ink
within `blockGap` = 2 blank cells (about 6 pt) belongs together. At the analysis resolution
(about 3 pt per cell) that joins the lines of a paragraph and the paragraphs of a section, and
leaves headings, figures, captions, tables, footnotes and columns as blocks of their own. A block
is its bounding box; a connected piece has ink in every row and column of its box.

A window **splits** a block when it overlaps the block's box without containing it. Each split
block costs

    kappa · min(1, cut ink of the block / (splitInk · median page ink))

with `kappa` = 0.2 (about ten text lines of cut ink) and `splitInk` = 0.005 (about a quarter
of a text line). So:

- a block kept whole, or dropped whole, costs nothing here, only its ink as before;
- a sliced figure title, table row or line of text costs the whole `kappa`;
- a nibble off a block's edge (the last three cells of a wide equation, the edge of a line
  drawing that sits a few points wider than the text) costs a small share. Without the
  saturation the first version of this model slid a whole paper's window 60 pt, or opened a
  second window, to avoid such nibbles.

Costs for every window position come from each block's box in O(blocks + positions per block)
(`PaddedPage.splitCosts`), so the solver's precompute barely changes. Kept ink of a block is
counted over its box with the page's summed-area table, which under-counts the cut where two
boxes overlap (an L-shaped paragraph beside a figure); that is rare and errs on the cheap side.

With blocks, the convex per-page term ε (which let a page losing a lot earn its own window) is
redundant and off: a page earns its own window when the shared one would split its blocks by
more than a window costs (γ + δ₂·2 + δ). That is exactly BatchNorm p11.

### Zoom from the same objective

Instead of the cut budget, the automatic zoom minimises

    cost(scale) / pages  +  zeta · scale

over the scales, with `zeta` = 0.5: a **legibility reward** for zooming in, paid for in cut
and split ink. Tightening is free while only margins go, then every page's body starts
splitting at once, so the optimum sits at the cliff where the pages' blocks just fit; a figure
page or a title page can be sacrificed when the rest of the paper gains enough (DiLoCo p1,
Chinchilla p1), and a couple of sliced figures in 15 pages can't be (Adam). `autoMargin` = 0.01
then keeps the content a few points off the screen edge; 0.02 was consistently too loose
against the owner's zooms and 0 too tight.

The search is local (`CropModel.autoByCost`): from the cut budget's own bound, coarse steps of
0.02 either way while the objective improves (allowing two worse steps), then every scale around
the best. Eight to fifteen solves per paper, against the old search's three to six.

### Two small things the labels asked for

- `beta` 0.1 → 0.5: the owner's "little off left-right" moves are 8–12 pt, which at β 0.1
  cost less than a thousandth of a page of ink. At 0.5 the shared window centres the body and a
  wide one-off table gets its own window, instead of everyone sliding 3 pt to fit it. γ 0.1
  still keeps half-empty pages (YOLO p10, Adam pp. 11 and 15) on the paper's window.
- `delta` 0 → 0.005 per switch: with blocks, two windows a few cells apart can tie on most pages,
  and the break term alone let pages alternate between them for free (LDM pages 1–8 went
  1-2-1-2). A small cost per switch keeps near ties on the previous window; a real two-sided
  layout (PDLT, GDL, Shannon) still pays only 0.15 for 30 switches against a gain of 0.4 or more.

## Weights

| weight | READER | TUNED | role |
| --- | --- | --- | --- |
| α | 1 | 1 | cut ink, in pages of ink |
| β | 0.1 | 0.5 | off-centredness of the kept ink |
| ε, a | 300, 0.01 | 0, 0.01 | convex per-page cut (off: blocks do this) |
| γ | 0.05 | 0.1 | per window beyond the first |
| δ, δ₂ | 0, 0.02 | 0.005, 0.02 | per switch; per break of the two-page pattern |
| κ, `splitInk`, `blockGap` | 0 | 0.2, 0.005, 2 | per split block, saturating; segmentation gap |
| ζ, `autoMargin` | 0, 0.02 | 0.5, 0.01 | legibility reward; margin on the chosen scale |

READER's crops are unchanged: with κ = ζ = 0 the new code paths aren't taken.

## Scores

(filled in below from `zoom-eval fit` and `zoom-eval review`)
