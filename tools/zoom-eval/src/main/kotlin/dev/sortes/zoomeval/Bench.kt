package dev.sortes.zoomeval

import dev.sortes.app.zoom.CropModel
import dev.sortes.app.zoom.analyzePaper
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `bench --only=id,id [--configs=tuned+text] [--repeat=3] [--corpus=] [--pdfs=] [--aspect=]`:
 * times [analyzePaper] on each paper, end to end but without rendering (every page it asks for
 * is rendered once beforehand), best of [repeat] runs after a warm-up: the measuring of each
 * sampled page's text and the automatic zoom's solves (the solving part on its own too). Also
 * the heap the finished analysis holds on to (what the reader keeps while the paper is open),
 * measured after a garbage collection.
 */
fun benchMain(args: List<String>) {
    val opts = args.associate { arg ->
        require(arg.startsWith("--") && '=' in arg) { "Bad argument: $arg" }
        arg.removePrefix("--").substringBefore('=') to arg.substringAfter('=')
    }
    val known = setOf("only", "configs", "repeat", "corpus", "pdfs", "aspect")
    opts.keys.firstOrNull { it !in known }?.let { throw IllegalArgumentException("Unknown option --$it") }
    val home = System.getProperty("user.home")
    val corpus = readCorpus(File(opts["corpus"] ?: "corpus.tsv"))
    val pdfs = File(opts["pdfs"] ?: "$home/.cache/sortes-zoom-eval/pdf")
    val aspect = (opts["aspect"] ?: "0.75").toFloat()
    val repeat = (opts["repeat"] ?: "3").toInt()
    val specs = (opts["configs"] ?: "tuned+text").split(',').map { ConfigSpec.parse(it.trim()) }
    val only = requireNotNull(opts["only"]) { "--only=id,id is required" }.split(',').toSet()
    println("paper\tpages\tanalysed\tconfig\tanalysis ms (wall)\tof it solving (last run)\tscale\twindows\tretained MB   [parallelism ${CropModel.DEFAULT_PARALLELISM}]")
    for (paper in corpus) {
        if (paper.id !in only) continue
        val pages = MemoPages(MutoolPdf(File(pdfs, paper.file)))
        for (spec in specs) {
            fun time(block: suspend () -> Unit): Long {
                runBlocking { block() }
                return (1..repeat).minOf {
                    val t = System.nanoTime()
                    runBlocking { block() }
                    (System.nanoTime() - t) / 1_000_000
                }
            }
            var analysis = runBlocking { analyzePaper(pages, aspect, spec.config) }
            val wall = time { analysis = analyzePaper(pages, aspect, spec.config) }
            val retained = retainedBytes { runBlocking { analyzePaper(MutoolPdf(File(pdfs, paper.file)), aspect, spec.config) } }
            println("${paper.id}\t${pages.pageCount}\t${analysis.pagesAnalyzed}\t${spec.label}\t$wall\t${analysis.solveMillis}\t${"%.3f".format(analysis.auto.scale)}\t${analysis.auto.k}\t${"%.1f".format(retained / 1e6)}")
        }
    }
}

/** Heap still in use, after garbage collection, while what [make] returns is alive. */
private fun retainedBytes(make: () -> Any): Long {
    fun used(): Long {
        repeat(3) { System.gc(); Thread.sleep(100) }
        return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    }
    val before = used()
    val kept = make()
    val after = used()
    java.lang.ref.Reference.reachabilityFence(kept)
    return after - before
}
