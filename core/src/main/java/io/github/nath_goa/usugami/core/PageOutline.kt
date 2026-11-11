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
 * don't pull the corners in. A bright region that isn't surely a page is split again, by how white
 * it is: against a dark floor, a light wooden desk is as bright as the page lying on it.
 *
 * By its edges, as document scanners do: the photo's edges (Canny's), the long straight ones among
 * them (a Hough transform), and the four of those that best make a page's outline, its sides lying
 * on edges from corner to corner and ending at the corners. This asks nothing of the page's
 * brightness, so it also finds a white page on a white desk by the faint shadow along its edges, a
 * page on a brighter table, and in colour a page that differs from the desk only in its colour.
 *
 * A page photographed close may run off the photo on one side, or end at its border: a book's page
 * as wide as the photo. Either search then finds the paper as far as it shows, with the photo's
 * border for that side of its outline. Less speaks for such an outline, so more is asked of it: the
 * paper ends at its three other sides, with no paper and no print beyond them, and it shows print
 * across a good part of it, as a page seen that close does and a wall or a desk doesn't. With fewer
 * than three of the page's edges in the photo, there is too little to tell it by.
 *
 * An outline that lies on a page isn't the page's: a table, a box, a tinted panel, a printed
 * photograph. Its sides are rules drawn on the paper with the same paper beyond them, or the printed
 * paper goes on beyond them. Rules and print show on a finer copy of the photo than the search runs
 * on.
 *
 * Each outline gets a confidence of its own kind. A page all in the photo, surely found, comes before
 * one that runs off it; of those, the surer one is the page, the one by edges when they are as sure
 * (its sides are fitted to the edges themselves). Its sides then move in to just inside the paper,
 * past the blur of its edge and the shadow it casts, so no strip of the desk shows along the
 * straightened page.
 */
object PageDetection {
    /** The search runs on a copy at most this many pixels on its long side. */
    const val WORK_SIDE = 400

    /** Print and thin rules are looked at on a copy at most this many pixels on its long side. */
    const val FINE_SIDE = 800

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
     * of its colours (or the brightness alone). [fine] is its brightness on a larger copy, where the
     * photo has the pixels for one: print and thin rules, a blur on the small copy, show on it.
     */
    internal class Grey(val width: Int, val height: Int, val levels: IntArray, val channels: List<IntArray> = listOf(levels), fine: Fine? = null) {
        val fine: Fine = fine ?: Fine(width, height, levels, 1.0)

        /** How white each pixel is: the level of its dullest colour, so a coloured desk as bright as the paper is darker than it. */
        val white: IntArray by lazy { if (channels.size == 1) levels else IntArray(levels.size) { i -> channels.minOf { it[i] } } }

        /**
         * Whether each square of the photo, [SQUARE] pixels a side and half a square on from the one
         * before it, counted from the photo's top-left corner, holds print, as far as [printWithin]
         * has looked: [UNSEEN], [OFF_PHOTO] (too little of it on the photo), [BARE] or [PRINTED].
         */
        val squares = ByteArray(ceil(width / (SQUARE / 2)).toInt() * ceil(height / (SQUARE / 2)).toInt())
    }

    /** A photo's brightness on a copy [scale] times as large each way as the small one. */
    internal class Fine(val width: Int, val height: Int, val levels: IntArray, val scale: Double) {
        /** The level at ([x], [y]) in the small copy's pixels, or -1 off the photo. */
        fun at(x: Double, y: Double): Int {
            val fx = floor(x * scale).toInt()
            val fy = floor(y * scale).toInt()
            return if (fx < 0 || fy < 0 || fx >= width || fy >= height) -1 else levels[fy * width + fx]
        }
    }

    /** [luma] averaged down to at most [WORK_SIDE] pixels on its long side (which also evens out noise). */
    internal fun shrink(luma: ByteArray, width: Int, height: Int): Grey {
        val factor = factor(width, height, WORK_SIDE)
        val levels = averaged(width, height, factor) { luma[it].toInt() and 0xFF }
        val fine = fine(width, height, factor) { luma[it].toInt() and 0xFF }
        return Grey((width + factor - 1) / factor, (height + factor - 1) / factor, levels, fine = fine)
    }

    /** [argb]'s colours averaged down as [shrink] does, with their brightness. */
    private fun shrink(argb: IntArray, width: Int, height: Int): Grey {
        val factor = factor(width, height, WORK_SIDE)
        val red = averaged(width, height, factor) { (argb[it] shr 16) and 0xFF }
        val green = averaged(width, height, factor) { (argb[it] shr 8) and 0xFF }
        val blue = averaged(width, height, factor) { argb[it] and 0xFF }
        val luma = IntArray(red.size) { (red[it] * 77 + green[it] * 150 + blue[it] * 29) shr 8 }
        val fine = fine(width, height, factor) { (((argb[it] shr 16) and 0xFF) * 77 + ((argb[it] shr 8) and 0xFF) * 150 + (argb[it] and 0xFF) * 29) shr 8 }
        return Grey((width + factor - 1) / factor, (height + factor - 1) / factor, luma, listOf(red, green, blue), fine)
    }

    /** How many pixels across and down make one of a copy at most [side] pixels on its long side. */
    private fun factor(width: Int, height: Int, side: Int) = max(1, ceil(max(width, height) / side.toDouble()).toInt())

    /**
     * The finer copy of a [width] × [height] photo whose small copy takes [factor] pixels to one: its
     * brightness ([level] of each pixel's index) averaged down to at most [FINE_SIDE] pixels on its
     * long side. Null when that is no finer than the small copy.
     */
    private inline fun fine(width: Int, height: Int, factor: Int, level: (Int) -> Int): Fine? {
        val fineFactor = factor(width, height, FINE_SIDE)
        if (fineFactor >= factor) return null
        val levels = if (fineFactor == 1) IntArray(width * height) { level(it) } else averaged(width, height, fineFactor, level)
        return Fine((width + fineFactor - 1) / fineFactor, (height + fineFactor - 1) / fineFactor, levels, factor / fineFactor.toDouble())
    }

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
     * The page among the outlines found by edges and by brightness on [image], its sides then moved
     * in to just inside the paper ([inside]): of those all in the photo and surely a page, the surest
     * (the one by edges if as sure); failing those, the surest of all, those that run off the photo
     * among them.
     */
    private fun find(image: Grey): PageOutline? {
        if (image.width < 8 || image.height < 8) return null
        val edges = edges(image.width, image.height, image.channels)
        val byLines = byLines(image, edges)
        // With a page surely found by its edges, the bright region is enough to check it against.
        val byRegions = byRegions(image, edges, if (byLines.page != null && byLines.page.confidence >= CONFIDENT) 1 else SPLITS)
        val found = listOfNotNull(byLines.page, byLines.clipped) + byRegions
        // A page all in the photo, surely found, before one that runs off it.
        val whole = found.filter { it.borders == 0 && it.confidence >= CONFIDENT }.maxByOrNull { it.confidence }
        // What runs off the photo round something like a page, however small, is the desk it lies on.
        val pages = (byLines.alike + byRegions).filter { it.borders == 0 && it.like >= CONFIDENT }
        val page = whole ?: found.filter { it.borders == 0 || pages.none { page -> holds(it.quad, page.quad, image.width, image.height) } }.maxByOrNull { it.confidence } ?: return null
        return PageOutline(inside(page.quad, image), page.confidence)
    }

    /** The outlines found by edges: the [page] all in the photo, the one [clipped] by the photo's border, and whatever all in the photo is [alike] a page. */
    private class Lines(val page: Found?, val clipped: Found?, val alike: List<Found>)

