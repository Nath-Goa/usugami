package io.github.nath_goa.usugami.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import java.io.File
import java.util.Locale

/**
 * Draws a page with Android's PDF renderer exactly where it is, whatever the renderer's version.
 *
 * Android gives a page's size in whole points (595 × 841 for an A4 page, 595.28 × 841.89), and
 * renderers differ in what they do with that. Earlier ones (Android 12 and 15, for two) draw a page
 * given no transform filling the bitmap as it is, and apply a transform to the page squeezed to
 * its whole-point size. Android 17's does the opposite: given no transform, the whole-point page
 * fills the bitmap, which stretches what is drawn by up to a point, and a transform applies to the
 * page as it is. A place worked out on the page itself (a box to black out, text found, an item to
 * add) would then be up to a point off what the picture shows there. Which way the renderer works
 * is found once, on a tiny page made for it: it is part of a system module, which updates.
 */
internal object PageRendering {
    /** Whether a transform given to the renderer applies to the page as it is, not squeezed to whole points. */
    private val transformsWholePage: Boolean by lazy { probe() }

    /** Draws [page], [shownWidth] × [shownHeight] points as shown, filling [bitmap] exactly. */
    fun render(page: PdfRenderer.Page, bitmap: Bitmap, shownWidth: Float, shownHeight: Float) {
        val transform = if (transformsWholePage) Matrix().apply { setScale(bitmap.width / shownWidth, bitmap.height / shownHeight) } else null
        page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    }

    /**
     * Draws the part of [page], [shownWidth] × [shownHeight] points as shown, that [bitmap] covers
     * when the page is drawn [scale] pixels to the point and the bitmap's top-left corner is ([left],
     * [top]) pixels into it. So a point (x, y) of the page as shown, from its top-left corner, lands
     * on pixel (x·scale − left, y·scale − top) with either kind of renderer.
     */
    fun renderPart(page: PdfRenderer.Page, bitmap: Bitmap, shownWidth: Float, shownHeight: Float, scale: Float, left: Float, top: Float) {
        // What a transform starts from: the page as it is, or the page squeezed to whole points (see the class's KDoc).
        val baseWidth = if (transformsWholePage) shownWidth else page.width.toFloat()
        val baseHeight = if (transformsWholePage) shownHeight else page.height.toFloat()
        val transform = Matrix().apply {
            setScale(scale * shownWidth / baseWidth, scale * shownHeight / baseHeight)
            postTranslate(-left, -top)
        }
        page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    }

    /**
     * A page 10.5 points square, black all over, drawn twice its size: as it is, it covers the
     * bitmap's last row and column of pixels; squeezed to 10 points first, it stops one pixel short.
     */
    private fun probe(): Boolean = try {
        // In the app's cache: java.io.tmpdir is set to it.
        val file = File.createTempFile("page-probe", ".pdf")
        try {
            file.writeBytes(probePdf())
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    renderer.openPage(0).use { page ->
                        val bitmap = createBitmap(21, 21)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, Matrix().apply { setScale(2f, 2f) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            Color.red(bitmap[20, 20]) < 128
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        } finally {
            file.delete()
        }
    } catch (_: Exception) {
        // As Android has always drawn a page without a transform.
        false
    }

    private fun probePdf(): ByteArray {
        val content = "0 g 0 0 10.5 10.5 re f"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 10.5 10.5] /Resources << >> /Contents 4 0 R >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
        )
        val pdf = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { i, body -> pdf.length.also { pdf.append("${i + 1} 0 obj\n$body\nendobj\n") } }
        val xref = pdf.length
        pdf.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) pdf.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset))
        pdf.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return pdf.toString().toByteArray(Charsets.US_ASCII)
    }
}
