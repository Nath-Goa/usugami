package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignatureLayoutTest {
    private val a4 = 595f to 842f
    private val landscape = 842f to 595f

    private fun assertArea(expected: Area, actual: Area) {
        assertEquals("x", expected.x, actual.x, 0.01f)
        assertEquals("y", expected.y, actual.y, 0.01f)
        assertEquals("width", expected.width, actual.width, 0.01f)
        assertEquals("height", expected.height, actual.height, 0.01f)
    }

    /** Whether the signature [placement] puts on a [page] lies wholly on it. */
    private fun onPage(placement: SignaturePlacement, page: Pair<Float, Float>, aspect: Float): Boolean {
        val area = SignatureLayout.pdfArea(placement, page.first, page.second, aspect)
        val slack = 0.01f
        return area.x >= -slack && area.y >= -slack && area.x + area.width <= page.first + slack && area.y + area.height <= page.second + slack
    }

    @Test fun inkBoundsCoverEveryStrokeAndThePen() {
        // Two straight strokes and a dot.
        val strokes = listOf(floatArrayOf(100f, 200f, 300f, 150f), floatArrayOf(250f, 260f, 260f, 240f), floatArrayOf(400f, 180f))
        assertArea(Area(95f, 145f, 310f, 120f), SignatureLayout.inkBounds(strokes, penWidth = 10f)!!)
        // A single dot is as big as the pen.
        assertArea(Area(495.5f, 245.5f, 9f, 9f), SignatureLayout.inkBounds(listOf(floatArrayOf(500f, 250f)))!!)
    }

    @Test fun inkBoundsFollowTheSmoothedCurve() {
        // From (0, 0) the pen bends towards (100, 100) on its way to (150, 50), halfway to the last
        // point, then goes straight to (200, 0). That bend peaks two thirds of the way down to 100.
        val bounds = SignatureLayout.inkBounds(listOf(floatArrayOf(0f, 0f, 100f, 100f, 200f, 0f)), penWidth = 0f)!!
        assertArea(Area(0f, 0f, 200f, 200f / 3), bounds)
        // Points along a straight line give that line.
        val straight = SignatureLayout.inkBounds(listOf(floatArrayOf(10f, 10f, 20f, 20f, 30f, 30f, 40f, 40f)), penWidth = 0f)!!
        assertArea(Area(10f, 10f, 30f, 30f), straight)
    }

    @Test fun nothingDrawnHasNoBounds() {
        assertNull(SignatureLayout.inkBounds(emptyList()))
        assertNull(SignatureLayout.inkBounds(listOf(FloatArray(0))))
    }

    @Test fun startsInTheLowerRight() {
        val (w, h) = a4
        val placement = SignatureLayout.default(w, h, aspect = 3f)
        val area = SignatureLayout.pdfArea(placement, w, h, 3f)
        // A third of the shorter side wide, and a margin from the right and bottom edges.
        assertEquals(0.32f * 595f, area.width, 0.01f)
        assertEquals(area.width / 3f, area.height, 0.01f)
        assertEquals(0.08f * 595f, w - area.x - area.width, 0.01f)
        assertEquals(0.08f * 595f, area.y, 0.01f)
        assertTrue(placement.centerX > 0.5f && placement.centerY > 0.5f)
    }

    @Test fun aTallSignatureStartsSmallerSoItIsNotHuge() {
        val (w, h) = a4
        val area = SignatureLayout.pdfArea(SignatureLayout.default(w, h, aspect = 1f), w, h, 1f)
        assertEquals(0.12f * 842f, area.height, 0.01f)
        assertEquals(area.height, area.width, 0.01f)
    }

    @Test fun landscapePagesUseTheShorterSide() {
        val (w, h) = landscape
        val area = SignatureLayout.pdfArea(SignatureLayout.default(w, h, aspect = 3f), w, h, 3f)
        assertEquals(0.32f * 595f, area.width, 0.01f)
        assertEquals(0.08f * 595f, area.y, 0.01f)
    }

    @Test fun widthGoesFromHalfAnInchToTheWholePage() {
        val wide = SignatureLayout.widthRange(595f, 842f, aspect = 3f)
        assertEquals(36f / 595f, wide.start, 0.0001f)
        assertEquals(1f, wide.endInclusive, 0.0001f)
        // A signature taller than it is wide is limited by the page's height instead.
        val tall = SignatureLayout.widthRange(595f, 842f, aspect = 0.5f)
        assertEquals(842f * 0.5f / 595f, tall.endInclusive, 0.0001f)
        // On a page smaller than half an inch the smallest is as big as fits.
        val tiny = SignatureLayout.widthRange(30f, 10f, aspect = 1f)
        assertEquals(tiny.endInclusive, tiny.start, 0.0001f)
    }

    @Test fun clampKeepsTheSignatureOnThePage() {
        for (page in listOf(a4, landscape, 300f to 300f, 100f to 40f)) {
            for (aspect in listOf(0.3f, 1f, 4f, 12f)) {
                for (wanted in listOf(
                    SignaturePlacement(0.5f, 0.5f, 0.3f),
                    SignaturePlacement(-1f, 2f, 0.4f),
                    SignaturePlacement(1.2f, -0.3f, 5f),
                    SignaturePlacement(0.99f, 0.99f, 0f),
                )) {
                    val placement = SignatureLayout.clamp(wanted, page.first, page.second, aspect)
                    assertTrue("$wanted with aspect $aspect is off the $page page as $placement", onPage(placement, page, aspect))
                    assertTrue(placement.width in SignatureLayout.widthRange(page.first, page.second, aspect))
                }
                assertTrue(onPage(SignatureLayout.default(page.first, page.second, aspect), page, aspect))
            }
        }
    }

    @Test fun clampLeavesAPlacementThatFitsAlone() {
        val placement = SignaturePlacement(0.4f, 0.7f, 0.25f)
        assertEquals(placement, SignatureLayout.clamp(placement, 595f, 842f, 3f))
    }

    @Test fun pdfAreaMeasuresFromTheBottom() {
        // Centred at a quarter from the left and a quarter from the top, a fifth of the page wide.
        val area = SignatureLayout.pdfArea(SignaturePlacement(0.25f, 0.25f, 0.2f), 600f, 800f, aspect = 2f)
        assertArea(Area(150f - 60f, 600f - 30f, 120f, 60f), area)
    }

    @Test fun imageIsPrintSharpButLimited() {
        // 2.5 by 1 inch at 300 dpi.
        assertEquals(750 to 300, SignatureLayout.pixels(180f, 72f))
        // A whole A3 page wide would be 3508 pixels: kept to 2400.
        val (width, height) = SignatureLayout.pixels(842f, 280f)
        assertEquals(2400, width)
        assertEquals(798, height)
        assertEquals(1 to 1, SignatureLayout.pixels(0.01f, 0.01f))
    }
}
