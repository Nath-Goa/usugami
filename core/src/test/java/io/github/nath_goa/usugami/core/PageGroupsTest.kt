package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PageGroupsTest {

    /** An image of a scan: its name, and its card number (0 when it is not a card's side). */
    private data class Image(val name: String, val card: Long)

    private fun pages(vararg cards: Long): List<List<Long>> =
        PageGroups.pages(cards.toList()) { it }

    @Test
    fun imagesOfOneCardNextToEachOtherAreOnePage() {
        assertEquals(listOf(listOf(0L), listOf(7L, 7L), listOf(0L), listOf(9L)), pages(0, 7, 7, 0, 9))
        assertEquals(emptyList<List<Long>>(), pages())
    }

    @Test
    fun aThirdImageWithTheSameNumberStartsANewPage() {
        assertEquals(listOf(listOf(7L, 7L), listOf(7L)), pages(7, 7, 7))
    }

    @Test
    fun onlyNeighboursShareAPage() {
        // Two cards one after the other, and the sides of one card parted by an ordinary image.
        assertEquals(listOf(listOf(7L, 7L), listOf(8L, 8L)), pages(7, 7, 8, 8))
        assertEquals(listOf(listOf(7L), listOf(0L), listOf(7L)), pages(7, 0, 7))
        // Ordinary images never share a page.
        assertEquals(listOf(listOf(0L), listOf(0L)), pages(0, 0))
    }

    @Test
    fun aCardsBackGoesRightAfterItsFront() {
        val front = Image("front", 7)
        val page = Image("page", 0)
        val other = Image("other card", 9)
        val back = Image("back", 7)
        // Other images came in after the front; the back still goes right after it.
        assertEquals(
            listOf(front, back, page, other),
            PageGroups.withAdded(listOf(front, page, other), back) { it.card },
        )
        // After the last image of its card, at the end of the list too.
        assertEquals(listOf(page, front, back), PageGroups.withAdded(listOf(page, front), back) { it.card })
    }

    @Test
    fun anImageWithoutACardOrWhoseCardHasNoImageGoesAtTheEnd() {
        val front = Image("front", 7)
        val page = Image("page", 0)
        assertEquals(listOf(front, page, Image("new", 0)), PageGroups.withAdded(listOf(front, page), Image("new", 0)) { it.card })
        assertEquals(listOf(front, page, Image("new card", 3)), PageGroups.withAdded(listOf(front, page), Image("new card", 3)) { it.card })
        assertEquals(listOf(Image("first", 0)), PageGroups.withAdded(emptyList(), Image("first", 0)) { it.card })
    }
}