    /** Whether [inner] lies within [outer] on a [w] × [h] image (to within [HELD] pixels) and is clearly smaller. */
    private fun holds(outer: CropQuad, inner: CropQuad, w: Int, h: Int): Boolean {
        val (xs, ys) = pixels(outer, w, h)
        val (ix, iy) = pixels(inner, w, h)
        if (area(ix, iy) > SMALLER * area(xs, ys)) return false
        val turn = if ((xs[1] - xs[0]) * (ys[2] - ys[1]) - (ys[1] - ys[0]) * (xs[2] - xs[1]) > 0) 1.0 else -1.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val length = hypot(xs[j] - xs[i], ys[j] - ys[i])
            if (length < 1) return false
            // How far each of the inner corners is inside this side of the outer outline.
            for (k in 0 until 4) {
                val inside = turn * ((xs[j] - xs[i]) * (iy[k] - ys[i]) - (ys[j] - ys[i]) * (ix[k] - xs[i])) / length
                if (inside < -HELD) return false
            }
        }
        return true
    }

    /**
     * An outline found, how sure it is, how many of its sides are the photo's border (the page runs
     * off it there), and how much it is [like] a page but for its size: a page too small on the photo
     * to be sure of is like one all the same.
     */
    private class Found(val quad: CropQuad, confidence: Float, val borders: Int = 0, like: Float = confidence) {
        val confidence = if (confidence.isNaN()) 0f else confidence
        val like = if (like.isNaN()) 0f else like
    }

    /**
     * [quad] with each side moved in to where the paper starts. Either search puts a side along the
     * middle of the page's edge: the step from the desk to the paper, blurred over a pixel or two, and
     * on a light desk the shadow line a page casts; and a side may lie a pixel or two askew of the
     * edge. Left there, a thin strip of desk shows along the straightened page. How far in the paper
     * starts along each side is measured on [image] ([insets]), and each end of a side moves in at
     * most [MAX_INSET] pixels, so the page's own margins stay. The corners stay on the image, and a
     * side that is the image's border stays on it.
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
            // A side that is the photo's border stays on it: the paper runs off the photo there.
            val (first, last) = if (border(xs[i], ys[i], xs[j], ys[j], w, h) >= 0) 0.0 to 0.0 else insets(image, xs[i], ys[i], dx, dy, inX, inY, length)
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

    /** The page as the surest of the bright regions on [image] ([byRegions]), or null if there is none large enough. */
    internal fun byBrightness(image: Grey): PageOutline? =
        byRegions(image, edges(image.width, image.height, image.channels), SPLITS).maxByOrNull { it.confidence }?.let { PageOutline(it.quad, it.confidence) }

    /**
     * The page as a bright region on [image]: the largest one, and where that isn't surely a page all
     * in the photo, the brightest part of it, and of that. The bright region may hold more than the
     * paper: against a dark floor, a light wooden desk is as bright as the page lying on it. So it is
     * split again, by how white each pixel is (as bright as its dullest colour, which sets paper off
     * from wood), into the print and what it is on, and that into the desk and the paper: [splits]
     * times in all.
     */
    private fun byRegions(image: Grey, edges: Edges, splits: Int): List<Found> {
        val white = image.white
        val found = ArrayList<Found>()
        var within: BooleanArray? = null
        for (split in 0 until splits) {
            // On a log scale a shadow darkens the paper and the table by the same step, so the first
            // split, between them, stays where it is instead of cutting off the paper in shadow. The
            // later ones are between shades too close for that scale.
            val region = if (split == 0) brightRegion(image, edges, IntArray(white.size) { LOG_LEVELS[image.levels[it]] }, image.levels, null, checked = false)
            else brightRegion(image, edges, white, white, within, checked = true)
            if (region == null) {
                // Nothing stands out by its brightness: by its whiteness, on a photo in colour.
                if (split == 0 && white !== image.levels) continue else break
            }
            found += region.found
            if (region.found.borders == 0 && region.found.confidence >= CONFIDENT) break
            within = region.page
        }
        return found
    }

    /** A bright region's outline, and the region's pixels. */
    private class Region(val found: Found, val page: BooleanArray)

    /**
     * The largest region of [image] brighter than the rest by [shades] (0..255, split by Otsu's
     * threshold), among the pixels [within] (or all), as a page: its convex hull, the four-cornered
     * shape that covers most of the hull, and each side fitted to the region's edge. [levels] are the
     * shades as the eye sees them, for the contrast. Null if there is no region large enough. With
     * [checked], print beyond its sides counts against it ([printBeyond]), as it always does against
     * an outline that runs off the photo.
     */
    private fun brightRegion(image: Grey, edges: Edges, shades: IntArray, levels: IntArray, within: BooleanArray?, checked: Boolean): Region? {
        val w = image.width
        val h = image.height
        val threshold = otsu(shades, within) ?: return null
        val bright = BooleanArray(shades.size) { (within == null || within[it]) && shades[it] > threshold }
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

        // A side along the photo's border is the border itself: the paper runs off the photo there.
        val quad = CropQuad(onBorder(corners, w, h, TOUCHING).map { CropPoint((it.x / w).coerceIn(0f, 1f), (it.y / h).coerceIn(0f, 1f)) }).ordered()
        return Region(regionConfidence(quad, image, edges, levels, page, outside, filledArea, checked), page)
    }

    /**
     * [corners] in order round a shape on a [w] × [h] image, those of each side that lies along the
     * image's border (within [near] pixels of it at both ends) moved onto it.
     */
    private fun onBorder(corners: List<OcrPoint>, w: Int, h: Int, near: Double = ON_BORDER): List<OcrPoint> {
        val xs = FloatArray(4) { corners[it].x }
        val ys = FloatArray(4) { corners[it].y }
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            when (border(xs[i].toDouble(), ys[i].toDouble(), xs[j].toDouble(), ys[j].toDouble(), w, h, near)) {
                TOP -> { ys[i] = 0f; ys[j] = 0f }
                RIGHT -> { xs[i] = w.toFloat(); xs[j] = w.toFloat() }
                BOTTOM -> { ys[i] = h.toFloat(); ys[j] = h.toFloat() }
                LEFT -> { xs[i] = 0f; xs[j] = 0f }
            }
        }
        return List(4) { OcrPoint(xs[it], ys[it]) }
    }

    /**
     * Which border of a [w] × [h] image the side from ([ax], [ay]) to ([bx], [by]) lies along, within
     * [near] pixels of it at both ends ([TOP], [RIGHT], [BOTTOM], [LEFT]), or -1.
     */
    private fun border(ax: Double, ay: Double, bx: Double, by: Double, w: Int, h: Int, near: Double = ON_BORDER): Int = when {
        ay <= near && by <= near -> TOP
        ax >= w - near && bx >= w - near -> RIGHT
        ay >= h - near && by >= h - near -> BOTTOM
        ax <= near && bx <= near -> LEFT
        else -> -1
    }

    /**
     * The page as four straight edges that make a page's outline on [image]. Every four of the
     * image's long straight edges ([straightEdges]) that make a four-cornered shape are scored by how
     * much of its sides lies on their edges, less what doesn't; the best few are fitted to their edges
     * and weighed ([edgeConfidence]), and of those about as sure as the surest, the best scored is the
     * page ([surest]).
     *
     * Unless that is surely a page, each of the photo's own borders is tried as a side as well, for
     * a page that runs off the photo there, and the outlines with a side on the border give a page
     * of their own: among them those of four edges, one of which turned out to lie along the border.
     */
    private fun byLines(image: Grey, edges: Edges): Lines {
        val w = image.width
        val h = image.height
        // The photo's own borders may stand in for sides: where the page runs off the photo.
        val lines = straightEdges(edges) + listOf(
            StraightEdge(0f, 1f, 0f, edges, TOP),
            StraightEdge(1f, 0f, w.toFloat(), edges, RIGHT),
            StraightEdge(0f, 1f, h.toFloat(), edges, BOTTOM),
            StraightEdge(1f, 0f, 0f, edges, LEFT),
        )
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
        val withBorder = ArrayList<IntArray>()
        for (i in 0 until n) for (j in i + 1 until n) {
            val a = lines[i]
            val b = lines[j]
            if (abs(a.dx * b.dy - a.dy * b.dx) > SIN_OPPOSITE) continue
            // How far apart they are across the image's middle.
            val middle = a.point(a.along(w / 2f, h / 2f))
            if (abs(b.nx * middle.x + b.ny * middle.y - b.rho) >= minGap) (if (a.border < 0 && b.border < 0) pairs else withBorder) += intArrayOf(i, j)
        }
        // Straight edges every way (a pile of sticks, a wire basket) make many pairs, and the shapes
        // tried grow as the square of their number: then only the pairs whose edges run longest
        // unbroken, as a page's sides do, are tried.
        val tried = if (pairs.size <= MAX_PAIRS) pairs else {
            pairs.indices.sortedByDescending { lines[pairs[it][0]].longestRun + lines[pairs[it][1]].longestRun }
                .take(MAX_PAIRS).sorted().map { pairs[it] }
        }
        // The best-scored outlines all on the photo, and those with the photo's border for a side:
        // kept apart, as the more sides lie on edges, the higher an outline scores.
        val best = List(2) { ArrayList<Pair<Double, IntArray>>() }
        val corners = arrayOfNulls<OcrPoint>(4)
        fun tryPairs(first: List<IntArray>, second: List<IntArray>, same: Boolean) {
            for (p in first.indices) for (q in (if (same) p + 1 else 0) until second.size) {
                val (i, j) = first[p]
                val (k, l) = second[q]
                // Clockwise or not, the sides in order round the shape: i, k, j, l.
                corners[0] = meets[l * n + i] ?: continue
                corners[1] = meets[i * n + k] ?: continue
                corners[2] = meets[k * n + j] ?: continue
                corners[3] = meets[j * n + l] ?: continue
                val sides = intArrayOf(i, k, j, l)
                // With fewer than three of the page's edges, there is too little to go by ([CLIPPED]).
                val borders = sides.count { lines[it].border >= 0 }
                if (borders >= 2) continue
                val score = score(sides, lines, corners, w, h) ?: continue
                val kept = best[borders]
                if (kept.size < MORE_CANDIDATES || score > kept.last().first) {
                    kept += score to sides
                    kept.sortByDescending { it.first }
                    if (kept.size > MORE_CANDIDATES) kept.removeAt(kept.size - 1)
                }
            }
        }
        tryPairs(tried, tried, same = true)
        val whole = weighed(best[0], lines, edges, image) { it.borders == 0 }
        val page = surest(whole.filter { it.borders == 0 })
        val alike = whole.filter { it.borders == 0 && it.like >= CONFIDENT }
        if (page != null && page.confidence >= CONFIDENT) return Lines(page, null, alike)
        // Of the pairs with the photo's border for one side, likewise, where there are many.
        val bordered = if (withBorder.size <= MAX_BORDER_PAIRS) withBorder else {
            withBorder.indices.sortedByDescending { max(lines[withBorder[it][0]].longestRun, lines[withBorder[it][1]].longestRun) }
                .take(MAX_BORDER_PAIRS).sorted().map { withBorder[it] }
        }
        tryPairs(tried, bordered, same = false)
        val clipped = whole.filter { it.borders > 0 } + weighed(best[1], lines, edges, image) { it.borders > 0 }
        return Lines(page, surest(clipped), alike)
    }

    /**
     * The outlines [best] (each a score and its four [lines], best scored first) fitted to their
     * edges and weighed ([edgeConfidence]): the best few, and when none of them is surely a page of
     * the kind [wanted] (the rows of a table on the page outscore the page itself), the next few as
     * well.
     */
    private fun weighed(best: List<Pair<Double, IntArray>>, lines: List<StraightEdge>, edges: Edges, image: Grey, wanted: (Found) -> Boolean): List<Found> {
        val w = image.width
        val h = image.height
        val outlines = ArrayList<Found>()
        for (few in best.chunked(CANDIDATES)) {
            for ((_, sides) in few) {
                val corners = fitted(sides.map { lines[it] }, edges) ?: continue
                val quad = CropQuad(onBorder(corners, w, h).map { CropPoint((it.x / w).coerceIn(0f, 1f), (it.y / h).coerceIn(0f, 1f)) }).ordered()
                outlines += edgeConfidence(quad, edges, image)
            }
            if (outlines.any { wanted(it) && it.confidence >= CONFIDENT }) break
        }
        return outlines
    }

    /** The page among [outlines], best scored first: of those about as sure as the surest, the best scored, as its sides have the most edge. */
    private fun surest(outlines: List<Found>): Found? {
        val surest = outlines.maxOfOrNull { it.confidence } ?: return null
        return outlines.firstOrNull { it.confidence >= surest - NEARLY_AS_SURE }
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
            // The photo's border has no edge to lie on, and loses nothing by it.
            score += if (line.border >= 0) max(0.0, 2.0 * supported - length) else 2.0 * supported - length
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
     * its point nearest the image's top-left corner. [border] is the photo's border it is ([TOP],
     * [RIGHT], [BOTTOM], [LEFT]), or -1 for an edge on the photo.
     */
    private class StraightEdge(val nx: Float, val ny: Float, val rho: Float, edges: Edges, val border: Int = -1) {
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
     * Otsu's threshold: the level that best splits the image, or its pixels [within], into a dark
     * and a bright part. Null for an image that is (nearly) one flat shade.
     */
    internal fun otsu(levels: IntArray, within: BooleanArray? = null): Int? {
        val histogram = IntArray(256)
        var count = 0
        for (i in levels.indices) if (within == null || within[i]) {
            histogram[levels[i]]++
            count++
        }
        if (count == 0) return null
        val total = count.toDouble()
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

        /**
         * Whether an edge running along a line with the unit normal ([nx], [ny]) passes within [NEAR]
         * pixels, across the line, of the point ([x], [y]): the edge a side was fitted to may bow away
         * from it, as a book's page does.
         */
        fun near(x: Float, y: Float, nx: Float, ny: Float): Boolean {
            for (k in -NEAR..NEAR) {
                val px = floor(x + k * nx).toInt()
                val py = floor(y + k * ny).toInt()
                if (px < 0 || py < 0 || px >= width || py >= height) continue
                val i = py * width + px
                if (on[i] && abs(ux[i] * nx + uy[i] * ny) >= COS_NEAR) return true
            }
            return false
        }

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
        // Whether all (to erode) or any (to dilate) of a pixel's 3 × 3 are set, the border repeated
        // beyond the image: first across each row, then down each column.
        fun pass(source: BooleanArray, all: Boolean): BooleanArray {
            val across = BooleanArray(source.size)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    val a = source[row + max(x - 1, 0)]
                    val b = source[row + x]
                    val c = source[row + min(x + 1, w - 1)]
                    across[row + x] = if (all) a && b && c else a || b || c
                }
            }
            val out = BooleanArray(source.size)
            for (y in 0 until h) {
                val up = max(y - 1, 0) * w
                val row = y * w
                val down = min(y + 1, h - 1) * w
                for (x in 0 until w) {
                    val a = across[up + x]
                    val b = across[row + x]
                    val c = across[down + x]
                    out[row + x] = if (all) a && b && c else a || b || c
                }
            }
            return out
        }
        return pass(pass(mask, all = true), all = false)
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
     * when it is too small to be what was photographed, a corner is squashed flat or a side has
     * (nearly) no length, 1 when it is large enough with corners near right angles.
     */
    private fun shape(xs: DoubleArray, ys: DoubleArray, w: Int, h: Int): Double = size(xs, ys, w, h) * squareness(xs, ys)

    /** How large the shape with corners at [xs], [ys] is on a [w] × [h] image, from 0 (too small to be what was photographed) to 1. */
    private fun size(xs: DoubleArray, ys: DoubleArray, w: Int, h: Int): Double = ramp(area(xs, ys) / (w.toDouble() * h), MIN_AREA, GOOD_AREA)

    /** How much the shape with corners at [xs], [ys] has a page's corners, from 0 (one squashed flat, or a side with no length) to 1 (all near right angles). */
    private fun squareness(xs: DoubleArray, ys: DoubleArray): Double {
        var angles = 1.0
        for (i in 0 until 4) {
            val prev = (i + 3) % 4
            val next = (i + 1) % 4
            val ax = xs[prev] - xs[i]
            val ay = ys[prev] - ys[i]
            val bx = xs[next] - xs[i]
            val by = ys[next] - ys[i]
            // Two corners at one point: three corners don't make a page.
            if (hypot(ax, ay) < MIN_SIDE || hypot(bx, by) < MIN_SIDE) return 0.0
            val cos = (ax * bx + ay * by) / (sqrt(ax * ax + ay * ay) * sqrt(bx * bx + by * by))
            val degrees = Math.toDegrees(acos(cos.coerceIn(-1.0, 1.0)))
            angles = min(angles, ramp(degrees, 35.0, 50.0) * (1 - ramp(degrees, 130.0, 145.0)))
        }
        return angles
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
     * How sure an outline found as a bright region is, from 0 to 1, with how many of its sides are
     * the photo's border. A page is large enough on the photo, fills its outline (a four-cornered
     * shape, unlike a round plate or a hand), is clearly brighter than what is around it (by its
     * [levels]) and its corners aren't squashed flat.
     *
     * It doesn't run off the photo at a slant (a corner out of the frame is a guess). But paper that
     * runs off the photo all along one side, or ends at the photo's border, is the page as far as it
     * shows, that side the border itself: a book's page photographed close. Such an outline has less
     * to go by, so more is asked of it ([CLIPPED]): its three other sides are sharp edges on the
     * photo (not a shadow's soft one), the paper ends at them ([paperBeyond]), no print goes on
     * beyond them (a shadow's hard edge across the page), it covers a good part of the photo and
     * shows print across it ([printWithin]). With [checked], sharp edges are asked of any outline,
     * and print beyond a side counts against it.
     */
    private fun regionConfidence(quad: CropQuad, image: Grey, edges: Edges, levels: IntArray, page: BooleanArray, outside: BooleanArray, filledArea: Int, checked: Boolean): Found {
        val w = image.width
        val h = image.height
        val (xs, ys) = pixels(quad, w, h)
        val quadArea = area(xs, ys)
        if (quadArea <= 0) return Found(quad, 0f)
        val fill = ramp(min(quadArea, filledArea.toDouble()) / max(quadArea, filledArea.toDouble()), 0.82, 0.94)

        var pageSum = 0L
        var pageCount = 0
        var aroundSum = 0L
        var aroundCount = 0
        for (i in page.indices) {
            if (page[i]) { pageSum += levels[i]; pageCount++ } else if (outside[i]) { aroundSum += levels[i]; aroundCount++ }
        }
        if (pageCount == 0 || aroundCount == 0) return Found(quad, 0f)
        val contrast = ramp(pageSum.toDouble() / pageCount - aroundSum.toDouble() / aroundCount, 18.0, 45.0)

        // Which of the photo's borders the outline's sides lie on, and from where to where.
        val sides = IntArray(4) { border(xs[it], ys[it], xs[(it + 1) % 4], ys[(it + 1) % 4], w, h, 0.0) }
        val from = DoubleArray(4) { -1.0 }
        val to = DoubleArray(4) { -1.0 }
        for (i in 0 until 4) {
            val on = sides[i]
            if (on < 0) continue
            val j = (i + 1) % 4
            val across = on == TOP || on == BOTTOM
            from[on] = (if (across) min(xs[i], xs[j]) else min(ys[i], ys[j])) - ON_BORDER
            to[on] = (if (across) max(xs[i], xs[j]) else max(ys[i], ys[j])) + ON_BORDER
        }
        // How much of the page lies along the photo's border elsewhere, where it goes on out of the
        // frame past a side that isn't the border.
        var perimeter = 0.0
        for (i in 0 until 4) perimeter += hypot(xs[(i + 1) % 4] - xs[i], ys[(i + 1) % 4] - ys[i])
        var along = 0
        for (x in 0 until w) {
            if (!outside[x] && (x + 0.5 < from[TOP] || x + 0.5 > to[TOP])) along++
            if (!outside[(h - 1) * w + x] && (x + 0.5 < from[BOTTOM] || x + 0.5 > to[BOTTOM])) along++
        }
        for (y in 1 until h - 1) {
            if (!outside[y * w] && (y + 0.5 < from[LEFT] || y + 0.5 > to[LEFT])) along++
            if (!outside[y * w + w - 1] && (y + 0.5 < from[RIGHT] || y + 0.5 > to[RIGHT])) along++
        }
        val inFrame = 1 - ramp(along / perimeter, 0.03, 0.2)
        // How much it is like a page, whatever its size.
        var confidence = squareness(xs, ys) * fill * contrast * inFrame

        val borders = sides.count { it >= 0 }
        if (borders == 4 || confidence == 0.0) return Found(quad, 0f, borders)
        if (borders > 0 || checked) {
            // The sides that aren't the border are the page's edges: sharp ones.
            var sharp = 1.0
            for (i in 0 until 4) {
                if (sides[i] >= 0) continue
                val j = (i + 1) % 4
                val length = hypot(xs[j] - xs[i], ys[j] - ys[i])
                if (length < 1) return Found(quad, 0f, borders)
                val dx = ((xs[j] - xs[i]) / length).toFloat()
                val dy = ((ys[j] - ys[i]) / length).toFloat()
                var hits = 0
                var count = 0
                var t = END_SHARE * length
                while (t <= (1 - END_SHARE) * length) {
                    if (edges.near((xs[i] + dx * t).toFloat(), (ys[i] + dy * t).toFloat(), -dy, dx)) hits++
                    count++
                    t += 1.0
                }
                if (count > 0) sharp = min(sharp, hits.toDouble() / count)
            }
            confidence *= ramp(sharp, 0.5, 0.75)
        }
        if (borders > 0) {
            confidence *= ramp(quadArea / (w.toDouble() * h), CLIPPED_AREA, CLIPPED_GOOD_AREA) * CLIPPED[borders]
            if (confidence > 0) confidence *= 1 - paperBeyond(xs, ys, image, sides)
            if (confidence >= CONFIDENT) confidence *= ramp(printWithin(xs, ys, image), SOME_PRINT, MUCH_PRINT)
        }
        if ((checked || borders > 0) && confidence > 0) confidence *= 1 - printBeyond(xs, ys, image, sides, null)
        val like = confidence
        confidence *= size(xs, ys, w, h)
        return Found(quad, confidence.toFloat(), borders, like.toFloat())
    }

    /**
     * How white the photo is (as bright as its dullest colour) along the sides of an outline: the
     * ground's shade just [within] each side and just [beyond] it, or -1 where it can't be told.
     * [paper] is the paper's shade: within the sides, where it is whitest, as a dark band may run
     * along one.
     */
    private class Shades(val within: DoubleArray, val beyond: DoubleArray) {
        val paper = within.max()

        /** Whether beyond side [i] it is as white as the paper, or whiter. */
        fun asWhite(i: Int) = beyond[i] >= 0 && beyond[i] >= SAME_SHADE * paper

        /** Whether beyond side [i] it is the shade of the paper just within it: the same sheet. */
        fun same(i: Int) = beyond[i] >= 0 && within[i] >= 0 && abs(beyond[i] - within[i]) <= (1 - SAME_SHADE) * within[i]

        /** Whether beyond side [i] it is clearly darker than within it: the desk. */
        fun darker(i: Int) = beyond[i] >= 0 && within[i] >= 0 && beyond[i] <= DARKER * within[i]
    }

    /**
     * The [Shades] along the sides of the outline with corners at [xs], [ys] on [image]: -1 for a
     * side on the photo's border ([borders], or -1), too short, or with too little of the photo
     * beyond it.
     */
    private fun shades(xs: DoubleArray, ys: DoubleArray, image: Grey, borders: IntArray): Shades {
        val w = image.width
        val h = image.height
        val white = image.white
        val turn = if ((xs[1] - xs[0]) * (ys[2] - ys[1]) - (ys[1] - ys[0]) * (xs[2] - xs[1]) > 0) 1.0 else -1.0
        val deep = (BEYOND_TO - BEYOND_FROM).toInt()
        val within = DoubleArray(4) { -1.0 }
        val beyond = DoubleArray(4) { -1.0 }
        for (i in 0 until 4) {
            if (borders[i] >= 0) continue
            val j = (i + 1) % 4
            val length = hypot(xs[j] - xs[i], ys[j] - ys[i])
            val across = ((1 - 2 * BEYOND_ENDS) * length).toInt()
            if (across < MIN_BEYOND / 2) continue
            val dx = (xs[j] - xs[i]) / length
            val dy = (ys[j] - ys[i]) / length
            val outX = dy * turn
            val outY = -dx * turn
            fun band(way: Double) = IntArray(deep * across) { k ->
                val along = BEYOND_ENDS * length + k % across + 0.5
                val out = way * (BEYOND_FROM + k / across + 0.5)
                val x = floor(xs[i] + along * dx + out * outX).toInt()
                val y = floor(ys[i] + along * dy + out * outY).toInt()
                if (x < 0 || y < 0 || x >= w || y >= h) -1 else white[y * w + x]
            }
            within[i] = ground(band(-1.0)) ?: -1.0
            beyond[i] = ground(band(1.0)) ?: -1.0
        }
        return Shades(within, beyond)
    }

    /**
     * How surely the paper goes on beyond a side of the outline with corners at [xs], [ys] on [image]
     * that isn't the photo's border ([borders] gives each side's, or -1), from 0 to 1. A page's edge
     * has the desk beyond it, as far as the photo shows; a dark band across a page (a heading's bar, a
     * tinted panel, a printed photograph, a book's gutter) has paper again beyond it: as white (as
     * bright as its dullest colour) as the paper just within the outline's edges, taken where it is
     * whitest, as a dark band may run along one.
     */
    private fun paperBeyond(xs: DoubleArray, ys: DoubleArray, image: Grey, borders: IntArray): Double {
        val w = image.width
        val h = image.height
        val white = image.white
        val turn = if ((xs[1] - xs[0]) * (ys[2] - ys[1]) - (ys[1] - ys[0]) * (xs[2] - xs[1]) > 0) 1.0 else -1.0
        val deep = (BEYOND_TO - BEYOND_FROM).toInt()
        var paper = -1.0
        var most = 0.0
        for (pass in 0..1) for (i in 0 until 4) {
            if (borders[i] >= 0) continue
            val j = (i + 1) % 4
            val length = hypot(xs[j] - xs[i], ys[j] - ys[i])
            val across = ((1 - 2 * BEYOND_ENDS) * length).toInt()
            if (across < MIN_BEYOND / 2) continue
            val dx = (xs[j] - xs[i]) / length
            val dy = (ys[j] - ys[i]) / length
            val outX = dy * turn
            val outY = -dx * turn
            fun at(along: Double, out: Double): Int {
                val x = floor(xs[i] + (BEYOND_ENDS * length + along) * dx + out * outX).toInt()
                val y = floor(ys[i] + (BEYOND_ENDS * length + along) * dy + out * outY).toInt()
                return if (x < 0 || y < 0 || x >= w || y >= h) -1 else white[y * w + x]
            }
            if (pass == 0) {
                // Just within the side.
                ground(IntArray(deep * across) { k -> at(k % across + 0.5, -BEYOND_FROM - k / across - 0.5) })?.let { paper = max(paper, it) }
                continue
            }
            if (paper < 0) return 0.0
            // Beyond the side as far as the photo goes, every other pixel each way.
            var seen = 0
            var bright = 0
            var out = BEYOND_FROM
            while (true) {
                var row = 0
                var along = 0.5
                while (along < across) {
                    val v = at(along, out)
                    if (v >= 0) {
                        row++
                        if (v >= AS_WHITE * paper) bright++
                    }
                    along += 2.0
                }
                if (row == 0) break
                seen += row
                out += 2.0
            }
            // A strip a few pixels wide tells nothing.
            if (seen >= FAR_SEEN * across) most = max(most, bright.toDouble() / seen)
        }
        return ramp(most, 0.1, 0.3)
    }

    /**
     * How surely printed paper goes on beyond a side of the outline with corners at [xs], [ys] on
     * [image], from 0 to 1: then the outline isn't the page's but lies on the page, round a table, a
     * box, a figure or a tinted panel, or along a shadow's edge. Beyond each side that isn't the
     * photo's border ([borders] gives each side's, or -1), a band of the photo is looked at on its
     * finer copy for print ([print]).
     *
     * With [sameShade], only where the ground beyond a side is as white as the paper within the
     * outline, as on one sheet: a darker desk, however speckled, isn't paper, nor is wood as bright
     * as the paper. Without, also where it is as dark as paper in shadow; darker than that, it is a
     * dark desk, whose grain and noise would pass for strokes.
     */
    private fun printBeyond(xs: DoubleArray, ys: DoubleArray, image: Grey, borders: IntArray, sameShade: Shades?): Double {
        val fine = image.fine
        // Out of the page from each side: to its left when the corners go clockwise.
        val turn = if ((xs[1] - xs[0]) * (ys[2] - ys[1]) - (ys[1] - ys[0]) * (xs[2] - xs[1]) > 0) 1.0 else -1.0
        val deep = ((PRINT_TO - BEYOND_FROM) * fine.scale).toInt()
        val stroke = (MAX_STROKE * fine.scale).toInt()
        if (deep < 4) return 0.0
        // The band beyond side i (or the one within it), row by row away from the side: null for a
        // side that is the photo's border, or too short.
        fun band(i: Int, way: Double): Pair<IntArray, Int>? {
            if (borders[i] >= 0) return null
            val j = (i + 1) % 4
            val length = hypot(xs[j] - xs[i], ys[j] - ys[i])
            val across = ((1 - 2 * BEYOND_ENDS) * length * fine.scale).toInt()
            if (across < MIN_BEYOND) return null
            val dx = (xs[j] - xs[i]) / length
            val dy = (ys[j] - ys[i]) / length
            val outX = dy * turn
            val outY = -dx * turn
            return IntArray(deep * across) { k ->
                val along = BEYOND_ENDS * length + (k % across + 0.5) / fine.scale
                val out = way * (BEYOND_FROM + (k / across + 0.5) / fine.scale)
                fine.at(xs[i] + along * dx + out * outX, ys[i] + along * dy + out * outY)
            } to across
        }
        // The paper's shade: within the outline's sides, where it is brightest (a dark band may run along one).
        val paper = (0 until 4).mapNotNull { i -> band(i, -1.0)?.let { ground(it.first) } }.maxOrNull() ?: return 0.0
        var most = 0.0
        for (i in 0 until 4) {
            val (beyond, across) = band(i, 1.0) ?: continue
            val ground = ground(beyond) ?: continue
            if (if (sameShade != null) !sameShade.asWhite(i) else ground < IN_SHADE * paper) continue
            val print = print(beyond, across, ground, stroke) ?: continue
            most = max(most, print)
        }
        return most
    }

    /**
     * How much of the outline with corners at [xs], [ys] on [image] holds print, from 0 to 1: the
     * share of the squares it is cut into, on the photo's finer copy, in which there is some ([print]).
     * A page photographed so close that it runs off the photo shows its print across a good part of
     * it; a white wall, a blanket or a desk between two straight edges shows none.
     */
    private fun printWithin(xs: DoubleArray, ys: DoubleArray, image: Grey): Double {
        val fine = image.fine
        val turn = if ((xs[1] - xs[0]) * (ys[2] - ys[1]) - (ys[1] - ys[0]) * (xs[2] - xs[1]) > 0) 1.0 else -1.0
        val side = (SQUARE * fine.scale).toInt()
        val stroke = (MAX_STROKE * fine.scale).toInt()
        val step = SQUARE / 2
        val across = ceil(image.width / step).toInt()
        val down = ceil(image.height / step).toInt()
        var squares = 0
        var printed = 0
        // The squares are the photo's own, not the outline's: each is looked at once, however many
        // outlines are weighed. First those side by side. Where they leave it open (some print, but
        // not across the page: a card's few lines, cut in two by the squares' sides), those half a
        // square on from them as well, so a line of print lies well within one wherever it falls.
        for (pass in 0..1) {
            for (row in max(0, ceil(ys.min() / step).toInt()) until min(down, floor(ys.max() / step).toInt() - 1)) {
                for (column in max(0, ceil(xs.min() / step).toInt()) until min(across, floor(xs.max() / step).toInt() - 1)) {
                    if ((row % 2 == 0 && column % 2 == 0) != (pass == 0)) continue
                    val left = column * step
                    val top = row * step
                    // A square whose middle is well within the outline.
                    val cx = left + SQUARE / 2
                    val cy = top + SQUARE / 2
                    var within = true
                    for (i in 0 until 4) {
                        val j = (i + 1) % 4
                        val length = hypot(xs[j] - xs[i], ys[j] - ys[i])
                        if (turn * ((xs[j] - xs[i]) * (cy - ys[i]) - (ys[j] - ys[i]) * (cx - xs[i])) < SQUARE / 2 * length) within = false
                    }
                    if (!within) continue
                    var state = image.squares[row * across + column]
                    if (state == UNSEEN) {
                        val square = IntArray(side * side) { k -> fine.at(left + (k % side + 0.5) / fine.scale, top + (k / side + 0.5) / fine.scale) }
                        val ground = ground(square)
                        state = if (ground == null) OFF_PHOTO else if ((print(square, side, ground, stroke, INK) ?: 0.0) >= 0.5) PRINTED else BARE
                        image.squares[row * across + column] = state
                    }
                    if (state != OFF_PHOTO) squares++
                    if (state == PRINTED) printed++
                }
            }
            if (squares < MIN_SQUARES) return 1.0
            if (printed == 0 || printed >= MUCH_PRINT * squares) break
        }
        return printed.toDouble() / squares
    }

    /**
     * How surely a patch of the photo's finer copy holds print, from 0 to 1: [levels] row by row,
     * [across] to a row (-1 off the photo), on a ground of the shade [ground]. Print is many short
     * dark strokes (at most [ink] of the ground's level, and darker than the specks of a photo taken
     * in poor light), at most [stroke] pixels long, on a lighter ground, in lines with gaps between
     * them, as letters and words are. Strokes in every row and column (the pages under a book's top
     * one, the grain of wood, stripes, the photo's noise) aren't print, nor are things too large for
     * strokes (keys, a pen). Null when too little of the patch is on the photo.
     */
    private fun print(levels: IntArray, across: Int, ground: Double, stroke: Int, ink: Double = PRINT_DARK): Double? {
        val deep = levels.size / across
        val histogram = IntArray(256)
        var seen = 0
        for (v in levels) if (v >= 0) {
            histogram[v]++
            seen++
        }
        if (seen < BEYOND_SEEN * levels.size) return null
        // How far the ground's own levels spread, by the level three tenths of the patch are
        // brighter than: a photo taken in poor light is speckled all over, and a stroke is darker
        // than its specks.
        var above = 0
        var bright = 255
        while (bright > 0 && 10 * (above + histogram[bright]) < 3 * seen) above += histogram[bright--]
        val spread = max(0.0, ground - bright)
        // Dark against the ground, or light: in between, as the pixel before (so a stroke's soft
        // edge doesn't flicker).
        val dark = min(min(ground * ink, ground - 2 * PRINT_LEVELS), ground - SPECKS * spread)
        val light = min(min(ground * PRINT_LIGHT, ground - PRINT_LEVELS), ground - SPECKS / 2 * spread)
        // Short strokes along the rows, then along the columns: how many, and in how many of them.
        val strokes = IntArray(2)
        val inked = IntArray(2)
        val looked = IntArray(2)
        for (way in 0..1) {
            val lines = if (way == 0) deep else across
            val steps = if (way == 0) across else deep
            for (line in 0 until lines) {
                var run = -1 // the dark run's length so far; -1 before the first light pixel
                var ink = false
                var on = 0
                var found = 0
                for (step in 0 until steps) {
                    val v = if (way == 0) levels[line * across + step] else levels[step * across + line]
                    if (v < 0) { run = -1; ink = false; continue }
                    on++
                    if (v < dark) ink = true else if (v > light) ink = false
                    if (ink) {
                        if (run >= 0) run++
                    } else {
                        if (run in 1..stroke) found++
                        run = 0
                    }
                }
                if (2 * on < steps) continue
                looked[way]++
                strokes[way] += found
                if (found > 0) inked[way]++
            }
        }
        // The ground is what most of the patch is: light letters on a darker ground (a camera's
        // stamp across the photo's foot) aren't a page's print.
        var below = 0
        var median = 0
        while (2 * (below + histogram[median]) < seen) below += histogram[median++]
        if (median < GROUND_SHARE * ground) return 0.0
        // Print is in lines: its strokes are many, and lie in a good part of the rows (or columns)
        // but not in all, for the gaps between the lines or the words. Stripes, grain and the
        // photo's noise have theirs in every one, a knot or a stain in a few.
        var print = 0.0
        for (way in 0..1) {
            if (looked[way] == 0) continue
            val many = ramp(strokes[way].toDouble() / seen, STROKES, MANY_STROKES)
            val share = inked[way].toDouble() / looked[way]
            print = max(print, min(many, min(ramp(share, FEW_LINES, SOME_LINES), 1 - ramp(share, LINED, UNLINED))))
        }
        return print
    }

    /** The ground's shade in a band of levels (-1 off the photo): what a tenth of it is brighter than. Null when too little of the band is on the photo. */
    private fun ground(band: IntArray): Double? {
        val histogram = IntArray(256)
        var count = 0
        for (v in band) if (v >= 0) {
            histogram[v]++
            count++
        }
        if (count < BEYOND_SEEN * band.size) return null
        var above = 0
        for (level in 255 downTo 0) {
            above += histogram[level]
            if (10 * above >= count) return level.toDouble()
        }
        return 0.0
    }

    /**
     * How sure an outline found by [edges] is, from 0 to 1, without asking the page to be brighter
     * than what is around it. Its sides run along edges that run the same way (all of each side but
     * its ends and a hand over part of it); its corners are where those edges stop (where lines go on
     * past the corners, the outline is a tile, a square of a grid or a stripe between lines of
     * print) and where both sides' edges stop (a side drawn on to a line that passes by, the grain
     * of the desk above the page, ends short of its corner); its sides aren't lines drawn or printed
     * on paper (a box on a form photographed close up, by the levels either side of them, on the
     * finer copy where a thin rule shows; two sides that meet, alone, are the shadow of a sheet
     * lifted off a white desk), not even one of them where the same paper lies beyond it
     * and the desk beyond another (a table as wide as its page); no printed paper goes on beyond a
     * side ([printBeyond]: a table, a box or a figure on the page), though neither counts where the
     * desk lies beyond three sides, the fourth then lying on a second sheet under the page; and as
     * for brightness it is large enough, doesn't run off the photo at a slant and its corners
     * aren't squashed flat.
     *
     * A side along the photo's border is the border itself, where the page runs off the photo, and
     * isn't weighed. As for a bright region ([regionConfidence]), more is then asked of the others:
     * the paper ends at them ([paperBeyond]), no print of any shade goes on beyond them, and the
     * page shows print across it ([printWithin]).
     */
    private fun edgeConfidence(quad: CropQuad, edges: Edges, image: Grey): Found {
        val w = edges.width
        val h = edges.height
        val fine = image.fine
        val (xs, ys) = pixels(quad, w, h)
        val quadArea = area(xs, ys)
        if (quadArea <= 0) return Found(quad, 0f)
        val sides = IntArray(4) { border(xs[it], ys[it], xs[(it + 1) % 4], ys[(it + 1) % 4], w, h) }
        val borders = sides.count { it >= 0 }
        if (borders == 4 || shape(xs, ys, w, h) == 0.0) return Found(quad, 0f, borders)

        // Which way is into the page from each side: to its right when the corners go clockwise.
        val turn = if ((xs[1] - xs[0]) * (ys[2] - ys[1]) - (ys[1] - ys[0]) * (xs[2] - xs[1]) > 0) 1f else -1f
        fun level(x: Float, y: Float) = fine.at(x.toDouble().coerceIn(0.0, w - 0.01), y.toDouble().coerceIn(0.0, h - 0.01))
        // Most of the outlines tried aren't on edges: those are told before anything dearer is measured.
        if (onEdges(xs, ys, sides, edges) == 0.0) return Found(quad, 0f, borders)
        var weakest = 1.0
        var hitsAll = 0
        var samples = 0
        var onBorder = 0
        var drawn = 0
        // The share of each side that is a line drawn on paper.
        val ruled = DoubleArray(4)
        val pastStart = DoubleArray(4)
        val pastEnd = DoubleArray(4)
        // How far from each of its corners a side's edge starts.
        val shortStart = DoubleArray(4)
        val shortEnd = DoubleArray(4)
        for (i in 0 until 4) {
            if (sides[i] >= 0) continue
            val j = (i + 1) % 4
            val length = hypot(xs[j] - xs[i], ys[j] - ys[i])
            if (length < 1) return Found(quad, 0f, borders)
            val dx = ((xs[j] - xs[i]) / length).toFloat()
            val dy = ((ys[j] - ys[i]) / length).toFloat()
            val inX = -dy * turn
            val inY = dx * turn
            fun at(t: Double) = OcrPoint((xs[i] + dx * t).toFloat(), (ys[i] + dy * t).toFloat())
            var hits = 0
            var count = 0
            var gap = 0
            var longestGap = 0
            val drawnBefore = drawn
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
                for (k in -4..4) line = min(line, level(p.x + k * 0.5f * inX, p.y + k * 0.5f * inY))
                // The paper either side of it, at its brightest a little way off: print may come close.
                var outer = 0
                var inner = 0
                for (k in -1..1) {
                    outer = max(outer, level(p.x - (DRAWN_SIDE + k) * inX, p.y - (DRAWN_SIDE + k) * inY))
                    inner = max(inner, level(p.x + (DRAWN_SIDE + k) * inX, p.y + (DRAWN_SIDE + k) * inY))
                }
                val dip = min(outer, inner) - line
                if (dip >= DRAWN_DIP && 2 * abs(outer - inner) < dip) drawn++
                count++
                t += 1.0
            }
            samples += count
            hitsAll += hits
            if (count > 0) ruled[i] = (drawn - drawnBefore).toDouble() / count
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
                var left = 0
                var right = 0
                var tried = 0
                var s = from + step * 2
                while (abs(s - from) <= reach) {
                    val p = at(s)
                    if (p.x >= 0 && p.y >= 0 && p.x < w && p.y < h) {
                        tried++
                        if (edges.along(p.x, p.y, -dy, dx)) found++
                        // Edges as much to either side of the line, running its way: the grain of a
                        // wooden desk the page lies square on, not the side's own edge going on. Far
                        // enough to the side to be clear of a thin rule's own other flank.
                        if (edges.along(p.x - BESIDE * dy, p.y + BESIDE * dx, -dy, dx)) left++
                        if (edges.along(p.x + BESIDE * dy, p.y - BESIDE * dx, -dy, dx)) right++
                    }
                    s += step
                }
                return if (tried == 0) 0.0 else max(0.0, found - (left + right) / 2.0) / tried
            }
            // Where the edge starts from either corner: the first of a few pixels in a row on it,
            // or near it, as a bowed edge is.
            fun short(from: Double, step: Double): Double {
                var s = 0.0
                var run = 0
                while (s < length / 2) {
                    val p = at(from + step * s)
                    if (edges.near(p.x, p.y, -dy, dx)) {
                        if (++run == 3) return s - 2
                    } else {
                        run = 0
                    }
                    s += 1.0
                }
                return length / 2
            }
            shortStart[i] = short(0.0, 1.0)
            shortEnd[i] = short(length, -1.0)
            // Past a corner on a side that is the photo's border, the page runs off the photo: no telling.
            pastStart[i] = if (sides[(i + 3) % 4] >= 0) 0.0 else beyond(0.0, -1.0)
            pastEnd[i] = if (sides[j] >= 0) 0.0 else beyond(length, 1.0)
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
        val support = onEdges(share, weakest)
        val inFrame = 1 - ramp(onBorder.toDouble() / max(samples, 1), 0.03, 0.2)
        val corners = (1 - ramp(crossed, 0.4, 0.7)) * (1 - ramp(open, 0.3, 0.6))
        // A sheet's shadow on a white desk falls along two sides that meet, dark as a rule on the
        // finer copy: those two don't count when neither of the other two is drawn.
        var drawnShare = drawn.toDouble() / max(samples, 1)
        if (borders == 0) for (i in 0 until 4) drawnShare = min(drawnShare, max(ruled[(i + 2) % 4], ruled[(i + 3) % 4]))
        val page = 1 - ramp(drawnShare, 0.3, 0.6)
        // How white the photo is just within each side and just beyond it: looked at only where it matters.
        val shades by lazy(LazyThreadSafetyMode.NONE) { shades(xs, ys, image, sides) }
        // Both sides of a page end at its corner, or both stop about as short of it (a rounded or
        // folded corner, a finger over it). Where one's edge stops well short, and much shorter than
        // the other's, that one was drawn on to a line that passes by: the grain of the desk above
        // the page, a rule printed across a receipt. And a side that runs off the photo shows up to
        // the photo's border.
        var passed = 0.0
        for (i in 0 until 4) {
            val before = (i + 3) % 4
            // By the photo's border twice as far: the page's corner may lie just inside it.
            val start = shortStart[i]
            val end = shortEnd[before]
            val stops = when {
                sides[i] >= 0 && sides[before] >= 0 -> 0.0
                sides[i] >= 0 -> end / 2
                sides[before] >= 0 -> start / 2
                max(start, end) >= FURTHER * (min(start, end) + REACHED) -> max(start, end)
                else -> 0.0
            }
            passed = max(passed, ramp(stops, SHORT, FAR_SHORT))
        }
        // A side that is a rule drawn on the paper, with the same paper beyond it, is a table's or a
        // box's, where another side has the desk beyond it, darker: a page's sides are all alike. (On
        // a white desk, where the paper's white goes on beyond every side, a page's edge may be the
        // line of its shadow.)
        // The desk beyond three sides, though, leaves the fourth lying on another sheet, one under
        // the page: it is the page's edge all the same, whatever is printed on the sheet beyond it.
        val desk by lazy(LazyThreadSafetyMode.NONE) { (0 until 4).count { shades.darker(it) } }
        // How much it is like a page, whatever its size.
        var like = squareness(xs, ys) * support * inFrame * corners * page * (1 - passed)
        if (like > 0 && (0 until 4).any { i -> ruled[i] >= RULED && shades.same(i) } && desk in 1..2) like = 0.0
        if (borders > 0 && like > 0) like *= ramp(quadArea / (w.toDouble() * h), CLIPPED_AREA, CLIPPED_GOOD_AREA) * CLIPPED[borders] * (1 - paperBeyond(xs, ys, image, sides))
        if (borders > 0 && like >= CONFIDENT) like *= ramp(printWithin(xs, ys, image), SOME_PRINT, MUCH_PRINT)
        // Printed paper going on beyond a side: a table, a box or a figure on the page.
        val beyond = if (like >= CONFIDENT && (borders > 0 || desk < 3)) printBeyond(xs, ys, image, sides, if (borders == 0) shades else null) else 0.0
        val sure = like * size(xs, ys, w, h)
        return Found(quad, (sure * (1 - beyond)).toFloat(), borders, (like * (1 - beyond)).toFloat())
    }

    /**
     * How well the sides of the outline with corners at [xs], [ys] lie on [edges], from 0 to 1, those
     * on the photo's border ([borders], or -1) left out: most of the outline on edges, and each side
     * on them for much of its length but for one stretch (a hand may cover part of a side).
     */
    private fun onEdges(xs: DoubleArray, ys: DoubleArray, borders: IntArray, edges: Edges): Double {
        var weakest = 1.0
        var hitsAll = 0
        var samples = 0
        for (i in 0 until 4) {
            if (borders[i] >= 0) continue
            val j = (i + 1) % 4
            val length = hypot(xs[j] - xs[i], ys[j] - ys[i])
            if (length < 1) return 0.0
            val dx = ((xs[j] - xs[i]) / length).toFloat()
            val dy = ((ys[j] - ys[i]) / length).toFloat()
            var hits = 0
            var count = 0
            var gap = 0
            var longestGap = 0
            var t = END_SHARE * length
            while (t <= (1 - END_SHARE) * length) {
                if (edges.along((xs[i] + dx * t).toFloat(), (ys[i] + dy * t).toFloat(), -dy, dx)) {
                    hits++
                    gap = 0
                } else {
                    gap++
                    longestGap = max(longestGap, gap)
                }
                count++
                t += 1.0
            }
            samples += count
            hitsAll += hits
            if (count > 0) weakest = min(weakest, (hits + longestGap).toDouble() / count)
        }
        return onEdges(hitsAll.toDouble() / max(samples, 1), weakest)
    }

    /** As [onEdges], from the [share] of the outline on edges and the share of its [weakest] side, its longest gap forgiven. */
    private fun onEdges(share: Double, weakest: Double): Double = ramp(share, 0.6, 0.85) * ramp(weakest, 0.75, 0.9)

    /** Each level 0..255 on a logarithmic scale, also 0..255. */
    private val LOG_LEVELS = IntArray(256) { (255 * ln(1.0 + it) / ln(256.0)).roundToInt() }

    /** 0 below [low], 1 above [high], in a straight line between. */
    private fun ramp(value: Double, low: Double, high: Double): Double = ((value - low) / (high - low)).coerceIn(0.0, 1.0)

    /** A page's side is at least this many pixels long. */
    private const val MIN_SIDE = 4.0
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
    /** and so many pairs of a side with the photo's border opposite it. */
    private const val MAX_BORDER_PAIRS = 100
    /** A page's corner may be this many pixels off the image. */
    private const val OFF_IMAGE = 3f
    /** So many of the best-scored outlines are fitted and weighed, */
    private const val CANDIDATES = 8
    /** and when none of them is surely a page, the next ones, up to so many in all. */
    private const val MORE_CANDIDATES = 24
    /** A line goes on past a corner when this share of it lies on its edge there. */
    private const val ON_PAST = 0.7f
    /** Outlines this much less sure than the surest are as good as it. */
    private const val NEARLY_AS_SURE = 0.1f
    /** Whether a side's edge goes on past its corner is checked against the edges this many pixels to either side of it. */
    private const val BESIDE = 6f
    /** A side's edge runs up to its corner when it starts within this many pixels of it, */
    private const val REACHED = 3.0
    /** and stops short of it from this many pixels on, for sure from this many, */
    private const val SHORT = 8.0
    private const val FAR_SHORT = 16.0
    /** when that is this many times as far as the other side's edge is from the corner. */
    private const val FURTHER = 2.5
    /** A line this much darker than the paper [DRAWN_SIDE] pixels either side of it is drawn on it. */
    private const val DRAWN_DIP = 35
    private const val DRAWN_SIDE = 4f

    /** An outline lies within another when its corners are at most this many pixels outside it, */
    private const val HELD = 3.0
    /** and is clearly smaller at this share of its area. */
    private const val SMALLER = 0.93

    /** The bright region is split at most this many times. */
    private const val SPLITS = 3

    /** The photo's borders, as a side lies along one. */
    private const val TOP = 0
    private const val RIGHT = 1
    private const val BOTTOM = 2
    private const val LEFT = 3
    /** A side within this many pixels of the photo's border at both ends lies along it, */
    private const val ON_BORDER = 2.5
    /** and a bright region's when it touches the border: its outermost pixels' middles are half a pixel in. */
    private const val TOUCHING = 1.0
    /** An outline with sides on the photo's border covers at least this much of the photo, */
    private const val CLIPPED_AREA = 0.2
    /** and this much to be sure of. */
    private const val CLIPPED_GOOD_AREA = 0.3
    /**
     * How sure it can be with no side, one, two or three on the border. With two of the page's edges
     * or one, too much else looks the same: a keyboard between its top and its foot, a wall between
     * the ceiling and the floor, the strip of a page between two rules.
     */
    private val CLIPPED = doubleArrayOf(1.0, 0.9, 0.0, 0.0)
    /** A page's bowed edge is within this many pixels of the side fitted to it, and runs within about 14° of it. */
    private const val NEAR = 6
    private const val COS_NEAR = 0.97f

    /** Paper beyond a side is looked for where the photo shows about 12 pixels' depth of it: so many times the side's length in samples, taken every other pixel each way, */
    private const val FAR_SEEN = 3
    /** and is what is at least this share as white as the paper within the outline. */
    private const val AS_WHITE = 0.9

    /**
     * The shade beyond a side is taken from this many pixels out to this many, and print is looked
     * for as far as this many (the blank margin round a tinted panel is about a line of print deep),
     */
    private const val BEYOND_FROM = 2.5
    private const val BEYOND_TO = 12.0
    private const val PRINT_TO = 14.0
    /** leaving out this share of the side at either end, along at least this many pixels of the finer copy, */
    private const val BEYOND_ENDS = 0.1
    private const val MIN_BEYOND = 24
    /** and only where this share of the band is on the photo. */
    private const val BEYOND_SEEN = 0.6
    /** A side is a rule drawn on the paper when this share of it is. */
    private const val RULED = 0.5
    /** Beyond a side it is the desk when at most this share as white as within it. */
    private const val DARKER = 0.85
    /** The ground beyond a side is as white as the paper within when at least this share of it, */
    private const val SAME_SHADE = 0.96
    /** and may be paper in shadow from this share on. */
    private const val IN_SHADE = 0.45
    /** Print is this share of the ground's level or darker, the ground between it this share or lighter. */
    private const val PRINT_DARK = 0.86
    private const val PRINT_LIGHT = 0.93
    /** and at least twice this many levels darker, or this many: less is the photo's noise. */
    private const val PRINT_LEVELS = 6
    /**
     * In a speckled photo, print is also darker than the ground by this many times what the
     * ground's brightest tenth is brighter than its brightest three tenths: less are its specks.
     */
    private const val SPECKS = 5.5
    /** A stroke of print is at most this many pixels long (of the small copy). */
    private const val MAX_STROKE = 6.0
    /** Print has at least this many strokes for each pixel of the band, and so many for sure, */
    private const val STROKES = 0.004
    private const val MANY_STROKES = 0.008
    /** in at most this share of the band's rows or columns for sure, and never in this share, */
    private const val LINED = 0.8
    private const val UNLINED = 0.95
    /** nor in less than this share of them, for sure in this share. */
    private const val FEW_LINES = 0.15
    private const val SOME_LINES = 0.3
    /** The ground print lies on is the shade of most of the patch: its median is at least this share of the ground's level. */
    private const val GROUND_SHARE = 0.85
    /** Within an outline only ink this share of the ground's level or darker counts: a wall's or a cloth's texture is fainter. */
    private const val INK = 0.78
    /** Print within an outline is looked for in squares this many pixels a side, of which it has at least this many, */
    private const val SQUARE = 16.0
    private const val MIN_SQUARES = 8
    /** and a page that runs off the photo has print in at least this share of them, for sure in this share. */
    private const val SOME_PRINT = 0.2
    private const val MUCH_PRINT = 0.3
    /** What is known of a square: not looked at yet, too little of it on the photo, no print in it, print. */
    private const val UNSEEN: Byte = 0
    private const val OFF_PHOTO: Byte = 1
    private const val BARE: Byte = 2
    private const val PRINTED: Byte = 3

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
