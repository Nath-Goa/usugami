package io.github.nath_goa.usugami.core

import io.github.nath_goa.usugami.core.ReaderGeometry.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** The list point under ([x], [y]) on the screen, with the list scrolled [scrolled] list pixels forward. */
    private fun under(x: Float, y: Float, zoom: Float, panX: Float, scrolled: Float): Pair<Float, Float> =
        ReaderGeometry.toList(x, y, zoom, panX).let { (lx, ly) -> lx to ly + scrolled }

    @Test
    fun zoomingAboutAPointKeepsWhatIsUnderIt() {
        val width = 1080f
        // In and out, about a point: what is under the fingers stays there, once the list has scrolled
        // as far as it is told.
        for ((zoom, pan, factor) in listOf(Triple(1f, 0f, 2f), Triple(3f, 1000f, 0.5f), Triple(2f, 500f, 1.3f), Triple(4.5f, 2000f, 0.9f))) {
            val (focusX, focusY) = 400f to 900f
            val before = under(focusX, focusY, zoom, pan, 0f)
            val zoomed = ReaderGeometry.zoomAbout(zoom, pan, factor, focusX, focusY, width)
            assertEquals(zoom * factor, zoomed.zoom, 1e-4f)
            val after = under(focusX, focusY, zoomed.zoom, zoomed.panX, zoomed.scrollBy)
            assertEquals("Across, at $zoom × $factor", before.first, after.first, 0.01f)
            assertEquals("Down, at $zoom × $factor", before.second, after.second, 0.01f)
            // And it is back on the screen where it was.
            val (x, y) = ReaderGeometry.toScreen(after.first, after.second - zoomed.scrollBy, zoomed.zoom, zoomed.panX)
            assertEquals(focusX, x, 0.01f)
            assertEquals(focusY, y, 0.01f)
        }

        // At the limits the zoom stops, and nothing moves.
        val most = ReaderGeometry.zoomAbout(ReaderGeometry.MAX_ZOOM, 900f, 2f, 300f, 700f, width)
        assertEquals(ReaderGeometry.MAX_ZOOM, most.zoom)
        assertEquals(900f, most.panX)
        assertEquals(0f, most.scrollBy)
        val centred = ReaderGeometry.clampPan(0f, ReaderGeometry.MIN_ZOOM, width)
        val least = ReaderGeometry.zoomAbout(ReaderGeometry.MIN_ZOOM, centred, 0.5f, 300f, 700f, width)
        assertEquals(ReaderGeometry.MIN_ZOOM, least.zoom)
        assertEquals(centred, least.panX)
        assertEquals(0f, least.scrollBy)
        // Past a limit, it zooms as far as the limit, still about the point.
        val past = ReaderGeometry.zoomAbout(4f, 1000f, 2f, 300f, 700f, width)
        assertEquals(ReaderGeometry.MAX_ZOOM, past.zoom)
        val (beforeX, beforeY) = under(300f, 700f, 4f, 1000f, 0f)
        val (afterX, afterY) = under(300f, 700f, past.zoom, past.panX, past.scrollBy)
        assertEquals(beforeX, afterX, 0.01f)
        assertEquals(beforeY, afterY, 0.01f)
        // Zoomed out past the screen's width, the pages are centred, and what is under the point keeps its height.
        val out = ReaderGeometry.zoomAbout(1f, 0f, 0.1f, 300f, 700f, width)
        assertEquals(ReaderGeometry.MIN_ZOOM, out.zoom)
        assertEquals(-width / 4f, out.panX)
        assertEquals(700f, under(300f, 700f, out.zoom, out.panX, out.scrollBy).second, 0.01f)
    }

    @Test
    fun thePanStaysOnThePageAndCentresWhenZoomedOut() {
        val width = 1000f
        // Zoomed in twice, the content may move left by up to one screen's width.
        assertEquals(0f, ReaderGeometry.clampPan(-50f, 2f, width))
        assertEquals(400f, ReaderGeometry.clampPan(400f, 2f, width))
        assertEquals(1000f, ReaderGeometry.clampPan(1500f, 2f, width))
        // At the screen's width, not at all.
        assertEquals(0f, ReaderGeometry.clampPan(123f, 1f, width))
        // Zoomed out to half, the pages are centred: a quarter of the screen each side.
        val half = ReaderGeometry.clampPan(300f, 0.5f, width)
        assertEquals(250f, ReaderGeometry.toScreen(0f, 0f, 0.5f, half).first)
        assertEquals(750f, ReaderGeometry.toScreen(width, 0f, 0.5f, half).first)
        // Zooming out at the right edge keeps the pages' edge at the screen's.
        val zoomed = ReaderGeometry.zoomAbout(3f, 2000f, 0.5f, 100f, 500f, width)
        assertEquals(1.5f, zoomed.zoom)
        assertEquals(500f, zoomed.panX)
        assertEquals(width, ReaderGeometry.toScreen(width, 0f, zoomed.zoom, zoomed.panX).first)
    }

    @Test
    fun aTileCoversWhatShowsAndNoMore() {
        val (width, height) = 1080f to 2000f
        val (itemLeft, itemWidth) = 21f to 1038f
        // A page three times as tall as wide, at zoom 3, its middle showing.
        val item = Item(0, -1000, 3114)
        val tile = ReaderGeometry.tile(item, itemLeft, itemWidth, 3f, 1080f, width, height)!!
        // What shows (339 to 699 across, 1000 to 1667 down) and a fifth of the screen each way (72 and 133 list pixels).
        assertEquals(267f, tile.left, 0.34f)
        assertEquals(771f, tile.left + tile.width, 0.34f)
        assertEquals(866.67f, tile.top, 0.34f)
        assertEquals(1800f, tile.top + tile.height, 0.34f)
        // One pixel to the screen pixel, within 6 million pixels.
        assertEquals(3f, tile.scale)
        assertEquals(tile.width * 3f, tile.pixelWidth.toFloat(), 0.01f)
        assertEquals(tile.height * 3f, tile.pixelHeight.toFloat(), 0.01f)
        assertTrue(tile.pixelWidth.toLong() * tile.pixelHeight <= ReaderGeometry.TILE_MAX_PIXELS)
        assertEquals(Math.round(tile.left * 3f), tile.pixelLeft)

        // At the page's top-left corner, cut to the page.
        val corner = ReaderGeometry.tile(Item(0, 40, 3114), itemLeft, itemWidth, 3f, 0f, width, height)!!
        assertEquals(0f, corner.left)
        assertEquals(0f, corner.top)
        assertEquals((1080f + 216f) / 3f - itemLeft, corner.left + corner.width, 0.34f)
        // A page off the screen, below or above, gets none.
        assertNull(ReaderGeometry.tile(Item(1, 680, 3114), itemLeft, itemWidth, 3f, 1080f, width, height))
        assertNull(ReaderGeometry.tile(Item(1, -4000, 3114), itemLeft, itemWidth, 3f, 1080f, width, height))

        // It still serves after a small scroll or move across, not after a zoom of 10% or a scroll past its edge.
        assertTrue(ReaderGeometry.serves(tile, Item(0, -1050, 3114), itemLeft, itemWidth, 3f, 1080f, width, height))
        assertTrue(ReaderGeometry.serves(tile, item, itemLeft, itemWidth, 3f, 1180f, width, height))
        assertTrue(ReaderGeometry.serves(tile, item, itemLeft, itemWidth, 3.1f, 1080f, width, height))
        assertFalse(ReaderGeometry.serves(tile, item, itemLeft, itemWidth, 3.3f, 1080f, width, height))
        assertFalse(ReaderGeometry.serves(tile, Item(0, -1200, 3114), itemLeft, itemWidth, 3f, 1080f, width, height))
        // A tile cut to the page's edge still serves there.
        assertTrue(ReaderGeometry.serves(corner, Item(0, 40, 3114), itemLeft, itemWidth, 3f, 0f, width, height))

        // A big screen: less margin, then fewer pixels, never over 6 million, still covering what shows.
        val big = Item(0, -2000, 20_000)
        val shrunk = ReaderGeometry.tile(big, 16f, 2528f, 4f, 3000f, 2560f, 3200f)!!
        assertTrue(shrunk.pixelWidth.toLong() * shrunk.pixelHeight <= ReaderGeometry.TILE_MAX_PIXELS)
        assertTrue(shrunk.scale < 4f)
        assertTrue(ReaderGeometry.serves(shrunk, big, 16f, 2528f, 4f, 3000f, 2560f, 3200f))
        // A screen of 4 million pixels keeps all of what shows one pixel to the screen pixel, with less margin.
        val tablet = ReaderGeometry.tile(big, 16f, 2528f, 4f, 3000f, 2560f, 1600f)!!
        assertEquals(4f, tablet.scale)
        assertTrue(tablet.pixelWidth.toLong() * tablet.pixelHeight in 2560L * 1600..ReaderGeometry.TILE_MAX_PIXELS.toLong())
    }
}
