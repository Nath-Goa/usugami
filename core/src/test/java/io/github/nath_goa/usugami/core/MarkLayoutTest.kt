package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkLayoutTest {
    // An A4 page as shown, in points.
    private val width = 595.28f
    private val height = 841.89f

    @Test fun sizesRunFromBarelyReadableToHalfThePage() {
        val smallest = MarkLayout.place(MarkStyle(size = 0), width, height)
        assertEquals(5.6f, smallest.fontSize, 0.1f)
        val default = MarkLayout.place(MarkStyle(), width, height)
        assertTrue("About a 9 pt font, not ${default.fontSize}", default.fontSize in 8.8f..9.6f)
        // At the largest, the word starts on the page's middle.
        val largest = MarkLayout.place(MarkStyle(size = 100), width, height)
        assertEquals(width / 2, largest.x, 0.01f)
        // Each step up is bigger than the last, and the small end changes least.
        val sizes = (0..100).map { MarkLayout.widthShare(it) }
        assertTrue(sizes.zipWithNext().all { (a, b) -> b > a })
        assertTrue(sizes[1] - sizes[0] < sizes[100] - sizes[99])
    }

    @Test fun staysInTheBottomRightCorner() {
        for (size in listOf(0, 20, 60, 100)) {
            val place = MarkLayout.place(MarkStyle(size = size), width, height)
            val f = place.fontSize
            // Its right edge and the bottom of its "g" are a font size from the page's edges.
            assertEquals(width - f * MarkLayout.MARGIN, place.x + f * MarkLayout.TEXT_WIDTH, 0.01f)
            assertEquals(f * MarkLayout.MARGIN, place.y - f * MarkLayout.DESCENT, 0.01f)
        }
        // On a page much wider than tall, the word stays low on the page.
        val strip = MarkLayout.place(MarkStyle(size = 100), 2000f, 100f)
        assertEquals(25f, strip.fontSize, 0.01f)
    }

    @Test fun styleStaysInItsRanges() {
        assertThrows(IllegalArgumentException::class.java) { MarkStyle(size = 101) }
        assertThrows(IllegalArgumentException::class.java) { MarkStyle(opacity = 5) }
        assertEquals(MarkStyle(100, 10), MarkStyle.of(150, 0))
        assertEquals(0.3f, MarkStyle().alpha, 0.0001f)
    }
}
