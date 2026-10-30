# Component-level content cost: first experiment

*Retired: `content-experiment` was removed with the READER and TUNED configs when the text model
shipped (see [../unified-pipeline.md](../unified-pipeline.md)); rerun it at a869e26. The component
cost itself (`ElementLoss`) is now part of the reader.*

Offline research on digital-native PDFs, starting from merged main 950d48a.
Neither READER nor TUNED is changed. This is a reproducible prototype, not a
replacement config. The placement-only control improves agreement from 137/144
to 140/144 pages with the original zooms, changing only the three footnote-page
outlines among 496 rendered pages. Joint automatic zoom is still unresolved.
The scan in the labelled corpus is excluded by its corpus
category; no scan-specific model logic or dirt suppression was added.

## Representation

Render each page at four times the existing analysis-mask width and height
(about 1024 pixels on the long side). Find 8-connected components with no gap
bridging. Each component has mass 1, distributed uniformly over its ink. A
straight rule is one component; text usually has many. These are not recognized
characters: dots and accents can be separate, ligatures can be connected, and a
whole line drawing can be a single component.

Project the masses onto the existing analysis grid and build a double-precision
summed-area table. The extra cost of a window is

```
eta * (total component mass - mass inside its kept cells)
```

Whole omissions count, as do proportional losses from partial components.
The original ink, block, centring and stability costs remain. Fine rasters and
labels are discarded page by page; retained element tables take about 0.4 MB
per letter page. This prototype has not been optimized for the tablet.

Existing ink exclusions still apply. Segment the fine image FIRST, then exclude
whole components whose ink mostly belongs to removed coarse cells. Erasing the
coarse cells before segmentation manufactured small components from the fringes
of ignored stamps; the regression test preserves that lesson. Fine ink in a
coarse blank is retained, because thin symbols can disappear on downsampling.

The solver gets an optional pure additional-page-cost callback, included in
precomputation, scoring, own-page placement, assignment and the reported total.
It defaults to null. It must return finite, non-negative costs. The null path
avoids allocating extra rectangles in the position loop. No Android rendering,
font handling or per-page zoom is changed.

## What the audit established

The audit scores the owner's explicit centre at the CURRENT baseline scale,
changing just that page for its per-page cost. It excludes window-count and
sequence costs, and does not claim a feasible paper-wide optimum. In particular,
a Perelman centre at 0.685 is not the complete owner target at 0.755.

- NTK p1: baseline page cost 0.0298, owner-centred 0.0468. Lost components
  change from 18 to 3. Ink area alone prefers the rule; component mass can
  prefer the footnote. SuperGLUE p1 is analogous: cost 0.0252 to 0.0404,
  lost components 20 to 3.
- Attention p1 is a useful counterexample. At the audited scale the owner crop
  loses MORE ink, is less centred, splits a block instead of none, and loses
  21 component equivalents instead of 20. It is dominated on these page-local
  features: no non-negative choice of their weights can rank it above the
  current crop at that scale. Counting shapes is not semantic understanding
  of which notice matters. This is different from the NTK/SuperGLUE rule case.
- Perelman pp. 15–16: the owner's centres improve the page-local costs even at
  the old scale, but an extra window must pay its paper-level costs. The new
  feature sees only about 3–4 lost component equivalents on those pages, so
  the cost still gets diluted across a 39-page paper at automatic zoom.

See `audit.tsv` (pilot) and `all-clean-audit.tsv` (all explicit moves).

## Experiments

All results below are training/development observations, not a new held-out
claim. There are 21 labelled digital-native papers, 496 rendered pages and 144
page targets. The existing scorer and its agreement tolerances are unchanged.
All paper pages participate in choosing the crops; unlabelled pages are not
silently counted as approved.

The scale experiment solves every default-grid scale from 0.60 to 0.95 in 0.005
steps, then adds the existing 0.01 margin. This is exhaustive over that bounded
scale grid, not over all scales or every multi-window layout. The fixed-scale
multi-window solver retains its existing heuristic. The zero-feature control
on the four pilot papers chose the same scales as the baseline auto search.

| experiment | pages agreeing | zooms agreeing | sliced-line pages | score |
| --- | --- | --- | --- | --- |
| unchanged TUNED | 137/144 | 19/21 | 0 | 61.938 |
| eta 0.01, placement only at baseline zoom | 140/144 | 19/21 | 0 | 50.115 |
| eta 0.01, joint placement + zoom | 140/144 | 18/21 | 1 | 50.493 |
| eta 0.008, gamma 0.075, joint | 140/144 | 19/21 | 0 | 50.615 |

The component term fixes **NTK p1, SuperGLUE p1 and DDPM p1** at their original
paper zooms. No previously agreeing labelled page becomes a miss. Adam's figures
and BatchNorm's table stay intact. The cost of these fixes is a second window
on each of those title pages, which is a legitimate use of the shared-window
model rather than a page-specific rule.

