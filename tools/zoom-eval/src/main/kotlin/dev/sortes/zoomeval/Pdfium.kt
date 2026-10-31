package dev.sortes.zoomeval

import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import dev.sortes.app.zoom.PageRenderer
import dev.sortes.app.zoom.PageSize
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException

/**
 * Reads and renders a PDF the way the tablet's PdfRenderer does: with pdfium, pinned to the
 * release nearest Android 15's (chromium/5731, installed by `setup-pdfium.sh`; see
 * notes/renderer-fidelity.md). Like Android's pdfClient (`Page::Render`) it draws onto a white
 * 32-bit bitmap with alpha, with `FPDF_REVERSE_BYTE_ORDER | FPDF_LCD_TEXT | FPDF_ANNOT`, scaled
 * from the page's size in whole points, which is what PdfRenderer reports (`Page::Width()` is an
 * int): an A4 page is 595 × 841, and its fractional edge falls off the bitmap as on the tablet.
 *
 * The document is opened for each call and closed after it, so nothing is held between renders.
 */
class PdfiumPdf(private val file: File) : PageRenderer {

    /** Each page's size in whole points, after its crop box and rotation, as PdfRenderer reports it. */
    val pageSizes: List<PageSize> = Pdfium.withDocument(file) { lib, doc ->
        (0 until lib.FPDF_GetPageCount(doc)).map { i ->
            Pdfium.withPage(lib, doc, i) { page -> wholePoints(lib.FPDF_GetPageWidth(page), lib.FPDF_GetPageHeight(page)) }
        }
    }

    override val pageCount: Int get() = pageSizes.size

    override suspend fun pageSize(index: Int): PageSize = pageSizes[index]

    override suspend fun renderPixels(index: Int, width: Int, height: Int): IntArray = argb(index, width, height)

    /** Page [index] on white paper, squashed into exactly [width] × [height], as opaque ARGB in row-major order. */
    fun argb(index: Int, width: Int, height: Int): IntArray {
        require(index in pageSizes.indices) { "${file.name} has no page ${index + 1}" }
        require(width > 0 && height > 0) { "Empty render: ${width}x$height" }
        val size = pageSizes[index]
        return Pdfium.withDocument(file) { lib, doc ->
            Pdfium.withPage(lib, doc, index) { page -> Pdfium.render(lib, page, width, height, width / size.width, height / size.height) }
        }
    }

    /** The page as an RGB image of exactly [width] × [height]. */
    fun renderImage(index: Int, width: Int, height: Int): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            setRGB(0, 0, width, height, argb(index, width, height), 0, width)
        }

    companion object {
        /** PdfRenderer's page size: pdfium's, truncated to whole points. */
        internal fun wholePoints(width: Double, height: Double): PageSize =
            PageSize(width.toInt().toFloat(), height.toInt().toFloat())
    }
}

/** The pinned pdfium library: where it is, loading it, and the calls [PdfiumPdf] makes. Not thread-safe, so every call holds [lock]. */
object Pdfium {
    /** The pdfium-binaries build zoom-eval is pinned to (`VERSION`'s BUILD). */
    const val BUILD = "5731"

    /** Where `setup-pdfium.sh` installs it by default. */
    val defaultDir: File get() = File(System.getProperty("user.home"), ".cache/sortes-zoom-eval/pdfium-chromium-$BUILD")

    /** `SORTES_PDFIUM`, if set, overrides [defaultDir]. */
    fun configuredDir(env: Map<String, String> = System.getenv()): File = env["SORTES_PDFIUM"]?.let(::File) ?: defaultDir

    /**
     * The library in [dir], checked to be the pinned build: an [IOException] that says how to
     * install it if it isn't there or is another version.
     */
    fun libraryIn(dir: File): File {
        val lib = File(dir, "lib/libpdfium.so")
        val version = File(dir, "VERSION")
        val hint = "run tools/zoom-eval/setup-pdfium.sh (or set SORTES_PDFIUM to its install dir)"
        if (!lib.isFile || !version.isFile) throw IOException("pdfium chromium/$BUILD isn't installed in $dir: $hint")
        val build = version.readLines().firstOrNull { it.startsWith("BUILD=") }?.removePrefix("BUILD=")?.trim()
        if (build != BUILD) throw IOException("$dir has pdfium build $build, not the pinned $BUILD: $hint")
        return lib
    }

    private val lock = Any()

    private val lib: Lib by lazy {
        val loaded = Native.load(libraryIn(configuredDir()).absolutePath, Lib::class.java)
        loaded.FPDF_InitLibrary()
        loaded
    }

