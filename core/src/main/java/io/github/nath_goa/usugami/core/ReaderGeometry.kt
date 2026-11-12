package io.github.nath_goa.usugami.core

import kotlin.math.sqrt

/**
 * The reader's arithmetic, in the pixels of its page list. Pages lie one under another, each a page
 * item as wide as the list but for its margins, as tall as its shape makes it.
 */
object ReaderGeometry {
    /** A page's picture is at most this wide and this many pixels (16 MB as a bitmap). */
    const val BASE_MAX_WIDTH = 1600
    const val BASE_MAX_PIXELS = 4_000_000

    /**
     * And at most this long on its longer side: Android 8's renderer leaves out a picture larger than
     * the graphics chip takes in one piece (4096 on some), so a page far taller than wide would show
     * blank there. Such a page gets a softer picture; the sharp tile drawn for a page showing makes up for it.
     */
    const val BASE_MAX_SIDE = 4096

    /** A page item showing: page [index], its [top] in the list's viewport and its [height] (list pixels). */
    class Item(val index: Int, val top: Int, val height: Int)

    /** The page that fills most of a viewport [viewportHeight] list pixels tall; the first of those that fill as much. Null for none. */
    fun pageInView(items: List<Item>, viewportHeight: Int): Int? {
        var best: Item? = null
        var bestShown = 0
        for (item in items) {
            val shown = minOf(item.top + item.height, viewportHeight) - maxOf(item.top, 0)
            if (shown > bestShown) {
                best = item
                bestShown = shown
            }
        }
        return best?.index
    }

    /** The page item at list height [y], if any (gaps between pages belong to none). */
    fun itemAt(items: List<Item>, y: Float): Item? = items.firstOrNull { y >= it.top && y < it.top + it.height }

    /** The size of a page's picture: [itemWidth] pixels wide (the page item at zoom 1), cut to BASE_MAX_WIDTH, BASE_MAX_PIXELS and BASE_MAX_SIDE, in the page's shape; at least 1 × 1. */
    fun baseSize(shownWidth: Float, shownHeight: Float, itemWidth: Int): Pair<Int, Int> {
        // A page box that isn't a finite size of at least a point each way is taken as A4, as ReaderDocument does.
        val (w, h) = if (shownWidth.isFinite() && shownHeight.isFinite() && shownWidth >= 1f && shownHeight >= 1f) shownWidth to shownHeight else 595f to 842f
        var width = minOf(itemWidth, BASE_MAX_WIDTH).coerceAtLeast(1).toDouble()
        var height = width * h / w
        if (width * height > BASE_MAX_PIXELS) {
            val shrink = sqrt(BASE_MAX_PIXELS / (width * height))
            width *= shrink
            height *= shrink
        }
        val longer = maxOf(width, height)
        if (longer > BASE_MAX_SIDE) {
            val shrink = BASE_MAX_SIDE / longer
            width *= shrink
            height *= shrink
        }
        // Rounded down, so that the picture stays within the caps.
        return maxOf(1, width.toInt()) to maxOf(1, height.toInt())
    }
}
