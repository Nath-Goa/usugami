package io.github.nath_goa.usugami.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import io.github.nath_goa.usugami.core.Compression
import io.github.nath_goa.usugami.core.Compressor
import io.github.nath_goa.usugami.core.FileNames
import io.github.nath_goa.usugami.core.GrayJpeg
import io.github.nath_goa.usugami.core.ImagePdfBuilder
import io.github.nath_goa.usugami.core.ImagePlacement
import io.github.nath_goa.usugami.core.InvalidJpegException
import io.github.nath_goa.usugami.core.Jpeg
import io.github.nath_goa.usugami.core.LayoutSettings
import io.github.nath_goa.usugami.core.MarkStyle
import io.github.nath_goa.usugami.core.PageImage
import io.github.nath_goa.usugami.core.PageLook
import io.github.nath_goa.usugami.core.PixelSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A finished PDF in the app's cache, ready to save or share. [originalBytes] is the size of its source images.
 * [images] are, for a PDF just made from images, the image on each page and where it is drawn: text
 * recognition reads those rather than the compressed copies in the PDF. Empty for other PDFs.
 * [keptOriginal]: Compress PDF couldn't make it smaller, so it is the PDF picked, as it was.
 * [mark]: the Usugami mark [PageMark] put on its pages, which tells the file apart from itself before.
 */
data class PdfFile(
    val file: File,
    val pageCount: Int,
    val originalBytes: Long,
    val images: List<PlacedImage> = emptyList(),
    val keptOriginal: Boolean = false,
    val mark: MarkStyle? = null,
) {
    val name: String get() = file.name
    val sizeBytes: Long get() = file.length()
}

/** The picked image [item], drawn on its page as [placement] says. */
data class PlacedImage(val item: ImageItem, val placement: ImagePlacement)

class ImageUnreadableException(val imageName: String) : Exception("Unreadable image: $imageName")

/**
 * Builds PDFs from the picked images, always compressed: each image is shrunk to the
 * [Compression] level's resolution for the space it fills on the page and stored as JPEG,
 * unless keeping the original is smaller. A page with a look of its own ([ImageItem.look]) or
 * brightness and contrast ([ImageItem.adjustments]) is given them at that size and stored as it
 * needs: black and white a bit a pixel, grey as a grey JPEG.
 * Output lives in the cache (output/images) until the next export.
 */
class PdfExporter(private val context: Context) {
    private val outputDir = File(context.cacheDir, "output/images")

    /** Deletes the PDFs made before, but [keep]: a result showing again after Android restarted Usugami. */
    fun clearOutput(keep: Collection<File> = emptyList()) {
        outputDir.deleteAllBut(keep, context)
    }

