package io.github.nath_goa.usugami.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What Page layout's Crop keeps of a page as it is shown (its crop box, turned by its /Rotate), as
 * shares of that page: [left] and [right] from its left edge, [top] and [bottom] from its top edge,
 * as on a screen. One box fits every page, whatever its size or turn.
 */
data class CropShare(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** The whole page, give or take a rounding error (0.001). */
    val isWhole: Boolean
        get() = left <= ROUNDING && top <= ROUNDING && right >= 1f - ROUNDING && bottom >= 1f - ROUNDING

    companion object {
        val WHOLE = CropShare(0f, 0f, 1f, 1f)
        private const val ROUNDING = 0.001f
    }
}

/** What of the box a finger or a button moves: a side, or a corner with its two sides. */
enum class CropHandle { LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

/**
 * The geometry of Crop's box: what a touch grabs, how a drag or a step button moves it, and the
 * /CropBox it gives a page. Shares run from the top-left corner of the page as shown, as on a
 * screen; only [pageBox] turns them into the page's own coordinates, where y runs up.
 */
object CropBoxes {
    /** The box is at least this share of the page, each way. */
    const val MIN_SHARE = 0.05f

    /** One press of a step button. */
    const val STEP = 0.01f

    /** Points; the smallest page side viewers accept ([PageLayout.MIN_PAGE_SIDE]). */
    const val MIN_SIDE = 3f

    /** Float rounding allowed when a saved box is checked against [MIN_SHARE]. */
    private const val SLACK = 0.0001f

    /** A step inwards that moves its side less than this moves nothing (Float rounding at its limit). */
    private const val NO_MOVE = 1e-6f

    /**
     * The handle a touch at ([x], [y]) (shares of the page) takes: the nearest corner within
     * [reachX], [reachY] of it, else the nearest side within reach of its line and between its ends,
     * else null. Nearness is measured in reaches, so a reach of the same size on screen each way
     * makes it the distance on screen.
     */
    fun grab(share: CropShare, x: Float, y: Float, reachX: Float, reachY: Float): CropHandle? {
        fun reaches(d: Float, reach: Float) = if (reach > 0f) d / reach else 0f
        val corners = listOf(
            Triple(CropHandle.TOP_LEFT, share.left, share.top),
            Triple(CropHandle.TOP_RIGHT, share.right, share.top),
            Triple(CropHandle.BOTTOM_RIGHT, share.right, share.bottom),
            Triple(CropHandle.BOTTOM_LEFT, share.left, share.bottom),
        )
        val corner = corners
            .filter { (_, cx, cy) -> abs(x - cx) <= reachX && abs(y - cy) <= reachY }
            .minByOrNull { (_, cx, cy) ->
                val dx = reaches(abs(x - cx), reachX)
                val dy = reaches(abs(y - cy), reachY)
                dx * dx + dy * dy
            }
        if (corner != null) return corner.first
        val betweenTopAndBottom = y >= share.top && y <= share.bottom
        val betweenLeftAndRight = x >= share.left && x <= share.right
        // Each side with how far the touch is from its line, in reaches.
        val sides = listOfNotNull(
            if (betweenTopAndBottom && abs(x - share.left) <= reachX) CropHandle.LEFT to reaches(abs(x - share.left), reachX) else null,
            if (betweenLeftAndRight && abs(y - share.top) <= reachY) CropHandle.TOP to reaches(abs(y - share.top), reachY) else null,
            if (betweenTopAndBottom && abs(x - share.right) <= reachX) CropHandle.RIGHT to reaches(abs(x - share.right), reachX) else null,
            if (betweenLeftAndRight && abs(y - share.bottom) <= reachY) CropHandle.BOTTOM to reaches(abs(y - share.bottom), reachY) else null,
        )
        return sides.minByOrNull { it.second }?.first
    }

