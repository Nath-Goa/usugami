package io.github.nath_goa.usugami.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A page found on a photo: its corners and how sure the search is, from 0 (a guess) to 1. Only a
 * [confident] outline is worth starting from.
 */
data class PageOutline(val quad: CropQuad, val confidence: Float) {
    val confident: Boolean get() = confidence >= PageDetection.CONFIDENT
}

/**
 * Finds a sheet of paper on a photo, as it lies on a darker table: the bright region the page makes
 * (Otsu's threshold, then the largest bright region), its convex hull, the four-cornered shape that
 * covers most of the hull, and each side then fitted to the page's edge so rounded or folded corners
 * and a finger on the page don't pull the corners in. It runs on a small copy of the photo.
 */
object PageDetection {
    /** The search runs on a copy at most this many pixels on its long side. */
    const val WORK_SIDE = 400

    /** Below this confidence, the outline is a guess. */
    const val CONFIDENT = 0.5f

    /**
     * The page on an image whose brightness (0..255, row by row) is [luma], [width] × [height]
     * pixels, or null when nothing like a page stands out. The corners are fractions of the image's
     * size, in the order a page's corners have.
     */
    fun find(luma: ByteArray, width: Int, height: Int): PageOutline? {
        require(width > 0 && height > 0 && luma.size == width * height) { "Bad image size" }
        val image = shrink(luma, width, height)
        return find(image)
    }

    /** A grey image, one level (0..255) per pixel. */
    internal class Grey(val width: Int, val height: Int, val levels: IntArray)

    /** [luma] averaged down to at most [WORK_SIDE] pixels on its long side (which also evens out noise). */
    internal fun shrink(luma: ByteArray, width: Int, height: Int): Grey {
        val factor = max(1, ceil(max(width, height) / WORK_SIDE.toDouble()).toInt())
        val w = (width + factor - 1) / factor
        val h = (height + factor - 1) / factor
        val sums = IntArray(w * h)
        val counts = IntArray(w * h)
        for (y in 0 until height) {
            val row = (y / factor) * w
            for (x in 0 until width) {
                val i = row + x / factor
                sums[i] += luma[y * width + x].toInt() and 0xFF
                counts[i]++
            }
        }
        return Grey(w, h, IntArray(w * h) { sums[it] / counts[it] })
    }

    internal fun find(image: Grey): PageOutline? {
        val w = image.width
        val h = image.height
        if (w < 8 || h < 8) return null
        // On a log scale a shadow darkens the paper and the table by the same step, so the split
        // between them stays where it is instead of cutting off the paper in shadow.
        val levels = IntArray(image.levels.size) { LOG_LEVELS[image.levels[it]] }
        val threshold = otsu(levels) ?: return null
        val bright = BooleanArray(levels.size) { levels[it] > threshold }
        val open = opened(bright, w, h)
        val page = largestRegion(open, w, h) ?: return null
        // The page with its holes (the print on it) filled: everything the outside can't reach.
        val outside = outside(page, w, h)
        val filledArea = outside.count { !it }
        if (filledArea < MIN_AREA * w * h) return null

        val edge = edgePoints(outside, w, h)
        val hull = MinAreaRect.convexHull(edge)
        if (hull.size < 4) return null
        val corners = refined(hull, largestQuad(hull), edge, w, h)

        val quad = CropQuad(corners.map { CropPoint((it.x / w).coerceIn(0f, 1f), (it.y / h).coerceIn(0f, 1f)) }).ordered()
        val confidence = confidence(quad, image, page, outside, filledArea)
        return PageOutline(quad, confidence)
    }

    /**
     * Otsu's threshold: the level that best splits the image into a dark and a bright part. Null
     * for an image that is (nearly) one flat shade.
     */
    internal fun otsu(levels: IntArray): Int? {
        val histogram = IntArray(256)
        for (v in levels) histogram[v]++
        val total = levels.size.toDouble()
        var sum = 0.0
        for (i in 0..255) sum += i.toDouble() * histogram[i]
        val mean = sum / total
        var variance = 0.0
        for (i in 0..255) variance += (i - mean) * (i - mean) * histogram[i]
        if (variance / total < MIN_VARIANCE) return null
        var below = 0.0
        var belowSum = 0.0
        var best = -1.0
        var threshold = 0
        for (t in 0..254) {
            below += histogram[t]
            if (below == 0.0) continue
            val above = total - below
            if (above == 0.0) break
            belowSum += t.toDouble() * histogram[t]
            val difference = belowSum / below - (sum - belowSum) / above
            val between = below * above * difference * difference
            if (between > best) {
                best = between
                threshold = t
            }
        }
        return threshold
    }

