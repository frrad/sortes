#!/usr/bin/env python3
"""Renders PDF pages with pdfium (pypdfium2's raw bindings) the way Android 15's PdfRenderer does
(MediaProvider pdfClient Page::Render): onto a white BGRA bitmap, with FPDF_REVERSE_BYTE_ORDER |
FPDF_LCD_TEXT | FPDF_ANNOT and a scale matrix from the page's *integer* size (FPDF_GetPageWidth
truncated, as Page::Width() does). Investigation tool for notes/renderer-fidelity.md.

A server on stdin/stdout:  "size PATH INDEX"  -> "W H\n" (integer points)
                           "render PATH INDEX W H" -> "OK\n" + W*H luminance bytes
--float-size: scale by the page's fractional size instead (as mutool does), for comparison.
Luminance is (r*299 + g*587 + b*114) / 1000, as InkMask computes it.
"""
import ctypes
import sys

import numpy as np
import pypdfium2.raw as pdfium

pdfium.FPDF_InitLibrary()
docs = {}


def doc(path):
    if path not in docs:
        d = pdfium.FPDF_LoadDocument(path.encode(), None)
        if not d:
            raise IOError(path)
        docs[path] = d
    return docs[path]


def size(path, index):
    page = pdfium.FPDF_LoadPage(doc(path), index)
    w, h = int(pdfium.FPDF_GetPageWidth(page)), int(pdfium.FPDF_GetPageHeight(page))
    pdfium.FPDF_ClosePage(page)
    return w, h


def render(path, index, w, h, flags):
    page = pdfium.FPDF_LoadPage(doc(path), index)
    pw, ph = pdfium.FPDF_GetPageWidth(page), pdfium.FPDF_GetPageHeight(page)
    if not FLOAT_SIZE:
        pw, ph = int(pw), int(ph)
    bitmap = pdfium.FPDFBitmap_Create(w, h, 1)
    pdfium.FPDFBitmap_FillRect(bitmap, 0, 0, w, h, 0xFFFFFFFF)
    matrix = pdfium.FS_MATRIX(w / pw, 0, 0, h / ph, 0, 0)
    clip = pdfium.FS_RECTF(0, 0, w, h)
    pdfium.FPDF_RenderPageBitmapWithMatrix(bitmap, page, ctypes.byref(matrix), ctypes.byref(clip), flags)
    stride = pdfium.FPDFBitmap_GetStride(bitmap)
    buf = ctypes.cast(pdfium.FPDFBitmap_GetBuffer(bitmap), ctypes.POINTER(ctypes.c_ubyte * (stride * h))).contents
    data = bytes(buf)
    pdfium.FPDFBitmap_Destroy(bitmap)
    pdfium.FPDF_ClosePage(page)
    a = np.frombuffer(data, np.uint8).reshape(h, stride)[:, :w * 4].reshape(h, w, 4).astype(np.int32)
    # REVERSE_BYTE_ORDER: RGBA.
    lum = (a[:, :, 0] * 299 + a[:, :, 1] * 587 + a[:, :, 2] * 114) // 1000
    return lum.astype(np.uint8).tobytes()


FLAGS = pdfium.FPDF_REVERSE_BYTE_ORDER | pdfium.FPDF_LCD_TEXT | pdfium.FPDF_ANNOT
if "--no-lcd" in sys.argv:
    FLAGS = pdfium.FPDF_REVERSE_BYTE_ORDER | pdfium.FPDF_ANNOT

FLOAT_SIZE = "--float-size" in sys.argv
out = sys.stdout.buffer
for line in sys.stdin:
    f = line.split()
    if f[0] == "size":
        out.write(("%d %d\n" % size(f[1], int(f[2]))).encode())
    elif f[0] == "render":
        data = render(f[1], int(f[2]), int(f[3]), int(f[4]), FLAGS)
        out.write(b"OK\n")
        out.write(data)
    out.flush()
