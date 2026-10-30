package io.github.nathgoa.pdfer.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How hard images are compressed: the most pixels per inch kept where the image sits on its
 * page, and the JPEG quality they are stored with.
 */
enum class Compression(val dpi: Int, val jpegQuality: Int) {
    LESS(200, 85),
    RECOMMENDED(150, 75),
    EXTREME(100, 55),
}

object Compressor {

    /**
     * The factor (at most 1, never enlarging) to shrink an image by so it has no more than
     * [level]'s resolution on the page. [shownWidth]/[shownHeight] are the pixel size as it
     * appears on the page.
     */
    fun scaleFor(shownWidth: Int, shownHeight: Int, settings: LayoutSettings, level: Compression): Double {
        if (settings.pageSize == PageSize.FIT) {
            // Fit pages take their size from the pixels, so judge the image as if it filled A4.
            val maxSide = PageSize.A4.longSide / 72.0 * level.dpi
            return min(1.0, maxSide / max(shownWidth, shownHeight))
        }
        val placedWidthInches = PageLayout.compute(shownWidth, shownHeight, settings).imageWidth / 72.0
        return min(1.0, placedWidthInches * level.dpi / shownWidth)
    }

    /** [width] x [height] scaled by [scale], each side at least one pixel. */
    fun scaledSize(width: Int, height: Int, scale: Double): Pair<Int, Int> =
        max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}
