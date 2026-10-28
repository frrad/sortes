#!/bin/bash
# Build the debug APK, install it on a device over network ADB, and launch it.
#
# Usage:
#   scripts/deploy.sh                # use the already-connected device
#   scripts/deploy.sh 192.168.1.50:37123   # adb connect to this address first
#   ADB_TARGET=192.168.1.50:37123 scripts/deploy.sh
#
# One-time setup (Android 11+): on the phone enable Developer options ->
# Wireless debugging -> "Pair device with pairing code", then run
#   adb pair <ip>:<pairing-port> <code>
# The connect port shown on the Wireless debugging screen is different from the
# pairing port and changes whenever wireless debugging is toggled.
set -euo pipefail

cd "$(dirname "$0")/.."

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
APP_ID="dev.sortes.app"
TARGET="${1:-${ADB_TARGET:-}}"

if [ -n "$TARGET" ]; then
  "$ADB" connect "$TARGET"
  export ANDROID_SERIAL="$TARGET"
fi

if [ -z "${ANDROID_SERIAL:-}" ]; then
  mapfile -t devices < <("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
  case "${#devices[@]}" in
    0) echo "No device connected. Pass <ip>:<port> or run 'adb connect' first." >&2; exit 1 ;;
    1) export ANDROID_SERIAL="${devices[0]}" ;;
    *) echo "Multiple devices: ${devices[*]}. Pass one as an argument." >&2; exit 1 ;;
  esac
fi

echo "Deploying to $ANDROID_SERIAL"
ANDROID_HOME="$ANDROID_HOME" ./gradlew installDebug
# Boox firmware disables freshly installed apps; re-enabling is a no-op elsewhere.
"$ADB" shell pm enable "$APP_ID" >/dev/null
"$ADB" shell am start -n "$APP_ID/.MainActivity" >/dev/null
echo "Launched $APP_ID"
