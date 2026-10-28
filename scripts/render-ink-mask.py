#!/usr/bin/env python3
"""Renders one PDF page as an ink-mask test fixture for app/src/test/resources/zoom.

Usage: scripts/render-ink-mask.py paper.pdf PAGE > fixture.txt   (PAGE counts from 1)

Mirrors the app's analysis: the page is rendered so that, padded to a 3:4 screen, its longer
side is 256 pixels (ANALYSIS_LONG_SIDE), and pixels with luminance below 224
(InkMask.DEFAULT_THRESHOLD) are ink. One text row per pixel row: '#' ink, '.' paper. Needs
mutool (MuPDF), so antialiasing differs slightly from Android's PdfRenderer.
"""
import os
import re
import subprocess
import sys
import tempfile

ASPECT = 0.75
LONG_SIDE = 256
THRESHOLD = 224


def main():
    pdf, page = sys.argv[1], sys.argv[2]
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
    for y in range(gh):
        print("".join("#" if pixels[y * gw + x] < THRESHOLD else "." for x in range(gw)))


if __name__ == "__main__":
    main()
