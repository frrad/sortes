# Crop model: spec

A prototype of the owner's crop model, evaluated offline by `zoom-eval` (see `README.md`). It is
not wired into the reader.

## Setting

Everything is measured on the analysis masks the reader already uses (`zoom/`): each page
rendered at one density for the paper, so the largest page padded to the screen's aspect
ratio is 256 cells on its longer side (`analysisDensity`, `maskSize`). A cell is ink if its
luminance is below 224 (`InkMask.fromArgb`), and the page is centred in a frame with the
screen's aspect ratio, exactly as `CropAnalysis` pads it. On a US-letter page at the Boox
aspect (0.769), 1 cell ≈ 3.1 pt.

A **window** is a screen-shaped rectangle on the padded frame, given as the reader's
`CropWindow` (left, top and scale as fractions of the frame). **Every window in a paper has the
same scale** (AGENTS.md, "The PDF page is canonical"). Pages are assigned to one of
K windows W₁…W_K.

## Cost

For a scale s, windows W₁…W_K and an assignment k(p) of pages to windows:

    cost = Σ_p [ α·cut_p + β·off_p ]  +  γ·(K − 1)  +  δ·switches  +  δ₂·breaks

- **cut_p**: the ink cells on page p outside its window, divided by the paper's median ink per
  page (over pages with any ink). The unit is "pages of ink", so a cell costs the same on every
  page of a paper, and costs are comparable between papers. A text line is about 0.02, a page
  number about 0.003–0.005, and the arXiv stamp about 0.02–0.03. **Unweighted:** every ink cell
  counts the same. See "Open questions" for weighting by distance from the text block.
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
never tighter than main's. Across the corpus it came out equal, or 0.005 looser on a few
papers.

## Solver

At a fixed scale, positions are integer cells on the reference frame, which is the paper's
most common page shape. Pages of other shapes use the same position as a fraction of their
frame.

1. Precompute `cost[p][pos] = α·cut + β·off` for every page and every position. Ink counts
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

Measured on this host for a whole paper (rendering with mutool, all four methods and the
contact sheet): 0.8–6 s. I haven't timed the solver on its own, and nothing has been profiled
on the tablet. A reader version would reuse `CropAnalysis`'s summed-area tables.

## Starting weights (`weights.properties`)

| weight | value | reasoning |
| --- | --- | --- |
| α | 1 | Sets the unit: cost is measured in pages of ink cut. |
| β | 0.1 | A page whose content sits 10% of a screen off-centre costs 0.01, about half a text line. Centring then matters when a whole run of pages is off-centre (book margins, appendices), but it never outweighs cutting real text. |
| γ | 0.05 | About 2.5 text lines, or 10–15 page numbers. A one-off window has to rescue more than a page number or a stamp to pay for itself. An extra window for a whole run of pages (e.g. every other page of a book) pays off when it saves more than γ in total. |
| δ | 0 | Plain switches would charge a two-sided book 29 times; δ₂ does the stability job without doing that. |
| δ₂ | 0.02 | One-off windows and jitter cost 2·δ₂ per excursion on top of γ. At 0.02 (and at 0.05), K > 1 survives only on the two two-sided books, with exact parity. At 0 it also fired on Latent Diffusion (jitter), and on BatchNorm without the stamp rule (to keep the stamp). |

**How I read γ.** The brief says "Cutting a page number on one page should lose to adding a
window". I took this as: rather than add a one-off window to keep one page's page number, cut
the page number. That needs α·0.004 < γ, which holds. If the owner meant the opposite,
lowering γ below about 0.004 does it, but then centring alone will also spawn windows.

**Learning the weights.** For fixed crops, the cost is linear in the weights. Its feature
vector is (Σcut, Σoff, K−1, switches, breaks), which `summary.tsv` reports per method. Given
labels of the form "crops A look better than crops B", the weights can be fitted as a ranking
problem, e.g. logistic regression on feature differences, constrained to be ≥ 0. The solver
already produces A/B pairs: compare its solution at each K, or across neighbouring weight
settings.

## Open questions

- **Weighted ink.** All ink counts the same, so the model happily cuts Attention page 1's
  two-line permission notice and conference footer (2.4% of that page) to keep one window.
  Main gave page 1 its own window and kept them. Options:
  - weight cut ink by whether it's body text (near the text block's bounding box, or on a
    regular line pitch);
  - a convex per-page penalty, e.g. cut_p², so one page losing a lot costs more than many
    pages losing a little;
  - a per-page cap alongside the paper-wide 0.5%.
- **Bounding box versus outliers.** A stray mark kept near an edge makes a page look
  off-centre. A trimmed bounding box (ignoring the outermost ~1% of kept ink) would be
  steadier.
- **Scale and cost.** The scale comes from the cut budget, not from the cost. Folding it in
  would need a legibility reward (e.g. −ζ·log s per page), which is another weight to learn.
