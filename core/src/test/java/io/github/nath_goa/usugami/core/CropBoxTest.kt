package io.github.nath_goa.usugami.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropBoxTest {

    private val share = CropShare(left = 0.1f, top = 0.2f, right = 0.7f, bottom = 0.9f)

    // A crop box that doesn't start at 0,0: 50,100 to 450,700, on a media box with room round it.
    private fun frame(turn: Int) = PageFrame(50f, 100f, 400f, 600f, turn)
    private val media = floatArrayOf(0f, 0f, 500f, 800f)

    private fun assertBox(expected: FloatArray, actual: FloatArray?, what: String = "") {
        assertNotNull(what, actual)
        assertArrayEquals(what, expected, actual!!, 1e-3f)
    }

    private fun assertShare(expected: CropShare, actual: CropShare) =
        assertArrayEquals("$actual", CropBoxes.pack(expected), CropBoxes.pack(actual), 1e-6f)

    @Test
    fun theBoxIsTheShareOfThePageAsShownForEveryTurn() {
        // Worked by hand from the page's sides: shown top 20% off, bottom 10% off, left 10%, right 30%.
        // Upright: the shown top is the page's top (700), its left the page's left (50).
        assertBox(floatArrayOf(90f, 160f, 330f, 580f), CropBoxes.pageBox(frame(0), share, media), "0")
        // A quarter turn: the shown top is the page's left side, the shown left its bottom.
        assertBox(floatArrayOf(130f, 160f, 410f, 520f), CropBoxes.pageBox(frame(90), share, media), "90")
        // Upside down: the shown top is the page's bottom, the shown left its right side.
        assertBox(floatArrayOf(170f, 220f, 410f, 640f), CropBoxes.pageBox(frame(180), share, media), "180")
        // Three quarters: the shown top is the page's right side, the shown left its top.
        assertBox(floatArrayOf(90f, 280f, 370f, 640f), CropBoxes.pageBox(frame(270), share, media), "270")
        // A box that is not square and not at 0,0, a quarter turn: shown left is the bottom, shown top the left.
        assertBox(
            floatArrayOf(180f, 70f, 460f, 170f),
            CropBoxes.pageBox(PageFrame(100f, 50f, 400f, 200f, 90), CropShare(0.1f, 0.2f, 0.6f, 0.9f), floatArrayOf(0f, 0f, 600f, 300f)),
        )
    }

    @Test
    fun theWholePageIsTheFrameItselfAndNothingLeavesTheMediaBox() {
        for (turn in listOf(0, 90, 180, 270)) {
            assertBox(floatArrayOf(50f, 100f, 450f, 700f), CropBoxes.pageBox(frame(turn), CropShare.WHOLE, media), "$turn")
            // A media box smaller than the crop box (PDFBox cuts it, a broken file may not): the box stays on it.
            val small = floatArrayOf(60f, 110f, 300f, 690f)
            for (s in listOf(CropShare.WHOLE, share, CropShare(0f, 0f, 0.5f, 0.5f), CropShare(0.5f, 0.5f, 1f, 1f))) {
                val box = CropBoxes.pageBox(frame(turn), s, small)
                assertNotNull("$turn $s", box)
                assertTrue("$turn $s: ${box!!.toList()}", Areas.inside(box, small))
            }
        }
        assertBox(floatArrayOf(60f, 110f, 300f, 690f), CropBoxes.pageBox(frame(90), CropShare.WHOLE, floatArrayOf(60f, 110f, 300f, 690f)))
        assertNull("A box wholly off the media box", CropBoxes.pageBox(frame(0), CropShare(0f, 0f, 0.1f, 0.1f), floatArrayOf(300f, 0f, 500f, 400f)))
    }

    @Test
    fun aBoxUnderThreePointsLeavesThePageAsItIs() {
        val tiny = PageFrame(0f, 0f, 40f, 50f, 90)
        val thin = CropShare(0f, 0f, 0.05f, 1f) // 5% of the page shown 50 wide: 2.5 points
        assertNull(CropBoxes.pageBox(tiny, thin, floatArrayOf(0f, 0f, 40f, 50f)))
        assertNotNull(CropBoxes.pageBox(tiny, CropShare(0f, 0f, 0.1f, 1f), floatArrayOf(0f, 0f, 40f, 50f)))
        assertEquals(PageLayout.MIN_PAGE_SIDE.toFloat(), CropBoxes.MIN_SIDE)
    }

    @Test
    fun aSideStopsAtThePagesEdgeAndShortOfTheSideOpposite() {
        val start = CropShare(0.2f, 0.2f, 0.8f, 0.8f)
        assertEquals(0f, CropBoxes.dragged(start, CropHandle.LEFT, -0.5f, 0f).left)
        assertEquals(1f, CropBoxes.dragged(start, CropHandle.BOTTOM, 0f, 0.5f).bottom)
        val far = CropBoxes.dragged(start, CropHandle.LEFT, 0.9f, 0f)
        assertEquals(0.75f, far.left, 1e-6f)
        assertEquals(0.8f, far.right)
        assertEquals(CropBoxes.MIN_SHARE, far.width, 1e-6f)
        val up = CropBoxes.dragged(start, CropHandle.BOTTOM, 0f, -0.9f)
        assertEquals(0.25f, up.bottom, 1e-6f)
        assertEquals(0.2f, up.top)
        // Along itself a side doesn't move, and the other sides stay where they were.
        assertShare(CropShare(0.3f, 0.2f, 0.8f, 0.8f), CropBoxes.dragged(start, CropHandle.LEFT, 0.1f, 0.3f))
        assertShare(CropShare(0.2f, 0f, 0.8f, 0.8f), CropBoxes.dragged(start, CropHandle.TOP, 0.3f, -0.4f))
    }

    @Test
    fun aCornerMovesBothItsSides() {
        val start = CropShare(0.2f, 0.2f, 0.8f, 0.8f)
        val moved = CropBoxes.dragged(start, CropHandle.TOP_RIGHT, 0.1f, -0.1f)
        assertEquals(0.9f, moved.right, 1e-6f)
        assertEquals(0.1f, moved.top, 1e-6f)
        assertEquals(0.2f, moved.left)
        assertEquals(0.8f, moved.bottom)
        val squeezed = CropBoxes.dragged(start, CropHandle.BOTTOM_LEFT, 2f, -2f)
        assertEquals(0.75f, squeezed.left, 1e-6f)
        assertEquals(0.25f, squeezed.bottom, 1e-6f)
        assertEquals("A move that is not a number", start, CropBoxes.dragged(start, CropHandle.TOP_LEFT, Float.NaN, Float.NaN))
    }

    @Test
    fun aCornerWinsOverItsSidesAndTheMiddleGrabsNothing() {
        val box = CropShare(0.2f, 0.2f, 0.8f, 0.8f)
        // Within reach of the top-left corner, nearer the left side's line than the corner.
        assertEquals(CropHandle.TOP_LEFT, CropBoxes.grab(box, 0.201f, 0.23f, 0.04f, 0.04f))
        assertEquals(CropHandle.BOTTOM_RIGHT, CropBoxes.grab(box, 0.83f, 0.79f, 0.04f, 0.04f))
        assertEquals(CropHandle.LEFT, CropBoxes.grab(box, 0.21f, 0.5f, 0.04f, 0.04f))
        assertEquals(CropHandle.BOTTOM, CropBoxes.grab(box, 0.5f, 0.78f, 0.04f, 0.04f))
        assertNull("The middle", CropBoxes.grab(box, 0.5f, 0.5f, 0.04f, 0.04f))
        assertNull("Beyond a side's end", CropBoxes.grab(box, 0.2f, 0.1f, 0.04f, 0.04f))
        // Reaches that differ each way, as on a tall page: a side is within reach of its own way.
        assertEquals(CropHandle.TOP, CropBoxes.grab(box, 0.5f, 0.205f, 0.1f, 0.01f))
        assertNull(CropBoxes.grab(box, 0.5f, 0.22f, 0.1f, 0.01f))
        // A narrow box, both sides within reach: the nearer one.
        assertEquals(CropHandle.RIGHT, CropBoxes.grab(CropShare(0.2f, 0.2f, 0.26f, 0.8f), 0.24f, 0.5f, 0.05f, 0.01f))
        assertEquals(CropHandle.LEFT, CropBoxes.grab(CropShare(0.2f, 0.2f, 0.26f, 0.8f), 0.22f, 0.5f, 0.05f, 0.01f))
    }

    @Test
    fun onTheWholePageEverythingIsGrabbedFromInsideIt() {
        val reach = 0.05f
        val whole = CropShare.WHOLE
        assertEquals(CropHandle.TOP_LEFT, CropBoxes.grab(whole, 0.02f, 0.03f, reach, reach))
        assertEquals(CropHandle.TOP_RIGHT, CropBoxes.grab(whole, 0.98f, 0.01f, reach, reach))
        assertEquals(CropHandle.BOTTOM_RIGHT, CropBoxes.grab(whole, 0.97f, 0.99f, reach, reach))
        assertEquals(CropHandle.BOTTOM_LEFT, CropBoxes.grab(whole, 0f, 1f, reach, reach))
        assertEquals(CropHandle.LEFT, CropBoxes.grab(whole, 0.03f, 0.5f, reach, reach))
        assertEquals(CropHandle.TOP, CropBoxes.grab(whole, 0.5f, 0.04f, reach, reach))
        assertEquals(CropHandle.RIGHT, CropBoxes.grab(whole, 0.96f, 0.5f, reach, reach))
        assertEquals(CropHandle.BOTTOM, CropBoxes.grab(whole, 0.5f, 0.99f, reach, reach))
        assertNull(CropBoxes.grab(whole, 0.5f, 0.5f, reach, reach))
    }

    @Test
    fun aStepAtItsLimitChangesNothing() {
        var box = CropShare.WHOLE
        assertEquals("Less off a side already at the edge", box, CropBoxes.stepped(box, CropHandle.LEFT, more = false))
        assertEquals(box, CropBoxes.stepped(box, CropHandle.BOTTOM, more = false))
        box = CropBoxes.stepped(box, CropHandle.TOP, more = true)
        assertEquals(CropBoxes.STEP, box.top, 1e-6f)
        box = CropBoxes.stepped(box, CropHandle.RIGHT, more = true)
        assertEquals(1f - CropBoxes.STEP, box.right, 1e-6f)
        // Pressed until it stops: then a press changes nothing, and the box is the smallest it may be.
        repeat(200) { box = CropBoxes.stepped(box, CropHandle.LEFT, more = true) }
        assertEquals(box, CropBoxes.stepped(box, CropHandle.LEFT, more = true))
        assertEquals(CropBoxes.MIN_SHARE, box.width, 1e-5f)
        assertFalse(box.isWhole)
        // Pressed back until it stops at the page's edge.
        repeat(200) { box = CropBoxes.stepped(box, CropHandle.LEFT, more = false) }
        assertEquals(0f, box.left)
        assertEquals(box, CropBoxes.stepped(box, CropHandle.LEFT, more = false))
        assertTrue(CropBoxes.stepped(CropBoxes.stepped(box, CropHandle.TOP, false), CropHandle.RIGHT, false).isWhole)
        // Made smallest by dragging the side opposite, where Float can't give the limit back exactly
        // (left 0.09999999 after ten presses, right then 0.14999999): the step still changes nothing.
        var left = CropShare.WHOLE
        repeat(10) { left = CropBoxes.stepped(left, CropHandle.LEFT, more = true) }
        left = CropBoxes.dragged(left, CropHandle.RIGHT, -2f, 0f)
        assertEquals(left, CropBoxes.stepped(left, CropHandle.LEFT, more = true))
        var top = CropShare.WHOLE
        repeat(10) { top = CropBoxes.stepped(top, CropHandle.TOP, more = true) }
        top = CropBoxes.dragged(top, CropHandle.BOTTOM, 0f, -2f)
        assertEquals(top, CropBoxes.stepped(top, CropHandle.TOP, more = true))
    }

    @Test
    fun aSavedBoxComesBackAndAnythingElseIsTheWholePage() {
        assertEquals(share, CropBoxes.unpack(CropBoxes.pack(share)))
        // Boxes a drag left at their smallest or at the page's edges, where float rounding may leave
        // one a hair under 5%: none of them comes back as the whole page after a rotation.
        for (start in listOf(CropShare(0.13f, 0.37f, 0.71f, 0.93f), CropShare(0.333f, 0.1f, 0.777f, 0.6f), CropShare.WHOLE)) {
            for (handle in CropHandle.entries) for (dx in listOf(-2f, 2f)) for (dy in listOf(-2f, 2f)) {
                val dragged = CropBoxes.dragged(start, handle, dx, dy)
                assertTrue("$dragged", dragged.width > CropBoxes.MIN_SHARE - 1e-5f && dragged.height > CropBoxes.MIN_SHARE - 1e-5f)
                assertEquals("$start $handle $dx $dy", dragged, CropBoxes.unpack(CropBoxes.pack(dragged)))
            }
        }
        for (bad in listOf(
            floatArrayOf(), floatArrayOf(0.1f, 0.1f, 0.9f), floatArrayOf(0.1f, 0.1f, 0.9f, 0.9f, 0f),
            floatArrayOf(Float.NaN, 0f, 1f, 1f), floatArrayOf(0f, 0f, Float.POSITIVE_INFINITY, 1f),
            floatArrayOf(-0.1f, 0f, 1f, 1f), floatArrayOf(0f, 0f, 1.2f, 1f),
            floatArrayOf(0.9f, 0f, 0.1f, 1f), floatArrayOf(0.5f, 0.5f, 0.52f, 1f), floatArrayOf(0f, 0.5f, 1f, 0.53f),
        )) {
            assertEquals(bad.toList().toString(), CropShare.WHOLE, CropBoxes.unpack(bad))
        }
    }

    @Test
    fun theWholePageGiveOrTakeARoundingError() {
        assertTrue(CropShare.WHOLE.isWhole)
        assertTrue(CropShare(0.0005f, 0f, 0.9995f, 1f).isWhole)
        assertFalse(CropShare(0.01f, 0f, 1f, 1f).isWhole)
        assertFalse(CropShare(0f, 0f, 1f, 0.99f).isWhole)
        assertEquals(0.6f, share.width, 1e-6f)
        assertEquals(0.7f, share.height, 1e-6f)
    }
}
