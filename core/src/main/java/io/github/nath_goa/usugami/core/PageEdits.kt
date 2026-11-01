package io.github.nath_goa.usugami.core

import java.io.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

// Edit PDF: text, pen and highlighter strokes, shapes and pictures put on top of a page. The
// preview on the screen and the saved PDF both draw them from what is here, so they match.

/** What a [ShapeItem] draws. */
enum class ShapeKind { RECTANGLE, ELLIPSE, LINE, ARROW }

/**
 * Something Edit PDF puts on top of page [page] (zero-based). Its coordinates are points on the page
 * as it is shown, from the shown page's top-left corner with y down, as on a screen: the preview
 * draws them as they are, and [PageFrame.topLeftMatrix] takes them to the page's own coordinates.
 *
 * Items are kept in the screen state Android saves, so they are small and [Serializable].
 */
sealed class PageItem : Serializable {
    abstract val id: Long
    abstract val page: Int

    /** What it covers on the page, ink included. */
    abstract val bounds: Area

    abstract fun moved(dx: Float, dy: Float): PageItem

    /** [factor] times as big, about the point ([cx], [cy]). Line widths stay as they are. */
    abstract fun scaled(factor: Float, cx: Float, cy: Float): PageItem
}

/**
 * Text in lines (split at line breaks), left-aligned, its box's top-left corner at ([x], [y]).
 * [unitWidth] and [unitHeight] are the box's size at a font size of one point, as Android lays the
 * text out; the box is [size] times that.
 */
data class TextItem(
    override val id: Long,
    override val page: Int,
    val x: Float,
    val y: Float,
    val text: String,
    val size: Float,
    val color: Int,
    val unitWidth: Float,
    val unitHeight: Float,
) : PageItem() {
    val width: Float get() = unitWidth * size
    val height: Float get() = unitHeight * size
    override val bounds: Area get() = Area(x, y, width, height)
    override fun moved(dx: Float, dy: Float) = copy(x = x + dx, y = y + dy)
    override fun scaled(factor: Float, cx: Float, cy: Float) =
        copy(x = cx + (x - cx) * factor, y = cy + (y - cy) * factor, size = size * factor)
}

/**
 * A finger's stroke: [points] are its points' coordinates in turn (x0, y0, x1, y1, …), drawn as the
 * smooth curve a signature is drawn with ([EditLayout.outline]), [width] points wide. A highlighter
 * stroke is see-through and darkens what is under it, like a marker.
 */
data class InkItem(
    override val id: Long,
    override val page: Int,
    val points: FloatArray,
    val width: Float,
    val color: Int,
    val highlighter: Boolean,
) : PageItem() {
    init {
        require(points.size >= 2 && points.size % 2 == 0)
    }

    override val bounds: Area get() = SignatureLayout.inkBounds(listOf(points), width)!!

    /** What the curve itself covers, without the pen's width. */
    val curveBounds: Area get() = SignatureLayout.inkBounds(listOf(points), 0f)!!

    override fun moved(dx: Float, dy: Float) =
        copy(points = FloatArray(points.size) { i -> points[i] + if (i % 2 == 0) dx else dy })

    override fun scaled(factor: Float, cx: Float, cy: Float) =
        copy(points = FloatArray(points.size) { i -> val c = if (i % 2 == 0) cx else cy; c + (points[i] - c) * factor })

    override fun equals(other: Any?): Boolean = other is InkItem && id == other.id && page == other.page &&
        points.contentEquals(other.points) && width == other.width && color == other.color && highlighter == other.highlighter

    override fun hashCode(): Int = ((id.hashCode() * 31 + page) * 31 + points.contentHashCode()) * 31 + color
}

/**
 * A rectangle or an ellipse in the box from ([x1], [y1]) to ([x2], [y2]) (x1 ≤ x2, y1 ≤ y2), outlined
 * [width] points wide or [filled]; or a line or an arrow from ([x1], [y1]) to ([x2], [y2]), the
 * arrow's head at the end.
 */
