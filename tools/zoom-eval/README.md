# zoom-eval

An offline harness for the reader's zoom. For each paper in a corpus, it compares four ways of
choosing crops:

| method | what it is |
| --- | --- |
| main | the reader today: `CropAnalysis` auto scale, anchor and `placeWindow` |
| main + stamp rule | the same, after the `IgnorableInkRule`s (the arXiv margin stamp, PR #20) |
| model | the crop model in `SPEC.md` (`CropModel.kt`) |
| model + stamp rule | the model, after the `IgnorableInkRule`s |

It compiles the app's real `zoom/` sources, not a copy; only `PaperAnalysis.kt` is left out,
because it needs Android. Pages are rendered with `mutool` (MuPDF) in place of Android's
`PdfRenderer`. All four methods are scored with the model's cost, each on the masks it saw.

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
| `--pdfs=` | `~/.cache/sortes-zoom-eval/pdf` |
| `--out=` | `~/sortes-review/model` |
| `--aspect=` | 0.7692: the Boox view, 1860 × 2418 |
| `--weights=` | `weights.properties` |

Any weight can be overridden directly: `--alpha= --beta= --gamma= --delta= --delta2=
--maxWindows=`. Papers run one at a time in a 768 MB heap.

## Output

- `index.html`: a summary table, plus each paper's cost breakdown (scale, K, switches, cut %,
  each term, and the window per page).
- `<id>.png`: the contact sheet. For every page, one thumbnail per method, each showing:
  - the padded screen frame;
  - the window, outlined in its window's colour (W1 green, W2 blue, W3 magenta);
  - what's off screen, shaded;
  - cut ink in red;
  - ink ignored by the stamp rule in blue.
- `<id>.pages.tsv`: every page's window, position, cut and off-centredness under every method.
- `summary.tsv`: one row per paper and method, with the cost features (for fitting weights).

## Corpus

`corpus.tsv` lists 20 arXiv papers:
- single- and two-column;
- figure-heavy and appendix-heavy;
- short papers;
- a small LNCS page;
- two two-sided book excerpts (30 pages each), with alternating margins.

PDFs are never committed. Book excerpts are cut with `mutool merge` by `fetch-corpus.sh`.
