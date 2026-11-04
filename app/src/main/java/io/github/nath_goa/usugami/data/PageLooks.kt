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
import io.github.nath_goa.usugami.core.PageCrop
import io.github.nath_goa.usugami.core.PageLook
import io.github.nath_goa.usugami.core.PixelSource

/** A page's [PageLook] on Android bitmaps (the pixel work is [DocumentLooks]'). Blocking. */
object PageLooks {

    /** [bitmap]'s pixels a row at a time. */
    fun pixels(bitmap: Bitmap): PixelSource = object : PixelSource {
        override val width get() = bitmap.width
        override val height get() = bitmap.height
        override fun readRow(y: Int, out: IntArray) = bitmap.getPixels(out, 0, width, 0, y, width, 1)
    }

    /** [bitmap] as [look] shows it, as a new opaque bitmap of the same size; [bitmap] is left as it is. */
    fun applied(bitmap: Bitmap, look: PageLook, checkCancelled: () -> Unit = {}): Bitmap {
        if (look == PageLook.ORIGINAL) return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val out = createBitmap(bitmap.width, bitmap.height)
        val width = bitmap.width
        val row = IntArray(width)
        when (look) {
            PageLook.ORIGINAL -> Unit
            PageLook.ENHANCED -> DocumentLooks.enhance(pixels(bitmap), { y, pixels -> out.setPixels(pixels, 0, width, 0, y, width, 1) }, checkCancelled)
            PageLook.GRAYSCALE -> {
                val gray = DocumentLooks.grayscale(pixels(bitmap), checkCancelled)
                for (y in 0 until gray.height) {
                    for (x in 0 until width) row[x] = grayArgb(gray[x, y])
                    out.setPixels(row, 0, width, 0, y, width, 1)
                }
            }
            PageLook.BLACK_WHITE -> {
                val bits = DocumentLooks.blackAndWhite(pixels(bitmap), checkCancelled)
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
     * The page inside [quad] (corners on [bitmap], in a page's order) straightened onto a rectangle the
     * page's shape, at most [maxSide] pixels on its long side, as [PageCropper.crop] makes it; null
     * when the corners make no page.
     */
    fun straightened(bitmap: Bitmap, quad: CropQuad, maxSide: Int): Bitmap? {
        if (!quad.isWhole() && !quad.isUsable(bitmap.width, bitmap.height)) return null
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

    /** [bitmap] in grey with the [PageLook.GRAYSCALE] look. */
    fun gray(bitmap: Bitmap, checkCancelled: () -> Unit = {}): GrayImage = DocumentLooks.grayscale(pixels(bitmap), checkCancelled)

    /** [bitmap] in black and white with the [PageLook.BLACK_WHITE] look. */
    fun blackAndWhite(bitmap: Bitmap, checkCancelled: () -> Unit = {}): BitImage = DocumentLooks.blackAndWhite(pixels(bitmap), checkCancelled)

    private fun grayArgb(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
