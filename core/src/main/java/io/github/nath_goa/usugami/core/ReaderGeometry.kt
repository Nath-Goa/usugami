package io.github.nath_goa.usugami.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The reader's arithmetic. Pages lie one under another in a list, each a page item as wide as the
 * list but for its margins, as tall as its shape makes it. Three kinds of coordinates: **screen**,
 * pixels of the reader's page area, W × H; **list**, pixels of the page list, which is laid out W wide
 * and H / zoom tall, then scaled by zoom from its top-left corner and moved left by panX screen
 * pixels (screen x = list x · zoom − panX, screen y = list y · zoom, y from the list's top edge as it
 * shows); **page**, points on a page as shown, from its top-left corner.
 */
object ReaderGeometry {
    const val MIN_ZOOM = 0.5f
    const val MAX_ZOOM = 5f
    const val DOUBLE_TAP_ZOOM = 2.5f

    /** A page's picture is at most this wide and this many pixels (16 MB as a bitmap). */
    const val BASE_MAX_WIDTH = 1600
    const val BASE_MAX_PIXELS = 4_000_000

    /**
     * And at most this long on its longer side: Android 8's renderer leaves out a picture larger than
     * the graphics chip takes in one piece (4096 on some), so a page far taller than wide would show
     * blank there. Such a page gets a softer picture; the sharp tile drawn for a page showing makes up for it.
     */
    const val BASE_MAX_SIDE = 4096

    /** The sharp part is at most this many pixels (24 MB). */
    const val TILE_MAX_PIXELS = 6_000_000

    /**
     * How far the content is moved left at [zoom] on a screen [width] wide: [panX] kept between 0 and
     * (zoom − 1)·width, so the pages' edges never leave the screen's; zoomed out, the pages are
     * centred: −(1 − zoom)·width / 2.
     */
    fun clampPan(panX: Float, zoom: Float, width: Float): Float =
        if (zoom >= 1f) panX.coerceIn(0f, maxOf(0f, (zoom - 1f) * width)) else -(1f - zoom) * width / 2f

    /** A new zoom and pan, and how far the list must scroll forward (list pixels; negative, back) to go with them. */
    class Zoomed(val zoom: Float, val panX: Float, val scrollBy: Float)

    /**
     * The zoom changed by [factor] about the screen point ([focusX], [focusY]), kept within
     * MIN_ZOOM..MAX_ZOOM, on a screen [width] wide: the new zoom and pan, and how far the list must
     * scroll forward for what is under the point to stay under it. [panX] is the pan as applied (see
     * clampPan). Across the screen, what is under the point stays there unless the pan must be kept
     * on the pages (clampPan): near their edges, and zoomed out.
     */
    fun zoomAbout(zoom: Float, panX: Float, factor: Float, focusX: Float, focusY: Float, width: Float): Zoomed {
        val zoomed = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val pan = clampPan((focusX + panX) / zoom * zoomed - focusX, zoomed, width)
        return Zoomed(zoomed, pan, focusY / zoom - focusY / zoomed)
    }

    /** The list point under the screen point ([x], [y]) at [zoom] and [panX] (as applied). */
    fun toList(x: Float, y: Float, zoom: Float, panX: Float): Pair<Float, Float> = (x + panX) / zoom to y / zoom

    /** The screen point over the list point ([x], [y]) at [zoom] and [panX] (as applied). */
    fun toScreen(x: Float, y: Float, zoom: Float, panX: Float): Pair<Float, Float> = x * zoom - panX to y * zoom

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

    /**
     * A part of a page drawn sharp: the rectangle [left], [top], [width] × [height] in the page item's
     * own list pixels, drawn [pixelWidth] × [pixelHeight] pixels, [scale] to the list pixel, for
     * [zoom]. [scale] is the zoom (one pixel to the screen pixel) unless the part had to be drawn with
     * fewer pixels (TILE_MAX_PIXELS). Its corner is a whole pixel of the page drawn at [scale]
     * ([pixelLeft], [pixelTop]), so ReaderDocument.part draws exactly what this rectangle covers.
     */
    class Tile(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val pixelWidth: Int,
        val pixelHeight: Int,
        val zoom: Float,
        val scale: Float,
    ) {
        /** Where it starts in the page item drawn at [scale], in whole pixels: ReaderDocument.part's left and top. */
        val pixelLeft: Int get() = Math.round(left * scale)
        val pixelTop: Int get() = Math.round(top * scale)
    }

