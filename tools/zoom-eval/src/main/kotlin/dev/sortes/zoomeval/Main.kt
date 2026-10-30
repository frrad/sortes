package dev.sortes.zoomeval

import dev.sortes.app.zoom.CropModelWeights
import dev.sortes.zoomeval.fit.fitMain
import dev.sortes.zoomeval.labeller.labellerExport
import dev.sortes.zoomeval.labeller.labellerServe
import java.io.File
import java.util.Properties

/** A paper in the evaluation corpus. */
data class Paper(val id: String, val file: String, val category: String, val title: String)

/** Reads corpus.tsv: id, file name, category, title; '#' starts a comment. */
fun readCorpus(file: File): List<Paper> = file.readLines()
    .filter { it.isNotBlank() && !it.startsWith("#") }
    .map { line ->
        val f = line.split('\t')
        require(f.size >= 4) { "Bad corpus line: $line" }
        Paper(f[0].trim(), f[1].trim(), f[2].trim(), f[3].trim())
    }

/** [base] weights (the reader's by default), then a properties file's, then `--name=value` overrides. */
fun readWeights(file: File?, overrides: Map<String, String>, base: CropModelWeights = CropModelWeights()): CropModelWeights {
    val props = Properties()
    file?.takeIf { it.exists() }?.reader()?.use { props.load(it) }
    val fromFile = props.stringPropertyNames().associateWith { props.getProperty(it).trim() }
    return base.with(fromFile).with(overrides)
}

private val USAGE = """
    Usage: zoom-eval review ... | labeller-export ... | labeller-serve ... (see labeller/README.md)
           | fit ... | zoom-experiment ... | text-size ... | bench ... | crops ...
    See tools/zoom-eval/README.md. Weights: ${WEIGHT_NAMES.joinToString(", ")}
""".trimIndent()

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "labeller-export" -> labellerExport(args.drop(1))
        "labeller-serve" -> labellerServe(args.drop(1))
        "fit" -> fitMain(args.drop(1))
        "review" -> reviewMain(args.drop(1))
        "bench" -> benchMain(args.drop(1))
        "crops" -> cropsMain(args.drop(1))
        "text-size" -> dev.sortes.zoomeval.content.textSizeMain(args.drop(1))
        "zoom-experiment" -> dev.sortes.zoomeval.content.zoomExperimentMain(args.drop(1))
        else -> {
            System.err.println(USAGE)
            kotlin.system.exitProcess(2)
        }
    }
}
