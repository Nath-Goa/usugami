package io.github.nath_goa.usugami.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A point in pixels, x to the right and y down. */
data class OcrPoint(val x: Float, val y: Float) {
    fun distanceTo(other: OcrPoint): Float = hypot(x - other.x, y - other.y)
}

/**
 * A text box: its top-left, top-right, bottom-right and bottom-left corners as the text reads,
 * which also give its angle.
 */
data class OcrQuad(val corners: List<OcrPoint>) {
    init {
        require(corners.size == 4) { "A box has 4 corners, not ${corners.size}" }
    }

    /** Length along the text (the longer of the top and bottom edges). */
    val width: Float get() = max(corners[0].distanceTo(corners[1]), corners[3].distanceTo(corners[2]))

    /** Height across the text (the longer of the left and right edges). */
    val height: Float get() = max(corners[0].distanceTo(corners[3]), corners[1].distanceTo(corners[2]))

    fun map(transform: (OcrPoint) -> OcrPoint) = OcrQuad(corners.map(transform))

    /** The same box with its corners turned by [quarterTurns] × 90° clockwise, as the text reads then. */
    fun turned(quarterTurns: Int): OcrQuad {
        val shift = Math.floorMod(quarterTurns, 4)
        return OcrQuad(List(4) { corners[(it + shift) % 4] })
    }
}

/**
 * PaddleOCR's DB post-processing (DBPostProcess with its PP-OCRv5 settings): turns the text
 * detection model's probability map into one box per line of text. Same steps as PaddleOCR, without
 * OpenCV: pixels above [THRESHOLD] are grouped into 8-connected regions; each region's minimum-area
 * rectangle is kept if the map's mean inside it reaches [BOX_THRESHOLD], then grown on every side by
 * area × [UNCLIP_RATIO] / perimeter (the DB model detects a shrunken text core, and this is exactly
 * what PaddleOCR's polygon offset gives for a rectangle).
 */
object TextDetection {
    const val THRESHOLD = 0.3f
    const val BOX_THRESHOLD = 0.6f
    const val UNCLIP_RATIO = 1.5f
    const val MAX_CANDIDATES = 1000
    private const val MIN_SIZE = 3f

    /** [THRESHOLD] on the map's 0..255 scale: a pixel is text when its level is at least this. */
    private const val THRESHOLD_LEVEL = 77

