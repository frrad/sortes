package dev.sortes.zoomeval.labeller

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The owner's labels for one paper's crops, as saved in `tools/zoom-eval/labels/<paper>.json`.
 * See tools/zoom-eval/labeller/README.md for the schema.
 */
data class PaperLabels(
    val paper: String,
    /** The crops these labels judge: who made them and how. */
    val labelledAgainst: LabelledAgainst?,
    /** The ideal zoom, as a scale of the padded page (the reader's [dev.sortes.app.zoom.CropWindow.scale]); null if not set. */
    val idealScale: Double?,
    val comment: String,
    /** By page number, counting from 1. */
    val pages: Map<Int, PageLabel>,
    /**
     * Who judged: null for the owner (the files in `labels/`), or a name such as "agent" for
     * labels made by an agent reviewing renders (`labels/agent/`), which score separately and
     * never override the owner's.
     */
    val labeller: String? = null,
) {
    init {
        require(PAPER_ID.matches(paper)) { "Bad paper id: $paper" }
        require(idealScale == null || idealScale in 0.05..1.0) { "Bad ideal scale: $idealScale" }
        require(pages.keys.all { it >= 1 }) { "Page numbers count from 1" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("paper", paper)
        .put("labelledAgainst", labelledAgainst?.toJson() ?: JSONObject.NULL)
        .put("idealScale", idealScale ?: JSONObject.NULL)
        .put("comment", comment)
        .apply { labeller?.let { put("labeller", it) } }
        // Pages nothing has been said about are left out.
        .put("pages", JSONObject().apply { pages.forEach { (n, p) -> if (!p.isEmpty) put(n.toString(), p.toJson()) } })

    /** Canonical text: sorted keys, two-space indent, trailing newline; equal labels, equal text. */
    fun toText(): String = canonical(toJson()) + "\n"

    companion object {
        const val SCHEMA = 1
        val PAPER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

        fun empty(paper: String) = PaperLabels(paper, null, null, "", emptyMap())

        fun fromJson(json: JSONObject): PaperLabels {
            val schema = json.getInt("schema")
            require(schema == SCHEMA) { "Unsupported label schema $schema" }
            val pages = json.optJSONObject("pages") ?: JSONObject()
            return PaperLabels(
                paper = json.getString("paper"),
                labelledAgainst = json.optJSONObject("labelledAgainst")?.let { LabelledAgainst.fromJson(it) },
                idealScale = json.optNullableDouble("idealScale"),
                comment = json.optString("comment", ""),
                pages = pages.keySet().associate { key ->
                    val n = requireNotNull(key.toIntOrNull()) { "Bad page number $key" }
                    n to PageLabel.fromJson(pages.getJSONObject(key))
                }.toSortedMap(),
                labeller = json.optNullableString("labeller"),
            )
        }

        fun fromText(text: String): PaperLabels = fromJson(JSONObject(text))

        /** The labels saved for [paper] in [dir], or empty ones if there are none yet. */
        fun load(dir: File, paper: String): PaperLabels {
            val file = fileFor(dir, paper)
            return if (file.exists()) fromText(file.readText()) else empty(paper)
        }

        fun fileFor(dir: File, paper: String): File {
            require(PAPER_ID.matches(paper)) { "Bad paper id: $paper" }
            return File(dir, "$paper.json")
        }
    }
}

/** Saves [labels] to [dir], atomically: a crash leaves the old file or the new one, not half of each. */
fun PaperLabels.save(dir: File): File {
    dir.mkdirs()
    val file = PaperLabels.fileFor(dir, paper)
    val temp = File(dir, ".$paper.json.tmp")
    temp.writeText(toText())
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    return file
}

/** What produced the crops being labelled. */
data class LabelledAgainst(
    /** The git commit of the code that made the crops. */
    val commit: String,
    /** How the crops were chosen, e.g. "reader" (the app's sampled crop model). */
    val algorithm: String,
    /** The crop model's weights, by [dev.sortes.app.zoom.CropModelWeights] property name. */
    val weights: Map<String, Double>,
    /** The scale the model chose for the paper. */
    val scale: Double,
    /** The screen's width / height the crops were made for. */
    val aspect: Double,
    /**
     * Each page's window centre as shown, in page points, by page index; null for older labels,
     * whose crops are rebuilt from [algorithm] and [weights]. Recorded for models that aren't
     * a [dev.sortes.app.zoom.CropConfig] (offline experiments), and so later code changes
     * can't move what was judged.
     */
    val centres: List<Point>? = null,
    /** Any model parameters besides [weights] (an experiment's), by name. */
    val parameters: Map<String, Double> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("commit", commit)
        .put("algorithm", algorithm)
        .put("weights", JSONObject().apply { weights.forEach { (k, v) -> put(k, v) } })
        .put("scale", scale)
        .put("aspect", aspect)
        .apply { centres?.let { c -> put("centres", JSONArray(c.map { JSONObject().put("x", it.x).put("y", it.y) })) } }
        .apply { if (parameters.isNotEmpty()) put("parameters", JSONObject().apply { parameters.forEach { (k, v) -> put(k, v) } }) }

    companion object {
        fun fromJson(json: JSONObject) = LabelledAgainst(
            commit = json.getString("commit"),
            algorithm = json.getString("algorithm"),
            weights = json.getJSONObject("weights").let { w -> w.keySet().associateWith { w.getDouble(it) }.toSortedMap() },
            scale = json.getDouble("scale"),
            aspect = json.getDouble("aspect"),
            centres = json.optJSONArray("centres")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it).let { p -> Point(p.getDouble("x"), p.getDouble("y")) } }
            },
            parameters = json.optJSONObject("parameters")?.let { w -> w.keySet().associateWith { w.getDouble(it) }.toSortedMap() }
                ?: emptyMap(),
        )
    }
}

