# Renderer fidelity: making zoom-eval's crops the tablet's

This is an investigation. Nothing here changes the reader. After #44, zoom-eval and the reader
share all of `analyzePaper` except `PageRenderer.renderPixels`. This note asks how far mutool's
pixels are from the Boox's, how often that changes the crops, and what it would take to get the
same crops offline as on the device.

## Setup

- **Device.** Boox Note Air5 C, Android 15 (SDK 35, firmware GV2.029, security patch 2026-06),
  mediaprovider APEX 352090000. aspect 0.75 (1860×2480).
- **Device dump.** `app/src/androidTest/.../pdf/CropDumpTest.kt` runs `analyzePaper` + `placeAll`
  on `PdfPages` for PDFs pushed to `/sdcard/Android/data/dev.sortes.app/files/fidelity/pdf`. It
  writes every page's crop (in `zoom-eval crops` format) and the luminance of every render the
  pipeline asked for, as PGM. The installed debug APK had the same dex as this branch, so only
  the test APK was installed:

      adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
      adb shell am instrument -w -e class dev.sortes.app.pdf.CropDumpTest \
          [-e papers a.pdf,b.pdf] [-e dump a.pdf,b.pdf] dev.sortes.app.test/androidx.test.runner.AndroidJUnitRunner
      adb pull /sdcard/Android/data/dev.sortes.app/files/fidelity/out

  The whole corpus (35 papers, 1201 pages) took about 6 minutes on the tablet. Dumping renders
  for the 33 papers of at most 60 pages came to 463 MB of PGM.
