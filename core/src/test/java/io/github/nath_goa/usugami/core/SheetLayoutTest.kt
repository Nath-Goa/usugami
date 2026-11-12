package io.github.nath_goa.usugami.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetLayoutTest {

    private val a4Short = PageSize.A4.shortSide.toFloat() // 595.28
    private val a4Long = PageSize.A4.longSide.toFloat() // 841.89
    private val a4Page = a4Short to a4Long
    private val slide = 960f to 540f // 16:9
    private val oldSlide = 720f to 540f // 4:3

    private fun portrait(columns: Int, rows: Int, paper: PageSize = PageSize.A4) =
        SheetGrid(columns, rows, paper.shortSide.toFloat(), paper.longSide.toFloat())

    private fun landscape(columns: Int, rows: Int, paper: PageSize = PageSize.A4) =
        SheetGrid(columns, rows, paper.longSide.toFloat(), paper.shortSide.toFloat())

    private fun scale(grid: SheetGrid, page: Pair<Float, Float>) = SheetLayout.slot(grid, 0, page.first, page.second)!!.scale

    @Test
    fun a4PagesOnA4ComeOutBiggestTheWayTheSpecWorkedOut() {
        val pages = List(5) { a4Page }
        assertEquals(landscape(2, 1), SheetLayout.grid(2, PageSize.A4, pages))
        assertEquals(0.664f, scale(landscape(2, 1), a4Page), 0.001f)
        assertEquals(0.473f, scale(portrait(1, 2), a4Page), 0.001f)
        assertEquals(portrait(2, 2), SheetLayout.grid(4, PageSize.A4, pages))
        assertEquals(0.462f, scale(portrait(2, 2), a4Page), 0.001f)
        assertEquals(0.327f, scale(landscape(2, 2), a4Page), 0.001f)
        assertEquals(landscape(3, 2), SheetLayout.grid(6, PageSize.A4, pages))
        assertEquals(0.327f, scale(landscape(3, 2), a4Page), 0.001f)
        assertEquals(0.312f, scale(portrait(2, 3), a4Page), 0.001f)
        assertEquals(portrait(3, 3), SheetLayout.grid(9, PageSize.A4, pages))
        assertEquals(0.303f, scale(portrait(3, 3), a4Page), 0.001f)
    }

    @Test
    fun slidesGetTheSheetThatDrawsThemBiggest() {
        assertEquals(portrait(1, 2), SheetLayout.grid(2, PageSize.A4, listOf(slide, slide, slide)))
        // Six 16:9 slides: a landscape sheet with 2 across and 3 down, the fourth candidate.
        assertEquals(landscape(2, 3), SheetLayout.grid(6, PageSize.A4, List(6) { slide }))
        assertEquals(0.334f, scale(landscape(2, 3), slide), 0.001f)
        assertEquals(0.287f, scale(portrait(2, 3), slide), 0.001f)
        assertEquals(portrait(2, 3), SheetLayout.grid(6, PageSize.A4, List(6) { oldSlide }))
        // Four slides a sheet: 2×2 either way round, and a landscape sheet draws them bigger.
        assertEquals(landscape(2, 2, PageSize.LETTER), SheetLayout.grid(4, PageSize.LETTER, listOf(slide)))
    }

    @Test
    fun aPdfOfMostlyPortraitPagesIsLaidOutForThem() {
        val cover = a4Long to a4Short
        assertEquals(landscape(2, 1), SheetLayout.grid(2, PageSize.A4, listOf(cover) + List(9) { a4Page }))
        assertEquals(portrait(1, 2), SheetLayout.grid(2, PageSize.A4, listOf(a4Page) + List(9) { cover }))
    }

    @Test
    fun aTieOrNothingToMeasureGoesToTheFirstCandidate() {
        assertEquals(portrait(1, 2), SheetLayout.grid(2, PageSize.A4, emptyList()))
        assertEquals(portrait(2, 3), SheetLayout.grid(6, PageSize.A4, listOf(0f to 0f, 100f to 0f)))
        // Square pages fill 2×2 cells as well on a portrait sheet as on a landscape one.
        assertEquals(portrait(2, 2), SheetLayout.grid(4, PageSize.A4, List(4) { 300f to 300f }))
        assertEquals(portrait(3, 3), SheetLayout.grid(9, PageSize.A4, List(4) { 300f to 300f }))
        assertThrows(IllegalArgumentException::class.java) { SheetLayout.grid(3, PageSize.A4, listOf(a4Page)) }
    }

    @Test
    fun cellsGoAcrossThenDownInsideTheMarginsAndAGapApart() {
        val gridOf = SheetLayout.grid(9, PageSize.A4, listOf(a4Page))
        val slots = (0 until 9).map { SheetLayout.slot(gridOf, it, a4Short, a4Long)!! }
        assertTrue(slots[1].x > slots[0].x && slots[2].x > slots[1].x)
        assertEquals(slots[0].y, slots[2].y, 0.001f)
        assertEquals(slots[0].x, slots[3].x, 0.001f)
        assertTrue("Row 2 is below row 1", slots[3].y < slots[0].y)
        assertTrue(slots[8].x > slots[6].x && slots[8].y < slots[5].y)

        val eps = 0.001f
        for (paper in SheetLayout.PAPERS) for (count in SheetLayout.COUNTS) for (page in listOf(a4Page, slide, oldSlide, 100f to 2000f)) {
            val grid = SheetLayout.grid(count, paper, listOf(page))
            val all = (0 until count).map { SheetLayout.slot(grid, it, page.first, page.second)!! }
            for ((i, s) in all.withIndex()) {
                val what = "$paper $count $page cell $i: $s"
                assertTrue(what, s.x >= SheetLayout.MARGIN - eps && s.y >= SheetLayout.MARGIN - eps)
                assertTrue(what, s.x + s.width <= grid.width - SheetLayout.MARGIN + eps)
                assertTrue(what, s.y + s.height <= grid.height - SheetLayout.MARGIN + eps)
                if ((i + 1) % grid.columns != 0) {
                    val right = all[i + 1]
                    assertTrue(what, right.x - (s.x + s.width) >= SheetLayout.GAP - eps)
                }
                if (i + grid.columns < count) {
                    val below = all[i + grid.columns]
                    assertTrue(what, s.y - (below.y + below.height) >= SheetLayout.GAP - eps)
                }
            }
        }
    }

    @Test
    fun aPageKeepsItsShapeAndIsCentredInItsCell() {
        val grid = portrait(2, 3)
        val tall = SheetLayout.slot(grid, 4, 100f, 400f)!!
        val wide = SheetLayout.slot(grid, 4, 400f, 100f)!!
        assertEquals(0.25f, tall.width / tall.height, 0.0001f)
        assertEquals(4f, wide.width / wide.height, 0.0001f)
        assertEquals(tall.width, 100f * tall.scale, 0.001f)
        // Both centred in the same cell.
        assertEquals(tall.x + tall.width / 2, wide.x + wide.width / 2, 0.001f)
        assertEquals(tall.y + tall.height / 2, wide.y + wide.height / 2, 0.001f)
        // Cell 4 of 2×3 is the left one of the last row.
        assertEquals(SheetLayout.MARGIN, wide.x, 0.001f)
        assertEquals(SheetLayout.MARGIN, tall.y, 0.001f)
        // A receipt-sized page is scaled up to fill its cell like the rest.
        assertTrue(SheetLayout.slot(grid, 0, 80f, 120f)!!.scale > 1f)
        for ((w, h) in listOf(0f to 100f, 100f to 0f, -5f to 100f, Float.NaN to 100f, 100f to Float.POSITIVE_INFINITY, 1e-38f to 1e-38f)) {
            assertNull("$w × $h", SheetLayout.slot(grid, 0, w, h))
        }
        assertThrows(IllegalArgumentException::class.java) { SheetLayout.slot(grid, 6, 100f, 100f) }
    }

    @Test
    fun thePageLandsUprightInItsSlotForEveryTurn() {
        for (turn in listOf(0, 90, 180, 270)) {
            // A crop box with an offset, not square.
            val frame = PageFrame(30f, 40f, 300f, 500f, turn)
            val grid = SheetLayout.grid(6, PageSize.A4, listOf(frame.shownWidth to frame.shownHeight))
            val slot = SheetLayout.slot(grid, 4, frame.shownWidth, frame.shownHeight)!!
            val m = SheetLayout.matrix(frame, slot)
            val what = "turn $turn"
            assertArrayEquals(what, floatArrayOf(slot.x, slot.y, slot.x + slot.width, slot.y + slot.height), Areas.mapped(m, floatArrayOf(30f, 40f, 330f, 540f)), 0.01f)
            // What shows at the page's top-left lands at the slot's top-left.
            val topLeft = when (turn) {
                0 -> 30f to 540f
                90 -> 30f to 40f
                180 -> 330f to 40f
                else -> 330f to 540f
            }
            assertArrayEquals(what, floatArrayOf(slot.x, slot.y + slot.height), apply(m, topLeft), 0.01f)
            // Any point lands where it shows, scaled.
            val (shownX, shownY) = frame.toShown(100f, 200f)
            assertArrayEquals(what, floatArrayOf(slot.x + slot.scale * shownX, slot.y + slot.scale * shownY), apply(m, 100f to 200f), 0.01f)
        }
    }

    private fun apply(m: FloatArray, point: Pair<Float, Float>) =
        floatArrayOf(m[0] * point.first + m[2] * point.second + m[4], m[1] * point.first + m[3] * point.second + m[5])

    @Test
    fun usLetterPagesGetUsLetterAndTheRestA4() {
        assertEquals(PageSize.LETTER, SheetLayout.paperLike(612f, 792f))
        assertEquals(PageSize.LETTER, SheetLayout.paperLike(792f, 612f))
        assertEquals(PageSize.LETTER, SheetLayout.paperLike(610.5f, 793.9f))
        assertEquals(PageSize.A4, SheetLayout.paperLike(615f, 792f))
        assertEquals(PageSize.A4, SheetLayout.paperLike(595f, 842f))
        assertEquals(PageSize.A4, SheetLayout.paperLike(1000f, 700f))
    }

    @Test
    fun theLastSheetMayNotBeFull() {
        assertEquals(2, SheetLayout.sheets(5, 4))
        assertEquals(2, SheetLayout.sheets(8, 4))
        assertEquals(1, SheetLayout.sheets(1, 9))
        assertEquals(0, SheetLayout.sheets(0, 2))
    }
}
