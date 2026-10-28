plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// The harness compiles the app's real zoom code rather than a copy of it. PaperAnalysis is left
// out because it reaches into the Android PDF renderer; the harness renders with mutool instead.
sourceSets {
    main {
        kotlin.srcDir("../../app/src/main/java")
        kotlin.include("dev/sortes/app/zoom/**", "dev/sortes/zoomeval/**")
        kotlin.exclude("dev/sortes/app/zoom/PaperAnalysis.kt")
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("dev.sortes.zoomeval.MainKt")
    // The host is short of memory: papers are processed one at a time in a small heap.
    applicationDefaultJvmArgs = listOf("-Xmx768m", "-Djava.awt.headless=true")
}
