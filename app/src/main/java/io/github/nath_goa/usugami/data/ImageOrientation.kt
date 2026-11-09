package io.github.nath_goa.usugami.data

import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import io.github.nath_goa.usugami.core.ImageFileType
import java.io.File

/**
 * How the picture in an image file is turned, as an EXIF orientation from 1 (upright) to 8. Images
 * to PDF and Edit PDF read it the same way, so a photo shows the same way up in both. Android's
 * decoders give the pixels as stored: whoever draws them turns them.
 */
internal object ImageOrientation {
    /**
     * The orientation of the image of [type] in [file]. A HEIF or AVIF file can turn its picture
     * with a property of its own, which Android's gallery follows, and tools such as libheif then
     * mark its EXIF data upright; the EXIF orientation counts only when the file has no such turn.
     */
    fun of(file: File, type: ImageFileType?): Int {
        if ((type == ImageFileType.HEIF || type == ImageFileType.AVIF) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val degrees = try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.path)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_ROTATION)?.toIntOrNull()
                } finally {
                    retriever.release()
                }
            } catch (e: Exception) {
                null
            }
            when (degrees?.let { Math.floorMod(it, 360) }) {
                90 -> return ExifInterface.ORIENTATION_ROTATE_90
                180 -> return ExifInterface.ORIENTATION_ROTATE_180
                270 -> return ExifInterface.ORIENTATION_ROTATE_270
            }
        }
        return exif(file)
    }

    /** The EXIF orientation of [file]; 1 when it has none or it can't be read. */
    fun exif(file: File): Int = try {
        ExifInterface(file.path)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            .takeIf { it in 1..8 } ?: 1
    } catch (e: Exception) {
        1
    } catch (e: OutOfMemoryError) {
        // ExifInterface allocates whatever a PNG chunk's length field says before reading it.
        1
    }
}
