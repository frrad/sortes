package dev.sortes.zoomeval.fit

import dev.sortes.app.zoom.CropConfig
import dev.sortes.app.zoom.CropModelWeights
import dev.sortes.zoomeval.WEIGHT_NAMES
import dev.sortes.zoomeval.readCorpus
import dev.sortes.zoomeval.with
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.random.Random

/**
 * `fit [--trials=200] [--seed=1] [--labels=labels] [--out=~/sortes-review/fit] [--only=id,id]
 *      [--<weight>=value ...]`: scores the model's default weights against the labels, then
 * searches random weights for a lower score, with leave-one-paper-out results. The "given" set is
 * [CropConfig.TUNED], or, with `--<weight>=`, those weights (fixed during the search too).
 */
fun fitMain(args: List<String>) {
    val opts = args.associate { arg ->
        require(arg.startsWith("--") && '=' in arg) { "Bad argument: $arg" }
        arg.removePrefix("--").substringBefore('=') to arg.substringAfter('=')
    }
    val known = WEIGHT_NAMES + setOf("trials", "seed", "labels", "out", "only", "corpus", "pdfs", "aspect", "variants", "split")
    opts.keys.firstOrNull { it !in known }?.let { throw IllegalArgumentException("Unknown option --$it") }
    val home = System.getProperty("user.home")
    val corpus = readCorpus(File(opts["corpus"] ?: "corpus.tsv")).associateBy { it.id }
    val labelsDir = File(opts["labels"] ?: "labels")
    val out = File(opts["out"] ?: "$home/sortes-review/fit").apply { mkdirs() }
    val aspect = (opts["aspect"] ?: "0.75").toFloat()
    val trials = (opts["trials"] ?: "200").toInt()
    val random = Random((opts["seed"] ?: "1").toLong())
    val fixed = opts.filterKeys { it in WEIGHT_NAMES }
    // The variants the search tries, alternating; the baseline is always today's reader.
    val variants = (opts["variants"] ?: "reader,furniture,furniture+marks").split(',').map { Variant.parse(it) }

    val started = System.currentTimeMillis()
    val papers = loadLabelled(corpus, labelsDir, File(opts["pdfs"] ?: "$home/.cache/sortes-zoom-eval/pdf"), aspect,
        opts["only"]?.split(',')?.toSet())
    println("Loaded ${papers.size} labelled papers (${System.currentTimeMillis() - started} ms): " +
        papers.joinToString { "${it.paper.id} ${it.targets.pages.size} targets" })

    val report = FitReport(papers, out, opts["split"]?.split(',')?.toSet().orEmpty())
    runBlocking {
        val baseline = trial(papers, CropModelWeights())
        // The shared tuned config (CropConfig.TUNED), or the weights fixed on the command line.
        val given = if (fixed.isEmpty()) trial(papers, CropConfig.TUNED.weights, Variant.of(CropConfig.TUNED))
        else trial(papers, CropModelWeights().with(fixed), variants.last())
        val tried = mutableListOf(baseline)
        variants.filter { it != Variant() }.forEach { tried += trial(papers, CropModelWeights(), it) }
        given?.let { tried += it }
        for (n in 1..trials) {
            tried += trial(papers, sampleWeights(random).with(fixed), variants[n % variants.size])
            if (n % 25 == 0) println("$n trials, best ${"%.2f".format(tried.minOf { it.units })} (baseline ${"%.2f".format(baseline.units)})")
        }
        report.write(baseline, given, tried)
    }
    println("Wrote ${File(out, "report.md")} (${(System.currentTimeMillis() - started) / 1000} s)")
}