data class ShapeItem(
    override val id: Long,
    override val page: Int,
    val kind: ShapeKind,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val width: Float,
    val color: Int,
    val filled: Boolean = false,
) : PageItem() {
    val isBox: Boolean get() = kind == ShapeKind.RECTANGLE || kind == ShapeKind.ELLIPSE

    /** Filled shapes have no outline. */
    val strokeWidth: Float get() = if (isBox && filled) 0f else width

    override val bounds: Area
        get() {
            val pad = strokeWidth / 2
            var left = min(x1, x2)
            var top = min(y1, y2)
            var right = max(x1, x2)
            var bottom = max(y1, y2)
            if (kind == ShapeKind.ARROW) {
                val head = EditLayout.arrowHead(x1, y1, x2, y2, width)
                left = min(left, min(head[0], head[2]))
                right = max(right, max(head[0], head[2]))
                top = min(top, min(head[1], head[3]))
                bottom = max(bottom, max(head[1], head[3]))
            }
            return Area(left - pad, top - pad, right - left + 2 * pad, bottom - top + 2 * pad)
        }

    override fun moved(dx: Float, dy: Float) = copy(x1 = x1 + dx, y1 = y1 + dy, x2 = x2 + dx, y2 = y2 + dy)

    override fun scaled(factor: Float, cx: Float, cy: Float) = copy(
        x1 = cx + (x1 - cx) * factor, y1 = cy + (y1 - cy) * factor,
        x2 = cx + (x2 - cx) * factor, y2 = cy + (y2 - cy) * factor,
    )
}

/**
 * A picture from the file at [path] (a copy in the app's cache), in the box at ([x], [y]), [width] ×
 * [height] points, which has the picture's shape as it shows upright. [orientation] is its EXIF
 * orientation (1 to 8), and [jpeg] says whether to store it as JPEG (photos) or losslessly, with
 * any transparency (drawings and screenshots).
 */
data class PictureItem(
    override val id: Long,
    override val page: Int,
    val path: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val orientation: Int,
    val jpeg: Boolean,
) : PageItem() {
    override val bounds: Area get() = Area(x, y, width, height)
    override fun moved(dx: Float, dy: Float) = copy(x = x + dx, y = y + dy)
    override fun scaled(factor: Float, cx: Float, cy: Float) =
        copy(x = cx + (x - cx) * factor, y = cy + (y - cy) * factor, width = width * factor, height = height * factor)
}

/** A step of a path, in points on the page as shown (y down). */
sealed class PathOp {
    data class Move(val x: Float, val y: Float) : PathOp()
    data class Line(val x: Float, val y: Float) : PathOp()

    /** A cubic Bézier curve from the current point to ([x], [y]), bending towards the two control points. */
    data class Cubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : PathOp()
    data object Close : PathOp()
}

/** The handles a chosen item shows, to change its size or shape by dragging. */
enum class Handle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, START, END }

/**
 * The matrix, as for a PDF `cm` operator, that takes points on the page as shown, from its top-left
 * corner with y down, to the page's own coordinates.
 */
val PageFrame.topLeftMatrix: FloatArray
    get() {
        val m = matrix
        // A shown point (x, y) from the top is (x, shownHeight - y) from the bottom.
        return floatArrayOf(m[0], m[1], -m[2], -m[3], m[4] + m[2] * shownHeight, m[5] + m[3] * shownHeight)
    }

/** The page's own coordinates of the shown point ([x], [y]), measured from the top-left corner with y down. */
fun PageFrame.fromTopLeft(x: Float, y: Float): Pair<Float, Float> = toPage(x, shownHeight - y)

object EditLayout {
    /** The smallest a shape, picture or drawing gets, in points. */
    const val MIN_SIZE = 8f

    /** Font sizes text can have, in points. */
    const val MIN_TEXT = 6f
    const val MAX_TEXT = 96f

