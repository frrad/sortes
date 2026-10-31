package dev.sortes.app.zoom

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BytesInputTest {

    @Test
    fun readsWhatDataOutputWrote() {
        val bytes =
            ByteArrayOutputStream()
                .also { buffer ->
                    DataOutputStream(buffer).use { out ->
                        out.writeInt(-7)
                        out.writeUTF("key=ü\n✓")
                        out.writeBoolean(true)
                        out.writeByte(200)
                        out.writeShort(-3)
                        out.writeChar('x'.code)
                        out.writeLong(Long.MIN_VALUE + 5)
                        out.writeFloat(0.805f)
                        out.writeDouble(Math.PI)
                        out.write(byteArrayOf(1, 2, 3))
                    }
                }
                .toByteArray()
        val input = BytesInput(bytes)

        assertEquals(-7, input.readInt())
        assertEquals("key=ü\n✓", input.readUTF())
        assertEquals(true, input.readBoolean())
        assertEquals(200, input.readUnsignedByte())
        assertEquals(-3, input.readShort().toInt())
        assertEquals('x', input.readChar())
        assertEquals(Long.MIN_VALUE + 5, input.readLong())
        assertEquals(0.805f.toRawBits(), input.readFloat().toRawBits())
        assertEquals(Math.PI.toRawBits(), input.readDouble().toRawBits())
        assertEquals(3, input.remaining)
        assertArrayEquals(byteArrayOf(1, 2, 3), ByteArray(3).also { input.readFully(it) })
        assertEquals(0, input.remaining)
    }

    @Test
    fun readingPastTheEndIsAnEofException() {
        val input = BytesInput(byteArrayOf(0, 0, 1))
        assertThrows(EOFException::class.java) { input.readInt() }
        assertThrows(EOFException::class.java) { BytesInput(ByteArray(2)).readFully(ByteArray(3)) }
        assertEquals(2, BytesInput(ByteArray(2)).skipBytes(5))
    }

    private fun gzip(data: ByteArray) =
        ByteArrayOutputStream()
            .also { out -> GZIPOutputStream(out).use { it.write(data) } }
            .toByteArray()

    private val data = Random(1).let { r -> ByteArray(200_000) { (r.nextInt(4) * 60).toByte() } }

    @Test
    fun inflatesWhatGzipOutputStreamWrote() {
        assertArrayEquals(data, inflateGzip(gzip(data)))
        assertArrayEquals(ByteArray(0), inflateGzip(gzip(ByteArray(0))))
    }

    @Test
    fun damagedGzipIsAnIoException() {
        val good = gzip(data)
        val cases =
            mapOf(
                "truncated" to good.copyOf(good.size - 20),
                "trailing bytes" to good + byteArrayOf(0),
                "a second member" to good + gzip(byteArrayOf(1)),
                "not gzip" to ByteArray(40),
                "flipped data" to
                    good.copyOf().also {
                        it[good.size / 2] = (it[good.size / 2].toInt() xor 0x10).toByte()
                    },
                "wrong crc" to
                    good.copyOf().also { it[good.size - 8] = (it[good.size - 8] + 1).toByte() },
                "size too small" to
                    good.copyOf().also { it[good.size - 4] = (it[good.size - 4] - 1).toByte() },
                "size too big" to
                    good.copyOf().also { it[good.size - 4] = (it[good.size - 4] + 1).toByte() },
                "absurd size" to good.copyOf().also { it[good.size - 1] = 0x7f },
            )
        for ((name, bytes) in cases) {
            assertThrows(name, IOException::class.java) { inflateGzip(bytes) }
        }
    }
}
