# zoom-eval

An offline harness for the reader's zoom: review boards, the labeller's renders, and scoring
against the owner's labels.

It compiles the app's real `zoom/` sources, not a copy, and gets every crop from the reader's
own entry point, `analyzePaper` (`app/src/main/java/dev/sortes/app/zoom/PaperAnalysis.kt`):
the same page sampling, mask resolution, thresholds, fine renders for the text model, zoom
search and placement of unsampled pages (`readerCrops` in `ReaderCrops.kt`). The one difference
is the pixels, behind the shared `PageRenderer` interface: the tablet renders with Android's
`PdfRenderer`, zoom-eval with `PdfiumPdf`, pdfium pinned to the release nearest the one Android 15
ships (chromium/5731), drawn the way PdfRenderer draws and with its whole-point page sizes. On the
corpus that gives the tablet's crops on 32 of 33 papers and its zoom on all of them; see
[renderer fidelity](notes/renderer-fidelity.md), and `device-check` below to re-check it.

## Running

Needs Linux x86-64, network access to arXiv and GitHub, and `mutool` only to cut the book
excerpts when fetching the corpus. On a memory-starved host, run Gradle under the shared lock:

    tools/zoom-eval/setup-pdfium.sh            # pinned pdfium into ~/.cache/sortes-zoom-eval/pdfium-chromium-5731
    tools/zoom-eval/fetch-corpus.sh            # PDFs into ~/.cache/sortes-zoom-eval/pdf
    flock /tmp/sortes-gradle.lock ./gradlew :zoom-eval:installDist :zoom-eval:test
    cd tools/zoom-eval && build/install/zoom-eval/bin/zoom-eval <command> ...

`setup-pdfium.sh` downloads pdfium-binaries' `chromium/5731` and checks its SHA-256;
`SORTES_PDFIUM` points zoom-eval at another install dir. Without it every command stops with an
error saying to run the script. The renderer tests skip without it; with
`SORTES_REQUIRE_PDFIUM=1` they fail instead, for a CI job that has run `setup-pdfium.sh`.

Commands: `review`, `labeller-export`, `labeller-serve` (see `labeller/README.md`), `fit`,
`zoom-experiment`, `text-size`, `bench`, `crops` and `device-check`. Papers run one at a time in
a 768 MB heap.
Weights (`--alpha= --beta= --epsilon= --allowance= --gamma= --delta= --delta2= --maxWindows=
--kappa= --blockGap= --splitInk= --zeta= --autoMargin=`) override `CropConfig.DEFAULT`'s where a
command takes them.

### Reviewing crops by eye

    build/install/zoom-eval/bin/zoom-eval review \
        --configs=tuned+text,tuned+text:kappa=0.5;zeta=1 --only=1412.6980 --blocks=2

writes, to `~/sortes-review/model/fable` by default, one readable (360 px) image per page and
config: the page in its frame, the window outlined, what's off screen shaded, cut ink boxed in
red and, with `--blocks=`, the blocks outlined in blue; plus a grid per paper and an index with
each config's zoom, windows, cut and sliced-line pages. A config is a `CropConfig` name with
optional `weight=value` overrides separated by semicolons. `--scale=` renders every paper at one
fixed zoom (e.g. 0.7, to see what slices at a tight zoom), as a manual zoom in the reader.

### Diffing crops between commits

    build/install/zoom-eval/bin/zoom-eval crops --out=crops.tsv [--only=id,id] [--configs=...]

writes every page's window (and the mask cells it covers) for every paper, one TSV row a page,
so two commits' crops can be compared exactly.

### Speed and memory

    build/install/zoom-eval/bin/zoom-eval bench --only=2005.14165,2106.10165-p1-237

times `analyzePaper` without rendering (every page rendered once beforehand) and reports the heap
the finished analysis keeps. See [the unification notes](notes/unified-pipeline.md).

### Checking pdfium against the tablet

The tablet's renderer can change under us (PdfRenderer's pdfium ships in the MediaProvider
mainline module, updated through Play system updates), and so can our pin. To re-check, dump the
tablet's crops and renders with the app's instrumented `CropDumpTest`, then compare:

    ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest   # install both if the app differs
    adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    D=/sdcard/Android/data/dev.sortes.app/files/fidelity
    adb shell mkdir -p $D/pdf && adb push ~/.cache/sortes-zoom-eval/pdf/. $D/pdf/
    adb shell am instrument -w -e class dev.sortes.app.pdf.CropDumpTest \
        -e dump 1706.03762.pdf,1810.04805.pdf dev.sortes.app.test/androidx.test.runner.AndroidJUnitRunner
    adb pull $D/out /tmp/tablet-dump
    build/install/zoom-eval/bin/zoom-eval device-check --device=/tmp/tablet-dump

(`-e papers a.pdf,b.pdf` limits the papers analysed; `-e dump` lists those whose renders are
saved, about 15 MB a 15-page paper, so dump a few.) For each dumped paper `device-check` reports
whether replaying the tablet's renders gives its crops (it must: the pipeline is the same code),
whether pdfium gives them, and how far pdfium's masks and grey levels are from the tablet's. The
last check (Boox Note Air5 C, Android 15, mediaprovider 352090000): pdfium gives the tablet's
crops on 32 of 33 papers, masks about 3% of ink apart. Dumps aren't committed.

## Corpus

