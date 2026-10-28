package dev.sortes.app.alphaxiv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The contents of a shared alphaXiv folder. */
data class AlphaxivFolder(
    val name: String?,
    /** alphaXiv `universalId`s in folder order; for arXiv papers this is the versionless arXiv ID. */
    val paperIds: List<String>,
    /** False if the folder had more than [HttpAlphaxivClient.MAX_PAGES] pages and only those were read. */
    val complete: Boolean = true,
)

/** One page of `GET /folders/v4/shared/{id}/papers`. */
data class AlphaxivPapersPage(val paperIds: List<String>, val nextCursor: String?)

fun interface AlphaxivClient {
    /** Fetches every paper in the shared folder [folderId]; throws [IOException] on any failure. */
    suspend fun fetchFolder(folderId: String): AlphaxivFolder
}

/** Thrown when alphaXiv says the folder doesn't exist (or isn't shared). */
class AlphaxivFolderNotFoundException(folderId: String) :
    IOException("alphaXiv folder $folderId not found or not shared")

/** Reads shared folders through the public, unauthenticated alphaXiv API the share page uses. */
class HttpAlphaxivClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
) : AlphaxivClient {

    companion object {
        const val DEFAULT_BASE_URL = "https://api.alphaxiv.org"

        /** Bounds the work per sync; a folder past this is read only in part. */
        const val MAX_PAGES = 100
    }

    override suspend fun fetchFolder(folderId: String): AlphaxivFolder = withContext(Dispatchers.IO) {
        val folderJson = try {
            get("/folders/v4/shared/$folderId")
        } catch (e: HttpStatusException) {
            // alphaXiv rejects IDs that aren't UUIDv7 with a 400 before looking anything up.
            if (e.code == 400 || e.code == 404) throw AlphaxivFolderNotFoundException(folderId)
            throw e
        }
        val name = AlphaxivJson.parseFolderName(folderJson, folderId)

        val ids = mutableListOf<String>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        for (page in 1..MAX_PAGES) {
            val query = cursor?.let { "?cursor=" + URLEncoder.encode(it, "UTF-8") } ?: ""
            val result = AlphaxivJson.parsePapersPage(get("/folders/v4/shared/$folderId/papers$query"))
            ids += result.paperIds
            cursor = result.nextCursor ?: return@withContext AlphaxivFolder(name, ids.distinct())
            if (!seenCursors.add(cursor)) throw IOException("alphaXiv returned a repeated page cursor")
        }
        AlphaxivFolder(name, ids.distinct(), complete = false)
    }

    private class HttpStatusException(val code: Int) : IOException("alphaXiv request failed: HTTP $code")

    private fun get(path: String): String {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/json")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw HttpStatusException(code)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

/** Parses alphaXiv shared-folder API responses. */
object AlphaxivJson {

    /** Name of [folderId] from `GET /folders/v4/shared/{id}`, which also lists its subfolders. */
    fun parseFolderName(json: String, folderId: String): String? = parse(json) { root ->
        val folders = root.optJSONArray("folders") ?: return@parse null
        (0 until folders.length())
            .mapNotNull { folders.optJSONObject(it) }
            .firstOrNull { it.optString("id").equals(folderId, ignoreCase = true) }
            ?.optStringOrNull("name")
    }

    fun parsePapersPage(json: String): AlphaxivPapersPage = parse(json) { root ->
        val papers = root.optJSONArray("papers") ?: throw IOException("alphaXiv response has no papers")
        AlphaxivPapersPage(
            paperIds = (0 until papers.length()).mapNotNull { papers.optJSONObject(it)?.optStringOrNull("universalId") },
            nextCursor = root.optStringOrNull("nextCursor"),
        )
    }

    private fun <T> parse(json: String, block: (JSONObject) -> T): T {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw IOException("Couldn't read alphaXiv response", e)
        }
        return block(root)
    }

    /** Like [JSONObject.optString] but maps missing, null and blank values to null. */
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).trim().ifEmpty { null }
}
