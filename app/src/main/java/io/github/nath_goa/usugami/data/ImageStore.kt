package io.github.nath_goa.usugami.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import io.github.nath_goa.usugami.core.Jpeg
import io.github.nath_goa.usugami.core.isOutOfSpace
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Keeps private copies of picked images. Copying at pick time means the PDF can still be made
 * later even if the picker's temporary access ends or the app is restarted by the system.
 * The copies live in the cache, so the system can reclaim them (and "Clear cache" removes them);
 * check [exists] before relying on one.
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
            val format = resolver.openInputStream(uri)?.use { raw ->
                val input = raw.buffered(64 * 1024)
                input.mark(16)
                val header = ByteArray(8)
                val read = readFully(input, header)
                input.reset()
                val format = when {
                    read >= 3 && Jpeg.isJpeg(header) -> ImageFormat.JPEG
                    read == 8 && header.contentEquals(PNG_SIGNATURE) -> ImageFormat.PNG
                    else -> return ImportResult.Unsupported
                }
                tmp.outputStream().use { input.copyTo(it, 64 * 1024) }
                format
            } ?: return ImportResult.Failed

            val file = fileFor(id, format)
            if (!tmp.renameTo(file)) throw IOException("rename failed")
            stored = file

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
                    name = itemName,
                    format = format,
                    width = bounds.outWidth,
                    height = bounds.outHeight,
                    exifOrientation = readOrientation(file),
                ),
            )
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

    /** Deletes the photos the camera app saved that were never added, except [keep] (one it may still be saving). */
    fun clearCaptures(keep: String? = null) {
        captureDir.listFiles()?.forEach { if (it.path != keep) it.delete() }
    }

    private fun displayName(uri: Uri): String? {
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) {
                    val name = c.getString(0).substringAfterLast('/').trim()
                    if (name.isNotEmpty()) return name
                }
            }
        } catch (e: Exception) {
            // Some providers don't support queries; fall back to the URI.
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    private fun readOrientation(file: File): Int = try {
        ExifInterface(file.path)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            .takeIf { it in 1..8 } ?: 1
    } catch (e: Exception) {
        1
    } catch (e: OutOfMemoryError) {
        // ExifInterface allocates whatever a PNG chunk's length field says before reading it.
        1
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

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
