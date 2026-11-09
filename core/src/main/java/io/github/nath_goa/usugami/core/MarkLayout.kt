package io.github.nath_goa.usugami.core

import kotlin.math.min
import kotlin.math.pow

/**
 * The small "Usugami" mark the PDFs Usugami makes can carry, to support the app, as the result
 * screen's sliders set it: [size] from 0 (smallest) to 100 (largest), and [opacity] in percent, from
 * [MIN_OPACITY] (barely visible) to 100 (solid).
 */
data class MarkStyle(val size: Int = DEFAULT_SIZE, val opacity: Int = DEFAULT_OPACITY) {
    init {
        require(size in 0..100) { "Size must be 0 to 100, not $size" }
        require(opacity in MIN_OPACITY..100) { "Opacity must be $MIN_OPACITY to 100, not $opacity" }
    }

    /** How much of the page the mark covers, from 0.1 to 1. */
    val alpha: Float get() = opacity / 100f

    companion object {
        const val DEFAULT_SIZE = 20
        const val DEFAULT_OPACITY = 30
        const val MIN_OPACITY = 10

        /** The style with [size] and [opacity], each brought into its range, as read from settings or a PDF. */
        fun of(size: Int, opacity: Int) = MarkStyle(size.coerceIn(0, 100), opacity.coerceIn(MIN_OPACITY, 100))
    }
}

/**
 * Where the mark goes on a page as shown (its crop box, turned by its rotation): upright in its
 * bottom-right corner, kept from the right and bottom edges by [MARGIN] font sizes, in Helvetica Bold.
 * Its size sets the word's width as a share of the shown page's width: from [MIN_WIDTH], which is
 * barely readable on a whole page seen on a phone, to [MAX_WIDTH], where the word starts on the
 * page's middle. In between the share grows geometrically, so the small end, where most will want it,
 * has the finest control.
 */
object MarkLayout {
    const val TEXT = "Usugami"

    /** The word's width in Helvetica Bold, in font sizes: its letters' widths are 4223 thousandths. */
    const val TEXT_WIDTH = 4.223f

    /** How far its "g" reaches below the baseline, in font sizes. */
    const val DESCENT = 0.217f

    /** How far the word keeps from the shown page's right and bottom edges, in font sizes. */
    const val MARGIN = 1f

    /** The word's width as a share of the shown page's width at the smallest size: about a 5.6 pt font on A4. */
    const val MIN_WIDTH = 0.04f

    /** At the largest size: the word then starts on the middle of the page. */
    const val MAX_WIDTH = TEXT_WIDTH / (2 * (TEXT_WIDTH + MARGIN))

    /** The font size is at most this share of the shown page's height, for a page much wider than tall. */
    const val MAX_HEIGHT = 0.25f

    /** The mark's grey, from 0 (black) to 1 (white): solid at full opacity, a light grey at 30%. */
    const val GRAY = 0.2f

    /** The word's width as a share of the shown page's width at [size] (0 to 100). */
    fun widthShare(size: Int): Float = MIN_WIDTH * (MAX_WIDTH / MIN_WIDTH).pow(size.coerceIn(0, 100) / 100f)

    /** Where to write the word on the shown page: its baseline starts at ([x], [y]), in [fontSize]. */
    data class Placement(val x: Float, val y: Float, val fontSize: Float)

    /** Where the word goes in [style] on a page [shownWidth] × [shownHeight] as shown (origin bottom-left, y up). */
    fun place(style: MarkStyle, shownWidth: Float, shownHeight: Float): Placement {
        require(shownWidth > 0 && shownHeight > 0)
        val fontSize = min(widthShare(style.size) * shownWidth / TEXT_WIDTH, MAX_HEIGHT * shownHeight)
        return Placement(shownWidth - (MARGIN + TEXT_WIDTH) * fontSize, (MARGIN + DESCENT) * fontSize, fontSize)
    }
}
