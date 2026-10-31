package dev.sortes.zoomeval.fit

import dev.sortes.app.zoom.CropConfig
import dev.sortes.zoomeval.CONFIG_WEIGHT_NAMES
import dev.sortes.zoomeval.weightValues
import dev.sortes.zoomeval.readCorpus
import dev.sortes.zoomeval.with
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.random.Random

/**
 * `fit [--trials=200] [--seed=1] [--labels=labels] [--out=~/sortes-review/fit] [--only=id,id]
 *      [--search=random|coordinate] [--coordinates=beta,comfortable+power,...] [--sweeps=2]
 *      [--minGain=0.5] [--holdout=id,id] [--<weight>=value ...]`: scores [CropConfig.DEFAULT]
 * (the reader's crops) against the labels, then searches for weights with a lower score, with
 * leave-one-paper-out results.
 *
 * - `--search=random` (the default) scores `--trials` random crop-model weight sets ([sampleWeights]).
 * - `--search=coordinate` is a local search around the defaults ([coordinateDescent]) over both
 *   models' weights: `--coordinates` (by default each of [SEARCH_WEIGHTS] on its own; `a+b` searches
 *   two jointly), at most `--sweeps` sweeps, keeping a move only if it gains more than `--minGain`.
 * - `--holdout=` papers are scored but not used to choose the weights.
 * - `--<weight>=` (either model's, [CONFIG_WEIGHT_NAMES]) is scored as "given" and fixed in the search.
 */
fun fitMain(args: List<String>) {
    val opts = args.associate { arg ->
        require(arg.startsWith("--") && '=' in arg) { "Bad argument: $arg" }
        arg.removePrefix("--").substringBefore('=') to arg.substringAfter('=')
    }
    val known = CONFIG_WEIGHT_NAMES + setOf("trials", "seed", "labels", "out", "only", "corpus", "pdfs", "aspect", "split",
        "search", "coordinates", "sweeps", "minGain", "holdout")
    opts.keys.firstOrNull { it !in known }?.let { throw IllegalArgumentException("Unknown option --$it") }
    val home = System.getProperty("user.home")
    val corpus = readCorpus(File(opts["corpus"] ?: "corpus.tsv")).associateBy { it.id }
    val labelsDir = File(opts["labels"] ?: "labels")
    val out = File(opts["out"] ?: "$home/sortes-review/fit").apply { mkdirs() }
    val aspect = (opts["aspect"] ?: "0.75").toFloat()
    val trials = (opts["trials"] ?: "200").toInt()
    val random = Random((opts["seed"] ?: "1").toLong())
    val fixed = opts.filterKeys { it in CONFIG_WEIGHT_NAMES }
    val search = opts["search"] ?: "random"
    require(search in setOf("random", "coordinate")) { "Unknown --search=$search" }
    val coordinates = (opts["coordinates"]?.split(',') ?: SEARCH_WEIGHTS).map { Coordinate.parse(it) }
        .filter { c -> c.names.none { it in fixed } }
    val holdout = opts["holdout"]?.split(',')?.toSet().orEmpty()
    val base = CropConfig.DEFAULT

    val started = System.currentTimeMillis()
    val papers = loadLabelled(corpus, labelsDir, File(opts["pdfs"] ?: "$home/.cache/sortes-zoom-eval/pdf"), aspect,
        opts["only"]?.split(',')?.toSet())
    println("Loaded ${papers.size} labelled papers (${System.currentTimeMillis() - started} ms): " +
        papers.joinToString { "${it.paper.id} ${it.targets.pages.size} targets" })

    holdout.firstOrNull { id -> papers.none { it.paper.id == id } }?.let { throw IllegalArgumentException("--holdout: $it isn't a labelled paper") }
    val train = papers.map { it.paper.id }.filter { it !in holdout }
    val report = FitReport(papers, out, opts["split"]?.split(',')?.toSet().orEmpty(), holdout)
    runBlocking {
        val baseline = trial(papers, base)
        // The weights fixed on the command line, if any.
        val given = if (fixed.isEmpty()) null else trial(papers, base.copy(name = "${base.name}+weights").with(fixed))
        val tried = mutableListOf(baseline)
        given?.let { tried += it }
        when (search) {
            "random" -> for (n in 1..trials) {
                tried += trial(papers, base.copy(name = "${base.name}+weights", weights = sampleWeights(random)).with(fixed))
                if (n % 25 == 0) println("$n trials, best ${"%.2f".format(tried.minOf { it.unitsOn(train) })} (baseline ${"%.2f".format(baseline.unitsOn(train))})")
            }
            "coordinate" -> {
                // Configs are scored once, however often the search comes back to them.
                val scored = tried.associateByTo(HashMap()) { it.config.weightValues() }
                val descent = coordinateDescent(
                    given ?: baseline, coordinates, train,
                    sweeps = (opts["sweeps"] ?: "2").toInt(),
                    minGain = (opts["minGain"] ?: "0.5").toDouble(),
                    evaluate = { config ->
                        scored.getOrPut(config.weightValues()) {
                            trial(papers, config.copy(name = "${base.name}+weights")).also { tried += it }
                        }
                    },
                ) { step ->
                    println("sweep ${step.sweep} ${step.coordinate}: " + step.candidates.joinToString { t ->
                        step.coordinate.names.joinToString("/") { "%.4g".format(t.config.weightValues().getValue(it)) } + " ${"%.2f".format(t.unitsOn(train))}"
                    } + " (now ${"%.2f".format(step.incumbent.unitsOn(train))})" + (if (step.moved) " → ${step.kept.diff()}" else "") +
                        " [${(System.currentTimeMillis() - started) / 1000} s]")
                    report.write(baseline, given, tried)
                }
                report.write(baseline, given, tried, descent)
                return@runBlocking
            }
        }
        report.write(baseline, given, tried)
    }
    println("Wrote ${File(out, "report.md")} (${(System.currentTimeMillis() - started) / 1000} s)")
}
