package io.github.nath_goa.usugami.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.floor
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
 * Finds a sheet of paper on a photo, on a small copy of it, in two ways.
 *
 * By brightness: the bright region a page makes on a darker table (Otsu's threshold, then the
 * largest bright region), its convex hull, the four-cornered shape that covers most of the hull, and
 * each side then fitted to the page's edge so rounded or folded corners and a finger on the page
 * don't pull the corners in.
 *
 * By its edges, as document scanners do: the photo's edges (Canny's), the long straight ones among
 * them (a Hough transform), and the four of those that best make a page's outline, its sides lying
 * on edges from corner to corner and ending at the corners. This asks nothing of the page's
 * brightness, so it also finds a white page on a white desk by the faint shadow along its edges, a
 * page on a brighter table, and in colour a page that differs from the desk only in its colour.
 *
 * Each outline gets a confidence of its own kind, and the surer one is the page; when they are as
 * sure, the one by edges, whose sides are fitted to the edges themselves. Its sides then move in to
 * just inside the paper, past the blur of its edge and the shadow it casts, so no strip of the desk
 * shows along the straightened page.
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
        require(width > 0 && height > 0 && luma.size.toLong() == width.toLong() * height) { "Bad image size" }
        return find(shrink(luma, width, height))
    }

    /**
     * As [find] for brightness, on an image's colours ([argb], 0xAARRGGBB row by row): edges are
     * looked for in each colour, so a white page on a pale blue desk, nearly as bright, stands out by
     * its colour.
     */
    fun find(argb: IntArray, width: Int, height: Int): PageOutline? {
        require(width > 0 && height > 0 && argb.size.toLong() == width.toLong() * height) { "Bad image size" }
        return find(shrink(argb, width, height))
    }

    /**
     * A small copy of a photo: its brightness, one level (0..255) per pixel, and the levels of each
     * of its colours (or the brightness alone).
     */
    internal class Grey(val width: Int, val height: Int, val levels: IntArray, val channels: List<IntArray> = listOf(levels))

    /** [luma] averaged down to at most [WORK_SIDE] pixels on its long side (which also evens out noise). */
    internal fun shrink(luma: ByteArray, width: Int, height: Int): Grey {
        val factor = factor(width, height)
        val levels = averaged(width, height, factor) { luma[it].toInt() and 0xFF }
        return Grey((width + factor - 1) / factor, (height + factor - 1) / factor, levels)
    }

    /** [argb]'s colours averaged down as [shrink] does, with their brightness. */
    private fun shrink(argb: IntArray, width: Int, height: Int): Grey {
        val factor = factor(width, height)
        val red = averaged(width, height, factor) { (argb[it] shr 16) and 0xFF }
        val green = averaged(width, height, factor) { (argb[it] shr 8) and 0xFF }
        val blue = averaged(width, height, factor) { argb[it] and 0xFF }
        val luma = IntArray(red.size) { (red[it] * 77 + green[it] * 150 + blue[it] * 29) shr 8 }
        return Grey((width + factor - 1) / factor, (height + factor - 1) / factor, luma, listOf(red, green, blue))
    }

    /** How many pixels across and down make one of the small copy's. */
    private fun factor(width: Int, height: Int) = max(1, ceil(max(width, height) / WORK_SIDE.toDouble()).toInt())

    /** The mean of [level] (of each pixel's index) over each [factor] × [factor] block of a [width] × [height] image. */
    private inline fun averaged(width: Int, height: Int, factor: Int, level: (Int) -> Int): IntArray {
        val w = (width + factor - 1) / factor
        val h = (height + factor - 1) / factor
        val sums = IntArray(w * h)
        val counts = IntArray(w * h)
        for (y in 0 until height) {
            val row = (y / factor) * w
            for (x in 0 until width) {
                val i = row + x / factor
                sums[i] += level(y * width + x)
                counts[i]++
            }
        }
        return IntArray(w * h) { sums[it] / counts[it] }
    }

    /**
     * The surer of the outlines found by edges and by brightness on [image], the one by edges if as
     * sure, its sides then moved in to just inside the paper ([inside]).
     */
    private fun find(image: Grey): PageOutline? {
        if (image.width < 8 || image.height < 8) return null
        val page = listOfNotNull(byLines(image), byBrightness(image)).maxByOrNull { it.confidence } ?: return null
        return page.copy(quad = inside(page.quad, image))
    }

    /**
     * [quad] with each side moved in to where the paper starts. Either search puts a side along the
     * middle of the page's edge: the step from the desk to the paper, blurred over a pixel or two, and
     * on a light desk the shadow line a page casts; and a side may lie a pixel or two askew of the
     * edge. Left there, a thin strip of desk shows along the straightened page. How far in the paper
     * starts along each side is measured on [image] ([insets]), and each end of a side moves in at
     * most [MAX_INSET] pixels, so the page's own margins stay. The corners stay on the image.
     */
    private fun inside(quad: CropQuad, image: Grey): CropQuad {
        val w = image.width
        val h = image.height
        val (xs, ys) = pixels(quad, w, h)
        if (area(xs, ys) <= 0) return quad
        // Into the page from each side: to its right when the corners go clockwise.
        val turn = if ((xs[1] - xs[0]) * (ys[2] - ys[1]) - (ys[1] - ys[0]) * (xs[2] - xs[1]) > 0) 1.0 else -1.0
        val sides = List(4) { i ->
            val j = (i + 1) % 4
            val length = hypot(xs[j] - xs[i], ys[j] - ys[i])
            if (length < 1) return quad
            val dx = (xs[j] - xs[i]) / length
            val dy = (ys[j] - ys[i]) / length
            val inX = -dy * turn
            val inY = dx * turn
            val (first, last) = insets(image, xs[i], ys[i], dx, dy, inX, inY, length)
            val ax = xs[i] + first * inX
            val ay = ys[i] + first * inY
            val bx = xs[j] + last * inX
            val by = ys[j] + last * inY
            val moved = hypot(bx - ax, by - ay)
            if (moved < 1) return quad
            Line(OcrPoint(ax.toFloat(), ay.toFloat()), ((bx - ax) / moved).toFloat(), ((by - ay) / moved).toFloat())
        }
        val corners = List(4) { i ->
            val corner = sides[(i + 3) % 4].meet(sides[i]) ?: return quad
            // Sides nearly in a line meet far along them: such a corner stays where it was.
            if (hypot(corner.x - xs[i], corner.y - ys[i]) > 4 * MAX_INSET) return quad
            CropPoint((corner.x / w).coerceIn(0f, 1f), (corner.y / h).coerceIn(0f, 1f))
        }
        return CropQuad(corners)
    }

    /**
     * How far in from a side the paper starts on [image], in its pixels, at the side's two ends: the
     * side goes from ([x], [y]) [length] pixels along ([dx], [dy]), and into the page along ([inX],
     * [inY]). It is measured in a few stretches along the side ([paperStart]). The line that best fits
     * where the paper starts in those away from the corners, moved in to the one furthest in, gives
     * the ends; and an end moves in further where the paper starts further in near it, as by a corner
     * a little rounded or curled. Each end moves in between 0 and [MAX_INSET].
     */
    private fun insets(image: Grey, x: Double, y: Double, dx: Double, dy: Double, inX: Double, inY: Double, length: Double): Pair<Double, Double> {
        val stretch = length * (1 - 2 * END_SHARE) / STRETCHES
        // Where along the side each stretch's middle is, and how far in its paper starts.
        val middles = ArrayList<Double>()
        val starts = ArrayList<Double>()
        for (s in 0 until STRETCHES) {
            val from = length * END_SHARE + s * stretch
            val start = paperStart(image, x, y, dx, dy, inX, inY, from, from + stretch) ?: continue
            middles += from + stretch / 2
            starts += start
        }
        var first = 0.0
        var last = 0.0
        if (starts.isNotEmpty()) {
            val meanAlong = middles.average()
            val meanStart = starts.average()
            var spread = 0.0
            var together = 0.0
            for (s in starts.indices) {
                spread += (middles[s] - meanAlong) * (middles[s] - meanAlong)
                together += (middles[s] - meanAlong) * (starts[s] - meanStart)
            }
            val slope = if (spread > 0) together / spread else 0.0
            fun fitted(along: Double) = meanStart + slope * (along - meanAlong)
            val furthest = starts.indices.maxOf { starts[it] - fitted(middles[it]) }
            first = fitted(0.0) + furthest
            last = fitted(length) + furthest
        }
        // Near each end, far enough from the corner that the shade across the side is clear of the
        // other side's edge.
        val clear = PAPER_TO + OUTSIDE
        val end = length * END_SHARE
        paperStart(image, x, y, dx, dy, inX, inY, clear, end)?.let { first = max(first, it) }
        paperStart(image, x, y, dx, dy, inX, inY, length - end, length - clear)?.let { last = max(last, it) }
        return first.coerceIn(0.0, MAX_INSET) to last.coerceIn(0.0, MAX_INSET)
    }

    /**
     * How far in from a side the paper starts on [image] along the stretch of it [from] .. [to] pixels
     * along: the side goes from ([x], [y]) along ([dx], [dy]), and into the page along ([inX], [inY]).
     * The shade across the side is compared with the paper's a little further in: the paper starts
     * where three quarters of the stretch are as near the paper's shade, in every colour, as its own
     * grain and a small share of the step at its edge, from there on in. Null when the stretch is too
     * short, or doesn't come to the paper's shade before [PAPER_FROM] (a hand over the edge, print
     * close to it).
     */
    private fun paperStart(image: Grey, x: Double, y: Double, dx: Double, dy: Double, inX: Double, inY: Double, from: Double, to: Double): Double? {
        val w = image.width
        val h = image.height
        val channels = image.channels
        val depths = ((PAPER_TO + OUTSIDE) / INSET_STEP).toInt() + 1
        fun depth(k: Int) = k * INSET_STEP - OUTSIDE
        val firstPaper = ((PAPER_FROM + OUTSIDE) / INSET_STEP).toInt()
        val onEdge = (OUTSIDE / INSET_STEP).toInt()
        val along = ArrayList<Double>()
        var t = from + 0.5
        while (t < to) {
            val px = x + t * dx
            val py = y + t * dy
            if (px >= 0 && py >= 0 && px <= w && py <= h) along += t
            t += 1.0
        }
        val n = along.size
        if (n < MIN_STRETCH) return null
        // Each colour's level at each depth and point along the stretch.
        val levels = Array(channels.size) { FloatArray(depths * n) }
        for (k in 0 until depths) for (p in 0 until n) {
            val px = x + along[p] * dx + depth(k) * inX
            val py = y + along[p] * dy + depth(k) * inY
            for (c in channels.indices) levels[c][k * n + p] = sampled(channels[c], w, h, px, py)
        }
        val paper = DoubleArray(channels.size) { c -> levels[c].copyOfRange(firstPaper * n, depths * n).sorted()[(depths - firstPaper) * n / 2].toDouble() }
        // How far from the paper's shade three quarters of the stretch are, at each depth.
        val off = DoubleArray(depths) { k ->
            val apart = DoubleArray(n) { p -> channels.indices.maxOf { c -> abs(levels[c][k * n + p] - paper[c]) } }
            apart.sorted()[3 * n / 4]
        }
        // As near as the paper's own grain is to its shade, and a small share of the step at its edge;
        // or as near as is too little to see.
        val grain = off.copyOfRange(firstPaper, depths).sorted()[(depths - firstPaper) / 2]
        val step = (0..onEdge).maxOf { off[it] }
        val close = max(INSET_LEVELS, grain + INSET_SHARE * step)
        var start = firstPaper
        while (start > onEdge && off[start - 1] <= close) start--
        return if (start == firstPaper) null else depth(start)
    }

    /** [channel]'s level at ([x], [y]) on a [w] × [h] image (pixel centres at .5), in a straight line between pixels. */
    private fun sampled(channel: IntArray, w: Int, h: Int, x: Double, y: Double): Float {
        val fx = (x - 0.5).coerceIn(0.0, w - 1.0)
        val fy = (y - 0.5).coerceIn(0.0, h - 1.0)
        val x0 = min(fx.toInt(), w - 2)
        val y0 = min(fy.toInt(), h - 2)
        val tx = (fx - x0).toFloat()
        val ty = (fy - y0).toFloat()
        val i = y0 * w + x0
        val top = channel[i] + (channel[i + 1] - channel[i]) * tx
        val bottom = channel[i + w] + (channel[i + w + 1] - channel[i + w]) * tx
        return top + (bottom - top) * ty
    }

    /** The page as the bright region on [image], or null if there is none large enough. */
    internal fun byBrightness(image: Grey): PageOutline? {
        val w = image.width
        val h = image.height
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
        return PageOutline(quad, brightnessConfidence(quad, image, page, outside, filledArea))
    }

    /**
     * The page as four straight edges that make a page's outline on [image], or null if no four do.
     * Every four of the image's long straight edges ([straightEdges]) that make a four-cornered shape
     * are scored by how much of its sides lies on their edges, less what doesn't; the best few are
     * fitted to their edges and weighed ([edgeConfidence]), and of those about as sure as the surest,
     * the best scored is the page.
     */
    private fun byLines(image: Grey): PageOutline? {
        val w = image.width
        val h = image.height
        val edges = edges(w, h, image.channels)
        val lines = straightEdges(edges)
        val n = lines.size
        // Where each two lines meet at a page's corner: at an angle a corner can have, on the image.
        val meets = arrayOfNulls<OcrPoint>(n * n)
        for (i in 0 until n) for (j in i + 1 until n) {
            val a = lines[i]
            val b = lines[j]
            if (abs(a.dx * b.dy - a.dy * b.dx) < SIN_CORNER) continue
            val corner = a.meet(b) ?: continue
            if (corner.x < -OFF_IMAGE || corner.y < -OFF_IMAGE || corner.x > w + OFF_IMAGE || corner.y > h + OFF_IMAGE) continue
            meets[i * n + j] = corner
            meets[j * n + i] = corner
        }
        // Pairs of lines that could be opposite sides: running roughly the same way, and apart.
        val minGap = MIN_GAP * min(w, h)
        val pairs = ArrayList<IntArray>()
        for (i in 0 until n) for (j in i + 1 until n) {
            val a = lines[i]
            val b = lines[j]
            if (abs(a.dx * b.dy - a.dy * b.dx) > SIN_OPPOSITE) continue
            // How far apart they are across the image's middle.
            val middle = a.point(a.along(w / 2f, h / 2f))
            if (abs(b.nx * middle.x + b.ny * middle.y - b.rho) >= minGap) pairs += intArrayOf(i, j)
        }
        // Straight edges every way (a pile of sticks, a wire basket) make many pairs, and the shapes
        // tried grow as the square of their number: then only the pairs whose edges run longest
        // unbroken, as a page's sides do, are tried.
        val tried = if (pairs.size <= MAX_PAIRS) pairs else {
            pairs.indices.sortedByDescending { lines[pairs[it][0]].longestRun + lines[pairs[it][1]].longestRun }
                .take(MAX_PAIRS).sorted().map { pairs[it] }
        }
        val best = ArrayList<Pair<Double, IntArray>>()
        val corners = arrayOfNulls<OcrPoint>(4)
        for (p in tried.indices) for (q in p + 1 until tried.size) {
            val (i, j) = tried[p]
            val (k, l) = tried[q]
            // Clockwise or not, the sides in order round the shape: i, k, j, l.
            corners[0] = meets[l * n + i] ?: continue
            corners[1] = meets[i * n + k] ?: continue
            corners[2] = meets[k * n + j] ?: continue
            corners[3] = meets[j * n + l] ?: continue
            val sides = intArrayOf(i, k, j, l)
            val score = score(sides, lines, corners, w, h) ?: continue
            if (best.size < CANDIDATES || score > best.last().first) {
                best += score to sides
                best.sortByDescending { it.first }
                if (best.size > CANDIDATES) best.removeAt(best.size - 1)
            }
        }
        val outlines = best.mapNotNull { (_, sides) ->
            val corners = fitted(sides.map { lines[it] }, edges) ?: return@mapNotNull null
            val quad = CropQuad(corners.map { CropPoint((it.x / w).coerceIn(0f, 1f), (it.y / h).coerceIn(0f, 1f)) }).ordered()
            PageOutline(quad, edgeConfidence(quad, edges, image.levels))
        }
        // Of outlines about as sure as the surest, the best scored: its sides have the most edge.
        val surest = outlines.maxOfOrNull { it.confidence } ?: return null
        return outlines.first { it.confidence >= surest - NEARLY_AS_SURE }
    }

    /**
     * How well four [lines] in order round a shape ([sides], meeting at [corners], the first between
     * the last side and the first) make a page's outline on a [w] × [h] image: how much of each side,
     * between its corners, lies on its edge, less how much doesn't. Null when they don't make a
     * page's shape: not convex, or too small.
     */
    private fun score(sides: IntArray, lines: List<StraightEdge>, corners: Array<OcrPoint?>, w: Int, h: Int): Double? {
        val xs = DoubleArray(4) { corners[it]!!.x.toDouble() }
        val ys = DoubleArray(4) { corners[it]!!.y.toDouble() }
        var sign = 0
        for (s in 0 until 4) {
            val t = (s + 1) % 4
            val u = (s + 2) % 4
            val cross = (xs[t] - xs[s]) * (ys[u] - ys[t]) - (ys[t] - ys[s]) * (xs[u] - xs[t])
            val turn = if (cross > 0) 1 else -1
            if (sign == 0) sign = turn else if (turn != sign) return null
        }
        if (area(xs, ys) < MIN_AREA * w * h) return null
        var score = 0.0
        val pastStart = FloatArray(4)
        val pastEnd = FloatArray(4)
        for (s in 0 until 4) {
            val line = lines[sides[s]]
            val from = line.along(corners[s]!!.x, corners[s]!!.y)
            val to = line.along(corners[(s + 1) % 4]!!.x, corners[(s + 1) % 4]!!.y)
            val length = abs(to - from)
            val supported = line.supported(min(from, to), max(from, to))
            score += 2.0 * supported - length
            // How much of the line goes on past either corner, as in [edgeConfidence].
            val reach = (0.1f * length).coerceIn(5f, 15f)
            val forward = if (to > from) 1f else -1f
            pastStart[s] = line.supported(min(from - forward * reach, from - forward * 2), max(from - forward * reach, from - forward * 2)) / (reach - 2)
            pastEnd[s] = line.supported(min(to + forward * 2, to + forward * reach), max(to + forward * 2, to + forward * reach)) / (reach - 2)
        }
        // A square of a grid, with lines crossing at its corners, isn't worth weighing.
        val crossed = (0 until 4).count { min(pastEnd[(it + 3) % 4], pastStart[it]) >= ON_PAST }
        return if (crossed >= 3) null else score
    }

    /**
     * The corners of the shape four lines in order round it ([sides]) make, each line first fitted
     * to the edge pixels along it between its corners (away from them), so a line that carries on
     * past the page doesn't tilt its side. Null when two sides don't meet.
     */
    private fun fitted(sides: List<StraightEdge>, edges: Edges): List<OcrPoint>? {
        val rough = List(4) { sides[(it + 3) % 4].meet(sides[it]) ?: return null }
        val lines = List(4) { s ->
            val line = sides[s]
            val from = line.along(rough[s].x, rough[s].y)
            val to = line.along(rough[(s + 1) % 4].x, rough[(s + 1) % 4].y)
            val start = min(from, to)
            val length = abs(to - from)
            val points = ArrayList<OcrPoint>()
            var t = start + END_SHARE.toFloat() * length
            while (t <= start + (1 - END_SHARE.toFloat()) * length) {
                val p = line.point(t)
                val i = edges.nearest(p.x, p.y, line.nx, line.ny)
                if (i >= 0) points += OcrPoint(i % edges.width + 0.5f, i / edges.width + 0.5f)
                t += 1f
            }
            if (points.size >= 6) Line.fit(points) else Line(line.point(start), line.dx, line.dy)
        }
        return List(4) { lines[(it + 3) % 4].meet(lines[it]) ?: return null }
    }

    /**
     * The image's long straight edges that may be a page's sides: the peaks of a Hough transform in
     * which each edge pixel votes for the lines through it within a few degrees of its edge's way,
     * each then fitted to the edge pixels along it.
     */
    private fun straightEdges(edges: Edges): List<StraightEdge> {
        val w = edges.width
        val h = edges.height
        val offset = w
        val size = 2 * w + h + 2
        val votes = IntArray(ANGLES * size)
        for (i in edges.on.indices) {
            if (!edges.on[i]) continue
            val x = i % w + 0.5f
            val y = i / w + 0.5f
            val angle = Math.toDegrees(kotlin.math.atan2(edges.uy[i].toDouble(), edges.ux[i].toDouble())).roundToInt()
            for (spread in -SPREAD..SPREAD) {
                val a = ((angle + spread) % ANGLES + 2 * ANGLES) % ANGLES
                val rho = (x * COS[a] + y * SIN[a]).roundToInt() + offset
                votes[a * size + rho]++
            }
        }
        val least = max(MIN_VOTES, (MIN_LINE * min(w, h)).toInt())
        val peaks = ArrayList<Int>()
        for (a in 0 until ANGLES) for (r in 0 until size) {
            val v = votes[a * size + r]
            if (v < least) continue
            var peak = true
            for (da in -PEAK_WINDOW..PEAK_WINDOW) for (dr in -PEAK_WINDOW..PEAK_WINDOW) {
                val b = a + da
                val s = r + dr
                if (b < 0 || b >= ANGLES || s < 0 || s >= size) continue
                if (votes[b * size + s] > v) peak = false
            }
            if (peak) peaks += a * size + r
        }
        peaks.sortByDescending { votes[it] }
        val ways = List(ANGLES / WAY) { ArrayList<StraightEdge>() }
        for (peak in peaks) {
            val a = peak / size
            val oneWay = ways[a / WAY]
            if (oneWay.size == MAX_PEAKS_ONE_WAY) continue
            val line = StraightEdge(COS[a], SIN[a], (peak % size - offset).toFloat(), edges).fitted(edges)
            if (ways.any { way -> way.any { it.isNear(line) } }) continue
            oneWay += line
        }
        // Lines of print, rows of keys, a grid of wires or tiles: many long lines one way. Only the
        // most unbroken few of each way are kept, and the outermost two either side, as a page's
        // sides lie outside the lines of print on it however many and strong those are.
        val lines = ArrayList<StraightEdge>()
        for (oneWay in ways) {
            val across = oneWay.sortedBy { it.rho }
            val outermost = (across.take(2) + across.takeLast(2)).distinct()
            lines += outermost
            lines += oneWay.filter { it !in outermost }.sortedByDescending { it.longestRun }.take(MAX_ONE_WAY - outermost.size)
        }
        return lines
    }

    /**
     * A straight line x·[nx] + y·[ny] = [rho] across an image (pixel centres at .5), with where along
     * it the image's [edges] run along it. Points on it are at a distance t along ([dx], [dy]) from
     * its point nearest the image's top-left corner.
     */
    private class StraightEdge(val nx: Float, val ny: Float, val rho: Float, edges: Edges) {
        val dx = -ny
        val dy = nx
        private val start: Float
        private val onEdge: IntArray

        /** The longest stretch of the line on its edge: a page's side is long and unbroken, a row of keys or words isn't. */
        val longestRun: Int

        init {
            // Where the line crosses the image.
            var from = Float.NEGATIVE_INFINITY
            var to = Float.POSITIVE_INFINITY
            fun clip(origin: Float, step: Float, size: Int) {
                if (abs(step) < 1e-6f) {
                    if (origin < 0 || origin > size) to = from
                    return
                }
                val a = -origin / step
                val b = (size - origin) / step
                from = max(from, min(a, b))
                to = min(to, max(a, b))
            }
            clip(rho * nx, dx, edges.width)
            clip(rho * ny, dy, edges.height)
            start = if (from.isFinite()) from else 0f
            val count = if (from.isFinite() && to > from) (to - from).toInt() else 0
            onEdge = IntArray(count + 1)
            var run = 0
            var gap = 0
            var longest = 0
            for (k in 0 until count) {
                val p = point(start + k + 0.5f)
                val on = edges.along(p.x, p.y, nx, ny)
                onEdge[k + 1] = onEdge[k] + if (on) 1 else 0
                // Runs of edge with gaps of a pixel or two in them.
                if (on) {
                    run += gap + 1
                    gap = 0
                } else if (++gap > 2) {
                    run = 0
                    gap = 0
                }
                longest = max(longest, run)
            }
            longestRun = longest
        }

        /** The point [t] along the line. */
        fun point(t: Float) = OcrPoint(rho * nx + t * dx, rho * ny + t * dy)

        /** How far along the line the point ([x], [y]) lies. */
        fun along(x: Float, y: Float) = x * dx + y * dy

        /** How many whole pixels of the line from [from] to [to] lie on its edge. */
        fun supported(from: Float, to: Float): Int {
            val a = (from - start).toInt().coerceIn(0, onEdge.size - 1)
            val b = (to - start).toInt().coerceIn(0, onEdge.size - 1)
            return onEdge[b] - onEdge[a]
        }

        /** Where this line crosses [other], or null if they are (nearly) parallel. */
        fun meet(other: StraightEdge): OcrPoint? = Line(point(0f), dx, dy).meet(Line(other.point(0f), other.dx, other.dy))

        /** Whether [other] is (nearly) the same line: within 3° and 4 pixels across the image. */
        fun isNear(other: StraightEdge): Boolean {
            if (abs(dx * other.dy - dy * other.dx) > SIN_SAME) return false
            val same = if (dx * other.dx + dy * other.dy > 0) 1 else -1
            return abs(rho - same * other.rho) <= SAME_RHO
        }

        /** This line fitted to the edge pixels along it. */
        fun fitted(edges: Edges): StraightEdge {
            val points = ArrayList<OcrPoint>()
            for (k in 0 until onEdge.size - 1) {
                if (onEdge[k + 1] == onEdge[k]) continue
                val p = point(start + k + 0.5f)
                val i = edges.nearest(p.x, p.y, nx, ny)
                if (i >= 0) points += OcrPoint(i % edges.width + 0.5f, i / edges.width + 0.5f)
            }
            if (points.size < 6) return this
            val line = Line.fit(points)
            var fx = -line.dy
            var fy = line.dx
            if (fx * nx + fy * ny < 0) {
                fx = -fx
                fy = -fy
            }
            return StraightEdge(fx, fy, line.point.x * fx + line.point.y * fy, edges)
        }
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

    /**
     * The edges on a [width] × [height] image whose colours are [channels], by Canny's method: the
     * image smoothed a little (a 5 × 5 Gaussian), its gradient (Sobel's, in whichever colour changes
     * most), only the pixels where the gradient peaks across an edge, and of those the strong ones and
     * the weaker ones that continue them. How strong is strong follows the image: a few times its
     * median gradient, so the grain of a table doesn't count but the faint edge of a white page on a
     * smooth white desk does.
     */
    private fun edges(width: Int, height: Int, channels: List<IntArray>): Edges {
        val n = width * height
        val gx = FloatArray(n)
        val gy = FloatArray(n)
        val strength = FloatArray(n)
        for (channel in channels) {
            val smooth = blurred(channel, width, height)
            for (y in 0 until height) {
                val up = max(y - 1, 0) * width
                val row = y * width
                val down = min(y + 1, height - 1) * width
                for (x in 0 until width) {
                    val left = max(x - 1, 0)
                    val right = min(x + 1, width - 1)
                    val dx = smooth[up + right] + 2 * smooth[row + right] + smooth[down + right] -
                        smooth[up + left] - 2 * smooth[row + left] - smooth[down + left]
                    val dy = smooth[down + left] + 2 * smooth[down + x] + smooth[down + right] -
                        smooth[up + left] - 2 * smooth[up + x] - smooth[up + right]
                    val squared = dx * dx + dy * dy
                    if (squared > strength[row + x]) {
                        strength[row + x] = squared
                        gx[row + x] = dx
                        gy[row + x] = dy
                    }
                }
            }
        }
        for (i in 0 until n) strength[i] = sqrt(strength[i])

        // Only where the gradient peaks across the edge (looking along it, to the nearest 45°).
        val peaks = FloatArray(n)
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val i = y * width + x
            val m = strength[i]
            if (m == 0f) continue
            val ax = abs(gx[i])
            val ay = abs(gy[i])
            val step = when {
                ay <= ax * TAN_22_5 -> 1
                ax <= ay * TAN_22_5 -> width
                (gx[i] > 0) == (gy[i] > 0) -> width + 1
                else -> width - 1
            }
            if (m >= strength[i - step] && m > strength[i + step]) peaks[i] = m
        }

        val high = max(MIN_STRONG_EDGE, NOISE_TIMES * median(strength))
        val low = high * WEAK_EDGE
        val on = BooleanArray(n)
        val stack = IntArrayList()
        for (start in 0 until n) {
            if (on[start] || peaks[start] < high) continue
            on[start] = true
            stack.add(start)
            while (stack.size > 0) {
                val i = stack.removeLast()
                val x = i % width
                val y = i / width
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                    val j = ny * width + nx
                    if (!on[j] && peaks[j] >= low) {
                        on[j] = true
                        stack.add(j)
                    }
                }
            }
        }
        val ux = FloatArray(n)
        val uy = FloatArray(n)
        for (i in 0 until n) {
            if (!on[i]) continue
            ux[i] = gx[i] / strength[i]
            uy[i] = gy[i] / strength[i]
        }
        return Edges(width, height, on, ux, uy)
    }

    /**
     * Edges on an image: which pixels lie on one, and there the direction across it, from darker to
     * brighter (a unit vector [ux], [uy]).
     */
    private class Edges(val width: Int, val height: Int, val on: BooleanArray, val ux: FloatArray, val uy: FloatArray) {
        /**
         * Whether an edge running along a line with the unit normal ([nx], [ny]) passes within two
         * pixels, across the line, of the point ([x], [y]).
         */
        fun along(x: Float, y: Float, nx: Float, ny: Float): Boolean = nearest(x, y, nx, ny) >= 0

        /** The edge pixel nearest to ([x], [y]) that [along] finds, or -1. */
        fun nearest(x: Float, y: Float, nx: Float, ny: Float): Int {
            for (k in ACROSS) {
                val px = floor(x + k * nx).toInt()
                val py = floor(y + k * ny).toInt()
                if (px < 0 || py < 0 || px >= width || py >= height) continue
                val i = py * width + px
                if (on[i] && abs(ux[i] * nx + uy[i] * ny) >= COS_ALONG) return i
            }
            return -1
        }
    }

    /** [levels] smoothed by a 5 × 5 Gaussian (1 4 6 4 1 across and down), the border repeated beyond the image. */
    private fun blurred(levels: IntArray, w: Int, h: Int): FloatArray {
        val across = FloatArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                fun at(dx: Int) = levels[row + (x + dx).coerceIn(0, w - 1)]
                across[row + x] = (at(-2) + 4 * at(-1) + 6 * at(0) + 4 * at(1) + at(2)) / 16f
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            fun at(dy: Int) = across[(y + dy).coerceIn(0, h - 1) * w + x]
            out[y * w + x] = (at(-2) + 4 * at(-1) + 6 * at(0) + 4 * at(1) + at(2)) / 16f
        }
        return out
    }

    /** The median of [values], none of them negative, rounded down to a whole number. */
    private fun median(values: FloatArray): Float {
        val histogram = IntArray(2048)
        for (v in values) histogram[min(v.toInt(), histogram.size - 1)]++
        var count = 0
        for (i in histogram.indices) {
            count += histogram[i]
            if (2 * count >= values.size) return i.toFloat()
        }
        return 0f
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

    /** [quad]'s corners in pixels of a [w] × [h] image: x and y. */
    private fun pixels(quad: CropQuad, w: Int, h: Int): Pair<DoubleArray, DoubleArray> =
        DoubleArray(4) { quad[it].x.toDouble() * w } to DoubleArray(4) { quad[it].y.toDouble() * h }

    /**
     * How much a page with corners at [xs], [ys] on a [w] × [h] image looks like one by its shape: 0
     * when it is too small to be what was photographed or a corner is squashed flat, 1 when it is
     * large enough with corners near right angles.
     */
    private fun shape(xs: DoubleArray, ys: DoubleArray, w: Int, h: Int): Double {
        val size = ramp(area(xs, ys) / (w.toDouble() * h), MIN_AREA, GOOD_AREA)
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
        return size * angles
    }

    /** The area of the four-cornered shape with corners at [xs], [ys]. */
    private fun area(xs: DoubleArray, ys: DoubleArray): Double {
        var twice = 0.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            twice += xs[i] * ys[j] - xs[j] * ys[i]
        }
        return abs(twice) / 2
    }

    /**
     * How sure an outline found by brightness is, from 0 to 1. A page is large enough on the photo,
     * fills its outline (a four-cornered shape, unlike a round plate or a hand), is clearly brighter
     * than what is around it, doesn't run off the photo (a corner out of the frame is a guess) and
     * its corners aren't squashed flat.
     */
    private fun brightnessConfidence(quad: CropQuad, image: Grey, page: BooleanArray, outside: BooleanArray, filledArea: Int): Float {
        val w = image.width
        val h = image.height
        val (xs, ys) = pixels(quad, w, h)
        val quadArea = area(xs, ys)
        if (quadArea <= 0) return 0f
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
        return (shape(xs, ys, w, h) * fill * contrast * inFrame).toFloat()
    }

    /**
     * How sure an outline found by [edges] is, from 0 to 1, without asking the page to be brighter
     * than what is around it. Its sides run along edges that run the same way (all of each side but
     * its ends and a hand over part of it); its corners are where those edges stop (where lines go on
     * past the corners, the outline is a tile, a square of a grid or a stripe between lines of
     * print); its sides aren't lines drawn or printed on paper (a box on a form photographed close up,
     * by the [levels] either side of them); and as for brightness it is large enough, doesn't run
     * along the photo's border and its corners aren't squashed flat.
     */
    private fun edgeConfidence(quad: CropQuad, edges: Edges, levels: IntArray): Float {
        val w = edges.width
        val h = edges.height
        val (xs, ys) = pixels(quad, w, h)
        if (area(xs, ys) <= 0) return 0f

        // Which way is into the page from each side: to its right when the corners go clockwise.
        val turn = if ((xs[1] - xs[0]) * (ys[2] - ys[1]) - (ys[1] - ys[0]) * (xs[2] - xs[1]) > 0) 1f else -1f
        fun level(x: Float, y: Float) = levels[y.toInt().coerceIn(0, h - 1) * w + x.toInt().coerceIn(0, w - 1)]
        var weakest = 1.0
        var hitsAll = 0
        var samples = 0
        var onBorder = 0
        var drawn = 0
        val pastStart = DoubleArray(4)
        val pastEnd = DoubleArray(4)
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val length = hypot(xs[j] - xs[i], ys[j] - ys[i])
            if (length < 1) return 0f
            val dx = ((xs[j] - xs[i]) / length).toFloat()
            val dy = ((ys[j] - ys[i]) / length).toFloat()
            val inX = -dy * turn
            val inY = dx * turn
            fun at(t: Double) = OcrPoint((xs[i] + dx * t).toFloat(), (ys[i] + dy * t).toFloat())
            var hits = 0
            var count = 0
            var gap = 0
            var longestGap = 0
            var t = END_SHARE * length
            while (t <= (1 - END_SHARE) * length) {
                val p = at(t)
                if (edges.along(p.x, p.y, -dy, dx)) {
                    hits++
                    gap = 0
                } else {
                    gap++
                    longestGap = max(longestGap, gap)
                }
                if (p.x < BORDER || p.y < BORDER || p.x > w - BORDER || p.y > h - BORDER) onBorder++
                // A line drawn or printed on paper is darker than both sides of it, which are alike;
                // a page's edge steps from one shade to another, or on a white desk is a faint shadow.
                var line = 255
                for (k in -2..2) line = min(line, level(p.x + k * inX, p.y + k * inY))
                val outer = level(p.x - DRAWN_SIDE * inX, p.y - DRAWN_SIDE * inY)
                val inner = level(p.x + DRAWN_SIDE * inX, p.y + DRAWN_SIDE * inY)
                val dip = min(outer, inner) - line
                if (dip >= DRAWN_DIP && 2 * abs(outer - inner) < dip) drawn++
                count++
                t += 1.0
            }
            samples += count
            hitsAll += hits
            // A hand or a cup over the page hides one stretch of a side; edges missing all along
            // it (a line of print, a curve's chord) are another matter.
            if (count > 0) weakest = min(weakest, (hits + longestGap).toDouble() / count)
            // Whether this side's edge goes on past either end.
            val reach = (length * 0.1).coerceIn(5.0, 15.0)
            fun beyond(from: Double, step: Double): Double {
                // From a corner on the photo's border, it may well go on off the photo.
                val corner = at(from)
                if (corner.x < BORDER || corner.y < BORDER || corner.x > w - BORDER || corner.y > h - BORDER) return 1.0
                var found = 0
                var tried = 0
                var s = from + step * 2
                while (abs(s - from) <= reach) {
                    val p = at(s)
                    if (p.x >= 0 && p.y >= 0 && p.x < w && p.y < h) {
                        tried++
                        if (edges.along(p.x, p.y, -dy, dx)) found++
                    }
                    s += step
                }
                return if (tried == 0) 0.0 else found.toDouble() / tried
            }
            pastStart[i] = beyond(0.0, -1.0)
            pastEnd[i] = beyond(length, 1.0)
        }
        // A page's sides end at its corners. Where both sides go on past a corner, it is a crossing
        // of lines (tiles, a grid; one may be chance), where a side goes on past both its corners it
        // is a longer line (a table's edge), and where the sides either side of one go on past it, it
        // is a line across something larger (a fold, a line of print, the half of a block of tiles).
        val crossed = DoubleArray(4) { min(pastEnd[(it + 3) % 4], pastStart[it]) }.sorted()[2]
        val open = (0 until 4).maxOf { max(min(pastStart[it], pastEnd[it]), min(pastEnd[(it + 3) % 4], pastStart[(it + 1) % 4])) }
        // Most of the outline on edges, and each side on them for much of its length: a hand may
        // cover part of a side.
        val share = hitsAll.toDouble() / max(samples, 1)
        val support = ramp(share, 0.6, 0.85) * ramp(weakest, 0.75, 0.9)
        val inFrame = 1 - ramp(onBorder.toDouble() / max(samples, 1), 0.03, 0.2)
        val corners = (1 - ramp(crossed, 0.4, 0.7)) * (1 - ramp(open, 0.3, 0.6))
        val page = 1 - ramp(drawn.toDouble() / max(samples, 1), 0.3, 0.6)
        return (shape(xs, ys, w, h) * support * inFrame * corners * page).toFloat()
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

    /** tan 22.5°: a gradient within 22.5° of across or down counts as across or down. */
    private const val TAN_22_5 = 0.41421357f
    /** A strong edge is this many times the image's median gradient, */
    private const val NOISE_TIMES = 4f
    /** and never weaker than this (Sobel's gradient, 0..1443), below which JPEG's own noise lies. */
    private const val MIN_STRONG_EDGE = 12f
    /** A weak edge, kept where it continues a strong one, is at least this share of a strong one. */
    private const val WEAK_EDGE = 0.4f
    /** An edge runs along a line when the brightness changes across the line, within about 25°. */
    private const val COS_ALONG = 0.9f
    /** The share of a side's length at either end where a corner may be rounded or folded. */
    private const val END_SHARE = 0.08
    /** A side's point this close to the photo's border (in its small copy's pixels) lies along it. */
    private const val BORDER = 2f
    /** How far across a line (in pixels, nearest first) an edge pixel may be and still lie on it. */
    private val ACROSS = intArrayOf(0, -1, 1, -2, 2)

    /** The Hough transform's lines are a degree apart, */
    private const val ANGLES = 180
    private val COS = FloatArray(ANGLES) { kotlin.math.cos(Math.toRadians(it.toDouble())).toFloat() }
    private val SIN = FloatArray(ANGLES) { kotlin.math.sin(Math.toRadians(it.toDouble())).toFloat() }
    /** and an edge pixel votes for those within this many degrees of its edge's way. */
    private const val SPREAD = 4
    /** A peak of votes is the most within this many degrees and pixels of it. */
    private const val PEAK_WINDOW = 3
    /** A straight edge is at least this long, and this share of the image's short side. */
    private const val MIN_VOTES = 12
    private const val MIN_LINE = 0.08
    /** Of the straight edges within each [WAY] degrees, so many of the longest are looked at, */
    private const val MAX_PEAKS_ONE_WAY = 40
    /** and so many tried as sides. */
    private const val MAX_ONE_WAY = 16
    private const val WAY = 15
    /** Two lines within sin⁻¹ of this (3°) and this many pixels of each other are one. */
    private const val SIN_SAME = 0.05f
    private const val SAME_RHO = 4f
    /** Opposite sides of a page differ in direction by at most sin⁻¹ of this (35°, as photographed at a slant), */
    private const val SIN_OPPOSITE = 0.57f
    /** sides meeting at a corner by at least sin⁻¹ of this (40°), */
    private const val SIN_CORNER = 0.64f
    /** and opposite sides are at least this share of the image's short side apart. */
    private const val MIN_GAP = 0.15
    /** At most so many pairs of opposite sides are tried (a busy photo has a few hundred). */
    private const val MAX_PAIRS = 1000
    /** A page's corner may be this many pixels off the image. */
    private const val OFF_IMAGE = 3f
    /** So many of the best-scored outlines are fitted and weighed. */
    private const val CANDIDATES = 8
    /** A line goes on past a corner when this share of it lies on its edge there. */
    private const val ON_PAST = 0.7f
    /** Outlines this much less sure than the surest are as good as it. */
    private const val NEARLY_AS_SURE = 0.1f
    /** A line this much darker than the paper [DRAWN_SIDE] pixels either side of it is drawn on it. */
    private const val DRAWN_DIP = 35
    private const val DRAWN_SIDE = 4f

    /**
     * A found side moves in at most this many pixels (1% of the small copy's long side) to leave the
     * desk out: a page's margins are wider.
     */
    private const val MAX_INSET = 4.0
    /** The paper's shade by a side is taken from as far in as a side may move, past the blur of its edge, */
    private const val PAPER_FROM = MAX_INSET
    private const val PAPER_TO = 6.0
    /** and the step at its edge from this many pixels out. */
    private const val OUTSIDE = 2.0
    /** How far in the paper starts is looked for in steps this many pixels apart, */
    private const val INSET_STEP = 0.25
    /** along each of this many stretches of a side, each at least this many pixels long. */
    private const val STRETCHES = 4
    private const val MIN_STRETCH = 6
    /** The paper starts where its shade is within its grain and this share of the step at the edge, */
    private const val INSET_SHARE = 0.1
    /** or within this many levels, too few to see. */
    private const val INSET_LEVELS = 4.0
}
