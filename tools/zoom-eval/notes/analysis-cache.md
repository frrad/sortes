# Caching the reader's zoom analysis

`analyzePaper` takes seconds on the Boox, and pages show whole until it finishes. The reader now
stores each analysis on disk (`app/src/main/java/dev/sortes/app/zoom/AnalysisCache.kt`), so it
runs only on a paper's first open (per screen shape and app build).

## What is stored

The whole analysis (`AnalysisCodec`), not just the automatic crops:

- the sample (and the readable pages it came from), the mask density, the page count;
- each sampled page's mask as the model sees it, packed to bits;
- each sampled page's text measures (`PageText`: glyph height, `ElementLoss` cells and masses,
  `LineTruncation` segments), written exactly, doubles included;
- the paper rule (the furniture's recurring line heights);
- the automatic crops (`ModelCrops`, every `PageCrop`), so opening solves nothing;
- since format 2, any crops solved at other scales: the reader stores the analysis again
  (`AnalysisCache.update`) after solving a saved zoom's crops, so a paper with a saved zoom
  opens without solving either (about 270 ms on the Boox), and the zoom curve's points solved by
  then ride along.

Storing only the crops would show sampled pages at once, but manual zooms, the zoom curve and
every unsampled page (most of a book) would still need the sample rendered again. With the
masks and text measures, a restored `PaperAnalysis` is the one computed: the same crops at every
scale, the same placements on every page (tested bit for bit in `AnalysisCacheTest`). Unsampled
pages are still rendered and measured when shown, as after a fresh analysis.

Size: gzip'd, about 54 KB a sampled page: 0.8 MB for the 15-page Attention, 3.1 MB for the
471-page PDLT book (60 sampled pages). The directory (`cacheDir/zoom-analysis`, which Android
may clear under storage pressure) is kept under 64 MB by dropping the least recently used.

## Key and invalidation

The key is the PDF's SHA-256 (about 20 ms for a 5 MB PDF on the Boox), the screen aspect (its
exact bits), the most pages sampled, `CropConfig.fingerprint()` (the name, the rules' classes,
the furniture flag and the weights' data-class text: any weight change), the format and
pipeline versions, and the app build: versionCode and install/update time, so every new APK,
debug builds included, analyses afresh without anyone bumping a constant. The key's text is
stored in the file and checked, and the file name is its hash.

Reading checks every size and index and reads to the end of the gzip stream (its CRC), so a
truncated, damaged or foreign file is a miss: it's deleted and the paper analysed again.
Writes go to a temporary file and are renamed. A fresh analysis is shown first and stored in
the background (an application scope), so first opens don't wait for the write.

Only complete analyses are stored: if any sampled page failed to render, or its 4× render
failed (memory, say), the next open analyses again rather than keeping a worse answer for good.

Not in the key: the system's PDF renderer. On Android 15 it is a Play-updated system module
(see `renderer-fidelity.md`), so it can change without an app update; analyses stored before
such an update stay valid crops from the previous renderer until the next app build or
eviction. Keying on it would need the module's version, which apps can't read reliably.

Files stored by older builds aren't deleted at once (their names are hashes); they go when the
64 MB limit drops the least recently used.

The model's per-page padding and segmentation (`CropModel.prepare`) is now lazy, so a restored
analysis pays for it only when a manual zoom or the curve first solves.

## On the Boox Note Air5 C

Time from opening the reader to the crops (the reader's log), clean cache:

| paper | first open | reopen (same process) | reopen (new process) |
| --- | --- | --- | --- |
| Attention (15 pages) | 5627 ms | 155, 87 ms | 206 ms |
| PDLT book (471 pages, 60 sampled) | 18496 ms | 310, 202 ms | 343 ms |

Reinstalling the APK makes the next open a fresh analysis (5630 ms), then cached again (155 ms).

How long opening takes from the tap to the zoomed page, and what else the reader draws on the
way, is in `open-latency.md`.
