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

// PdfiumTest's render tests skip when the pinned pdfium isn't installed, and fail instead under
// SORTES_REQUIRE_PDFIUM (CI). Both decide what the tests do, so both are inputs: otherwise the
// build cache can replay a run that skipped them into one that should have run them.
val pdfiumDir = providers.environmentVariable("SORTES_PDFIUM")
    .orElse(providers.systemProperty("user.home").map { "$it/.cache/sortes-zoom-eval/pdfium-chromium-5731" })
    .map(::File)

tasks.test {
    dependsOn(labellerJsTest)
    inputs.property("requirePdfium", providers.environmentVariable("SORTES_REQUIRE_PDFIUM").map { true }.orElse(false))
    // A file collection, unlike inputs.file, may name files that don't exist: a missing install
    // is fingerprinted as missing.
    inputs.files(pdfiumDir.map { listOf(File(it, "VERSION"), File(it, "lib/libpdfium.so")) })
        .withPropertyName("pdfiumInstall")
        .withPathSensitivity(PathSensitivity.NONE)
}