However, the first joint candidate loosens Attention from 0.81 to 0.83 and still
clips its footer: one lost zoom agreement and one sliced-line page. Lowering eta
to 0.008 with gamma held at 0.1 avoids that regression but loses the new NTK and
SuperGLUE fixes. Lowering gamma to 0.075 recovers those fixes, yet resurrects a
large centring shift on sparse MMLU pages (10/12/14) and an additional LDM window.
Those unlabelled-page changes are visible on the boards despite unchanged
agreement counts. Neither joint variant is ready to replace TUNED.

`all-clean.tsv`, `nearby-weights.tsv`, and `lower-window-cost.tsv` preserve these
observations. `lower-window-cost-parameters.json` records the effective model
weights, feature factor, scale range and source-PDF hashes.

### Placement-only control

The cleanest first result is to isolate window selection: retain TUNED's automatic
scale, then solve with eta 0.01 and the original gamma 0.1. This is an ablation,
not a newly unified zoom objective. It fixes the same three title pages, preserves
all 19 zoom agreements, and retains zero sliced-line pages over the 496 pages.
No previously agreeing label becomes a miss. A comparison of the 480-pixel
rendered outlines found only three changed rectangles (NTK p1, SuperGLUE p1,
DDPM p1); the other 493 have identical rendered outline bounds. This is an
artifact check at preview resolution, not a claim of bitwise model equality.
The reader is still unchanged.

`placement-control.tsv` and `placement-control-parameters.json` preserve the run.
The latter records hashes for all 21 source PDFs. The board's columns are baseline,
fixed-zoom component placement, and the joint placement/zoom experiment, so the
Attention regression is visible alongside the controlled improvement.

This supports using component loss as a placement feature. It does not establish
that its current paper-wide averaging is suitable for automatic zoom, and it does
not solve Perelman. No config has been promoted from this experiment.

### Worst-page sensitivity

`pilot.tsv` also tries risk 1 and 4 in

```
mean model cost + zeta * scale + risk * max(additional page cost)
```

This is ONLY a sensitivity check: it reranks mean-objective placements at each
scale. It does not jointly optimize the non-additive worst-page objective.
It often over-loosens the title-page papers. On Perelman, eta 0.05 / risk 1
chooses roughly 0.715 and two windows, keeping the fine component mass, instead
of the owner's 0.755. The owner-centre and zoom targets still disagree. This
alternative needs visual judgement; it is not reported as a solved case.

## Run it

From the repository root, under the shared build lock:

```sh
flock /tmp/sortes-gradle.lock ./gradlew :zoom-eval:installDist
cd tools/zoom-eval
flock /tmp/sortes-gradle.lock build/install/zoom-eval/bin/zoom-eval content-experiment \
  --weights=0.01 --risks=0 --render=true --out="$HOME/sortes-review/content-review"
```

Controls include `--only=id,id`, `--factor=4`, `--labels=`, `--corpus=`, `--pdfs=`,
`--minScale=0.60`, `--maxScale=0.95`, and existing weight overrides such as
`--gamma=0.075`. `--weights=` here lists COMPONENT penalty weights, not a weights
file. `--risks=` lists worst-page sensitivity weights. A weight greater than zero
also emits a placement-only control at the baseline's automatic scale, shared
by every page. This separates the placement feature from its zoom effects.

Output: `summary.tsv`, `pages.tsv`, `audit.tsv`, `curves.tsv`, `parameters.json`,
and, with `--render=true`, page-by-page comparisons. Completed paper rows are
written after each paper. No PDF or rendered page is committed.

Pilot command:

```sh
flock /tmp/sortes-gradle.lock build/install/zoom-eval/bin/zoom-eval content-experiment \
  --only=1806.07572,1905.00537,perelman-0211159,1412.6980 \
  --weights=0,0.002,0.01,0.05 --risks=0,1,4 \
  --out="$HOME/sortes-review/content-pilot-clean"
```

## Next model questions

1. Counted elements distinguish a rule from text, but not an unimportant text
   notice from a substantive footnote. Attention supplies a concrete feature-
   dominance counterexample, rather than a reason for a larger weight search.
2. A fractional component loss can still make clipping a few pixels cheap.
   A fine-component interruption feature is worth testing separately from whole
   component omissions; the paragraph-level split cost is too coarse for this.
3. Perelman exposes aggregation and layout tradeoffs. Compare the two-window
   0.715 alternative with the owner's 0.755 crop before forcing the zoom target
   through a stronger worst-page objective.
4. Lower gamma revives sparse-page recentering. Stability might need to account
   for the SIZE of a departure from the recurring layout, not just its count;
   genuine odd/even alternation should remain free of that departure penalty.
