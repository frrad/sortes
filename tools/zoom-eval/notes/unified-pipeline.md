# One crop pipeline for the reader and zoom-eval

The text model ("tuned+text", round 5) moved from zoom-eval into the app, and the reader and
zoom-eval now share one implementation of the whole crop pipeline. `CropConfig.DEFAULT` is the
text model, and it is the reader's config.

## What is shared

Everything from pixels to crops is in `app/src/main/java/dev/sortes/app/zoom/` (pure Kotlin) and
reached through one entry point, `analyzePaper(source, aspect, config)`:

| step | code |
| --- | --- |
| which pages are analysed | `samplePages`: all of a paper of up to 60 pages, facing pairs of a longer one |
| mask resolution | `paperRaster`: one density for the paper, from the sampled pages' sizes |
| thresholds | `PageRenderer.renderMask` → `InkMask.fromArgb` |
| ignorable ink | the config's rules and page furniture |
| fine renders | `PaperRaster.fine`: 4× each sampled page, one at a time, released after measuring |
| text measures | `PageText` (`TextSize`, `ElementLoss`, `LineTruncation`), components found once a page |
| zoom search | `CropModel.Prepared.auto`: every scale 0.60–0.97, the text-size `Legibility` cost |
| placement | the text costs at the chosen zoom; unsampled pages rendered, measured and placed on demand (the last 8 kept) |

Only the pixels differ: `PageRenderer.renderPixels` is PdfRenderer in the app (`PdfPages`) and
pdfium pinned to Android's version in zoom-eval (`PdfiumPdf`; mutool's `MutoolPdf` until the
[renderer-fidelity](renderer-fidelity.md) change). `PaperAnalysis.kt` is no longer excluded from zoom-eval; it is
pure. zoom-eval's `readerCrops` (review, labeller export, fit, zoom-experiment, crops, bench) is
`analyzePaper` plus `placeAll`, which places every page exactly as the reader's `place` does.

## Choices

- **Old configs dropped.** `READER`, `TUNED`, the anchor algorithm (`CropAnalysis`), the
  cut-budget zoom (`autoMaxLost`, `zoomIgnoredPages`, `autoMaxPageCut`) and the rules variants
  in the fit are gone. Labels made against them now record the crops they judged
  (`labelledAgainst.centres`, rebuilt with the code of a869e26 in the first commit), so their
  scores don't depend on removed code. `content-experiment` and `TextModel` were removed too.
- **Exhaustive zoom search.** The reader's sparse walk (`autoByCost`) is replaced by the
  experiment's exhaustive scan of 0.60–0.97 (about 75 solves, without the text costs; then one
  more with them at the chosen zoom). On the corpus the walk and the scan chose the same scales
  (the "linear" row of `text-size/final.tsv`).
- **Sampling.** The reader's: at most 60 pages. Every labelled paper has at most 56 pages, so the
  experiment's "every page" and the reader's sample are the same there. On longer papers the
  glyph-height median is over the sample, and unsampled pages get their text costs when placed.
- **Screen width.** Glyph sizes are measured against the Boox's 1860 px width
  (`TextWeights.screenWidth`), not the device's, so the crops depend only on the paper and the
  screen's shape and zoom-eval's are the tablet's.
- **Failed renders.** A sampled page whose 4× render fails is solved without its text (it
  drops out of the glyph-height median); the reader's log line counts the pages measured. A
  render of the wrong size is a bug and fails the analysis.
- **Memory.** Neither fine renders nor the text costs' tables are kept: per sampled page the
  analysis keeps the components' masses per cell and the line segments (roughly a quarter of a
  megabyte), and builds the summed-area tables while solving, one page per thread at a time. The tables are
  built by the same loops as before, so costs are bit-for-bit the experiment's.

## Results

`zoom-eval crops` at a869e26 (TextModel, every page) against this branch (`analyzePaper`): the
same window on every page of every corpus paper of up to 60 pages (35 papers, including the
unlabelled ones and the scan). `zoom-experiment` with the default config:

| | pages | strict | zooms | score |
| --- | --- | --- | --- | --- |
| 21 training papers | 140/144 | 140 | 20/21 | 35.868 |
| round 5 (6 papers) | 57/63 | 57 | 5/6 | |

as in `text-size/final.tsv` (e0.01 l0.1 T28 n16 f0.1, placed) and `labels/ROUND-5.md`. `fit
--trials=0` over all 28 labelled papers (with the scan): 198 of 211 pages agree, score 67.81.

These were rendered with mutool. With pdfium, which gives the tablet's crops, the same model
scores 135/144 (19/21 zooms, 47.238) on the training papers, 57/63 (5/6) on round 5, and 193 of
211 pages, 80.65, in the fit: see [renderer fidelity](renderer-fidelity.md#re-scoring-with-pdfium).
Re-fitted on pdfium's renders, the component weight is now 0.016: 137/144 (19/21, 40.391), 57/63,
and 195 of 211, 74.22 ([pdfium-refit.md](pdfium-refit.md)).

`bench` (host, four threads, no rendering), and the heap the finished analysis keeps:

| paper | pages analysed | analysis ms | of it solving | retained MB |
| --- | --- | --- | --- | --- |
| Attention | 15 | 1052 | 504 | 8.6 |
| Perelman | 39 | 1655 | 599 | 22.7 |
| GPT-3 | 60 of 75 | 2993 | 1212 | 38.6 |
| PDLT book | 60 of 237 | 2628 | 1289 | 33.7 |

The rest of the analysis time is measuring text on the fine renders. With rendering, the whole
pipeline (every page of the 237-page book placed, as `crops` does) runs in a 64 MB heap and not
in 48 MB, where a single 4× page render (pixels, luminance and labels, about 12 MB at once) is
the peak. On the tablet, rendering 60 pages at 4× with PdfRenderer adds to this: pages show
whole until it finishes. The reader keeps each analysis on disk, so it runs once per paper and
screen shape ([analysis-cache.md](analysis-cache.md)).
