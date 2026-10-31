# From the tap to the zoomed page

Opening a paper whose analysis was cached used to show the whole page first and then zoom in:
on e-ink that's two (here, three) full refreshes and ghosting, which looks like an animation.
For a cached paper the first thing the reader draws should be the zoomed page, as soon as
possible.

## Measuring it

The reader times every opening (`app/src/main/java/dev/sortes/app/ui/OpenTimeline.kt`): from
the tap on the paper in the list to the first zoomed page drawn, with each step on the way and
every frame it drew (messages and pages). It logs one line, tag `SortesOpen`, and writes the
steps as trace sections (and the whole opening as the async section "Sortes open paper") for
Perfetto:

    Opened paper 1: settled in 236 ms; 1 frame(s) [zoomed page 1]; reader shown +35,
    pdf fetched +61, pdf opened +68, pdf hashed +71, analysis started for 1860x2480 +80,
    analysis ready (cached) for 1860x2480 +165, placing page 1 +176,
    rendering zoomed page 1 +191, drew zoomed page 1 +236

`scripts/measure-open.sh` taps papers in the list over adb (found by title with uiautomator),
waits for that line and goes back, a few times each; `--cold` force-stops the app first, so the
open runs in a fresh process (the tap is still timed from the list, not the app's launch).

JVM tests (`PageReaderTest`) check that a paper opened again renders exactly one page, already
zoomed, even while reading its stored analysis takes a while; that a miss shows the whole page
at once (a fresh analysis takes seconds); and that a saved zoom opens from stored crops.

## What it found

Before (cached Attention, warm process): `settled in 815 ms; 4 frame(s) [message "Loading…",
whole page 1, whole page 1, zoomed page 1]`. Three causes:

1. **The system bars.** The reader hides them on opening, but on the Boox the window's insets
   report them gone only about 550 ms later. Until then the reader was laid out around them
   (1860x2292), so it analysed (a cache miss: another aspect) and rendered for that size, then
   did both again for the whole screen (1860x2480). `HideSystemBars` now says the bars are on
   their way out until the insets first report them hidden (or 2 s), and meanwhile
   `ReaderFrame` lays the page out for the window they'll leave, keeping clear only of a cutout.
2. **Whole pages while the analysis was looked up.** Pages rendered whole until any analysis
   arrived, and a whole page renders faster than a stored analysis loads. Now the analysis says
   when it must be computed afresh (`AnalysisCache.analyze`'s `onMiss`); until then the reader
   draws no page. A miss still shows the whole page at once.
3. **"Loading…"** flashed for the ~40 ms it takes to open a PDF on disk. It now shows only if
   opening takes over 500 ms (downloading).

Then the time:

- A saved zoom re-solved on every open (~270 ms warm, ~800 ms in a fresh process): its crops are
  now stored with the analysis (format 2).
- Loading decoded millions of values through `DataInputStream` over a buffered gzip stream, a
  synchronized call per byte. It now inflates the file whole into an array sized from the gzip
  trailer (checking the CRC and size) and decodes from the array with bulk reads of int and
  double arrays (`BytesInput`); in a fresh process the app's code runs interpreted, so a call
  per value is what costs. Reading, decoding and touching the file are one trip off the main
  thread.
- The PDF is hashed while `PdfRenderer` opens it, and crops already solved are used in the same
  composition as the analysis, a frame sooner.

## On the Boox Note Air5 C

Debug build, cached analysis, times from the tap to the zoomed page drawn (ranges over 3-4
opens), with the frames drawn:

| paper | before, warm | after, warm | before, new process | after, new process |
| --- | --- | --- | --- | --- |
| Attention (15 pages) | 792-833 ms, 4 frames | 209-356 ms, 1 frame | 995-1014 ms, 4 frames | 629-655 ms, 1 frame |
| DiLoCo (saved zoom 0.805) | 1104-1154 ms, 3-4 frames | 214-329 ms, 1 frame | 1805-1820 ms, 4 frames | 627-698 ms, 1 frame |
| PDLT book (471 pages) | 881-922 ms, 4 frames | 248-385 ms, 1 frame | 1110-1140 ms, 4 frames | 581-630 ms, 1 frame |

A first open (no stored analysis) went from 4 frames to 2: the whole page at once, then the
zoomed page when the analysis is done, seconds later.

What's left, warm: ~20-40 ms from the tap to the reader composed, ~20 ms to open the PDF,
~20-30 ms to lay out the reader, 50-180 ms to read the analysis (more for a long book), ~10 ms
to place the page and 50-90 ms to render and draw it. In a new process, reading the analysis
takes 300-400 ms: the decoder runs interpreted until the JIT warms up.
