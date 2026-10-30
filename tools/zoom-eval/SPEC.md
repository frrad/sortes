# Crop model: spec

The owner's crop model. It lives once, in the app: `CropModel` and `CropModelWeights` in
`app/src/main/java/dev/sortes/app/zoom/CropModel.kt`, on the shared `PaddedPage`. The offline
harness `zoom-eval` (see `README.md`) compiles and calls that same code.

## Setting

Everything is measured on the analysis masks the reader already uses (`zoom/`): each page
rendered at one density for the paper, so the largest page padded to the screen's aspect
ratio is 256 cells on its longer side (`analysisDensity`, `maskSize`). A cell is ink if its
luminance is below `InkMask.inkThreshold`, computed per page:
- **Paper colour:** the median luminance of the page's light pixels (those ≥ 192).
- **Grain:** ink only darkens, so the paper's grain shows in how far its light pixels stray
  *above* that median. The spread is their 99th percentile minus the median.
- **Threshold:** the paper colour minus max(6, spread + 2). On clean white paper that is 249.

Consequences:
- **Faint glyph edges count.** The old fixed threshold of 224 dropped them, so a window could
  clip a page number on screen while the analysis counted nothing cut. Example: the bottom row
  of Attention p3's "3" is luminance 226 at analysis resolution (`InkMaskTest`).
- **Light shading on white paper is ink.** A code listing or table row at 240 is content, and
  cutting it shows.
- **Grain on an off-white scan is paper.** At 245 ± 8 the threshold comes out around 235, so
  the auto zoom doesn't collapse to the whole page (`InkMaskTest`). The cost is that faint
  shading lighter than the grain isn't seen there.
- **A real scan works.** The corpus's Shannon 1948 (an image-only BSTJ scan) zooms to 1.18×,
  against 1.20× at the old threshold.

The page is centred in a frame with the screen's aspect ratio, in whole cells (`PaddedPage`).
On a US-letter page at the Boox aspect (0.769), 1 cell ≈ 3.1 pt.

A **window** is a screen-shaped rectangle on the padded frame, given as the reader's
`CropWindow` (left, top and scale as fractions of the frame). The solver works in whole-cell
positions. `PaddedPage.windowAt` converts a position to a `CropWindow` centred on the cells as
they lie on the page, because `regionOn` centres the page exactly rather than in whole cells.
Before this, an odd number of padding cells shifted the rendered crop by up to half a cell
from the cells the analysis counted. **Every window in a paper has the
same scale** (AGENTS.md, "The PDF page is canonical"). Pages are assigned to one of
K windows W₁…W_K.

## Cost

For a scale s, windows W₁…W_K and an assignment k(p) of pages to windows:

    cost = Σ_p [ α·cut_p + β·off_p + ε·max(0, pageCut_p − a)² + κ·split_p ]  +  γ·(K − 1)  +  δ·switches  +  δ₂·breaks

- **cut_p**: the ink cells on page p outside its window, divided by the paper's median ink per
  page (over pages with any ink). The unit is "pages of ink", so a cell costs the same on every
  page of a paper, and costs are comparable between papers. A text line is about 0.02, a page
  number about 0.003–0.005, and the arXiv stamp about 0.02–0.03. **Unweighted:** every ink cell
  counts the same. See "Open questions" for weighting by distance from the text block.
- **pageCut_p**: the cut cells as a share of page p's *own* ink. Up to the allowance a (1%),
  it's free. Beyond that it costs ε times the square of the excess, so cost grows faster than
  linearly. This is how a page that would lose a lot (real lines, not a page number) can earn
  a window of its own, while many pages each losing a little still prefer to stay put. At
  ε = 300, a page losing 2.4% (1.4% over the allowance) costs 0.06: more than a one-off window
  (γ + 2δ₂ = 0.09) once the linear cut is added.
- **off_p**: how far the ink the window keeps is from being centred in it. Take the bounding box
  of the kept ink and its four gaps to the window edges (left, right, top, bottom). Then

      off_p = |gap_left − gap_right| / (2·w) + |gap_top − gap_bottom| / (2·h)

  where w and h are the window's size in cells. That is the displacement of the kept ink's
  centre from the window's centre, in screen widths plus screen heights. It is 0 when centred
  (or when nothing is kept) and at most 1. It uses the bounding box rather than the centroid
  because what reads as lopsided is the white space at the edges.
