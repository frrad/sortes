package dev.sortes.zoomeval.labeller

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

class LabellerServerTest {
    private lateinit var root: File
    private lateinit var server: LabellerServer

    @Before
    fun start() {
        root = Files.createTempDirectory("labeller").toFile()
        File(root, "web").mkdirs()
        File(root, "web/index.html").writeText("<html>labeller</html>")
        File(root, "data/1706.03762").mkdirs()
        File(root, "data/1706.03762/paper.json").writeText("{\"id\": \"1706.03762\"}")
        File(root, "secret.txt").writeText("not served")
        server = LabellerServer(File(root, "web"), File(root, "data"), File(root, "labels"), port = 0)
        server.start()
    }

    @After
    fun stop() {
        server.stop()
        root.deleteRecursively()
    }

    private data class Response(val code: Int, val body: String)

    private fun request(method: String, path: String, body: String? = null): Response {
        val c = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        return Response(code, text)
    }

    @Test
    fun servesThePageAndTheData() {
        assertEquals(Response(200, "<html>labeller</html>"), request("GET", "/"))
        assertEquals(200, request("GET", "/data/1706.03762/paper.json").code)
        assertEquals(404, request("GET", "/data/nope.json").code)
    }

    @Test
    fun unchangedFilesAreAnswered304() {
        val first = URL("http://127.0.0.1:${server.port}/data/1706.03762/paper.json").openConnection() as HttpURLConnection
        assertEquals(200, first.responseCode)
        val etag = first.getHeaderField("ETag")
        assertTrue(etag != null && etag.startsWith("\""))
        val again = URL("http://127.0.0.1:${server.port}/data/1706.03762/paper.json").openConnection() as HttpURLConnection
        again.setRequestProperty("If-None-Match", etag)
        assertEquals(304, again.responseCode)
        // Changed: served afresh.
        File(root, "data/1706.03762/paper.json").writeText("{\"id\": \"1706.03762\", \"changed\": true}")
        val changed = URL("http://127.0.0.1:${server.port}/data/1706.03762/paper.json").openConnection() as HttpURLConnection
        changed.setRequestProperty("If-None-Match", etag)
        assertEquals(200, changed.responseCode)
    }

    @Test
    fun nothingOutsideItsFoldersIsServed() {
        assertEquals(404, request("GET", "/../secret.txt").code)
        assertEquals(404, request("GET", "/data/../secret.txt").code)
        assertEquals(404, request("GET", "/data/%2e%2e/secret.txt").code)
    }

    @Test
    fun unlabelledPapersGetEmptyLabels() {
        val r = request("GET", "/api/labels/1706.03762")
        assertEquals(200, r.code)
        assertEquals(PaperLabels.empty("1706.03762"), PaperLabels.fromText(r.body))
    }

    @Test
    fun savedLabelsReloadAndSavingAgainChangesNothing() {
        val labels = PaperLabels(
            "1706.03762", null, 0.8, "zoom in",
            mapOf(3 to PageLabel(Verdict.BAD, setOf(Tag.SIDE_CUT), "right column cut", Point(300.0, 410.0))),
        )
        val saved = request("PUT", "/api/labels/1706.03762", labels.toText())
        assertEquals(200, saved.code)
        assertEquals(labels.toText(), saved.body)
        val file = File(root, "labels/1706.03762.json")
        val bytes = file.readBytes()
        assertEquals(labels, PaperLabels.fromText(request("GET", "/api/labels/1706.03762").body))
        // Idempotent: a second identical save leaves the file the same.
        request("PUT", "/api/labels/1706.03762", labels.toText())
        assertTrue(bytes.contentEquals(file.readBytes()))
    }

    @Test
    fun badSavesAreRefusedAndChangeNothing() {
        assertEquals(400, request("PUT", "/api/labels/1706.03762", "not json").code)
        assertEquals(400, request("PUT", "/api/labels/1706.03762", PaperLabels.empty("other").toText()).code)
        assertEquals(400, request("PUT", "/api/labels/..%2Fx", PaperLabels.empty("x").toText()).code)
        assertEquals(405, request("POST", "/api/labels/1706.03762", "{}").code)
        assertEquals(405, request("PUT", "/index.html", "x").code)
        assertTrue(!File(root, "labels").exists() || File(root, "labels").list()!!.isEmpty())
    }
}
