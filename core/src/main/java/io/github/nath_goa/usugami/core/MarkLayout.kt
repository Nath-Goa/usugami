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
 * One shape of the mark ([MarkLayout.LAYERS]): its contours, filled by the nonzero winding rule in
 * [rgb] (0xRRGGBB) at [alpha], within the mark as a whole, whose own opacity the user sets.
 */
class MarkLayer internal constructor(val rgb: Int, val alpha: Float, private val outline: String) {
    val red: Float get() = (rgb shr 16 and 0xFF) / 255f
    val green: Float get() = (rgb shr 8 and 0xFF) / 255f
    val blue: Float get() = (rgb and 0xFF) / 255f

    /**
     * Draws its contours with [pen], to be filled by the nonzero winding rule: at [fontSize] (in font
     * sizes by default), the word's baseline starting at the origin, y up.
     */
    fun draw(pen: MarkLayout.Pen, fontSize: Float = 1f) {
        val scale = fontSize / MarkGlyphs.UNITS_PER_EM
        val tokens = outline.split(' ').filter { it.isNotEmpty() }
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

/**
 * Where the mark goes on a page as shown (its crop box, turned by its rotation): upright in its
 * bottom-right corner, kept from the right and bottom edges by [MARGIN] font sizes. The mark is the
 * word "Usugami" as the banners write it (Segoe UI Semibold, in the logo's deep purple) with the app's
 * logo to its right ([MarkGlyphs]): filled shapes rather than text, so a PDF's text search and copy,
 * and screen readers, don't find it. Its size sets the mark's width as a share of the shown page's
 * width: from [MIN_WIDTH], where the word is barely readable on a whole page seen on a phone, to
 * [MAX_WIDTH], where the mark starts on the page's middle. In between the share grows geometrically,
 * so the small end, where most will want it, has the finest control.
 */
object MarkLayout {
    const val TEXT = "Usugami"

    /** The mark's width, in font sizes: the word, a gap, and the logo. */
    const val WIDTH = MarkGlyphs.ADVANCE / MarkGlyphs.UNITS_PER_EM

    /** How far it reaches below the word's baseline, in font sizes: the word's "g". */
    const val DESCENT = -MarkGlyphs.Y_MIN / MarkGlyphs.UNITS_PER_EM

    /** Where its outlines start and end, from the word's baseline's start, in font sizes. */
    const val INK_LEFT = MarkGlyphs.X_MIN / MarkGlyphs.UNITS_PER_EM
    const val INK_RIGHT = MarkGlyphs.X_MAX / MarkGlyphs.UNITS_PER_EM

    /** How far it reaches above the word's baseline, in font sizes: the logo's circle. */
    const val INK_TOP = MarkGlyphs.Y_MAX / MarkGlyphs.UNITS_PER_EM

    /** How far the mark keeps from the shown page's right and bottom edges, in font sizes. */
    const val MARGIN = 1f

    /** The mark's width as a share of the shown page's width at the smallest size: the word in about a 5.6 pt font on A4. */
    const val MIN_WIDTH = WIDTH * 0.0095f

    /** At the largest size: the mark then starts on the middle of the page. */
    const val MAX_WIDTH = WIDTH / (2 * (WIDTH + MARGIN))

    /** The font size is at most this share of the shown page's height, for a page much wider than tall. */
    const val MAX_HEIGHT = 0.25f

    /**
     * The mark's shapes, painted in this order: the word in the deep purple #4A3F6B; then the logo,
     * its lilac #E8DEF8 circle, the deep-purple disc, the sheet of paper over it in white at 0.55, and
     * the sheet's outline in the deep purple at 0.4.
     */
    val LAYERS: List<MarkLayer> get() = MarkGlyphs.LAYERS

    /** The mark's width as a share of the shown page's width at [size] (0 to 100). */
    fun widthShare(size: Int): Float = MIN_WIDTH * (MAX_WIDTH / MIN_WIDTH).pow(size.coerceIn(0, 100) / 100f)

    /** Where to draw the mark on the shown page: the word's baseline starts at ([x], [y]), in [fontSize]. */
    data class Placement(val x: Float, val y: Float, val fontSize: Float)

    /** Where the mark goes in [style] on a page [shownWidth] × [shownHeight] as shown (origin bottom-left, y up). */
    fun place(style: MarkStyle, shownWidth: Float, shownHeight: Float): Placement {
        require(shownWidth > 0 && shownHeight > 0)
        val fontSize = min(widthShare(style.size) * shownWidth / WIDTH, MAX_HEIGHT * shownHeight)
        return Placement(shownWidth - (MARGIN + WIDTH) * fontSize, (MARGIN + DESCENT) * fontSize, fontSize)
    }

    /** Draws a path: a layer's outlines, for [MarkLayer.draw]. */
    interface Pen {
        fun moveTo(x: Float, y: Float)
        fun lineTo(x: Float, y: Float)
        fun quadTo(x1: Float, y1: Float, x: Float, y: Float)
        fun close()
    }

    /**
     * The name [pdfMark] gives the graphics state of [LAYERS]' layer [index], one painted at an
     * opacity below 1: the PDF's resources give it that opacity (ca).
     */
    fun pdfStateName(index: Int): String = "L$index"

    /**
     * PDF content that paints the mark's layers, each filled in its colour and opacity: at a font size
     * of 1, the word's baseline starting at the origin. It is shapes, not text: there is nothing to
     * find, copy or read out. Drawn as a transparency group, it takes the user's opacity as a whole.
     */
    fun pdfMark(): String = StringBuilder().apply {
        fun line(vararg parts: Any) = append(parts.joinToString(" ")).append('\n')
        fun number(value: Float, decimals: Int = 2) =
            BigDecimal(value.toDouble()).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        // In the font's units, which keep the numbers short and exact.
        val scale = BigDecimal.ONE.divide(BigDecimal(MarkGlyphs.UNITS_PER_EM)).toPlainString()
        line(scale, 0, 0, scale, 0, 0, "cm")
        for ((index, layer) in LAYERS.withIndex()) {
            line("q")
            if (layer.alpha < 1f) line("/${pdfStateName(index)}", "gs")
            line(number(layer.red, 4), number(layer.green, 4), number(layer.blue, 4), "rg")
            var x0 = 0f
            var y0 = 0f
            var startX = 0f
            var startY = 0f
            layer.draw(
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
                MarkGlyphs.UNITS_PER_EM.toFloat(),
            )
            line("f")
            line("Q")
        }
    }.toString()
}
