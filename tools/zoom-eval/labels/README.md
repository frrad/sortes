# Crop labels

The owner's judgements of the reader's crops: the long-term dataset the crop model is scored and
fitted against. There is one JSON file per paper, written by the labeller
(`tools/zoom-eval/labeller/`, started with `tools/zoom-eval/label.sh`). The schema is in
`tools/zoom-eval/labeller/README.md`.

**Treat these files as data that must persist.** Commit them after every labelling round, in
the PR that fits the model to them or in a small labels-only PR, so nothing labelled is lost on
an unmerged branch. Don't edit them by hand. If the schema changes, migrate them in code, with a
test.

## How they're used

Each file records the crops it judged (`labelledAgainst`: commit, algorithm, weights, scale,
aspect), so labels made against older crops stay interpretable after the model changes. For
scoring and fitting (`zoom-eval`):

- **Pages:**
  - Pages with no label count as **no opinion**, not as good.
  - A page's moved window (`idealCentre`) and the paper's zoom (`idealScale`) are targets: the
    model is scored on how far its window lies from them, and on ink it cuts that the ideal
    crop keeps.
  - Verdicts and tags score agreement: a crop judged bad should change, one judged good should
    stay.
- **Comments are first-class input.** Each one is summarised per paper and mapped to the label
  or score term it implies, e.g. "could be slightly tighter" is a zoom target and "could move
  up slightly" is a vertical position target. The mapping is reviewed with the owner before
  it's used.
