package io.github.nath_goa.usugami.core

import io.github.nath_goa.usugami.core.ReaderGeometry.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderGeometryTest {

    @Test
    fun thePageInViewIsTheOneThatFillsMost() {
        // Page 3 shows its last 300 pixels, page 4 fills 1500 of a 2000-pixel viewport.
        val items = listOf(Item(3, -900, 1200), Item(4, 320, 1500), Item(5, 1840, 1500))
        assertEquals(4, ReaderGeometry.pageInView(items, 2000))
        // Two pages that fill as much: the first.
        assertEquals(7, ReaderGeometry.pageInView(listOf(Item(7, -500, 1000), Item(8, 520, 1000)), 1000))
        // A short page wholly in view loses to a long one showing more.
        assertEquals(1, ReaderGeometry.pageInView(listOf(Item(0, 0, 200), Item(1, 220, 2000)), 1000))
        assertNull(ReaderGeometry.pageInView(emptyList(), 1000))

        // A point in a gap between two pages belongs to neither.
        assertEquals(4, ReaderGeometry.itemAt(items, 400f)?.index)
        assertNull(ReaderGeometry.itemAt(items, 310f))
        assertEquals(3, ReaderGeometry.itemAt(items, -10f)?.index)
        assertNull(ReaderGeometry.itemAt(items, 5000f))
    }

    @Test
    fun aPagesPictureKeepsItsShapeWithinTheCaps() {
        // A4 on a screen whose page item is 1080 pixels wide.
        assertEquals(1080 to 1527, ReaderGeometry.baseSize(595.28f, 841.89f, 1080))
        // A page 3 times as tall as it is wide on a tablet: within 4 million pixels, in its shape.
        val (w, h) = ReaderGeometry.baseSize(100f, 300f, 1600)
        assertTrue("$w × $h", w.toLong() * h <= ReaderGeometry.BASE_MAX_PIXELS)
        assertEquals(3.0, h.toDouble() / w, 0.01)
        assertTrue("Not shrunk more than it must be", w.toLong() * h > ReaderGeometry.BASE_MAX_PIXELS * 0.99)
        // A strip 20 times as tall as it is wide: its long side within 4096 pixels, in its shape (but
        // for rounding down a pixel), so Android 8 draws it.
        val (sw, sh) = ReaderGeometry.baseSize(100f, 2000f, 1080)
        assertTrue("$sw × $sh", sh in ReaderGeometry.BASE_MAX_SIDE - 1..ReaderGeometry.BASE_MAX_SIDE)
        assertTrue("$sw × $sh", sw in sh / 20 - 1..sh / 20 + 1)
        // A tablet's 2560 pixels are cut to 1600.
        assertEquals(1600 to 1600, ReaderGeometry.baseSize(500f, 500f, 2560))
        // A page box of no size is taken as A4 rather than divided by; a page item of no width still gets a pixel.
        assertEquals(1080 to 1528, ReaderGeometry.baseSize(0f, Float.NaN, 1080))
        assertEquals(1 to 1, ReaderGeometry.baseSize(595f, 842f, 0))
    }
}