- **split_p**: how badly the window cuts into the page's blocks (`PageBlocks`: connected ink,
  joined across `blockGap` blank cells). For each block the window overlaps without containing,
  the ink cut off it as a share of `splitInk` of the median page's ink, capped at 1. Keeping or
  dropping a whole block costs nothing here. See `BLOCKS.md`. Off (κ = 0) in `READER`.
- **K − 1**: windows beyond the first.
- **switches**: page turns where the window changes, i.e. k(p) ≠ k(p−1).
- **breaks**: pages whose window differs from the one two pages back, i.e. k(p) ≠ k(p−2).
  Breaks penalise jitter and one-off windows (a one-off window costs 2 breaks, and so does
  each later stretch on another window), but a strict left/right alternation costs nothing.
  That lets two-sided layouts emerge without a parity special case. Without this term, K = 2
  triggered on Latent Diffusion: two windows 6–9 pt apart, with 15 switches, to win a few
  thousandths of centring.

Blank pages cost nothing in any window, so ties in the assignment are common. They go:
- with δ₂ > 0, to the window two pages back, continuing the pattern;
- otherwise, to the previous page's window.

Ties between window positions go to the one nearest the frame's centre, so an all-blank paper
below scale 1 gets a centred window.

## Scale

The scale is fixed per paper: every scale of `ZoomScales.defaultScales()` from 0.60 to 0.97
is solved without the text model's page costs, the one minimising the cost per page plus a
legibility cost is loosened by `autoMargin` (+0.01), and the crops there are solved with the
page costs (`CropModel.Prepared.auto`). The legibility cost is ζ·scale while the body text is
small, earning less once it is bigger than 28 px on the Boox's screen (`Legibility`, `PageText`,
[notes/text-size](notes/text-size/README.md)). The search is exhaustive over that range (about 75
solves; an earlier sparse walk found the same scales on the corpus) and is the only one: the
cut-budget zoom (`AUTO_MAX_LOST`) that the older reader config used is gone.

*History: the sections below describe the model as it was built; `READER`, `TUNED`,
`CropAnalysis` and the cut budget they mention were removed when the reader moved to
`CropConfig.DEFAULT` (see [notes/unified-pipeline.md](notes/unified-pipeline.md)).*

## In the reader

`analyzePaper` is the one entry point, shared with zoom-eval: it renders a sample of at most
60 pages, strips their ignorable ink, and measures each sampled page's text on a 4× render. The sample
is evenly spaced pairs of facing pages (`samplePages`): even, odd, even, odd, so both sides of a
two-sided layout are seen equally. Plain even spacing put every sampled page of a 119-page
paper on one side. It then solves the model at its automatic scale (`SampledPaper`, shared with
the harness). The resulting `PaperAnalysis` holds:
- `auto`: the crops at the automatic scale;
- `cropsAt(scale)`: a manual zoom re-solves the model at exactly that scale (cached), so a
  saved per-paper zoom still gets the model's windows and assignment;
