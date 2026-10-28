# Crop model: spec

The owner's crop model. It lives once, in the app: `CropModel` and `CropModelWeights` in
`app/src/main/java/dev/sortes/app/zoom/CropModel.kt`, on the shared `PaddedPage`. The offline
harness `zoom-eval` (see `README.md`) compiles and calls that same code.

## Setting

Everything is measured on the analysis masks the reader already uses (`zoom/`): each page
rendered at one density for the paper, so the largest page padded to the screen's aspect
ratio is 256 cells on its longer side (`analysisDensity`, `maskSize`). A cell is ink if its
luminance is more than 6 below the page's paper colour (`InkMask.fromArgb`; the paper colour
is the most common luminance of at least 192, so about 249 on white paper). The page is
centred in a frame with the screen's aspect ratio, in whole cells (`PaddedPage`). On a
US-letter page at the Boox aspect (0.769), 1 cell ≈ 3.1 pt.

The threshold used to be a fixed 224. That dropped the faint anti-aliased edges of thin
glyphs, so a window could clip a page number on screen while the analysis counted nothing
cut. Example: the bottom row of Attention p3's "3" is luminance 226 at analysis resolution.
Measuring relative to the paper colour keeps off-white scans from turning into solid ink.

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

    cost = Σ_p [ α·cut_p + β·off_p + ε·max(0, pageCut_p − a)² ]  +  γ·(K − 1)  +  δ·switches  +  δ₂·breaks

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
- **K − 1**: windows beyond the first.
- **switches**: page turns where the window changes, i.e. k(p) ≠ k(p−1).
- **breaks**: pages whose window differs from the one two pages back, i.e. k(p) ≠ k(p−2).
  Breaks penalise jitter and one-off windows (a one-off window costs 2 breaks, and so does
  each later stretch on another window), but a strict left/right alternation costs nothing.
  That lets two-sided layouts emerge without a parity special case. Without this term, K = 2
  triggered on Latent Diffusion: two windows 6–9 pt apart, with 15 switches, to win a few
  thousandths of centring.

Blank pages cost nothing in any window. The assignment step's tie-break keeps the previous
page's window, or the window from two pages back when δ₂ > 0.

## Scale

The scale is fixed per paper. It uses the same rule as the reader's auto zoom, applied to the
crops the model will actually use. That is the tightest scale on `CropAnalysis.defaultScales()`
at which the model's solved crops cut at most `AUTO_MAX_LOST` (0.5%) of the paper's ink,
loosened by `AUTO_MARGIN` (+0.02). The reader's own curve gives a lower bound on the scale,
because each page's best window loses least. The search starts there and steps looser,
re-solving each time, until the model's crops are within budget. Usually it takes 0–2 steps.

Because the model keeps windows put, it can cut more than per-page optima, so its scale is
never tighter than the per-page anchor placement's. Across the corpus it came out equal, or 0.005 looser on a few
papers.

## Solver

At a fixed scale, positions are integer cells on the reference frame, which is the paper's
most common page shape. Pages of other shapes use the same position as a fraction of their
frame.

1. Precompute `cost[p][pos] = α·cut + β·off + ε·excess²` for every page and every position. Ink counts
   come from a summed-area table, and the kept-ink bounding box comes from four binary
   searches on it.
2. **K = 1**: exact. Take the position minimising Σ_p cost[p][pos] over the full grid.
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
- Precompute: O(P·G·log 256).
- Each Lloyd round: O(K·P·G) for updates and O(P·K²) for pair-Viterbi assignment.
- Per scale: a few tens of millions of operations.

Measured on this host for a whole paper (rendering with mutool, all four columns and the
contact sheet): 0.8–6 s. I haven't timed the solver on its own, and nothing has been profiled
on the tablet. A reader version would reuse `CropAnalysis`'s summed-area tables.

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
