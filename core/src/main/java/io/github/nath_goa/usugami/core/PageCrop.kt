package io.github.nath_goa.usugami.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A point on an image, as fractions of its width and height: x from the left, y from the top, 0..1. */
data class CropPoint(val x: Float, val y: Float)

/**
 * Where a page's four corners are on an image, as fractions of its size: [corners] go round the page
 * clockwise as it shows, from its top-left ([TOP_LEFT], [TOP_RIGHT], [BOTTOM_RIGHT], [BOTTOM_LEFT]).
 */
data class CropQuad(val corners: List<CropPoint>) {
    init {
        require(corners.size == 4) { "A page has 4 corners, not ${corners.size}" }
    }

    operator fun get(index: Int): CropPoint = corners[index]

    /** Whether this is the whole image (or as good as: within [tolerance] of each corner). */
    fun isWhole(tolerance: Float = 0.002f): Boolean = isCloseTo(WHOLE, tolerance)

    /** Whether each corner is within [tolerance] of [other]'s. */
    fun isCloseTo(other: CropQuad, tolerance: Float = 1e-4f): Boolean =
        corners.zip(other.corners).all { (a, b) -> abs(a.x - b.x) <= tolerance && abs(a.y - b.y) <= tolerance }

    /** Corner [index] moved to ([x], [y]), kept on the image. */
    fun with(index: Int, x: Float, y: Float): CropQuad =
        CropQuad(corners.mapIndexed { i, p -> if (i == index) CropPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)) else p })

    /** Corner [index] moved by ([dx], [dy]), kept on the image. */
    fun moved(index: Int, dx: Float, dy: Float): CropQuad = with(index, corners[index].x + dx, corners[index].y + dy)

    /**
     * Side [side] (from corner [side] to the next, clockwise: [TOP_SIDE] and so on) dragged by ([dx],
     * [dy]), as fractions of the image, on an image [width] × [height] pixels. Only the part of the drag
     * across the side counts: the side moves parallel to itself, and its two corners slide along the
     * sides beside it, as a page's edge does. When a side beside it is (nearly) parallel to it, or a
     * corner would leave the image that way, both corners move straight across instead. The corners
     * stay on the image.
     */
    fun movedSide(side: Int, dx: Float, dy: Float, width: Int, height: Int): CropQuad {
        val first = side
        val second = (side + 1) % 4
        // In pixels: a fraction of the width and one of the height aren't the same length.
        val xs = DoubleArray(4) { corners[it].x.toDouble() * width }
        val ys = DoubleArray(4) { corners[it].y.toDouble() * height }
        val sideX = xs[second] - xs[first]
        val sideY = ys[second] - ys[first]
        val length = hypot(sideX, sideY)
        // A side with no length has no direction: its corners just follow the drag.
        if (length < 1e-9) return moved(first, dx, dy).moved(second, dx, dy)
        val normalX = -sideY / length
        val normalY = sideX / length
        val across = dx.toDouble() * width * normalX + dy.toDouble() * height * normalY

        // Where a corner goes sliding along its other side, the one to [other], for the side to move
        // across: null when that side is nearly parallel to the one moved, or the corner would leave
        // the image.
        fun slid(corner: Int, other: Int): CropPoint? {
            val alongX = xs[corner] - xs[other]
            val alongY = ys[corner] - ys[other]
            val alongLength = hypot(alongX, alongY)
            if (alongLength < 1e-9) return null
            val slant = (alongX * normalX + alongY * normalY) / alongLength
            if (abs(slant) < MIN_SLANT) return null
            val t = across / (slant * alongLength)
            val x = (xs[corner] + t * alongX) / width
            val y = (ys[corner] + t * alongY) / height
            return if (x in -EDGE..1 + EDGE && y in -EDGE..1 + EDGE) CropPoint(x.toFloat().coerceIn(0f, 1f), y.toFloat().coerceIn(0f, 1f)) else null
        }
        val a = slid(first, (side + 3) % 4)
        val b = slid(second, (side + 2) % 4)
        if (a != null && b != null) return with(first, a.x, a.y).with(second, b.x, b.y)
        val shiftX = (across * normalX / width).toFloat()
        val shiftY = (across * normalY / height).toFloat()
        return moved(first, shiftX, shiftY).moved(second, shiftX, shiftY)
    }

    /**
     * The same corners on the image turned [degrees] clockwise (a multiple of 90): each goes where
     * the turn takes it, and is named for where it lands, so a quarter turn makes corner i corner
     * i + 1 (and side i side i + 1). Turning back gives the same corners again.
     */
    fun turned(degrees: Int): CropQuad {
        val quarters = Math.floorMod(degrees, 360) / 90
        val moved = transformed(ImageTransform.rotation(degrees))
        return CropQuad(List(4) { moved[Math.floorMod(it - quarters, 4)] })
    }

    /** Each corner put where [transform] takes it (as the image is turned or flipped), in the same order. */
    fun transformed(transform: ImageTransform): CropQuad = CropQuad(
        corners.map { p ->
            CropPoint(
                transform.a * p.x + transform.b * p.y + transform.c,
                transform.d * p.x + transform.e * p.y + transform.f,
            )
        },
    )

    /**
     * The same four points in order round the page, clockwise as it shows, from the one nearest the
     * top-left of the [width] × [height] image they are on: the order a page's corners have, whichever
     * were dragged where. Nearest in pixels: in fractions of the image's sides, a page tilted to the
     * right on a tall photo would start at its bottom-left corner and come out lying on its side.
     */
    fun ordered(width: Int = 1, height: Int = 1): CropQuad {
        val cx = corners.sumOf { it.x.toDouble() } / 4
        val cy = corners.sumOf { it.y.toDouble() } / 4
        // Clockwise on the screen, where y points down, is the angle growing.
        val round = corners.sortedBy { atan2(it.y - cy, it.x - cx) }
        val first = round.indices.minBy { round[it].x.toDouble() * width + round[it].y.toDouble() * height }
        return CropQuad(List(4) { round[(first + it) % 4] })
    }

    /**
     * Whether the corners make a page shape on a [width] × [height] image: they go round a convex
     * shape (in either direction) and its sides are at least [minSide] pixels long.
     */
    fun isUsable(width: Int, height: Int, minSide: Float = MIN_SIDE): Boolean {
        val xs = DoubleArray(4) { corners[it].x.toDouble() * width }
        val ys = DoubleArray(4) { corners[it].y.toDouble() * height }
        var sign = 0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val k = (i + 2) % 4
            if (hypot(xs[j] - xs[i], ys[j] - ys[i]) < minSide) return false
            val cross = (xs[j] - xs[i]) * (ys[k] - ys[j]) - (ys[j] - ys[i]) * (xs[k] - xs[j])
            // A corner with its sides (almost) in a line isn't a corner.
            if (abs(cross) < 1e-6 * width * height) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    companion object {
        const val TOP_LEFT = 0
        const val TOP_RIGHT = 1
        const val BOTTOM_RIGHT = 2
        const val BOTTOM_LEFT = 3

        /** The sides, each from the corner of the same number to the next: see [movedSide]. */
        const val TOP_SIDE = 0
        const val RIGHT_SIDE = 1
        const val BOTTOM_SIDE = 2
        const val LEFT_SIDE = 3

        /** The shortest side, in pixels of the image, that a cropped page can have. */
        const val MIN_SIDE = 16f

        /**
         * A side less than about 10 degrees from the side being moved counts as parallel to it: its
         * corner would race along it, far further than the drag.
         */
        private const val MIN_SLANT = 0.17

        /** How far past the image's edge, as a fraction of it, a sliding corner still counts as on it. */
        private const val EDGE = 1e-6

        val WHOLE = CropQuad(listOf(CropPoint(0f, 0f), CropPoint(1f, 0f), CropPoint(1f, 1f), CropPoint(0f, 1f)))

        /** The image less [fraction] of its width and height on every side. */
        fun inset(fraction: Float): CropQuad {
            val far = 1 - fraction
            return CropQuad(listOf(CropPoint(fraction, fraction), CropPoint(far, fraction), CropPoint(far, far), CropPoint(fraction, far)))
        }
    }
}