- `place(page)`: an analysed page gets its assigned window and the cells the model counted.
  An unanalysed page (in papers over 60 pages) gets the window that costs it least, with ties
  going to the window of the nearest analysed page on the same side (odd or even). If even that
  window cuts more than the allowance a of its own ink (a landscape table or offset figure the
  sample didn't see), it gets its own best window at the same scale. The cut-ink boxes in the
  frame preview come from those same cells (`cutInk`);
- `curve()`: the zoom panel's lost-ink curve. It is the model's own cut ink at every 4th scale
  (0.02 of zoom) plus the automatic one, solved in the background while the panel is open and
  drawn as it fills in. It stops after the first point cutting more than 20%: the tighter ones
  are the slowest and nobody reads at them. The padded pages and the solver's buffer are kept
  between solves. The summary for the selected zoom is exact once its crops are solved,
  and "about" the nearest computed point before that.

The previous per-page anchored placement (`CropAnalysis.anchor`, `placeWindow`) is no longer
used by the reader. It remains for the harness's comparison column.

## Solver

At a fixed scale, positions are integer cells on a reference page: the paper's most common
mask size (not padded-frame size, since pages of different sizes can pad to the same frame by
different amounts). Other pages use the same `CropWindow` (`PaddedPage.positionOf`). Each page's
crop also carries its own `CropWindow`, centred exactly on its own cells, which is what the
reader renders.

1. Precompute `cost[p][pos] = α·cut + β·off + ε·excess²` for every page and every position. Ink counts
   come from a summed-area table, and the kept-ink bounding box comes from four binary
   searches on it.
2. **K = 1**: exact. Take the position minimising Σ_p cost[p][pos] over the full grid,
   summed page row by page row into one array.
3. **K = 2, 3**: seed with the best K−1 windows plus one more. Candidates for the extra window
   are each page's own optimum, plus, for K = 2, an odd/even split. Keep the 6 seeds whose
   immediate assignment is cheapest, then refine each one Lloyd-style:
   - **assign:** argmin per page; Viterbi over pages when δ > 0; Viterbi over (previous,
     current) window pairs when δ₂ > 0.
   - **update:** re-place each window at the exact grid argmin of its pages' summed cost.
   - **stop:** when nothing changes or the cost stops falling, with at most 20 rounds.
4. Keep the cheapest K, including the γ, δ and δ₂ terms. Windows are numbered in order of
   first use.

**Complexity.** Let P be pages, G grid positions per page (≈ (0.17·W)·(0.17·H) ≈ 33 × 43 ≈
1,400 at scale 0.83; ≈ 4,000 at 0.7) and K ≤ 3.
- Precompute: O(P·G), into one flat buffer reused across the scale search. Off-centredness for
  all positions comes from tables of the next and previous inked row and column
  (`PaddedPage.offCentres`).
- Each Lloyd round: O(P·G) for the window updates, and O(P·K³) for the pair-Viterbi
  assignment (K² states, K predecessors each).
- Per scale: a few tens of millions of operations.

**Scale search.** The bound (the tightest scale at which each page's own best window is within
budget) is found by bisecting on per-page best fits, so the reader no longer computes
`CropAnalysis`'s whole curve. The search starts at that bound and gallops looser (1, 2, 4, …
steps) until the model's crops are within budget. It then bisects back between the last miss
and the first fit, treating the model's cut as rising with zoom. That is a handful of solves
even when the bound is far off.

Measured times for the analysis plus the automatic solve, not counting rendering:

