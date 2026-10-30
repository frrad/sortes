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

Each page is segmented into **blocks** (`PageBlocks`): runs of ink along a row with at most
`blockGap` = 2 blank cells (about 6 pt) between them are one piece, and pieces in rows at most
3 apart that come within 3 columns of each other are one block (a union-find over the runs: a
few hundred microseconds a page). At the analysis resolution (about 3 pt per cell) that joins
the lines of a paragraph and the paragraphs of a section, and leaves headings, figures,
captions, tables, footnotes and columns as blocks of their own. A block of fewer than 8 cells
(`MIN_BLOCK_INK`: a speck of scan dirt, a stray dot, about half a glyph) is ink to cut or keep
but not a thing to keep whole; without that, Shannon's specks beside the text moved its windows
by a cell.

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

The cut is counted from each block's own cells (a summed-area table per block over its box),
never from its box, so a frame around other content, or an L-shaped paragraph beside a figure,
is charged only for its own ink. `splitInk` is capped at half the block's ink, so a small block
(an equation number, a short heading) cut in two costs a whole split too.

Costs for every window position (`PaddedPage.splitCosts`): a dense block (half its box or more
inked: text) costs a whole split at every position whose window overlaps its box without
containing it, filled through a 2-D difference array, except in a thin ring around the
containing positions where the window cuts fewer than the block's unit off one side and the
share is computed exactly; a sparse block (a figure, a frame) is computed exactly at every
overlapping position, row by row from a column profile, since a window inside its box can keep
none of its cells. The work is about linear in the positions plus the blocks' rings, plus the
sparse blocks' overlap rectangles.

With blocks, the convex per-page term ε (which let a page losing a lot earn its own window) is
redundant and off: a page earns its own window when the shared one would split its blocks by
more than a window costs (γ + δ₂·2 + δ). That is exactly BatchNorm p11.

Pages outside a long paper's 60-page sample (`SampledPaper.cropFor`) follow the same rule: the
shared window is kept if it cuts at most the allowance *and* no block; otherwise the page's own
best window takes over when its per-page cost plus γ is lower. Before, that gate was by cut ink
alone, so a shared window slicing a line under the 1 % allowance was kept, and a clean own window
that dropped a footer lost to a slicing shared one.

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

The search is local (`CropModel.autoByCost`): from the cut budget's own bound, steps of 0.01
either way while the objective improves (allowing one worse step), then the scales either side
of the best. Six to nine solves per paper, against the old search's two or three.

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

### The owner's labels, rounds 1–3 (107 labelled pages, 16 papers)

`zoom-eval fit --trials=0` with the tolerant scorer (README.md, "Fitting to the labels").
"Agreeing" is agreement in principle: window within 8 pt of the owner's, at most 0.25 % of the
ink they keep cut, zoom within 0.015.

| | pages agreeing | zooms agreeing | score (lower is better) | r1 / r2 / r3 |
| --- | --- | --- | --- | --- |
| `READER` | — | — | 143.4 | 46.3 / 42.3 / 54.8 |
| `TUNED` before (furniture, marks, γ 0.1) | 99 of 107 | 14 of 16 | 36.6 | 21.4 / 8.2 / 7.0 |
| `TUNED` now (this model) | **103 of 107** | 15 of 16 | 43.0 | 20.7 / 16.1 / 6.2 |

The four pages that still disagree, and why:

- **Attention p1 and DDPM p1** (title pages). The owner's compromise slices the title or the
  notice to keep the conference footer. The model drops one whole block instead (the footer,
  as before). Both crops cut something; the owner's keeps the footnote. At one window size per
  paper this is the kind of page the owner has said may sacrifice something, and a rule that
  prefers the bottom of a title page would be a special case.
- **GDL p6** (half-weight label): the window shifts 60 pt to keep a wide figure whole, rather
  than nibble it and centre the text. Fixable by a larger `splitInk`, at the price of Adam's
  figures; the owner's own label was made at a looser zoom where the figure fit.
- **LDM's zoom**, 0.845 against 0.825: three pages have figures wider than the 0.825 window,
  so the legibility reward loses to their split cost. The owner judged four pages at 0.825 and
  did not see those figures.

The summed score is higher than before (43 against 37) because round 2's zooms land 0.005–0.01
away from the owner's (ResNet 0.820, BERT 0.845, GDL 0.800, LDM 0.845), each costing a unit or
two, and GDL's three windows. Where the owner gave an explicit move or a zoom of their own, the
model is better or equal: Adam's zoom 0.785 (0.795; before 0.770) with pp. 6–7's figures whole,
BatchNorm 4 of 4 with p11's table whole in a window of its own, Shannon with a clean left/right
alternation.

**Held out.** These weights were chosen from a small grid scored on all three rounds, so there
is no held-out round among them. Round 4 (six papers the owner is labelling on the *previous*
`TUNED`'s crops) is the honest test; it will be scored before any fitting on it.

### The whole corpus (29 papers, 718 pages; `zoom-eval review`, ~/sortes-review/model/fable)

| | pages with a window edge through a line (`EdgeSlices`, ≥ 3 cells) |
| --- | --- |
| at the automatic zoom, `TUNED` before | 25 (Shannon 14, SuperGLUE 5, MMLU 2, Adam, Attention, GDL, NTK) |
| at the automatic zoom, `TUNED` now | **0** |
| at a manual zoom of 0.7, before | 491 |
| at a manual zoom of 0.7, now | **286** |

At 0.7 the body of a letter page doesn't fit, so some line must be sliced on most text pages;
the block term puts the edge between paragraphs or sections where there is one to use (the
"at-0.7" board shows both placements side by side).

Zooms moved by at most 0.02 on any paper: tighter on 12, looser on 8, unchanged on 9. One-off windows
appear on BatchNorm p11 (the table) and LDM p20 (a wide figure). GDL takes three windows
instead of two: the third is the even pages' window 45 pt lower, for three pages (4, 16, 18)
whose margin figures hang below the body and would be cut through by the even pages' window;
the owner's round-2 complaint on GDL was exactly such cut content (pp. 4 and 8), so this is the
model choosing the figure over staying put, as designed. A dearer window doesn't change it
without losing what the owner asked for elsewhere: γ 0.15 or 0.2 takes away BatchNorm p11's
window (99 of 107 agreeing), and only 0.2 brings GDL back to two; δ₂ 0.04 changes nothing.

### Runtime

The block segmentation is one pass per page (cached per page), the split costs are linear in
blocks and positions, and the cost-based zoom search solves 8–15 scales against 3–6 before.
Host times for the analysis plus the automatic solve, not counting rendering (`summary.tsv`'s
`millis`, `zoom-eval --columns` with a `config=tuned` column against the previous weights):

| paper | before | now |
| --- | --- | --- |
| Attention, 15 pages | 419 ms (first paper, warming up) | 357 ms |
| GPT-3, 60 of 75 pages sampled | 93 ms | 470 ms |
| Shannon, 45 pages | 79 ms | 330 ms |
| PDLT, 60 of 237 pages sampled | 428 ms | 1178 ms |

So three to five times the previous solve, almost all of it the extra scales the zoom search
visits. On the tablet, where PDLT's solve took 386 ms before, expect about a second on a long
book, on top of the 0.6–0.8 s of rendering. If that matters, the coarse step of the zoom search
is the knob (`CropModel.COARSE_STEP`).
