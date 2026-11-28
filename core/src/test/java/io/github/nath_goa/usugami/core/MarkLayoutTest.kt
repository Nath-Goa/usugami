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
        assertTrue("About a 9 pt font, not ${default.fontSize}", default.fontSize in 8.5f..9.5f)
        // At the largest, the mark starts on the page's middle.
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
            // The logo's right edge and the bottom of the word's "g" are a font size from the page's edges.
            assertEquals(width - f * MarkLayout.MARGIN, place.x + f * MarkLayout.WIDTH, 0.01f)
            assertEquals(f * MarkLayout.MARGIN, place.y - f * MarkLayout.DESCENT, 0.01f)
        }
        // On a page much wider than tall, the mark stays low on the page.
        val strip = MarkLayout.place(MarkStyle(size = 100), 2000f, 100f)
        assertEquals(25f, strip.fontSize, 0.01f)
    }

    /** Where [layer]'s contours start and close, and the points they pass, at [fontSize]. */
    private class Traced(layer: MarkLayer, fontSize: Float = 1f) : MarkLayout.Pen {
        var starts = 0
        var closes = 0
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()

        init {
            layer.draw(this, fontSize)
        }

        private fun at(vararg xy: Float) {
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
    }

    /**
     * The mark is outlines, filled shapes: the word in Segoe UI Semibold as the banners set it, in the
     * logo's deep purple, then the logo in its colours to the word's right, all within the bounds the
     * layout keeps from the page's edges. The PDF's content draws the same contours, each closed, and
     * fills them, layer by layer.
     */
    @Test fun theMarkIsOutlinesWithinItsBounds() {
        val layers = MarkLayout.LAYERS
        assertEquals(listOf(0x4A3F6B, 0xE8DEF8, 0x4A3F6B, 0xFFFFFF, 0x4A3F6B), layers.map { it.rgb })
        assertEquals(listOf(1f, 1f, 1f, 0.55f, 0.4f), layers.map { it.alpha })
        val traced = layers.map { Traced(it) }
        // The word: U, s, u, m: one contour each; g, a, i: two. The circle, the disc and the sheet: one
        // each; the sheet's outline: a ring, two.
        assertEquals(listOf(10, 1, 1, 1, 2), traced.map { it.starts })
        assertEquals(traced.map { it.starts }, traced.map { it.closes })
        assertEquals(MarkLayout.INK_LEFT, traced.minOf { it.xs.min() }, 0.0001f)
        assertEquals(MarkLayout.INK_RIGHT, traced.maxOf { it.xs.max() }, 0.0001f)
        assertEquals(-MarkLayout.DESCENT, traced.minOf { it.ys.min() }, 0.0001f)
        assertEquals(MarkLayout.INK_TOP, traced.maxOf { it.ys.max() }, 0.0001f)
        assertTrue(MarkLayout.INK_LEFT > 0 && MarkLayout.INK_RIGHT <= MarkLayout.WIDTH)
        // The word is the "g" lowest; the logo is to its right, no lower, and reaches highest.
        val word = traced[0]
        val logo = traced.drop(1)
        assertEquals(-MarkLayout.DESCENT, word.ys.min(), 0.0001f)
        assertTrue(logo.all { it.xs.min() > word.xs.max() && it.ys.min() > -MarkLayout.DESCENT })
        assertEquals(MarkLayout.INK_TOP, logo.first().ys.max(), 0.0001f)
        // Segoe UI Semibold's letters, each 1/96 of the font size closer than the font sets them, as in
        // the banners: the word is 8170 of the font's 2048 units wide, its "i" the last 535, of which
        // its ink takes the first 409; the six gaps between the letters bring it 6 × 2048/96 back.
        assertEquals((8170f - 535 + 409 - 6 * 2048f / 96) / 2048, word.xs.max(), 0.0001f)
        // At a size, as the preview draws them: the same outlines, that much bigger.
        assertEquals(MarkLayout.INK_RIGHT * 12, Traced(layers[1], 12f).xs.max(), 0.001f)

        val pdf = MarkLayout.pdfMark().lines().filter { it.isNotEmpty() }
        assertEquals("0.00048828125 0 0 0.00048828125 0 0 cm", pdf.first())
        assertEquals(traced.sumOf { it.starts }, pdf.count { it.endsWith(" m") })
        assertEquals(traced.sumOf { it.closes }, pdf.count { it == "h" })
        assertEquals(layers.size, pdf.count { it == "f" })
        assertEquals(listOf("0.2902 0.2471 0.4196 rg", "0.9098 0.8706 0.9725 rg", "0.2902 0.2471 0.4196 rg", "1 1 1 rg", "0.2902 0.2471 0.4196 rg"), pdf.filter { it.endsWith(" rg") })
        // The sheet and its outline are painted at their own opacity, set by name.
        assertEquals(listOf("/L3 gs", "/L4 gs"), pdf.filter { it.endsWith(" gs") })
        assertEquals("Q", pdf.last())
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