- **Offline.** `zoom-eval fidelity` (`Fidelity.kt`) runs the same pipeline on these renderers:
  - `device`: replays the dump.
  - `mutool`: today's `MutoolPdf`.
  - `mutool-exact-rgb`: mutool with the device's integer page size and exact matrix,
    `fidelity/mutool-render.js`.
  - `pdfium5731` / `pdfium8076`: pypdfium2 4.8.0 (pdfium-binaries chromium/5731) and 5.14
    (chromium/8076), through `fidelity/pdfium-render.py`. This script copies what the device
    does: white BGRA bitmap, `REVERSE_BYTE_ORDER|LCD_TEXT|ANNOT`, integer page size.
  - Hybrids that take the coarse masks from one renderer and the fine 4× renders from another.

  The command compares each renderer's crops with the device's page by page. With `--pixels` it
  also compares every dumped render's grey pixels, threshold and masks. `ZOOM_EVAL_RENDERER=
  device:DIR|pdfium:PY` points `fit` and `zoom-experiment` at another renderer. Results are in
  `renderer-fidelity/`.

## What Android actually renders with

- On Android 15, `android.graphics.pdf.PdfRenderer` comes from the **MediaProvider mainline
  module**, not the platform: `framework-pdf-v.jar` → `PdfProcessor` → `PdfDocumentProxy` →
  `/apex/com.android.mediaprovider/lib64/libpdfclient.so`. That library links pdfium statically
  and uses the APEX's own `libft2.so`. The test process's `/proc/pid/maps` shows it loaded.
  `/system/lib64/libpdfium.so` is also mapped, but PdfRenderer doesn't use it.
- AOSP's `external/pdfium` is "Chrome 114.0.5735.130 pdfium" (Sept 2023) plus local patches.
  This is the same on `android15-release` and `main`. It builds with AGG (no Skia), against
  AOSP's FreeType, with the arm64 toolchain.
- `pdfClient` `Page::Render` uses `FPDF_REVERSE_BYTE_ORDER | FPDF_LCD_TEXT | FPDF_ANNOT` for
  `RENDER_MODE_FOR_DISPLAY`. Our bitmap is ARGB_8888, and pdfium turns LCD text off on a bitmap
  with alpha. So the text is plain greyscale AA: the dump has zero coloured pixels on text-only
  pages, and pypdfium2 behaves the same way.
- **Page sizes are integers.** `Page::Width()` returns `int`, so A4 is 595×841 on the device. mutool
  reports 595.276×841.89. This changes the mask size and the scale matrix. 9 of the 33 papers
  have fractional boxes (A4, 2003.08934, shannon).
- The module is updated through Play system updates, so the device's pdfium can change under
  us. Older Android versions use the platform `libpdfium.so`, which is another version.

## 1. Where the masks diverge

Here are the per-render comparisons against the device over 800 pages of 32 papers (shannon,
a scan, is left out because image pages are identical everywhere). Masks use the pipeline's own
threshold (`InkMask.fromArgb`).

| renderer | coarse: mask cells differing / page | as % of device ink | mean abs grey diff | fine 4×: pixels differing / page | % of ink | fine glyph height, other − device (px) |
| --- | --- | --- | --- | --- | --- | --- |
| mutool | 2822 | 27.4% | 6.1 | 22 732 | 22.9% | −0.06 ± 0.39 |
| pdfium 5731 | 281 | 2.7% | 0.8 | 2 306 | 2.3% | −0.02 ± 0.24 |
| pdfium 8076 | 522 | 5.1% | 1.3 | 8 951 | 9.0% | +0.00 ± 0.25 |

- **The threshold never differs.** The paper is 255 and the threshold 249 on every page of every
  renderer. The differences are all in which pixels fall darker than 249.
- **mutool's difference is in where the glyphs are, not their tone.** In a difference image the
  mutool − device diff is whole glyph edges moved sideways, while pdfium's is faint noise
  of ±1–8 levels. No tone curve can fix it: on Attention p2, the best per-level LUT from mutool
  grey to device grey still leaves 3491 differing coarse cells (3512 untouched), and the best
  threshold leaves 3459. pdfium 5731 leaves 306. This fits pdfium snapping glyph origins to whole
  pixels while MuPDF positions them at subpixel offsets. Exact geometry doesn't help either: with
  `mutool-exact-rgb` (integer page size, device matrix, RGB→luma) the masks differ about as
  much (≈30–38% of ink on the papers tried).
- **pdfium 5731 is close but not bit-exact.** 76% of coarse and 85% of fine pixels are
  identical. The rest are off by a few grey levels. That is enough to flip pixels around the very
  faint 249 cut, because every faint AA edge counts as ink. The likely causes are the FreeType
  version and build options (AOSP's `libft2` against pdfium's bundled FreeType) and arm64 against
  x86-64 floating point (FMA contraction in AGG).
- **The coarse mask decides the crops; the fine render hardly matters.** Coarse from the device
  with fine from mutool reproduces the device's crops on 27 of 33 papers (31 of 33 zooms).
  Coarse from mutool with fine from the device reproduces them on only 14 papers. Attention is
  an example: device coarse + mutool fine gives 0.805 (device), and mutool coarse + device fine
  gives 0.81 (mutool). The 20.5 against 20.7 px glyph height is a symptom, not the cause:
  `glyphPx` also depends on the coarse masks through the frame width. Even when crops agree,
  `glyphPx` differs by up to 1.9 px between renderers, which shows how sensitive the
  median-of-heights measure is.

## 3. How often crops differ today

These are device crops (from the dump) against `zoom-eval crops` on the same commit. 35 papers,
1201 pages:

| | papers with a different zoom | papers with any page different | pages different |
| --- | --- | --- | --- |
| **mutool (today)** | **16 / 35** | **23 / 35** | **520 / 1201** |

Over the 33 dumped papers (845 pages), the other renderers compare like this
(`renderer-fidelity/crops-by-renderer.tsv`):

| renderer | zoom differs | papers differing | pages differing |
| --- | --- | --- | --- |
| device replay on the JVM | 0 | 0 | 0 |
| mutool | 16 | 20 | 518 |
| mutool, exact geometry | 16 | 20 | 493 |
| pdfium 8076 | 2 | 7 | 133 |
| **pdfium 5731** | **0** | **1** | **15** |
| pdfium 5731 with fractional page sizes (9 papers that have them) | 1 | 2 | 45 |

- When the zoom differs it is mostly by one step (±0.005). mutool's misses: −0.015 ×1,
  −0.010 ×3, −0.005 ×7, +0.005 ×5. One outlier: mutool-exact puts perelman at 0.725 against the
  device's 0.765.
- When the zoom is the same, a single-window paper still moves its window by one cell on every
  page (1412.6980, 1502.03167, 2311.08105, shannon).
- pdfium 5731's only miss is Attention: same zoom, but the window is one cell off on all 15
  pages.
- The replay is exact. The JVM and ART give bit-identical crops from the same pixels, and the
  integer page sizes are right: every requested render size existed in the dump. This still holds
  after rebasing on main (37979471).

**This also affects the labels.** Scored with the device's own pixels (`ZOOM_EVAL_RENDERER=
device:…  fit --trials=0`), the shipped model agrees on **193 of 211** pages, score **81.34**.
Scored with mutool, as zoom-eval does now, it agrees on 198 of 211, score 67.81. Pages lost on the
device: 1806.07572 p1, 1905.00537 p1, 2006.11239 p1, perelman p15–16. 2104.13478 goes from zoom
"agrees" to "off". So the text model was tuned partly on mutool's pixels, and the tablet scores
noticeably worse than zoom-eval reports.

## 2. Options

**(a) Render offline with pdfium behind `PageRenderer`.**
- *Fidelity:* pdfium-binaries chromium/5731, close to AOSP's 5735, gives the device's crops on
  32 of 33 papers and every zoom right. The newest build (8076) is clearly worse (7 papers), so
  the version must be pinned to AOSP's, not to whatever is latest. Bit-exactness isn't
  realistic: it would mean building AOSP's pdfium + FreeType at the module's revision, and an
  x86-64 build may still round differently from arm64.
- *Effort:* about 1–1.5 days.
  - A `PdfiumPdf` renderer: pypdfium2 4.8.0 in a pinned venv through the prototype's persistent
    helper, or JNA on pdfium-binaries' `libpdfium.so` to avoid Python.
  - Integer page sizes, and tests.
  - `review`/labeller images through it as well.
  - Re-baseline the labels' scores.
- *Maintenance:* low. Pin the binary. Re-check against a device dump whenever the tablet's
  mediaprovider module updates (APEX version above). If it updates pdfium, move the pin.
- *Cheap part to take anyway:* integer page sizes. On its own it flips 2 of the 9
  fractional-size papers for pdfium.

**(b) Tune mutool.** Not viable. The divergence is glyph placement, not antialiasing or gamma.
Exact geometry, RGB→luma, the best LUT and the best threshold all leave about 30% of coarse ink
different. `-A`/gamma can't move glyph origins. Effort small, payoff none.

**(c) Make the pipeline robust to raster differences.** Expensive and open-ended. It can't
guarantee identical crops, because the zoom is an argmin over 0.005 steps and the window snaps
to cells, so some papers will always sit near a tie. The obvious lever goes the wrong way:
counting only darker pixels as ink makes agreement *worse* (pdfium 5731 against device: 1 paper
at <249, 4 papers at <224, 20 papers at <128; mutool: 20 → 27 → 31). The faint cut is the most
stable one, because pixels around mid-grey are where renderers disagree most. Other ideas would
each need re-tuning and new labels, and would change the reader:
- derive the coarse mask from the fine render (coverage, not a second rasterisation);
- add hysteresis between neighbouring scales;
- use a more robust glyph-height statistic.

Estimate: a week or more of research. Worth doing for its own sake (robustness across devices
and module updates), not as the way to match zoom-eval.

**(d) Evaluate on the device.** `CropDumpTest` already runs the corpus in about 6 minutes, and
its renders replay exactly offline. Two forms:
- *Device render cache:* dump once, check the PGMs in or cache them (PNG would cut 463 MB a
  lot), and have zoom-eval read them (`DumpPages`), falling back to (a) for anything missing.
  This is exact for every experiment that keeps the render sizes: config weights, costs and the
  zoom search. It isn't exact if the density, the sampling or `TextWeights.factor` changes, or
  for new papers, which need a re-dump. Effort ½–1 day on top of the prototype. Needs the tablet
  occasionally.
- *Running zoom-eval itself on the device:* impractical. It needs labels, mutool images, a
  large heap and a web labeller.

## Recommendation

Do (a) with pdfium pinned at chromium/5731 and integer page sizes. Make it zoom-eval's default
renderer and keep mutool only where nothing depends on pixel accuracy, if anywhere. Back it with
(d)'s device render cache for the labelled corpus, so fits and scores use the tablet's exact
pixels, and so a mediaprovider update shows up as a diff when re-dumped. Total about 2–2.5 days.
Then re-score and, if needed, re-fit the text model on device-faithful pixels. The 198 → 193 drop
shows the current tuning leans on mutool. Leave (c) as separate work on robustness, and drop (b).

## Asides

- On the device, `analyzePaper` took 3–10 s for ordinary papers and about 26 s for GPT-3's 60 of
  75 pages. `solveMillis` was about ⅓ of that.
- The first all-corpus instrumentation run was SIGKILLed after about 30 papers in one process,
  on 2203.15556. The paper analyses fine in a fresh process. This could be the low-memory killer
  reacting to memory built up across papers in one process (`PdfRenderer` native memory or
  test-side). Worth a look, since the reader opens many papers in one process.
