#!/bin/bash
# Starts a labelling session: renders the labelling set if needed, then serves the labeller on
# http://127.0.0.1:8766/ until stopped (Ctrl-C). See tools/zoom-eval/labeller/README.md.
#
# Usage: tools/zoom-eval/label.sh [--render]
#   --render   re-render every paper in labeller/papers.txt with the current code and weights
#              (otherwise only papers not rendered yet are rendered)
# LABELLER_ARGS adds export options, e.g. LABELLER_ARGS="--config=tuned" to show the tuned
# config's crops (CropConfig.TUNED) instead of the reader's.
set -euo pipefail

cd "$(dirname "$0")/../.."
REPO=$PWD
EVAL=$REPO/tools/zoom-eval
DATA=${LABELLER_DATA:-$HOME/sortes-review/labeller/data}
PORT=${LABELLER_PORT:-8766}
LOCK=/tmp/sortes-gradle.lock
export ANDROID_HOME=${ANDROID_HOME:-$HOME/android-sdk}

papers=$(grep -v '^#' "$EVAL/labeller/papers.txt" | grep -v '^[[:space:]]*$')
if [ "${1:-}" = "--render" ]; then
    todo=$papers
else
    todo=$(for p in $papers; do [ -f "$DATA/$p/paper.json" ] || echo "$p"; done)
fi

if [ -n "$todo" ]; then
    "$EVAL/fetch-corpus.sh"
    echo "Rendering: $(echo $todo)"
    flock "$LOCK" ./gradlew -q :zoom-eval:run \
        --args="labeller-export --out=$DATA --papers=$(echo $todo | tr ' ' ',') ${LABELLER_ARGS:-}"
fi

# Run the server outside Gradle, from its own copy, so it holds neither the build lock nor files
# a later build replaces.
flock "$LOCK" ./gradlew -q :zoom-eval:installDist
rm -rf /tmp/sortes-labeller
cp -r "$EVAL/build/install/zoom-eval" /tmp/sortes-labeller

echo
echo "Labeller: http://127.0.0.1:$PORT/"
echo "To open it from another machine, from this thread run:  bb connect expose $PORT"
echo
JAVA_OPTS=-Xmx128m exec /tmp/sortes-labeller/bin/zoom-eval labeller-serve \
    --web="$EVAL/labeller" --labels="$EVAL/labels" --data="$DATA" --port="$PORT"
