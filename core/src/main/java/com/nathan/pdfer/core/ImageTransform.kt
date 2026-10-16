package com.nathan.pdfer.core

/**
 * One of the eight ways to rotate/flip an image, as a map of normalised coordinates
 * (s, t) in [0,1]², origin top-left, t pointing down:
 *
 *   s' = a·s + b·t + c
 *   t' = d·s + e·t + f
 */
data class ImageTransform(val a: Int, val b: Int, val c: Int, val d: Int, val e: Int, val f: Int) {

    /** True when the transform turns the image sideways, so width and height trade places. */
    val swapsAxes: Boolean get() = a == 0

    /** This transform followed by [next]. */
    fun then(next: ImageTransform) = ImageTransform(
        a = next.a * a + next.b * d,
        b = next.a * b + next.b * e,
        c = next.a * c + next.b * f + next.c,
        d = next.d * a + next.e * d,
        e = next.d * b + next.e * e,
        f = next.d * c + next.e * f + next.f,
    )

    /**
     * The PDF `cm` matrix [A B C D E F] that paints an image XObject (which always fills the
     * unit square, first row at the top) transformed by this, into the rectangle
     * (x, y, width, height) on the page, with y measured from the bottom.
     */
    fun pdfMatrix(x: Double, y: Double, width: Double, height: Double): DoubleArray = doubleArrayOf(
        width * a,
        -height * d,
        -width * b,
        height * e,
        x + width * (b + c),
        y + height * (1 - e - f),
    )

    companion object {
        val IDENTITY = ImageTransform(1, 0, 0, 0, 1, 0)
        private val ROTATE_90_CW = ImageTransform(0, -1, 1, 1, 0, 0)

        /** The transform that displays an image stored with the given EXIF orientation (1–8) upright. */
        fun fromExif(orientation: Int): ImageTransform = when (orientation) {
            2 -> ImageTransform(-1, 0, 1, 0, 1, 0)   // mirror horizontally
            3 -> ImageTransform(-1, 0, 1, 0, -1, 1)  // rotate 180°
            4 -> ImageTransform(1, 0, 0, 0, -1, 1)   // mirror vertically
            5 -> ImageTransform(0, 1, 0, 1, 0, 0)    // transpose
            6 -> ROTATE_90_CW
            7 -> ImageTransform(0, -1, 1, -1, 0, 1)  // transverse
            8 -> ImageTransform(0, 1, 0, -1, 0, 1)   // rotate 90° counter-clockwise
            else -> IDENTITY
        }

        /** A clockwise rotation by a multiple of 90 degrees (any integer, normalised). */
        fun rotation(degreesClockwise: Int): ImageTransform {
            val quarterTurns = Math.floorMod(degreesClockwise, 360) / 90
            var t = IDENTITY
            repeat(quarterTurns) { t = t.then(ROTATE_90_CW) }
            return t
        }

        /** EXIF orientation first, then the user's clockwise rotation. */
        fun of(exifOrientation: Int, userRotationDegrees: Int): ImageTransform =
            fromExif(exifOrientation).then(rotation(userRotationDegrees))
    }
}
