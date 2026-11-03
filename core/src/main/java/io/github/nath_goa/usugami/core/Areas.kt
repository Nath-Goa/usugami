package io.github.nath_goa.usugami.core

/**
 * Rectangles on a PDF page, as left, bottom, right, top, and the matrices that move them, as a, b,
 * c, d, e, f: the six numbers of a PDF `cm` operator (x' = a x + c y + e, y' = b x + d y + f).
 */
object Areas {
    /** The bounds of [area] once [m] has moved it. */
    fun mapped(m: FloatArray, area: FloatArray): FloatArray {
        val xs = FloatArray(4)
        val ys = FloatArray(4)
        for ((i, corner) in listOf(area[0] to area[1], area[2] to area[1], area[0] to area[3], area[2] to area[3]).withIndex()) {
            xs[i] = m[0] * corner.first + m[2] * corner.second + m[4]
            ys[i] = m[1] * corner.first + m[3] * corner.second + m[5]
        }
        return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }

    /** The matrix that undoes [m]; null if [m] squashes everything onto a line or a point. */
    fun inverted(m: FloatArray): FloatArray? {
        val det = m[0].toDouble() * m[3] - m[1].toDouble() * m[2]
        if (det == 0.0 || !det.isFinite()) return null
        val a = m[3] / det
        val b = -m[1] / det
        val c = -m[2] / det
        val d = m[0] / det
        val e = -(a * m[4] + c * m[5])
        val f = -(b * m[4] + d * m[5])
        return floatArrayOf(a.toFloat(), b.toFloat(), c.toFloat(), d.toFloat(), e.toFloat(), f.toFloat())
    }

    /** Where [a] and [b] overlap; null if they don't, or only along an edge. */
    fun intersection(a: FloatArray, b: FloatArray): FloatArray? {
        val left = maxOf(a[0], b[0])
        val bottom = maxOf(a[1], b[1])
        val right = minOf(a[2], b[2])
        val top = minOf(a[3], b[3])
        return if (left < right && bottom < top) floatArrayOf(left, bottom, right, top) else null
    }

    /** Whether [inner] lies within [outer], reaching at most [slack] beyond its edges. */
    fun inside(inner: FloatArray, outer: FloatArray, slack: Float = 0f): Boolean =
        inner[0] >= outer[0] - slack && inner[1] >= outer[1] - slack && inner[2] <= outer[2] + slack && inner[3] <= outer[3] + slack
}
