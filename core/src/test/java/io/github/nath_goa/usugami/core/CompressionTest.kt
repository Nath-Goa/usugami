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
    fun scaledSizeKeepsAtLeastOnePixel() {
        assertEquals(1 to 1, Compressor.scaledSize(1, 1, 0.01))
        assertEquals(1 to 50, Compressor.scaledSize(2, 100, 0.5))
    }
}