    /** Line widths of pens and shapes, and of highlighters, in points. */
    val PEN_WIDTHS = 0.5f..20f
    val HIGHLIGHTER_WIDTHS = 4f..40f

    /** How see-through a highlighter is: the share of its colour laid on the page. */
    const val HIGHLIGHTER_ALPHA = 0.55f

    /** Ellipses are drawn with four cubic curves; this places their control points. */
    private const val KAPPA = 0.5522848f

    /**
     * The path of an ink stroke or a shape. A stroke follows the curve a signature does: from its
     * first point it bends through each point in turn to the midpoint between it and the next, then
     * goes straight to the last point; a stroke of one point is a dot (a line of no length, which
     * the pen's round end draws as one).
     */
    fun outline(item: PageItem): List<PathOp> = when (item) {
        is InkItem -> strokePath(item.points)
        is ShapeItem -> when (item.kind) {
            ShapeKind.RECTANGLE -> listOf(
                PathOp.Move(item.x1, item.y1), PathOp.Line(item.x2, item.y1), PathOp.Line(item.x2, item.y2),
                PathOp.Line(item.x1, item.y2), PathOp.Close,
            )
            ShapeKind.ELLIPSE -> ellipse(item.x1, item.y1, item.x2, item.y2)
            ShapeKind.LINE -> listOf(PathOp.Move(item.x1, item.y1), PathOp.Line(item.x2, item.y2))
            ShapeKind.ARROW -> {
                val head = arrowHead(item.x1, item.y1, item.x2, item.y2, item.width)
                listOf(
                    PathOp.Move(item.x1, item.y1), PathOp.Line(item.x2, item.y2),
                    PathOp.Move(head[0], head[1]), PathOp.Line(item.x2, item.y2), PathOp.Line(head[2], head[3]),
                )
            }
        }
        is TextItem, is PictureItem -> emptyList()
    }

    private fun strokePath(points: FloatArray): List<PathOp> {
        val count = points.size / 2
        val ops = ArrayList<PathOp>(count + 1)
        var x = points[0]
        var y = points[1]
        ops += PathOp.Move(x, y)
        for (i in 1 until count - 1) {
            val cx = points[2 * i]
            val cy = points[2 * i + 1]
            val ex = (cx + points[2 * i + 2]) / 2
            val ey = (cy + points[2 * i + 3]) / 2
            ops += quadratic(x, y, cx, cy, ex, ey)
            x = ex
            y = ey
        }
        ops += PathOp.Line(points[2 * count - 2], points[2 * count - 1])
        return ops
    }

    /** The quadratic curve from ([x0], [y0]) to ([x], [y]) bending towards ([cx], [cy]), as the cubic curve it is. */
    fun quadratic(x0: Float, y0: Float, cx: Float, cy: Float, x: Float, y: Float) = PathOp.Cubic(
        x0 + 2f / 3 * (cx - x0), y0 + 2f / 3 * (cy - y0),
        x + 2f / 3 * (cx - x), y + 2f / 3 * (cy - y),
        x, y,
    )

    /** The ellipse in the box from ([left], [top]) to ([right], [bottom]), clockwise from its top. */
    fun ellipse(left: Float, top: Float, right: Float, bottom: Float): List<PathOp> {
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        val kx = (right - left) / 2 * KAPPA
        val ky = (bottom - top) / 2 * KAPPA
        return listOf(
            PathOp.Move(cx, top),
            PathOp.Cubic(cx + kx, top, right, cy - ky, right, cy),
            PathOp.Cubic(right, cy + ky, cx + kx, bottom, cx, bottom),
            PathOp.Cubic(cx - kx, bottom, left, cy + ky, left, cy),
            PathOp.Cubic(left, cy - ky, cx - kx, top, cx, top),
            PathOp.Close,
        )
    }