    /** Where a ZIP file of the PDFs is made, beside them, so it goes when they do. */
    val zipDir get() = File(outputDir, "zip")

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
                val images = writePdf(file, name, items, options) { onProgress(it) }
                listOf(PdfFile(file, items.size, items.sumOf { File(it.path).length() }, images))
            } else {
                val taken = HashSet<String>()
                items.mapIndexed { index, item ->
                    val name = FileNames.unique(FileNames.sanitize(FileNames.baseName(item.name), DEFAULT_NAME), taken)
                    taken += name
                    val file = File(outputDir, "$name.pdf")
                    val images = writePdf(file, name, listOf(item), options) { onProgress(index + 1) }
                    PdfFile(file, 1, File(item.path).length(), images)
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

    /** Returns each page's image and where it went. */
    private fun writePdf(file: File, title: String, items: List<ImageItem>, options: Options, onPageDone: (Int) -> Unit): List<PlacedImage> {
        val placed = ArrayList<PlacedImage>()
        file.outputStream().use { out ->
            val builder = ImagePdfBuilder(out, options.layout, title, options.producer)
            items.forEachIndexed { index, item ->
                options.checkCancelled()
                val page = loadPage(item, options)
                try {
                    placed += PlacedImage(item, builder.addPage(page.image, item.exifOrientation, item.rotation, options.checkCancelled))
                } finally {
                    page.release()
                }
                onPageDone(index + 1)
            }
            builder.finish()
            out.fd.sync()
        }
        return placed
    }

    private class LoadedPage(val image: PageImage, val release: () -> Unit = {})

    /** The smaller of the compressed image and the original (when the original can be kept as is). */
    private fun loadPage(item: ImageItem, options: Options): LoadedPage {
        val file = File(item.path)
        if (!file.isFile) throw ImageUnreadableException(item.name)
        val scale = Compressor.scaleFor(item.shownWidth, item.shownHeight, options.layout, options.compression)
        if (item.styled) return LoadedPage(withLook(item, scale, options))
        val compressed = compressToJpeg(item, scale, options.compression.jpegQuality)
        options.checkCancelled()
        return when (item.format) {
            ImageFormat.JPEG -> LoadedPage(original(file, compressed.data.size) ?: compressed)
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

    /**
     * The original JPEG, stripped for embedding, if a PDF can show it as it is and it is no bigger
     * than [maxSize] bytes; otherwise null.
     */
    private fun original(file: File, maxSize: Int): PageImage.Jpeg? {
        // A PDF says how to read the colours (/DeviceRGB, as sRGB), and viewers ignore a profile in
        // the JPEG, so a wide-gamut photo (Display P3, Adobe RGB) would look dull. The compressed
        // copy has been converted to sRGB.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outColorSpace?.isSrgb == false) return null
        val bytes = file.readBytes()
        return try {
            val info = Jpeg.parse(bytes)
            // Sized first, so a big photo that can't beat the compressed copy isn't copied as well.
            if (!info.canEmbedDirectly || Jpeg.strippedSize(bytes) > maxSize) null
            else PageImage.Jpeg(Jpeg.stripForEmbedding(bytes), info)
        } catch (e: InvalidJpegException) {
            null
        }
    }

    /**
     * Decodes the image shrunk by [scale], flattens any transparency onto white and encodes it as
     * JPEG.
     */
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
            return jpeg(bitmap, quality, item)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * [bitmap] as JPEG. An Ultra HDR photo decodes with its gain map, and encoding writes that back
     * (an extra image plus XMP and MPF segments), so the result is stripped like an original.
     */
    private fun jpeg(bitmap: Bitmap, quality: Int, item: ImageItem): PageImage.Jpeg {
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw ImageUnreadableException(item.name)
        val bytes = Jpeg.stripForEmbedding(out.toByteArray())
        return PageImage.Jpeg(bytes, Jpeg.parse(bytes))
    }

    /**
     * The image shrunk by [scale] with its look and adjustments (see [PageLooks]): black and white a
     * bit a pixel (CCITT G4, far smaller than a photo), grey as a JPEG with one component, the colour
     * looks as a colour JPEG, at the compression level's quality. A look takes more memory than the
     * picture alone: if memory runs out, the page is made at half the size, as [decode] does, until
     * it fits.
     */
    private fun withLook(item: ImageItem, scale: Double, options: Options): PageImage {
        var shrink = scale
        var halvings = 0
        while (true) {
            try {
                return lookAt(item, shrink, options)
            } catch (e: OutOfMemoryError) {
                if (++halvings > MAX_LOOK_HALVINGS) throw e
                shrink /= 2
            }
        }
    }

    private fun lookAt(item: ImageItem, scale: Double, options: Options): PageImage {
        // Changeable, so that a colour look is made in it rather than in a second picture as large.
        val bitmap = decode(item, scale, mutable = true)
        try {
            val quality = options.compression.jpegQuality
            val look = item.look
            return when {
                look.isBilevel -> PageImage.Bilevel(PageLooks.blackAndWhite(bitmap, look, item.adjustments, options.checkCancelled))
                look == PageLook.GRAYSCALE -> {
                    val bytes = GrayJpeg.encode(PageLooks.gray(bitmap, item.adjustments, options.checkCancelled), quality, options.checkCancelled)
                    PageImage.Jpeg(bytes, Jpeg.parse(bytes))
                }
                else -> {
                    PageLooks.colourInPlace(bitmap, look, item.adjustments, options.checkCancelled)
                    jpeg(bitmap, quality, item)
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Decodes the image at [scale] of its stored size (orientation left as stored; the PDF applies
     * it). Decodes at the nearest larger power-of-two reduction, then resizes smoothly. If memory
     * runs out, the image is halved again until it fits. The bitmap can be changed when [mutable].
     */
    private fun decode(item: ImageItem, scale: Double, mutable: Boolean = false): Bitmap {
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
                inMutable = mutable
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
        const val DEFAULT_NAME = "Usugami"
        private const val MAX_PIXELS = 40_000_000L
        /** How many times a page with a look is halved, at most, when memory runs out. */
        private const val MAX_LOOK_HALVINGS = 5
    }
}
