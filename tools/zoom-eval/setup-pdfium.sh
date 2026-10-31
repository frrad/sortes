#!/bin/sh
# Installs the pdfium zoom-eval renders with: pdfium-binaries chromium/5731 (Chrome 114), the
# release nearest the pdfium in Android 15's PdfRenderer (AOSP external/pdfium is "Chrome
# 114.0.5735.130"). Pinned by tag and checksum; see notes/renderer-fidelity.md before moving it.
# Usage: tools/zoom-eval/setup-pdfium.sh [DIR]   (default ~/.cache/sortes-zoom-eval/pdfium-chromium-5731)
set -eu
tag=chromium/5731
dir=${1:-$HOME/.cache/sortes-zoom-eval/pdfium-chromium-5731}
case "$(uname -s)-$(uname -m)" in
    Linux-x86_64)
        asset=pdfium-linux-x64.tgz
        sha256=b722d69a301bf37a068568185d98c24f86eff374d06418f543a371766044c584 ;;
    *)
        echo "setup-pdfium.sh: no pinned pdfium for $(uname -s)-$(uname -m); only Linux x86_64 is set up" >&2
        exit 1 ;;
esac
if [ -s "$dir/lib/libpdfium.so" ] && grep -qx 'BUILD=5731' "$dir/VERSION" 2>/dev/null; then
    echo "pdfium $tag already in $dir"
    exit 0
fi
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
curl -sfL -o "$tmp/$asset" "https://github.com/bblanchon/pdfium-binaries/releases/download/$(echo "$tag" | sed 's|/|%2F|')/$asset"
echo "$sha256  $tmp/$asset" | sha256sum -c --quiet - || { echo "setup-pdfium.sh: checksum mismatch for $asset" >&2; exit 1; }
mkdir -p "$dir"
tar xzf "$tmp/$asset" -C "$dir"
echo "pdfium $tag installed in $dir"
