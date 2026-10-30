package io.github.nath_goa.pdfer.data

import android.os.Parcelable
import io.github.nath_goa.pdfer.core.ImageTransform
import kotlinx.parcelize.Parcelize

enum class ImageFormat(val extension: String) { JPEG("jpg"), PNG("png") }

/**
 * One picked image: a private copy in app storage plus what the editor needs to show it.
 * [width]/[height] are the stored pixel size; [exifOrientation] says how the camera stored it;
 * [rotation] is the extra clockwise rotation (0, 90, 180, 270) chosen in the editor.
 */
@Parcelize
data class ImageItem(
    val id: Long,
    val path: String,
    val name: String,
    val format: ImageFormat,
    val width: Int,
    val height: Int,
    val exifOrientation: Int,
    val rotation: Int = 0,
) : Parcelable {

    /** EXIF orientation only: how the image looks upright in a gallery. */
    val uprightTransform: ImageTransform get() = ImageTransform.fromExif(exifOrientation)

    /** Width and height as the image will appear on its page. */
    val shownWidth: Int get() = if (ImageTransform.of(exifOrientation, rotation).swapsAxes) height else width
    val shownHeight: Int get() = if (ImageTransform.of(exifOrientation, rotation).swapsAxes) width else height

    fun rotatedBy(degrees: Int) = copy(rotation = Math.floorMod(rotation + degrees, 360))
}
