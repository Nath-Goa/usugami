package io.github.nathgoa.pdfer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import io.github.nathgoa.pdfer.core.Compression
import io.github.nathgoa.pdfer.core.Compressor
import io.github.nathgoa.pdfer.core.FileNames
import io.github.nathgoa.pdfer.core.ImagePdfBuilder
import io.github.nathgoa.pdfer.core.InvalidJpegException
import io.github.nathgoa.pdfer.core.Jpeg
import io.github.nathgoa.pdfer.core.LayoutSettings
import io.github.nathgoa.pdfer.core.PageImage
import io.github.nathgoa.pdfer.core.PixelSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import java.io.ByteArrayOutputStream
import java.io.File

/** A finished PDF in app storage, ready to save or share. [originalBytes] is the size of its source images. */
data class PdfFile(val file: File, val pageCount: Int, val originalBytes: Long) {
    val name: String get() = file.name
    val sizeBytes: Long get() = file.length()
}

class ImageUnreadableException(val imageName: String) : Exception("Unreadable image: $imageName")

/**
 * Builds PDFs from the picked images, always compressed: each image is shrunk to the
 * [Compression] level's resolution for the space it fills on the page and stored as JPEG,
 * unless keeping the original is smaller. Output lives in files/output until the next export.
 */
class PdfExporter(context: Context) {
    private val outputDir = File(context.filesDir, "output/images")

    fun clearOutput() {
        outputDir.deleteRecursively()
    }

    /**
     * Writes one PDF with all [items] (named [mergedName].pdf) or, when [merge] is false, one PDF per
     * image named after it. Blocking and cancellable: call from a background coroutine.
     * [onProgress] receives the number of images done so far.
     */
    suspend fun export(
        items: List<ImageItem>,
        settings: LayoutSettings,
        compression: Compression,
        merge: Boolean,
        mergedName: String,
        producer: String,
        onProgress: (Int) -> Unit,
    ): List<PdfFile> {
        require(items.isNotEmpty())
        val job = currentCoroutineContext().job
        val checkCancelled = { job.ensureActive() }
        val options = Options(settings, compression, producer, checkCancelled)
        clearOutput()
        outputDir.mkdirs()
        try {
            return if (merge) {
                val name = FileNames.sanitize(mergedName, DEFAULT_NAME)
                val file = File(outputDir, "$name.pdf")
                writePdf(file, name, items, options) { onProgress(it) }
                listOf(PdfFile(file, items.size, items.sumOf { File(it.path).length() }))
            } else {
                val taken = HashSet<String>()
                items.mapIndexed { index, item ->
                    val name = FileNames.unique(FileNames.sanitize(FileNames.baseName(item.name), DEFAULT_NAME), taken)
                    taken += name
                    val file = File(outputDir, "$name.pdf")
                    writePdf(file, name, listOf(item), options) { onProgress(index + 1) }
                    PdfFile(file, 1, File(item.path).length())
                }
            }
        } catch (t: Throwable) {
            clearOutput()
            throw t
        }
    }

    private class Options(
        val layout: LayoutSettings,
        val compression: Compression,
        val producer: String,
        val checkCancelled: () -> Unit,
    )

    private fun writePdf(file: File, title: String, items: List<ImageItem>, options: Options, onPageDone: (Int) -> Unit) {
        file.outputStream().use { out ->
            val builder = ImagePdfBuilder(out, options.layout, title, options.producer)
            items.forEachIndexed { index, item ->
                options.checkCancelled()
                val page = loadPage(item, options)
                try {
                    builder.addPage(page.image, item.exifOrientation, item.rotation, options.checkCancelled)
                } finally {
                    page.release()
                }
                onPageDone(index + 1)
            }
            builder.finish()
            out.fd.sync()
        }
    }

    private class LoadedPage(val image: PageImage, val release: () -> Unit = {})