    /**
     * The ends of an arrow's two barbs (x, y, x, y), which meet at its tip ([x2], [y2]). They are
     * longer for a thicker line, but at most half as long as the arrow.
     */
    fun arrowHead(x1: Float, y1: Float, x2: Float, y2: Float, width: Float): FloatArray {
        val length = hypot(x2 - x1, y2 - y1)
        if (length == 0f) return floatArrayOf(x2, y2, x2, y2)
        val barb = min(max(10f, 4f * width), length / 2)
        val ux = (x1 - x2) / length
        val uy = (y1 - y2) / length
        val c = cos(ARROW_ANGLE)
        val s = sin(ARROW_ANGLE)
        return floatArrayOf(
            x2 + barb * (ux * c - uy * s), y2 + barb * (ux * s + uy * c),
            x2 + barb * (ux * c + uy * s), y2 + barb * (-ux * s + uy * c),
        )
    }

    /** Half the angle between an arrow's barbs, in radians (30°). */
    private const val ARROW_ANGLE = 0.5235988f

    /**
     * How big [item] is, for the size slider: a text's font size; otherwise the longer side of what
     * it covers (without line widths), or a line's length.
     */
    fun size(item: PageItem): Float = when (item) {
        is TextItem -> item.size
        is InkItem -> item.curveBounds.let { max(it.width, it.height) }
        is ShapeItem -> if (item.isBox) max(item.x2 - item.x1, item.y2 - item.y1) else hypot(item.x2 - item.x1, item.y2 - item.y1)
        is PictureItem -> max(item.width, item.height)
    }

    /**
     * The sizes [item] can have on a page [pageWidth] × [pageHeight] points big: from small to as big
     * as fits on the page. A text's are font sizes.
     */
    fun sizeRange(item: PageItem, pageWidth: Float, pageHeight: Float): ClosedFloatingPointRange<Float> {
        val size = size(item)
        val bounds = item.bounds
        val fits = size * min(pageWidth / bounds.width, pageHeight / bounds.height)
        return if (item is TextItem) MIN_TEXT..max(MIN_TEXT, min(MAX_TEXT, fits))
        else min(MIN_SIZE, size)..max(min(MIN_SIZE, size), fits)
    }

