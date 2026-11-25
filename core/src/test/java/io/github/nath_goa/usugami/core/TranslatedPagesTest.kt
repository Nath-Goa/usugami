package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatedPagesTest {
    @Test fun pagesAreDrawnAt150Dpi() {
        // A4 is 8.27 × 11.69 inches.
        assertEquals(1240 to 1754, TranslatedPages.pixelSize(595f, 842f))
        // A2, 8.7 million pixels, is still under the cap.
        assertEquals(2481 to 3508, TranslatedPages.pixelSize(1191f, 1684f))
        assertEquals(1 to 1, TranslatedPages.pixelSize(0.1f, 0.1f))
    }

    @Test fun aHugePageKeepsItsShapeWithin12MegaPixels() {
        // A0 would be 4967 × 7021 pixels at 150 dpi, 35 million.
        val (width, height) = TranslatedPages.pixelSize(2384f, 3370f)
        // Rounding each side may add a row's worth.
        assertTrue(width.toLong() * height <= TranslatedPages.MAX_PIXELS + 10_000)
        assertTrue(width.toLong() * height >= TranslatedPages.MAX_PIXELS - 10_000)
        assertEquals(2384f / 3370f, width.toFloat() / height, 0.001f)
    }

    @Test fun aPageWithoutAreaIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { TranslatedPages.pixelSize(0f, 842f) }
        assertThrows(IllegalArgumentException::class.java) { TranslatedPages.pixelSize(595f, Float.NaN) }
    }
}