    /**
     * The text boxes in [probabilities], the model's output for a [width] × [height] picture as
     * levels 0..255 (probability × 255, unsigned), row by row. Boxes are in the map's pixels and can
     * reach slightly past its edges; corners are ordered as PaddleOCR orders them (left to right,
     * then top to bottom), which is how upright text reads. Each comes with its score.
     */
    fun boxes(probabilities: ByteArray, width: Int, height: Int): List<Pair<OcrQuad, Float>> {
        require(width > 0 && height > 0 && probabilities.size == width * height) { "Bad map size" }
        val visited = BooleanArray(width * height)
        val stack = IntArrayList()
        val region = IntArrayList()
        val result = ArrayList<Pair<OcrQuad, Float>>()
        var candidates = 0
        for (start in probabilities.indices) {
            if (visited[start] || level(probabilities, start) < THRESHOLD_LEVEL) continue
            // Flood fill one 8-connected region.
            region.clear()
            stack.clear()
            stack.add(start)
            visited[start] = true
            while (stack.size > 0) {
                val index = stack.removeLast()
                region.add(index)
                val x = index % width
                val y = index / width
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue
                        val next = ny * width + nx
                        if (!visited[next] && level(probabilities, next) >= THRESHOLD_LEVEL) {
                            visited[next] = true
                            stack.add(next)
                        }
                    }
                }
            }
            if (++candidates > MAX_CANDIDATES) break
            val rect = MinAreaRect.of(rowExtremes(region, width)) ?: continue
            if (min(rect.width, rect.height) < MIN_SIZE) continue
            val score = meanInside(probabilities, width, height, rect.corners)
            if (score < BOX_THRESHOLD) continue
            val distance = rect.width * rect.height * UNCLIP_RATIO / (2 * (rect.width + rect.height))
            val grown = rect.grown(distance)
            if (min(grown.width, grown.height) < MIN_SIZE + 2) continue
            result += OcrQuad(orderCorners(grown.corners)) to score
        }
        return result
    }

    private fun level(map: ByteArray, index: Int) = map[index].toInt() and 0xFF

    /** The leftmost and rightmost pixel of each row of a region: enough for its convex hull. */
    private fun rowExtremes(region: IntArrayList, width: Int): List<OcrPoint> {
        var top = Int.MAX_VALUE
        var bottom = Int.MIN_VALUE
        for (i in 0 until region.size) {
            val y = region[i] / width
            top = min(top, y)
            bottom = max(bottom, y)
        }
        val left = IntArray(bottom - top + 1) { Int.MAX_VALUE }
        val right = IntArray(bottom - top + 1) { Int.MIN_VALUE }
        for (i in 0 until region.size) {
            val y = region[i] / width - top
            val x = region[i] % width
            left[y] = min(left[y], x)
            right[y] = max(right[y], x)
        }
        val points = ArrayList<OcrPoint>(left.size * 2)
        for (row in left.indices) {
            if (left[row] > right[row]) continue
            points += OcrPoint(left[row].toFloat(), (row + top).toFloat())
            if (right[row] != left[row]) points += OcrPoint(right[row].toFloat(), (row + top).toFloat())
        }
        return points
    }

    /**
     * The mean probability inside [polygon], as PaddleOCR's "fast" box score computes it: over the
     * box's bounds (clipped to the map), with the corners cut to whole pixels and the polygon filled
     * the way OpenCV's fillPoly fills it (the rows between its edges, plus its outline).
     */
    internal fun meanInside(map: ByteArray, width: Int, height: Int, polygon: List<OcrPoint>): Float {
        val left = floor(polygon.minOf { it.x }).toInt().coerceIn(0, width - 1)
        val right = ceil(polygon.maxOf { it.x }).toInt().coerceIn(0, width - 1)
        val top = floor(polygon.minOf { it.y }).toInt().coerceIn(0, height - 1)
        val bottom = ceil(polygon.maxOf { it.y }).toInt().coerceIn(0, height - 1)
        val maskWidth = right - left + 1
        val mask = BooleanArray(maskWidth * (bottom - top + 1))
        // Relative to the bounds and cut towards zero, as numpy's astype(int32) does.
        val xs = IntArray(polygon.size) { (polygon[it].x - left).toInt() }
        val ys = IntArray(polygon.size) { (polygon[it].y - top).toInt() }
        fun set(x: Int, y: Int) {
            if (x in 0 until maskWidth && y in 0..bottom - top) mask[y * maskWidth + x] = true
        }
        for (y in 0..bottom - top) {
            var from = Double.POSITIVE_INFINITY
            var to = Double.NEGATIVE_INFINITY
            for (i in polygon.indices) {
                val j = (i + 1) % polygon.size
                if (y < min(ys[i], ys[j]) || y > max(ys[i], ys[j])) continue
                if (ys[i] == ys[j]) {
                    from = min(from, min(xs[i], xs[j]).toDouble())
                    to = max(to, max(xs[i], xs[j]).toDouble())
                } else {
                    val x = xs[i] + (y - ys[i]).toDouble() * (xs[j] - xs[i]) / (ys[j] - ys[i])
                    from = min(from, x)
                    to = max(to, x)
                }
            }
            if (from <= to) for (x in ceil(from).toInt()..floor(to).toInt()) set(x, y)
        }
        // The outline, as 8-connected lines (Bresenham).
        for (i in polygon.indices) {
            var x = xs[i]
            var y = ys[i]
            val endX = xs[(i + 1) % polygon.size]
            val endY = ys[(i + 1) % polygon.size]
            val dx = abs(endX - x)
            val dy = -abs(endY - y)
            val stepX = if (x < endX) 1 else -1
            val stepY = if (y < endY) 1 else -1
            var error = dx + dy
            while (true) {
                set(x, y)
                if (x == endX && y == endY) break
                val twice = 2 * error
                if (twice >= dy) { error += dy; x += stepX }
                if (twice <= dx) { error += dx; y += stepY }
            }
        }
        var sum = 0L
        var count = 0
        for (i in mask.indices) {
            if (!mask[i]) continue
            sum += level(map, (top + i / maskWidth) * width + left + i % maskWidth)
            count++
        }
        return if (count == 0) 0f else sum.toFloat() / count / 255f
    }

    /**
     * PaddleOCR's corner order (get_mini_boxes): of the two leftmost corners the upper one is the
     * top-left and the lower one the bottom-left; likewise on the right.
     */
    internal fun orderCorners(corners: List<OcrPoint>): List<OcrPoint> {
        val byX = corners.sortedWith(compareBy({ it.x }, { it.y }))
        val (topLeft, bottomLeft) = if (byX[1].y > byX[0].y) byX[0] to byX[1] else byX[1] to byX[0]
        val (topRight, bottomRight) = if (byX[3].y > byX[2].y) byX[2] to byX[3] else byX[3] to byX[2]
        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }
}

