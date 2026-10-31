package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PageStampsTest {
    private fun assertPoint(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, 0.001f)
        assertEquals(expected.second, actual.second, 0.001f)
    }

    // A crop box that doesn't start at the origin: 400 wide, 600 tall, from (10, 20).
    private fun frame(rotation: Int) = PageFrame(10f, 20f, 400f, 600f, rotation)

    @Test fun upright() {
        val frame = frame(0)
        assertEquals(400f, frame.shownWidth)
        assertEquals(600f, frame.shownHeight)
        assertPoint(10f to 20f, frame.toPage(0f, 0f))
        assertPoint(410f to 620f, frame.toPage(400f, 600f))
    }

    @Test fun turnedClockwise() {
        // Shown sideways: the box's right edge is the shown bottom, its bottom edge the shown left.
        val frame = frame(90)
        assertEquals(600f, frame.shownWidth)
        assertEquals(400f, frame.shownHeight)
        assertPoint(410f to 20f, frame.toPage(0f, 0f)) // shown bottom-left
        assertPoint(410f to 620f, frame.toPage(600f, 0f)) // shown bottom-right
        assertPoint(10f to 20f, frame.toPage(0f, 400f)) // shown top-left
        assertPoint(10f to 620f, frame.toPage(600f, 400f)) // shown top-right
    }

    @Test fun upsideDown() {
        val frame = frame(180)
        assertPoint(410f to 620f, frame.toPage(0f, 0f))
        assertPoint(10f to 20f, frame.toPage(400f, 600f))
    }

    @Test fun turnedAnticlockwise() {
        val frame = frame(-90) // the same as 270
        assertEquals(270, frame.rotation)
        assertEquals(600f, frame.shownWidth)
        assertPoint(10f to 620f, frame.toPage(0f, 0f)) // shown bottom-left: the box's top-left
        assertPoint(10f to 20f, frame.toPage(600f, 0f))
        assertPoint(410f to 620f, frame.toPage(0f, 400f))
    }

    @Test fun everyRotationCoversTheWholeBox() {
        for (rotation in listOf(0, 90, 180, 270, 450)) {
            val frame = frame(rotation)
            val corners = listOf(0f to 0f, frame.shownWidth to 0f, 0f to frame.shownHeight, frame.shownWidth to frame.shownHeight)
                .map { (x, y) -> frame.toPage(x, y) }
            assertEquals("Rotation $rotation", setOf(10f, 410f), corners.map { it.first }.toSet())
            assertEquals("Rotation $rotation", setOf(20f, 620f), corners.map { it.second }.toSet())
        }
    }

    @Test fun oddRotationsAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { PageFrame(0f, 0f, 100f, 100f, 45) }
    }

    @Test fun skippedPagesGetNoNumber() {
        assertEquals(listOf(0, 1, 2, 3), PageNumbers.numbered("", 4))
        assertEquals(listOf(0, 1, 2, 3), PageNumbers.numbered("  ", 4))
        assertEquals(listOf(1, 2, 3), PageNumbers.numbered("1", 4))
        assertEquals(listOf(2), PageNumbers.numbered("1-2,4", 4))
        assertEquals(emptyList<Int>(), PageNumbers.numbered("1-4", 4))
    }

    @Test fun badSkipInputSaysWhy() {
        fun problem(text: String) = assertThrows(PageSelectionException::class.java) { PageNumbers.numbered(text, 4) }.problem
        assertEquals(PageSelectionException.Problem.FORMAT, problem("cover"))
        assertEquals(PageSelectionException.Problem.OUT_OF_RANGE, problem("5"))
    }

    @Test fun numbersSitInsideTheMargin() {
        // A4: the margin is half an inch.
        assertEquals(35.7f, PageNumbers.margin(595f, 842f), 0.01f)
        assertEquals(36f, PageNumbers.margin(842f, 1190f), 0.01f)
        // A small page gets a smaller margin.
        assertEquals(12f, PageNumbers.margin(200f, 300f), 0.01f)

        val (w, h) = 600f to 800f // margin 36
        assertPoint(36f to 36f, PageNumbers.origin(PageNumberPosition.BOTTOM_LEFT, w, h, 20f, 10f))
        assertPoint(290f to 36f, PageNumbers.origin(PageNumberPosition.BOTTOM_CENTER, w, h, 20f, 10f))
        assertPoint(544f to 36f, PageNumbers.origin(PageNumberPosition.BOTTOM_RIGHT, w, h, 20f, 10f))
        assertPoint(36f to 754f, PageNumbers.origin(PageNumberPosition.TOP_LEFT, w, h, 20f, 10f))
        assertPoint(290f to 754f, PageNumbers.origin(PageNumberPosition.TOP_CENTER, w, h, 20f, 10f))
        assertPoint(544f to 754f, PageNumbers.origin(PageNumberPosition.TOP_RIGHT, w, h, 20f, 10f))
    }

    @Test fun watermarkRunsAlongTheDiagonal() {
        // A long text on A4 is limited by the diagonal.
        val long = WatermarkLayout.place(595f, 842f, 1000f, 50f)
        assertEquals(297.5f, long.centerX, 0.01f)
        assertEquals(421f, long.centerY, 0.01f)
        assertEquals(54.75f, long.degrees, 0.01f)
        assertEquals(0.7f * 1031.0f / 1000f, long.scale, 0.001f)
        // A short word is limited by its height.
        val short = WatermarkLayout.place(595f, 842f, 100f, 50f)
        assertEquals(0.18f * 595f / 50f, short.scale, 0.001f)
        // Landscape pages tilt less.
        assertEquals(35.25f, WatermarkLayout.place(842f, 595f, 1000f, 50f).degrees, 0.01f)
    }

    @Test fun watermarkStaysOnThePage() {
        for ((w, h) in listOf(595f to 842f, 842f to 595f, 300f to 300f, 200f to 1000f)) {
            for ((stampW, stampH) in listOf(1000f to 50f, 100f to 50f, 300f to 100f)) {
                val p = WatermarkLayout.place(w, h, stampW, stampH)
                val angle = Math.toRadians(p.degrees.toDouble())
                val halfW = stampW * p.scale / 2
                val halfH = stampH * p.scale / 2
                for ((dx, dy) in listOf(-halfW to -halfH, halfW to -halfH, -halfW to halfH, halfW to halfH)) {
                    val x = p.centerX + dx * cos(angle) - dy * sin(angle)
                    val y = p.centerY + dx * sin(angle) + dy * cos(angle)
                    assertTrue("Corner ($x, $y) of $stampW×$stampH is off the $w×$h page", x in 0.0..w.toDouble() && y in 0.0..h.toDouble())
                }
            }
        }
    }
}
