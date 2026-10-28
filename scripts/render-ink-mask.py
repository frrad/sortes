#!/usr/bin/env python3
"""Renders one PDF page as an ink-mask test fixture for app/src/test/resources/zoom.

Usage: scripts/render-ink-mask.py [--gray] paper.pdf PAGE > fixture.txt   (PAGE counts from 1)

Mirrors the app's analysis: the page is rendered so that, padded to a 3:4 screen, its longer
side is 256 pixels (ANALYSIS_LONG_SIDE), and pixels more than PAPER_MARGIN darker than the
page's paper colour (the most common luminance of at least PAPER_MIN) are ink, as in
InkMask.fromArgb. One text row per pixel row: '#' ink, '.' paper. With --gray, each pixel is
instead its luminance as two hex digits, for tests of the thresholding itself. Needs mutool
(MuPDF), so antialiasing differs slightly from Android's PdfRenderer.
"""
import os
import re
import subprocess
import sys
import tempfile

ASPECT = 0.75
LONG_SIDE = 256
PAPER_MARGIN = 6
PAPER_MIN = 192


def main():
    args = sys.argv[1:]
    gray = "--gray" in args
    pdf, page = [a for a in args if a != "--gray"]
    info = subprocess.run(["mutool", "info", "-M", pdf, page], capture_output=True, text=True, check=True).stdout
    x0, y0, x1, y1 = map(float, re.search(r"\[\s*([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)\s*\]", info).groups())
    w, h = x1 - x0, y1 - y0
    padded = (h * ASPECT, h) if w / h < ASPECT else (w, w / ASPECT)
    density = LONG_SIDE / max(padded)
    width, height = max(1, round(w * density)), max(1, round(h * density))
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "page.pgm")
        subprocess.run(["mutool", "draw", "-q", "-c", "gray", "-w", str(width), "-h", str(height),
                        "-o", out, pdf, page], check=True, stderr=subprocess.DEVNULL)
        data = open(out, "rb").read()
    # Binary PGM: "P5\n<w> <h>\n255\n" then one byte per pixel.
    magic, dims, maxval, pixels = data.split(b"\n", 3)
    gw, gh = map(int, dims.split())
    if gray:
        for y in range(gh):
            print("".join("%02x" % pixels[y * gw + x] for x in range(gw)))
        return
    counts = [0] * 256
    for p in pixels[:gw * gh]:
        if p >= PAPER_MIN:
            counts[p] += 1
    paper = max(range(PAPER_MIN, 256), key=lambda l: (counts[l], l == 255))
    threshold = paper - PAPER_MARGIN
    for y in range(gh):
        print("".join("#" if pixels[y * gw + x] < threshold else "." for x in range(gw)))


if __name__ == "__main__":
    main()
