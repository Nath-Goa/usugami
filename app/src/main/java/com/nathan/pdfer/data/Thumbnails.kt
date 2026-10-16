package com.nathan.pdfer.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import com.nathan.pdfer.core.ImageTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Small upright previews (EXIF orientation applied, user rotation not) kept in memory.
 * The user's rotation is applied when drawing, so rotating never re-decodes anything.
 */
class Thumbnails {
    private val cache = object : LruCache<Long, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }
    private val decoding = Dispatchers.IO.limitedParallelism(3)

    fun cached(id: Long): Bitmap? = cache.get(id)

    suspend fun load(item: ImageItem): Bitmap? = cache.get(item.id) ?: withContext(decoding) {
        cache.get(item.id) ?: decode(item)?.also { cache.put(item.id, it) }
    }

    fun remove(id: Long) {
        cache.remove(id)
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
            val matrix = item.uprightTransform.toMatrix(decoded.width, decoded.height).apply { postScale(scale, scale) }
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
