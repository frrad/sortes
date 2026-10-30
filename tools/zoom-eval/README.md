# zoom-eval

An offline harness for the reader's zoom. For each paper in a corpus, it draws contact sheets
comparing several crop choices side by side, and writes a cost breakdown per paper.

It compiles the app's real `zoom/` sources, not a copy, so it runs exactly the reader's code:
`InkMask`, the ignorable-ink rules, `CropAnalysis` and `CropModel`. Only `PaperAnalysis.kt` is
left out, because it needs Android. Pages are rendered with `mutool` (MuPDF) in place of
Android's `PdfRenderer`.

## Running

Needs `mutool` and network access to arXiv. On a memory-starved host, run Gradle under the
shared lock:

    tools/zoom-eval/fetch-corpus.sh            # PDFs into ~/.cache/sortes-zoom-eval/pdf
    flock /tmp/sortes-gradle.lock ./gradlew :zoom-eval:run
    flock /tmp/sortes-gradle.lock ./gradlew :zoom-eval:run --args="--only=1706.03762 --gamma=0.2"
    flock /tmp/sortes-gradle.lock ./gradlew :zoom-eval:test

Options:

| option | default |
| --- | --- |
| `--corpus=` | `corpus.tsv` |
| `--columns=` | `columns.tsv` |
| `--pdfs=` | `~/.cache/sortes-zoom-eval/pdf` |
| `--out=` | `~/sortes-review/model` |
| `--aspect=` | 0.75: the Boox reader, the whole 1860 × 2480 screen |
| `--scale=` | each column's automatic zoom; or one fixed scale for every paper, as a manual zoom |
| `--weights=` | `weights.properties` |
| `--only=` | every paper; or a comma-separated list of ids |

Any weight can be set directly: `--alpha= --beta= --epsilon= --allowance= --gamma= --delta=
--delta2= --maxWindows= --kappa= --blockGap= --splitInk= --zeta= --autoMargin=` and the rest of
`WEIGHT_NAMES`. Papers run one at a time in a 768 MB heap.

A column's overrides may name a config, `config=tuned`: its rules and weights, then the other
overrides on top.

### Reviewing crops by eye

    flock /tmp/sortes-gradle.lock ./gradlew :zoom-eval:run \
        --args="review --configs=reader,tuned,tuned:kappa=0.5;zeta=1 --only=1412.6980 --blocks=2"

writes, to `~/sortes-review/model/fable` by default, one readable (360 px) image per page and
config: the page in its frame, the window outlined, what's off screen shaded, cut ink boxed in
red and, with `--blocks=`, the blocks outlined in blue; plus a grid per paper and an index with
each config's zoom, windows, cut and sliced-line pages. A config is a `CropConfig` name with
optional `weight=value` overrides separated by semicolons. `--scale=` renders every paper at one
fixed zoom (e.g. 0.7, to see what slices at a tight zoom).

## Columns

`columns.tsv` lists the sheets' columns. Each row has:
- a label;
- an algorithm: `model` (`CropModel`, what the reader uses) or `anchor` (the reader's previous
  per-page anchored placement, kept for comparison);
- an ink threshold: `default` (the reader's) or a fixed luminance, e.g. `224` (the old one);
- weight overrides for that column.

Every column applies the reader's ignorable-ink rules and is scored with the model's cost.

## Tuning loop

The weights are defined once: `CropModelWeights` in
`app/src/main/java/dev/sortes/app/zoom/CropModel.kt`. Its defaults are what the reader uses. To
tune:

1. Put candidate values in `weights.properties` (applied to every column), or pass them as
   `--name=value`, or add a column with its own overrides to `columns.tsv` to compare settings
   side by side.
2. Run `flock /tmp/sortes-gradle.lock ./gradlew :zoom-eval:run`, which regenerates
   `~/sortes-review/model/`.
3. Look at the board: `index.html` and the contact sheets.
4. Copy the winning numbers into `CropModelWeights`' defaults, clear `weights.properties`, and
   run `./gradlew :app:testDebugUnitTest` (the model's tests pin some behaviour that depends on
   the weights).

## Output

- `index.html`: a summary table, plus each paper's cost breakdown (scale, K, switches, breaks,
  cut %, worst page %, each term, and the window per page).
