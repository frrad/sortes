pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// zoom-eval compiles and tests with a JDK 17 toolchain (jvmToolchain(17)); this lets Gradle
// download one into ~/.gradle/jdks when the machine has only another JDK (Gradle itself runs on
// any JDK 17-24).
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Sortes"
include(":app")

// Offline evaluation of the zoom analysis; not part of the app. See tools/zoom-eval/README.md.
include(":zoom-eval")
project(":zoom-eval").projectDir = file("tools/zoom-eval")
