# Labelling round 1

## The round

- **Crops judged:** the reader's crops at commit `f80ae4ffc214`, with the default
  `CropModelWeights` (α 1, β 0.1, ε 300, allowance 0.01, γ 0.05, δ 0, δ₂ 0.02, up to 3 windows),
  the `reader` algorithm, at aspect 0.75.
- **Papers:** six were shown and five labelled. PDLT pp. 1–40 wasn't labelled.
- **Moves:** "move" is the owner's window centre minus the model's, in points (x right, y down).
  The model's window is about 500 × 665 pt on a US-letter page.
- **Zoom:** a scale is the fraction of the padded page the window shows; zoom = 1 / scale.

## How the labels are read

These rules turn the labels into scoring targets for phase 2.

**Pages**
- **Unlabelled pages:** no opinion. They are neither good nor bad, and nothing is scored on them.
- **Verdicts:** "good" says to keep this crop, and is used as a regression check; "bad" says the
  crop should change. A verdict judges what the page showed at the time. Once the owner had set
  a paper zoom, that was their window at that zoom, at the model's position unless moved.

**Moved windows**
- **Confidence:** a move is a position target, high confidence if it's 8 pt or more, or backed
  by a comment. Unexplained moves under about 8 pt are **low confidence**: until the labeller
  was fixed, a plain click inside the window could record a small move. Low-confidence targets
  are scored at a quarter of the weight. A move of 0 is no signal.
- **Moves under a different zoom:** a move is a centre, so it holds at any zoom. A window centred
  there, at the paper's ideal zoom, is the ideal crop.

**Zoom**
- **Paper zoom:** an ideal zoom on a paper is the paper's zoom target.
- **Per-page zoom words:** "slightly tighter" or "not tall enough" on one page is a weak zoom
  vote. The crop size is one per paper, so these only shift the paper target, and only when
  the owner gave no zoom of their own.

**Comments**
- **Must-keep:** "X and Y both visible" is a request to keep the ink the ideal crop keeps. It's
  scored as that ink cut by the model, which is soft: it can't always be met at one size per
  paper.
- **Affirmed behaviour:** for example, "good job cutting off that page number". This becomes a
  regression check.

## Attention Is All You Need (1706.03762)

- **Zoom:** the model's 1.23× (scale 0.815), unchanged by the owner.
- **Labelled:** 6 of 15 pages, no verdicts.

| page | label | interpretation |
| --- | --- | --- |
| 1 | move (+6.3, +11.7); "ideally the red text at the top and the footnote at bottom would both be visible." The crop cuts 1.3% of the page (the conference footer). | **Must-keep:** both the top notice and the bottom footnote. They span about 700 pt, more than the window's 665, so this can't be met at this zoom; it's a soft target. The move is down (+11.7) towards the footnote, high confidence (backed by the comment). |
| 2 | move (+0.2, −6.2); "this is good. could mb be slightly tighter, but good job cutting off that page number" | Good, and a weak zoom-in vote. **Affirmed:** cutting the page number. The move is low confidence (6 pt, not explained). |
| 3 | move (+3.3, −5.9); "also good" | Good. The move is low confidence. |
| 4 | move (+2.8, −6.2); "very tight on top, but a bit of gap on bottom. could move up slightly" | **Vertical position target:** up about 6 pt, high confidence (backed by the comment). This is evidence that vertical centring is a little off. |
| 5 | move (+1.5, −7.1); "up - down centering was maybe not quite perfect" | **Vertical position target:** up about 7 pt, high confidence. Same as page 4. |
| 6 | move (0, 0), no comment | No signal (a click). |

## DiLoCo (2311.08105)

- **Zoom:** the owner set 1.21× (scale 0.825), tighter than the model's 1.13× (0.885). This is
  the paper's zoom target.
- **Labelled:** 12 of 16 pages. Pages 7–16 are good, judged at the owner's zoom.

