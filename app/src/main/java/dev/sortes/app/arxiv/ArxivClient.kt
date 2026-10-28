package dev.sortes.app.arxiv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/** Metadata for one arXiv paper. */
data class ArxivEntry(
    val title: String,
    val authors: List<String>,
    val abstract: String,
    val year: Int?,
)

fun interface ArxivClient {
    /** Fetches metadata for [id]; throws [IOException] if it can't be found or fetched. */
    suspend fun fetch(id: String): ArxivEntry
}

/** Talks to the arXiv export API (https://info.arxiv.org/help/api/). */
class HttpArxivClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
) : ArxivClient {

    companion object {
        const val DEFAULT_BASE_URL = "https://export.arxiv.org/api/query"
    }

    override suspend fun fetch(id: String): ArxivEntry = withContext(Dispatchers.IO) {
        val connection = URL("$baseUrl?id_list=$id&max_results=1").openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("arXiv lookup failed: HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { ArxivAtom.parse(it, id) }
        } finally {
            connection.disconnect()
        }
    }
}

/** Parses the Atom feed returned by the arXiv API. */
object ArxivAtom {
    private const val ATOM = "http://www.w3.org/2005/Atom"

    fun parse(input: InputStream, id: String): ArxivEntry {
        val document = try {
            newDocumentBuilderFactory().newDocumentBuilder().apply {
                // Never fetch external DTDs or entities, even where the features above are unsupported.
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
            }.parse(input)
        } catch (e: Exception) {
            throw IOException("Couldn't read arXiv response", e)
        }

        val entry = document.getElementsByTagNameNS(ATOM, "entry").item(0) as Element?
            ?: throw IOException("arXiv has no paper $id")
        // arXiv reports problems such as a malformed ID as a pseudo-entry.
        if (entry.text("id").contains("/api/errors")) {
            throw IOException("arXiv error: ${entry.text("summary").ifEmpty { "unknown" }}")
        }
        val title = entry.text("title")
        if (title.isEmpty()) throw IOException("arXiv has no paper $id")

        val authors = entry.getElementsByTagNameNS(ATOM, "author").let { nodes ->
            (0 until nodes.length).map { (nodes.item(it) as Element).text("name") }
                .filter { it.isNotEmpty() }
        }
        return ArxivEntry(
            title = title,
            authors = authors,
            abstract = entry.text("summary"),
            year = entry.text("published").take(4).toIntOrNull(),
        )
    }

    private fun newDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // Not every platform parser knows these (Android's rejects unknown features), so try each.
            for ((feature, value) in XXE_FEATURES) {
                try {
                    setFeature(feature, value)
                } catch (_: Exception) {
                }
            }
        }

    private val XXE_FEATURES = listOf(
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
    )

    /** Whitespace-normalized text of the first direct Atom child [name], or "". */
    private fun Element.text(name: String): String {
        var node = firstChild
        while (node != null) {
            if (node is Element && node.namespaceURI == ATOM && node.localName == name) {
                return node.textContent.trim().replace(Regex("\\s+"), " ")
            }
            node = node.nextSibling
        }
        return ""
    }
}
