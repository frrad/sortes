package dev.sortes.zoomeval.labeller

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONException
import java.io.File
import java.net.InetSocketAddress

/**
 * The labeller's web server, for one person on a trusted host: serves the page ([web]), the
 * exported papers ([data]), and reads and saves labels in [labels].
 *
 * - `GET /` and `GET /<file>`: the page, from [web].
 * - `GET /data/<path>`: exported papers and images, from [data].
 * - `GET /api/labels/<paper>`: that paper's labels, or empty ones.
 * - `PUT /api/labels/<paper>`: validates and saves labels (canonical JSON), returns them as saved.
 */
class LabellerServer(
    private val web: File,
    private val data: File,
    private val labels: File,
    host: String = "127.0.0.1",
    port: Int = 8766,
) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0)

    /** The port actually bound: useful when started on port 0. */
    val port: Int get() = server.address.port

    init {
        server.createContext("/") { exchange ->
            try {
                handle(exchange)
            } catch (e: Exception) {
                send(exchange, 500, "text/plain", "Error: ${e.message}".toByteArray())
            } finally {
                exchange.close()
            }
        }
    }

    fun start() = server.start()

    fun stop() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val method = exchange.requestMethod
        when {
            path.startsWith("/api/labels/") -> {
                val paper = path.removePrefix("/api/labels/")
                if (!PaperLabels.PAPER_ID.matches(paper)) return send(exchange, 400, "text/plain", "Bad paper id".toByteArray())
                when (method) {
                    "GET" -> sendJson(exchange, 200, PaperLabels.load(labels, paper).toText())
                    "PUT" -> {
                        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                        val parsed = try {
                            PaperLabels.fromText(body)
                        } catch (e: JSONException) {
                            return send(exchange, 400, "text/plain", "Bad labels: ${e.message}".toByteArray())
                        } catch (e: IllegalArgumentException) {
                            return send(exchange, 400, "text/plain", "Bad labels: ${e.message}".toByteArray())
                        }
                        if (parsed.paper != paper) return send(exchange, 400, "text/plain", "Paper id mismatch".toByteArray())
                        parsed.save(labels)
                        sendJson(exchange, 200, parsed.toText())
                    }
                    else -> send(exchange, 405, "text/plain", "Method not allowed".toByteArray())
                }
            }
            method != "GET" -> send(exchange, 405, "text/plain", "Method not allowed".toByteArray())
            path.startsWith("/data/") -> sendFile(exchange, data, path.removePrefix("/data/"))
            else -> sendFile(exchange, web, path.removePrefix("/").ifEmpty { "index.html" })
        }
    }

    private fun sendFile(exchange: HttpExchange, root: File, relative: String) {
        val base = root.canonicalFile
        val file = File(base, relative).canonicalFile
        // Nothing outside the root: no ../ escapes.
        if (!file.path.startsWith(base.path + File.separator) || !file.isFile) {
            return send(exchange, 404, "text/plain", "Not found".toByteArray())
        }
        val type = when (file.extension) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }
        // Labels change under the page; exports and the page itself only on re-export.
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        send(exchange, 200, type, file.readBytes())
    }

    private fun sendJson(exchange: HttpExchange, code: Int, text: String) {
        exchange.responseHeaders.add("Cache-Control", "no-store")
        send(exchange, code, "application/json; charset=utf-8", text.toByteArray())
    }

    private fun send(exchange: HttpExchange, code: Int, type: String, body: ByteArray) {
        exchange.responseHeaders.set("Content-Type", type)
        exchange.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
    }
}
