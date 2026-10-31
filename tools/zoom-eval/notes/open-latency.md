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

## Showing the stored first page

After the work above a reopen still waited, one after the other, for the PDF to open, its
zoom analysis to be read (50-180 ms warm, 300-400 ms in a new process) and page 1 to be placed,
rendered and drawn: 180-330 ms warm and 530-700 ms in a new process (debug build).

Now the reader stores page 1 as it showed it (`app/src/main/java/dev/sortes/app/pdf/PageSnapshots.kt`),
the exact pixels `PdfRenderer` rendered at the crop, and shows them as soon as the paper is
tapped again, before the PDF is open or the analysis read. Then everything runs as before: the
analysis is read, page 1 placed and rendered, and the render compared with the stored page
(`Bitmap.sameAs`). Identical (always, so far), the stored bitmap stays on screen and isn't drawn
again; different, the render replaces it (a second refresh, but never a wrong crop for longer)
and is stored.

- **Key.** One file per paper, `cacheDir/first-pages` (kept under 32 MB, least recently used
  first). It's used only if stored by this app build (versionCode and install time, as for the
  analysis), with this `CropConfig`, `PIPELINE_VERSION` and format, for this PDF file, page and
  zoom (automatic, or the exact saved scale), and only if the reader's size is the one it was
  rendered for. The PDF is identified by path, size and modification time, not its SHA-256:
  hashing reads the whole file (20-60 ms), longer than the rest of showing the page; the app
  writes a paper's PDF once, and a stale page would still be caught by the comparison.
- **Format.** Lossless: the bitmap's bytes as `copyPixelsToBuffer` gives them, deflated at the
  fastest level in 8 bands of rows, each with its CRC-32, inflated side by side. Measured on the
  Boox for a 1860x2480 page (debug build): rendering it with `PdfRenderer` takes 14-33 ms (37-68
  ms the first time in a process), so a stored page only pays because it doesn't wait for the
  PDF and the analysis. Decoding a PNG of it took 52-63 ms (encoding 320-420 ms); lossless WebP
  38-96 ms, and didn't give back the same pixels; one inflater over the raw bytes 29-37 ms (0.2-0.9
  MB on disk); 8 bands side by side 18-35 ms, then 5-20 ms to copy into a bitmap. 8-bit grey would
  inflate in a third of the time, but pages needn't be grey to the pixel (Attention's and
  DiLoCo's page 1 aren't), and drawing grey through an alpha mask didn't give the same pixels.
  Corrupt, truncated or foreign files are misses, and deleted (`PageSnapshotsTest`).
- **Earlier start.** The read starts on the tap in the list (`prefetchFirstPage`), not when the
  reader is composed, 20-60 ms later. The reader is composed at once with no pages
  (`PageReader(pages = null)`), so the stored page is drawn by the same `Image` that later shows
  the rendered pages: nothing is drawn twice.
- **No second draw.** The page `Image` has its own layer, so the system bars leaving (about
  550 ms after opening, see above) no longer draws the page again; with the confirmed page kept
  as the same bitmap, page 1 is drawn exactly once.
- **While the analysis is computed afresh** (its file evicted, say), the stored page stays rather
  than give way to the whole page; it's then confirmed as usual.

Tried and dropped: writing a new bitmap's memory while the pixels inflate (the copy got faster,
the inflate slower: no gain); `Bitmap.prepareToDraw` (moved the upload out of the frame's sync
stage, but the frame took as long).

Not done, and why:

- **Skipping the hash** with a file identity → hash map: the hash runs while `PdfRenderer` opens
  and only gates the analysis, which is no longer on the way to page 1.
- **Reading the analysis before the PDF opens**, for the size the reader will have: same reason.

### Draw to display

`drew zoomed page 1` is when the reader's draw code ran. From there (`dumpsys gfxinfo
framestats`, debug build) the frame took 2-4 ms to record, ~11 ms to upload the 18 MB bitmap
to the GPU and 5-15 ms on the GPU: 20-26 ms until handed to SurfaceFlinger. Then the e-ink
controller updates the panel, which takes far longer and is outside the app's control (the
Boox reports no present time).

### Release builds

Debug builds run the app's code unoptimised. The `benchmark` build type is the release build
(R8, not debuggable) signed with the debug key, so it installs over a debug build with
`adb install -r`, keeping the library: `./gradlew assembleBenchmark`. Installed by adb, its code
runs interpreted and JIT-compiled until the device compiles it in the background; compiled
ahead of time (`adb shell cmd package compile -m speed -f dev.sortes.app`), a new-process open
took 219-286 ms before this change, against 268-371 ms as installed (same session).

### On the Boox Note Air5 C

Cached Attention, DiLoCo (saved zoom 0.805) and the PDLT book, tap to zoomed page 1 drawn, 4
warm and 3 new-process opens each, before (main at 869a067) and after; every open drew one frame
and every stored page was confirmed identical:

| build | before, warm | after, warm | before, new process | after, new process |
| --- | --- | --- | --- | --- |
| debug | 178-332 ms | 58-119 ms | 532-700 ms | 136-172 ms |
| release (`benchmark`) | 133-271 ms | 75-97 ms | 250-341 ms | 79-119 ms |

What's left (release): 15-35 ms from the tap to the reader composed, which runs alongside the
read; 40-60 ms to read the stored page (file, inflate, copy into a bitmap); then a frame or two to
compose, lay out and draw it. A first open is unchanged: the whole page, then the zoomed one.

`scripts/measure-open.sh` now goes back with the system Back (a tap on the arrow sometimes
landed on the page and turned it), finds papers above as well as below in the list, and reports
whether each stored page shown was confirmed.
