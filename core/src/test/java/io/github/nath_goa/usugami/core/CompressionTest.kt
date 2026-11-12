package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CompressionTest {

    @Test
    fun phonePhotoOfAPageOnA4() {
        // 3000x4000 portrait photo filling an A4 page (8.27 in wide) at 150 dpi -> 1240 px wide.
        val settings = LayoutSettings(PageSize.A4, PageOrientation.PORTRAIT, Margin.NONE)
        val scale = Compressor.scaleFor(3000, 4000, settings, Compression.RECOMMENDED)
        val (w, h) = Compressor.scaledSize(3000, 4000, scale)
        assertEquals(1240, w)
        assertEquals(1654, h)
    }

    @Test
    fun marginsAndLevelsChangeTheTarget() {
        val settings = LayoutSettings(PageSize.LETTER, PageOrientation.PORTRAIT, Margin.BIG)
        // Letter is 8.5 in wide; big margins leave 7 in.
        assertEquals(7 * 200 / 4000.0, Compressor.scaleFor(4000, 3000, settings, Compression.LESS), 1e-9)
        assertEquals(7 * 100 / 4000.0, Compressor.scaleFor(4000, 3000, settings, Compression.EXTREME), 1e-9)
    }

    @Test
    fun neverEnlarges() {
        val settings = LayoutSettings(PageSize.A4)
        assertEquals(1.0, Compressor.scaleFor(800, 600, settings, Compression.LESS), 0.0)
        assertEquals(1.0, Compressor.scaleFor(800, 600, LayoutSettings(PageSize.FIT), Compression.RECOMMENDED), 0.0)
    }

    @Test
    fun fitPagesCapTheLongSide() {
        val scale = Compressor.scaleFor(3000, 4000, LayoutSettings(PageSize.FIT), Compression.RECOMMENDED)
        val (w, h) = Compressor.scaledSize(3000, 4000, scale)
        assertEquals(1754, h) // 11.69 in at 150 dpi
        assertEquals(1315, w)
    }

    @Test
    fun fitPagesKeepLongScreenshotsReadable() {
        val fit = LayoutSettings(PageSize.FIT)
        // A 1080 px wide scrolling screenshot keeps its width, upright or sideways.
        assertEquals(1.0, Compressor.scaleFor(1080, 10000, fit, Compression.RECOMMENDED), 0.0)
        assertEquals(1.0, Compressor.scaleFor(10000, 1080, fit, Compression.RECOMMENDED), 0.0)
        // A wider one shrinks to A4's width at 150 dpi (8.27 in), not to a sliver.
        val scale = Compressor.scaleFor(3000, 12000, fit, Compression.RECOMMENDED)
        assertEquals(1240 to 4961, Compressor.scaledSize(3000, 12000, scale))
    }

    @Test
    fun anImageDrawnSmallKeepsTheDetailAsked() {
        // A card's side 85.6 mm (242.65 pt) wide, shown 3000 px wide, at 300 dpi (twice Recommended's).
        val placed = 85.6 * 72 / 25.4
        val scale = Compressor.scaleFor(3000, placed, Compression.RECOMMENDED, detail = 2.0)
        assertEquals(1011 to 638, Compressor.scaledSize(3000, 1893, scale))
        // Detail 1 is the level's own resolution, as for a page that size.
        assertEquals(scale / 2, Compressor.scaleFor(3000, placed, Compression.RECOMMENDED), 1e-12)
    }

    @Test
    fun anImageDrawnSmallIsNeverEnlarged() {
        assertEquals(1.0, Compressor.scaleFor(800, 242.65, Compression.LESS, detail = 2.0), 0.0)
        assertEquals(1.0, Compressor.scaleFor(1, 595.0, Compression.EXTREME), 0.0)
    }

    @Test
    fun savingsUnderOnePercentDontCount() {
        assertEquals(null, Compressor.savedPercent(1000, 1000))
        assertEquals(null, Compressor.savedPercent(1000, 995))
        assertEquals(1, Compressor.savedPercent(1000, 990))
        assertEquals(50, Compressor.savedPercent(1000, 500))
        assertEquals(null, Compressor.savedPercent(0, 0))
    }

    @Test
    fun scaledSizeKeepsAtLeastOnePixel() {
        assertEquals(1 to 1, Compressor.scaledSize(1, 1, 0.01))
        assertEquals(1 to 50, Compressor.scaledSize(2, 100, 0.5))
    }
}