/** A rectangle at any angle: its [center], side lengths and the unit vector [axis] along [width]. */
class MinAreaRect(val center: OcrPoint, val width: Float, val height: Float, val axis: OcrPoint) {
    /** The four corners, going round the rectangle. */
    val corners: List<OcrPoint>
        get() {
            val ux = axis.x * width / 2
            val uy = axis.y * width / 2
            val vx = -axis.y * height / 2
            val vy = axis.x * height / 2
            return listOf(
                OcrPoint(center.x - ux - vx, center.y - uy - vy),
                OcrPoint(center.x + ux - vx, center.y + uy - vy),
                OcrPoint(center.x + ux + vx, center.y + uy + vy),
                OcrPoint(center.x - ux + vx, center.y - uy + vy),
            )
        }

    /** The rectangle grown by [distance] on every side. */
    fun grown(distance: Float) = MinAreaRect(center, width + 2 * distance, height + 2 * distance, axis)

    companion object {
        /**
         * The smallest rectangle around [points] (like OpenCV's minAreaRect): one of its sides lies
         * on an edge of their convex hull, so each edge is tried. Null without any point.
         */
        fun of(points: List<OcrPoint>): MinAreaRect? {
            val hull = convexHull(points)
            if (hull.isEmpty()) return null
            if (hull.size == 1) return MinAreaRect(hull[0], 0f, 0f, OcrPoint(1f, 0f))
            var best: MinAreaRect? = null
            var bestArea = Double.MAX_VALUE
            for (i in hull.indices) {
                val a = hull[i]
                val b = hull[(i + 1) % hull.size]
                val length = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
                if (length == 0.0) continue
                val ux = (b.x - a.x) / length
                val uy = (b.y - a.y) / length
                var minU = Double.MAX_VALUE
                var maxU = -Double.MAX_VALUE
                var minV = Double.MAX_VALUE
                var maxV = -Double.MAX_VALUE
                for (p in hull) {
                    val u = (p.x - a.x) * ux + (p.y - a.y) * uy
                    val v = -(p.x - a.x) * uy + (p.y - a.y) * ux
                    minU = min(minU, u)
                    maxU = max(maxU, u)
                    minV = min(minV, v)
                    maxV = max(maxV, v)
                }
                val area = (maxU - minU) * (maxV - minV)
                if (area < bestArea - 1e-9) {
                    bestArea = area
                    val cu = (minU + maxU) / 2
                    val cv = (minV + maxV) / 2
                    val center = OcrPoint((a.x + cu * ux - cv * uy).toFloat(), (a.y + cu * uy + cv * ux).toFloat())
                    best = MinAreaRect(center, (maxU - minU).toFloat(), (maxV - minV).toFloat(), OcrPoint(ux.toFloat(), uy.toFloat()))
                }
            }
            return best
        }

        /** Andrew's monotone chain; collinear points are dropped. */
        internal fun convexHull(points: List<OcrPoint>): List<OcrPoint> {
            val sorted = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
            if (sorted.size < 3) return sorted
            fun cross(o: OcrPoint, a: OcrPoint, b: OcrPoint) =
                (a.x - o.x).toDouble() * (b.y - o.y) - (a.y - o.y).toDouble() * (b.x - o.x)
            val hull = ArrayList<OcrPoint>(sorted.size + 1)
            for (p in sorted) {
                while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0) hull.removeAt(hull.size - 1)
                hull += p
            }
            val lowerSize = hull.size + 1
            for (i in sorted.size - 2 downTo 0) {
                val p = sorted[i]
                while (hull.size >= lowerSize && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0) hull.removeAt(hull.size - 1)
                hull += p
            }
            hull.removeAt(hull.size - 1)
            return hull
        }
    }
}

