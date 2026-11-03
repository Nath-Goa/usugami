package io.github.nath_goa.usugami.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AreasTest {
    @Test fun anAreaMovedAndMovedBackIsWhereItWas() {
        // Turned a quarter, stretched and moved, as a scan drawn on a turned page is.
        val m = floatArrayOf(0f, 2f, -3f, 0f, 400f, 10f)
        val area = floatArrayOf(0.25f, 0f, 0.5f, 1f)
        assertArrayEquals(floatArrayOf(397f, 10.5f, 400f, 11f), Areas.mapped(m, area), 1e-4f)
        assertArrayEquals(area, Areas.mapped(Areas.inverted(m)!!, Areas.mapped(m, area)), 1e-4f)
        assertNull("Squashed flat, it can't be undone", Areas.inverted(floatArrayOf(1f, 2f, 2f, 4f, 0f, 0f)))
    }

    @Test fun overlapsAndWhatLiesWithin() {
        assertArrayEquals(floatArrayOf(5f, 5f, 10f, 8f), Areas.intersection(floatArrayOf(0f, 0f, 10f, 8f), floatArrayOf(5f, 5f, 20f, 20f)), 0f)
        assertNull("Touching edges don't overlap", Areas.intersection(floatArrayOf(0f, 0f, 10f, 10f), floatArrayOf(10f, 0f, 20f, 10f)))
        val page = floatArrayOf(200f, 0f, 400f, 200f)
        assertTrue(Areas.inside(floatArrayOf(199.5f, 0f, 300f, 200.5f), page, slack = 1f))
        assertFalse(Areas.inside(floatArrayOf(0f, 0f, 200f, 200f), page, slack = 1f))
    }
}
