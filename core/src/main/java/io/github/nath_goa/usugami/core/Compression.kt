package io.github.nath_goa.usugami.core

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
            // Fit pages take their size from the pixels, so judge the image as if it filled A4: each
            // side keeps at least as many pixels as A4's side would at this resolution. Capping only
            // the long side would crush a long screenshot to a sliver no one could read.
            val longCap = PageSize.A4.longSide / 72.0 * level.dpi / max(shownWidth, shownHeight)
            val shortCap = PageSize.A4.shortSide / 72.0 * level.dpi / min(shownWidth, shownHeight)
            return min(1.0, max(longCap, shortCap))
        }
        val placedWidthInches = PageLayout.compute(shownWidth, shownHeight, settings).imageWidth / 72.0
        return min(1.0, placedWidthInches * level.dpi / shownWidth)
    }

    /**
     * As [scaleFor] for an image drawn [placedWidth] points wide, keeping [detail] times the level's
     * resolution: [shownWidth] is the image's pixel width as it appears on the page. An ID card's
     * side is drawn small, so it keeps more pixels per inch than a page (IdCardLayout.DETAIL).
     */
    fun scaleFor(shownWidth: Int, placedWidth: Double, level: Compression, detail: Double = 1.0): Double =
        min(1.0, placedWidth / 72.0 * level.dpi * detail / shownWidth)

    /**
     * How much smaller [now] bytes are than [original], in whole percent; null unless that is at
     * least 1%, so a PDF is never said to be "0% smaller".
     */
    fun savedPercent(original: Long, now: Long): Int? {
        if (original <= 0 || now >= original) return null
        return ((original - now) * 100 / original).toInt().takeIf { it >= 1 }
    }

    /** [width] x [height] scaled by [scale], each side at least one pixel. */
    fun scaledSize(width: Int, height: Int, scale: Double): Pair<Int, Int> =
        max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}
