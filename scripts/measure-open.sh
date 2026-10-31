#!/bin/bash
# Times opening papers in the reader on a connected device: from the tap on a paper in the list
# to its first page settled on screen at its crop, with the frames the reader drew on the way.
# Reads the reader's own timeline (OpenTimeline, logcat tag SortesOpen).
#
# Usage:
#   scripts/measure-open.sh [-n RUNS] [--cold] "Paper title" ["Another title" ...]
#
# Each run starts from the paper list, taps the paper (found by its title with uiautomator),
# waits for the timeline's summary line and goes back (system Back). With --cold the app is force-stopped
# before each run, so the open includes a new process (but not the app's launch: the tap is
# timed from the list). Run each paper once first if its analysis may not be cached yet.
#
# Set ANDROID_SERIAL to choose the device.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
APP_ID="dev.sortes.app"
RUNS=3
COLD=0
TITLES=()
while [ $# -gt 0 ]; do
  case "$1" in
    -n) RUNS="$2"; shift 2 ;;
    --cold) COLD=1; shift ;;
    *) TITLES+=("$1"); shift ;;
  esac
done
[ ${#TITLES[@]} -gt 0 ] || { echo "Usage: $0 [-n RUNS] [--cold] \"Paper title\" ..." >&2; exit 1; }

start_list() {
  if [ "$COLD" = 1 ]; then "$ADB" shell am force-stop "$APP_ID"; fi
  "$ADB" shell am start -n "$APP_ID/.MainActivity" >/dev/null
  sleep 2
}

# Prints the centre "x y" of the node whose text is exactly $1.
find_title() {
  "$ADB" shell uiautomator dump /sdcard/sortes-ui.xml >/dev/null
  "$ADB" shell cat /sdcard/sortes-ui.xml | TITLE="$1" python3 -c '
import os, re, sys, html
title = os.environ["TITLE"]
for m in re.finditer(r"<node [^>]*>", sys.stdin.read()):
    node = m.group(0)
    t = re.search(r"text=\"([^\"]*)\"", node)
    if t and html.unescape(t.group(1)) == title:
        x1, y1, x2, y2 = map(int, re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
'
}

for title in "${TITLES[@]}"; do
  for run in $(seq 1 "$RUNS"); do
    start_list
    pos=$(find_title "$title")
    # Further down the list: scroll until it shows.
    for _ in 1 2 3 4 5 6; do
      [ -z "$pos" ] || break
      "$ADB" shell input swipe 930 2000 930 800 300
      sleep 2
      pos=$(find_title "$title")
    done
    # Or back up (the list keeps its scroll between runs).
    for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
      [ -z "$pos" ] || break
      "$ADB" shell input swipe 930 800 930 2000 300
      sleep 2
      pos=$(find_title "$title")
    done
    [ -n "$pos" ] || { echo "Paper not on screen: $title" >&2; exit 1; }
    "$ADB" logcat -c
    # shellcheck disable=SC2086
    "$ADB" shell input tap $pos
    line=""
    for _ in $(seq 1 120); do
      line=$("$ADB" logcat -d -s SortesOpen:I | grep "Opened paper" | tail -1 || true)
      [ -n "$line" ] && break
      sleep 0.5
    done
    echo "$title #$run: ${line#*Opened paper }"
    # A stored first page shown is then rendered again to check it: report what that found.
    if [[ "$line" == *"showing stored page"* ]]; then
      check=""
      for _ in $(seq 1 20); do
        check=$("$ADB" logcat -d -s Sortes:I | grep -o "Stored page .*" | tail -1 || true)
        [ -n "$check" ] && break
        sleep 0.5
      done
      echo "  ${check:-stored page not checked within 10 s}"
    fi
    # Back to the list (the system Back: a tap on the reader's arrow sometimes landed on the page).
    "$ADB" shell input keyevent KEYCODE_BACK
    sleep 2
  done
done