    /** [mask] eroded then dilated (3 × 3): thin bright bridges, such as a cable touching the page, go. */
    private fun opened(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        fun pass(source: BooleanArray, keep: Boolean): BooleanArray = BooleanArray(source.size) { i ->
            val x = i % w
            val y = i / w
            var all = true
            var any = false
            for (dy in -1..1) for (dx in -1..1) {
                val nx = (x + dx).coerceIn(0, w - 1)
                val ny = (y + dy).coerceIn(0, h - 1)
                val v = source[ny * w + nx]
                all = all && v
                any = any || v
            }
            if (keep) all else any
        }
        return pass(pass(mask, keep = true), keep = false)
    }

    /** The largest 4-connected region of [mask], or null if it has none. */
    private fun largestRegion(mask: BooleanArray, w: Int, h: Int): BooleanArray? {
        val label = IntArray(mask.size)
        val stack = IntArrayList()
        var bestLabel = 0
        var bestSize = 0
        var next = 0
        for (start in mask.indices) {
            if (!mask[start] || label[start] != 0) continue
            next++
            var size = 0
            stack.clear()
            stack.add(start)
            label[start] = next
            while (stack.size > 0) {
                val i = stack.removeLast()
                size++
                val x = i % w
                val y = i / w
                if (x > 0 && mask[i - 1] && label[i - 1] == 0) { label[i - 1] = next; stack.add(i - 1) }
                if (x < w - 1 && mask[i + 1] && label[i + 1] == 0) { label[i + 1] = next; stack.add(i + 1) }
                if (y > 0 && mask[i - w] && label[i - w] == 0) { label[i - w] = next; stack.add(i - w) }
                if (y < h - 1 && mask[i + w] && label[i + w] == 0) { label[i + w] = next; stack.add(i + w) }
            }
            if (size > bestSize) {
                bestSize = size
                bestLabel = next
            }
        }
        if (bestLabel == 0) return null
        return BooleanArray(mask.size) { label[it] == bestLabel }
    }

