package io.github.nath_goa.usugami.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import io.github.nath_goa.usugami.core.Jpeg
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

    sealed interface ImportResult {
        data class Added(val item: ImageItem) : ImportResult
        data object Unsupported : ImportResult
        data object Failed : ImportResult
    }

    /** Copies [uri] into app storage. Blocking: call from a background thread. */
    fun import(uri: Uri, id: Long): ImportResult {
        dir.mkdirs()
        val name = displayName(uri) ?: "Image"
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

            val file = File(dir, "$id.${format.extension}")
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
                    name = name,
                    format = format,
                    width = bounds.outWidth,
                    height = bounds.outHeight,
                    exifOrientation = readOrientation(file),
                ),
            )
        } catch (e: Exception) {
            // SecurityException (access revoked), IOException (provider or storage failure), ...
            stored?.delete()
            return ImportResult.Failed
        } catch (e: OutOfMemoryError) {
            // A damaged or crafted file can make a decoder ask for a huge buffer.
            stored?.delete()
            return ImportResult.Failed
        } finally {
            tmp.delete()
        }
    }

    fun delete(item: ImageItem) {
        File(item.path).delete()
    }

    fun exists(item: ImageItem): Boolean = File(item.path).isFile

    /** Deletes every stored image except those in [keep]. */
    fun deleteAllExcept(keep: Collection<ImageItem>) {
        val keepPaths = keep.mapTo(HashSet()) { File(it.path).name }
        dir.listFiles()?.forEach { if (it.name !in keepPaths) it.delete() }
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
