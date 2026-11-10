package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCropTest {
    private fun quad(vararg xy: Float) = CropQuad(List(4) { CropPoint(xy[2 * it], xy[2 * it + 1]) })

    private fun assertSame(expected: CropQuad, actual: CropQuad, tolerance: Float = 1e-5f) {
        for (i in 0..3) {
            assertEquals("x of corner $i in $actual", expected[i].x, actual[i].x, tolerance)
            assertEquals("y of corner $i in $actual", expected[i].y, actual[i].y, tolerance)
        }
    }

    @Test fun cornersAreOrderedClockwiseFromTheTopLeft() {
        val page = quad(0.2f, 0.1f, 0.9f, 0.15f, 0.85f, 0.9f, 0.1f, 0.8f)
        // Dragged past each other, or found in another order.
        val shuffled = CropQuad(listOf(page[2], page[0], page[3], page[1]))
        assertSame(page, shuffled.ordered())
        assertSame(page, CropQuad(page.corners.reversed()).ordered())
        assertSame(page, page.ordered())
    }

    @Test fun aTiltedPageOnATallPhotoStartsAtItsOwnTopLeft() {
        // A 600 x 800 pixel page in the middle of a 1080 x 2400 photo, turned 30 degrees clockwise and
        // 30 degrees the other way: each from its own top-left corner.
        val right = quad(0.4446f, 0.2932f, 0.9257f, 0.4182f, 0.5554f, 0.7068f, 0.0743f, 0.5818f)
        val left = quad(0.0743f, 0.4182f, 0.5554f, 0.2932f, 0.9257f, 0.5818f, 0.4446f, 0.7068f)
        for (page in listOf(right, left)) {
            assertSame(page, CropQuad(page.corners.reversed()).ordered(1080, 2400))
            // Upright, not on its side.
            assertEquals(600 to 800, PageCrop.outputSize(page.ordered(1080, 2400), 1080, 2400))
        }
    }

    @Test fun turningAndTurningBackKeepsTheCorners() {
        val page = quad(0.2f, 0.1f, 0.9f, 0.15f, 0.85f, 0.9f, 0.1f, 0.8f)
        for (degrees in listOf(0, 90, 180, 270)) {
            val turn = ImageTransform.rotation(degrees)
            val shown = page.transformed(turn).ordered()
            assertSame(page, shown.transformed(turn.inverse()).ordered())
        }
        // A quarter turn clockwise: the top-left corner goes to the top right.
        val turned = page.transformed(ImageTransform.rotation(90))
        assertEquals(CropPoint(0.9f, 0.2f), turned[CropQuad.TOP_LEFT])
        assertSame(CropQuad.WHOLE, CropQuad.WHOLE.transformed(ImageTransform.rotation(90)).ordered())
    }

    @Test fun onlyAConvexShapeWithRealSidesIsAPage() {
        assertTrue(CropQuad.WHOLE.isUsable(100, 100))
        assertTrue(quad(0.2f, 0.1f, 0.9f, 0.15f, 0.85f, 0.9f, 0.1f, 0.8f).isUsable(1000, 1000))
        // Counter-clockwise is still a page.
        assertTrue(CropQuad(CropQuad.WHOLE.corners.reversed()).isUsable(100, 100))
        // Two corners swapped: the sides cross.
        assertFalse(quad(0f, 0f, 1f, 1f, 1f, 0f, 0f, 1f).isUsable(100, 100))
        // A corner pushed inside: an arrow head, not a page.
        assertFalse(quad(0f, 0f, 1f, 0f, 0.3f, 0.3f, 0f, 1f).isUsable(100, 100))
        // Three corners in a line.
        assertFalse(quad(0f, 0f, 0.5f, 0f, 1f, 0f, 0f, 1f).isUsable(100, 100))
        // Sides shorter than 16 pixels.
        assertFalse(quad(0.5f, 0.5f, 0.51f, 0.5f, 0.51f, 0.51f, 0.5f, 0.51f).isUsable(1000, 1000))
        assertTrue(quad(0.5f, 0.5f, 0.51f, 0.5f, 0.51f, 0.51f, 0.5f, 0.51f).isUsable(4000, 4000))
    }

    @Test fun cornersStayOnTheImage() {
        val moved = CropQuad.WHOLE.moved(CropQuad.TOP_LEFT, -0.1f, 0.2f)
        assertEquals(CropPoint(0f, 0.2f), moved[CropQuad.TOP_LEFT])
        assertEquals(CropPoint(1f, 1f), CropQuad.inset(0.1f).with(CropQuad.BOTTOM_RIGHT, 1.5f, 3f)[CropQuad.BOTTOM_RIGHT])
        assertEquals(CropQuad.WHOLE[CropQuad.TOP_RIGHT], moved[CropQuad.TOP_RIGHT])
    }

    @Test fun aDraggedSideMovesAcrossWithItsCornersOnTheSidesBeside() {
        // Narrower at the top, on a 1000 × 2000 image: the top side dragged up 100 pixels (and along
        // itself, which doesn't count) takes its corners up the slanting sides, closer together.
        val page = quad(0.3f, 0.2f, 0.7f, 0.2f, 0.9f, 0.8f, 0.1f, 0.8f)
        val moved = page.movedSide(CropQuad.TOP_SIDE, 0.05f, -0.05f, 1000, 2000)
        assertSame(quad(0.3f + 0.2f / 12, 0.15f, 0.7f - 0.2f / 12, 0.15f, 0.9f, 0.8f, 0.1f, 0.8f), moved)
    }

    @Test fun aSideDraggedAlongItselfStaysInPixelsNotFractions() {
        // On a 2000 × 1000 image, the right side runs 600 pixels across and 800 down: a drag of 60
        // and 80 pixels is along it, though as fractions (0.03, 0.08) it isn't.
        val page = quad(0.1f, 0.1f, 0.6f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
        assertSame(page, page.movedSide(CropQuad.RIGHT_SIDE, 0.03f, 0.08f, 2000, 1000))
    }

    @Test fun aSideMovesStraightAcrossWhenASideBesideIsNearlyParallel() {
        // The left side runs almost along the top: sliding the top-left corner along it would send
        // it far off.
        val page = quad(0.5f, 0.3f, 0.9f, 0.3f, 0.9f, 0.9f, 0f, 0.32f)
        assertSame(quad(0.5f, 0.35f, 0.9f, 0.35f, 0.9f, 0.9f, 0f, 0.32f), page.movedSide(CropQuad.TOP_SIDE, 0f, 0.05f, 1000, 1000))
    }

    @Test fun aSideMovesStraightAcrossWhenACornerWouldLeaveTheImageAndStaysOnIt() {
        // Wider at the top, near the image's top edge: up the slanting sides the corners would go off
        // the image, so they go straight up, and stop at the edge.
        val page = quad(0.05f, 0.05f, 0.95f, 0.05f, 0.7f, 0.9f, 0.3f, 0.9f)
        assertSame(quad(0.05f, 0f, 0.95f, 0f, 0.7f, 0.9f, 0.3f, 0.9f), page.movedSide(CropQuad.TOP_SIDE, 0f, -0.1f, 1000, 1000))
        // The bottom side, moved down a little, slides along the sides as usual.
        val down = page.movedSide(CropQuad.BOTTOM_SIDE, 0f, 0.02f, 1000, 1000)
        assertEquals(0.92f, down[CropQuad.BOTTOM_RIGHT].y, 1e-5f)
        assertTrue(down[CropQuad.BOTTOM_RIGHT].x < 0.7f && down[CropQuad.BOTTOM_LEFT].x > 0.3f)
    }

    @Test fun aQuarterTurnNamesEachCornerForWhereItLands() {
        val page = quad(0.2f, 0.1f, 0.9f, 0.15f, 0.85f, 0.9f, 0.1f, 0.8f)
        val turned = page.turned(90)
        // The top-left corner lands top right, and is named so.
        assertEquals(CropPoint(0.9f, 0.2f), turned[CropQuad.TOP_RIGHT])
        assertSame(page.transformed(ImageTransform.rotation(90)).ordered(), turned)
        // Turned back, or right round, the corners are where they were.
        assertSame(page, turned.turned(-90))
        assertSame(page, page.turned(180).turned(180))
        assertSame(page.turned(-90), page.turned(270))
    }

    @Test fun wholeAndInset() {
        assertTrue(CropQuad.WHOLE.isWhole())
        assertTrue(quad(0.001f, 0f, 1f, 0.0005f, 0.999f, 1f, 0f, 1f).isWhole())
        assertFalse(CropQuad.inset(PageCrop.INSET).isWhole())
        assertSame(quad(0.08f, 0.08f, 0.92f, 0.08f, 0.92f, 0.92f, 0.08f, 0.92f), CropQuad.inset(0.08f))
    }

    @Test fun theStraightenedPageIsShapedByItsEdgesAndAsLargeAsTheLongest() {
        // A rectangle, on a 1000 × 2000 image.
        assertEquals(800 to 1600, PageCrop.outputSize(quad(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f), 1000, 2000))
        // Photographed at a slant: the bottom edge is 900 pixels, the top 500, the sides 1020 long.
        // The page is shaped as the average edges say (700 × 1020), as large as the bottom edge.
        val slanted = quad(0.25f, 0f, 0.75f, 0f, 0.95f, 0.5f, 0.05f, 0.5f)
        val (w, h) = PageCrop.outputSize(slanted, 1000, 2000)
        assertEquals(900, w)
        assertEquals(1020.0 * 900 / 700, h.toDouble(), 1.0)
        // A tilted square keeps its shape.
        val tilted = quad(0.5f, 0.1f, 0.9f, 0.5f, 0.5f, 0.9f, 0.1f, 0.5f)
        val (tw, th) = PageCrop.outputSize(tilted, 1000, 1000)
        assertEquals(566, tw)
        assertEquals(566, th)
    }

    @Test fun aLargePageIsShrunkToTheLongSide() {
        assertEquals(3000 to 2250, PageCrop.outputSize(CropQuad.WHOLE, 8000, 6000))
        assertEquals(1500 to 3000, PageCrop.outputSize(CropQuad.WHOLE, 4000, 8000))
        assertEquals(1000 to 2000, PageCrop.outputSize(CropQuad.WHOLE, 1000, 2000, maxSide = 3000))
        // Never less than a pixel.
        assertEquals(1 to 3000, PageCrop.outputSize(quad(0f, 0f, 0.0001f, 0f, 0.0001f, 1f, 0f, 1f), 100, 100_000))
    }

    @Test fun cornersOnTheStoredPhotoFollowItsExifOrientation() {
        // Stored 400 × 300, shown upright 300 × 400 (turned a quarter clockwise, EXIF 6).
        val page = quad(0.1f, 0.2f, 0.9f, 0.2f, 0.9f, 0.8f, 0.1f, 0.8f)
        val stored = PageCrop.storedCorners(page, ImageTransform.fromExif(6), 400, 300)
        // The page's top-left shows 10% from the left and 20% down: on the stored photo, that is 20%
        // across and 10% up from the bottom.
        val expected = floatArrayOf(80f, 270f, 80f, 30f, 320f, 30f, 320f, 270f)
        for (i in expected.indices) assertEquals("$i", expected[i], stored[i], 1e-3f)
        // Upright already: the same place.
        val same = PageCrop.storedCorners(page, ImageTransform.fromExif(1), 300, 400)
        assertEquals(30f, same[0], 1e-3f)
        assertEquals(80f, same[1], 1e-3f)
        // Mirrored (EXIF 2): the page's top-left is on the stored photo's right.
        val mirrored = PageCrop.storedCorners(page, ImageTransform.fromExif(2), 300, 400)
        assertEquals(270f, mirrored[0], 1e-3f)
        assertEquals(80f, mirrored[1], 1e-3f)
        // Every orientation: each corner comes back where it shows.
        for (orientation in 1..8) {
            val upright = ImageTransform.fromExif(orientation)
            val w = if (upright.swapsAxes) 300 else 400
            val h = if (upright.swapsAxes) 400 else 300
            val points = PageCrop.storedCorners(page, upright, w, h)
            val back = CropQuad(List(4) { CropPoint(points[2 * it] / w, points[2 * it + 1] / h) }).transformed(upright)
            assertSame(page, back)
        }
    }
}
