# Labelling round 2

## The round

- **Crops judged:**
  - PDLT: the same crops as round 1, at commit `f80ae4ffc214`.
  - BERT, ResNet, NeRF, Latent Diffusion and GDL: rendered at `dba446cf17bc`, which has the same
    `zoom/` code and the default weights.
- **Notation:** as in ROUND-1.md. A move is the owner's window centre minus the model's, in
  points (x right, y down).
- **Confidence:** this round used the fixed labeller, where only a drag or a Shift+arrow nudge
  moves the window, in steps of 5 pt. So every non-zero move is deliberate and counts as high
  confidence; moves of 0 are no signal. Where the owner said the labels are rough, the paper's
  moves count at half weight.

## The Principles of Deep Learning Theory, pp. 1–40 (2106.10165-p1-40)

- **Zoom:** the model's 1.40× (scale 0.715), unchanged.
- **Labelled:** 5 of 40 pages.

| page | label | interpretation |
| --- | --- | --- |
| 2 | move (−1, −149); "for this page, ok to just crop that page number for consistency with other pages" | **Position target**, high confidence. The model gives this front-matter page its own window, placed low to keep its page number. The owner wants the same window as the other pages, and the page number cut. **Affirmed:** cutting the page number; consistency over keeping it. |
| 10, 14, 16, 18 | move (0, 0), no comment | No signal (viewed, unchanged). |

## BERT (1810.04805)

- **Zoom:** the model's 1.18× (scale 0.85), affirmed.
- **Paper comment:** "this is a nice one, easy. but you did a good job".
- **Labelled:** 11 of 16 pages.

| page | label | interpretation |
| --- | --- | --- |
| 1–11 | good | Good. **Regression checks:** keep these crops. |

## ResNet (1512.03385)

- **Zoom:** the model's 1.21× (scale 0.825), unchanged.
- **Paper comment:** "better to just lose the page numbers and have even up-down balance".
- **Labelled:** 7 of 12 pages.

| page | label | interpretation |
| --- | --- | --- |
| 1, 2, 4–7 | move (0, −15) | **Position target:** 15 pt up, high confidence. |
| 3 | move (0, −10) | **Position target:** 10 pt up, high confidence. |

The model keeps the page number at the bottom, so its window sits low. The owner wants the page
number cut and the body text balanced top to bottom. **Affirmed:** losing page numbers.

## NeRF (2003.08934, small LNCS page)

- **Zoom:** the owner set 1.10× (scale 0.91), tighter than the model's 1.06× (0.945). This is the
  paper's zoom target.
- **Paper comment:** "zoom tighter, lose the header. i did a few pages for you".
- **Labelled:** 5 of 25 pages.

| page | label | interpretation |
| --- | --- | --- |
| 1 | move (0, +10) | **Position target:** 10 pt down, high confidence. |
| 2 | move (+4, +12) | **Position target**, high confidence. |
| 3–5 | move (0, +15) | **Position target:** 15 pt down, high confidence. |

The running header at the top (page number and running title) is what holds the window up.
**Affirmed:** losing running headers.

## Latent Diffusion (2112.10752)

- **Zoom:** the model's 1.21× (scale 0.825), unchanged.
- **Paper comment:** "i did first like 4 pages - I didn't dial it in perfect, but basically ok to
  lose the page number for better balance".
- **Labelled:** 4 of 45 pages.

| page | label | interpretation |
| --- | --- | --- |
| 1 | move (0, −5) | **Position target:** up, at half weight ("not dialled in perfect"). |
| 2 | move (−5, −10) | **Position target**, at half weight. |
| 3 | move (0, −5) | **Position target**, at half weight. |
| 4 | move (−5, −5) | **Position target**, at half weight. |

**Affirmed:** losing page numbers for balance.

## Geometric Deep Learning, pp. 20–49 (2104.13478-p20-49)

- **Zoom:** the owner set 1.18× (scale 0.85), *looser* than the model's 1.27× (0.79). "Page 8
  convinced me" (its content was cut).
- **Paper comment:** "this one is weird. page 8 convinced me to tweak the zoom. then mostly just
  try to center each page is what i ended up on. not super strongly held but this is what i
  think mb looks best?"
- **Labelled:** 9 of 30 pages.

| page | label | interpretation |
| --- | --- | --- |
| 1 | move (0, +5) | **Position target**, at half weight ("not super strongly held"). |
| 2 | move (−5, −35) | **Position target**, at half weight. |
| 3 | move (0, −5) | **Position target**, at half weight. |
| 4 | move (−5, +13); the crop cuts 1.5% | **Position target**, at half weight. **Must-keep.** |
| 5 | move (0, +5) | **Position target**, at half weight. |
| 6 | move (−10, −30) | **Position target**, at half weight. |
| 7 | move (+5, −5) | **Position target**, at half weight. |
| 8 | move (−5, −30); the crop cuts its content | **Position target**, at half weight. **Must-keep:** the reason for the looser zoom. |
| 9 | move (0, −5) | **Position target**, at half weight. |

"Centre each page" pulls against staying put. This book has margin notes on alternating sides
(two windows), so some per-page movement is the layout itself. The rest is noted as a tension
with the "stay put" rule in AGENTS.md.

## What round 2 adds

1. **Page numbers and running headers:** lose them freely, for balance and consistency (ResNet,
   Latent Diffusion, PDLT p2, NeRF). With round 1 (Attention p2), that's five papers.
2. **Up-down balance:** ResNet (all pages, 10–15 pt) and Latent Diffusion. That's the page
   number again: keeping it pulls the window down.
3. **Zoom:** tighter on NeRF (0.91 against 0.945); looser on GDL (0.85 against 0.79), where
   content was cut. It's fine on BERT, ResNet, Latent Diffusion and PDLT.
4. **Good:** all of BERT.
