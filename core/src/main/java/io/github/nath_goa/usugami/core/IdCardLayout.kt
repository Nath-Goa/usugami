package io.github.nath_goa.usugami.core

import kotlin.math.max
import kotlin.math.min

/**
 * Where an ID card's sides go on their page: at the card's real size, the front above the back,
 * each centred across the page and the pair centred down it, [GAP_MM] apart. A side's long edge is
 * [CARD_LONG_MM] (ID-1: ID cards, driving licences, bank cards) and its other edge follows from the
 * side's own proportions as cropped; sides squarer than a card's are a bigger document's, such as a
 * passport's page, and get [LARGE_LONG_MM]. When that doesn't fit inside the margins, both sides and
 * the gap shrink together until it does; nothing is enlarged past those sizes and nothing is cut.
 */
object IdCardLayout {
    /** An ID-1 card's long edge (ISO/IEC 7810: 85.60 mm x 53.98 mm). */
    const val CARD_LONG_MM = 85.6

    /** A bigger document's long edge: a passport's page (ID-3, 125 mm x 88 mm). */
    const val LARGE_LONG_MM = 125.0

    /** Sides whose long edge is, on average, at least this many times the short one are a card's. */
    const val MIN_CARD_RATIO = 1.48

    /** Between the front and the back. */
    const val GAP_MM = 10.0

    /**
     * A side keeps this many times the compression level's pixels per inch (see Compressor.scaleFor):
     * it is drawn small, and its print is half the size of a letter's.
     */
    const val DETAIL = 2.0

    /**
     * Where a card's sides go on their page: [sides] are each side's pixel size as it shows
     * (after EXIF orientation and the user's turn), front first; one or two. Returns one
     * PageGeometry per side, all with the same page box.
     *
     * On a paper size the page is portrait unless [settings] asks for landscape ("Auto" is portrait
     * for a card's page). On [PageSize.FIT] the page is as big as the sides at real size with the gap
     * between them, plus the margin in proportion, as [PageLayout] makes Fit pages.
     */
    fun compute(sides: List<Pair<Int, Int>>, settings: LayoutSettings): List<PageGeometry> {
        require(sides.size in 1..2) { "A card has one or two sides, not ${sides.size}" }
        require(sides.all { (w, h) -> w > 0 && h > 0 }) { "Invalid side size $sides" }

        val card = sides.map { (w, h) -> max(w, h).toDouble() / min(w, h) }.average() >= MIN_CARD_RATIO
        val long = points(if (card) CARD_LONG_MM else LARGE_LONG_MM)
        // Each side's natural size: its long edge [long], its other edge in its own proportions.
        val widths = DoubleArray(sides.size)
        val heights = DoubleArray(sides.size)
        sides.forEachIndexed { i, (w, h) ->
            if (w >= h) {
                widths[i] = long
                heights[i] = long * h / w
            } else {
                heights[i] = long
                widths[i] = long * w / h
            }
        }
        val gap = if (sides.size == 2) points(GAP_MM) else 0.0
        val contentW = widths.max()
        val contentH = heights.sum() + gap

        val pageW: Double
        val pageH: Double
        val scale: Double
        if (settings.pageSize == PageSize.FIT) {
            // The margin in proportion to the content, as PageLayout's Fit pages have it.
            val m = settings.margin.points / PageSize.A4.shortSide * min(contentW, contentH)
            pageW = max(contentW + 2 * m, PageLayout.MIN_PAGE_SIDE)
            pageH = max(contentH + 2 * m, PageLayout.MIN_PAGE_SIDE)
            scale = 1.0
        } else {
            val landscape = settings.orientation == PageOrientation.LANDSCAPE
            val size = settings.pageSize
            pageW = if (landscape) size.longSide else size.shortSide
            pageH = if (landscape) size.shortSide else size.longSide
            val m = settings.margin.points
            scale = min(1.0, min((pageW - 2 * m) / contentW, (pageH - 2 * m) / contentH))
        }

        // The group centred down the page, each side centred across it, the front on top.
        var top = (pageH - contentH * scale) / 2
        return sides.indices.map { i ->
            val w = widths[i] * scale
            val h = heights[i] * scale
            val geometry = PageGeometry(pageW, pageH, (pageW - w) / 2, pageH - top - h, w, h)
            top += h + gap * scale
            geometry
        }
    }

    private fun points(mm: Double) = mm * 72.0 / 25.4
}
