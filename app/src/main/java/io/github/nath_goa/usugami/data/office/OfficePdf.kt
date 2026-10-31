package io.github.nath_goa.usugami.data.office

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.scale
import java.io.Closeable
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pages drawn with Android's PDF backend, which embeds just the letters used from the phone's fonts
 * and keeps text real, selectable text. Pages are drawn in layout units ([U] per point).
 *
 * The backend holds every page, with its pictures, in memory until the document is written. So
 * once the pictures drawn add up to [IMAGE_BUDGET] (or after [PAGES_PER_PART] pages), the pages so
 * far are written to a part file and the next pages start a new document; the parts are joined
 * afterwards.
 */
internal class PagedPdf(private val dir: File) : Closeable {
    private var document: PdfDocument? = null
    private var page: PdfDocument.Page? = null
    private val parts = ArrayList<File>()
    private val bitmaps = ArrayList<Bitmap>()
    private var imageBytes = 0L
    private var pagesInPart = 0
    private val partListeners = ArrayList<() -> Unit>()

    var pageCount = 0
        private set

    /** Starts a page [width] × [height] points big, and returns its canvas, in layout units. */
    fun startPage(width: Float, height: Float): Canvas {
        check(page == null) { "A page is already open" }
        val doc = document ?: PdfDocument().also { document = it }
        val info = PdfDocument.PageInfo.Builder(max(1, width.roundToInt()), max(1, height.roundToInt()), pageCount + 1).create()
        return doc.startPage(info).also { page = it }.canvas.apply { scale(1 / U, 1 / U) }
    }

    fun finishPage() {
        val current = page ?: return
        document!!.finishPage(current)
        page = null
        pageCount++
        pagesInPart++
        if (imageBytes > IMAGE_BUDGET || pagesInPart >= PAGES_PER_PART) writePart()
    }

    /** [bitmap] is drawn on the open document's pages: it stays until the document is written. */
    fun keep(bitmap: Bitmap) {
        bitmaps += bitmap
        imageBytes += bitmap.allocationByteCount
    }

    /** [listener] runs when a part has been written, and the bitmaps drawn so far are freed. */
    fun onPartWritten(listener: () -> Unit) {
        partListeners += listener
    }

    /** Writes the last part, and returns all the part files in order. */
    fun finish(): List<File> {
        writePart()
        return parts
    }

    private fun writePart() {
        val doc = document ?: return
        val file = File(dir, "part-${parts.size + 1}.pdf")
        try {
            file.outputStream().buffered().use(doc::writeTo)
        } finally {
            doc.close()
            document = null
            freeBitmaps()
        }
        parts += file
        pagesInPart = 0
    }

    private fun freeBitmaps() {
        bitmaps.forEach(Bitmap::recycle)
        bitmaps.clear()
        imageBytes = 0
        partListeners.forEach { it() }
    }

    override fun close() {
        // After an error: whatever was drawn goes, the part files are the caller's to delete.
        page = null
        document?.close()
        document = null
        freeBitmaps()
    }

    private companion object {
        const val IMAGE_BUDGET = 48L * 1024 * 1024
        const val PAGES_PER_PART = 400
    }
}

/**
 * Pictures from the document, decoded no bigger than they are shown at [DPI], so a phone photo in a
 * small frame doesn't take a phone photo's memory. Each is decoded once per part file.
 */
internal class OfficeImages(private val open: (String) -> InputStream?, private val pdf: PagedPdf, private val check: () -> Unit) {
    private val decoded = HashMap<String, Bitmap?>()

    init {
        pdf.onPartWritten { decoded.clear() }
    }

    /**
     * The picture in part [path], to be shown [width] × [height] points big; null when Android
     * can't decode it (such as EMF and WMF drawings, or CMYK JPEGs).
     */
    fun bitmap(path: String, width: Float, height: Float): Bitmap? {
        val targetW = (width / 72f * DPI).roundToInt().coerceIn(1, MAX_SIDE)
        val targetH = (height / 72f * DPI).roundToInt().coerceIn(1, MAX_SIDE)
        val key = "$path@$targetW\u00D7$targetH"
        if (decoded.containsKey(key)) return decoded[key]
        val bitmap = decode(path, targetW, targetH)
        check()
        if (bitmap != null) pdf.keep(bitmap)
        decoded[key] = bitmap
        return bitmap
    }

    private fun decode(path: String, targetW: Int, targetH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (open(path) ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / (sample * 2) >= targetW && h / (sample * 2) >= targetH) sample *= 2
        while ((w.toLong() / sample) * (h.toLong() / sample) > MAX_PIXELS) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = try {
            (open(path) ?: return null).use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        } catch (_: OutOfMemoryError) {
            return null
        }
        // Still well over the size shown: scaled down the rest of the way.
        val scale = maxOf(targetW.toFloat() / bitmap.width, targetH.toFloat() / bitmap.height)
        if (scale >= 0.8f) return bitmap
        val smaller = bitmap.scale(max(1, (bitmap.width * scale).roundToInt()), max(1, (bitmap.height * scale).roundToInt()))
        if (smaller !== bitmap) bitmap.recycle()
        return smaller
    }

    private companion object {
        /** Enough for print; PDF compression brings large pictures down to 150 dpi afterwards. */
        const val DPI = 200f
        const val MAX_SIDE = 2400
        const val MAX_PIXELS = 12_000_000L
    }
}

/**
 * A picture from the document, shown [width] × [height] points big, with the share of each side in
 * [crop] (left, top, right, bottom) cut off. It is decoded when drawn, so a part file that has been
 * written and freed doesn't take it along; one Android can't decode is drawn as a grey box.
 */
internal class ImagePicture(
    private val images: OfficeImages,
    private val path: String,
    private val width: Float,
    private val height: Float,
    private val crop: FloatArray?,
) : Picture {
    override fun draw(canvas: Canvas, rect: RectF) {
        val shownX = crop?.let { 1f - it[0] - it[2] }?.takeIf { it > 0.05f } ?: 1f
        val shownY = crop?.let { 1f - it[1] - it[3] }?.takeIf { it > 0.05f } ?: 1f
        val bitmap = images.bitmap(path, width / shownX, height / shownY) ?: return StandInPicture(null).draw(canvas, rect)
        val source = crop?.let { c ->
            val w = bitmap.width
            val h = bitmap.height
            Rect(
                (c[0] * w).roundToInt().coerceIn(0, w - 1), (c[1] * h).roundToInt().coerceIn(0, h - 1),
                (w - c[2] * w).roundToInt().coerceIn(1, w), (h - c[3] * h).roundToInt().coerceIn(1, h),
            ).takeIf { it.width() > 0 && it.height() > 0 }
        }
        BitmapPicture(bitmap, source).draw(canvas, rect)
    }
}
