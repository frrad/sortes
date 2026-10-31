package dev.sortes.app.arxiv

/**
 * An arXiv identifier, e.g. `1706.03762` (new style) or `hep-th/9901001` (old style). [id] never
 * carries a version; [version] is kept only for information.
 */
data class ArxivId(val id: String, val version: Int? = null) {

    /** PDF of the latest version, so every version of a paper maps to the same URL. */
    val pdfUrl: String
        get() = "https://arxiv.org/pdf/$id"

    companion object {
        // YYMM.NNNN (2007–2014) or YYMM.NNNNN (2015–), with a real month.
        private const val NEW_STYLE = """\d{2}(?:0[1-9]|1[0-2])\.\d{4,5}"""

        // archive(.SUBJECT)/YYMMNNN, e.g. hep-th/9901001, math.GT/0309136.
        private const val OLD_STYLE =
            """[a-z]+(?:-[a-z]+)*(?:\.[A-Za-z]{2})?/\d{2}(?:0[1-9]|1[0-2])\d{3}"""

        private const val ID = """($NEW_STYLE|$OLD_STYLE)(?:v(\d+))?"""

        // What may follow an ID without it running on into something else.
        private const val END = """(?=$|[\s/?#)\]>"',;]|\.(?!\w))"""

        private val BARE = Regex("""^(?:arxiv:\s*)?$ID$""", RegexOption.IGNORE_CASE)

        private const val ARXIV_PAGE = """(?:www\.|export\.)?arxiv\.org/(?:abs|pdf|html)"""

        // alphaXiv paper pages, optionally under a language prefix such as /es/ or /zh-CN/.
        private const val ALPHAXIV_PAGE =
            """(?:www\.)?alphaxiv\.org/(?:[a-z]{2}(?:-[a-z]{2,4})?/)?(?:abs|pdf|overview)"""

        private val URL =
            Regex(
                """(?<![\w./-])(?:https?://)?(?:$ARXIV_PAGE|$ALPHAXIV_PAGE)/$ID(?:\.pdf|\.md)?$END""",
                RegexOption.IGNORE_CASE,
            )

        private val PREFIXED = Regex("""(?<!\w)arxiv:\s*$ID$END""", RegexOption.IGNORE_CASE)

        /**
         * Extracts an arXiv ID from user input or shared text: a bare ID, `arXiv:<id>`, an
         * arxiv.org abs/pdf/html URL, or an alphaxiv.org abs/pdf/overview URL, possibly embedded in
         * longer text. Returns null if none.
         */
        fun parse(text: String): ArxivId? {
            val trimmed = text.trim()
            val match =
                BARE.matchEntire(trimmed)
                    ?: URL.find(trimmed)
                    ?: PREFIXED.find(trimmed)
                    ?: return null
            val (id, version) = match.destructured
            return ArxivId(normalize(id), version.toIntOrNull())
        }

        // Archive names are lower case; old-style subject classes are upper case.
        private fun normalize(id: String): String {
            val slash = id.indexOf('/')
            if (slash < 0) return id
            val archive = id.substring(0, slash)
            val dot = archive.indexOf('.')
            val normalized =
                if (dot < 0) {
                    archive.lowercase()
                } else {
                    archive.substring(0, dot).lowercase() +
                        "." +
                        archive.substring(dot + 1).uppercase()
                }
            return normalized + id.substring(slash)
        }
    }
}
