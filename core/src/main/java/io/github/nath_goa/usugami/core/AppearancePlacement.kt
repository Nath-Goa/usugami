package io.github.nath_goa.usugami.core

/**
 * Where an annotation's appearance goes when it is drawn into the page itself, as Flatten PDF does:
 * the rule viewers use to show it (PDF 32000-1, 12.5.5).
 *
 * The appearance is a form with a bounding box ([bbox]: left, bottom, right, top) and its own
 * matrix ([formMatrix]: a, b, c, d, e, f). The box, turned by that matrix, is fitted onto the
 * annotation's rectangle ([rect]: left, bottom, right, top, on the page).
 */
object AppearancePlacement {
    /**
     * The a, b, c, d, e, f to concatenate (`cm`) before drawing the form with `Do`. The form's own
     * matrix is applied by `Do` itself, so it isn't part of the result.
     */
    fun matrix(rect: FloatArray, bbox: FloatArray, formMatrix: FloatArray = IDENTITY): FloatArray {
        require(rect.size == 4 && bbox.size == 4 && formMatrix.size == 6)
        val (a, b, c, d, e, f) = formMatrix
        // The box's corners, through the form's matrix; their bounds are what is fitted.
        val xs = FloatArray(4)
        val ys = FloatArray(4)
        for ((i, corner) in listOf(bbox[0] to bbox[1], bbox[2] to bbox[1], bbox[0] to bbox[3], bbox[2] to bbox[3]).withIndex()) {
            xs[i] = a * corner.first + c * corner.second + e
            ys[i] = b * corner.first + d * corner.second + f
        }
        val left = xs.min()
        val bottom = ys.min()
        val width = xs.max() - left
        val height = ys.max() - bottom
        val rectLeft = minOf(rect[0], rect[2])
        val rectBottom = minOf(rect[1], rect[3])
        // A box with no width or height is drawn at its size rather than stretched to infinity.
        val scaleX = if (width > 0f) (maxOf(rect[0], rect[2]) - rectLeft) / width else 1f
        val scaleY = if (height > 0f) (maxOf(rect[1], rect[3]) - rectBottom) / height else 1f
        return floatArrayOf(scaleX, 0f, 0f, scaleY, rectLeft - left * scaleX, rectBottom - bottom * scaleY)
    }

    private val IDENTITY = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)

    private operator fun FloatArray.component6() = this[5]
}
