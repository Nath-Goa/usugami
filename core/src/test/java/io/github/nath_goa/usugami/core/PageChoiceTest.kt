package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PageChoiceTest {
    @Test fun placesFollowThePagesWhereverTheyGo() {
        assertEquals(listOf(1, 3), PageChoice.places(listOf("a", "b", "c", "d"), setOf("d", "b")))
        // Page b goes and d moves to the front: d stays selected, now first.
        val moved = listOf("d", "a", "c")
        val left = PageChoice.following(moved, setOf("d", "b"))
        assertEquals(setOf("d"), left)
        assertEquals(listOf(0), PageChoice.places(moved, left))
        // A selection whose pages are all still there is given back as it is.
        val chosen = setOf("a", "c")
        assertSame(chosen, PageChoice.following(moved, chosen))
        val none = emptySet<String>()
        assertSame(none, PageChoice.following(moved, none))
        assertEquals(emptyList<Int>(), PageChoice.places(moved, none))
    }

    @Test fun theFieldKeepsWhatWasTypedWhileItNamesTheSamePages() {
        assertEquals("5,1", PageChoice.fieldText("5,1", listOf(0, 4), 6))
        assertEquals("1,3,5", PageChoice.fieldText("5,1", listOf(0, 2, 4), 6))
        // Still being typed, or naming a page the PDF hasn't: kept while nothing is selected.
        assertEquals("2,5-", PageChoice.fieldText("2,5-", emptyList(), 6))
        assertEquals("9", PageChoice.fieldText("9", emptyList(), 6))
        // A page tapped then says what is selected.
        assertEquals("2", PageChoice.fieldText("9", listOf(1), 6))
        assertEquals("2,5-7", PageChoice.fieldText("", listOf(1, 4, 5, 6), 8))
        assertEquals("", PageChoice.fieldText("", emptyList(), 6))
    }

    @Test fun theSelectionIsPackedAsRangesAndComesBack() {
        val order = (0 until 12).map { "p$it" }
        val chosen = setOf("p0", "p2", "p3", "p4", "p8", "p11")
        val packed = PageChoice.pack(order, chosen)
        assertEquals("1,3-5,9,12", packed)
        assertEquals(chosen, PageChoice.unpack(packed, order))
        // Every page of a long PDF takes a few bytes.
        val long = (0 until 5000).map { "p$it" }
        assertEquals("1-5000", PageChoice.pack(long, long.toSet()))
        assertEquals(long.toSet(), PageChoice.unpack("1-5000", long))
        // Kept for pages that are no longer there, or not readable: nothing is selected.
        assertEquals(emptySet<String>(), PageChoice.unpack("9", listOf("a", "b", "c")))
        assertEquals(emptySet<String>(), PageChoice.unpack("x", listOf("a", "b", "c")))
        assertEquals(emptySet<String>(), PageChoice.unpack("1", emptyList<String>()))
        assertEquals(emptySet<String>(), PageChoice.unpack("", listOf("a", "b", "c")))
    }
}
