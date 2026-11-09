package io.github.nath_goa.usugami.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.net.Uri
import androidx.core.graphics.createBitmap
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import io.github.nath_goa.usugami.core.ImageFileType
import io.github.nath_goa.usugami.core.ImageTransform
import io.github.nath_goa.usugami.core.Jpeg
import io.github.nath_goa.usugami.core.PictureItem
import io.github.nath_goa.usugami.core.Webp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A picture picked for Edit PDF, copied into the cache: its size as it shows upright, in pixels. */
class PickedPicture(val path: String, val width: Int, val height: Int, val orientation: Int, val jpeg: Boolean)

/**
 * Pictures put on pages in Edit PDF. Each picked picture is copied into the cache first, like the
 * PDFs the tools work on, so it is still there after the picker's access ends or Android restarts
 * Usugami; the items refer to the copy by its path.
 */
class PagePictures(private val context: Context) {
    private val dir = File(context.cacheDir, "edit-pictures")

    /**
     * Copies the picture at [uri] in as picture [id]. Returns null, leaving nothing behind, if it
     * isn't a picture Android can read. [check] is called between chunks of the copy.
     */
    fun import(uri: Uri, id: Long, check: () -> Unit = {}): PickedPicture? {
        dir.mkdirs()
        val file = File(dir, "$id.picture")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                file.outputStream().use { target ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        check()
                        val read = source.read(buffer)
                        if (read < 0) break
                        target.write(buffer, 0, read)
                    }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                file.delete()
                return null
            }
            val header = header(file)
            // Turned the way Images to PDF turns the same photo.
            val orientation = ImageOrientation.of(file, ImageFileType.of(header))
            val sideways = ImageTransform.fromExif(orientation).swapsAxes
            // Photos stay photos; drawings, screenshots and pictures with transparency stay lossless.
            // A WebP file can be either: its header says which.
            val jpeg = bounds.outMimeType in PHOTO_TYPES || (bounds.outMimeType == "image/webp" && Webp.isLossyOpaque(header))
            return PickedPicture(
                file.path,
                if (sideways) bounds.outHeight else bounds.outWidth,
                if (sideways) bounds.outWidth else bounds.outHeight,
                orientation,
                jpeg,
            )
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
    }

    /** Deletes every copy but those at [keep]. */
    fun deleteAllExcept(keep: Collection<String>) {
        val names = keep.mapTo(HashSet()) { File(it).name }
        dir.listFiles()?.forEach { if (it.name !in names) it.delete() }
    }

    /** The first bytes of [file], as many as [Webp.isLossyOpaque] reads, more than [ImageFileType.of] needs. */
    private fun header(file: File): ByteArray = file.inputStream().use { input ->
        val header = ByteArray(Webp.HEADER_BYTES)
        var read = 0
        while (read < header.size) {
            val count = input.read(header, read, header.size - read)
            if (count < 0) break
            read += count
        }
        header.copyOf(read)
    }

    companion object {
        /** Kinds of picture stored as JPEG; the rest are stored losslessly. */
        private val PHOTO_TYPES = setOf("image/jpeg", "image/heif", "image/heic", "image/avif")

        /** Photos go in at this resolution of the size they are drawn at… */
        private const val PHOTO_DPI = 300

        /** …and other pictures, stored losslessly and so bigger, at this one… */
        private const val LOSSLESS_DPI = 200

        /** …but never more pixels than this on their longer side. */
        private const val MAX_SIDE = 2400

        private const val JPEG_QUALITY = 85

        /**
         * The picture at [path], upright, with its longer side at most [longSide] pixels, decoded
         * straight to about that size, a HEIC or AVIF photo too (see [HeifTiles]). Null if it can't
         * be read.
         */
        fun decode(path: String, orientation: Int, longSide: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longSide) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
            val decoded = HeifTiles.decode(path, sample) ?: BitmapFactory.decodeFile(path, options) ?: return null
            val scale = min(1f, longSide.toFloat() / max(decoded.width, decoded.height))
            val matrix = ImageTransform.fromExif(orientation).toMatrix(decoded.width, decoded.height, scale)
            val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (upright !== decoded) decoded.recycle()
            return upright
        }

        /**
         * [item]'s picture as an image for [doc], sharp at the size it is drawn: a photo as JPEG, any
         * other picture losslessly, with its transparency.
         */
        fun image(doc: PDDocument, item: PictureItem): PDImageXObject {
            val dpi = if (item.jpeg) PHOTO_DPI else LOSSLESS_DPI
            val longSide = min(MAX_SIDE, max(1, (max(item.width, item.height) / 72f * dpi).roundToInt()))
            var bitmap = decode(item.path, item.orientation, longSide) ?: throw IOException("Can't read picture ${item.path}")
            try {
                if (item.jpeg) {
                    if (bitmap.hasAlpha()) {
                        val flat = createBitmap(bitmap.width, bitmap.height)
                        Canvas(flat).apply {
                            drawColor(Color.WHITE)
                            drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                        }
                        bitmap.recycle()
                        bitmap = flat
                    }
                    return jpeg(doc, bitmap)
                }
                return LosslessFactory.createFromImage(doc, bitmap)
            } finally {
                bitmap.recycle()
            }
        }

        /**
         * [bitmap] as a JPEG image for [doc]. An Ultra HDR photo decodes with its gain map, and
         * encoding writes that back (a second picture, with XMP and MPF data, that PDF viewers don't
         * show), so the JPEG is stripped as Images to PDF strips a photo.
         */
        private fun jpeg(doc: PDDocument, bitmap: Bitmap): PDImageXObject {
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) throw IOException("Can't encode picture")
            val bytes = Jpeg.stripForEmbedding(out.toByteArray())
            val info = Jpeg.parse(bytes)
            val colors = if (info.components == 1) PDDeviceGray.INSTANCE else PDDeviceRGB.INSTANCE
            return PDImageXObject(doc, ByteArrayInputStream(bytes), COSName.DCT_DECODE, info.width, info.height, 8, colors)
        }
    }
}
