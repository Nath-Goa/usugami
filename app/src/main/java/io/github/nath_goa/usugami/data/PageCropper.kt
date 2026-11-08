package io.github.nath_goa.usugami.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import androidx.core.graphics.createBitmap
import io.github.nath_goa.usugami.core.CropQuad
import io.github.nath_goa.usugami.core.ImageTransform
import io.github.nath_goa.usugami.core.PageCrop
import io.github.nath_goa.usugami.core.PageDetection
import io.github.nath_goa.usugami.core.PageOutline
import java.io.File
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A page cropped out of an image: its file and pixel size. */
data class CroppedPage(val file: File, val width: Int, val height: Int)

/**
 * Crops pages out of photos: finds the page on one, shows it for its corners to be dragged, and
 * straightens the page inside the corners into a picture of its own. Blocking: call from a
 * background thread.
 */
object PageCropper {
    /** The search for the page looks at a copy about this many pixels on its long side. */
    private const val SEARCH_SIDE = 800
    private const val JPEG_QUALITY = 92

    /**
     * The page on [image], as the automatic search finds it, or null when nothing like one stands out.
     * The search sees the photo's colours, so a page that differs from the desk only in colour stands out.
     */
    fun findPage(image: OriginalImage): PageOutline? {
        val bitmap = upright(image, 0, SEARCH_SIDE) ?: return null
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            return PageDetection.find(pixels, width, height)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * [image] as it shows upright and then turned [rotation] degrees clockwise, [side] pixels on its
     * long side (or as it is, if smaller), or null if it can't be read.
     */
    fun upright(image: OriginalImage, rotation: Int, side: Int): Bitmap? {
        var sample = 1
        while (max(image.width, image.height) / (sample * 2) >= side) sample *= 2
        while (sample <= 64) {
            try {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                }
                val decoded = BitmapFactory.decodeFile(image.path, options) ?: return null
                val transform = ImageTransform.of(image.exifOrientation, rotation)
                val scale = min(1f, side / max(decoded.width, decoded.height).toFloat())
                if (transform == ImageTransform.IDENTITY && scale == 1f) return decoded
                val matrix = transform.toMatrix(decoded.width, decoded.height).apply { postScale(scale, scale) }
                val turned = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (turned !== decoded) decoded.recycle()
                return turned
            } catch (e: OutOfMemoryError) {
                sample *= 2
            }
        }
        return null
    }

    /**
     * Writes the page inside [quad] (corners on [image] as it shows upright) into [target], straightened
     * onto a rectangle the page's shape (see [PageCrop.outputSize]: at most [PageCrop.MAX_SIDE] pixels
     * on its long side), in [image]'s format and with no metadata. A photo's page is decoded no larger than it
     * needs to be, and only the part inside the corners. If memory runs short, it is made smaller.
     */
    fun crop(image: OriginalImage, quad: CropQuad, target: File): CroppedPage {
        val upright = ImageTransform.fromExif(image.exifOrientation)
        var maxSide = PageCrop.MAX_SIDE
        while (true) {
            try {
                return crop(image, quad, upright, maxSide, target)
            } catch (e: OutOfMemoryError) {
                if (maxSide < 500) throw e
                maxSide /= 2
            }
        }
    }

    private fun crop(image: OriginalImage, quad: CropQuad, upright: ImageTransform, maxSide: Int, target: File): CroppedPage {
        val (outWidth, outHeight) = PageCrop.outputSize(quad, image.uprightWidth, image.uprightHeight, maxSide)
        val (fullWidth, fullHeight) = PageCrop.outputSize(quad, image.uprightWidth, image.uprightHeight, Int.MAX_VALUE)
        // The stored image's pixels around the page, read at a power-of-two reduction that keeps at
        // least the pixels the page needs.
        val corners = PageCrop.storedCorners(quad, upright, image.width, image.height)
        val xs = FloatArray(4) { corners[2 * it] }
        val ys = FloatArray(4) { corners[2 * it + 1] }
        val region = Rect(
            floor(xs.min()).toInt().coerceIn(0, image.width - 1),
            floor(ys.min()).toInt().coerceIn(0, image.height - 1),
            ceil(xs.max()).toInt().coerceIn(1, image.width),
            ceil(ys.max()).toInt().coerceIn(1, image.height),
        )
        if (region.width() < 1 || region.height() < 1) throw IOException("Empty crop")
        var sample = 1
        val reduction = max(fullWidth.toDouble() / outWidth, fullHeight.toDouble() / outHeight)
        while (sample * 2 <= reduction) sample *= 2
        val decoded = decodeRegion(image, region, sample)
        try {
            val scaleX = decoded.width / region.width().toFloat()
            val scaleY = decoded.height / region.height().toFloat()
            val source = FloatArray(8) { i -> if (i % 2 == 0) (corners[i] - region.left) * scaleX else (corners[i] - region.top) * scaleY }
            val destination = floatArrayOf(0f, 0f, outWidth.toFloat(), 0f, outWidth.toFloat(), outHeight.toFloat(), 0f, outHeight.toFloat())
            val matrix = Matrix()
            if (!matrix.setPolyToPoly(source, 0, destination, 0, 4)) throw IOException("Corners can't be straightened")
            val page = createBitmap(outWidth, outHeight)
            try {
                val jpeg = image.format == ImageFormat.JPEG
                // A PNG keeps its transparency; anything else is on white, as in the PDF.
                if (jpeg || !decoded.hasAlpha()) page.eraseColor(Color.WHITE)
                Canvas(page).drawBitmap(decoded, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
                val written = File(target.path + ".tmp")
                try {
                    written.outputStream().use { out ->
                        val format = if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                        if (!page.compress(format, if (jpeg) JPEG_QUALITY else 100, out)) throw IOException("Couldn't encode the page")
                        out.fd.sync()
                    }
                    if (!written.renameTo(target)) throw IOException("Couldn't save the page")
                } finally {
                    written.delete()
                }
                return CroppedPage(target, outWidth, outHeight)
            } finally {
                page.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }

    /** The [region] of [image] as stored, reduced [sample] times, in sRGB. */
    private fun decodeRegion(image: OriginalImage, region: Rect, sample: Int): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val decoder = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BitmapRegionDecoder.newInstance(image.path)
            else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(image.path, false)
        } catch (e: IOException) {
            null
        }
        if (decoder != null) {
            try {
                decoder.decodeRegion(region, options)?.let { return it }
            } finally {
                decoder.recycle()
            }
        }
        // An image the region decoder can't read in parts (an interlaced PNG, say): the whole of it.
        val whole = BitmapFactory.decodeFile(image.path, options) ?: throw IOException("Unreadable image")
        val left = (region.left / sample.toFloat()).roundToInt().coerceIn(0, whole.width - 1)
        val top = (region.top / sample.toFloat()).roundToInt().coerceIn(0, whole.height - 1)
        val right = min(whole.width, max(left + 1, (region.right / sample.toFloat()).roundToInt()))
        val bottom = min(whole.height, max(top + 1, (region.bottom / sample.toFloat()).roundToInt()))
        val part = Bitmap.createBitmap(whole, left, top, right - left, bottom - top)
        if (part !== whole) whole.recycle()
        return part
    }
}
