package io.github.nath_goa.usugami.core

import kotlin.math.max
import kotlin.math.min

/** A rectangle on a page as shown, in points from the page's top-left corner, y down. */
data class BlockArea(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Where a translation is drawn, and how big: in [size]-point type in [area]. [overflow] when it
 * doesn't fit even at the smallest size and in all the room it may have: it then runs on past the
 * area, as nothing is cut off.
 */
data class Fit(val size: Float, val area: BlockArea, val overflow: Boolean)

/**
 * Measures text as it will be drawn: the height in points of [text] in [sizePt]-point type wrapped
 * at [widthPt] points, as Android's StaticLayout wraps it. [widthPt] may be [TextFit.UNBOUNDED],
 * for the text on as few lines as it can have (one, unless it has line breaks).
 */
fun interface TextMeasurer {
    fun height(text: String, sizePt: Float, widthPt: Float): Float
}

/**
 * A translation fitted into its block's place. A translation is longer or shorter than the text
 * it replaces: one that fits stays as it is; one that doesn't first takes more room where the page
 * has some (see [limits]) at the block's own size, and only then gets smaller.
 */
object TextFit {
    /** Each smaller size tried is this share of the one before: 7 % smaller. */
    const val SHRINK_STEP = 0.93f

    /** The smallest a translation is made, as a share of its block's size, unless told otherwise… */
    const val MIN_SCALE = 0.55f

    /** …and never smaller than this many points. */
    const val MIN_SIZE = 5f

    /** A block grows up to this many points short of another. */
    const val NEIGHBOUR_GAP = 6f

    /** The page's margin, where blocks don't grow: this share of the page's width (or height)… */
    const val MARGIN_SHARE = 0.04f

    /** …and at least this many points. */
    const val MIN_MARGIN = 12f

    /** A width wider than any text on one line, to measure it on as few lines as it can have. */
    const val UNBOUNDED = 100_000f

    /** Text up to this share of its size taller than its room still fits: what rounding leaves over. */
    private const val SLACK = 0.05f

    /** A width a single line grows to is found to within this many points. */
    private const val WIDTH_PRECISION = 0.25f

    /** At most this many halvings to find that width, and this many sizes tried: neither ever runs on. */
    private const val MAX_TRIES = 64

    /**
     * How far [block] may grow on its page [pageWidth] × [pageHeight] points big, given every
     * other block of the page, also those that aren't translated and stay as they are
     * ([others]; [block] itself among them is left out). A single line grows sideways, towards
     * where its alignment lets it end (right of a left-to-right line aligned at its start, left of
     * a right-to-left one; the other way for [BlockAlign.END]; both ways for [BlockAlign.CENTER]),
     * up to [NEIGHBOUR_GAP] short of the nearest block beside it (one that overlaps it in height
     * and starts beyond its middle) or up to the page's margin ([MARGIN_SHARE] of its
     * width, at least [MIN_MARGIN]). A block of several lines keeps its width and grows down, up to
     * [NEIGHBOUR_GAP] above the nearest block under it (overlapping it sideways and starting below
     * its middle) or to the page's bottom margin. The result holds the block's own box: a block
     * already in the margin or against a neighbour doesn't grow that way. A side that isn't a
     * number doesn't grow.
     */
    fun limits(block: SourceBlock, others: List<SourceBlock>, pageWidth: Float, pageHeight: Float): BlockArea {
        val own = box(block)
        if (!own.isFinite()) return own
        val neighbours = others.filter { it !== block }.map(::box).filter { it.isFinite() }
        if (block.lines.size <= 1) {
            val (growsLeft, growsRight) = when (block.align) {
                BlockAlign.CENTER -> true to true
                BlockAlign.START -> block.rtl to !block.rtl
                BlockAlign.END -> !block.rtl to block.rtl
            }
            val margin = margin(pageWidth)
            val middle = (own.left + own.right) / 2
            var left = own.left
            var right = own.right
            if (growsRight) {
                var limit = if (margin != null) pageWidth - margin else own.right
                for (other in neighbours) if (acrossFrom(other, own) && other.left > middle) limit = min(limit, other.left - NEIGHBOUR_GAP)
                right = max(own.right, limit)
            }
            if (growsLeft) {
                var limit = margin ?: own.left
                for (other in neighbours) if (acrossFrom(other, own) && other.right < middle) limit = max(limit, other.right + NEIGHBOUR_GAP)
                left = min(own.left, limit)
            }
            return BlockArea(left, own.top, right, own.bottom)
        }
        val margin = margin(pageHeight)
        val middle = (own.top + own.bottom) / 2
        var limit = if (margin != null) pageHeight - margin else own.bottom
        for (other in neighbours) {
            if (min(other.right, own.right) - max(other.left, own.left) > 0f && other.top > middle) limit = min(limit, other.top - NEIGHBOUR_GAP)
        }
        return BlockArea(own.left, own.top, own.right, max(own.bottom, limit))
    }

    /**
     * How [text], a translation of a block whose text is [size] points, is drawn: in the block's
     * own box [area], growing within [limits] (see [limits]), and in what size, as measured by
     * [measurer]. In this order:
     * 1. In [area] at [size], when it fits there: then nothing changes, so a text that fits is
     *    never moved, grown or made smaller.
     * 2. At [size] in the smallest area within [limits] where it fits: wider (both ways alike
     *    while there is room on both sides) and else, at the most width, taller.
     * 3. At sizes each [SHRINK_STEP] of the one before, down to [size] × [minScale] but not under
     *    [MIN_SIZE] points (nor over [size]): the first that fits in all the room [limits] give,
     *    in the smallest area where it fits.
     * 4. At that smallest size in all the room, with [Fit.overflow].
     *
     * Text fits in an area when, wrapped at the area's width, it is no taller than the area or
     * than its own lines at their fewest: a single line always fits a single line's box, however
     * tall its letters. [area]'s top never moves. A [size] or an [area] that isn't a number gives
     * them back as they are, with [Fit.overflow]. The result depends only on what is given.
     */
    fun fit(text: String, size: Float, area: BlockArea, limits: BlockArea, measurer: TextMeasurer, minScale: Float = MIN_SCALE): Fit {
        if (!size.isFinite() || size <= 0f || !area.isFinite()) return Fit(size, area, true)
        val box = BlockArea(min(area.left, area.right), min(area.top, area.bottom), max(area.left, area.right), max(area.top, area.bottom))
        val room = BlockArea(
            min(box.left, limits.left.orElse(box.left)),
            box.top,
            max(box.right, limits.right.orElse(box.right)),
            max(box.bottom, limits.bottom.orElse(box.bottom)),
        )
        val scale = if (minScale.isFinite() && minScale > 0f) min(minScale, 1f) else MIN_SCALE
        val smallest = min(size, max(size * scale, MIN_SIZE))
        val fitting = Fitting(text, box, room, measurer)
        if (fitting.fits(size, box.width, box.height)) return Fit(size, area, false)
        fitting.grown(size)?.let { return Fit(size, it, false) }
        var tried = size
        for (i in 0 until MAX_TRIES) {
            tried = max(smallest, tried * SHRINK_STEP)
            if (fitting.fits(tried, room.width, room.height)) return Fit(tried, fitting.grown(tried) ?: room, false)
            if (tried <= smallest) break
        }
        return Fit(smallest, room, true)
    }

    /** Fitting [text] in [box], growing within [room] (which holds it, with the same top). */
    private class Fitting(val text: String, val box: BlockArea, val room: BlockArea, val measurer: TextMeasurer) {
        /** Heights measured, by size and width: the same question is never asked twice. */
        private val heights = HashMap<Pair<Float, Float>, Float>()

        fun measured(size: Float, width: Float): Float = heights.getOrPut(size to width) { measurer.height(text, size, width) }

        fun fits(size: Float, width: Float, height: Float): Boolean =
            measured(size, width) <= max(height, measured(size, UNBOUNDED)) + SLACK * size

        /** The smallest area within [room] where the text fits at [size]: [box] itself when it fits there; null when none does. */
        fun grown(size: Float): BlockArea? {
            if (fits(size, box.width, box.height)) return box
            val before = box.left - room.left
            val after = room.right - box.right
            if (before + after > 0f && fits(size, room.width, box.height)) {
                var narrow = box.width
                var wide = room.width
                var tries = 0
                while (wide - narrow > WIDTH_PRECISION && tries++ < MAX_TRIES) {
                    val middle = (narrow + wide) / 2
                    if (fits(size, middle, box.height)) wide = middle else narrow = middle
                }
                return widened(wide - box.width, before, after)
            }
            if (room.height > box.height && fits(size, room.width, room.height)) {
                val wide = widened(before + after, before, after)
                val needed = measured(size, room.width).coerceIn(box.height, room.height)
                return BlockArea(wide.left, box.top, wide.right, box.top + needed)
            }
            return null
        }

        /** [box] made [extra] wider: half on each side while both have room ([before], [after]), the rest where there is. */
        private fun widened(extra: Float, before: Float, after: Float): BlockArea {
            var startward = min(extra / 2, before)
            val endward = min(extra - startward, after)
            startward = min(extra - endward, before)
            return BlockArea(box.left - startward, box.top, box.right + endward, box.bottom)
        }
    }

    private fun box(block: SourceBlock) =
        BlockArea(min(block.left, block.right), min(block.top, block.bottom), max(block.left, block.right), max(block.top, block.bottom))

    /** Whether [other] is beside [own]: they overlap in height. */
    private fun acrossFrom(other: BlockArea, own: BlockArea): Boolean = min(other.bottom, own.bottom) - max(other.top, own.top) > 0f

    /** The page's margin along a side [extent] points long; null for a page without a size. */
    private fun margin(extent: Float): Float? = if (extent.isFinite() && extent > 0f) max(MIN_MARGIN, MARGIN_SHARE * extent) else null

    private fun BlockArea.isFinite() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()

    private fun Float.orElse(other: Float) = if (isFinite()) this else other
}
