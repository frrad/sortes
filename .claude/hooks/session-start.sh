#!/bin/bash
# Installs the Android SDK so Claude Code on the web can build, lint and test
# the app. Idempotent: re-running is cheap once the SDK is in place.
set -euo pipefail

# Run in the background so the session starts without waiting for the SDK
# install. Note: a build/lint/test command issued before this finishes may
# fail because the SDK isn't ready yet.
echo '{"async": true, "asyncTimeout": 600000}'

# Only needed in remote (Claude Code on the web) environments. Locally the
# developer is expected to have their own Android SDK / Android Studio.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_TOOLS_VERSION="11076708"
PLATFORM="platforms;android-35"
BUILD_TOOLS="build-tools;35.0.0"

echo "[session-start] Ensuring Android SDK at $ANDROID_HOME"

mkdir -p "$ANDROID_HOME"

# 1. Command-line tools (sdkmanager).
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "[session-start] Installing Android command-line tools"
  tmp_zip="$(mktemp /tmp/cmdline-tools.XXXXXX.zip)"
  curl -fsSL \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
    -o "$tmp_zip"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest" "$ANDROID_HOME/cmdline-tools/tmp"
  mkdir -p "$ANDROID_HOME/cmdline-tools/tmp"
  unzip -q "$tmp_zip" -d "$ANDROID_HOME/cmdline-tools/tmp"
  mkdir -p "$ANDROID_HOME/cmdline-tools/latest"
  mv "$ANDROID_HOME/cmdline-tools/tmp/cmdline-tools/"* "$ANDROID_HOME/cmdline-tools/latest/"
  rm -rf "$ANDROID_HOME/cmdline-tools/tmp" "$tmp_zip"
fi

export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# 2. SDK packages required to build the app.
if [ ! -d "$ANDROID_HOME/$(echo "$BUILD_TOOLS" | tr ';' '/')" ]; then
  echo "[session-start] Installing SDK packages (platform-tools, $PLATFORM, $BUILD_TOOLS)"
  yes 2>/dev/null | sdkmanager --licenses >/dev/null || true
  sdkmanager "platform-tools" "$PLATFORM" "$BUILD_TOOLS"
fi
yes 2>/dev/null | sdkmanager --licenses >/dev/null || true

# 3. Persist environment for the rest of the session.
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export ANDROID_HOME=\"$ANDROID_HOME\""
    echo "export ANDROID_SDK_ROOT=\"$ANDROID_HOME\""
    echo "export PATH=\"$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:\$PATH\""
  } >> "$CLAUDE_ENV_FILE"
fi

echo "[session-start] Android SDK ready"
