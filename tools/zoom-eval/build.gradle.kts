plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// The harness compiles the app's real zoom code rather than a copy of it: the whole crop
// pipeline (analyzePaper) is pure Kotlin. Only the pixels differ: the harness renders with a
// pinned pdfium (PdfiumPdf, through JNA; setup-pdfium.sh), the reader with Android's PdfRenderer.
sourceSets {
    main {
        kotlin.srcDir("../../app/src/main/java")
        kotlin.include("dev/sortes/app/zoom/**", "dev/sortes/zoomeval/**")
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.org.json)
    implementation(libs.jna)
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

// The labeller's JavaScript geometry, unit-tested with Node's built-in runner.
val labellerJsTest by tasks.registering(Exec::class) {
    description = "Runs the labeller's JavaScript unit tests (node --test)."
    workingDir = projectDir
    commandLine("node", "--test", "labeller/geometry.test.js", "labeller/labels.test.js")
    inputs.files(fileTree("labeller") { include("*.js") })
}

tasks.test {
    dependsOn(labellerJsTest)
}
