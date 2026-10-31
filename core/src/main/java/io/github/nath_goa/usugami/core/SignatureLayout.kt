package io.github.nath_goa.usugami.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A rectangle: its corner ([x], [y]) and its size. Which corner depends on which way y points. */
data class Area(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * Where a signature goes on a page as it is shown, as fractions of that page: its centre
 * ([centerX] from the left edge, [centerY] from the top edge, as on a screen) and its [width].
 * The preview on the screen and the saved PDF both work from these, so they match whatever size
 * each is drawn at.
 */
data class SignaturePlacement(val centerX: Float, val centerY: Float, val width: Float)

/**
 * A hand-drawn signature and its place on a page. Strokes are drawn on a pad [PAD_WIDTH] by
 * [PAD_HEIGHT] units big; the signature is what they cover ([inkBounds]), so its shape (width over
 * height, the "aspect") is that of the ink, not of the pad.
 */
object SignatureLayout {
    const val PAD_WIDTH = 1000f
    const val PAD_HEIGHT = 500f

    /** The pen, in pad units: about 3 dp on a pad as wide as a phone. */
    const val PEN_WIDTH = 9f

    /** The smallest a signature gets, in points: half an inch wide. */
    const val MIN_WIDTH_POINTS = 36f

    /** A new signature is this share of the page's shorter side wide: 2.6 inches on A4… */
    const val DEFAULT_WIDTH = 0.32f

    /** …unless that makes it taller than this share of the page… */
    const val DEFAULT_MAX_HEIGHT = 0.12f

    /** …and it sits this share of the shorter side from the bottom and right edges. */
    const val DEFAULT_MARGIN = 0.08f

    /** The signature image's resolution, sharp in print… */
    const val DPI = 300

    /** …but never more pixels than this on its longer side. */
    const val MAX_PIXELS = 2400

    /**
     * What [strokes] cover in pad units (y down), the pen's width included; null without a point.
     * Each stroke is its points' coordinates in turn: x0, y0, x1, y1, …
     *
     * The pen follows a smoothed curve, not the points: from the first point it bends through each
     * point in turn to the midpoint between it and the next (a quadratic curve with the point as
     * its control point), then goes straight to the last point. The curve doesn't reach the
     * points it bends through, so its own extremes are measured, to trim the image to the ink.
     */
    fun inkBounds(strokes: List<FloatArray>, penWidth: Float = PEN_WIDTH): Area? {
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        fun include(x: Float, y: Float) {
            left = min(left, x)
            right = max(right, x)
            top = min(top, y)
            bottom = max(bottom, y)
        }
        for (stroke in strokes) {
            val count = stroke.size / 2
            if (count == 0) continue
            var startX = stroke[0]
            var startY = stroke[1]
            include(startX, startY)
            for (i in 1 until count - 1) {
                val controlX = stroke[2 * i]
                val controlY = stroke[2 * i + 1]
                val endX = (controlX + stroke[2 * i + 2]) / 2
                val endY = (controlY + stroke[2 * i + 3]) / 2
                include(endX, endY)
                // Where the curve turns along x, and along y, if it does between its ends.
                for (t in listOf(turn(startX, controlX, endX), turn(startY, controlY, endY))) {
                    if (t != null) include(quadratic(startX, controlX, endX, t), quadratic(startY, controlY, endY, t))
                }
                startX = endX
                startY = endY
            }
            include(stroke[2 * count - 2], stroke[2 * count - 1])
        }
        if (left > right) return null
        return Area(left - penWidth / 2, top - penWidth / 2, right - left + penWidth, bottom - top + penWidth)
    }

    /** The point at [t] of the quadratic curve from [start] to [end] bending towards [control], on one axis. */
    private fun quadratic(start: Float, control: Float, end: Float, t: Float): Float =
        (1 - t) * (1 - t) * start + 2 * t * (1 - t) * control + t * t * end

    /** Where that curve turns back on this axis, if it does strictly between its ends. */
    private fun turn(start: Float, control: Float, end: Float): Float? {
        val bend = start - 2 * control + end
        if (bend == 0f) return null
        return ((start - control) / bend).takeIf { it > 0f && it < 1f }
    }

    /**
     * The widths a signature with [aspect] can have on a shown page [shownWidth] × [shownHeight]
     * points big, as shares of that width: from half an inch up to as big as the page.
     */
    fun widthRange(shownWidth: Float, shownHeight: Float, aspect: Float): ClosedFloatingPointRange<Float> {
        require(shownWidth > 0 && shownHeight > 0 && aspect > 0)
        val most = min(1f, shownHeight * aspect / shownWidth)
        return min(MIN_WIDTH_POINTS / shownWidth, most)..most
    }

    /** The signature's height as a share of the shown page's height, when it is [width] of its width. */
    fun heightShare(width: Float, shownWidth: Float, shownHeight: Float, aspect: Float): Float =
        width * shownWidth / aspect / shownHeight

    /** Where a signature starts out: in the lower right of the page, about as big as a real one. */
    fun default(shownWidth: Float, shownHeight: Float, aspect: Float): SignaturePlacement {
        val shorter = min(shownWidth, shownHeight)
        val width = min(DEFAULT_WIDTH * shorter, DEFAULT_MAX_HEIGHT * shownHeight * aspect)
        val height = width / aspect
        val margin = DEFAULT_MARGIN * shorter
        val placement = SignaturePlacement(
            centerX = (shownWidth - margin - width / 2) / shownWidth,
            centerY = (shownHeight - margin - height / 2) / shownHeight,
            width = width / shownWidth,
        )
        return clamp(placement, shownWidth, shownHeight, aspect)
    }

    /** [placement] with its width in [widthRange], moved as little as needed to be wholly on the page. */
    fun clamp(placement: SignaturePlacement, shownWidth: Float, shownHeight: Float, aspect: Float): SignaturePlacement {
        val width = placement.width.coerceIn(widthRange(shownWidth, shownHeight, aspect))
        // At most 1, since the width is at most what the page's height allows.
        val height = min(1f, heightShare(width, shownWidth, shownHeight, aspect))
        return SignaturePlacement(
            centerX = placement.centerX.coerceIn(width / 2, 1 - width / 2),
            centerY = placement.centerY.coerceIn(height / 2, 1 - height / 2),
            width = width,
        )
    }

    /**
     * Where the PDF draws the signature: in points on the shown page, from its bottom-left corner
     * with y up. [Area.x] and [Area.y] are the signature's bottom-left corner.
     */
    fun pdfArea(placement: SignaturePlacement, shownWidth: Float, shownHeight: Float, aspect: Float): Area {
        val width = placement.width * shownWidth
        val height = width / aspect
        return Area(placement.centerX * shownWidth - width / 2, (1 - placement.centerY) * shownHeight - height / 2, width, height)
    }

    /** The image's size in pixels for a signature [widthPoints] × [heightPoints] big. */
    fun pixels(widthPoints: Float, heightPoints: Float): Pair<Int, Int> {
        require(widthPoints > 0 && heightPoints > 0)
        val perPoint = min(DPI / 72f, MAX_PIXELS / max(widthPoints, heightPoints))
        return max(1, (widthPoints * perPoint).roundToInt()) to max(1, (heightPoints * perPoint).roundToInt())
    }
}