/** The owner's verdict on one page's crop. */
data class PageLabel(
    /** "good", "bad", or null if not judged. */
    val verdict: Verdict?,
    val tags: Set<Tag>,
    val comment: String,
    /**
     * Where the window should be centred, in page points from the page's top left (it may lie in
     * the padding around the page); null to accept the model's position. Its size is the paper's
     * zoom: [PaperLabels.idealScale], or the model's scale if that isn't set.
     */
    val idealCentre: Point?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("verdict", verdict?.json ?: JSONObject.NULL)
        .put("tags", JSONArray(tags.map { it.json }.sorted()))
        .put("comment", comment)
        .put("idealCentre", idealCentre?.let { JSONObject().put("x", it.x).put("y", it.y) } ?: JSONObject.NULL)

    /** True if nothing has been said about the page. */
    val isEmpty: Boolean get() = verdict == null && tags.isEmpty() && comment.isEmpty() && idealCentre == null

    companion object {
        fun fromJson(json: JSONObject) = PageLabel(
            verdict = json.optNullableString("verdict")?.let { Verdict.of(it) },
            tags = json.optJSONArray("tags")?.let { a -> (0 until a.length()).map { Tag.of(a.getString(it)) }.toSet() } ?: emptySet(),
            comment = json.optString("comment", ""),
            idealCentre = json.optJSONObject("idealCentre")?.let { Point(it.getDouble("x"), it.getDouble("y")) },
        )
    }
}

data class Point(val x: Double, val y: Double)

enum class Verdict(val json: String) {
    GOOD("good"),
    BAD("bad");

    companion object {
        fun of(json: String) = entries.firstOrNull { it.json == json } ?: throw IllegalArgumentException("Bad verdict: $json")
    }
}

/** Quick tags for what's wrong with a crop. */
enum class Tag(val json: String) {
    TOP_CUT("top-cut"),
    BOTTOM_CUT("bottom-cut"),
    SIDE_CUT("side-cut"),
    TOO_LOOSE("too-loose"),
    OFF_CENTRE("off-centre"),
    WINDOW_JUMPED("window-jumped"),
    OTHER("other");

    companion object {
        fun of(json: String) = entries.firstOrNull { it.json == json } ?: throw IllegalArgumentException("Bad tag: $json")
    }
}

private fun JSONObject.optNullableDouble(key: String): Double? = if (isNull(key)) null else getDouble(key)
private fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else getString(key)

/** JSON text with object keys sorted and a two-space indent, so equal values give equal text. */
fun canonical(value: Any?, indent: String = ""): String {
    val inner = "$indent  "
    return when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> if (value.isEmpty) "{}" else value.keySet().sorted().joinToString(",\n", "{\n", "\n$indent}") {
            inner + JSONObject.quote(it) + ": " + canonical(value.get(it), inner)
        }
        is JSONArray -> if (value.isEmpty) "[]" else (0 until value.length()).joinToString(",\n", "[\n", "\n$indent]") {
            inner + canonical(value.get(it), inner)
        }
        is String -> JSONObject.quote(value)
        is Double, is Float -> {
            val d = (value as Number).toDouble()
            require(d.isFinite()) { "Not a finite number: $d" }
            if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()
        }
        is Number, is Boolean -> value.toString()
        else -> throw IllegalArgumentException("Not JSON: $value")
    }
}