/**
 * Detection in strips: the model's memory grows with the picture (about half a gigabyte for a whole
 * page at full size, measured on x86_64), so a large page is detected in overlapping strips across
 * its whole width, and their maps are joined. Strips rather than tiles, because the model's
 * attention averages over everything it sees: two tiles side by side can draw the same line a
 * little differently on each side of the seam, which makes its box too tall, while lines rarely
 * cross the seam between two strips. Each strip's map is used up to the middle of its overlaps with
 * the next strips, where the model saw at least half the overlap around every pixel.
 */
object DetectionStrips {
    /** A strip at ([x], [y]) of [width] × [height] (multiples of 32); its map is used for [keep]. */
    data class Strip(val x: Int, val y: Int, val width: Int, val height: Int, val keep: IntArray) {
        override fun equals(other: Any?) = other is Strip && x == other.x && y == other.y && width == other.width &&
            height == other.height && keep.contentEquals(other.keep)

        override fun hashCode() = listOf(x, y, width, height).hashCode() * 31 + keep.contentHashCode()
    }

    /**
     * Strips covering a [width] × [height] map (multiples of 32), each of at most [maxPixels]
     * (unless the map is too wide for that) and overlapping by at least [overlap]. One strip when the
     * map fits.
     */
    fun of(width: Int, height: Int, maxPixels: Int, overlap: Int): List<Strip> {
        require(width % 32 == 0 && height % 32 == 0 && width > 0 && height > 0) { "Sides must be multiples of 32" }
        require(overlap % 32 == 0 && overlap >= 0) { "Bad overlap" }
        if (width.toLong() * height <= maxPixels) return listOf(Strip(0, 0, width, height, intArrayOf(0, 0, width, height)))
        val maxHeight = max(overlap + 32, maxPixels / width / 32 * 32)
        return spans(height, maxHeight, overlap).map { (y, h, top, bottom) -> Strip(0, y, width, h, intArrayOf(0, top, width, bottom)) }
    }

    /** Start, length, and the part kept (from, to) of each strip along its height. */
    private fun spans(length: Int, maxSide: Int, overlap: Int): List<IntArray> {
        if (length <= maxSide) return listOf(intArrayOf(0, length, 0, length))
        val count = ceilDiv(length - overlap, maxSide - overlap)
        val size = min(maxSide, ceilDiv(ceilDiv(length + (count - 1) * overlap, count), 32) * 32)
        val starts = List(count) { i -> (i.toLong() * (length - size) / (count - 1)).toInt() }
        return starts.mapIndexed { i, start ->
            val from = if (i == 0) 0 else (starts[i - 1] + size + start) / 2
            val to = if (i == count - 1) length else (start + size + starts[i + 1]) / 2
            intArrayOf(start, size, from, to)
        }
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

    /** Copies the kept part of [strip]'s map [values] into [map], which is [mapWidth] wide. */
    fun copy(values: ByteArray, strip: Strip, map: ByteArray, mapWidth: Int) {
        require(values.size == strip.width * strip.height) { "Wrong strip map size" }
        val (left, top, right, bottom) = strip.keep
        for (y in top until bottom) {
            System.arraycopy(values, (y - strip.y) * strip.width + (left - strip.x), map, y * mapWidth + left, right - left)
        }
    }
}

/** What the detected boxes are: which are pieces of one line, and which lines run down the page. */
object TextLines {
    private const val MAX_ANGLE_DEGREES = 10.0
    private const val MIN_OVERLAP = 0.6f
    private const val MIN_HEIGHT_RATIO = 0.6f
    private const val MAX_GAP = 1.0f

    /** PaddleOCR reads a box at least this much taller than wide as a line running down the page. */
    private const val TALL = 1.5f

    /** Only a box this much taller than wide is surely such a line; shorter ones go with the page. */
    private const val SURELY_TALL = 3f

    /** A line made of the boxes [parts] (indices into the input). */
    class Line(val quad: OcrQuad, val parts: List<Int>)

