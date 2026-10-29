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
--delta2= --maxWindows=`. Papers run one at a time in a 768 MB heap.

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

`corpus.tsv` lists 20 arXiv papers:
- single- and two-column;
- figure-heavy and appendix-heavy;
- short papers;
- a small LNCS page;
- two two-sided book excerpts (30 pages each), with alternating margins.

PDFs are never committed. Book excerpts are cut with `mutool merge` by `fetch-corpus.sh`.