    /** Opens [file], runs [block] on it and closes it, holding [lock] throughout. */
    fun <T> withDocument(file: File, block: (Lib, Pointer) -> T): T = synchronized(lock) {
        if (!file.isFile) throw IOException("No such PDF: $file")
        val doc = lib.FPDF_LoadDocument(file.absolutePath, null)
            ?: throw IOException("pdfium can't open $file: ${errorMessage(lib.FPDF_GetLastError().toLong())}")
        try {
            block(lib, doc)
        } finally {
            lib.FPDF_CloseDocument(doc)
        }
    }

    fun <T> withPage(lib: Lib, doc: Pointer, index: Int, block: (Pointer) -> T): T {
        val page = lib.FPDF_LoadPage(doc, index) ?: throw IOException("pdfium can't load page ${index + 1}")
        try {
            return block(page)
        } finally {
            lib.FPDF_ClosePage(page)
        }
    }

    /** [page] drawn as pdfClient draws it, scaled by [sx] × [sy] into a [width] × [height] white bitmap; opaque ARGB. */
    fun render(lib: Lib, page: Pointer, width: Int, height: Int, sx: Float, sy: Float): IntArray {
        val bitmap = lib.FPDFBitmap_Create(width, height, 1) ?: throw IOException("pdfium can't allocate a ${width}x$height bitmap")
        try {
            lib.FPDFBitmap_FillRect(bitmap, 0, 0, width, height, NativeLong(0xFFFFFFFFL))
            val matrix = floatArrayOf(sx, 0f, 0f, sy, 0f, 0f)
            val clip = floatArrayOf(0f, 0f, width.toFloat(), height.toFloat())
            lib.FPDF_RenderPageBitmapWithMatrix(bitmap, page, matrix, clip, RENDER_FLAGS)
            val stride = lib.FPDFBitmap_GetStride(bitmap)
            val bytes = lib.FPDFBitmap_GetBuffer(bitmap).getByteArray(0, stride * height)
            return rgbaToOpaqueArgb(bytes, width, height, stride)
        } finally {
            lib.FPDFBitmap_Destroy(bitmap)
        }
    }

    /** pdfClient's flags for RENDER_MODE_FOR_DISPLAY. LCD text is off anyway on a bitmap with alpha. */
    const val RENDER_FLAGS = 0x10 or 0x02 or 0x01 // FPDF_REVERSE_BYTE_ORDER | FPDF_LCD_TEXT | FPDF_ANNOT

    /** RGBA rows of [stride] bytes (as `FPDF_REVERSE_BYTE_ORDER` writes them) to opaque ARGB, as `Bitmap.getPixels` gives. */
    internal fun rgbaToOpaqueArgb(bytes: ByteArray, width: Int, height: Int, stride: Int): IntArray {
        require(stride >= width * 4 && bytes.size >= stride * height) { "Short bitmap: ${bytes.size} bytes for ${width}x$height, stride $stride" }
        return IntArray(width * height) { i ->
            val o = (i / width) * stride + (i % width) * 4
            val r = bytes[o].toInt() and 0xFF
            val g = bytes[o + 1].toInt() and 0xFF
            val b = bytes[o + 2].toInt() and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** `FPDF_GetLastError`'s codes. */
    internal fun errorMessage(code: Long): String = when (code) {
        1L -> "unknown error"
        2L -> "file not found or could not be opened"
        3L -> "not a PDF or corrupted"
        4L -> "password required or incorrect"
        5L -> "unsupported security scheme"
        6L -> "page not found or content error"
        else -> "error $code"
    }

    /** The pdfium calls used, mapped by JNA. */
    @Suppress("FunctionName")
    interface Lib : com.sun.jna.Library {
        fun FPDF_InitLibrary()
        fun FPDF_LoadDocument(path: String, password: String?): Pointer?
        fun FPDF_GetLastError(): NativeLong
        fun FPDF_CloseDocument(doc: Pointer)
        fun FPDF_GetPageCount(doc: Pointer): Int
        fun FPDF_LoadPage(doc: Pointer, index: Int): Pointer?
        fun FPDF_ClosePage(page: Pointer)
        fun FPDF_GetPageWidth(page: Pointer): Double
        fun FPDF_GetPageHeight(page: Pointer): Double
        fun FPDFBitmap_Create(width: Int, height: Int, alpha: Int): Pointer?
        fun FPDFBitmap_FillRect(bitmap: Pointer, left: Int, top: Int, width: Int, height: Int, color: NativeLong)
        fun FPDF_RenderPageBitmapWithMatrix(bitmap: Pointer, page: Pointer, matrix: FloatArray, clip: FloatArray, flags: Int)
        fun FPDFBitmap_GetStride(bitmap: Pointer): Int
        fun FPDFBitmap_GetBuffer(bitmap: Pointer): Pointer
        fun FPDFBitmap_Destroy(bitmap: Pointer)
    }
}