    /**
     * Joins boxes that are pieces of one line. On a page detected at full size the detection model
     * often splits a line at wider spaces between words; read separately, the pieces would come out
     * without the space between them, and a short word on its own (Hindi's है) can even look like a
     * line running down the page. Two boxes are pieces of one line when they run the same way
     * (within [MAX_ANGLE_DEGREES]), are about as high, mostly overlap across the text, and are at
     * most one line height apart along it. Boxes are taken with their corners as PaddleOCR orders
     * them (by position on the page), and the joined boxes come out the same way.
     */
    fun merge(boxes: List<OcrQuad>): List<Line> {
        val parent = IntArray(boxes.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            return r
        }
        for (a in boxes.indices) for (b in a + 1 until boxes.size) {
            if (sameLine(boxes[a], boxes[b])) parent[root(b)] = root(a)
        }
        return boxes.indices.groupBy(::root).values.map { group ->
            if (group.size == 1) return@map Line(boxes[group[0]], group)
            val frame = axis(boxes[group.maxBy { maxOf(boxes[it].width, boxes[it].height) }])
            val corners = group.flatMap { boxes[it].corners }
            val us = corners.map { frame.u(it) }
            val vs = corners.map { frame.v(it) }
            val joined = listOf(
                frame.point(us.min(), vs.min()), frame.point(us.max(), vs.min()),
                frame.point(us.max(), vs.max()), frame.point(us.min(), vs.max()),
            )
            Line(OcrQuad(TextDetection.orderCorners(joined)), group)
        }
    }

    /**
     * Which of [boxes] are lines running down the page, to be read turned a quarter round. As in
     * PaddleOCR, that is a box at least 1.5 times as tall as wide; but a box less than 3 times as tall
     * (one or two letters) is only taken as one when most of the page's other text runs down it too.
     */
    fun vertical(boxes: List<OcrQuad>): BooleanArray {
        var across = 0.0
        var down = 0.0
        for (box in boxes) {
            when {
                box.height >= SURELY_TALL * box.width -> down += box.height
                box.height < TALL * box.width -> across += box.width
            }
        }
        val pageRunsDown = down > across
        return BooleanArray(boxes.size) { i ->
            val box = boxes[i]
            box.height >= SURELY_TALL * box.width || (pageRunsDown && box.height >= TALL * box.width)
        }
    }

    /** A box's frame: u along its text, v across it (a tall box's text runs down its long side). */
    private fun axis(quad: OcrQuad): Frame {
        val (topLeft, topRight, _, bottomLeft) = quad.corners
        return if (quad.height >= TALL * quad.width) Frame(topLeft, bottomLeft) else Frame(topLeft, topRight)
    }

    private class Frame(val origin: OcrPoint, towards: OcrPoint) {
        private val length = origin.distanceTo(towards).coerceAtLeast(1e-3f)
        val ux = (towards.x - origin.x) / length
        val uy = (towards.y - origin.y) / length

        fun u(p: OcrPoint) = (p.x - origin.x) * ux + (p.y - origin.y) * uy
        fun v(p: OcrPoint) = -(p.x - origin.x) * uy + (p.y - origin.y) * ux
        fun point(u: Float, v: Float) = OcrPoint(origin.x + u * ux - v * uy, origin.y + u * uy + v * ux)
    }

    private fun sameLine(a: OcrQuad, b: OcrQuad): Boolean {
        // Two tall boxes are compared as pieces of a line running down the page; otherwise along the
        // page, so that a short word standing taller than wide still joins its line.
        val bothTall = a.height >= TALL * a.width && b.height >= TALL * b.width
        val frame = if (bothTall) axis(a) else Frame(a.corners[0], a.corners[1])
        val other = if (bothTall) axis(b) else Frame(b.corners[0], b.corners[1])
        if (abs(frame.ux * other.ux + frame.uy * other.uy) < kotlin.math.cos(Math.toRadians(MAX_ANGLE_DEGREES))) return false
        val au = a.corners.map { frame.u(it) }
        val av = a.corners.map { frame.v(it) }
        val bu = b.corners.map { frame.u(it) }
        val bv = b.corners.map { frame.v(it) }
        val aHeight = av.max() - av.min()
        val bHeight = bv.max() - bv.min()
        if (min(aHeight, bHeight) < MIN_HEIGHT_RATIO * max(aHeight, bHeight)) return false
        val overlap = min(av.max(), bv.max()) - max(av.min(), bv.min())
        if (overlap < MIN_OVERLAP * min(aHeight, bHeight)) return false
        val gap = max(bu.min() - au.max(), au.min() - bu.max())
        return gap <= MAX_GAP * max(aHeight, bHeight)
    }
}

/** A growable list of ints without boxing. */
internal class IntArrayList {
    private var values = IntArray(256)
    var size = 0
        private set

