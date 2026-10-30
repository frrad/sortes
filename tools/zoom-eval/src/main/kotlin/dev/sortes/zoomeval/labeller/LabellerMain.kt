package dev.sortes.zoomeval.labeller

import dev.sortes.zoomeval.MutoolPdf
import dev.sortes.app.zoom.CropConfig
import dev.sortes.zoomeval.WEIGHT_NAMES
import dev.sortes.zoomeval.fit.Variant
import dev.sortes.zoomeval.content.TextModel
import dev.sortes.zoomeval.readCorpus
import dev.sortes.zoomeval.readWeights
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch

private fun options(args: List<String>, known: Set<String>): Map<String, String> {
    val opts = args.associate { arg ->
        require(arg.startsWith("--") && '=' in arg) { "Bad argument: $arg" }
        arg.removePrefix("--").substringBefore('=') to arg.substringAfter('=')
    }
    opts.keys.firstOrNull { it !in known }?.let { throw IllegalArgumentException("Unknown option --$it") }
    return opts
}

private val home = System.getProperty("user.home")

/** Default home of exported papers: next to the review board. */
val DEFAULT_LABELLER_DATA = "$home/sortes-review/labeller/data"

/**
 * `labeller-export --papers=id,id [--config=reader|tuned|tuned+text] [--variant=furniture|marks|furniture+marks]
 * [--corpus=] [--pdfs=] [--out=] [--aspect=] [--weights=] [--<weight>=]`: renders the papers (ids
 * from the corpus) with a [CropConfig]'s crops (the reader's by default) for the labeller, and lists
 * them in `<out>/papers.json`. `--variant=` swaps the config's rules; weights override its weights.
 */
fun labellerExport(args: List<String>) {
    val opts = options(args, WEIGHT_NAMES + setOf("papers", "corpus", "pdfs", "out", "aspect", "weights", "variant", "config"))
    // "tuned+text": the offline experiment (TextModel), not a CropConfig.
    val textModel = if (opts["config"] == "tuned+text") TextModel() else null
    require(textModel == null || (opts.keys.none { it in WEIGHT_NAMES || it == "variant" })) { "tuned+text takes no weight or variant overrides" }
    val base = textModel?.config ?: CropConfig.named(opts["config"] ?: "reader")
    val corpus = readCorpus(File(opts["corpus"] ?: "corpus.tsv")).associateBy { it.id }
    val ids = requireNotNull(opts["papers"]) { "--papers=id,id is required" }.split(',').map { it.trim() }
    val pdfs = File(opts["pdfs"] ?: "$home/.cache/sortes-zoom-eval/pdf")
    val out = File(opts["out"] ?: DEFAULT_LABELLER_DATA).apply { mkdirs() }
    val aspect = (opts["aspect"] ?: "0.75").toFloat()
    val weights = readWeights(File(opts["weights"] ?: "weights.properties"), opts.filterKeys { it in WEIGHT_NAMES }, base.weights)
    // The config as asked for: its own, or a variant's rules, with any weights overridden.
    val config = opts["variant"]?.let { Variant.parse(it).config(weights) }
        ?: base.copy(weights = weights).let { if (it.weights == base.weights) base else it.copy(name = "${base.name}+weights") }
    val commit = gitCommit()

    val index = File(out, "papers.json")
    val listed = if (index.exists()) JSONArray(index.readText()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } else emptyList()
    val entries = listed.associateBy { it.getString("id") }.toMutableMap()
    for (id in ids) {
        val paper = requireNotNull(corpus[id]) { "$id isn't in the corpus" }
        val started = System.currentTimeMillis()
        val json = runBlocking { exportPaper(paper, MutoolPdf(File(pdfs, paper.file)), aspect, commit, out, config, textModel) }
        entries[id] = JSONObject()
            .put("id", id)
            .put("title", paper.title)
            .put("category", paper.category)
            .put("pageCount", json.getInt("pageCount"))
        println("$id: ${json.getInt("pageCount")} pages (${System.currentTimeMillis() - started} ms)")
    }
    // In the order asked for, then any exported before.
    val order = ids + entries.keys.filter { it !in ids }.sorted()
    index.writeText(canonical(JSONArray(order.map { entries.getValue(it) })) + "\n")
    println("Wrote ${out.path}")
}

/** `labeller-serve --web=DIR --data=DIR --labels=DIR [--host=127.0.0.1] [--port=8766]`: serves until killed. */
fun labellerServe(args: List<String>) {
    val opts = options(args, setOf("web", "data", "labels", "host", "port"))
    val server = LabellerServer(
        web = File(requireNotNull(opts["web"]) { "--web is required" }),
        data = File(opts["data"] ?: DEFAULT_LABELLER_DATA),
        labels = File(requireNotNull(opts["labels"]) { "--labels is required" }),
        host = opts["host"] ?: "127.0.0.1",
        port = (opts["port"] ?: "8766").toInt(),
    )
    server.start()
    println("Labeller on http://${opts["host"] ?: "127.0.0.1"}:${server.port}/")
    CountDownLatch(1).await()
}

/** The current commit, marked "-dirty" if the zoom code has uncommitted changes. */
private fun gitCommit(): String {
    fun git(vararg args: String): String = ProcessBuilder("git", *args).redirectErrorStream(true).start()
        .let { p -> p.inputStream.bufferedReader().readText().trim().also { p.waitFor() } }
    val commit = git("rev-parse", "--short=12", "HEAD")
    val dirty = git("status", "--porcelain", "--", "../../app/src/main/java/dev/sortes/app/zoom", ".").isNotEmpty()
    return if (dirty) "$commit-dirty" else commit
}
