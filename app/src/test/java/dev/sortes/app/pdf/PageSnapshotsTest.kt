package dev.sortes.app.pdf

import dev.sortes.app.zoom.CropConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class PageSnapshotsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dir get() = File(temp.root, "pages")

    private fun store(build: String = BUILD, maxBytes: Long = PageSnapshots.DEFAULT_MAX_BYTES) =
        PageSnapshots(dir, build, maxBytes)

    private fun key(file: String = "/papers/a.pdf|1000|5", index: Int = 0, scale: Float? = null, config: CropConfig = CropConfig.DEFAULT) =
        PageSnapshots.Key(file, config, index, scale)

    /** A page's pixels: white with [seed]-random dark marks, opaque, like a rendered page. */
    private fun pixels(width: Int, height: Int, seed: Int = 1): ByteArray {
        val random = Random(seed)
        val bytes = ByteArray(width * height * 4) { -1 }
        repeat(width * height / 20) {
            val at = random.nextInt(width * height) * 4
            val grey = random.nextInt(256).toByte()
            bytes[at] = grey
            bytes[at + 1] = grey
            bytes[at + 2] = random.nextInt(256).toByte()
        }
        return bytes
    }

    private fun files() = dir.listFiles()?.toList().orEmpty()

    @Test
    fun aStoredPageIsReadBackExactly() {
        // A height that doesn't split evenly into bands.
        val width = 37
        val height = 53
        val page = pixels(width, height)
        runBlocking { store().save(1, key(), width, height, page) }

        val read = runBlocking { store().load(1, key()) }!!

        assertEquals(width, read.width)
        assertEquals(height, read.height)
        assertEquals(0, read.index)
        assertArrayEquals(page, read.pixels)
    }

    @Test
    fun pagesAreStoredPerPaperAndReplaced() {
        val store = store()
        runBlocking {
            store.save(1, key(), 10, 10, pixels(10, 10, seed = 1))
            store.save(2, key(), 10, 10, pixels(10, 10, seed = 2))
            store.save(1, key(), 12, 8, pixels(12, 8, seed = 3))
        }

        assertArrayEquals(pixels(12, 8, seed = 3), runBlocking { store.load(1, key()) }!!.pixels)
        assertArrayEquals(pixels(10, 10, seed = 2), runBlocking { store.load(2, key()) }!!.pixels)
        assertNull(runBlocking { store.load(3, key()) })
        assertEquals(2, files().size)
    }

    @Test
    fun aPageStoredForAnythingElseIsAMiss() {
        runBlocking { store().save(1, key(), 10, 10, pixels(10, 10)) }

        // A new app build (an update), the PDF changed, another page, zoom or config.
        assertNull(runBlocking { store(build = "2@later").load(1, key()) })
        assertNull(runBlocking { store().load(1, key(file = "/papers/a.pdf|1001|5")) })
        assertNull(runBlocking { store().load(1, key(file = "/papers/a.pdf|1000|6")) })
        assertNull(runBlocking { store().load(1, key(index = 1)) })
        assertNull(runBlocking { store().load(1, key(scale = 0.8f)) })
        assertNull(runBlocking { store().load(1, key(config = CropConfig.DEFAULT.copy(name = "other"))) })
        // ...but still there for what it was stored for.
        assertNotNull(runBlocking { store().load(1, key()) })
    }

    @Test
    fun aZoomIsMatchedExactly() {
        runBlocking { store().save(1, key(scale = 0.8f), 10, 10, pixels(10, 10)) }

        assertNotNull(runBlocking { store().load(1, key(scale = 0.8f)) })
        assertNull(runBlocking { store().load(1, key(scale = Math.nextUp(0.8f))) })
        assertNull(runBlocking { store().load(1, key(scale = null)) })
    }

    @Test
    fun aFilesIdentityChangesWhenItsWritten() {
        val pdf = temp.newFile("paper.pdf").apply { writeBytes(ByteArray(100)) }
        pdf.setLastModified(1_000_000)
        val before = PageSnapshots.fileIdentity(pdf)
        assertEquals(before, PageSnapshots.fileIdentity(pdf))

        pdf.writeBytes(ByteArray(101))
        pdf.setLastModified(1_000_000)
        val resized = PageSnapshots.fileIdentity(pdf)
        pdf.writeBytes(ByteArray(101))
        pdf.setLastModified(2_000_000)
        val touched = PageSnapshots.fileIdentity(pdf)

        assertNotEquals(before, resized)
        assertNotEquals(resized, touched)
        assertNotEquals(touched, PageSnapshots.fileIdentity(File(temp.root, "other.pdf")))
    }

    @Test
    fun damagedFilesAreMissesAndDeleted() {
        val page = pixels(30, 40)
        val corruptions = mapOf<String, (ByteArray) -> ByteArray>(
            "truncated" to { it.copyOf(it.size - 7) },
            "cut in the header" to { it.copyOf(20) },
            "empty" to { ByteArray(0) },
            "a pixel byte flipped" to { b -> b.copyOf().also { it[it.size - 30] = (it[it.size - 30].toInt() xor 0x5a).toByte() } },
            "trailing bytes" to { it + ByteArray(5) },
            "not a page" to { "%PDF-1.4 not a page".toByteArray() },
        )
        for ((what, corrupt) in corruptions) {
            val store = store()
            runBlocking { store.save(1, key(), 30, 40, page) }
            val file = files().single()
            file.writeBytes(corrupt(file.readBytes()))

            assertNull(what, runBlocking { store.load(1, key()) })
            assertFalse(what, file.exists())
        }
    }

    @Test
    fun aDamagedBandIsCaughtByItsCrc() {
        // The pixels inflate fine, but not to what was stored: the band's CRC says so.
        val store = store()
        runBlocking { store.save(1, key(), 30, 40, pixels(30, 40)) }
        val file = files().single()
        val bytes = file.readBytes()
        val crcAt = crcOffset(bytes)
        bytes[crcAt] = (bytes[crcAt].toInt() xor 1).toByte()
        file.writeBytes(bytes)

        assertNull(runBlocking { store.load(1, key()) })
        assertFalse(file.exists())
    }

    /** Where the first band's CRC is in a stored page: after its row count, after the band count. */
    private fun crcOffset(bytes: ByteArray): Int {
        val input = java.io.DataInputStream(bytes.inputStream())
        input.readInt()
        input.readUTF()
        input.readInt()
        input.readInt()
        input.readInt()
        input.readInt()
        return bytes.size - input.available()
    }

    @Test
    fun storingTheWrongNumberOfPixelsFails() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store().save(1, key(), 10, 10, ByteArray(399)) }
        }
        assertEquals(emptyList<File>(), files())
    }

    @Test
    fun theLeastRecentlyUsedPagesGoFirst() {
        val one = runBlocking {
            val probe = store()
            probe.save(9, key(), 64, 64, pixels(64, 64, seed = 9))
            files().single().length().also { probe.delete(9) }
        }
        // Room for two pages.
        val store = store(maxBytes = one * 2 + one / 2)
        runBlocking {
            store.save(1, key(), 64, 64, pixels(64, 64, seed = 1))
            File(dir, "paper-1.page").setLastModified(1_000_000)
            store.save(2, key(), 64, 64, pixels(64, 64, seed = 2))
            File(dir, "paper-2.page").setLastModified(2_000_000)
            // Reading paper 1 makes it the most recently used.
            assertNotNull(store.load(1, key()))
            store.save(3, key(), 64, 64, pixels(64, 64, seed = 3))
        }

        assertEquals(setOf("paper-1.page", "paper-3.page"), files().map { it.name }.toSet())
    }

    @Test
    fun noTemporaryFilesAreLeft() {
        runBlocking { store().save(1, key(), 20, 20, pixels(20, 20)) }

        assertEquals(listOf("paper-1.page"), files().map { it.name })
    }

    private companion object {
        const val BUILD = "1@1000"
    }
}
