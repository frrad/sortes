package dev.sortes.zoomeval

import dev.sortes.app.zoom.PageRenderer
import dev.sortes.app.zoom.PageSize
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Reads and renders a PDF with MuPDF's `mutool`, standing in for Android's PdfRenderer.
 * Antialiasing differs slightly from the device, so masks can differ by a cell at edges.
 */
class MutoolPdf(private val file: File) : PageRenderer {

    /** Each page's size in points, after its crop box and rotation. */
    val pageSizes: List<PageSize> = parsePageSizes(run("mutool", "pages", file.path))

    override val pageCount: Int get() = pageSizes.size

    override suspend fun pageSize(index: Int): PageSize = pageSizes[index]

    override suspend fun renderPixels(index: Int, width: Int, height: Int): IntArray = grey(index, width, height)

    /** The page in grey, as opaque ARGB pixels of exactly [width] × [height]. */
    fun grey(index: Int, width: Int, height: Int): IntArray {
        val gray = render(index, width, height, rgb = false)
        return IntArray(width * height) { i ->
            val g = gray.pixel(i % width, i / width, 0)
            (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
    }

    /** The page as an RGB image of exactly [width] × [height]. */
    fun renderImage(index: Int, width: Int, height: Int): BufferedImage {
        val pnm = render(index, width, height, rgb = true)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) {
            image.setRGB(x, y, (pnm.pixel(x, y, 0) shl 16) or (pnm.pixel(x, y, 1) shl 8) or pnm.pixel(x, y, 2))
        }
        return image
    }

    private fun render(index: Int, width: Int, height: Int, rgb: Boolean): Pnm {
        val out = Files.createTempFile("zoom-eval", if (rgb) ".ppm" else ".pgm").toFile()
        try {
            run(
                "mutool", "draw", "-q", "-c", if (rgb) "rgb" else "gray",
                "-w", width.toString(), "-h", height.toString(), "-o", out.path, file.path, (index + 1).toString(),
            )
            // mutool keeps the page's proportions, so it can be a pixel off: sample to the exact size.
            return Pnm.parse(out.readBytes()).resized(width, height)
        } finally {
            out.delete()
        }
    }

    companion object {
        private val PAGE = Regex("""<page pagenum="(\d+)">(.*?)</page>""", RegexOption.DOT_MATCHES_ALL)
        private fun box(tag: String) = Regex("""<$tag l="([-\d.]+)" b="([-\d.]+)" r="([-\d.]+)" t="([-\d.]+)" />""")
        private val ROTATE = Regex("""<Rotate v="(-?\d+)" />""")

        internal fun parsePageSizes(listing: String): List<PageSize> = PAGE.findAll(listing).map { page ->
            val body = page.groupValues[2]
            val b = (box("CropBox").find(body) ?: box("MediaBox").find(body)
                ?: throw IOException("No page box for page ${page.groupValues[1]}")).groupValues
            val w = b[3].toFloat() - b[1].toFloat()
            val h = b[4].toFloat() - b[2].toFloat()
            val rotate = ROTATE.find(body)?.groupValues?.get(1)?.toInt() ?: 0
            if (Math.floorMod(rotate, 180) == 90) PageSize(h, w) else PageSize(w, h)
        }.toList()

        private fun run(vararg command: String): String {
            val process = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0) throw IOException("${command.joinToString(" ")} failed")
            return output
        }
    }
}

/** A binary PGM (P5) or PPM (P6) image. */
internal class Pnm(val width: Int, val height: Int, val channels: Int, private val data: ByteArray) {
    fun pixel(x: Int, y: Int, channel: Int): Int = data[(y * width + x) * channels + channel].toInt() and 0xFF

    /** Nearest-neighbour resample; the identity when the size already matches. */
    fun resized(w: Int, h: Int): Pnm {
        if (w == width && h == height) return this
        val out = ByteArray(w * h * channels)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = (x * width / w).coerceAtMost(width - 1)
            val sy = (y * height / h).coerceAtMost(height - 1)
            for (c in 0 until channels) out[(y * w + x) * channels + c] = data[(sy * width + sx) * channels + c]
        }
        return Pnm(w, h, channels, out)
    }

    companion object {
        fun parse(bytes: ByteArray): Pnm {
            var i = 0
            val tokens = mutableListOf<String>()
            while (tokens.size < 4) {
                while (bytes[i].toInt().toChar().isWhitespace()) i++
                if (bytes[i] == '#'.code.toByte()) {
                    while (bytes[i] != '\n'.code.toByte()) i++
                    continue
                }
                val start = i
                while (!bytes[i].toInt().toChar().isWhitespace()) i++
                tokens += String(bytes, start, i - start)
            }
            i++ // the single whitespace after maxval
            val channels = when (tokens[0]) {
                "P5" -> 1
                "P6" -> 3
                else -> throw IOException("Unsupported PNM ${tokens[0]}")
            }
            val w = tokens[1].toInt()
            val h = tokens[2].toInt()
            return Pnm(w, h, channels, bytes.copyOfRange(i, i + w * h * channels))
        }
    }
}