/** How a page cropped from an image comes out. */
object PageCrop {
    /** Cropped pages are at most this many pixels on their long side: plenty for a PDF page. */
    const val MAX_SIDE = 3000

    /** Where a page's corners start when none was found on the image: this far in from each edge. */
    const val INSET = 0.08f

    /**
     * The size of the page in [quad] once straightened, from an image [width] × [height] pixels as it
     * shows. Its shape comes from the average lengths of its opposite edges (the far edge of a page
     * photographed at a slant looks shorter than it is, the near one longer), and it is as large as
     * its longest edges, so no detail is lost; then it is shrunk to [maxSide] on its long side.
     */
    fun outputSize(quad: CropQuad, width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        fun length(a: Int, b: Int) = hypot(
            (quad[a].x - quad[b].x).toDouble() * width,
            (quad[a].y - quad[b].y).toDouble() * height,
        )
        val top = length(CropQuad.TOP_LEFT, CropQuad.TOP_RIGHT)
        val bottom = length(CropQuad.BOTTOM_LEFT, CropQuad.BOTTOM_RIGHT)
        val left = length(CropQuad.TOP_LEFT, CropQuad.BOTTOM_LEFT)
        val right = length(CropQuad.TOP_RIGHT, CropQuad.BOTTOM_RIGHT)
        val w = (top + bottom) / 2
        val h = (left + right) / 2
        if (w <= 0 || h <= 0) return max(1, w.roundToInt()) to max(1, h.roundToInt())
        val grow = max(max(top, bottom) / w, max(left, right) / h)
        val scale = min(grow, maxSide / max(w, h))
        return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
    }

    /**
     * The corners of [quad] (on the image as it shows upright) on the image as stored, in its pixels:
     * x0, y0, x1, y1, … in the page's corner order, for a [storedWidth] × [storedHeight] image shown
     * upright by [upright] (its EXIF orientation).
     */
    fun storedCorners(quad: CropQuad, upright: ImageTransform, storedWidth: Int, storedHeight: Int): FloatArray {
        val stored = quad.transformed(upright.inverse())
        return FloatArray(8) { i -> if (i % 2 == 0) stored[i / 2].x * storedWidth else stored[i / 2].y * storedHeight }
    }
}
