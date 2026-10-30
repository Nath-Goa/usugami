package io.github.nath_goa.pdfer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLayoutTest {
    private val eps = 1e-6

    @Test
    fun a4PortraitNoMarginFitsLandscapePhotoToWidth() {
        val g = PageLayout.compute(4000, 3000, LayoutSettings(PageSize.A4, PageOrientation.PORTRAIT, Margin.NONE))
        assertEquals(595.2756, g.pageWidth, 1e-4)
        assertEquals(841.8898, g.pageHeight, 1e-4)
        assertEquals(g.pageWidth, g.imageWidth, eps)
        assertEquals(g.pageWidth * 0.75, g.imageHeight, eps)
        assertEquals(0.0, g.imageX, eps)
        assertEquals((g.pageHeight - g.imageHeight) / 2, g.imageY, eps)
        assertEquals(g.imageY, g.imageTop, eps) // centred
    }

    @Test
    fun autoOrientationFollowsImage() {
        val auto = LayoutSettings(PageSize.LETTER, PageOrientation.AUTO, Margin.NONE)
        val wide = PageLayout.compute(300, 200, auto)
        assertEquals(792.0, wide.pageWidth, eps)
        assertEquals(612.0, wide.pageHeight, eps)
        val tall = PageLayout.compute(200, 300, auto)
        assertEquals(612.0, tall.pageWidth, eps)
        val square = PageLayout.compute(500, 500, auto)
        assertEquals(612.0, square.pageWidth, eps) // square images stay portrait
    }

    @Test
    fun landscapeAndMargins() {
        val g = PageLayout.compute(1000, 1000, LayoutSettings(PageSize.A4, PageOrientation.LANDSCAPE, Margin.BIG))
        assertEquals(841.8898, g.pageWidth, 1e-4)
        assertEquals(595.2756, g.pageHeight, 1e-4)
        assertEquals(595.2756 - 108, g.imageHeight, 1e-4)
        assertEquals(54.0, g.imageY, eps)
        assertEquals((g.pageWidth - g.imageWidth) / 2, g.imageX, eps)
    }

    @Test
    fun smallImagesAreScaledUpToFill() {
        val g = PageLayout.compute(10, 20, LayoutSettings(PageSize.A5, PageOrientation.PORTRAIT, Margin.SMALL))
        assertEquals(PageSize.A5.longSide - 36, g.imageHeight, 1e-6)
        assertEquals(g.imageHeight / 2, g.imageWidth, 1e-6)
    }

    @Test
    fun fitUsesPixelsAsPointsAndProportionalMargin() {
        val none = PageLayout.compute(1200, 800, LayoutSettings(PageSize.FIT, PageOrientation.PORTRAIT, Margin.NONE))
        assertEquals(PageGeometry(1200.0, 800.0, 0.0, 0.0, 1200.0, 800.0), none)

        val small = PageLayout.compute(1200, 800, LayoutSettings(PageSize.FIT, PageOrientation.LANDSCAPE, Margin.SMALL))
        val m = 18.0 / PageSize.A4.shortSide * 800
        assertEquals(1200 + 2 * m, small.pageWidth, eps)
        assertEquals(800 + 2 * m, small.pageHeight, eps)
        assertEquals(m, small.imageX, eps)
        assertEquals(m, small.imageY, eps)
        assertEquals(1200.0, small.imageWidth, eps)
    }

    @Test
    fun fitKeepsPagesWithinViewerLimits() {
        val huge = PageLayout.compute(30000, 10000, LayoutSettings(PageSize.FIT, margin = Margin.NONE))
        assertEquals(PageLayout.MAX_PAGE_SIDE, huge.pageWidth, eps)
        assertEquals(4800.0, huge.pageHeight, eps)

        val tiny = PageLayout.compute(1, 2, LayoutSettings(PageSize.FIT, margin = Margin.NONE))
        assertEquals(3.0, tiny.pageWidth, eps)
        assertEquals(6.0, tiny.pageHeight, eps)

        val withMargin = PageLayout.compute(20000, 20000, LayoutSettings(PageSize.FIT, margin = Margin.BIG))
        assertTrue(withMargin.pageWidth <= PageLayout.MAX_PAGE_SIDE + eps)
        assertEquals(withMargin.pageWidth - 2 * withMargin.imageX, withMargin.imageWidth, 1e-6)
    }

    @Test
    fun imageAlwaysInsidePage() {
        for (size in PageSize.entries) for (o in PageOrientation.entries) for (margin in Margin.entries) {
            for ((w, h) in listOf(1 to 1, 4000 to 3000, 3000 to 4000, 100 to 5000, 5000 to 100)) {
                val g = PageLayout.compute(w, h, LayoutSettings(size, o, margin))
                assertTrue(g.imageX >= -eps && g.imageY >= -eps)
                assertTrue(g.imageX + g.imageWidth <= g.pageWidth + eps)
                assertTrue(g.imageY + g.imageHeight <= g.pageHeight + eps)
                assertEquals(w.toDouble() / h, g.imageWidth / g.imageHeight, 1e-9 * w / h + 1e-9)
            }
        }
    }
}
