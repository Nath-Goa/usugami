package io.github.nath_goa.usugami.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The pages of a translated PDF: each is a picture of the page, with the translated text on it as real text. */
object TranslatedPages {
    /**
     * Pages are drawn at this resolution: the translated text over them is real text, sharp at any
     * zoom, so the picture only has to keep what isn't translated (pictures, numbers, lines)
     * readable, with fewer bytes than Redact PDF's 200 dpi…
     */
    const val DPI = 150

    /** …but with at most this many pixels, so that a poster-sized page still fits in memory. */
    const val MAX_PIXELS = 12_000_000

    /** How many pixels wide and tall a page [shownWidth] × [shownHeight] points big is drawn. */
    fun pixelSize(shownWidth: Float, shownHeight: Float): Pair<Int, Int> {
        require(shownWidth > 0 && shownHeight > 0)
        val perPoint = min(DPI / 72f, sqrt(MAX_PIXELS / (shownWidth.toDouble() * shownHeight)).toFloat())
        return max(1, (shownWidth * perPoint).roundToInt()) to max(1, (shownHeight * perPoint).roundToInt())
    }
}
