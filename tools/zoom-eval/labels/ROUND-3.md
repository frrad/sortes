# Labelling round 3

## The round

- **Papers:** five the model had never been fitted to: ViT, YOLO, LoRA, Chinchilla and Adam.
- **Crops judged:** `CropConfig.TUNED` (furniture and isolated marks ignored, β 0.5), rendered
  at `37d755c5c48c`. It was tuned on rounds 1–2 only.
- **Notation:** as in ROUND-1.md. A move is the owner's window centre minus the model's, in
  points (x right, y down). Moves are deliberate (Shift+arrow nudges of 5 pt, or drags), so they
  count as high confidence.
- **New readings:** `interpretation.tsv` gains two forms:
  - `high@10`: the page should have page 10's crop, for "same as the rest" comments on pages
    judged bad;
  - `medium/x`: only the horizontal part of a move counts (GDL, below).

## ViT (2010.11929)

- **Zoom:** the model's 1.23× (scale 0.81), unchanged.
- **Labelled:** 10 of 22 pages, all good.

| page | label | interpretation |
| --- | --- | --- |
| 1–6, 19–22 | good | Good. **Regression checks.** |

## YOLO (1506.02640)

- **Zoom:** the model's 1.21× (scale 0.825), unchanged.
- **Labelled:** 8 of 10 pages.

| page | label | interpretation |
| --- | --- | --- |
| 1–4, 7–9 | good | Good. |
| 10 | "seems like this should be cropped same as rest of pages??" | **Like p9**, high confidence. The last page of references fills only the top-left column. The model gives it a second window, 83 pt up and left, to centre what's there. The owner wants the paper's window. |

## LoRA (2106.09685)

- **Zoom:** the model's 1.23× (scale 0.815), affirmed.
- **Paper comment:** "seems perfect".
- **Labelled:** 8 of 26 pages, all good.

| page | label | interpretation |
| --- | --- | --- |
| 1–3, 11, 19, 22, 23, 26 | good | Good. |

## Chinchilla (2203.15556)

- **Zoom:** the model's 1.23× (scale 0.815), unchanged.
- **Labelled:** 12 of 36 pages.

| page | label | interpretation |
| --- | --- | --- |
| 1 | "don't love this page, but the rest of them are great. nothing better we can do here with this crop window." | **No signal.** The title page is wider than the window (the model cuts 6%). The owner accepts it as the price of the paper's zoom. |
| 3–8, 10–14 | good | Good. |

## Adam (1412.6980)

- **Zoom:** the owner set 1.26× (scale 0.795), looser than the model's 1.30× (0.77). This is the
  paper's zoom target.
- **Labelled:** 7 of 15 pages.

| page | label | interpretation |
| --- | --- | --- |
| 6 | move (0, −5); "tiny move, but previously the figures were cropped at the top" | **Position target**, high confidence. **Must-keep:** the figures' tops (the model cuts 0.54%). |
| 7 | move (0, −10); "figure was cropped at the top. had to make window larger to accomodate" | **Position target**, high confidence. **Must-keep** (the model cuts 1.10%). This is the reason for the looser zoom. |
| 11 | bad; "big jump vs prev page. i think it should have been same" | **Like p10**, high confidence. |
| 12–14 | good | Good. |
| 15 | bad; "window moved up for no reason." | **Like p14**, high confidence. |

Pages 11 and 15 end sections: text fills only the top of the page. The model gives them a
second window, 108 pt higher, to centre that text.

## GDL, re-read (2104.13478-p20-49, round 2)

The owner answered the open question: **running heads are always dropped.** On GDL the head
stayed in view only because the page is wide, so at that zoom the window had to include it. It
wasn't a preference. So:

- **Moves:** their vertical parts centred the page *with* its head, so only the horizontal part
  counts (`medium/x`, still at half weight). Vertically, the model centres the body without the
  head, which is the same rule.
- **Zoom:** the owner set 0.85 because p8's content was cut at the reader's 0.79. That cut came
  from fitting the head as well. Without the head, p8's body fits at the zoom the owner saw, so
  0.85 no longer counts (`unset`). The target is the zoom they saw (0.79), at half weight.
- **Must-keep** on pp. 4 and 8 stays. The kept ink never counted the head (scoring strips
  furniture).

## What round 3 says

1. **Held out, TUNED generalises.** Before any re-tuning, scored on round 3 as labelled:

   | | score | pages agreeing | zooms agreeing |
   | --- | --- | --- | --- |
   | `READER` | 66.8 | 19 of 44 | 2 of 5 |
   | `TUNED` | 38.1 | 39 of 44 | 4 of 5 |

   Most of round 3 is "good" verdicts on TUNED's own crops, so TUNED agrees with them by
   construction. That is still the signal: the owner saw TUNED's crops and approved 39 pages.
   READER would have moved those pages by 22–29 pt and zoomed differently on ViT and LoRA.
2. **Second windows for half-empty pages are wrong** (Adam pp. 11 and 15, YOLO p10). β 0.5 makes
   centring a page's little text worth a window of its own. The owner wants the paper's window:
   "same as the rest", "moved up for no reason". AGENTS.md says the same: prefer a crop that
   stays put.
3. **Don't cut figures to centre** (Adam pp. 6–7): looser zoom, window a little higher.
4. **Running heads: always drop them** (GDL re-read above).
