# Text-size-aware zoom, truncated lines, worst-page check

Offline research on the 21 labelled digital-native papers (144 page targets, 21 zoom
targets), building on the component cost in `../content-elements`. Neither READER nor TUNED
is changed. All results are training observations on existing labels, not a held-out claim.

## The problem

TUNED's automatic zoom minimises `cost per page + zeta·scale`: every step tighter earns the
same, however big the text already is. On Perelman (12 pt type, wide margins), going from the
model's 0.685 to the owner's 0.755 saves about 0.006 of content cost per page but loses 0.035
of legibility reward, so no reasonable content weight gets there.

## Text size from the page image

`TextSize.glyphHeight`: render the page finer than the analysis mask, take its 8-connected
components, drop specks, rules (more than 3× wider than tall) and anything taller than 3% of
the page, and average the middle half of the heights. The paper's size is the median over
pages. No PDF text is used.

Validated against the PDFs' own font sizes (`estimator-validation.tsv`, all 30 clean corpus
PDFs, 12 pages each):

| estimate | ratio to PDF font size | spread (CV) |
| --- | --- | --- |
| glyph height, 4× render | 0.65 | 5.1% |
| glyph height, 2× render | 0.80 | 2.3% |
| line pitch (`TextSize.linePitch`), 4× | 1.18 | 8.4% |

The pitch estimator recovers the PDF's baseline-to-baseline distance almost exactly, but
leading isn't font size (Maldacena is nearly double-spaced), so glyph height is the better
proxy. Some of glyph height's spread is real (fonts' x-heights differ). The experiment uses the
4× render because it is already made for the component cost; the 2× estimate is tighter and
cheaper, worth preferring if this goes to the reader.

Body glyph height on screen at the owner's zoom (`text-size-by-paper.tsv`): 22–26 px for the
ordinary 10 pt papers, 27–31 px for NeRF, the book, Chinchilla, DiLoCo, GDL and Maldacena,
and **34 px for Perelman at the model's zoom**, the largest in the corpus (31 px at the owner's).

## Legibility cost

`Legibility`: the slope of the zoom cost is `zeta` while glyphs are at most `T` screen px, then
falls as `(scale / sT)^n` (sT = the scale where glyphs are `T` px), never below `floor·zeta`.
Bigger text is still worth something, just less, so the zoom still tightens when nothing is
lost and doesn't start showing page numbers for no reason. `newIgnoredPages` checks this: pages
whose window now shows ignored ink (page numbers, running heads, stamps) that TUNED's didn't.

Each scale on the 0.60–0.97 grid is solved once, then each spec picks its scale from those
solves (`zoom-experiment`). "placed" rows choose the zoom WITHOUT extra page costs and then
place windows WITH them at that zoom. This keeps the component cost out of the zoom choice,
which is where it loosened Attention before.

Results (`legibility.tsv`, `grid.tsv`):

| setting | pages | zooms | sliced | new page-number pages | score |
| --- | --- | --- | --- | --- | --- |
| TUNED | 137/144 | 19/21 | 0 | 0 | 61.94 |
| components, placement only (PR #43) | 140/144 | 19/21 | 0 | 0 | 50.11 |
| legibility T28 n16, zoom only | 137/144 | 20/21 | 0 | 39 (all Perelman) | 48.51 |
| legibility T28 n16 + component placement | **140/144** | **20/21** | 0 | 39 (all Perelman) | **36.69** |
| ... + truncated lines 0.02 | 140/144 | 20/21 | 0 | 39 (all Perelman) | 36.70 |

The legibility cost moves exactly three zooms: Perelman 0.685 → 0.755 (the owner's), the book
0.715 → 0.72 and Maldacena 0.795 → 0.80 (both within tolerance). Every other paper keeps its
zoom, so no other paper newly shows page numbers. Perelman's do now show: the owner's 0.755
shows them too. The floor makes no difference in this grid. T 26–28 with n 16–32 all give
20/21; T26 n16 joint and T24 n8 put Perelman at 0.765–0.775. The only zoom miss left is Latent
Diffusion (0.845 against 0.825), which the owner accepts.

Perelman pp. 15–16 at 0.755 keep 0.0–0.1% less ink than the owner's crop (down from 0.4–0.5%),
but their centres still miss by 11–13 pt, so they don't count as agreeing. The content is right
and the window sits differently.

## Truncated lines

`LineTruncation`: group fine components into line segments: components overlapping
vertically by half the shorter one's height, at most 1.5 glyph heights apart. Column gutters
and the gap before an equation number split segments. A window's left or right edge truncates
a segment when it keeps some of its components and loses others. Each truncated segment costs
`min(1, lost components / 2)`. A whole segment dropped is not a truncation (the component
cost counts that). Per-edge prefix tables make it O(1) per window.

At 0.02 (placed), it changes only YOLO. At TUNED's 0.82, every YOLO page clips the last
character or two of each right-column line. The page targets don't catch this: the owner
judged 0.825, and the clip is under the 0.25% ink tolerance. The line cost shifts the window
to keep both columns whole. Agreement is unchanged. In the joint zoom search, line weights
0.02–0.1 instead pull Perelman to a two-window 0.725 and loosen GDL, so for now they are used
for placement only.

## Worst-page emphasis (empirical answer)

`mean + risk·max(page extra cost)` with risk 1 is clearly worse in every combination: 13–14/21
zooms, 133/144 pages, 80–120 pages newly showing page numbers (six papers loosen, LDM to
0.88–0.92). Not pursued.

## Run it

```sh
flock /tmp/sortes-gradle.lock ./gradlew :zoom-eval:installDist
cd tools/zoom-eval
build/install/zoom-eval/bin/zoom-eval text-size            # estimator vs nothing; join with the PDF fonts yourself
build/install/zoom-eval/bin/zoom-eval zoom-experiment --elements=0,0.01 --lines=0,0.02 \
  --T=24,26,28,30 --n=8,16,32 --floor=0.1 --render=true \
  --renderSpecs=e0.0-l0.0-T28.0-n16.0-f0.1-r0.0,e0.01-l0.0-T28.0-n16.0-f0.1-placed-r0.0,e0.01-l0.02-T28.0-n16.0-f0.1-placed-r0.0 \
  --out="$HOME/sortes-review/zoom-legibility"
```

The full grid (`grid.tsv`) added `--lines=0,0.02,0.05,0.1 --T=22..32 --n=2,4,8,16
--floor=0,0.05,0.1,0.2 --risks=0,1`.

## Before the reader

- The reader needs a finer render to measure glyphs: a few sampled pages at 2× would do for
  text size. The component and line costs need every page at a finer resolution, which costs
  more and is not yet measured on the tablet.
- The app's `autoByCost` search is not exhaustive; the new cost should be checked with it.
- A fresh review round should confirm these choices before any config changes.