    operator fun get(index: Int) = values[index]

    fun add(value: Int) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    fun removeLast(): Int = values[--size]

    fun clear() {
        size = 0
    }
}

/** Reading order of the lines on a page. */
object ReadingOrder {
    /**
     * The order in which to read [lines]: columns one after the other, each from top to bottom, and
     * pieces of the same line from left to right. The page is first turned so that the lines are
     * level (by their mean angle, so a tilted photo works too), then cut recursively at the widest
     * gap (a recursive XY cut): a column gap must be at least one line high to count, so separate
     * boxes on one line stay together.
     */
    fun of(lines: List<OcrQuad>): List<Int> {
        if (lines.size < 2) return lines.indices.toList()
        // The mean direction of the text, weighted by length.
        var sumX = 0.0
        var sumY = 0.0
        for (line in lines) {
            val (topLeft, topRight) = line.corners
            sumX += topRight.x - topLeft.x
            sumY += topRight.y - topLeft.y
        }
        val length = sqrt(sumX * sumX + sumY * sumY)
        val cos = if (length > 0) sumX / length else 1.0
        val sin = if (length > 0) sumY / length else 0.0
        val items = lines.mapIndexed { index, line ->
            // Turned by minus the text's angle, so the lines are level.
            val xs = line.corners.map { it.x * cos + it.y * sin }
            val ys = line.corners.map { -it.x * sin + it.y * cos }
            Item(index, xs.min(), xs.max(), ys.min(), ys.max())
        }
        val lineHeight = items.map { it.bottom - it.top }.sorted()[items.size / 2].coerceAtLeast(1.0)
        val order = ArrayList<Int>(lines.size)
        cut(items, lineHeight, order)
        return order
    }

    private class Item(val index: Int, val left: Double, val right: Double, val top: Double, val bottom: Double) {
        // Lines are compared by their middle half, so boxes that overlap a little (tight line spacing)
        // still count as separate rows.
        val coreTop get() = top + (bottom - top) / 4
        val coreBottom get() = bottom - (bottom - top) / 4
    }

    private class Gap(val after: Double, val size: Double)

    /** The widest gap between items along one axis, with where it is; null without any gap. */
    private fun widestGap(items: List<Item>, start: (Item) -> Double, end: (Item) -> Double): Gap? {
        val sorted = items.sortedBy(start)
        var reach = end(sorted[0])
        var best: Gap? = null
        for (item in sorted.drop(1)) {
            val size = start(item) - reach
            if (size > 0 && (best == null || size > best.size)) best = Gap(reach, size)
            reach = max(reach, end(item))
        }
        return best
    }

    /** Cuts [all] recursively (with a stack of its own, as a page can have a thousand lines). */
    private fun cut(all: List<Item>, lineHeight: Double, order: MutableList<Int>) {
        val pending = ArrayDeque<List<Item>>()
        pending.addLast(all)
        while (pending.isNotEmpty()) {
            val items = pending.removeLast()
            if (items.size == 1) {
                order += items[0].index
                continue
            }
            val rows = widestGap(items, { it.coreTop }, { it.coreBottom })
            val columns = widestGap(items, { it.left }, { it.right })?.takeIf { it.size >= lineHeight }
            // A row gap measured between the middle halves of lines is half a line wider than the space.
            val rowSpace = rows?.let { it.size - lineHeight / 2 } ?: Double.NEGATIVE_INFINITY
            val (first, second) = when {
                columns != null && columns.size > rowSpace -> items.partition { it.right <= columns.after }
                rows != null -> items.partition { it.coreBottom <= rows.after }
                else -> {
                    // Overlapping boxes: left to right, as pieces of one line.
                    items.sortedWith(compareBy({ it.left }, { it.top })).mapTo(order) { it.index }
                    continue
                }
            }
            pending.addLast(second)
            pending.addLast(first)
        }
    }
}