    /**
     * [start] with [handle] moved by ([dx], [dy]) shares (dy down the page): the moved sides stay on
     * the page and at least [MIN_SHARE] from the side opposite. A side ignores the drag along itself.
     */
    fun dragged(start: CropShare, handle: CropHandle, dx: Float, dy: Float): CropShare {
        // Written so that it never throws, even for a box already smaller than the limits allow, and
        // a move that is not a number (a drag over a preview of no size) moves nothing.
        val byX = if (dx.isNaN()) 0f else dx
        val byY = if (dy.isNaN()) 0f else dy
        fun low(value: Float, opposite: Float) = max(0f, min(value, opposite - MIN_SHARE))
        fun high(value: Float, opposite: Float) = min(1f, max(value, opposite + MIN_SHARE))
        val movesLeft = handle == CropHandle.LEFT || handle == CropHandle.TOP_LEFT || handle == CropHandle.BOTTOM_LEFT
        val movesRight = handle == CropHandle.RIGHT || handle == CropHandle.TOP_RIGHT || handle == CropHandle.BOTTOM_RIGHT
        val movesTop = handle == CropHandle.TOP || handle == CropHandle.TOP_LEFT || handle == CropHandle.TOP_RIGHT
        val movesBottom = handle == CropHandle.BOTTOM || handle == CropHandle.BOTTOM_LEFT || handle == CropHandle.BOTTOM_RIGHT
        return CropShare(
            left = if (movesLeft) low(start.left + byX, start.right) else start.left,
            top = if (movesTop) low(start.top + byY, start.bottom) else start.top,
            right = if (movesRight) high(start.right + byX, start.left) else start.right,
            bottom = if (movesBottom) high(start.bottom + byY, start.top) else start.bottom,
        )
    }

    /**
     * A step button: side [side] (LEFT, TOP, RIGHT or BOTTOM) moved [STEP] inwards ([more]: crops
     * more off that side) or outwards. At its limit [share] itself comes back, so a button whose
     * step gives back an equal box can be turned off.
     */
    fun stepped(share: CropShare, side: CropHandle, more: Boolean): CropShare {
        val inwards = if (more) STEP else -STEP
        val next = when (side) {
            CropHandle.LEFT -> dragged(share, side, inwards, 0f)
            CropHandle.TOP -> dragged(share, side, 0f, inwards)
            CropHandle.RIGHT -> dragged(share, side, -inwards, 0f)
            CropHandle.BOTTOM -> dragged(share, side, 0f, -inwards)
            else -> throw IllegalArgumentException("A step button moves one side, not $side")
        }
        // Inwards the limit is the side opposite less MIN_SHARE, which Float can't always give back
        // exactly: on a box made smallest by dragging the side opposite, the step would move this side
        // by a hair, nothing to see, and its button would stay on. Outwards the limit is the page's
        // edge, 0 or 1 exactly, so a side a hair off the edge still gets there.
        val moved = abs(next.left - share.left) + abs(next.top - share.top) +
            abs(next.right - share.right) + abs(next.bottom - share.bottom)
        return if (more && moved < NO_MOVE) share else next
    }

    /**
     * The new crop box, as left, bottom, right, top in the page's own coordinates: [share] of the
     * page as [frame] shows it, cut to [media] (left, bottom, right, top). Null when it would be
     * under [MIN_SIDE] wide or tall: such a page is left as it is.
     */
    fun pageBox(frame: PageFrame, share: CropShare, media: FloatArray): FloatArray? {
        val w = frame.shownWidth
        val h = frame.shownHeight
        // Shown y runs up from the bottom; the share's top and bottom are measured from the top.
        val (x1, y1) = frame.toPage(share.left * w, (1f - share.bottom) * h)
        val (x2, y2) = frame.toPage(share.right * w, (1f - share.top) * h)
        val bounds = floatArrayOf(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
        val box = Areas.intersection(bounds, media) ?: return null
        if (box.any { !it.isFinite() }) return null
        return box.takeIf { it[2] - it[0] >= MIN_SIDE && it[3] - it[1] >= MIN_SIDE }
    }

    /** The box as 4 numbers, for the saved state. */
    fun pack(share: CropShare): FloatArray = floatArrayOf(share.left, share.top, share.right, share.bottom)

    /** The box [pack] saved; [CropShare.WHOLE] for anything else than 4 numbers that make a box on the page. */
    fun unpack(values: FloatArray): CropShare {
        if (values.size != 4 || values.any { !it.isFinite() }) return CropShare.WHOLE
        val (left, top, right, bottom) = values
        val onPage = left >= 0f && top >= 0f && right <= 1f && bottom <= 1f
        val bigEnough = right - left >= MIN_SHARE - SLACK && bottom - top >= MIN_SHARE - SLACK
        return if (onPage && bigEnough) CropShare(left, top, right, bottom) else CropShare.WHOLE
    }
}
