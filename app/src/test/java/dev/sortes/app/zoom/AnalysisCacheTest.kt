package dev.sortes.app.zoom

import java.io.File
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnalysisCacheTest {

    @get:Rule val temp = TemporaryFolder()

    private val aspect = 0.75f
    private val config = CropConfig.DEFAULT

    private fun cache(maxBytes: Long = AnalysisCache.DEFAULT_MAX_BYTES) =
        AnalysisCache(File(temp.root, "analyses"), BUILD, maxBytes)

    private fun key(
        content: String = "abc",
        aspect: Float = this.aspect,
        config: CropConfig = this.config,
        maxPages: Int = 6,
        build: String = BUILD,
    ) = AnalysisCache.Key(build, content, aspect, config, maxPages)

    /** [pages] analysed as the cache would, sampling at most [Key.maxPages] pages. */
    private fun analysed(pages: PageRenderer, key: AnalysisCache.Key) = runBlocking {
        analyzePaper(pages, key.aspect, key.config, key.maxPages)
    }

    private fun files() =
        File(temp.root, "analyses").listFiles()?.filter { it.name.endsWith(".zoom") }.orEmpty()

    @Test
    fun aRestoredAnalysisIsTheAnalysisItWas() {
        val pages = BookPages(14)
        val key = key()
        val fresh = analysed(pages, key)
        // The paper exercises what's stored: text, furniture, two windows, unsampled pages.
        assertNotNull(fresh.glyphPx)
        assertEquals("${fresh.auto.scale} ${fresh.glyphPx}", 2, fresh.auto.k)
        assertTrue(fresh.sampleText.all { it != null })
        assertTrue(
            fresh.paperRule is PageFurniture && (fresh.paperRule as PageFurniture).top.isNotEmpty()
        )
        val cache = cache()
        runBlocking { cache.save(key, fresh) }
        val copy = BookPages(14)

        val restored = runBlocking { cache.load(key, copy) }!!

        assertTrue(restored.restored)
        assertFalse(fresh.restored)
        assertEquals(fresh.auto, restored.auto)
        assertEquals(fresh.sample, restored.sample)
        assertEquals(fresh.glyphPx, restored.glyphPx)
        assertEquals(fresh.density, restored.density)
        assertEquals(fresh.pageCount, restored.pageCount)
        assertEquals(fresh.config, restored.config)
        // Nothing rendered to restore it, nor to place a sampled page.
        assertEquals(emptyList<Int>(), copy.renders.toList())
        runBlocking { restored.place(fresh.sample[2], restored.auto) }
        assertEquals(emptyList<Int>(), copy.renders.toList())
        // Other scales, the curve and every page's placement as from the fresh analysis.
        for (scale in listOf(1f, 0.9f, 0.8f, 0.7f)) {
            assertEquals(
                "scale $scale",
                runBlocking { fresh.cropsAt(scale) },
                runBlocking { restored.cropsAt(scale) },
            )
        }
        assertEquals(
            runBlocking { fresh.curve().toList().last().points },
            runBlocking { restored.curve().toList().last().points },
        )
        for (index in 0 until pages.pageCount) {
            for (crops in listOf(fresh.auto, runBlocking { fresh.cropsAt(0.7f) })) {
                assertEquals(
                    "page $index",
                    runBlocking { fresh.place(index, crops) },
                    runBlocking { restored.place(index, crops) },
                )
            }
        }
        // Unsampled pages are rendered when placed, as after a fresh analysis.
        assertEquals(
            pages.pageCount - fresh.sample.size,
            copy.renders.filter { it.fine }.map { it.index }.distinct().size,
        )
    }

    @Test
    fun aPaperWithoutFurnitureOrSomeTextRoundTrips() {
        // No paper rule, and pages whose fine render fails: no text measured on them.
        val noFurniture = config.copy(furniture = false)
        val pages = BookPages(8, fineFails = setOf(1, 4))
        val key = key(config = noFurniture, maxPages = 60)
        val fresh = analysed(pages, key)
        assertEquals(listOf(1, 4), fresh.sampleText.indices.filter { fresh.sampleText[it] == null })
        val cache = cache()
        runBlocking { cache.save(key, fresh) }

        val restored = runBlocking { cache.load(key, BookPages(8, fineFails = setOf(1, 4))) }!!

        assertEquals(fresh.auto, restored.auto)
        assertEquals(fresh.sampleText.map { it == null }, restored.sampleText.map { it == null })
        assertEquals(runBlocking { fresh.cropsAt(0.75f) }, runBlocking { restored.cropsAt(0.75f) })
    }

    @Test
    fun masksRoundTripBitForBitWhateverTheirSize() {
        val random = kotlin.random.Random(3)
        // Whole bytes of cells and a partial last byte; blank, full and mixed.
        for ((w, h) in listOf(8 to 4, 3 to 5, 7 to 9, 61 to 79, 1 to 1)) {
            for (fill in listOf(0.0, 1.0, 0.3)) {
                val mask = InkMask(w, h, BooleanArray(w * h) { random.nextDouble() < fill })
                val bytes =
                    java.io
                        .ByteArrayOutputStream()
                        .also { out ->
                            java.io.DataOutputStream(out).use { AnalysisCodec.writeMask(mask, it) }
                        }
                        .toByteArray()
                val input = BytesInput(bytes)

                val read = AnalysisCodec.readMask(input)

                assertEquals(0, input.remaining)
                assertEquals(w to h, read.width to read.height)
                for (y in 0 until h) for (x in 0 until w) assertEquals(
                    "${w}x$h $fill ($x, $y)",
                    mask[x, y],
                    read[x, y],
                )
            }
        }
    }

    @Test
    fun aBlankPaperRoundTrips() {
        val pages = MaskPages(List(3) { TestPage(60, 80).mask() })
        val key = key()
        val fresh = analysed(pages, key)
        assertNull(fresh.glyphPx)
        val cache = cache()
        runBlocking { cache.save(key, fresh) }

        val restored =
            runBlocking { cache.load(key, MaskPages(List(3) { TestPage(60, 80).mask() })) }!!

        assertEquals(fresh.auto, restored.auto)
        assertNull(restored.glyphPx)
    }

    @Test
    fun anotherPdfScreenShapeConfigOrSampleIsAMiss() {
        val pages = BookPages(14)
        val key = key()
        val cache = cache()
        runBlocking { cache.save(key, analysed(pages, key)) }
        val others =
            listOf(
                key(content = "abd"),
                key(aspect = 0.7501f),
                key(config = config.copy(weights = config.weights.copy(beta = 0.51))),
                key(config = config.copy(text = config.text.copy(lines = 0.11))),
                key(config = config.copy(pageRules = IGNORABLE_INK_RULES)),
                key(config = config.copy(furniture = false)),
                key(maxPages = 8),
                key(build = "2@2000"),
            )

        assertNotNull(runBlocking { cache.load(key, pages) })
        for (other in others) {
            assertNotEquals(key.text, other.text)
            assertNotEquals(key.fileName, other.fileName)
            assertNull("$other", runBlocking { cache.load(other, pages) })
        }
        // A miss leaves the stored analysis alone.
        assertNotNull(runBlocking { cache.load(key, pages) })
    }

    @Test
    fun aNewBuildOfTheAppAnalysesAfresh() {
        // As after an app update: same PDF, screen and config, but stored by the old build.
        val old = AnalysisCache(File(temp.root, "analyses"), "1@1000")
        val updated = AnalysisCache(File(temp.root, "analyses"), "1@2000")
        runBlocking { old.analyze(BookPages(14), "abc", aspect) }
        assertTrue(runBlocking { old.analyze(BookPages(14), "abc", aspect) }.restored)
        val pages = BookPages(14)

        val analysis = runBlocking { updated.analyze(pages, "abc", aspect) }

        assertFalse(analysis.restored)
        assertTrue(pages.renders.isNotEmpty())
        assertTrue(runBlocking { updated.analyze(BookPages(14), "abc", aspect) }.restored)
    }

    @Test
    fun theKeyDependsOnThePipelineAndFormatVersions() {
        val text = key().text
        assertTrue(text, "pipeline=${AnalysisCache.PIPELINE_VERSION}\n" in text)
        assertTrue(text, "format=${AnalysisCache.FORMAT_VERSION}\n" in text)
    }

    @Test
    fun aFileStoredForAnotherKeyIsNotTrusted() {
        // As if two keys' names collided, or a file were renamed: the key inside decides.
        val pages = BookPages(14)
        val stored = key(content = "one")
        val asked = key(content = "two")
        val cache = cache()
        runBlocking { cache.save(stored, analysed(pages, stored)) }
        File(cache.dirFile(), stored.fileName).renameTo(File(cache.dirFile(), asked.fileName))

        assertNull(runBlocking { cache.load(asked, pages) })
        assertEquals(emptyList<File>(), files())
    }

    @Test
    fun corruptFilesAreMissesAndDeleted() {
        val pages = BookPages(14)
        val key = key()
        val cache = cache()
        runBlocking { cache.save(key, analysed(pages, key)) }
        val file = File(cache.dirFile(), key.fileName)
        val good = file.readBytes()
        val damaged =
            listOf(
                good.copyOf(good.size / 2),
                good.copyOf(good.size - 1),
                good.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() },
                good.copyOf().also {
                    it[it.size - 6] = (it[it.size - 6] + 1).toByte()
                }, // the gzip CRC
                ByteArray(0),
                "not an analysis".toByteArray(),
            )

        for ((i, bytes) in damaged.withIndex()) {
            file.writeBytes(bytes)
            assertNull("damage $i", runBlocking { cache.load(key, pages) })
            assertFalse("damage $i", file.exists())
        }
        file.writeBytes(good)
        assertNotNull(runBlocking { cache.load(key, pages) })
    }

    @Test
    fun corruptContentInsideAValidFileIsAMiss() {
        // Valid gzip, valid key, but an index out of range: rejected when read, not when solving.
        val pages = BookPages(14)
        val key = key()
        val fresh = analysed(pages, key)
        val cache = cache()
        runBlocking { cache.save(key, fresh) }
        val file = File(cache.dirFile(), key.fileName)
        val raw = java.util.zip.GZIPInputStream(file.inputStream()).readBytes()
        // The first sampled page, after the magic, the key, the page count, density, glyph size
        // and the sample's length: page 99 of 14.
        assertNotNull(fresh.glyphPx)
        val offset = 4 + 2 + key.text.toByteArray().size + 4 + 4 + 9 + 4
        val bad = raw.copyOf().also { it[offset + 3] = 99 }
        file.outputStream().use { out -> java.util.zip.GZIPOutputStream(out).use { it.write(bad) } }

        assertNull(runBlocking { cache.load(key, pages) })
        assertFalse(file.exists())
    }

    @Test
    fun aStoredAnalysisForAPaperWithAnotherPageCountIsAMiss() {
        val key = key()
        val cache = cache()
        runBlocking { cache.save(key, analysed(BookPages(14), key)) }

        assertNull(runBlocking { cache.load(key, BookPages(15)) })
    }

    @Test
    fun analyzeComputesOnceThenRestores() {
        val cache = cache()
        val first = BookPages(14)
        val fresh = runBlocking { cache.analyze(first, "abc", aspect) }
        assertFalse(fresh.restored)
        assertTrue(first.renders.isNotEmpty())
        val second = BookPages(14)

        val again = runBlocking { cache.analyze(second, "abc", aspect) }

        assertTrue(again.restored)
        assertEquals(fresh.auto, again.auto)
        assertEquals(emptyList<BookPages.Render>(), second.renders.toList())
        // Another screen shape is analysed afresh, and kept alongside.
        val third = BookPages(14)
        assertFalse(runBlocking { cache.analyze(third, "abc", 0.6f) }.restored)
        assertEquals(2, files().size)
    }

    @Test
    fun withASaveScopeTheAnalysisIsReturnedBeforeItIsStored() {
        // Saves launched in the scope run only when its dispatcher is advanced.
        val dispatcher = StandardTestDispatcher()
        val job = SupervisorJob()
        val cache =
            AnalysisCache(
                File(temp.root, "analyses"),
                BUILD,
                saveScope = CoroutineScope(dispatcher + job),
            )

        val analysis = runBlocking { cache.analyze(BookPages(14), "abc", aspect) }

        assertFalse(analysis.restored)
        assertEquals(emptyList<File>(), files())
        val deadline = System.currentTimeMillis() + 10_000
        while (job.children.any { it.isActive }) {
            dispatcher.scheduler.advanceUntilIdle()
            check(System.currentTimeMillis() < deadline) { "The save never finished" }
            Thread.sleep(5)
        }
        assertEquals(1, files().size)
        assertTrue(runBlocking { cache.analyze(BookPages(14), "abc", aspect) }.restored)
    }

    @Test
    fun aCacheThatCantBeWrittenStillAnalyses() {
        // The directory is a file: nothing can be stored, but the analysis is still returned.
        val dir = temp.newFile("not-a-dir")
        val cache = AnalysisCache(dir, BUILD)
        val pages = BookPages(14)

        val analysis = runBlocking { cache.analyze(pages, "abc", aspect) }

        assertFalse(analysis.restored)
        assertFalse(runBlocking { cache.analyze(pages, "abc", aspect) }.restored)
    }

    @Test
    fun anAnalysisWithPagesThatFailedToRenderIsNotKept() {
        val text = List(10) { textPage().mask() }
        // Fine renders fail (as when memory is short). A page whose size can't even be read is
        // left out of the sample itself, so doesn't make an analysis incomplete.
        val pages = MaskPages(text, maxPixels = 100_000)
        val cache = cache()
        val analysis = runBlocking { cache.analyze(pages, "abc", aspect) }
        assertFalse(analysis.complete)
        assertEquals(emptyList<File>(), files())
        assertFalse(runBlocking { cache.analyze(pages, "abc", aspect) }.restored)
        assertTrue(runBlocking { analyzePaper(MaskPages(text), aspect) }.complete)
    }

    @Test
    fun theLeastRecentlyUsedAnalysesAreDroppedToFit() {
        val pages = BookPages(14)
        val analysis = analysed(pages, key())
        val probe = cache()
        runBlocking { probe.save(key("probe"), analysis) }
        val size = files().single().length()
        files().single().delete()
        // Room for two.
        val cache = cache(maxBytes = size * 2 + size / 2)
        val keys = listOf("a", "b", "c").map { key(it) }
        runBlocking { cache.save(keys[0], analysis) }
        File(cache.dirFile(), keys[0].fileName).setLastModified(1_000_000)
        runBlocking { cache.save(keys[1], analysis) }
        File(cache.dirFile(), keys[1].fileName).setLastModified(2_000_000)
        // Reading "a" makes it the most recently used.
        assertNotNull(runBlocking { cache.load(keys[0], pages) })

        runBlocking { cache.save(keys[2], analysis) }

        assertEquals(setOf(keys[0].fileName, keys[2].fileName), files().map { it.name }.toSet())
    }

    @Test
    fun theNewestAnalysisIsKeptEvenIfItAloneIsTooBig() {
        val cache = cache(maxBytes = 1)
        val pages = BookPages(14)
        val keys = listOf(key("a"), key("b"))
        for (k in keys) runBlocking { cache.save(k, analysed(pages, k)) }

        assertEquals(listOf(keys[1].fileName), files().map { it.name })
        assertNotNull(runBlocking { cache.load(keys[1], pages) })
    }

    @Test
    fun noTemporaryFilesAreLeftBehind() {
        val cache = cache()
        runBlocking { cache.analyze(BookPages(14), "abc", aspect) }

        assertEquals(files().map { it.name }, cache.dirFile().list()!!.toList())
    }

    @Test
    fun contentHashesTellFilesApartByContent() {
        val a = temp.newFile("a.pdf").apply { writeText("%PDF-1.4 one") }
        val b = temp.newFile("b.pdf").apply { writeText("%PDF-1.4 one") }
        val c = temp.newFile("c.pdf").apply { writeText("%PDF-1.4 two") }
        val big =
            temp.newFile("big.pdf").apply { writeBytes(ByteArray(300_000) { (it % 251).toByte() }) }

        assertEquals(AnalysisCache.contentHash(a), AnalysisCache.contentHash(b))
        assertNotEquals(AnalysisCache.contentHash(a), AnalysisCache.contentHash(c))
        assertEquals(64, AnalysisCache.contentHash(big).length)
        // SHA-256 of the empty file.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            AnalysisCache.contentHash(temp.newFile("empty.pdf")),
        )
    }

    @Test
    fun theConfigFingerprintIsStableAndSeesEveryWeight() {
        val fingerprint = config.fingerprint()
        assertEquals(fingerprint, CropConfig.DEFAULT.copy().fingerprint())
        // No identity hashes: stable from one run of the app to the next.
        assertFalse(fingerprint, '@' in fingerprint)
        assertTrue(fingerprint, "MarginStampRule" in fingerprint && "IsolatedMarks" in fingerprint)
        assertNotEquals(
            fingerprint,
            config.copy(weights = config.weights.copy(autoMargin = 0.02)).fingerprint(),
        )
        assertNotEquals(
            fingerprint,
            config.copy(text = config.text.copy(comfortable = 27.0)).fingerprint(),
        )
    }

    @Test
    fun onMissIsCalledOnlyWhenTheAnalysisMustBeComputed() {
        val cache = cache()
        var misses = 0
        runBlocking { cache.analyze(BookPages(14), "abc", aspect, onMiss = { misses++ }) }
        assertEquals(1, misses)

        assertTrue(
            runBlocking { cache.analyze(BookPages(14), "abc", aspect, onMiss = { misses++ }) }
                .restored
        )
        assertEquals(1, misses)

        // An unreadable file is a miss too.
        files().single().writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(
            runBlocking { cache.analyze(BookPages(14), "abc", aspect, onMiss = { misses++ }) }
                .restored
        )
        assertEquals(2, misses)
    }

    @Test
    fun cropsSolvedAtOtherScalesAreKeptByUpdate() {
        val cache = cache()
        val fresh = runBlocking { cache.analyze(BookPages(14), "abc", aspect) }
        val atSaved = runBlocking { fresh.cropsAt(0.9f) }
        val atOther = runBlocking { fresh.cropsAt(0.7f) }
        // Not kept until the reader asks: the file holds only the automatic crops so far.
        assertNull(runBlocking { cache.analyze(BookPages(14), "abc", aspect) }.solvedAt(0.9f))

        runBlocking { cache.update("abc", fresh) }
        val pages = BookPages(14)
        val restored = runBlocking { cache.analyze(pages, "abc", aspect) }

        assertTrue(restored.restored)
        // Read back bit for bit, without solving them again.
        assertEquals(atSaved, restored.solvedAt(0.9f))
        assertEquals(atOther, restored.solvedAt(0.7f))
        assertEquals(fresh.auto, restored.auto)
        assertNull(restored.solvedAt(0.8f))
        assertEquals(runBlocking { fresh.cropsAt(0.8f) }, runBlocking { restored.cropsAt(0.8f) })
        assertEquals(emptyList<BookPages.Render>(), pages.renders.toList())
        assertEquals(1, files().size)
    }

    @Test
    fun anIncompleteAnalysisIsNotKeptByUpdateEither() {
        val pages = MaskPages(List(10) { textPage().mask() }, maxPixels = 100_000)
        val cache = cache()
        val analysis = runBlocking { cache.analyze(pages, "abc", aspect) }
        runBlocking { analysis.cropsAt(0.9f) }

        runBlocking { cache.update("abc", analysis) }

        assertEquals(emptyList<File>(), files())
    }

    @Test
    fun aFileWithTheSameScaleSolvedTwiceIsNotTrusted() {
        val cache = cache()
        val key = key()
        val fresh = analysed(BookPages(14), key)
        runBlocking { cache.save(key, fresh) }
        val file = files().single()
        val raw = java.util.zip.GZIPInputStream(file.inputStream()).use { it.readBytes() }
        // The codec's bytes end with the automatic crops and a count of 0 other crops: claim one
        // other, a second copy of the automatic crops.
        val codec =
            java.io
                .ByteArrayOutputStream()
                .also { out ->
                    java.io.DataOutputStream(out).use { AnalysisCodec.write(fresh, it) }
                }
                .toByteArray()
        val autoCrops = codec.copyOfRange(codec.size - 4 - cropsSize(fresh.auto), codec.size - 4)
        fun store(other: ByteArray) {
            val bytes = raw.copyOfRange(0, raw.size - 4) + byteArrayOf(0, 0, 0, 1) + other
            File(file.path).outputStream().use { out ->
                java.util.zip.GZIPOutputStream(out).use { it.write(bytes) }
            }
        }
        // The same bytes at another scale read back: only the repeat is wrong.
        store(
            java.nio.ByteBuffer.allocate(4).putFloat(0.9f).array() +
                autoCrops.copyOfRange(4, autoCrops.size)
        )
        assertNotNull(runBlocking { cache.load(key, BookPages(14)) }?.solvedAt(0.9f))

        store(autoCrops)

        assertNull(runBlocking { cache.load(key, BookPages(14)) })
        assertEquals(emptyList<File>(), files())
    }

    /** The bytes AnalysisCodec writes for [crops]: scale, windows, page crops and total ink. */
    private fun cropsSize(crops: ModelCrops) =
        4 +
            4 +
            crops.windows.size * 12 +
            4 +
            crops.pages.size * (4 + 12 + 16 + 4 + 8 * 3 + 4 + 8 * 2) +
            8

    private fun AnalysisCache.dirFile() = File(temp.root, "analyses")

    private companion object {
        const val BUILD = "1@1000"
    }
}

