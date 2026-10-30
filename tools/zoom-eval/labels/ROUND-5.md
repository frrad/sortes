# Labelling round 5

The owner reviewed six new papers on 2026-10-30, rendered with the offline
`tuned+text` model (`TextModel` at 9f51866: text-size zoom T 28 / n 16 / floor 0.1,
component weight 0.01, line weight 0.1; see `notes/text-size/README.md`). The labels
record each shown window's centre, so scoring uses the crops as judged. Neither this
model nor any weights were fitted on these papers.

## Held-out result

| paper | reviewed pages | agreeing | zoom: model / owner |
| --- | --- | --- | --- |
| Witten, AdS and Holography | 13 | 13 | 0.800 / 0.800 |
| Green–Tao, primes in AP | 20 | 20 | 0.820 / 0.820 |
| VAE | 2 | 2 | 0.810 / 0.810 |
| LIGO, GW150914 (PRL) | 6 | 0 | 0.870 / 0.850 |
| GAN | 9 | 9 | 0.810 / 0.810 |
| Scaling Laws | 13 | 13 | 0.810 / 0.810 |
| **Total** | **63** | **57** | **5 of 6 zooms** |

Strict agreement (no line clipped that the owner's crop shows whole) is the same,
57/63. No sliced lines and no extra windows.

**Caveat:** on these six papers unchanged TUNED picks the same zooms and windows as
`tuned+text` (identical scores). The round confirms the model behaves well on new
material, including two old-style math papers, but it does not discriminate between
the two. The cases that did (Perelman, YOLO, the footnote pages) are training labels.

## Feedback

- **Witten:** "this looks great, no notes"; 13 good pages.
- **Green–Tao:** "seems all good, no notes"; 20 good. p13: "not sure why red boxes at
  top. this is totally fine". The window cuts that page's running head (4.2% of its
  ink), which the furniture rule didn't recognise there; harmless.
- **VAE, GAN, Scaling Laws:** "lgtm"; all reviewed pages good.
- **LIGO:** "ok tbh yours was maybe better, but i don't hate this view either, slightly
  tighter zoom, exclude the header". The owner set 0.85 and moved all six windows down
  10–15 pt to drop the PRL running header ("PRL 116, … PHYSICAL REVIEW LETTERS … week
  ending"). The model keeps it at 0.87: the header isn't treated as furniture, so
  dropping it costs ink. A soft preference ("don't hate this view"), but a concrete
  gap: running heads that carry real text are not recognised as ignorable.

## Reproduce

```sh
build/install/zoom-eval/bin/zoom-eval zoom-experiment \
  --only=witten-9802150,greentao-0404188,1312.6114,1602.03837,1406.2661,2001.08361 \
  --elements=0.01 --lines=0.1 --T=28 --n=16 --floor=0.1 --out="$HOME/sortes-review/round5-heldout"
```

The app still uses READER.
