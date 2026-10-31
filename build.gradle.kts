plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.spotless)
}

// Code formatting, gofmt-style: ktfmt's kotlinlang style, no knobs. `./gradlew spotlessApply`
// reformats; `./gradlew spotlessCheck` (run in CI) fails on unformatted code.
spotless {
    val ktfmtVersion = libs.versions.ktfmt.get()
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/.gradle/**")
        ktfmt(ktfmtVersion).kotlinlangStyle()
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", "**/.gradle/**")
        ktfmt(ktfmtVersion).kotlinlangStyle()
    }
}
