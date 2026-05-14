# Sortes

An Android app for reading academic papers.

Kotlin + Jetpack Compose. `minSdk` 26, `targetSdk` 35.

## Development

This project is set up to be developed primarily in Claude Code on the web.

- **CI** — `.github/workflows/ci.yml` runs lint, unit tests, and a debug APK
  build on every push and pull request. The APK and reports are uploaded as
  workflow artifacts.
- **Branch APKs** — `.github/workflows/release.yml` builds a debug APK for
  every branch push and publishes it as a rolling GitHub *prerelease* tagged
  `build-<branch>`. Open that release on your phone and tap the `.apk` to
  install (enable "install unknown apps" for your browser first). Each push to
  the branch replaces the release, so the latest build is always at the same
  place.
- **Web sessions** — `.claude/hooks/session-start.sh` installs the Android SDK
  at the start of each Claude Code on the web session so builds, lint, and
  tests work without any local setup.

### Local builds

```sh
./gradlew assembleDebug        # build debug APK -> app/build/outputs/apk/debug/
./gradlew lintDebug            # Android lint
./gradlew testDebugUnitTest    # JVM unit tests
```

Requires JDK 17+ and an Android SDK (`ANDROID_HOME`) with platform 35 and
build-tools 35.0.0.
