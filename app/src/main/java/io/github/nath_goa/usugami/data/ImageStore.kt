package io.github.nath_goa.usugami.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.github.nath_goa.usugami.core.ImageFileType
import io.github.nath_goa.usugami.core.Webp
import io.github.nath_goa.usugami.core.isOutOfSpace
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Keeps private copies of picked images. Copying at pick time means the PDF can still be made
 * later even if the picker's temporary access ends or the app is restarted by the system.
 * The copies live in the cache, so the system can reclaim them (and "Clear cache" removes them);
 * check [exists] before relying on one. JPEG and PNG images are copied as they are; HEIC, AVIF,
 * WebP, BMP and GIF images are stored as one of those two (see [converted]), so the rest of the
 * app reads only JPEG and PNG.
 */
class ImageStore(context: Context) {
    private val resolver = context.contentResolver
    private val dir = File(context.cacheDir, "images")
    /** Where the camera app saves the photos taken from Usugami, until they are added. */
    private val captureDir = File(context.cacheDir, "camera")

    sealed interface ImportResult {
        data class Added(val item: ImageItem) : ImportResult
        data object Unsupported : ImportResult
        data object Failed : ImportResult
        /** The storage is full: the image may be fine. */
        data object NoSpace : ImportResult
    }

    /** Copies [uri] into app storage, named [name] or else as the picker names it. Blocking: call from a background thread. */
    fun import(uri: Uri, id: Long, name: String? = null): ImportResult {
        dir.mkdirs()
        val itemName = name ?: displayName(uri) ?: "Image"
        val tmp = File(dir, "$id.tmp")
        var stored: File? = null
        try {
            val type = resolver.openInputStream(uri)?.use { raw ->
                val input = raw.buffered(64 * 1024)
                input.mark(ImageFileType.HEADER_BYTES)
                val type = typeOf(input)?.takeIf(::canRead) ?: return ImportResult.Unsupported
                input.reset()
                tmp.outputStream().use { input.copyTo(it, 64 * 1024) }
                type
            } ?: return ImportResult.Failed

            val format = storedFormat(type)
            if (format == null) {
                val (file, converted) = converted(tmp, id, type) ?: return ImportResult.Failed
                stored = file
                return checked(file, id, itemName, converted, ImageOrientation.of(tmp, type))
            }
            val file = fileFor(id, format)
            if (!tmp.renameTo(file)) throw IOException("rename failed")
            stored = file
            return checked(file, id, itemName, format)
        } catch (e: Exception) {
            // SecurityException (access revoked), IOException (provider or storage failure), ...
            stored?.delete()
            return if (e.isOutOfSpace()) ImportResult.NoSpace else ImportResult.Failed
        } catch (e: OutOfMemoryError) {
            // A damaged or crafted file can make a decoder ask for a huge buffer.
            stored?.delete()
            return ImportResult.Failed
        } finally {
            tmp.delete()
        }
    }

    /**
     * Adds the photo the camera app saved in [file] as image [id], named [name]. It is moved in, which
     * needs no free storage, unless [keepFile]: it is then copied, and moved only when there is no
     * room for a copy. Blocking: call from a background thread.
     */
    fun addPhoto(file: File, id: Long, name: String, keepFile: Boolean): ImportResult {
        val copied = if (keepFile) import(Uri.fromFile(file), id, name) else null
        if (copied != null && copied != ImportResult.NoSpace) return copied
        return moveIn(file, id, name) ?: copied ?: import(Uri.fromFile(file), id, name)
    }

    /**
     * [file] moved in as image [id]; null when it can't be moved (it is then still there), or when
     * it is stored converted, which [import] does from a copy.
     */
    private fun moveIn(file: File, id: Long, name: String): ImportResult? {
        dir.mkdirs()
        var stored: File? = null
        try {
            val type = file.inputStream().use { typeOf(it) }?.takeIf(::canRead) ?: return ImportResult.Unsupported
            val format = storedFormat(type) ?: return null
            val target = fileFor(id, format)
            if (!file.renameTo(target)) return null
            stored = target
            return checked(target, id, name, format)
        } catch (e: Exception) {
            stored?.delete()
            return ImportResult.Failed
        } catch (e: OutOfMemoryError) {
            stored?.delete()
            return ImportResult.Failed
        }
    }

    /** The type of image [input] starts with, read from its first bytes; null when it is none Usugami takes. */
    private fun typeOf(input: InputStream): ImageFileType? {
        val header = ByteArray(ImageFileType.HEADER_BYTES)
        return ImageFileType.of(header.copyOf(readFully(input, header)))
    }