    /** The smaller of the compressed image and the original (when the original can be kept as is). */
    private fun loadPage(item: ImageItem, options: Options): LoadedPage {
        val file = File(item.path)
        if (!file.isFile) throw ImageUnreadableException(item.name)
        val scale = Compressor.scaleFor(item.shownWidth, item.shownHeight, options.layout, options.compression)
        val compressed = compressToJpeg(item, scale, options.compression.jpegQuality)
        options.checkCancelled()
        return when (item.format) {
            ImageFormat.JPEG -> {
                val bytes = file.readBytes()
                val original = try {
                    val info = Jpeg.parse(bytes)
                    if (info.canEmbedDirectly) PageImage.Jpeg(Jpeg.stripForEmbedding(bytes), info) else null
                } catch (e: InvalidJpegException) {
                    null
                }
                LoadedPage(if (original != null && original.data.size <= compressed.data.size) original else compressed)
            }
            ImageFormat.PNG -> {
                // Small PNGs (screenshots of plain colours, diagrams) can beat JPEG; keep those lossless.
                if (file.length() <= compressed.data.size && scale >= 1.0) {
                    val bitmap = decode(item, 1.0)
                    LoadedPage(PageImage.Raster(BitmapPixels(bitmap))) { bitmap.recycle() }
                } else {
                    LoadedPage(compressed)
                }
            }
        }
    }

    /** Decodes the image shrunk by [scale], flattens any transparency onto white and encodes it as JPEG. */
    private fun compressToJpeg(item: ImageItem, scale: Double, quality: Int): PageImage.Jpeg {
        var bitmap = decode(item, scale)
        try {
            if (bitmap.hasAlpha()) {
                val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                Canvas(flat).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(bitmap, 0f, 0f, null)
                }
                bitmap.recycle()
                bitmap = flat
            }
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw ImageUnreadableException(item.name)
            val bytes = out.toByteArray()
            return PageImage.Jpeg(bytes, Jpeg.parse(bytes))
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Decodes the image at [scale] of its stored size (orientation left as stored; the PDF applies
     * it). Decodes at the nearest larger power-of-two reduction, then resizes smoothly. If memory
     * runs out, the image is halved again until it fits.
     */
    private fun decode(item: ImageItem, scale: Double): Bitmap {
        var (targetW, targetH) = Compressor.scaledSize(item.width, item.height, scale)
        while (targetW.toLong() * targetH > MAX_PIXELS) {
            targetW = maxOf(1, targetW / 2)
            targetH = maxOf(1, targetH / 2)
        }
        var sample = 1
        while (item.width / (sample * 2) >= targetW && item.height / (sample * 2) >= targetH) sample *= 2
        while (true) {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
            try {
                val decoded = BitmapFactory.decodeFile(item.path, options) ?: throw ImageUnreadableException(item.name)
                if (decoded.width <= targetW || decoded.height <= targetH) return decoded
                val resized = resize(decoded, targetW, targetH)
                decoded.recycle()
                return resized
            } catch (e: OutOfMemoryError) {
                if (sample >= 32) throw e
                sample *= 2
                targetW = maxOf(1, targetW / 2)
                targetH = maxOf(1, targetH / 2)
            }
        }
    }

    /** Smooth downscale; steps down by halves first so large reductions don't alias. */
    private fun resize(source: Bitmap, width: Int, height: Int): Bitmap {
        var current = source
        while (current.width / 2 >= width && current.height / 2 >= height) {
            val half = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
            if (current !== source) current.recycle()
            current = half
        }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setHasAlpha(source.hasAlpha())
        Canvas(result).drawBitmap(
            current,
            android.graphics.Rect(0, 0, current.width, current.height),
            android.graphics.Rect(0, 0, width, height),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        if (current !== source) current.recycle()
        return result
    }

    private class BitmapPixels(private val bitmap: Bitmap) : PixelSource {
        override val width get() = bitmap.width
        override val height get() = bitmap.height
        override fun readRow(y: Int, out: IntArray) = bitmap.getPixels(out, 0, width, 0, y, width, 1)
    }

    companion object {
        const val DEFAULT_NAME = "PDFer"
        private const val MAX_PIXELS = 40_000_000L
    }
}
