package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The background a block of text sits on and the colour of its letters, on small drawn pictures. */
class PageColorsTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun hex(color: Int) = "%08X".format(color)

    /** A picture [width] × [height] of [paper]. */
    private class Picture(val width: Int, val height: Int, paper: Int) {
        val pixels = IntArray(width * height) { paper }

        fun fill(left: Int, top: Int, right: Int, bottom: Int, color: (x: Int, y: Int) -> Int) {
            for (y in top until bottom) for (x in left until right) pixels[y * width + x] = color(x, y)
        }

        /**
         * Letters, as upright strokes 2 pixels wide every 6, in [ink], each with a pixel of [edge] on
         * either side (anti-aliasing), from [left] to [right] and [top] to [bottom].
         */
        fun letters(left: Int, top: Int, right: Int, bottom: Int, ink: Int, edge: Int) = fill(left, top, right, bottom) { x, y ->
            when ((x - left) % 6) {
                1, 2 -> ink
                0, 3 -> edge
                else -> pixels[y * width + x]
            }
        }

        fun background(left: Int, top: Int, right: Int, bottom: Int) = PageColors.background(pixels, width, height, left, top, right, bottom)

        fun ink(left: Int, top: Int, right: Int, bottom: Int, background: Int) = PageColors.ink(pixels, width, height, left, top, right, bottom, background)
    }

    @Test fun blackTextOnAWhitePage() {
        val page = Picture(100, 60, PageColors.WHITE)
        page.letters(20, 20, 80, 40, PageColors.BLACK, rgb(128, 128, 128))
        val paper = page.background(20, 20, 80, 40)
        assertEquals(hex(PageColors.WHITE), hex(paper))
        // The strokes' cores, not their grey edges.
        assertEquals(hex(PageColors.BLACK), hex(page.ink(20, 20, 80, 40, paper)))
    }

    @Test fun darkBlueTextOnAYellowBand() {
        val page = Picture(120, 80, PageColors.WHITE)
        // A yellow band with a little noise in it, as a scan or a JPEG has: one colour at 5 bits a channel.
        page.fill(0, 10, 120, 70) { x, y -> if ((x + y) % 2 == 0) rgb(255, 230, 0) else rgb(253, 228, 2) }
        page.letters(20, 25, 100, 55, rgb(0, 0, 139), rgb(128, 115, 70))
        val band = page.background(20, 25, 100, 55)
        assertEquals(hex(rgb(254, 229, 1)), hex(band))
        assertEquals(hex(rgb(0, 0, 139)), hex(page.ink(20, 25, 100, 55, band)))
    }

    @Test fun whiteTextOnADarkBackground() {
        val dark = rgb(20, 24, 32)
        val page = Picture(80, 40, dark)
        page.letters(10, 10, 70, 30, PageColors.WHITE, rgb(140, 140, 144))
        val background = page.background(10, 10, 70, 30)
        assertEquals(hex(dark), hex(background))
        assertEquals(hex(PageColors.WHITE), hex(page.ink(10, 10, 70, 30, background)))
    }

    @Test fun aRuleThroughTheRingDoesNotChangeTheBackground() {
        val page = Picture(100, 60, PageColors.WHITE)
        // A table's rule along the block's left side, in the ring.
        page.fill(17, 0, 19, 60) { _, _ -> PageColors.BLACK }
        page.letters(20, 20, 80, 40, PageColors.BLACK, rgb(128, 128, 128))
        assertEquals(hex(PageColors.WHITE), hex(page.background(20, 20, 80, 40)))
    }

    @Test fun aRectangleCoveringThePictureSitsOnWhite() {
        val page = Picture(30, 20, rgb(10, 10, 10))
        assertEquals(hex(PageColors.WHITE), hex(page.background(0, 0, 30, 20)))
        assertEquals(hex(PageColors.WHITE), hex(page.background(-50, -50, 500, 500)))
        // Wholly off the picture: nothing of its ring is on it either.
        assertEquals(hex(PageColors.WHITE), hex(page.background(100, 100, 120, 110)))
        // No picture at all.
        assertEquals(hex(PageColors.WHITE), hex(PageColors.background(IntArray(0), 0, 0, 0, 0, 10, 10)))
    }

    @Test fun aRectangleAtThePicturesEdgeIsMeasuredByWhatOfItsRingIsOnIt() {
        val grey = rgb(200, 200, 200)
        val page = Picture(60, 40, grey)
        // A block in the top-left corner: only the ring's right and bottom parts are on the picture.
        page.letters(0, 0, 30, 10, PageColors.BLACK, rgb(100, 100, 100))
        assertEquals(hex(grey), hex(page.background(0, 0, 30, 10)))
        // A rectangle given right to left and bottom to top is the same rectangle.
        assertEquals(hex(grey), hex(page.background(30, 10, 0, 0)))
        // A block a pixel wide and none tall still has a ring.
        assertEquals(hex(grey), hex(page.background(40, 20, 41, 20)))
        // A pixel that isn't opaque shows as on white paper.
        val clear = Picture(20, 20, 0x00000000)
        assertEquals(hex(PageColors.WHITE), hex(clear.background(5, 5, 15, 15)))
    }

    @Test fun inkKeepsAContrastOfThreeToOne() {
        // Yellow letters on white are far from it in colour but hardly darker: they are written black.
        val page = Picture(60, 30, PageColors.WHITE)
        page.letters(10, 10, 50, 20, rgb(255, 255, 0), rgb(255, 255, 128))
        assertTrue(PageColors.contrastRatio(rgb(255, 255, 0), PageColors.WHITE) < PageColors.MIN_CONTRAST)
        assertEquals(hex(PageColors.BLACK), hex(page.ink(10, 10, 50, 20, PageColors.WHITE)))
        // Dark grey on light grey keeps its own colour.
        val grey = Picture(60, 30, rgb(220, 220, 220))
        grey.letters(10, 10, 50, 20, rgb(60, 60, 60), rgb(140, 140, 140))
        assertEquals(hex(rgb(60, 60, 60)), hex(grey.ink(10, 10, 50, 20, rgb(220, 220, 220))))
    }

    @Test fun aBlockWithoutInkOfItsOwnGetsBlackOrWhite() {
        val light = Picture(40, 20, rgb(240, 240, 230))
        assertEquals(hex(PageColors.BLACK), hex(light.ink(5, 5, 35, 15, rgb(240, 240, 230))))
        val dark = Picture(40, 20, rgb(30, 30, 60))
        assertEquals(hex(PageColors.WHITE), hex(dark.ink(5, 5, 35, 15, rgb(30, 30, 60))))
        // Two red pixels are too few to be letters.
        val bright = Picture(40, 20, PageColors.WHITE)
        bright.fill(10, 10, 12, 11) { _, _ -> rgb(200, 0, 0) }
        assertEquals(hex(PageColors.BLACK), hex(bright.ink(5, 5, 35, 15, PageColors.WHITE)))
        // A mid grey background, where white would be too faint: black.
        assertEquals(hex(PageColors.BLACK), hex(PageColors.ink(IntArray(0), 0, 0, 0, 0, 0, 0, rgb(150, 150, 150))))
    }

    @Test fun contrastIsMeasuredAsWcagDoes() {
        assertEquals(21.0, PageColors.contrastRatio(PageColors.BLACK, PageColors.WHITE), 1e-9)
        assertEquals(1.0, PageColors.contrastRatio(rgb(90, 30, 200), rgb(90, 30, 200)), 1e-9)
        // #767676 on white is WCAG's well-known 4.54:1.
        assertEquals(4.54, PageColors.contrastRatio(rgb(0x76, 0x76, 0x76), PageColors.WHITE), 0.01)
    }
}
