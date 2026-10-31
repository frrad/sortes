package dev.sortes.app.zoom

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Papers' zoom analyses kept on disk, so a paper opened again shows its crops at once instead of
 * rendering and measuring its sample again ([analyzePaper] takes seconds on the tablet).
 *
 * Each analysis is kept whole ([AnalysisCodec]): the sampled pages' masks and text measures as
 * well as the automatic crops, so manual zooms, the zoom curve and pages outside the sample work
 * as after a fresh analysis, rendering only the pages they always render (an unsampled page when
 * it's shown). That's a few hundred kilobytes to a few megabytes a paper, compressed; the
 * directory is kept under [maxBytes] by dropping the analyses least recently used.
 *
 * An analysis is keyed ([Key]) by the PDF's content, the screen's shape, the config, the app's
 * build and [PIPELINE_VERSION], so a changed paper, screen, model or app is analysed afresh. Anything unreadable
 * (a truncated or corrupt file, an old format) is a miss: it's deleted and the paper analysed again.
 */
class AnalysisCache(
    private val dir: File,
    /**
     * The build of the code doing the analysis, e.g. the app's versionCode and when it was
     * installed: part of every key, so a new build never reads analyses an older one stored,
     * whatever changed in it, without anyone remembering to bump [PIPELINE_VERSION].
     */
    private val build: String,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    /**
     * Where [analyze] stores fresh analyses, so they're shown without waiting for the write (a
     * few hundred milliseconds on the tablet); null to store them before returning.
     */
    private val saveScope: CoroutineScope? = null,
) {

    /**
     * What an analysis depends on: the [build] of the code, the PDF's [content] hash
     * ([contentHash]), the screen's [aspect], the [config] and the most pages sampled ([maxPages]).
     */
    data class Key(
        val build: String,
        val content: String,
        val aspect: Float,
        val config: CropConfig,
        val maxPages: Int = MAX_ANALYZED_PAGES,
    ) {
        /** The key as text: written into the file and checked when it's read. */
        val text: String = listOf(
            "format=$FORMAT_VERSION", "pipeline=$PIPELINE_VERSION", "build=$build", "content=$content",
            // Exactly, by its bits: a different screen shape is a different analysis.
            "aspect=${aspect.toRawBits()}", "maxPages=$maxPages", "config=${config.fingerprint()}",
        ).joinToString("\n")

        /** The file name: a hash of [text], so any change to it is a different file. */
        val fileName: String get() = sha256(text.toByteArray()) + SUFFIX
    }

    /**
     * The analysis stored for [key], placing unsampled pages from [source]; null if there's none
     * or it can't be read (then it's deleted).
     */
    suspend fun load(key: Key, source: PageRenderer): PaperAnalysis? {
        val file = File(dir, key.fileName)
        val bytes = withContext(Dispatchers.IO) { if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null }
            ?: return null
        return try {
            DataInputStream(BufferedInputStream(GZIPInputStream(bytes.inputStream(), 1 shl 16), 1 shl 16)).use { input ->
                if (input.readInt() != MAGIC) throw IOException("Not an analysis")
                if (input.readUTF() != key.text) throw IOException("Stored for another key")
                val analysis = AnalysisCodec.read(input, source, key.aspect, key.config)
                // Reading to the end checks the gzip trailer, so a damaged file isn't trusted.
                if (input.read() != -1) throw IOException("Trailing bytes")
                analysis
            }.also { withContext(Dispatchers.IO) { file.setLastModified(System.currentTimeMillis()) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { file.delete() }
            null
        }
    }

    /**
     * Stores [analysis] under [key], replacing any analysis there, then trims the directory to
     * [maxBytes]. Writes to a temporary file first, so a crash leaves the old file or the new one.
     */
    suspend fun save(key: Key, analysis: PaperAnalysis) {
        val bytes = withContext(Dispatchers.Default) { encode(key, analysis) }
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val tmp = File.createTempFile("analysis-", ".part", dir)
            try {
                tmp.writeBytes(bytes)
                val file = File(dir, key.fileName)
                if (!tmp.renameTo(file)) throw IOException("Couldn't save $file")
            } finally {
                tmp.delete()
            }
            trim(keep = key.fileName)
        }
    }

    private fun encode(key: Key, analysis: PaperAnalysis): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        DataOutputStream(BufferedOutputStream(GZIPOutputStream(buffer, 1 shl 16), 1 shl 16)).use { out ->
            out.writeInt(MAGIC)
            out.writeUTF(key.text)
            AnalysisCodec.write(analysis, out)
        }
        return buffer.toByteArray()
    }

    /** Drops the least recently used analyses until the directory fits in [maxBytes], never [keep]. */
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

    /**
     * [source]'s analysis for a screen of [aspect]: the stored one if [content] (the PDF's
     * [contentHash]) was analysed before with [config], else a fresh one, which is stored (in
     * [saveScope], if given, after returning) if it's [PaperAnalysis.complete]. A failure to store
     * isn't the caller's problem: the paper is just analysed again next time.
     */
    suspend fun analyze(
        source: PageRenderer,
        content: String,
        aspect: Float,
        config: CropConfig = CropConfig.DEFAULT,
    ): PaperAnalysis {
        val key = Key(build, content, aspect, config)
        load(key, source)?.let { return it }
        val analysis = analyzePaper(source, aspect, config, key.maxPages)
        // A page that failed to render may render next time: don't keep its absence for good.
        if (!analysis.complete) return analysis
        if (saveScope != null) saveScope.launch { saveQuietly(key, analysis) } else saveQuietly(key, analysis)
        return analysis
    }

    private suspend fun saveQuietly(key: Key, analysis: PaperAnalysis) {
        try {
            save(key, analysis)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing lost but time next open.
        }
    }

    companion object {
        /**
         * The crop pipeline's version. Every new build of the app invalidates stored analyses
         * anyway (the key has the [build]); bump this too when a change alters what
         * [analyzePaper] computes, for builds that don't (zoom-eval, tests), and as a record.
         * Config changes need no bump: the config is part of the key ([CropConfig.fingerprint]).
         */
        const val PIPELINE_VERSION = 1

        /** The file format's version ([AnalysisCodec]): bump it whenever the format changes. */
        const val FORMAT_VERSION = 1

        /** Stored analyses take at most this much space in all. */
        const val DEFAULT_MAX_BYTES = 64L * 1024 * 1024

        private const val MAGIC = 0x534f5a41 // "SOZA"
        private const val SUFFIX = ".zoom"

        /** A hash of [file]'s content, to key its analysis by. Blocking; call off the main thread. */
        fun contentHash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            BufferedInputStream(file.inputStream()).use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().toHex()
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
