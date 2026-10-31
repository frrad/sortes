package dev.sortes.app.pdf

import dev.sortes.app.zoom.AnalysisCache
import dev.sortes.app.zoom.CropConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * The first page of each paper as the reader last showed it on opening, kept on disk as the
 * exact pixels it rendered: shown the moment a paper is opened again, before its PDF is open or
 * its zoom analysis read, which takes a few times longer. The reader still renders the page as
 * it always does once it can, and keeps the stored one on screen only if the two are identical
 * (so a stored page that's somehow out of date costs a refresh, never a wrong crop for longer).
 *
 * A page is stored with what it was rendered from ([Key]): the PDF file (its path, size and
 * modification time: a cheap stand-in for its content, which hashing would take longer than
 * showing the page), the app's build, the crop config, the page and the zoom chosen. A page
 * stored for anything else is a miss, as is a damaged file (then deleted). The screen size is
 * stored too ([Snapshot.width], [Snapshot.height]) for the reader to check against its own.
 *
 * Pixels are stored losslessly as [android.graphics.Bitmap.copyPixelsToBuffer] gives them,
 * deflated at the fastest level in bands of rows, each with its CRC, inflated side by side.
 * One inflater takes about 35 ms on the Boox for a 1860x2480 page, faster than decoding a PNG of
 * it (60 ms), and the file is as small (0.2 to 1 MB). There's one file per paper, the directory
 * kept under [maxBytes] by dropping the least recently used.
 */
class PageSnapshots(
    private val dir: File,
    /** The app's build, as for [AnalysisCache]: a new build never shows pages an old one stored. */
    private val build: String,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    /**
     * What a stored page was rendered from: the PDF [file] ([fileIdentity]), the [config], page
     * [index] and the paper's chosen zoom ([scale], null for automatic).
     */
    data class Key(val file: String, val config: CropConfig, val index: Int, val scale: Float?)

    /** A stored page: [pixels] are [width] x [height] ARGB_8888 pixels, as the bitmap holds them. */
    class Snapshot(val index: Int, val width: Int, val height: Int, val pixels: ByteArray)

    private fun header(key: Key): String = listOf(
        "format=$FORMAT_VERSION", "pipeline=${AnalysisCache.PIPELINE_VERSION}", "build=$build",
        "file=${key.file}", "config=${key.config.fingerprint()}", "index=${key.index}",
        "scale=${key.scale?.toRawBits() ?: "auto"}",
    ).joinToString("\n")

    private fun file(paperId: Long) = File(dir, "paper-$paperId$SUFFIX")

    /** The page stored for [paperId] if it was stored for [key], else null (and an unreadable file is deleted). */
    suspend fun load(paperId: Long, key: Key): Snapshot? = withContext(Dispatchers.IO) {
        val file = file(paperId)
        val bytes = if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
        if (bytes == null) return@withContext null
        try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != MAGIC) throw IOException("Not a page snapshot")
            // Another key: out of date (another zoom, build or file), not damaged. It's replaced
            // when the page is next shown.
            if (input.readUTF() != header(key)) return@withContext null
            val width = input.readInt()
            val height = input.readInt()
            if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) throw IOException("Bad size ${width}x$height")
            val bands = List(input.readInt().also { if (it !in 1..MAX_BANDS) throw IOException("Bad band count $it") }) {
                Band(input.readInt(), input.readInt(), input.readInt())
            }
            // Where each band's data is, and where its pixels go.
            var offset = bytes.size - input.available()
            var row = 0
            val placed = bands.map { band ->
                if (band.rows <= 0 || band.size < 0 || band.size > bytes.size - offset) throw IOException("Bad band")
                if (band.rows > height - row) throw IOException("Too many rows")
                (offset to row).also {
                    offset += band.size
                    row += band.rows
                }
            }
            if (row != height || offset != bytes.size) throw IOException("Wrong size")
            val pixels = ByteArray(width * height * BYTES_PER_PIXEL)
            // Side by side: one inflater would take several times as long.
            coroutineScope {
                bands.zip(placed).map { (band, at) ->
                    async(Dispatchers.Default) {
                        val start = at.second * width * BYTES_PER_PIXEL
                        val length = band.rows * width * BYTES_PER_PIXEL
                        inflateRaw(bytes, at.first, band.size, pixels, start, length)
                        val crc = CRC32().apply { update(pixels, start, length) }
                        if (crc.value.toInt() != band.crc) throw IOException("Bad CRC")
                    }
                }.awaitAll()
            }
            file.setLastModified(System.currentTimeMillis())
            Snapshot(key.index, width, height, pixels)
        } catch (e: IOException) {
            file.delete()
            null
        }
    }

    /**
     * Stores [pixels] ([width] x [height], as in [Snapshot]) as [paperId]'s page for [key],
     * replacing what was there, then trims the directory to [maxBytes]. Writes a temporary file
     * first, so a crash leaves the old file or the new one.
     */
    suspend fun save(paperId: Long, key: Key, width: Int, height: Int, pixels: ByteArray) {
        require(pixels.size == width * height * BYTES_PER_PIXEL) { "${pixels.size} bytes for ${width}x$height" }
        val rowsPerBand = (height + BANDS - 1) / BANDS
        val bands = coroutineScope {
            (0 until height step rowsPerBand).map { first ->
                async(Dispatchers.Default) {
                    val rows = minOf(rowsPerBand, height - first)
                    val start = first * width * BYTES_PER_PIXEL
                    val length = rows * width * BYTES_PER_PIXEL
                    val crc = CRC32().apply { update(pixels, start, length) }
                    Triple(rows, crc.value.toInt(), deflateRaw(pixels, start, length))
                }
            }.awaitAll()
        }
        withContext(Dispatchers.IO) {
            val buffer = ByteArrayOutputStream(bands.sumOf { it.third.size } + 1024)
            DataOutputStream(buffer).run {
                writeInt(MAGIC)
                writeUTF(header(key))
                writeInt(width)
                writeInt(height)
                writeInt(bands.size)
                for ((rows, crc, data) in bands) {
                    writeInt(rows)
                    writeInt(crc)
                    writeInt(data.size)
                }
                for ((_, _, data) in bands) write(data)
                flush()
            }
            dir.mkdirs()
            val tmp = File.createTempFile("page-", ".part", dir)
            try {
                tmp.writeBytes(buffer.toByteArray())
                if (!tmp.renameTo(file(paperId))) throw IOException("Couldn't save ${file(paperId)}")
            } finally {
                tmp.delete()
            }
            trim(keep = file(paperId).name)
        }
    }

    /** [rows] rows of pixels, deflated to [size] bytes, whose CRC-32 is [crc]. */
    private class Band(val rows: Int, val crc: Int, val size: Int)

    /** Forgets [paperId]'s page. */
    fun delete(paperId: Long) {
        file(paperId).delete()
    }

    private fun trim(keep: String) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }?.toList() ?: return
        var total = files.sumOf { it.length() }
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            if (f.name == keep) continue
            val size = f.length()
            if (f.delete()) total -= size
        }
    }

    companion object {
        /** The file format's version: bump it whenever the format changes. */
        const val FORMAT_VERSION = 1

        /** Stored pages take at most this much space in all: dozens of papers. */
        const val DEFAULT_MAX_BYTES = 32L * 1024 * 1024

        const val BYTES_PER_PIXEL = 4

        /** Bands of rows stored, to inflate side by side. */
        private const val BANDS = 8
        private const val MAX_BANDS = 1024
        private const val MAX_PIXELS = 64L shl 20
        private const val MAGIC = 0x534f5047 // "SOPG"
        private const val SUFFIX = ".page"

        /**
         * [file]'s identity, for a [Key]: its path, size and modification time, which change
         * whenever it's written (the app writes a paper's PDF once; a new copy is a new file).
         */
        fun fileIdentity(file: File): String = "${file.absolutePath}|${file.length()}|${file.lastModified()}"

        /** [length] bytes of [input] from [offset], deflated (no header) at the fastest level. */
        private fun deflateRaw(input: ByteArray, offset: Int, length: Int): ByteArray {
            val deflater = Deflater(Deflater.BEST_SPEED, true)
            try {
                deflater.setInput(input, offset, length)
                deflater.finish()
                val out = ByteArrayOutputStream(length / 16 + 64)
                val chunk = ByteArray(1 shl 16)
                while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
                return out.toByteArray()
            } finally {
                deflater.end()
            }
        }

        /** Inflates [size] bytes of [input] from [offset] to exactly [length] bytes of [out] from [start]. */
        private fun inflateRaw(input: ByteArray, offset: Int, size: Int, out: ByteArray, start: Int, length: Int) {
            val inflater = Inflater(true)
            try {
                inflater.setInput(input, offset, size)
                var n = 0
                while (n < length) {
                    val got = inflater.inflate(out, start + n, length - n)
                    if (got == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                    n += got
                }
                // Exactly that much, with nothing left over.
                if (n != length) throw IOException("Band too short")
                if (!inflater.finished() && inflater.inflate(ByteArray(1)) != 0) throw IOException("Band too long")
                if (!inflater.finished() || inflater.remaining != 0) throw IOException("Band doesn't end where stored")
            } catch (e: DataFormatException) {
                throw IOException(e)
            } finally {
                inflater.end()
            }
        }
    }
}
