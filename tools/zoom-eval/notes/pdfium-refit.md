# Re-fitting the crop model on pdfium's renders

zoom-eval now renders the way the tablet does ([renderer-fidelity.md](renderer-fidelity.md)).
With the weights tuned partly on mutool's pixels, `CropConfig.DEFAULT` ("tuned+text") agreed with
the labels less well: 193 of 211 pages where mutool had said 198. This note re-fits both models'
weights on pdfium's renders, through the reader's pipeline (`readerCrops` → `analyzePaper`, as
`fit` and `zoom-experiment` already score).

**Result:** the only change that holds up is the **component weight, `TextWeights.elements`, from
0.01 to 0.016**. It wins back two of the five lost pages, the footnote-vs-rule title pages of
SuperGLUE (1905.00537) and DDPM (2006.11239). It leaves every zoom and every other labelled page
as it was, and it doesn't touch round 5. The other weights stay. The searches found nothing else
that survives holding papers out.

| | before (elements 0.01) | after (0.016) | mutool era, for reference |
| --- | --- | --- | --- |
| `fit --trials=0`, 28 papers with the scan | 193 of 211, 80.65 | **195 of 211, 74.22** | 198 of 211, 67.81 |
| ... leave-one-paper-out | 80.65 | **74.22** | |
| `zoom-experiment`, 21 training papers: pages (strict), zooms, score | 135/144 (135), 19/21, 47.238 | **137/144 (137), 19/21, 40.391** | 140/144 (140), 20/21, 35.868 |
| `zoom-experiment`, round 5 (held out of every search) | 57/63 (57), 5/6, 13.546 | 57/63 (57), 5/6, 13.546 | 57/63 (57), 5/6, 12.426 |
| sliced-line pages / pages newly showing ignored ink / lines clipped vs owner | 1 / – / 1 | 1 / 0 / 1 | 0 / 0 / 0 |

The sliced-line page (an unlabelled page of 2006.11239) and the clipped line (Perelman, one of
p15–16) are there with both weights. Leave-one-paper-out scores each paper with the weights chosen
without it: the move is worth 3.1 and 3.8 units on two different papers, so either one alone still
chooses it. Every other paper scores exactly as before.

## The search

`fit --search=coordinate` (new; see the README) is a coordinate descent around the current
weights, over both models: the crop model's beta, gamma, delta, delta2, kappa, blockGap, splitInk,
zeta and autoMargin, and the text model's comfortable, power, floor, elements and lines. Each step
tries a weight a step or two each way on a grid that suits it, keeps the best value if it gains
more than `--minGain`, and moves to the next weight. Each try is one full scoring of the papers,
about 50 s for all 28. The report flags a step as **fragile** when leaving out a single training
paper would have changed its decision.

Round 5 (Witten, Green–Tao, VAE, LIGO, GAN, Scaling Laws) was held out of both searches with
`--holdout`.

**Search 1:** rounds 1–4 (22 papers, including the Shannon scan), minimum gain 0.5, three sweeps
([search-rounds1-4.md](pdfium-refit/search-rounds1-4.md),
[trials](pdfium-refit/search-rounds1-4-trials.tsv)).
- It kept five moves: comfortable 28 → 30, power 16 → 21.28, elements 0.01 → 0.0133, zeta
  0.5 → 0.665, and lines 0.1 → 0 → 0.3. The training score fell 67.10 → 49.94.
- It doesn't hold up:
  - Pages agreeing stay at 193 of 211.
  - Round 5 gets worse: 13.55 → 13.80, and 57 → 56 pages.
  - Leave-one-paper-out is 80.71, against 80.65 for the defaults.
- The legibility moves (comfortable, power) are fragile. Most of their gain is the Shannon scan,
  whose zoom moves 0.860 → 0.825 (−8.9 units). Perelman pays for it, 0.765 → 0.735. Both lines
  moves are fragile and worth under a unit.

**Search 2:** the same papers without Shannon, minimum gain 1.0, two sweeps
([search-rounds1-4-noscan.md](pdfium-refit/search-rounds1-4-noscan.md),
[trials](pdfium-refit/search-rounds1-4-noscan-trials.tsv)). It kept two moves:
- **elements 0.01 → 0.0133:** −6.85 units, and not fragile.
- **zeta 0.5 → 0.665:** −2.64 units, fragile on Latent Diffusion (2112.10752). It moves that
  paper's zoom from 0.845 to 0.830, near the owner's 0.825, but the owner accepts 0.845
  (text-size notes), and two of its four pages stop agreeing (p2, p4).

With both moves it is still 193 of 211, held out 33.41 → 33.83, leave-one-out 81.39. Without the
scan, comfortable, power and floor stay where they are: every step of them costs units.

So zeta, and all the legibility and crop-model moves, were rejected. Elements was taken on its
own and checked on a finer grid.

## Why the component weight, and why 0.016

`TextWeights.elements` prices each component equivalent a window loses (`ElementLoss`). It is a
**placement** cost only: the automatic zoom is chosen without the text costs, and windows are
placed with them at that zoom. Changing it therefore can't move any paper's zoom, only which
window a page takes.

On the title pages of SuperGLUE and DDPM the choice is between two windows
([content-elements](content-elements/README.md)):
- one that keeps the conference notice or footnote at the foot of the page, which the owner keeps
  and which has many small components;
- one that keeps the rule at the top instead, which is one component.

The model takes the footnote window when elements × (the components saved) outweighs the
footnote window's extra ink and centring cost. With pdfium the break-even weight rose:
- At 0.01, mutool's renders took the footnote window on both pages (and the first component
  experiment found 0.008 too little).
