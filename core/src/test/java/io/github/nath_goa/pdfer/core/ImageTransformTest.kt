package io.github.nath_goa.pdfer.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Checks the PDF placement matrix against a plain pixel-shuffling reference implementation of
 * the EXIF orientations followed by clockwise rotation.
 */
class ImageTransformTest {

    private val storedWidth = 4
    private val storedHeight = 3

    @Test
    fun everyOrientationAndRotationPlacesEveryPixelCorrectly() {
        val stored = Array(storedHeight) { r -> IntArray(storedWidth) { c -> r * storedWidth + c } }
        for (exif in 1..8) {
            for (rotation in listOf(0, 90, 180, 270)) {
                var expected = referenceExif(stored, exif)
                repeat(rotation / 90) { expected = rotateCw(expected) }
                val shownH = expected.size
                val shownW = expected[0].size

                val transform = ImageTransform.of(exif, rotation)
                assertEquals("swap for exif=$exif rot=$rotation", shownW != storedWidth, transform.swapsAxes)

                val x0 = 10.0
                val y0 = 20.0
                val w = shownW * 2.0
                val h = shownH * 3.0
                val m = transform.pdfMatrix(x0, y0, w, h)
                for (row in 0 until storedHeight) {
                    for (col in 0 until storedWidth) {
                        val u = (col + 0.5) / storedWidth
                        val v = 1 - (row + 0.5) / storedHeight
                        val x = m[0] * u + m[2] * v + m[4]
                        val y = m[1] * u + m[3] * v + m[5]
                        val (er, ec) = find(expected, stored[row][col])
                        val ex = x0 + (ec + 0.5) / shownW * w
                        val ey = y0 + h * (1 - (er + 0.5) / shownH)
                        assertEquals("x exif=$exif rot=$rotation px=($col,$row)", ex, x, 1e-9)
                        assertEquals("y exif=$exif rot=$rotation px=($col,$row)", ey, y, 1e-9)
                    }
                }
            }
        }
    }

    @Test
    fun rotationNormalisesAngles() {
        assertEquals(ImageTransform.rotation(270), ImageTransform.rotation(-90))
        assertEquals(ImageTransform.IDENTITY, ImageTransform.rotation(360))
        assertEquals(ImageTransform.fromExif(6), ImageTransform.rotation(90))
        assertEquals(ImageTransform.fromExif(3), ImageTransform.rotation(180))
        assertEquals(ImageTransform.fromExif(8), ImageTransform.rotation(270))
        assertEquals(ImageTransform.IDENTITY, ImageTransform.fromExif(0))
        assertEquals(ImageTransform.IDENTITY, ImageTransform.fromExif(9))
    }

    private fun find(image: Array<IntArray>, value: Int): Pair<Int, Int> {
        for (r in image.indices) for (c in image[r].indices) if (image[r][c] == value) return r to c
        error("value $value not found")
    }

    /** EXIF orientation semantics, written as explicit pixel moves. */
    private fun referenceExif(img: Array<IntArray>, orientation: Int): Array<IntArray> {
        val h = img.size
        val w = img[0].size
        return when (orientation) {
            1 -> Array(h) { r -> IntArray(w) { c -> img[r][c] } }
            2 -> Array(h) { r -> IntArray(w) { c -> img[r][w - 1 - c] } }
            3 -> Array(h) { r -> IntArray(w) { c -> img[h - 1 - r][w - 1 - c] } }
            4 -> Array(h) { r -> IntArray(w) { c -> img[h - 1 - r][c] } }
            5 -> Array(w) { r -> IntArray(h) { c -> img[c][r] } }
            6 -> rotateCw(img)
            7 -> Array(w) { r -> IntArray(h) { c -> img[h - 1 - c][w - 1 - r] } }
            8 -> Array(w) { r -> IntArray(h) { c -> img[c][w - 1 - r] } }
            else -> error("bad orientation")
        }
    }

    private fun rotateCw(img: Array<IntArray>): Array<IntArray> {
        val h = img.size
        val w = img[0].size
        return Array(w) { r -> IntArray(h) { c -> img[h - 1 - c][r] } }
    }
}
