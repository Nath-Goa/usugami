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

    /**
     * At most this many boxes per page (from the top); the rest of a page denser than any real one is
     * left out. PaddleOCR stops after its first 1000 regions, tiny ones included, but it detects at
     * a lower resolution: at full size a page of small print can have more than 1000 words.
     */
    const val MAX_BOXES = 5000
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
        for (start in probabilities.indices) {
            if (result.size >= MAX_BOXES) break
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

/** The size pictures are read at. */
object OcrScale {
    /** The most pixels on a side: the native bridge's limit (usugami_ocr.cpp). */
    const val MAX_SIDE = 8192

    /**
     * The scale for reading a [width] × [height] picture: to [longSide] pixels on its long side,
     * enlarged at most [maxEnlarge] times. A larger picture is only shrunk as far as it needs to have
     * no more pixels than a 4:3 one of that size: shrunk to its long side, a long screenshot's text
     * would be a few pixels high. Never more than [MAX_SIDE] pixels on a side.
     */
    fun of(width: Int, height: Int, longSide: Int, maxEnlarge: Double): Double {
        require(width > 0 && height > 0 && longSide > 0 && maxEnlarge >= 1) { "Bad size" }
        val long = max(width, height).toDouble()
        val bySide = longSide / long
        val scale = if (bySide >= 1) {
            min(bySide, maxEnlarge)
        } else {
            min(1.0, max(bySide, sqrt(longSide * (longSide * 0.75) / (width.toDouble() * height))))
        }
        return min(scale, MAX_SIDE / long)
    }
}

/**
 * Words too large for text detection at full size. The detection model finds letters up to a few
 * hundred pixels high; a larger word (a poster, a sign, a slide's title photographed close up) it
 * finds only in pieces, or not at all. Detection on a small copy of the page finds such words, and
 * they are read in place of their pieces.
 */
object LargeText {
    /** How much of a box found on the small copy boxes found at full size may cover, for it to count as new. */
    private const val MAX_COVERED = 0.4f

    /** A box found at full size with at least this share of its area inside a large word is a piece of it. */
    private const val PIECE = 0.5f

    /**
     * The boxes of [coarse] (found on a small copy of the page) that may be large words: at least
     * [minHeight] high across their text (a line of ordinary print running down the page is long,
     * not large), covered less than [MAX_COVERED] by boxes of [fine] (found at full size, in the
     * same pixels), and with none of those running through them. A covered one is a line found
     * already, or a paragraph of small print that the small copy shows as one block; the pieces of
     * a large word found at full size cover little of it, and lie inside it. One that lines run
     * through is something else among them, such as a stamp or a picture: read, it would give a few
     * stray characters, and it would also take its place among the lines when they are put in
     * reading order, moving them.
     */
    fun candidates(coarse: List<OcrQuad>, fine: List<OcrQuad>, minHeight: Float): List<OcrQuad> = coarse.filter { box ->
        val area = area(box.corners)
        if (min(box.width, box.height) < minHeight || area <= 0f) return@filter false
        var covered = 0f
        for (line in fine) {
            val overlap = Overlap.of(line, box) ?: continue
            if (overlap.runsThrough) return@filter false
            covered += overlap.area
        }
        covered < MAX_COVERED * area
    }

    /**
     * The boxes to read: [fine] without the pieces of [words] (those mostly inside one), then
     * [words]. Left in, a piece would be read on its own, as a stray letter or two inside the word.
     */
    fun inPlaceOfPieces(fine: List<OcrQuad>, words: List<OcrQuad>): List<OcrQuad> =
        fine.filter { line -> words.none { Overlap.of(line, it)?.isPiece == true } } + words

    /**
     * The part of a line found at full size that lies inside a box found on the small copy, measured
     * in the line's own frame, so that it is the same on a tilted page: its [area], and how far it
     * reaches [along] and [across] the line, which is [lineArea] large and [thickness] thick.
     */
    private class Overlap(val area: Float, private val lineArea: Float, private val along: Float, private val across: Float, private val thickness: Float) {
        /** Whether the line is a piece of the box: mostly inside it. */
        val isPiece get() = area >= PIECE * lineArea

        /**
         * Whether the line runs through the box: it is mostly outside, but the box holds more than an
         * edge of it (at least half its thickness across, and twice its thickness along). The box of
         * a large word may graze the lines above and below it, or a word next to it, but no more.
         */
        val runsThrough get() = !isPiece && across >= thickness / 2 && along >= 2 * thickness

        companion object {
            /** The part of [line] inside [box], or null if they don't overlap. */
            fun of(line: OcrQuad, box: OcrQuad): Overlap? {
                val (origin, end, _, side) = line.corners
                val length = origin.distanceTo(end)
                val width = origin.distanceTo(side)
                if (length <= 0f || width <= 0f) return null
                val ux = (end.x - origin.x) / length
                val uy = (end.y - origin.y) / length
                fun u(p: OcrPoint) = (p.x - origin.x) * ux + (p.y - origin.y) * uy
                fun v(p: OcrPoint) = -(p.x - origin.x) * uy + (p.y - origin.y) * ux
                // The line spans 0..length along its first edge, and 0..width across it, on one side.
                val (near, far) = if (v(side) >= 0) 0f to width else -width to 0f
                var polygon = box.corners.map { OcrPoint(u(it), v(it)) }
                polygon = clip(polygon) { it.x }
                polygon = clip(polygon) { length - it.x }
                polygon = clip(polygon) { it.y - near }
                polygon = clip(polygon) { far - it.y }
                if (polygon.size < 3) return null
                val area = area(polygon)
                if (area <= 0f) return null
                val uExtent = polygon.maxOf { it.x } - polygon.minOf { it.x }
                val vExtent = polygon.maxOf { it.y } - polygon.minOf { it.y }
                return if (length >= width) {
                    Overlap(area, length * width, along = uExtent, across = vExtent, thickness = width)
                } else {
                    Overlap(area, length * width, along = vExtent, across = uExtent, thickness = length)
                }
            }

            /**
             * [polygon] cut along a straight edge (Sutherland-Hodgman), keeping the side where
             * [distance] from the edge is positive.
             */
            private fun clip(polygon: List<OcrPoint>, distance: (OcrPoint) -> Float): List<OcrPoint> {
                val kept = ArrayList<OcrPoint>(polygon.size + 2)
                for (i in polygon.indices) {
                    val from = polygon[i]
                    val to = polygon[(i + 1) % polygon.size]
                    val fromDistance = distance(from)
                    val toDistance = distance(to)
                    if (fromDistance >= 0) kept += from
                    if ((fromDistance >= 0) != (toDistance >= 0)) {
                        val t = fromDistance / (fromDistance - toDistance)
                        kept += OcrPoint(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
                    }
                }
                return kept
            }
        }
    }

    /** The area of the polygon with [corners] (shoelace formula). */
    private fun area(corners: List<OcrPoint>): Float {
        var twice = 0f
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            twice += a.x * b.y - b.x * a.y
        }
        return abs(twice) / 2
    }
}

/** What the detected boxes are: which are pieces of one line, and which lines run down the page. */
object TextLines {
    private const val MAX_ANGLE_DEGREES = 10.0
    private const val MIN_OVERLAP = 0.6f
    private const val MIN_HEIGHT_RATIO = 0.6f

    /**
     * Pieces of one line are at most this many line heights apart: more than a word space (about a
     * quarter), less than the gap between two columns, which in two-column papers and newspapers is
     * only about one line height. ReadingOrder takes such a gap down a whole column as a column gap.
     */
    private const val MAX_GAP = 0.5f

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
     * most [MAX_GAP] line heights apart along it. They are compared along the page's lines (which
     * run down a page turned a quarter round, as in [vertical]), unless both surely run the other
     * way: short words that stand tall on lines one under the other (है, a lone "I") aren't pieces
     * of a line running down the page. Boxes are taken with their corners as PaddleOCR orders them
     * (by position on the page), and the joined boxes come out the same way.
     */
    fun merge(boxes: List<OcrQuad>): List<Line> {
        val pageRunsDown = runsDown(boxes)
        // Boxes that surely run the other way from the page's lines.
        val against = BooleanArray(boxes.size) { i ->
            val box = boxes[i]
            if (pageRunsDown) box.width >= SURELY_TALL * box.height else box.height >= SURELY_TALL * box.width
        }
        val bounds = boxes.map { box ->
            val xs = box.corners.map { it.x }
            val ys = box.corners.map { it.y }
            floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max(), hypot(xs.max() - xs.min(), ys.max() - ys.min()))
        }
        val parent = IntArray(boxes.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            return r
        }
        for (a in boxes.indices) for (b in a + 1 until boxes.size) {
            if (!near(bounds[a], bounds[b])) continue
            val down = pageRunsDown != (against[a] && against[b])
            if (sameLine(boxes[a], boxes[b], down)) parent[root(b)] = root(a)
        }
        return boxes.indices.groupBy(::root).values.map { group ->
            if (group.size == 1) return@map Line(boxes[group[0]], group)
            val down = pageRunsDown != group.all { against[it] }
            val frame = frame(boxes[group.maxBy { maxOf(boxes[it].width, boxes[it].height) }], down)
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
        val pageRunsDown = runsDown(boxes)
        return BooleanArray(boxes.size) { i ->
            val box = boxes[i]
            box.height >= SURELY_TALL * box.width || (pageRunsDown && box.height >= TALL * box.width)
        }
    }

    /** Whether most of the text on the page runs down it (by length): a page turned a quarter round. */
    private fun runsDown(boxes: List<OcrQuad>): Boolean {
        var across = 0.0
        var down = 0.0
        for (box in boxes) {
            when {
                box.height >= SURELY_TALL * box.width -> down += box.height
                box.height < TALL * box.width -> across += box.width
            }
        }
        return down > across
    }

    /**
     * Whether two boxes' upright bounds (left, top, right, bottom, diagonal) are close enough for them
     * to be pieces of one line: no further apart than [MAX_GAP] times the larger box's size.
     */
    private fun near(a: FloatArray, b: FloatArray): Boolean {
        val reach = MAX_GAP * max(a[4], b[4])
        return a[0] - reach <= b[2] && b[0] - reach <= a[2] && a[1] - reach <= b[3] && b[1] - reach <= a[3]
    }

    /** A box's frame: u along its text, v across it; [down] for text running down the page. */
    private fun frame(quad: OcrQuad, down: Boolean): Frame {
        val (topLeft, topRight, _, bottomLeft) = quad.corners
        return if (down) Frame(topLeft, bottomLeft) else Frame(topLeft, topRight)
    }

    private class Frame(val origin: OcrPoint, towards: OcrPoint) {
        private val length = origin.distanceTo(towards).coerceAtLeast(1e-3f)
        val ux = (towards.x - origin.x) / length
        val uy = (towards.y - origin.y) / length

        fun u(p: OcrPoint) = (p.x - origin.x) * ux + (p.y - origin.y) * uy
        fun v(p: OcrPoint) = -(p.x - origin.x) * uy + (p.y - origin.y) * ux
        fun point(u: Float, v: Float) = OcrPoint(origin.x + u * ux - v * uy, origin.y + u * uy + v * ux)
    }

    /** Whether [a] and [b] are pieces of one line, compared as text running down the page if [down]. */
    private fun sameLine(a: OcrQuad, b: OcrQuad, down: Boolean): Boolean {
        val frame = frame(a, down)
        val other = frame(b, down)
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
    /** Columns of at least this many lines on each side of a gap narrower than a line height. */
    private const val MIN_COLUMN_LINES = 3

    /**
     * The order in which to read [lines]: columns one after the other, each from top to bottom, and
     * pieces of the same line from left to right. The page is first turned so that the lines are
     * level (by their mean angle, so a tilted photo works too), then cut recursively at the widest
     * gap (a recursive XY cut). A column gap must be at least one line high to count, so separate
     * boxes on one line stay together, or half a line between columns of [MIN_COLUMN_LINES] lines or
     * more: two-column papers and newspapers leave only about a line height between their columns.
     */
    fun of(lines: List<OcrQuad>): List<Int> {
        if (lines.size < 2) return lines.indices.toList()
        // The mean direction of the text, then again of only the lines within 45° of it, so that a
        // line at right angles to the rest (an arXiv paper's ID down its margin) doesn't tilt the page.
        val (cos, sin) = direction(lines, null)?.let { direction(lines, it) ?: it } ?: doubleArrayOf(1.0, 0.0)
        val items = lines.mapIndexed { index, line ->
            // Turned by minus the text's angle, so the lines are level.
            val xs = line.corners.map { it.x * cos + it.y * sin }
            val ys = line.corners.map { -it.x * sin + it.y * cos }
            val (topLeft, topRight) = line.corners
            val along = (topRight.x - topLeft.x) * cos + (topRight.y - topLeft.y) * sin
            val across = -(topRight.x - topLeft.x) * sin + (topRight.y - topLeft.y) * cos
            if (abs(across) < abs(along)) {
                // A line at an angle of its own (a page photographed at a slant fans its lines out) is
                // only as high as it is thick: the bounds of a long one would reach into its neighbours.
                val middle = ys.average()
                Item(index, xs.min(), xs.max(), middle - line.height / 2.0, middle + line.height / 2.0)
            } else {
                Item(index, xs.min(), xs.max(), ys.min(), ys.max())
            }
        }
        val lineHeight = items.map { it.bottom - it.top }.sorted()[items.size / 2].coerceAtLeast(1.0)
        val order = ArrayList<Int>(lines.size)
        cut(items, lineHeight, order)
        return order
    }

    /**
     * The mean direction (cos, sin) of [lines] from their top-left to their top-right corners,
     * weighted by length; with [around], of only the lines within 45° of that direction. Null if
     * there are none, or they cancel out.
     */
    private fun direction(lines: List<OcrQuad>, around: DoubleArray?): DoubleArray? {
        var sumX = 0.0
        var sumY = 0.0
        for (line in lines) {
            val (topLeft, topRight) = line.corners
            val dx = (topRight.x - topLeft.x).toDouble()
            val dy = (topRight.y - topLeft.y).toDouble()
            if (around != null && dx * around[0] + dy * around[1] <= sqrt(0.5) * hypot(dx, dy)) continue
            sumX += dx
            sumY += dy
        }
        val length = hypot(sumX, sumY)
        return if (length > 0) doubleArrayOf(sumX / length, sumY / length) else null
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
            val columns = widestGap(items, { it.left }, { it.right })?.takeIf { gap ->
                gap.size >= lineHeight || gap.size >= lineHeight / 2 &&
                    items.count { it.right <= gap.after } >= MIN_COLUMN_LINES &&
                    items.count { it.left > gap.after } >= MIN_COLUMN_LINES
            }
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