- pdfium's renders need **0.011 for SuperGLUE and 0.0133 for DDPM** (each break-even lies just
  below its value: above 0.01 and above 0.012 respectively). Both papers also tighten
  by a step (0.810 → 0.805), where the footnote window gives up more of the rest of the page.

Grid on the 27 clean papers (`zoom-experiment --elements=...`,
[elements-grid.tsv](pdfium-refit/elements-grid.tsv); training pages are of 144, round 5 of 63):

| elements | training pages (strict) | score | round 5 | newly showing ignored ink | what changes |
| --- | --- | --- | --- | --- | --- |
| 0.01 (before) | 135 (135) | 47.238 | 57 | – | |
| 0.011, 0.012 | 136 (136) | 43.482 | 57 | 0 | SuperGLUE p1 agrees |
| **0.0133–0.02** | **137 (137)** | **40.391** | 57 | 0 | DDPM p1 agrees too |
| 0.025 | 137 | | 57 | 1 | a 2203.15556 page shows ignored ink |
| 0.03, 0.045 | 135 (135) | 44.897 | 57 | 3 | both title pages lost again |
| 0.07 | 134 (134) | 45.069 | 57 | 5 | a 2311.08105 page lost too |

0.016 is near the log-middle of the clean range (above 0.012, below 0.025). That leaves a margin
of at least 1.2× below and 1.5× above for pixel noise, such as between our pdfium build and the
tablet's.

- **Title pages:** both still use a second window, as they did with mutool. The rest of the paper
  keeps its one window; see the boards (`review --configs=tuned+text,tuned+text:elements=0.01
  --only=1905.00537,2006.11239`).
- **Shannon** (the scan): p4 moves 0.8 → 5.0 pt from its target, still agreeing; +0.42 units.

## What isn't won back

Three of the five lost cases aren't recovered. In each the cause is the zoom, not placement:

- **1806.07572 (NTK) p1:**
  - pdfium picks 0.810 where mutool picked 0.805 (the owner's), and at 0.810 the footnote window
    loses to the rule at every component weight up to 0.07.
  - Getting 0.805 back means moving the zoom of every paper, through zeta, the legibility
    weights or autoMargin, and none of those moves held up.
- **Perelman p15–16:**
  - pdfium measures Perelman's glyphs 3% bigger (23.9 px against 23.2 at scale 1), and the zoom
    loosens from 0.755 to 0.765.
  - That isn't a moved threshold. No single legibility move brings 0.755 back: comfortable 29 or
    30, power 12 or 21 and floor 0.2 all leave Perelman the same or worse.
  - Line weights of 0.133 and up narrow p15's miss by 0.7 units, but don't bring either page
    within 8 pt.
- **2104.13478's zoom:** 0.810 against the owner's 0.790, and no step of any weight moved it.

These need a model change, not a re-weighting: for instance a legibility cost that is less sensitive
to a few percent of glyph height, or a placement that keeps Perelman's equation pages apart at
any zoom. They're left for that work.

## Checks

- **Tablet** (Boox Note Air5 C, the debug app installed with `adb install -r` and `CropDumpTest`):
  - SuperGLUE, DDPM, NTK and Perelman analyse fine.
  - Their crops are zoom-eval's on every page: `device-check` for the two dumped papers, `crops`
    against the dump for the other two.
  - Both title pages use two windows, as offline.
  - The reader opened a library paper and logged its analysis normally (`tuned+text`, 60 of 471
    pages, 18.7 s).
- **Cache:** the config's text is in the analysis cache key (`CropConfig.fingerprint`), and so is
  the build, so `AnalysisCache.PIPELINE_VERSION` stays: `analyzePaper` computes the same thing,
  with a different weight.
- **Tables:**
  - The text-size notes' tables (`final.tsv`, `grid.tsv`, `legibility.tsv`,
    `text-size-by-paper.tsv`), the content-elements tables and
    `renderer-fidelity/zoom-experiment-mutool-vs-pdfium.tsv` are records of the earlier weights
    (the first three mutool-era).
  - The current per-paper numbers are
    [zoom-experiment-before-after.tsv](pdfium-refit/zoom-experiment-before-after.tsv) (elements
    0.01 against 0.016, pdfium) and [fit-final.md](pdfium-refit/fit-final.md).

## Reproduce

```sh
tools/zoom-eval/setup-pdfium.sh
flock /tmp/sortes-gradle.lock ./gradlew :zoom-eval:installDist
cd tools/zoom-eval
R5=witten-9802150,greentao-0404188,1312.6114,1602.03837,1406.2661,2001.08361
nice build/install/zoom-eval/bin/zoom-eval fit --search=coordinate --holdout=$R5 --sweeps=3 --minGain=0.5 \
  --coordinates=comfortable,power,floor,elements,lines,zeta,autoMargin,beta,gamma,delta,delta2,kappa,blockGap,splitInk
nice build/install/zoom-eval/bin/zoom-eval fit --search=coordinate --holdout=$R5,shannon1948 --sweeps=2 --minGain=1.0 \
  --coordinates=elements,lines,comfortable,power,floor,zeta,autoMargin,beta,gamma,delta,delta2,kappa,blockGap,splitInk
nice build/install/zoom-eval/bin/zoom-eval zoom-experiment --elements=0.01,0.011,0.012,0.0133,0.015,0.02,0.03,0.045,0.07
nice build/install/zoom-eval/bin/zoom-eval zoom-experiment --configs=tuned+text:elements=0.01,tuned+text
nice build/install/zoom-eval/bin/zoom-eval fit --trials=0 --holdout=$R5
```

The searches ran from the old default, elements 0.01. Run now, they start from 0.016.