    /**
     * The part of the page item [item] ([itemLeft] from the list's left edge, [itemWidth] wide) that
     * shows on a [width] × [height] screen at [zoom] and [panX] (as applied), grown by a fifth of the
     * screen each way, cut to the item, drawn one pixel to the screen pixel; shrunk (less margin, then
     * fewer pixels) to stay within TILE_MAX_PIXELS. Null when none of it shows.
     */
    fun tile(item: Item, itemLeft: Float, itemWidth: Float, zoom: Float, panX: Float, width: Float, height: Float): Tile? {
        if (!(zoom > 0f) || !(itemWidth > 0f) || item.height <= 0) return null
        val shows = showing(item, itemLeft, itemWidth, zoom, panX, width, height) ?: return null
        val marginX = width / 5f / zoom
        val marginY = height / 5f / zoom
        fun grown(part: Float) = Area(
            maxOf(0f, shows.left - marginX * part),
            maxOf(0f, shows.top - marginY * part),
            minOf(itemWidth, shows.right + marginX * part),
            minOf(item.height.toFloat(), shows.bottom + marginY * part),
        )
        // The whole margin when it fits, else as much of it as fits.
        var margin = 1f
        if (snapped(grown(1f), zoom, itemWidth, item.height).pixelCount > TILE_MAX_PIXELS) {
            var low = 0f
            var high = 1f
            repeat(20) {
                val middle = (low + high) / 2f
                if (snapped(grown(middle), zoom, itemWidth, item.height).pixelCount > TILE_MAX_PIXELS) high = middle else low = middle
            }
            margin = low
        }
        var scale = zoom
        var part = snapped(grown(margin), scale, itemWidth, item.height)
        // Still too many with no margin at all (a screen of over 6 million pixels): fewer pixels.
        while (part.pixelCount > TILE_MAX_PIXELS) {
            scale *= (sqrt(TILE_MAX_PIXELS.toDouble() / part.pixelCount) * 0.99).toFloat()
            part = snapped(shows, scale, itemWidth, item.height)
        }
        return Tile(
            left = part.left / scale,
            top = part.top / scale,
            width = (part.right - part.left) / scale,
            height = (part.bottom - part.top) / scale,
            pixelWidth = part.right - part.left,
            pixelHeight = part.bottom - part.top,
            zoom = zoom,
            scale = scale,
        )
    }

    /**
     * Whether [tile] still serves [item] at [zoom] and [panX] (as applied): it was drawn within 5% of
     * this zoom, and it covers what shows of the item (but for a pixel at its edges).
     */
    fun serves(tile: Tile, item: Item, itemLeft: Float, itemWidth: Float, zoom: Float, panX: Float, width: Float, height: Float): Boolean {
        if (abs(zoom / tile.zoom - 1f) > 0.05f) return false
        val shows = showing(item, itemLeft, itemWidth, zoom, panX, width, height) ?: return true
        val slack = 1f / tile.scale
        return shows.left >= tile.left - slack && shows.top >= tile.top - slack &&
            shows.right <= tile.left + tile.width + slack && shows.bottom <= tile.top + tile.height + slack
    }

    /** A rectangle in a page item's own list pixels. */
    private class Area(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** A rectangle in whole pixels of a page item drawn at some scale. */
    private class Pixels(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val pixelCount: Long get() = (right - left).toLong() * (bottom - top)
    }

    /** What shows of [item] on the screen, in its own list pixels; null when nothing does. */
    private fun showing(item: Item, itemLeft: Float, itemWidth: Float, zoom: Float, panX: Float, width: Float, height: Float): Area? {
        val left = maxOf(0f, panX / zoom - itemLeft)
        val right = minOf(itemWidth, (panX + width) / zoom - itemLeft)
        val top = maxOf(0f, -item.top.toFloat())
        val bottom = minOf(item.height.toFloat(), height / zoom - item.top)
        return if (right > left && bottom > top) Area(left, top, right, bottom) else null
    }

    /**
     * [area] in whole pixels of the item drawn at [scale]: grown out to whole pixels, but not past the
     * item's last whole pixel; at least one pixel each way.
     */
    private fun snapped(area: Area, scale: Float, itemWidth: Float, itemHeight: Int): Pixels {
        val lastX = maxOf(1, floor(itemWidth * scale).toInt())
        val lastY = maxOf(1, floor(itemHeight * scale).toInt())
        val left = floor(area.left * scale).toInt().coerceIn(0, lastX - 1)
        val top = floor(area.top * scale).toInt().coerceIn(0, lastY - 1)
        val right = ceil(area.right * scale).toInt().coerceIn(left + 1, lastX)
        val bottom = ceil(area.bottom * scale).toInt().coerceIn(top + 1, lastY)
        return Pixels(left, top, right, bottom)
    }
}
