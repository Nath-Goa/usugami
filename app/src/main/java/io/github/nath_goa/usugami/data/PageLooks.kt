package io.github.nath_goa.usugami.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import io.github.nath_goa.usugami.core.BitImage
import io.github.nath_goa.usugami.core.CropQuad
import io.github.nath_goa.usugami.core.DocumentLooks
import io.github.nath_goa.usugami.core.GrayImage
import io.github.nath_goa.usugami.core.LookAdjustments
import io.github.nath_goa.usugami.core.PageCrop
import io.github.nath_goa.usugami.core.PageLook
import io.github.nath_goa.usugami.core.PixelSource

/** A page's [PageLook] and [LookAdjustments] on Android bitmaps (the pixel work is [DocumentLooks]'). Blocking. */
object PageLooks {

    /** [bitmap]'s pixels a row at a time. */
    fun pixels(bitmap: Bitmap): PixelSource = object : PixelSource {
        override val width get() = bitmap.width
        override val height get() = bitmap.height
        override fun readRow(y: Int, out: IntArray) = bitmap.getPixels(out, 0, width, 0, y, width, 1)
    }

    /**
     * [bitmap] as [look] shows it with [adjustments], as a new opaque bitmap of the same size; [bitmap]
     * is left as it is.
     */
    fun applied(
        bitmap: Bitmap,
        look: PageLook,
        adjustments: LookAdjustments = LookAdjustments.NONE,
        checkCancelled: () -> Unit = {},
    ): Bitmap {
        val width = bitmap.width
        val out = createBitmap(width, bitmap.height)
        val row = IntArray(width)
        when {
            look.isColour -> DocumentLooks.colour(pixels(bitmap), look, adjustments, { y, pixels -> out.setPixels(pixels, 0, width, 0, y, width, 1) }, checkCancelled)
            look == PageLook.GRAYSCALE -> {
                val gray = DocumentLooks.grayscale(pixels(bitmap), adjustments, checkCancelled)
                for (y in 0 until gray.height) {
                    for (x in 0 until width) row[x] = grayArgb(gray[x, y])
                    out.setPixels(row, 0, width, 0, y, width, 1)
                }
            }
            else -> {
                val bits = DocumentLooks.blackAndWhite(pixels(bitmap), look, adjustments, checkCancelled)
                for (y in 0 until bits.height) {
                    for (x in 0 until width) row[x] = if (bits.isBlack(x, y)) BLACK else WHITE
                    out.setPixels(row, 0, width, 0, y, width, 1)
                }
            }
        }
        out.setHasAlpha(false)
        return out
    }

    /**
     * Gives the mutable [bitmap] a colour [look] ([PageLook.isColour]) with [adjustments], in place: as
     * [applied] makes it, without a second picture as large.
     */
    fun colourInPlace(bitmap: Bitmap, look: PageLook, adjustments: LookAdjustments, checkCancelled: () -> Unit = {}) {
        require(bitmap.isMutable) { "The bitmap can't be changed" }
        val width = bitmap.width
        // Each row is read before it is written (see DocumentLooks.colour).
        DocumentLooks.colour(pixels(bitmap), look, adjustments, { y, pixels -> bitmap.setPixels(pixels, 0, width, 0, y, width, 1) }, checkCancelled)
        bitmap.setHasAlpha(false)
    }

    /**
     * The page inside [quad] (corners on [bitmap], in a page's order) straightened onto a rectangle the
     * page's shape, at most [maxSide] pixels on its long side, as [PageCropper.crop] makes it; null
     * when the corners can't be mapped onto a rectangle.
     */
    fun straightened(bitmap: Bitmap, quad: CropQuad, maxSide: Int): Bitmap? {
        val (width, height) = PageCrop.outputSize(quad, bitmap.width, bitmap.height, maxSide)
        val matrix = Matrix()
        val source = FloatArray(8) { i -> if (i % 2 == 0) quad[i / 2].x * bitmap.width else quad[i / 2].y * bitmap.height }
        val w = width.toFloat()
        val h = height.toFloat()
        if (!matrix.setPolyToPoly(source, 0, floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0, 4)) return null
        val page = createBitmap(width, height)
        page.eraseColor(Color.WHITE)
        Canvas(page).drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        return page
    }

    /** [bitmap] in grey with the [PageLook.GRAYSCALE] look and [adjustments]. */
    fun gray(bitmap: Bitmap, adjustments: LookAdjustments, checkCancelled: () -> Unit = {}): GrayImage =
        DocumentLooks.grayscale(pixels(bitmap), adjustments, checkCancelled)

    /** [bitmap] in black and white with [look] ([PageLook.isBilevel]) and [adjustments]. */
    fun blackAndWhite(bitmap: Bitmap, look: PageLook, adjustments: LookAdjustments, checkCancelled: () -> Unit = {}): BitImage =
        DocumentLooks.blackAndWhite(pixels(bitmap), look, adjustments, checkCancelled)

    private fun grayArgb(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
