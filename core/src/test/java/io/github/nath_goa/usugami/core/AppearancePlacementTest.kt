package io.github.nath_goa.usugami.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AppearancePlacementTest {
    /** Where the page ends up drawing the form's point ([x], [y]): the form's matrix, then the result. */
    private fun place(rect: FloatArray, bbox: FloatArray, form: FloatArray, x: Float, y: Float): Pair<Float, Float> {
        val inner = form[0] * x + form[2] * y + form[4] to form[1] * x + form[3] * y + form[5]
        val m = AppearancePlacement.matrix(rect, bbox, form)
        return m[0] * inner.first + m[2] * inner.second + m[4] to m[1] * inner.first + m[3] * inner.second + m[5]
    }

    @Test fun boxTheSizeOfTheRectangleIsMovedOntoIt() {
        val m = AppearancePlacement.matrix(floatArrayOf(100f, 200f, 300f, 230f), floatArrayOf(0f, 0f, 200f, 30f))
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f, 100f, 200f), m, 0.0001f)
    }

    @Test fun boxIsStretchedToFillTheRectangle() {
        // A 20 x 10 appearance shown in a 100 x 40 rectangle, its box starting at (5, 5).
        val rect = floatArrayOf(50f, 60f, 150f, 100f)
        val bbox = floatArrayOf(5f, 5f, 25f, 15f)
        val identity = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
        val (x0, y0) = place(rect, bbox, identity, 5f, 5f)
        val (x1, y1) = place(rect, bbox, identity, 25f, 15f)
        assertEquals(50f, x0, 0.001f)
        assertEquals(60f, y0, 0.001f)
        assertEquals(150f, x1, 0.001f)
        assertEquals(100f, y1, 0.001f)
    }

    @Test fun turnedAppearanceStillFillsTheRectangle() {
        // A field turned a quarter: its form matrix turns the 100 x 20 box into a 20 x 100 one.
        val rect = floatArrayOf(10f, 10f, 30f, 110f)
        val bbox = floatArrayOf(0f, 0f, 100f, 20f)
        val quarter = floatArrayOf(0f, 1f, -1f, 0f, 20f, 0f)
        val corners = listOf(0f to 0f, 100f to 0f, 0f to 20f, 100f to 20f).map { (x, y) -> place(rect, bbox, quarter, x, y) }
        assertEquals(10f, corners.minOf { it.first }, 0.001f)
        assertEquals(30f, corners.maxOf { it.first }, 0.001f)
        assertEquals(10f, corners.minOf { it.second }, 0.001f)
        assertEquals(110f, corners.maxOf { it.second }, 0.001f)
    }

    @Test fun rectangleGivenUpsideDownIsNormalised() {
        val m = AppearancePlacement.matrix(floatArrayOf(300f, 230f, 100f, 200f), floatArrayOf(0f, 0f, 200f, 30f))
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f, 100f, 200f), m, 0.0001f)
    }

    @Test fun flatBoxIsNotStretchedToInfinity() {
        // A horizontal line drawn as an appearance has no height.
        val m = AppearancePlacement.matrix(floatArrayOf(0f, 50f, 100f, 50f), floatArrayOf(0f, 0f, 50f, 0f))
        assertArrayEquals(floatArrayOf(2f, 0f, 0f, 1f, 0f, 50f), m, 0.0001f)
    }
}
