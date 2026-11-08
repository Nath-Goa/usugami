package io.github.nath_goa.usugami.data

import android.os.Parcel
import android.os.Parcelable
import io.github.nath_goa.usugami.core.CropPoint
import io.github.nath_goa.usugami.core.CropQuad
import io.github.nath_goa.usugami.core.ImageTransform
import io.github.nath_goa.usugami.core.LookAdjustments
import io.github.nath_goa.usugami.core.PageLook
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler

enum class ImageFormat(val extension: String) { JPEG("jpg"), PNG("png") }

/**
 * One picked image: a private copy in app storage plus what the editor needs to show it.
 * [width]/[height] are the stored pixel size; [exifOrientation] says how the camera stored it;
 * [rotation] is the extra clockwise rotation (0, 90, 180, 270) chosen in the editor. A page cropped
 * from an image is the straightened page ([path], upright), and [original] is the image it was
 * cropped from. [look] is how the page shows in the PDF, with [adjustments] (brightness and
 * contrast) on top; the image itself is left as it is.
 */
@Parcelize
@TypeParceler<LookAdjustments, LookAdjustmentsParceler>
data class ImageItem(
    val id: Long,
    val path: String,
    val name: String,
    val format: ImageFormat,
    val width: Int,
    val height: Int,
    val exifOrientation: Int,
    val rotation: Int = 0,
    val original: OriginalImage? = null,
    val look: PageLook = PageLook.ORIGINAL,
    val adjustments: LookAdjustments = LookAdjustments.NONE,
) : Parcelable {

    /** Whether the page shows otherwise than its image: with a look, or with brightness or contrast changed. */
    val styled: Boolean get() = look != PageLook.ORIGINAL || !adjustments.isNone

    /** EXIF orientation only: how the image looks upright in a gallery. */
    val uprightTransform: ImageTransform get() = ImageTransform.fromExif(exifOrientation)

    /** Width and height as the image will appear on its page. */
    val shownWidth: Int get() = if (ImageTransform.of(exifOrientation, rotation).swapsAxes) height else width
    val shownHeight: Int get() = if (ImageTransform.of(exifOrientation, rotation).swapsAxes) width else height

    fun rotatedBy(degrees: Int) = copy(rotation = Math.floorMod(rotation + degrees, 360))

    /** What cropping starts from: the image as picked or taken, with the page's corners on it. */
    val source: OriginalImage get() = original ?: OriginalImage(path, format, width, height, exifOrientation, CropQuad.WHOLE)
}

/**
 * An image as picked or taken, which a page was cropped from: kept so the page can be cropped again
 * from scratch. [corners] are where the page is on it, upright (its EXIF orientation applied).
 */
@Parcelize
@TypeParceler<CropQuad, CropQuadParceler>
data class OriginalImage(
    val path: String,
    val format: ImageFormat,
    val width: Int,
    val height: Int,
    val exifOrientation: Int,
    val corners: CropQuad,
) : Parcelable {
    private val upright: ImageTransform get() = ImageTransform.fromExif(exifOrientation)

    /** Its size as it shows upright. */
    val uprightWidth: Int get() = if (upright.swapsAxes) height else width
    val uprightHeight: Int get() = if (upright.swapsAxes) width else height
}

/** A [CropQuad] in a [Parcel] as its eight coordinates. */
object CropQuadParceler : Parceler<CropQuad> {
    override fun create(parcel: Parcel): CropQuad = CropQuad(List(4) { CropPoint(parcel.readFloat(), parcel.readFloat()) })

    override fun CropQuad.write(parcel: Parcel, flags: Int) {
        for (corner in corners) {
            parcel.writeFloat(corner.x)
            parcel.writeFloat(corner.y)
        }
    }
}

/** [LookAdjustments] in a [Parcel] as its two numbers. */
object LookAdjustmentsParceler : Parceler<LookAdjustments> {
    override fun create(parcel: Parcel): LookAdjustments = LookAdjustments(parcel.readInt(), parcel.readInt())

    override fun LookAdjustments.write(parcel: Parcel, flags: Int) {
        parcel.writeInt(brightness)
        parcel.writeInt(contrast)
    }
}
