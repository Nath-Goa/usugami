package io.github.nath_goa.usugami.core

import java.math.BigDecimal
import java.math.RoundingMode
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
 * bottom-right corner, kept from the right and bottom edges by [MARGIN] font sizes. The word is drawn
 * as outlines of Liberation Sans Bold ([MarkGlyphs]), filled shapes rather than text, so a PDF's text
 * search and copy, and screen readers, don't find it. Its size sets the word's width as a share of the
 * shown page's width: from [MIN_WIDTH], which is barely readable on a whole page seen on a phone, to
 * [MAX_WIDTH], where the word starts on the page's middle. In between the share grows geometrically,
 * so the small end, where most will want it, has the finest control.
 */
object MarkLayout {
    const val TEXT = "Usugami"

    /** The word's width, in font sizes: its letters' widths, the same as in Helvetica Bold. */
    const val TEXT_WIDTH = MarkGlyphs.ADVANCE.toFloat() / MarkGlyphs.UNITS_PER_EM

    /** How far its "g" reaches below the baseline, in font sizes. */
    const val DESCENT = -MarkGlyphs.Y_MIN.toFloat() / MarkGlyphs.UNITS_PER_EM

    /** Where its letters' outlines start and end, from the baseline's start, in font sizes. */
    const val INK_LEFT = MarkGlyphs.X_MIN.toFloat() / MarkGlyphs.UNITS_PER_EM
    const val INK_RIGHT = MarkGlyphs.X_MAX.toFloat() / MarkGlyphs.UNITS_PER_EM

    /** How far its "i" reaches above the baseline, in font sizes. */
    const val INK_TOP = MarkGlyphs.Y_MAX.toFloat() / MarkGlyphs.UNITS_PER_EM

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

    /** Draws a path: the word's outlines, for [drawWord]. */
    interface Pen {
        fun moveTo(x: Float, y: Float)
        fun lineTo(x: Float, y: Float)
        fun quadTo(x1: Float, y1: Float, x: Float, y: Float)
        fun close()
    }

    /**
     * Draws the word's outlines with [pen], to be filled by the nonzero winding rule: at [fontSize] (in
     * font sizes by default), the baseline starting at the origin, y up.
     */
    fun drawWord(pen: Pen, fontSize: Float = 1f) = trace(fontSize / MarkGlyphs.UNITS_PER_EM, pen)

    /**
     * PDF content that fills the word's outlines in the colour set: at a font size of 1, the baseline
     * starting at the origin. It is a shape, not text: there is nothing to find, copy or read out.
     */
    fun pdfWord(): String = StringBuilder().apply {
        fun line(vararg parts: Any) = append(parts.joinToString(" ")).append('\n')
        fun number(value: Float) = BigDecimal(value.toDouble()).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        // In the font's units, which keep the numbers short and exact.
        val scale = BigDecimal.ONE.divide(BigDecimal(MarkGlyphs.UNITS_PER_EM)).toPlainString()
        line(scale, 0, 0, scale, 0, 0, "cm")
        var x0 = 0f
        var y0 = 0f
        var startX = 0f
        var startY = 0f
        trace(
            1f,
            object : Pen {
                override fun moveTo(x: Float, y: Float) {
                    line(number(x), number(y), "m")
                    x0 = x
                    y0 = y
                    startX = x
                    startY = y
                }

                override fun lineTo(x: Float, y: Float) {
                    line(number(x), number(y), "l")
                    x0 = x
                    y0 = y
                }

                // PDF has cubic curves only: this one is the same curve.
                override fun quadTo(x1: Float, y1: Float, x: Float, y: Float) {
                    val c1x = x0 + 2 * (x1 - x0) / 3
                    val c1y = y0 + 2 * (y1 - y0) / 3
                    val c2x = x + 2 * (x1 - x) / 3
                    val c2y = y + 2 * (y1 - y) / 3
                    line(number(c1x), number(c1y), number(c2x), number(c2y), number(x), number(y), "c")
                    x0 = x
                    y0 = y
                }

                override fun close() {
                    line("h")
                    x0 = startX
                    y0 = startY
                }
            },
        )
        line("f")
    }.toString()

    /** Reads [MarkGlyphs.OUTLINE] to [pen], each number times [scale]. */
    private fun trace(scale: Float, pen: Pen) {
        val tokens = MarkGlyphs.OUTLINE.split(' ').filter { it.isNotEmpty() }
        var i = 0
        fun next() = tokens[i++].toFloat() * scale
        while (i < tokens.size) {
            when (val command = tokens[i++]) {
                "M" -> pen.moveTo(next(), next())
                "L" -> pen.lineTo(next(), next())
                "Q" -> pen.quadTo(next(), next(), next(), next())
                "Z" -> pen.close()
                else -> error("Unknown outline command $command")
            }
        }
    }
}