    /** [item] made [size] big (see [size]) about its centre, then moved as little as needed to be on the page. */
    fun resized(item: PageItem, size: Float, pageWidth: Float, pageHeight: Float): PageItem {
        val current = size(item)
        if (current <= 0f) return item
        val bounds = item.bounds
        val scaled = item.scaled(size / current, bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
        return onPage(scaled, pageWidth, pageHeight)
    }

    /** [item] moved as little as needed to be wholly on the page; one bigger than the page is centred on it. */
    fun onPage(item: PageItem, pageWidth: Float, pageHeight: Float): PageItem {
        val (dx, dy) = clampMove(item.bounds, 0f, 0f, pageWidth, pageHeight)
        return if (dx == 0f && dy == 0f) item else item.moved(dx, dy)
    }

    /**
     * How far something covering [bounds] can move by ([dx], [dy]) and stay on the page: the move,
     * shortened where it would cross an edge.
     */
    fun clampMove(bounds: Area, dx: Float, dy: Float, pageWidth: Float, pageHeight: Float): Pair<Float, Float> {
        fun axis(start: Float, length: Float, delta: Float, page: Float): Float {
            // Something bigger than the page is centred on it.
            if (length >= page) return (page - length) / 2 - start
            return (start + delta).coerceIn(0f, page - length) - start
        }
        return axis(bounds.x, bounds.width, dx, pageWidth) to axis(bounds.y, bounds.height, dy, pageHeight)
    }

    /** [item] moved by ([dx], [dy]), but no further than keeps it on the page. */
    fun moved(item: PageItem, dx: Float, dy: Float, pageWidth: Float, pageHeight: Float): PageItem {
        val (x, y) = clampMove(item.bounds, dx, dy, pageWidth, pageHeight)
        return item.moved(x, y)
    }

    /** Where [item]'s handles are. */
    fun handles(item: PageItem): List<Pair<Handle, Pair<Float, Float>>> = when (item) {
        is TextItem, is InkItem -> item.bounds.let { listOf(Handle.BOTTOM_RIGHT to (it.x + it.width to it.y + it.height)) }
        is PictureItem -> corners(item.x, item.y, item.x + item.width, item.y + item.height)
        is ShapeItem -> if (item.isBox) corners(item.x1, item.y1, item.x2, item.y2)
        else listOf(Handle.START to (item.x1 to item.y1), Handle.END to (item.x2 to item.y2))
    }

    private fun corners(left: Float, top: Float, right: Float, bottom: Float) = listOf(
        Handle.TOP_LEFT to (left to top), Handle.TOP_RIGHT to (right to top),
        Handle.BOTTOM_LEFT to (left to bottom), Handle.BOTTOM_RIGHT to (right to bottom),
    )

    /**
     * [start] (the item as it was when the drag began) with [handle] dragged to ([x], [y]), kept on
     * the page. A box's corner moves while the opposite corner stays; a picture keeps its shape; a
     * line's end moves; text and drawings grow or shrink from their top-left corner.
     */
    fun dragHandle(start: PageItem, handle: Handle, x: Float, y: Float, pageWidth: Float, pageHeight: Float): PageItem {
        val px = x.coerceIn(0f, pageWidth)
        val py = y.coerceIn(0f, pageHeight)
        return when (start) {
            is ShapeItem -> if (start.isBox) {
                val (fx, fy) = opposite(handle, start.x1, start.y1, start.x2, start.y2)
                val (ex, ey) = away(fx, fy, px, py, handle)
                start.copy(x1 = min(fx, ex), y1 = min(fy, ey), x2 = max(fx, ex), y2 = max(fy, ey))
            } else {
                if (handle == Handle.START) start.copy(x1 = px, y1 = py) else start.copy(x2 = px, y2 = py)
            }
            is PictureItem -> {
                val (fx, fy) = opposite(handle, start.x, start.y, start.x + start.width, start.y + start.height)
                val aspect = start.width / start.height
                val sx = directionX(handle)
                val sy = directionY(handle)
                // Room on the page beyond the fixed corner, in the direction the handle points.
                val roomX = if (sx > 0) pageWidth - fx else fx
                val roomY = if (sy > 0) pageHeight - fy else fy
                val wanted = max(sx * (px - fx), sy * (py - fy) * aspect)
                val smallest = max(MIN_SIZE, MIN_SIZE * aspect)
                val width = wanted.coerceIn(min(smallest, start.width), max(min(roomX, roomY * aspect), min(smallest, start.width)))
                val height = width / aspect
                val left = if (sx > 0) fx else fx - width
                val top = if (sy > 0) fy else fy - height
                start.copy(x = left, y = top, width = width, height = height)
            }
            is TextItem, is InkItem -> {
                // Grows from the top-left corner of what it covers.
                val bounds = start.bounds
                val factor = max((px - bounds.x) / bounds.width, (py - bounds.y) / bounds.height)
                val most = min((pageWidth - bounds.x) / bounds.width, (pageHeight - bounds.y) / bounds.height)
                val least = when (start) {
                    is TextItem -> MIN_TEXT / start.size
                    else -> min(1f, MIN_SIZE / max(1f, size(start)))
                }
                val upper = if (start is TextItem) min(most, MAX_TEXT / start.size) else most
                start.scaled(factor.coerceIn(least, max(least, upper)), bounds.x, bounds.y)
            }
        }
    }

    private fun directionX(handle: Handle) = if (handle == Handle.TOP_RIGHT || handle == Handle.BOTTOM_RIGHT) 1f else -1f
    private fun directionY(handle: Handle) = if (handle == Handle.BOTTOM_LEFT || handle == Handle.BOTTOM_RIGHT) 1f else -1f

    /** The corner of the box opposite [handle]: the one that stays put while it is dragged. */
    private fun opposite(handle: Handle, left: Float, top: Float, right: Float, bottom: Float): Pair<Float, Float> =
        (if (directionX(handle) > 0) left else right) to (if (directionY(handle) > 0) top else bottom)

    /**
     * The dragged corner at ([x], [y]), at least [MIN_SIZE] from the fixed corner ([fx], [fy]) on
     * each side. It may cross to the other side of the fixed corner, as when a box is turned inside out.
     */
    private fun away(fx: Float, fy: Float, x: Float, y: Float, handle: Handle): Pair<Float, Float> {
        fun axis(fixed: Float, at: Float, preferred: Float): Float {
            val d = at - fixed
            if (abs(d) >= MIN_SIZE) return at
            val s = if (d != 0f) sign(d) else preferred
            return fixed + s * MIN_SIZE
        }
        return axis(fx, x, directionX(handle)) to axis(fy, y, directionY(handle))
    }

    /** Whether a touch at ([x], [y]) is on [item], or within [reach] points of what it draws. */
    fun hits(item: PageItem, x: Float, y: Float, reach: Float): Boolean = when (item) {
        is TextItem, is PictureItem -> item.bounds.let { b ->
            x >= b.x - reach && x <= b.x + b.width + reach && y >= b.y - reach && y <= b.y + b.height + reach
        }
        is InkItem -> {
            val points = item.points
            val near = item.width / 2 + reach
            if (points.size == 2) {
                hypot(x - points[0], y - points[1]) <= near
            } else {
                (0 until points.size / 2 - 1).any { i ->
                    distanceToSegment(x, y, points[2 * i], points[2 * i + 1], points[2 * i + 2], points[2 * i + 3]) <= near
                }
            }
        }
        is ShapeItem -> {
            val near = item.strokeWidth / 2 + reach
            when (item.kind) {
                ShapeKind.RECTANGLE -> {
                    val inside = x >= item.x1 - near && x <= item.x2 + near && y >= item.y1 - near && y <= item.y2 + near
                    // An outlined box is picked up by its outline, so what it frames can still be tapped.
                    val hollow = item.x2 - item.x1 > 2 * near && item.y2 - item.y1 > 2 * near &&
                        x > item.x1 + near && x < item.x2 - near && y > item.y1 + near && y < item.y2 - near
                    inside && (item.filled || !hollow)
                }
                ShapeKind.ELLIPSE -> {
                    val rx = (item.x2 - item.x1) / 2
                    val ry = (item.y2 - item.y1) / 2
                    val dx = x - (item.x1 + rx)
                    val dy = y - (item.y1 + ry)
                    fun inEllipse(a: Float, b: Float) = a > 0 && b > 0 && (dx / a) * (dx / a) + (dy / b) * (dy / b) <= 1f
                    inEllipse(rx + near, ry + near) && (item.filled || !inEllipse(rx - near, ry - near))
                }
                ShapeKind.LINE -> distanceToSegment(x, y, item.x1, item.y1, item.x2, item.y2) <= near
                ShapeKind.ARROW -> {
                    val head = arrowHead(item.x1, item.y1, item.x2, item.y2, item.width)
                    distanceToSegment(x, y, item.x1, item.y1, item.x2, item.y2) <= near ||
                        distanceToSegment(x, y, head[0], head[1], item.x2, item.y2) <= near ||
                        distanceToSegment(x, y, head[2], head[3], item.x2, item.y2) <= near
                }
            }
        }
    }

    /**
     * The item a touch at ([x], [y]) picks among [items] (those on one page, drawn in order): the
     * chosen one ([chosen]) if it is touched, so it can be dragged out from under others; otherwise
     * the topmost one touched.
     */
    fun pick(items: List<PageItem>, x: Float, y: Float, reach: Float, chosen: Long?): PageItem? {
        items.firstOrNull { it.id == chosen }?.let { if (hits(it, x, y, reach)) return it }
        return items.lastOrNull { hits(it, x, y, reach) }
    }

    /** The shortest distance from ([x], [y]) to the line segment from ([x1], [y1]) to ([x2], [y2]). */
    fun distanceToSegment(x: Float, y: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0f) 0f else (((x - x1) * dx + (y - y1) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(x - (x1 + t * dx), y - (y1 + t * dy))
    }

    /** A new shape of [kind] in the middle of the page: a box a third of its shorter side wide, or a line as long. */
    fun newShape(id: Long, page: Int, kind: ShapeKind, filled: Boolean, width: Float, color: Int, pageWidth: Float, pageHeight: Float): ShapeItem {
        val side = min(pageWidth, pageHeight) / 3
        val cx = pageWidth / 2
        val cy = pageHeight / 2
        return if (kind == ShapeKind.RECTANGLE || kind == ShapeKind.ELLIPSE) {
            val height = if (kind == ShapeKind.RECTANGLE) side * 0.6f else side
            ShapeItem(id, page, kind, cx - side / 2, cy - height / 2, cx + side / 2, cy + height / 2, width, color, filled)
        } else {
            ShapeItem(id, page, kind, cx - side / 2, cy, cx + side / 2, cy, width, color)
        }
    }

    /**
     * New text in the middle of the page, at font size [size], or smaller if that is too wide for
     * the page. [unitWidth] and [unitHeight] are as in [TextItem].
     */
    fun newText(id: Long, page: Int, text: String, size: Float, color: Int, unitWidth: Float, unitHeight: Float, pageWidth: Float, pageHeight: Float): TextItem {
        val fits = min(0.9f * pageWidth / unitWidth, 0.9f * pageHeight / unitHeight)
        val fontSize = min(size, fits).coerceAtLeast(MIN_TEXT)
        return TextItem(id, page, (pageWidth - unitWidth * fontSize) / 2, (pageHeight - unitHeight * fontSize) / 2, text, fontSize, color, unitWidth, unitHeight)
            .let { onPage(it, pageWidth, pageHeight) as TextItem }
    }

    /**
     * [item], new, moved down and to the right in small steps while it would be centred on one of
     * [others] on its page, so a second shape or text added in the middle of the page doesn't hide
     * the first.
     */
    fun clearOf(item: PageItem, others: List<PageItem>, pageWidth: Float, pageHeight: Float): PageItem {
        val step = 0.04f * min(pageWidth, pageHeight)
        fun Area.centre() = (x + width / 2) to (y + height / 2)
        val taken = others.filter { it.page == item.page }.map { it.bounds.centre() }
        var placed = item
        repeat(10) {
            val (cx, cy) = placed.bounds.centre()
            if (taken.none { (x, y) -> abs(x - cx) < 1f && abs(y - cy) < 1f }) return placed
            placed = moved(placed, step, step, pageWidth, pageHeight)
        }
        return placed
    }

    /** A new picture [aspect] (width over height) in shape, in the middle of the page and half as wide or tall. */
    fun newPicture(id: Long, page: Int, path: String, aspect: Float, orientation: Int, jpeg: Boolean, pageWidth: Float, pageHeight: Float): PictureItem {
        val width = min(pageWidth / 2, pageHeight / 2 * aspect)
        val height = width / aspect
        return PictureItem(id, page, path, (pageWidth - width) / 2, (pageHeight - height) / 2, width, height, orientation, jpeg)
    }
}

/**
 * The colours, line widths and font size the next item of each kind gets. Changing an item's
 * changes these too, so the next one matches.
 */
data class EditStyle(
    val textColor: Int = 0xFF000000.toInt(),
    val textSize: Float = 14f,
    val penColor: Int = 0xFF1565C0.toInt(),
    val penWidth: Float = 2.5f,
    val highlighterColor: Int = 0xFFFFE600.toInt(),
    val highlighterWidth: Float = 14f,
    val shapeColor: Int = 0xFFD32F2F.toInt(),
    val shapeWidth: Float = 2.5f,
) : Serializable
