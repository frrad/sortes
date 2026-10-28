#!/bin/sh
# Downloads the evaluation corpus (corpus.tsv) into the cache dir; PDFs are never committed.
# Usage: tools/zoom-eval/fetch-corpus.sh [DIR]   (default ~/.cache/sortes-zoom-eval/pdf)
set -eu
dir=${1:-$HOME/.cache/sortes-zoom-eval/pdf}
mkdir -p "$dir"
cd "$(dirname "$0")"
grep -v '^#' corpus.tsv | while IFS="$(printf '\t')" read -r id file _; do
    [ -s "$dir/$file" ] && continue
    case $id in
        *-p*-*)
            # An excerpt of a long book: id is <arxiv id>-p<first>-<last>.
            arxiv=${id%%-p*}; range=${id#*-p}
            curl -sfL -o "$dir/$arxiv.full.pdf" "https://arxiv.org/pdf/$arxiv"
            mutool merge -o "$dir/$file" "$dir/$arxiv.full.pdf" "$range"
            rm "$dir/$arxiv.full.pdf" ;;
        *) curl -sfL -o "$dir/$file" "https://arxiv.org/pdf/$id" ;;
    esac
    echo "fetched $id"
    sleep 3 # be gentle with arXiv
done
