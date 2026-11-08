package io.github.nath_goa.usugami.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import io.github.nath_goa.usugami.core.ImageTransform
import io.github.nath_goa.usugami.core.LookAdjustments
import io.github.nath_goa.usugami.core.PageLook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Small upright previews (EXIF orientation applied, user rotation not) kept in memory, one per
 * image file, look and adjustments: a page cropped again has a new file, and a new preview, and a
 * page given another look or adjustments is made from its plain preview.
 * The user's rotation is applied when drawing, so rotating never re-decodes anything.
 */
class Thumbnails {
    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val decoding = Dispatchers.IO.limitedParallelism(3)

    fun cached(item: ImageItem): Bitmap? = cache.get(key(item))

    suspend fun load(item: ImageItem): Bitmap? = cache.get(key(item)) ?: withContext(decoding) {
        val plain = cache.get(item.path) ?: decode(item)?.also { cache.put(item.path, it) }
        if (plain == null || !item.styled) return@withContext plain
        cache.get(key(item)) ?: withLook(plain, item.look, item.adjustments)?.also { cache.put(key(item), it) }
    }

    /** Forgets every preview of [item]'s file, whatever its look. */
    fun remove(item: ImageItem) {
        val styled = "${item.path}#"
        for (key in cache.snapshot().keys) if (key == item.path || key.startsWith(styled)) cache.remove(key)
    }

    private fun key(item: ImageItem) =
        if (!item.styled) item.path else "${item.path}#${item.look.name}#${item.adjustments.brightness}#${item.adjustments.contrast}"

    private fun withLook(plain: Bitmap, look: PageLook, adjustments: LookAdjustments): Bitmap? = try {
        PageLooks.applied(plain, look, adjustments)
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun decode(item: ImageItem): Bitmap? = try {
        var sample = 1
        while (max(item.width, item.height) / (sample * 2) >= TARGET_SIZE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(item.path, options)?.let { decoded ->
            val scale = min(1f, TARGET_SIZE.toFloat() / max(decoded.width, decoded.height))
            val matrix = item.uprightTransform.toMatrix(decoded.width, decoded.height, scale)
            val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (upright !== decoded) decoded.recycle()
            upright
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private companion object {
        const val TARGET_SIZE = 512

        fun cacheBytes(): Int = (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(96L * 1024 * 1024).toInt()
    }
}

/**
 * The [ImageTransform] as a pixel-space matrix for a [width] x [height] bitmap, so previews use
 * exactly the same orientation rules as the PDF.
 */
fun ImageTransform.toMatrix(width: Int, height: Int): Matrix {
    val outW = (if (swapsAxes) height else width).toFloat()
    val outH = (if (swapsAxes) width else height).toFloat()
    val w = width.toFloat()
    val h = height.toFloat()
    return Matrix().apply {
        setValues(
            floatArrayOf(
                a * outW / w, b * outW / h, c * outW,
                d * outH / w, e * outH / h, f * outH,
                0f, 0f, 1f,
            ),
        )
    }
}

/**
 * The [ImageTransform] as a pixel-space matrix for a [width] x [height] bitmap that also shrinks it
 * by about [scale], to a whole number of pixels each way. Bitmap.createBitmap rounds the size it
 * makes, and a last row or column the picture only partly covered would mix in the black a new
 * opaque bitmap starts as: a grey line along a white page's edge.
 */
fun ImageTransform.toMatrix(width: Int, height: Int, scale: Float): Matrix {
    val outW = if (swapsAxes) height else width
    val outH = if (swapsAxes) width else height
    val scaledW = max(1, (outW * scale).roundToInt())
    val scaledH = max(1, (outH * scale).roundToInt())
    return toMatrix(width, height).apply { postScale(scaledW / outW.toFloat(), scaledH / outH.toFloat()) }
}
