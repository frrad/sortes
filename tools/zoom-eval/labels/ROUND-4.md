# Labelling round 4

The owner reviewed six new papers on 2026-10-30. Labels were autosaved in the
labeller's original worktree; these six JSON files are copied byte for byte.
They judge TUNED at c307074745ad, with the block-aware weights fitted on rounds
1–3. This round was not used to choose those weights.

## Held-out result

Scored before any round-4 search against merged main 4fb0770 (#41), with its
unchanged TUNED weights. The intervening changes include block accounting,
zoom-walk refinement and performance work; this evaluates the current model,
not a reconstruction of every pixel of c307074. The scorer reconstructs the
judged crops using the recorded config and weights with the current code.

| paper | reviewed pages | agreeing | zoom: model / owner |
| --- | --- | --- | --- |
| Attention is not Explanation | 8 | 8 | 0.845 / 0.845 |
| Neural Tangent Kernel | 8 | 7 | 0.805 / 0.805 |
| SuperGLUE | 7 | 6 | 0.810 / 0.810 |
| MMLU | 4 | 4 | 0.840 / 0.840 |
| Maldacena | 6 | 6 | 0.795 / 0.795 |
| Perelman | 8 | 6 | 0.685 / 0.755 |
| **Total** | **41** | **37** | **5 of 6 zooms** |

Agreement uses the existing thresholds: centre within 8 pt, at most 0.25% of
the page's ink lost that the owner's crop keeps, and zoom scale within 0.015.
The unchanged TUNED score is 26.65; READER scores 29.32. See
[the raw report](ROUND-4-heldout-report.md). Its generic "fitted" wording means
selection between READER and the frozen TUNED here: zero random trials ran.
Its leave-one-out section is not a new fitting experiment.

## Feedback

- **Attention is not Explanation:** "very good"; eight good pages. No zoom or
  position changes.
- **NTK p1:** "i prefer to keep the footnote at the expense of that horizontal
  bar". The owner moved the window down; the model differs by 20 pt and loses
  2.70% of ink the owner's crop keeps. Seven other pages are good.
- **SuperGLUE p1:** "again get that footnote at the expense of the top vertical
  line is the preferred tradeoff". Another downward move, 20 pt from the
  model; 2.42% kept-ink loss. Six other pages are good. This reinforces a
  preference for substantive footnotes over decorative rules; it does not
  imply permission to sacrifice title text generally.
- **MMLU:** "mostly fine. a hair out of vertical balance tho i think". The
  owner moved pp. 2–5 to y=399. The model is 5 pt away, within the agreement
  threshold. Keep the explicit moves as targets; don't invent a zoom change.
- **Maldacena:** "no notes, lg"; six good pages. No changes.
- **Perelman:** the owner loosened the whole paper's scale from 0.685 to
  0.755 to include equations on pp. 15–16: "I'm torn here. i might expand
  the crop to get that equation in it. esp since the overall crop box is
  pretty tight i think it will still be readable on the tablet with a
  slightly larger window"; p16 says "same as prev page". The owner also
  moved both windows. These miss by 10–11 pt and lose 0.38–0.54% of ink the
  owner's crop keeps. Six other pages are good. This is evidence against
  treating the equation tails as harmless nibbles (BLOCKS.md, audit notes).

No interpretation overrides were added: explicit centres, the explicit
Perelman zoom and good verdicts already express the numerical targets.
Comments remain available as qualitative evidence. In particular, paper-level
praise has not silently been turned into a stronger numerical zoom target.

## Reproduce

From tools/zoom-eval, after :zoom-eval:installDist:

```sh
flock /tmp/sortes-gradle.lock build/install/zoom-eval/bin/zoom-eval fit \
  --trials=0 --variants=reader \
  --only=1902.10186,1806.07572,perelman-0211159,maldacena-9711200,2009.03300,1905.00537 \
  --out="$HOME/sortes-review/round4-heldout"
```

The app still uses READER. This round does not authorize switching its config.

## Search after recording the held-out result

The unchanged TUNED scores **69.69**, with **140 of 148 pages** and **19 of 22
zooms** agreeing across all four rounds. The current-code recomputation is in
[the all-round report](ROUND-4-all-frozen-report.md); use it as the baseline for
future experiments rather than combining historical zoom counts.

A seed-1, 30-trial search varied only kappa, splitInk and zeta (the block
penalty, split saturation and legibility reward), keeping the remaining tuned
weights fixed. Its best random candidate scored **69.785** (kappa 0.26689,
splitInk 0.0042066, zeta 0.31637), slightly worse than unchanged TUNED. No
sampled adjustment was adopted. This is a small search, not proof that no
better weights exist.

The search also scores controls: READER, default weights with furniture and
marks, and the fixed parameters with the three searched weights at their
defaults. The furniture/marks control scores 61.02, but uses the earlier cut
budget without block costs, whose sliced figures/lines motivated the block
model. A lower summed score alone does not justify restoring it. The best
random candidate above excludes these controls. Full trial scores are in
[ROUND-4-search.tsv](ROUND-4-search.tsv).

Reproduce the training experiment from tools/zoom-eval:

```sh
flock /tmp/sortes-gradle.lock build/install/zoom-eval/bin/zoom-eval fit \
  --trials=30 --seed=1 --variants=furniture+marks \
  --alpha=1 --beta=0.5 --epsilon=0 --gamma=0.1 --delta=0.005 --delta2=0.02 \
  --allowance=0.01 --blockGap=2 --maxWindows=3 --autoMargin=0.01 \
  --autoMaxLost=0.005 --autoMaxPageCut=1 --zoomIgnoredPages=0 \
  --out="$HOME/sortes-review/round4-search"
```

Further model work should address the distinction between decorative rules
and substantive content, and the cost of rare equation tails versus loosening
a whole paper. No paper-specific rules, model changes or app changes were made
in this round.
