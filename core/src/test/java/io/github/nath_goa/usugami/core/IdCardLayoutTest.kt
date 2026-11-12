package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IdCardLayoutTest {

    private fun mm(value: Double) = value * 72 / 25.4

    private val a4 = LayoutSettings(PageSize.A4, PageOrientation.PORTRAIT, Margin.NONE)
    private val card = mm(85.6)

    private fun near(expected: Double, actual: Double, what: String = "") = assertEquals(what, expected, actual, 0.01)

    @Test
    fun twoSidesOfACardAtTheirRealSize() {
        val (front, back) = IdCardLayout.compute(listOf(1011 to 638, 1011 to 638), a4)
        for (side in listOf(front, back)) {
            near(PageSize.A4.shortSide, side.pageWidth)
            near(PageSize.A4.longSide, side.pageHeight)
            near(card, side.imageWidth) // 242.65
            near(card * 638 / 1011, side.imageHeight) // 153.12
            near((PageSize.A4.shortSide - card) / 2, side.imageX) // 176.31
        }
        val height = card * 638 / 1011
        val content = 2 * height + mm(10.0)
        near(PageSize.A4.longSide - (PageSize.A4.longSide - content) / 2 - height, front.imageY) // 435.12
        near((PageSize.A4.longSide - content) / 2, back.imageY) // 253.65
        // As much space above the front as below the back, and 10 mm (28.35 pt) between them.
        near(front.imageTop, back.imageY)
        near(mm(10.0), front.imageY - (back.imageY + back.imageHeight))
    }

    @Test
    fun oneSideIsInTheMiddleOfThePage() {
        val (side) = IdCardLayout.compute(listOf(1011 to 638), a4)
        near(side.imageX, side.pageWidth - side.imageX - side.imageWidth)
        near(side.imageY, side.imageTop)
        near(card, side.imageWidth)
    }

    @Test
    fun anUprightSideIsTheCardsLengthHigh() {
        val (side) = IdCardLayout.compute(listOf(638 to 1011), a4)
        near(card, side.imageHeight)
        near(card * 638 / 1011, side.imageWidth)
    }

    @Test
    fun sidesCroppedAtASlantAreStillACardEachInItsOwnProportions() {
        // Ratios 1.50 and 1.70: on average a card's. Both get the card's long edge.
        val (front, back) = IdCardLayout.compute(listOf(1500 to 1000, 1700 to 1000), a4)
        near(card, front.imageWidth)
        near(card, back.imageWidth)
        near(card * 1000 / 1500, front.imageHeight)
        near(card * 1000 / 1700, back.imageHeight)
    }

    @Test
    fun squarerSidesAreAPassportsPage() {
        val (front, back) = IdCardLayout.compute(listOf(1250 to 880, 1250 to 880), a4)
        near(mm(125.0), front.imageWidth) // 354.33
        near(mm(125.0), back.imageWidth)
        near(mm(125.0) * 880 / 1250, front.imageHeight)
    }

    @Test
    fun whatDoesNotFitInsideTheMarginsShrinksAsOne() {
        val settings = LayoutSettings(PageSize.A5, PageOrientation.PORTRAIT, Margin.BIG)
        val (front, back) = IdCardLayout.compute(listOf(1250 to 880, 1250 to 880), settings)
        val inside = PageSize.A5.shortSide - 2 * Margin.BIG.points
        // The width is what limits: both shrunk by one factor, to the margins' width.
        val scale = inside / mm(125.0)
        assertTrue(scale < 1)
        for (side in listOf(front, back)) {
            near(inside, side.imageWidth)
            near(mm(125.0) * 880 / 1250 * scale, side.imageHeight)
            near(Margin.BIG.points, side.imageX)
            assertTrue(side.imageY >= Margin.BIG.points - 0.01)
            assertTrue(side.imageTop >= Margin.BIG.points - 0.01)
        }
        // Still centred down the page, the gap shrunk by the same factor.
        near(front.imageTop, back.imageY)
        near(mm(10.0) * scale, front.imageY - (back.imageY + back.imageHeight))
    }

    @Test
    fun onlyLandscapeTurnsThePage() {
        val sides = listOf(1011 to 638, 1011 to 638)
        val landscape = IdCardLayout.compute(sides, a4.copy(orientation = PageOrientation.LANDSCAPE)).first()
        near(PageSize.A4.longSide, landscape.pageWidth)
        near(PageSize.A4.shortSide, landscape.pageHeight)
        // Auto is portrait for a card's page, wide as its sides are.
        for (orientation in listOf(PageOrientation.AUTO, PageOrientation.PORTRAIT)) {
            val page = IdCardLayout.compute(sides, a4.copy(orientation = orientation)).first()
            near(PageSize.A4.shortSide, page.pageWidth)
            near(PageSize.A4.longSide, page.pageHeight)
        }
    }

    @Test
    fun fitPagesAreTheSidesAndTheGapPlusTheMargin() {
        val sides = listOf(1011 to 638, 1011 to 638)
        val height = card * 638 / 1011
        val contentH = 2 * height + mm(10.0)
        val (front, back) = IdCardLayout.compute(sides, LayoutSettings(PageSize.FIT, margin = Margin.NONE))
        near(card, front.pageWidth)
        near(contentH, front.pageHeight)
        near(0.0, front.imageX)
        near(0.0, front.imageTop)
        near(0.0, back.imageY)
        // With a margin, in proportion to the content as Fit pages have it; sides at their real size.
        val m = Margin.SMALL.points / PageSize.A4.shortSide * card
        val (small) = IdCardLayout.compute(sides, LayoutSettings(PageSize.FIT, PageOrientation.LANDSCAPE, Margin.SMALL))
        near(card + 2 * m, small.pageWidth)
        near(contentH + 2 * m, small.pageHeight)
        near(m, small.imageX)
        near(m, small.imageTop)
        near(card, small.imageWidth)
    }

    @Test
    fun aStripOnAFitPageStaysWithinThePageLimits() {
        for (sides in listOf(listOf(3000 to 16), listOf(3000 to 16, 3000 to 16), listOf(16 to 3000))) {
            val placed = IdCardLayout.compute(sides, LayoutSettings(PageSize.FIT))
            for (side in placed) {
                assertTrue("$side", side.pageWidth in PageLayout.MIN_PAGE_SIDE..PageLayout.MAX_PAGE_SIDE)
                assertTrue("$side", side.pageHeight in PageLayout.MIN_PAGE_SIDE..PageLayout.MAX_PAGE_SIDE)
                // On the page and centred on it.
                assertTrue("$side", side.imageX >= 0 && side.imageY >= 0 && side.imageTop >= -1e-9)
                near(side.imageX, side.pageWidth - side.imageX - side.imageWidth)
            }
            near(placed.first().imageTop, placed.last().imageY)
        }
    }

    @Test
    fun aCardHasOneOrTwoSides() {
        assertThrows(IllegalArgumentException::class.java) { IdCardLayout.compute(emptyList(), a4) }
        assertThrows(IllegalArgumentException::class.java) { IdCardLayout.compute(List(3) { 1011 to 638 }, a4) }
        assertThrows(IllegalArgumentException::class.java) { IdCardLayout.compute(listOf(1011 to 0), a4) }
    }
}