| paper | host (`summary.tsv`'s `millis`) | Boox Note Air5 C (log line, solving only) |
| --- | --- | --- |
| Attention, 15 pages | 54 ms | 248 ms |
| Latent Diffusion, 45 pages | 68 ms | — |
| GPT-3, 75 pages | 67 ms | — |
| PDLT, 60 of 471 pages sampled | — | 386 ms |

With rendering, the whole analysis on the tablet takes 0.6 s (Attention) and 0.83 s (PDLT).
Before these optimisations it took 1.1 s and 11.2 s. The zoom panel's curve, about 36 solves,
fills in within about 10 s on PDLT.

## Default weights (`CropModelWeights`)

| weight | value | reasoning |
| --- | --- | --- |
| α | 1 | Sets the unit: cost is measured in pages of ink cut. |
| β | 0.1 | A page whose content sits 10% of a screen off-centre costs 0.01, about half a text line. Centring then matters when a whole run of pages is off-centre (book margins, appendices), but it never outweighs cutting real text. |
| γ | 0.05 | About 2.5 text lines, or 10–15 page numbers. A one-off window has to rescue more than a page number or a stamp to pay for itself. An extra window for a whole run of pages (e.g. every other page of a book) pays off when it saves more than γ in total. |
| δ | 0 | Plain switches would charge a two-sided book 29 times; δ₂ does the stability job without doing that. |
| δ₂ | 0.02 | One-off windows and jitter cost 2·δ₂ per excursion on top of γ. At 0.02 (and at 0.05), K > 1 survives only on the two two-sided books, with exact parity. At 0 it also fired on Latent Diffusion (jitter), and on BatchNorm without the stamp rule (to keep the stamp). |
| ε | 300 | With this, Attention p1 gets its own window and keeps its two-line permission notice. With ε = 0 (the board's "stay put" column), p1 stays on the shared window and loses 3% of its ink. The owner chooses. |
| a | 0.01 | A page may lose 1% of its own ink (a page number, a stray mark) at no extra cost. |
| κ, `splitInk`, `blockGap` | 0, 0.005, 2 | The block term, off in the reader; `TUNED` sets κ 0.2 (`BLOCKS.md`). |
| ζ | 0 | The legibility reward, off in the reader: the zoom comes from the cut budget. `TUNED` sets 0.5. |

**Reading of γ (confirmed by the owner).** Cutting one page's page number beats giving that
page its own window: α·0.004 ≪ γ.

**Learning the weights.** For fixed crops, the cost is linear in the weights. Its feature
vector is (Σcut, Σoff, Σexcess², K−1, switches, breaks); `summary.tsv` and `<id>.pages.tsv` report its pieces.. Given
labels of the form "crops A look better than crops B", the weights can be fitted as a ranking
problem, e.g. logistic regression on feature differences, constrained to be ≥ 0. The solver
already produces A/B pairs: compare its solution at each K, or across neighbouring weight
settings.

## Open questions

- **Weighted ink.** All ink counts the same; the convex per-page term (ε) now protects pages
  that would lose a lot. Weighting cut ink by whether it's body text (near the text block, or
  on a regular line pitch) is a possible next step if ε proves too blunt.
- **Bounding box versus outliers.** A stray mark kept near an edge makes a page look
  off-centre. A trimmed bounding box (ignoring the outermost ~1% of kept ink) would be
  steadier.
- **Scale and cost.** The scale comes from the cut budget, not from the cost. Folding it in
  would need a legibility reward (e.g. −ζ·log s per page), which is another weight to learn.

## Tuning against the owner's labels (rounds 1 and 2)

Labels: `labels/ROUND-1.md` and `ROUND-2.md`. Fit: `zoom-eval fit` (see README.md).

### Prototyped, not in the reader yet

- **`PageFurniture`** (paper-level) ignores page numbers and running heads and feet.
  - It takes the outermost short block of rows at the top or bottom of a page (a line, or a line
    with a rule), clear of the rest.
  - The block must be either narrow (a page number) or at a height that recurs on 30% of the
    paper's pages (a running head or foot).
  - A page that is only furniture ends up blank and takes the usual window.
- **`IsolatedMarks`** ignores small pieces of ink (≤ 0.5% of the page's) with nothing else within
  3% of the page height: scan dirt, scan edges, stray marks.

### New weights, defaults unchanged

| weight | meaning |
| --- | --- |
| `autoMaxLost` | The automatic zoom's budget, which was the constant `AUTO_MAX_LOST`. |
| `autoMargin` | How much looser than the tightest acceptable scale the auto zoom is, which was the constant `AUTO_MARGIN`. |
| `zoomIgnoredPages` | Pages the budget leaves out (those that would lose most). |
| `autoMaxPageCut` | A per-page cap on what the auto zoom may cut. |

The fit found no robust gain from these, so they stay at their old values (off).

### `CropConfig`: the tuned set, kept out of the reader for now

`CropConfig.TUNED` holds the proposal. After round 3 it was the furniture and marks rules and
γ 0.05 → 0.1; it is now the block-aware model of `BLOCKS.md` on top of those. The harness, fit
and labeller use it.

After round 2 it had β 0.1 → 0.5 instead. Round 3, held out, refuted that (labels/ROUND-3.md):

- **The problem:** β 0.5 makes centring a half-empty page (a section's last page, the end of the
  references) worth a window of its own. The owner wants the paper's window there.
- **The evidence:** fitted on rounds 1–2 alone, the search still picks β 0.5, which scores 38.1
  on round 3. β 0.1 scores 7.5 there.
- **Rounds 1–3:** β is flat from 0.05 to 0.3. γ 0.1 is a little better than 0.05, and sets a
  higher bar for a second window, which is round 3's lesson.

| | score | pages agreeing (of 107) |
| --- | --- | --- |
| `READER` | 155.5 | — |
| β 0.5 (old `TUNED`) | 67.9 | 92 |
| β 0.1, γ 0.05 | 38.9 | 98 |
| β 0.1, γ 0.1 (`TUNED`) | 38.7 | 99 |

Leaving one round out, the set fitted on the other two scores on the held-out round:

| held out | fitted on the rest | `READER` | β 0.5 | `TUNED` |
| --- | --- | --- | --- | --- |
| round 1 | 23.2 | 46.3 | 19.5 | 23.0 |
| round 2 | 8.9 | 42.3 | 10.4 | 8.2 |
| round 3 | 38.1 | 66.8 | 38.1 | 7.5 |

β 0.5 does better on round 1 (BatchNorm pp. 8 and 11, where centring firmly helps) but much
worse on round 3. The reader keeps `CropConfig.READER` until the owner
approves; adopting `TUNED` is one line, `analyzePaper`'s default config.

