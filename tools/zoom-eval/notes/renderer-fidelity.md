# Renderer fidelity: zoom-eval renders as the tablet does

zoom-eval and the reader share all of `analyzePaper` except `PageRenderer.renderPixels`. zoom-eval
rendered with mutool. On the corpus that changed the zoom on 16 of 35 papers and the window on
520 of 1201 pages, compared with what the Boox actually shows. zoom-eval now renders with
**pdfium pinned to the release nearest Android 15's** (`PdfiumPdf`, chromium/5731 via
`setup-pdfium.sh`), drawn the way PdfRenderer draws and with its whole-point page sizes. That gives
the tablet's crops on 32 of 33 papers and its zoom on all 33. Re-scored this way, the shipped
model agrees with the labels less well than the mutool numbers said: **193 of 211 pages, not
198** (see [Re-scoring](#re-scoring-with-pdfium)).

The investigation's tools (a pypdfium2 helper, exact-matrix mutool, coarse/fine hybrids, tone
and threshold sweeps) are not part of zoom-eval. They live on branch
`bb/renderer-fidelity-prototype` (d616d92), which is also where the measurements below were made.
The pinned renderer reproduces them: `device-check` gives the same 32 of 33.

## What the tablet renders with

- **Device.** Boox Note Air5 C, Android 15 (SDK 35, firmware GV2.029), mediaprovider APEX
  352090000.
- **Renderer.** On Android 15 `android.graphics.pdf.PdfRenderer` comes from the MediaProvider
  mainline module: `framework-pdf-v.jar` → `PdfProcessor` → `PdfDocumentProxy` →
  `/apex/com.android.mediaprovider/lib64/libpdfclient.so`. That library links pdfium statically
  and uses the APEX's own `libft2.so`; the test process's `/proc/pid/maps` shows it loaded. It
  doesn't use the platform's `/system/lib64/libpdfium.so`.
- **pdfium version.** AOSP `external/pdfium` is "Chrome 114.0.5735.130 pdfium" plus local patches,
  on `android15-release` and `main`. It is built with AGG, against AOSP's FreeType, for arm64.
  The closest pdfium-binaries release is **chromium/5731** (114.0.5731), the same binary as
  pypdfium2 4.8.0. The latest release (chromium/8076) is clearly further from the tablet (below),
  so the pin must follow Android, not upstream.
- **Flags.** pdfClient's `Page::Render` uses `FPDF_REVERSE_BYTE_ORDER | FPDF_LCD_TEXT |
  FPDF_ANNOT` for display. Our bitmap is ARGB_8888, and pdfium turns LCD text off on a bitmap
  with alpha, so text is greyscale antialiased: the dump has no coloured pixels on text-only
  pages. `PdfiumPdf` draws the same way: white 32-bit bitmap with alpha, the same flags, and the
  scale matrix `width / pageWidth`.
- **Page sizes are whole points.** `Page::Width()` returns `int`, so A4 is 595×841 on the tablet,
  where mutool said 595.276×841.89. This changes the mask size and the matrix, and the page's
  fractional edge falls off the bitmap. It matters on its own: with fractional sizes, pdfium's
  crops flip on 2 of the 9 corpus papers that have them.
- **The tablet's renderer can change.** The module is updated through Play system updates. Older
  Android versions use the platform `libpdfium.so`, which is another version. `device-check`
  is how to notice (README, "Checking pdfium against the tablet").

## Where mutool's masks diverged

The tablet's renders were dumped with the app's `CropDumpTest`: every render the pipeline asks
PdfRenderer for, as PGM. Replaying them through `analyzePaper` on the JVM gives the tablet's crops
exactly, on all 33 papers (845 pages): ART and the JVM agree bit for bit, and the whole-point
sizes are right. Per render, against the tablet, over 800 pages of 32 papers (the scan, shannon,
is identical everywhere and left out), with masks at the pipeline's own threshold:

| renderer | coarse: mask cells differing / page | % of ink | mean abs grey diff | fine 4×: pixels differing / page | % of ink |
| --- | --- | --- | --- | --- | --- |
| mutool | 2822 | 27.4% | 6.1 | 22 732 | 22.9% |
| pdfium chromium/5731 | 281 | 2.7% | 0.8 | 2 306 | 2.3% |
| pdfium chromium/8076 | 522 | 5.1% | 1.3 | 8 951 | 9.0% |

- **The threshold never differed.** Paper 255, threshold 249, on every page with every
  renderer. All the difference is in which pixels are darker than 249.
- **mutool places glyphs differently; the tone is not the problem.** The mutool − tablet
  difference is whole glyph edges shifted sideways, which fits pdfium snapping glyph origins to
  pixels and MuPDF not. No tuning closes it:
  - the best per-level grey mapping still leaves about 30% of ink differing on Attention p2;
  - so does the best threshold;
  - so does mutool with the device's exact matrix, whole-point sizes and RGB→luma
    (`mutool run`).
- **pdfium 5731 is close but not bit-exact.** 76% of coarse and 85% of fine pixels match, and
  the rest are a few grey levels off: likely FreeType version and build, and arm64 against
  x86-64 floating point. That flips some pixels around the faint 249 cut. Exactness would mean
  building AOSP's pdfium and FreeType at the module's revision, and an x86-64 build might still
  round differently.
- **The coarse masks decide the crops.**
  - Tablet coarse masks with mutool's fine renders gave the tablet's crops on 27 of 33 papers.
  - mutool coarse with the tablet's fine renders gave them on 14.
  - The Attention glyph-height gap (20.5 against 20.7 px) was a symptom: `glyphPx` also depends on
    the coarse masks, through the frame width.

## How often the crops differed

The tablet's crops against each renderer's (`renderer-fidelity/crops-by-renderer.tsv`; aspect
0.75):

| renderer | papers | zoom differs | papers differing | pages differing |
| --- | --- | --- | --- | --- |
| mutool (zoom-eval before this change) | 35 | 16 | 23 | 520 / 1201 |
| tablet renders replayed | 33 | 0 | 0 | 0 / 845 |
| mutool, exact geometry | 33 | 16 | 20 | 493 / 845 |
| pdfium chromium/8076 | 33 | 2 | 7 | 133 / 845 |
| **pdfium chromium/5731 (now)** | 33 | **0** | **1** | **15 / 845** |

- mutool's zoom misses were mostly a step: −0.015 ×1, −0.010 ×3, −0.005 ×7, +0.005 ×5.
- At the same zoom, single-window papers still had their window a cell off on every page.
- pdfium's one miss is Attention: same zoom (0.805), window one cell off on all 15 pages.

## Re-scoring with pdfium

Same model, same weights (`CropConfig.DEFAULT`), only the renderer changed. Per-paper numbers
are in `renderer-fidelity/zoom-experiment-mutool-vs-pdfium.tsv`.

| | mutool | pdfium |
| --- | --- | --- |
| `zoom-experiment`, 21 training papers: pages (strict), zooms, score | 140/144 (140), 20/21, 35.868 | **135/144 (135), 19/21, 47.238** |
| `zoom-experiment`, round 5 (6 papers) | 57/63 (57), 5/6, 12.426 | 57/63 (57), 5/6, 13.546 |
| `fit --trials=0`, 28 papers with the scan | 198 of 211, score 67.81 | **193 of 211, score 80.65** |

Scoring the tablet's own replayed renders gives 193 of 211 too (81.34; only Attention's window
differs from pdfium's).

Where agreement changes from the mutool numbers. Five pages and one zoom are lost:

| paper | zoom: mutool → pdfium (owner's) | what changes |
| --- | --- | --- |
| 1806.07572 | 0.805 → 0.810 (0.805) | p1 now 22 pt off |
| 1905.00537 | 0.810 → 0.805 (0.810) | p1 now 18 pt off, cutting 2.3% |
| 2006.11239 | 0.810 → 0.805 (0.810) | p1 (its only target) now 21 pt off, cutting 1.5% |
| perelman-0211159 | 0.755 → 0.765 (0.755) | p15, p16 now 12–13 pt off |
| 2104.13478-p20-49 | 0.800 → 0.810 (0.790) | zoom now off; the same 6 of 9 pages agree |

- **Better, no change in agreement:** Attention 0.810 → 0.805, the owner's zoom (score 5.77 →
  2.46). BERT 0.845 → 0.850, exact (1.00 → 0). NeRF 0.905 → 0.910, exact.
- **Zoom one step off target, still agreeing:** 1506.02640 0.820 → 0.835 (0.825); 1902.10186,
  2009.03300, greentao and Scaling Laws, each by one step.
- **Unchanged misses:** LIGO (0.870 → 0.875, owner 0.850, still 0 of 6) and shannon (1 of 4).

So the text model was tuned partly on mutool's pixels, and on the tablet it does somewhat worse
than zoom-eval reported. Weights aren't re-fitted here. The re-fit
([pdfium-refit.md](pdfium-refit.md)) raised the component weight to 0.016, which wins back the
SuperGLUE and DDPM title pages (195 of 211, 74.22). The other three losses are zoom changes that no
re-weighting fixed robustly. The table above and `zoom-experiment-mutool-vs-pdfium.tsv` are for
the earlier weights.

## Options considered

- **pdfium offline, pinned to Android's version (done).** Fidelity: 32 of 33 papers' crops,
  every zoom. Maintenance: re-check with `device-check` when the tablet's mediaprovider module
  updates, and move the pin if it moved pdfium. Speed: rendering in-process through JNA, `fit
  --trials=0` takes 95 s where mutool took 115 s.
- **Tuning mutool.** Ruled out. The divergence is glyph placement, which no antialiasing, gamma
  or threshold setting moves (see the bullets above).
- **Making the pipeline robust to raster differences.** Can't guarantee identical crops: the
  zoom is an argmin over 0.005 steps and windows snap to cells. The obvious lever goes the wrong
  way. Counting only darker pixels as ink made pdfium-vs-tablet agreement worse:
  - 1 paper differing at <249, 4 at <224, 20 at <128;
  - mutool: 20 → 27 → 31.

  The faint cut is the stable one. Robustness across devices would be worth its own work.
- **Evaluating on the tablet.** Running the corpus takes about 6 minutes, and its renders replay
  exactly. A render cache from the tablet would be exact for experiments that keep the render
  sizes. It needs the tablet, and hundreds of MB that can't go in git. Instead the dump test and
  replay are kept as `device-check`, to keep pdfium honest.

## Asides

- On the tablet, `analyzePaper` took 3–10 s for ordinary papers and about 26 s for GPT-3 (60 of
  75 pages).
- One instrumented run over about 30 papers in a single process was SIGKILLed on 2203.15556,
  which analyses fine in a fresh process. This may be memory building up across papers in one
  process (`PdfRenderer` native memory or test-side). Not investigated.