`corpus.tsv` lists arXiv papers and excerpts (plus one non-arXiv scan, by URL):
- single- and two-column, including ACL/NAACL;
- figure-heavy, appendix-heavy and table-heavy;
- math-heavy, including old-style single-column (1997, 2002);
- short papers;
- a small LNCS page;
- two-sided book excerpts with alternating margins;
- a long paper (GPT-3, 75 pages, sampled by the reader);
- an image-only scan (Shannon 1948).

Ids may only use letters, digits, `.`, `_` and `-` (they name label files), so old-style arXiv
ids like `math/0211159` get a name and an explicit URL.

PDFs are never committed. Book excerpts are cut with `mutool merge` by `fetch-corpus.sh`.

## Fitting to the labels

`zoom-eval fit` scores the model against the owner's labels (`labels/*.json`, read through
`labels/interpretation.tsv`). It then searches for weights that score better.

    build/install/zoom-eval/bin/zoom-eval fit --trials=200 --split=<round-1 ids>

**Scoring** (`fit/Score.kt`; lower is better, about 1 unit per noticeable miss):

- **Position:** each labelled page with a target centre (a moved window, or a crop called good)
  scores the distance from the model's window centre, at 1 unit per 10 pt. The distance is
  weighted by confidence: high 1, medium 0.5, low 0.25, good 0.5.
- **Kept ink:** the ink the owner's crop keeps but the model cuts, at 1 unit per percent of the
  page's ink. Page numbers, running heads and isolated marks don't count, since the owner is
  happy to lose them.
- **Zoom:** each paper's distance from the owner's zoom (or the zoom they saw and left, at half
  weight), at 2 units per 0.01 of scale.
- **Good verdicts** are tolerant: a crop the owner called good costs nothing until the model's
  window is more than 8 pt from it (the agreement threshold). Most good verdicts were given on
  one config's own crops, so without the tolerance any model that moves a window at all would
  score worse than the one labelled.
- **Unlabelled pages:** no opinion. They aren't scored.
- **Readings** (`interpretation.tsv`): a page can ask for another page's crop (`high@10`, for
  "same as the rest" on a page judged bad), or count only the horizontal part of its move
  (`medium/x`). A paper's set zoom can be superseded (`unset`).

**Search:**
- `--trials=` random weight sets (`sampleWeights`) around `CropConfig.DEFAULT`'s, each scored
  through `analyzePaper`. Every render is remembered (`MemoPages`, a bit a pixel), so trials
  don't re-render; each trial takes about a minute on the 28 labelled papers, since every paper
  gets the full zoom search.
- `--<weight>=` fixes a weight and also scores that set as "given".
- `--split=id,…` adds a held-out test between that group and the rest (e.g. round 1 against
  round 2).
- `--out=` is the report folder, by default `~/sortes-review/fit`.
- `--labels=` scores other labels, e.g. `labels/agent` (an agent's own review of renders, kept
  apart from the owner's; see `labels/agent/README.md`).

Labels must record the crops they judged (`labelledAgainst.centres`): those are the "stay put"
targets, so scoring never depends on model code that has since changed.

**Report** (`report.md`):
- the best set and per-paper scores before and after;
- leave-one-paper-out and by-group held-out scores;
- **agreement in principle** per paper: window within 8 pt, at most 0.25% of the kept ink
  cut, zoom within 0.015;
- every page that changes;
- where the defaults lose.

`trials.tsv` has every trial's per-paper scores.

## The config: one definition for the reader and the harness

`CropConfig` (`app/src/main/java/dev/sortes/app/zoom/CropConfig.kt`) bundles everything that
decides crops besides the pages: per-page ignorable-ink rules, whether to ignore page furniture,
`CropModelWeights`, and the text model's `TextWeights`. There is one: **`CropConfig.DEFAULT`**
("tuned+text"), what the reader uses (`analyzePaper`'s default) and what the labeller renders.
The old `READER` and `TUNED` configs, and the anchor algorithm before them, are gone; labels made
against them keep the crops they judged (`labelledAgainst.centres`).

The pipeline, all in `app/src/main/java/dev/sortes/app/zoom/`:

1. **Sample** (`samplePages`): every page of a paper of up to 60 pages; facing pairs of a
   longer one.
2. **Render** (`PaperRaster`): one density for the paper, so its largest padded page is 256 px on
   its long side; each sampled page's mask, thresholded against its paper colour.
3. **Strip** the config's ignorable ink: margin stamps, isolated marks, page furniture.
4. **Measure text** (`PageText`): each sampled page rendered 4× finer, one page at a time; its
   body glyph height (`TextSize`), its components (`ElementLoss`) and its line segments
   (`LineTruncation`) are kept, the render isn't.
5. **Zoom** (`CropModel.Prepared.auto`): every scale from 0.60 to 0.97 is solved without the
   text costs; the one minimising the cost per page plus the text-size-aware legibility cost
   (`Legibility`) is loosened by 0.01.
6. **Place**: the crops there are solved with the text costs; pages outside the sample are
   rendered, measured and placed as they're shown (`SampledPaper.cropFor`).

## Experiments

`text-size` measures each paper's body-text size from page images (no PDF text) against the
PDF's fonts. `zoom-experiment` scores variations of the text model (`--T= --n= --floor=
--elements= --lines=`, each a list) on the labelled clean papers, through `analyzePaper`. See
[text-size notes](notes/text-size/README.md). The first component-cost experiment
(`content-experiment`, [notes](notes/content-elements/README.md)) was retired with the old
configs; it can be rerun at a869e26.
