package dev.sortes.app.zoom

import java.io.DataInput
import java.io.DataInputStream
import java.io.EOFException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * A [DataInput] over a byte array, reading what a [java.io.DataOutputStream] wrote. Unlike a
 * [DataInputStream] over a buffered stream, which makes a synchronized call for every byte of
 * every int, it reads straight from the array, and reads arrays of values in one call
 * ([readInts], [readDoubles]): [AnalysisCache] reads millions of values on each open, and in a
 * process just started, the app's own code runs interpreted, so a call per value is slow.
 */
internal class BytesInput(bytes: ByteArray) : DataInput {
    private val buffer = ByteBuffer.wrap(bytes) // Big-endian, as DataOutput writes.

    /** Bytes not read yet. */
    val remaining: Int get() = buffer.remaining()

    private inline fun <T> read(block: ByteBuffer.() -> T): T = try {
        buffer.block()
    } catch (e: BufferUnderflowException) {
        throw EOFException()
    }

    override fun readFully(b: ByteArray) = readFully(b, 0, b.size)

    override fun readFully(b: ByteArray, off: Int, len: Int) {
        read { get(b, off, len) }
    }

    override fun skipBytes(n: Int): Int {
        val skipped = n.coerceIn(0, buffer.remaining())
        buffer.position(buffer.position() + skipped)
        return skipped
    }

    override fun readBoolean(): Boolean = readByte().toInt() != 0
    override fun readByte(): Byte = read { get() }
    override fun readUnsignedByte(): Int = readByte().toInt() and 0xff
    override fun readShort(): Short = read { short }
    override fun readUnsignedShort(): Int = readShort().toInt() and 0xffff
    override fun readChar(): Char = read { char }
    override fun readInt(): Int = read { int }
    override fun readLong(): Long = read { long }
    override fun readFloat(): Float = read { float }
    override fun readDouble(): Double = read { double }

    /** [n] ints, as [readInt] would read them one by one, in one call. */
    fun readInts(n: Int): IntArray {
        val values = IntArray(n)
        read { asIntBuffer().get(values) }
        buffer.position(buffer.position() + 4 * n)
        return values
    }

    /** [n] doubles, as [readDouble] would read them one by one, in one call. */
    fun readDoubles(n: Int): DoubleArray {
        val values = DoubleArray(n)
        read { asDoubleBuffer().get(values) }
        buffer.position(buffer.position() + 8 * n)
        return values
    }

    override fun readLine(): String = throw UnsupportedOperationException()

    override fun readUTF(): String = DataInputStream.readUTF(this)
}

/**
 * The bytes gzip'd in [gzip], one member as [java.util.zip.GZIPOutputStream] writes it, inflated
 * straight into an array of the size its trailer gives, and checked against the trailer's CRC
 * and size. Throws [java.io.IOException] for anything else.
 */
internal fun inflateGzip(gzip: ByteArray): ByteArray {
    if (gzip.size < 18 || gzip[0] != 0x1f.toByte() || gzip[1] != 0x8b.toByte() || gzip[2] != 8.toByte()) {
        throw java.io.IOException("Not gzip")
    }
    // GZIPOutputStream writes a bare 10-byte header: no name, comment or extra field.
    if (gzip[3].toInt() != 0) throw java.io.IOException("Unexpected gzip header flags")
    val trailer = ByteBuffer.wrap(gzip, gzip.size - 8, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val crc = trailer.int.toLong() and 0xffffffffL
    val size = trailer.int.toLong() and 0xffffffffL
    if (size > MAX_INFLATED) throw java.io.IOException("Too big: $size bytes")
    val out = ByteArray(size.toInt())
    val inflater = java.util.zip.Inflater(true)
    try {
        inflater.setInput(gzip, 10, gzip.size - 18)
        var n = 0
        while (n < out.size) {
            val got = inflater.inflate(out, n, out.size - n)
            if (got == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
            n += got
        }
        // The data must end exactly where the trailer says, with nothing after it.
        if (n == out.size && !inflater.finished() && inflater.inflate(ByteArray(1)) != 0) {
            throw java.io.IOException("Longer than the trailer says")
        }
        if (n != out.size || !inflater.finished() || inflater.remaining != 0) {
            throw java.io.IOException("Inflated size doesn't match the trailer")
        }
    } catch (e: java.util.zip.DataFormatException) {
        throw java.io.IOException(e)
    } finally {
        inflater.end()
    }
    val check = java.util.zip.CRC32().apply { update(out) }
    if (check.value != crc) throw java.io.IOException("Bad CRC")
    return out
}

/** Largest analysis inflated: far beyond the few megabytes of a long book's. */
private const val MAX_INFLATED = 256L shl 20