/**
 * A two-sided book drawn at any resolution: lines of glyphs in a column on the left of even pages
 * and the right of odd ones, a running head and a page number on every page, and a wide figure on
 * every fifth. Pages in [fineFails] fail to render finer than the analysis masks.
 */
private class BookPages(override val pageCount: Int, private val fineFails: Set<Int> = emptySet()) :
    PageRenderer {
    data class Render(val index: Int, val width: Int, val height: Int, val fine: Boolean)

    val renders: MutableList<Render> = Collections.synchronizedList(mutableListOf())

    override suspend fun pageSize(index: Int) = PageSize(600f, 800f)

    override suspend fun renderPixels(index: Int, width: Int, height: Int): IntArray {
        val fine = width > 2 * ANALYSIS_LONG_SIDE
        if (fine && index in fineFails) throw java.io.IOException("Can't render page $index finely")
        renders += Render(index, width, height, fine)
        val sx = width / 600f
        val sy = height / 800f
        val out = IntArray(width * height) { MaskPages.WHITE }
        fun fill(l: Float, t: Float, r: Float, b: Float) {
            val x0 = (l * sx).toInt()
            val y0 = (t * sy).toInt()
            val x1 = (r * sx).toInt().coerceAtLeast(x0 + 1).coerceAtMost(width)
            val y1 = (b * sy).toInt().coerceAtLeast(y0 + 1).coerceAtMost(height)
            for (y in y0 until y1) for (x in x0 until x1) out[y * width + x] = MaskPages.BLACK
        }
        val left = if (index % 2 == 0) 40f else 260f
        fill(left, 40f, left + 200f, 47f) // running head
        for (line in 0 until 70) {
            var x = left
            val top = 80f + line * 9f
            while (x + 4f <= left + 300f) {
                fill(x, top, x + 3f, top + 5f)
                x += 5f
            }
        }
        if (index % 5 == 3) fill(20f, 300f, 580f, 360f) // a figure wider than the column
        fill(290f, 760f, 300f, 768f) // page number
        return out
    }
}