| page | label | interpretation |
| --- | --- | --- |
| 1 | move (−4.4, −1.7); "i hesitate to say this, but might not be tall enough? ideally we get both google and the full footnote. though mb ok to cut off google depending on how other pages look. At this higher zoom level i really don't like that we lost that footnote but if there are no more like this might be worth it" | **Soft must-keep:** "Google" and the full footnote. The owner trades this page for a tighter zoom on the rest. This is the case the per-page cut penalty (ε) and the own-window fallback are for. The move is low confidence. |
| 2 | move (−3.7, −3.5); "ok opposite here, i zoomed in much tighter. this page looks great at this zoom level and I'm willing to sacrifice the 1st page a bit i think" | Good at the owner's zoom, supporting the zoom target. The move is low confidence. |
| 7–15 | good | Good at the owner's zoom, at the model's position. Regression checks. |
| 16 | good; "yea the 1st page makes me a lil sad but the rest looks really good at this zoom / box" | Good. Confirms the paper zoom target and the page 1 trade-off. |

## Batch Normalization (1502.03167)

- **Zoom:** the model's 1.17× (scale 0.855), unchanged.
- **Paper comment:** "overall zoom level looks good, but some misses on (primarily left-right)
  padding". The zoom is affirmed. Horizontal centring is the issue.
- **Labelled:** 4 of 11 pages.

| page | label | interpretation |
| --- | --- | --- |
| 1 | move (−10.4, +3.3); "padding uneven both lr and up-down" | **Position target:** 10 pt left, high confidence. **Off-centre** both ways. |
| 5 | move (−8.5, −2.7); "little off left-right" | **Position target:** 8.5 pt left, high confidence. |
| 8 | move (−11.5, +0.3), no comment | **Position target:** 11.5 pt left, high confidence (over 8 pt). |
| 11 | move (+11.0, −3.1); "cut off some table unnecessarily!" The crop cuts 1.2% of the page. | **Must-keep:** the table, which sliding the window keeps. **Position target:** 11 pt right, high confidence. |

The shift is consistent: about 10 pt left on pages 1, 5 and 8. This points at how the model
centres a two-column page horizontally, more than at a weight.

## DDPM (2006.11239)

- **Zoom:** the model's 1.23× (scale 0.81), affirmed: "this is the right zoom level".
- **Labelled:** 1 of 25 pages.

| page | label | interpretation |
| --- | --- | --- |
| 1 | move (+0.1, +19.0); "nothing looks super good on this page, but 1/ this is the right zoom level, rest of paper looks great 2/ if we have to choose, i'd rather lose that line over than the footnote" | **Zoom affirmed;** the rest of the paper is affirmed (no verdicts, though). **Must-keep:** the footnote. Losing the top line is acceptable. **Position target:** 19 pt down, high confidence. |

## Shannon, A Mathematical Theory of Communication (shannon1948, a scan)

- **Zoom:** the owner set 1.26× (scale 0.795), tighter than the model's 1.18× (0.845). This is
  the paper's zoom target.
- **Paper comment:** "crop was too loose, not well balanced. i fixed a few for you".
- **Labelled:** 4 of 45 pages.

| page | label | interpretation |
| --- | --- | --- |
| 1 | move (−25.0, +15.0) | **Position target**, high confidence. |
| 2 | move (+23.7, +9.7) | **Position target**, high confidence. |
| 3 | move (−25.0, +15.0) | **Position target**, high confidence. |
| 4 | move (+15.0, 0) | **Position target**, high confidence. |

The moves alternate left and right by about 25 pt on odd and even pages. The scan is a
two-sided print layout, which the model covered with one window at its looser zoom. At the
owner's zoom it should be two windows, one per side. "Too loose" also says the auto zoom is too
conservative on this scan.

## What the round says overall

1. **Zoom:** right on Attention, BatchNorm and DDPM. Too loose on DiLoCo (the owner's 0.825
   against 0.885) and on Shannon (0.795 against 0.845). The owner accepts a sacrificed title
   page (DiLoCo p1) for a tighter zoom elsewhere.
2. **Horizontal centring:** off by about 10 pt on BatchNorm, a two-column paper, and by about
   25 pt alternating on the Shannon scan (two windows needed).
3. **Vertical balance:** a little high or low on Attention p4–5 (about 6–7 pt) and on BatchNorm
   p1.
4. **Title pages:** must-keep requests on every title page: Attention, DiLoCo and DDPM p1. The
   owner prefers keeping footnotes, and the top line can go.
5. **Affirmed:** cutting page numbers (Attention p2).
