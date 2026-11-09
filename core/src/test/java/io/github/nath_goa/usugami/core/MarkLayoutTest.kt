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

    /**
     * The word is outlines, filled shapes: as wide as Helvetica Bold made it before, so the sizes are
     * the same, and within the bounds the layout keeps from the page's edges. The PDF's content draws
     * the same contours, each closed, and fills them.
     */
    @Test fun theWordIsOutlinesWithinItsBounds() {
        assertEquals(4.223f, MarkLayout.TEXT_WIDTH, 0.001f)
        assertEquals(0.212f, MarkLayout.DESCENT, 0.001f)
        var starts = 0
        var closes = 0
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        MarkLayout.drawWord(
            object : MarkLayout.Pen {
                fun at(vararg xy: Float) {
                    for (i in xy.indices step 2) {
                        xs += xy[i]
                        ys += xy[i + 1]
                    }
                }
                override fun moveTo(x: Float, y: Float) = at(x, y).also { starts++ }
                override fun lineTo(x: Float, y: Float) = at(x, y)
                override fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = at(x1, y1, x, y)
                override fun close() {
                    closes++
                }
            },
        )
        // U, s, u, m: one contour each; g, a, i: two.
        assertEquals(10, starts)
        assertEquals(starts, closes)
        assertEquals(MarkLayout.INK_LEFT, xs.min(), 0.0001f)
        assertEquals(MarkLayout.INK_RIGHT, xs.max(), 0.0001f)
        assertEquals(-MarkLayout.DESCENT, ys.min(), 0.0001f)
        assertEquals(MarkLayout.INK_TOP, ys.max(), 0.0001f)
        assertTrue(MarkLayout.INK_LEFT > 0 && MarkLayout.INK_RIGHT < MarkLayout.TEXT_WIDTH)
        // At a size, as the preview draws them: the same outlines, that much bigger.
        var right = 0f
        MarkLayout.drawWord(
            object : MarkLayout.Pen {
                override fun moveTo(x: Float, y: Float) = lineTo(x, y)
                override fun lineTo(x: Float, y: Float) {
                    right = maxOf(right, x)
                }
                override fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = lineTo(maxOf(x1, x), y)
                override fun close() = Unit
            },
            fontSize = 12f,
        )
        assertEquals(MarkLayout.INK_RIGHT * 12, right, 0.001f)

        val pdf = MarkLayout.pdfWord().lines().filter { it.isNotEmpty() }
        assertEquals("0.00048828125 0 0 0.00048828125 0 0 cm", pdf.first())
        assertEquals(starts, pdf.count { it.endsWith(" m") })
        assertEquals(closes, pdf.count { it == "h" })
        assertEquals("f", pdf.last())
        // No text: nothing for a PDF's text search to find.
        assertTrue(pdf.none { it.endsWith("Tj") || it == "BT" })
    }

    @Test fun styleStaysInItsRanges() {
        assertThrows(IllegalArgumentException::class.java) { MarkStyle(size = 101) }
        assertThrows(IllegalArgumentException::class.java) { MarkStyle(opacity = 5) }
        assertEquals(MarkStyle(100, 10), MarkStyle.of(150, 0))
        assertEquals(0.3f, MarkStyle().alpha, 0.0001f)
    }
}