    /** The format an image of [type] is stored in as it is; null for one stored converted (see [converted]). */
    private fun storedFormat(type: ImageFileType): ImageFormat? = when (type) {
        ImageFileType.JPEG -> ImageFormat.JPEG
        ImageFileType.PNG -> ImageFormat.PNG
        else -> null
    }

    /**
     * Whether this phone's Android can decode an image of [type]: HEIF from Android 9, AVIF from
     * Android 12.
     */
    private fun canRead(type: ImageFileType): Boolean = when (type) {
        ImageFileType.HEIF -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        ImageFileType.AVIF -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        else -> true
    }

    /**
     * The image of [type] in [source] stored as image [id], and the format it is stored in: a photo
     * as a JPEG, and a picture with transparency or one kept without loss (a lossless WebP, a BMP,
     * the first frame of a GIF) as a PNG. A very large photo is stored at half its size, or less,
     * until it has at most [MAX_CONVERTED_PIXELS], still more than a page needs at the least
     * compression, and is decoded straight to that size (see [decode]). The pixels stay as stored:
     * [ImageOrientation] says how they are turned. Null when the image can't be decoded.
     */
    private fun converted(source: File, id: Long, type: ImageFileType): Pair<File, ImageFormat>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > MAX_CONVERTED_PIXELS) sample *= 2
        val bitmap = decode(source, sample) ?: return null
        try {
            val lossless = bitmap.hasAlpha() || when (type) {
                ImageFileType.WEBP -> !Webp.isLossyOpaque(header(source))
                ImageFileType.BMP, ImageFileType.GIF -> true
                else -> false
            }
            val format = if (lossless) ImageFormat.PNG else ImageFormat.JPEG
            val file = fileFor(id, format)
            try {
                WriteFailure(file.outputStream()).use { out ->
                    val written = if (lossless) bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    else bitmap.compress(Bitmap.CompressFormat.JPEG, CONVERTED_JPEG_QUALITY, out)
                    // Storage running full is told apart from an image that can't be encoded.
                    if (!written) throw out.failure ?: IOException("encoding failed")
                }
            } catch (e: Exception) {
                file.delete()
                throw e
            }
            return file to format
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * [source] decoded at 1/[sample] of its size, or smaller while memory runs out; null if it can't
     * be. Android's decoders make a WebP, BMP or GIF image smaller as they read it; a HEIC or AVIF
     * photo is read a tile at a time for that (see [HeifTiles]).
     */
    private fun decode(source: File, sample: Int): Bitmap? {
        var shrink = sample
        while (true) {
            val options = BitmapFactory.Options().apply {
                inSampleSize = shrink
                inPreferredConfig = Bitmap.Config.ARGB_8888
                // A PDF's colours are sRGB: a wide-colour or HDR photo is brought to it here, once.
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
            try {
                return HeifTiles.decode(source.path, shrink) ?: BitmapFactory.decodeFile(source.path, options)
            } catch (e: OutOfMemoryError) {
                if (shrink >= MAX_SHRINK * sample) throw e
                shrink *= 2
            }
        }
    }

    /** The first bytes of [file], as many as [Webp.isLossyOpaque] reads. */
    private fun header(file: File): ByteArray = file.inputStream().use { input ->
        val header = ByteArray(Webp.HEADER_BYTES)
        header.copyOf(readFully(input, header))
    }

    /**
     * The image now in the store at [file], turned as [orientation] says; Failed, and the file
     * deleted, when it can't be read.
     */
    private fun checked(
        file: File,
        id: Long,
        name: String,
        format: ImageFormat,
        orientation: Int = ImageOrientation.exif(file),
    ): ImportResult {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            file.delete()
            return ImportResult.Failed
        }
        return ImportResult.Added(
            ImageItem(
                id = id,
                path = file.path,
                name = name,
                format = format,
                width = bounds.outWidth,
                height = bounds.outHeight,
                exifOrientation = orientation,
            ),
        )
    }

    /** Where the copy of image [id] is kept. */
    fun fileFor(id: Long, format: ImageFormat): File = File(dir, "$id.${format.extension}")

    /** Deletes the image's copy, and the image it was cropped from. */
    fun delete(item: ImageItem) {
        File(item.path).delete()
        item.original?.let { File(it.path).delete() }
    }

    /** Whether the image's copy (the page, for a cropped one) is still there. */
    fun exists(item: ImageItem): Boolean = File(item.path).isFile

    /**
     * [item] as its files are now; null when its copy has gone. The system clears the oldest files
     * first, so a cropped page can outlast the image it was cropped from: it then stays, cropped
     * from as it is, and takes that image's file name, which a page never cropped has (see
     * [fileFor]). Blocking: call from the file work's thread.
     */
    fun stillThere(item: ImageItem): ImageItem? {
        val page = File(item.path)
        if (!page.isFile) return null
        val original = item.original ?: return item
        if (File(original.path).isFile) return item
        val file = fileFor(item.id, item.format)
        return when {
            page == file -> item.copy(original = null)
            page.renameTo(file) -> item.copy(path = file.path, original = null)
            else -> null
        }
    }

    /**
     * [item], a cropped page whose own file has gone while the image it was cropped from is still
     * there, put back on that image whole, as a page never cropped; null for any other page (see
     * [stillThere] for those). A page about to be cropped again from that image needs nothing more.
     * Blocking: call from the file work's thread.
     */
    fun putBackWhole(item: ImageItem): ImageItem? {
        val original = item.original ?: return null
        if (File(item.path).isFile || !File(original.path).isFile) return null
        return item.copy(
            path = original.path,
            format = original.format,
            width = original.width,
            height = original.height,
            exifOrientation = original.exifOrientation,
            original = null,
        )
    }

    /** Deletes every stored image except those in [keep] (and the images they were cropped from). */
    fun deleteAllExcept(keep: Collection<ImageItem>) {
        val keepPaths = HashSet<String>()
        for (item in keep) {
            keepPaths += File(item.path).name
            item.original?.let { keepPaths += File(it.path).name }
        }
        dir.listFiles()?.forEach { if (it.name !in keepPaths) it.delete() }
    }

    /** A new, empty file [id] for the camera app to save a photo in. */
    fun newCapture(id: Long): File {
        captureDir.mkdirs()
        return File(captureDir, "$id.jpg").apply { delete() }
    }

    /**
     * Deletes the photos the camera app saved that were never added, except those at the paths
     * [keep] (one it may still be saving, and those still to be added).
     */
    fun clearCaptures(keep: Collection<String> = emptyList()) {
        captureDir.listFiles()?.forEach { if (it.path !in keep) it.delete() }
    }

    private fun displayName(uri: Uri): String? {
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) {
                    val name = c.getString(0).substringAfterLast('/').trim()
                    if (name.isNotEmpty()) return if (isPhotoPicker(uri)) datedName(uri, name) else name
                }
            }
        } catch (e: Exception) {
            // Some providers don't support queries; fall back to the URI.
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    /** Whether [uri] is a photo picked with Android's photo picker, which hides the photo's file name. */
    private fun isPhotoPicker(uri: Uri): Boolean =
        uri.authority == MediaStore.AUTHORITY && uri.pathSegments.firstOrNull()?.startsWith("picker") == true

    /**
     * A name for the photo at [uri] from the photo picker, which names it [pickerName], its number
     * in the phone's media store ("1000000163.jpg"): "Photo_" and the date and time it was taken,
     * or else picked, as a photo taken from Usugami is named after when it was taken. The PDF of
     * one photo is named after it.
     */
    private fun datedName(uri: Uri, pickerName: String): String {
        val taken = try {
            resolver.query(uri, arrayOf(MediaStore.Images.ImageColumns.DATE_TAKEN), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        } catch (e: Exception) {
            null
        }
        val time = Instant.ofEpochMilli(taken?.takeIf { it > 0 } ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault())
        val extension = pickerName.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
        return "Photo_" + time.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)) + extension
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /**
     * Passes what is written on to [out], keeping the failure of a write: Bitmap.compress catches
     * it and only says it failed.
     */
    private class WriteFailure(out: OutputStream) : FilterOutputStream(out) {
        var failure: IOException? = null
            private set

        override fun write(b: ByteArray, off: Int, len: Int) {
            try {
                out.write(b, off, len)
            } catch (e: IOException) {
                failure = e
                throw e
            }
        }
    }

    companion object {
        /**
         * At most this many pixels are kept of an image stored converted: a 24-megapixel photo whole,
         * a 50-megapixel one at half its size.
         */
        private const val MAX_CONVERTED_PIXELS = 25_000_000L

        /** How many times smaller than planned an image is decoded, at most, while memory runs out. */
        private const val MAX_SHRINK = 32

        /** High, as the PDF is made from this copy and compresses it again. */
        private const val CONVERTED_JPEG_QUALITY = 95

        /** The types of image to offer in the file picker: those this phone's Android can read. */
        val pickableTypes: Array<String>
            get() = buildList {
                addAll(listOf("image/jpeg", "image/png", "image/webp", "image/bmp", "image/x-ms-bmp", "image/gif"))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) addAll(listOf("image/heic", "image/heif"))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add("image/avif")
            }.toTypedArray()
    }
}
