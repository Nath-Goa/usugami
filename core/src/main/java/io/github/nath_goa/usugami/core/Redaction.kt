package io.github.nath_goa.usugami.core

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A box Redact PDF blacks out, on page [page] (zero-based) as it is shown: its crop box, turned by
 * its /Rotate. Its sides are fractions of that page: [left] and [right] from its left edge, [top]
 * and [bottom] from its top edge, as on a screen. The preview and the saved page both work from
 * these, so they match whatever size each is drawn at.
 */
data class RedactBox(val page: Int, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * A glyph's box on a page as shown, in points from the page's top-left corner (y down). [across]
 * says whether its line of text runs across the page, rather than up or down it.
 */
data class GlyphBox(val left: Float, val top: Float, val right: Float, val bottom: Float, val across: Boolean)

object Redaction {
    /** Redacted pages are drawn at this resolution, where even small print stays easy to read… */
    const val DPI = 200

    /** …but with at most this many pixels, so that a poster-sized page still fits in memory. */
    const val MAX_PIXELS = 12_000_000

    /** The smallest a box gets, as a share of the page's width and of its height. */
    const val MIN_SIZE = 0.005f

    /** Each arrow or size button moves or changes a box by this share of the page. */
    const val STEP = 0.005f

    /** More boxes than this aren't kept: they live in the screen's saved state. */
    const val MAX_BOXES = 2000

    /** A box Find text puts around a line of a match reaches this share of the line's height beyond its letters. */
    const val TEXT_MARGIN = 0.15f

    /** The box with corners ([x0], [y0]) and ([x1], [y1]), in either order, cut to the page. */
    fun between(page: Int, x0: Float, y0: Float, x1: Float, y1: Float): RedactBox = RedactBox(
        page,
        min(x0, x1).coerceIn(0f, 1f),
        min(y0, y1).coerceIn(0f, 1f),
        max(x0, x1).coerceIn(0f, 1f),
        max(y0, y1).coerceIn(0f, 1f),
    )

    /** [box] at least [MIN_SIZE] wide and tall, moved as little as needed to be wholly on the page. */
    fun clamp(box: RedactBox): RedactBox {
        val width = box.width.coerceIn(MIN_SIZE, 1f)
        val height = box.height.coerceIn(MIN_SIZE, 1f)
        val left = box.left.coerceIn(0f, 1f - width)
        val top = box.top.coerceIn(0f, 1f - height)
        return RedactBox(box.page, left, top, left + width, top + height)
    }

    /** [box] moved by [dx] and [dy], the same size, stopping at the edges of the page. */
    fun moved(box: RedactBox, dx: Float, dy: Float): RedactBox =
        clamp(RedactBox(box.page, box.left + dx, box.top + dy, box.right + dx, box.bottom + dy))

    /** [box] made [dw] wider and [dh] taller around its centre, at least [MIN_SIZE], on the page. */
    fun resized(box: RedactBox, dw: Float, dh: Float): RedactBox {
        val width = (box.width + dw).coerceIn(MIN_SIZE, 1f)
        val height = (box.height + dh).coerceIn(MIN_SIZE, 1f)
        val centerX = (box.left + box.right) / 2
        val centerY = (box.top + box.bottom) / 2
        return clamp(RedactBox(box.page, centerX - width / 2, centerY - height / 2, centerX + width / 2, centerY + height / 2))
    }

    /** [box] with its bottom-right corner dragged to ([right], [bottom]); its top-left corner stays. */
    fun withCorner(box: RedactBox, right: Float, bottom: Float): RedactBox = clamp(
        box.copy(right = right.coerceIn(box.left + MIN_SIZE, 1f), bottom = bottom.coerceIn(box.top + MIN_SIZE, 1f)),
    )

    /**
     * A new box for the "Add box" button on a page [shownWidth] × [shownHeight] points big: two
     * inches wide and a line of text tall, centred across the page. [offset], the number of boxes
     * already on the page, moves it down a little each time, so boxes don't land on each other.
     */
    fun default(page: Int, shownWidth: Float, shownHeight: Float, offset: Int): RedactBox {
        require(shownWidth > 0 && shownHeight > 0)
        val width = min(144f / shownWidth, 0.6f)
        val height = min(24f / shownHeight, 0.2f)
        val centerY = 0.2f + (Math.floorMod(offset, 1000) * 0.07f + 0.3f) % 0.6f
        return clamp(RedactBox(page, 0.5f - width / 2, centerY - height / 2, 0.5f + width / 2, centerY + height / 2))
    }

    /** How many pixels wide and tall a page [shownWidth] × [shownHeight] points big is drawn. */
    fun pixelSize(shownWidth: Float, shownHeight: Float): Pair<Int, Int> {
        require(shownWidth > 0 && shownHeight > 0)
        val perPoint = min(DPI / 72f, sqrt(MAX_PIXELS / (shownWidth.toDouble() * shownHeight)).toFloat())
        return max(1, (shownWidth * perPoint).roundToInt()) to max(1, (shownHeight * perPoint).roundToInt())
    }

    /**
     * The pixels [box] covers in a picture of its page [width] × [height] pixels big, as left, top,
     * right and bottom (right and bottom exclusive). Rounded outwards: a pixel the box only partly
     * covers is blacked out too.
     */
    fun pixelBounds(box: RedactBox, width: Int, height: Int): IntArray = intArrayOf(
        floor(box.left * width).toInt().coerceIn(0, width),
        floor(box.top * height).toInt().coerceIn(0, height),
        ceil(box.right * width).toInt().coerceIn(0, width),
        ceil(box.bottom * height).toInt().coerceIn(0, height),
    )

    /**
     * The index in [boxes] of the box on [page] at ([x], [y]), counting a box as [reachX] and
     * [reachY] bigger on each side, so a thin box is easy to tap; -1 for none. Of boxes on top of
     * each other, the smallest wins, so a small box inside a big one can still be picked.
     */
    fun hit(boxes: List<RedactBox>, page: Int, x: Float, y: Float, reachX: Float, reachY: Float): Int {
        var best = -1
        var bestArea = Float.MAX_VALUE
        boxes.forEachIndexed { index, box ->
            if (box.page != page) return@forEachIndexed
            if (x < box.left - reachX || x > box.right + reachX || y < box.top - reachY || y > box.bottom + reachY) return@forEachIndexed
            val area = box.width * box.height
            if (area < bestArea) {
                best = index
                bestArea = area
            }
        }
        return best
    }

    /** Whether [a] and [b] are the same box, give or take a rounding error. */
    fun same(a: RedactBox, b: RedactBox): Boolean = a.page == b.page &&
        abs(a.left - b.left) < 1e-4f && abs(a.top - b.top) < 1e-4f && abs(a.right - b.right) < 1e-4f && abs(a.bottom - b.bottom) < 1e-4f

    /** [boxes] as numbers, five for each, as the screen's saved state keeps them. */
    fun pack(boxes: List<RedactBox>): FloatArray = FloatArray(boxes.size * 5).also { values ->
        boxes.forEachIndexed { i, box ->
            values[5 * i] = box.page.toFloat()
            values[5 * i + 1] = box.left
            values[5 * i + 2] = box.top
            values[5 * i + 3] = box.right
            values[5 * i + 4] = box.bottom
        }
    }

    /** The boxes [pack] made [values] from. */
    fun unpack(values: FloatArray): List<RedactBox> = (0 until values.size / 5).map { i ->
        RedactBox(values[5 * i].roundToInt(), values[5 * i + 1], values[5 * i + 2], values[5 * i + 3], values[5 * i + 4])
    }

    /**
     * Boxes over the glyphs of one match of Find text, given in reading order on a page [shownWidth]
     * × [shownHeight] points big: one box for each line the match is on, reaching [TEXT_MARGIN] of
     * the line's height beyond the letters.
     */
    fun boxesFor(page: Int, glyphs: List<GlyphBox>, shownWidth: Float, shownHeight: Float): List<RedactBox> {
        require(shownWidth > 0 && shownHeight > 0)
        val lines = ArrayList<MutableList<GlyphBox>>()
        for (glyph in glyphs) {
            val line = lines.lastOrNull()
            if (line != null && sameLine(line.last(), glyph)) line += glyph else lines += mutableListOf(glyph)
        }
        return lines.map { line ->
            val left = line.minOf { it.left }
            val top = line.minOf { it.top }
            val right = line.maxOf { it.right }
            val bottom = line.maxOf { it.bottom }
            val thickness = if (line[0].across) bottom - top else right - left
            val pad = max(0.5f, TEXT_MARGIN * thickness)
            clamp(between(page, (left - pad) / shownWidth, (top - pad) / shownHeight, (right + pad) / shownWidth, (bottom + pad) / shownHeight))
        }
    }

    /** Whether [b] carries on the line [a] is on: the same direction, side by side, not far apart. */
    private fun sameLine(a: GlyphBox, b: GlyphBox): Boolean {
        if (a.across != b.across) return false
        // Along the line and across it, in the line's own direction.
        val (aStart, aEnd, aLow, aHigh) = if (a.across) listOf(a.left, a.right, a.top, a.bottom) else listOf(a.top, a.bottom, a.left, a.right)
        val (bStart, bEnd, bLow, bHigh) = if (a.across) listOf(b.left, b.right, b.top, b.bottom) else listOf(b.top, b.bottom, b.left, b.right)
        val thickness = max(aHigh - aLow, bHigh - bLow)
        val overlap = min(aHigh, bHigh) - max(aLow, bLow)
        if (overlap < 0.5f * min(aHigh - aLow, bHigh - bLow)) return false
        val gap = max(bStart - aEnd, aStart - bEnd)
        return gap <= 3 * thickness
    }
}

/**
 * A page's text, for Find text: each character knows the glyph it came from. Letters are compared
 * ignoring case and accents (é finds e), and every gap between words or lines is a single space,
 * so a phrase is found across a line break.
 */
class PageText private constructor(val text: String, private val owners: IntArray) {
    class Builder {
        private val text = StringBuilder()
        private var owners = IntArray(256)

        private fun append(c: Char, owner: Int) {
            if (text.length == owners.size) owners = owners.copyOf(owners.size * 2)
            owners[text.length] = owner
            text.append(c)
        }

        /** Adds glyph [index], which reads as [unicode]. */
        fun glyph(index: Int, unicode: String) {
            for (c in fold(unicode)) if (c == ' ') space() else append(c, index)
        }

        /** A break between words or lines. */
        fun space() {
            if (text.isNotEmpty() && text.last() != ' ') append(' ', -1)
        }

        fun build(): PageText = PageText(text.toString(), owners.copyOf(text.length))
    }

    /** For each place [query] is found, in order, the glyphs it covers, in reading order. */
    fun find(query: String): List<List<Int>> {
        val wanted = normalize(query)
        if (wanted.isEmpty()) return emptyList()
        val found = ArrayList<List<Int>>()
        var i = 0
        while (i + wanted.length <= text.length) {
            if (text.regionMatches(i, wanted, 0, wanted.length, ignoreCase = true)) {
                val glyphs = LinkedHashSet<Int>()
                for (k in i until i + wanted.length) if (owners[k] >= 0) glyphs += owners[k]
                if (glyphs.isNotEmpty()) found += glyphs.toList()
                i += wanted.length
            } else {
                i++
            }
        }
        return found
    }

    companion object {
        /**
         * [text] as Find text compares it: compatibility forms split up (the ligature ﬁ becomes f
         * and i), Latin, Greek and Cyrillic accents dropped, invisible format characters dropped,
         * and every kind of space a plain one.
         */
        fun fold(text: String): String {
            val split = Normalizer.normalize(text, Normalizer.Form.NFKD)
            val out = StringBuilder(split.length)
            for (c in split) {
                when {
                    c in '̀'..'ͯ' -> Unit
                    c.isWhitespace() -> out.append(' ')
                    Character.getType(c) == Character.FORMAT.toInt() || Character.isISOControl(c) -> Unit
                    else -> out.append(c)
                }
            }
            return out.toString()
        }

        /** [query] folded like page text, with its spaces trimmed and each run of them made one. */
        fun normalize(query: String): String = fold(query).trim().replace(Regex(" +"), " ")
    }
}