    /** The pixels outside [region] that the image's edges reach without crossing it (4-connected). */
    private fun outside(region: BooleanArray, w: Int, h: Int): BooleanArray {
        val reached = BooleanArray(region.size)
        val stack = IntArrayList()
        fun push(i: Int) {
            if (!region[i] && !reached[i]) {
                reached[i] = true
                stack.add(i)
            }
        }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (stack.size > 0) {
            val i = stack.removeLast()
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
        return reached
    }

    /**
     * The filled page's outermost pixels: the first and last of each row and of each column, at the
     * pixels' centres. Enough for its convex hull, and dense along every side for fitting lines.
     */
    private fun edgePoints(outside: BooleanArray, w: Int, h: Int): List<OcrPoint> {
        val points = ArrayList<OcrPoint>()
        for (y in 0 until h) {
            var first = -1
            var last = -1
            for (x in 0 until w) if (!outside[y * w + x]) { if (first < 0) first = x; last = x }
            if (first >= 0) {
                points += OcrPoint(first + 0.5f, y + 0.5f)
                if (last != first) points += OcrPoint(last + 0.5f, y + 0.5f)
            }
        }
        for (x in 0 until w) {
            var first = -1
            var last = -1
            for (y in 0 until h) if (!outside[y * w + x]) { if (first < 0) first = y; last = y }
            if (first >= 0) {
                points += OcrPoint(x + 0.5f, first + 0.5f)
                if (last != first) points += OcrPoint(x + 0.5f, last + 0.5f)
            }
        }
        return points
    }

    private fun triangle(a: OcrPoint, b: OcrPoint, c: OcrPoint): Double =
        abs((b.x - a.x).toDouble() * (c.y - a.y) - (b.y - a.y).toDouble() * (c.x - a.x)) / 2

    /**
     * Which four corners of [hull] (a convex polygon) make the largest four-cornered shape: their
     * indices in the hull's order, each later one past the hull's end counting on from n. For each
     * diagonal i–k, the best third and fourth corners lie on either side and only move forwards as
     * k does, so this takes n² steps.
     */
    internal fun largestQuad(hull: List<OcrPoint>): IntArray {
        val n = hull.size
        require(n >= 4)
        fun p(i: Int) = hull[i % n]
        var best = -1.0
        var corners = intArrayOf(0, 1, 2, 3)
        for (i in 0 until n) {
            var j = i + 1
            var l = i + 3
            for (k in i + 2..i + n - 2) {
                while (j + 1 < k && triangle(p(i), p(j + 1), p(k)) >= triangle(p(i), p(j), p(k))) j++
                if (l <= k) l = k + 1
                while (l + 1 < i + n && triangle(p(k), p(l + 1), p(i)) >= triangle(p(k), p(l), p(i))) l++
                val area = triangle(p(i), p(j), p(k)) + triangle(p(k), p(l), p(i))
                if (area > best) {
                    best = area
                    corners = intArrayOf(i, j, k, l)
                }
            }
        }
        return corners
    }

    /**
     * The page's corners: where its sides meet. Each side of the four-cornered shape ([corners] of
     * [hull]) lies along the longest stretch of the hull between its corners, which a folded or
     * rounded corner doesn't bend; the line that best fits the page's [edge] points along that
     * stretch (away from the corners) is the side. A corner that would move far stays where it was.
     */
    private fun refined(hull: List<OcrPoint>, corners: IntArray, edge: List<OcrPoint>, w: Int, h: Int): List<OcrPoint> {
        val n = hull.size
        fun p(i: Int) = hull[i % n]
        val lines = arrayOfNulls<Line>(4)
        for (side in 0 until 4) {
            val from = corners[side]
            val to = if (side == 3) corners[0] + n else corners[side + 1]
            // The longest stretch of the hull along this side.
            var longest = from
            for (k in from until to) if (p(k).distanceTo(p(k + 1)) > p(longest).distanceTo(p(longest + 1))) longest = k
            val start = p(longest)
            val stretch = start.distanceTo(p(longest + 1))
            if (stretch < 2) continue
            val ux = (p(longest + 1).x - start.x) / stretch
            val uy = (p(longest + 1).y - start.y) / stretch
            fun along(q: OcrPoint) = (q.x - start.x) * ux + (q.y - start.y) * uy
            val first = along(p(from))
            val last = along(p(to))
            val margin = 0.1f * (last - first)
            val reach = 1.5f + 0.01f * (last - first)
            val near = edge.filter { q ->
                val t = along(q)
                t > first + margin && t < last - margin && abs(-(q.x - start.x) * uy + (q.y - start.y) * ux) <= reach
            }
            lines[side] = if (near.size >= 6) Line.fit(near) else Line(start, ux, uy)
        }
        val limit = 0.1f * hypot(w.toFloat(), h.toFloat())
        return List(4) { i ->
            val corner = p(corners[i])
            val before = lines[(i + 3) % 4]
            val after = lines[i]
            val meet = if (before != null && after != null) before.meet(after) else null
            if (meet != null && meet.distanceTo(corner) <= limit) meet else corner
        }
    }

    /** A straight line through [point] along the unit vector ([dx], [dy]). */
    private class Line(val point: OcrPoint, val dx: Float, val dy: Float) {
        /** Where this line crosses [other], or null if they are (nearly) parallel. */
        fun meet(other: Line): OcrPoint? {
            val det = dx * other.dy - dy * other.dx
            if (abs(det) < 1e-4f) return null
            val t = ((other.point.x - point.x) * other.dy - (other.point.y - point.y) * other.dx) / det
            return OcrPoint(point.x + t * dx, point.y + t * dy)
        }

        companion object {
            /** The line closest to [points] (total least squares: along their main direction). */
            fun fit(points: List<OcrPoint>): Line {
                val cx = points.sumOf { it.x.toDouble() } / points.size
                val cy = points.sumOf { it.y.toDouble() } / points.size
                var sxx = 0.0
                var syy = 0.0
                var sxy = 0.0
                for (p in points) {
                    val x = p.x - cx
                    val y = p.y - cy
                    sxx += x * x
                    syy += y * y
                    sxy += x * y
                }
                val angle = 0.5 * kotlin.math.atan2(2 * sxy, sxx - syy)
                return Line(OcrPoint(cx.toFloat(), cy.toFloat()), kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
            }
        }
    }

    /**
     * How sure the outline is, from 0 to 1. A page is large enough on the photo, fills its outline
     * (a four-cornered shape, unlike a round plate or a hand), is clearly brighter than what is
     * around it, doesn't run off the photo (a corner out of the frame is a guess) and its corners
     * aren't squashed flat.
     */
    private fun confidence(quad: CropQuad, image: Grey, page: BooleanArray, outside: BooleanArray, filledArea: Int): Float {
        val w = image.width
        val h = image.height
        val xs = DoubleArray(4) { quad[it].x.toDouble() * w }
        val ys = DoubleArray(4) { quad[it].y.toDouble() * h }
        var twice = 0.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            twice += xs[i] * ys[j] - xs[j] * ys[i]
        }
        val quadArea = abs(twice) / 2
        if (quadArea <= 0) return 0f
        val size = ramp(quadArea / (w.toDouble() * h), MIN_AREA, GOOD_AREA)
        val fill = ramp(min(quadArea, filledArea.toDouble()) / max(quadArea, filledArea.toDouble()), 0.82, 0.94)

        var pageSum = 0L
        var pageCount = 0
        var aroundSum = 0L
        var aroundCount = 0
        for (i in page.indices) {
            if (page[i]) { pageSum += image.levels[i]; pageCount++ } else if (outside[i]) { aroundSum += image.levels[i]; aroundCount++ }
        }
        if (pageCount == 0 || aroundCount == 0) return 0f
        val contrast = ramp(pageSum.toDouble() / pageCount - aroundSum.toDouble() / aroundCount, 18.0, 45.0)

        // How much of the page lies along the photo's edges, where it may go on out of the frame.
        var perimeter = 0.0
        for (i in 0 until 4) perimeter += hypot(xs[(i + 1) % 4] - xs[i], ys[(i + 1) % 4] - ys[i])
        var along = 0
        for (x in 0 until w) {
            if (!outside[x]) along++
            if (!outside[(h - 1) * w + x]) along++
        }
        for (y in 1 until h - 1) {
            if (!outside[y * w]) along++
            if (!outside[y * w + w - 1]) along++
        }
        val inFrame = 1 - ramp(along / perimeter, 0.03, 0.2)

        var angles = 1.0
        for (i in 0 until 4) {
            val prev = (i + 3) % 4
            val next = (i + 1) % 4
            val ax = xs[prev] - xs[i]
            val ay = ys[prev] - ys[i]
            val bx = xs[next] - xs[i]
            val by = ys[next] - ys[i]
            val cos = (ax * bx + ay * by) / (sqrt(ax * ax + ay * ay) * sqrt(bx * bx + by * by))
            val degrees = Math.toDegrees(acos(cos.coerceIn(-1.0, 1.0)))
            angles = min(angles, ramp(degrees, 35.0, 50.0) * (1 - ramp(degrees, 130.0, 145.0)))
        }
        return (size * fill * contrast * inFrame * angles).toFloat()
    }

    /** Each level 0..255 on a logarithmic scale, also 0..255. */
    private val LOG_LEVELS = IntArray(256) { (255 * ln(1.0 + it) / ln(256.0)).roundToInt() }

    /** 0 below [low], 1 above [high], in a straight line between. */
    private fun ramp(value: Double, low: Double, high: Double): Double = ((value - low) / (high - low)).coerceIn(0.0, 1.0)

    /** A page covering less of the photo than this isn't looked for. */
    private const val MIN_AREA = 0.08
    /** A page covering at least this much of the photo is large enough to be sure of. */
    private const val GOOD_AREA = 0.2
    /** An image whose brightness varies less than this (its variance) has nothing to find. */
    private const val MIN_VARIANCE = 30.0
}
