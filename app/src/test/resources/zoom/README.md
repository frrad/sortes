# Ink-mask fixtures

Real pages as the zoom analysis sees them, rendered with `scripts/render-ink-mask.py`
(`#` ink, `.` paper, one line per pixel row).

| Fixture | Source |
| --- | --- |
| `attention-p1.txt`, `attention-page2.txt` | arXiv 1706.03762v7 ("Attention Is All You Need"), pages 1 and 2 |
| `bert-p1.txt` | arXiv 1810.04805v2 (BERT), page 1 |
| `resnet-p1.txt` | arXiv 1512.03385v1 (ResNet), page 1 |
| `line-numbers*.txt`, `margin-notes.txt`, `rotated-label.txt` | page 1 of the matching `sources/*.tex`, built with `pdflatex` |

The arXiv first pages carry the vertical arXiv stamp in the left margin; the others are margin
content that must be kept:

- `line-numbers.txt`: `lineno` defaults, 1in margins.
- `line-numbers-narrow-margin.txt`: `lineno` defaults, 0.75in margins.
- `line-numbers-footnotesize.txt`, `line-numbers-small.txt`: larger numbers, 0.75in margins.
  The `\small` ones ink 53% of their rows, so only the unbroken-run test keeps them.
- `margin-notes.txt`: `\marginpar` notes in a wide left margin.
- `rotated-label.txt`: a tall rotated label in the left margin, clear of the text but 9% of
  the width in. The same label right at the page edge would be taken for a stamp.