- `<id>.png`: the contact sheet. For every page, one thumbnail per column, each showing:
  - the padded screen frame;
  - the window, outlined in its window's colour (W1 green, W2 blue, W3 magenta);
  - what's off screen, shaded;
  - cut ink in red: ink outside the cells the window covers, exactly as the cost counts it;
  - ink ignored by the ignorable-ink rules in blue.
- `<id>.pages.tsv`: every page's window, position, cut, page cut and off-centredness under every
  column, and its edge slices: for each edge, the inked cells on the window's edge row or column
  with ink directly beyond them (`EdgeSlices.kt`; a measure only, the model doesn't use it).
- `summary.tsv`: one row per paper and column, with the cost features (for fitting weights), and
  the pages whose top or bottom edge slices a line (`slicedLinePages`: at least 3 cells) or whose
  side edges slice ink (`slicedSidePages`).

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
`labels/interpretation.tsv`). It then searches for weights and model variants that score better.

    flock /tmp/sortes-gradle.lock nice ./gradlew :zoom-eval:run \
        --args="fit --trials=400 --variants=reader,furniture+marks --split=<round-1 ids>"

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
- `--trials=` random weight sets (`sampleWeights`), alternating over `--variants=`. Variants:
  - `reader`, today's reader;
  - `furniture`, `PageFurniture`;
  - `marks`, `IsolatedMarks`;
  - `furniture+marks`.
- `--<weight>=` fixes a weight and also scores that set as "given".
- `--split=id,…` adds a held-out test between that group and the rest (e.g. round 1 against
  round 2).
- `--out=` is the report folder, by default `~/sortes-review/fit`.
- `--labels=` scores other labels, e.g. `labels/agent` (an agent's own review of renders, kept
  apart from the owner's; see `labels/agent/README.md`).

**Report** (`report.md`):
- the best set and per-paper scores before and after;
- leave-one-paper-out and by-group held-out scores;
- **agreement in principle** per paper: window within 8 pt, at most 0.25% of the kept ink
  cut, zoom within 0.015;
- every page that changes;
- where the defaults lose.

`trials.tsv` has every trial's per-paper scores.

## Configs: one definition for the harness, the labeller and (later) the reader

`CropConfig` (`app/src/main/java/dev/sortes/app/zoom/CropConfig.kt`) bundles everything that
decides crops besides the pages: per-page ignorable-ink rules, whether to ignore page furniture,
and `CropModelWeights`.

- **`CropConfig.READER`** is what the reader uses today (`analyzePaper`'s default).
- **`CropConfig.TUNED`** is the candidate tuned on the owner's labels: furniture and isolated
  marks ignored, γ 0.1.

The fit scores `TUNED` as its "given" set, and the labeller renders it with
`labeller-export --config=tuned` (or `LABELLER_ARGS="--config=tuned" tools/zoom-eval/label.sh
--render`). Weight options override a config's weights, and `--variant=` swaps its rules. The
fit's variants are built as `CropConfig`s too, so everything runs the reader's own code path.

To move the reader to a tuned config once the owner approves, change `analyzePaper`'s default
`config` to `CropConfig.TUNED`: one line.


## Experimental content costs (digital-native PDFs)

`content-experiment` tests a fine-component omission cost without changing READER
or TUNED. It includes a target-cost audit, bounded exhaustive scale sweeps, a
placement-only control, optional worst-page sensitivity and comparison images.
See [the experiment and results](notes/content-elements/README.md) for commands,
limitations and the cases it fixes or regresses.

`text-size` measures each paper's body-text size from page images (no PDF text), and
`zoom-experiment` tests a text-size-aware legibility cost for the automatic zoom, a
truncated-line placement cost and worst-page emphasis. See
[text-size notes](notes/text-size/README.md).
