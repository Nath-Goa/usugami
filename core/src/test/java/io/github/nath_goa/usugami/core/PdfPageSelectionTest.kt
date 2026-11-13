package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PdfPageSelectionTest {
    @Test fun selectedPagesKeepOrderAndDiscardDuplicates() {
        assertEquals(listOf(4, 0, 2, 3), PdfPageSelection.selected("5,1,3-4,3", 6))
    }

    @Test fun customRangesStaySeparate() {
        assertEquals(listOf(listOf(0, 1), listOf(4), listOf(2, 3)), PdfPageSelection.customRanges("1-2;5;3-4", 6))
    }

    @Test fun fixedRangesIncludeLastShortGroup() {
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), PdfPageSelection.fixed(5, 2))
    }

    @Test fun badInputIsRejected() {
        for (input in listOf("", "0", "2-1", "7", "1,,2", "one")) {
            assertThrows(IllegalArgumentException::class.java) { PdfPageSelection.selected(input, 6) }
        }
        assertThrows(IllegalArgumentException::class.java) { PdfPageSelection.customRanges("1-2;", 6) }
    }

    @Test fun badInputSaysWhy() {
        // The split screen turns the problem into a message in the user's language.
        fun problem(block: () -> Unit) = assertThrows(PageSelectionException::class.java, block).problem
        assertEquals(PageSelectionException.Problem.FORMAT, problem { PdfPageSelection.selected("one", 6) })
        assertEquals(PageSelectionException.Problem.FORMAT, problem { PdfPageSelection.selected("", 6) })
        assertEquals(PageSelectionException.Problem.OUT_OF_RANGE, problem { PdfPageSelection.selected("7", 6) })
        assertEquals(PageSelectionException.Problem.REVERSED, problem { PdfPageSelection.selected("2-1", 6) })
        assertEquals(PageSelectionException.Problem.REVERSED, problem { PdfPageSelection.selected("5-2", 6) })
        // A range running backwards from a page that doesn't exist is out of range, as it says.
        assertEquals(PageSelectionException.Problem.OUT_OF_RANGE, problem { PdfPageSelection.selected("8-3", 6) })
        assertEquals(PageSelectionException.Problem.OUT_OF_RANGE, problem { PdfPageSelection.selected("99999999999", 6) })
        assertEquals(PageSelectionException.Problem.OUT_OF_RANGE, problem { PdfPageSelection.customRanges("1-99999999999", 6) })
        // Custom ranges typed as a page list are told how ranges are written, not to use commas.
        assertEquals(PageSelectionException.Problem.RANGES_FORMAT, problem { PdfPageSelection.customRanges("1-3,5", 6) })
        assertEquals(PageSelectionException.Problem.RANGES_FORMAT, problem { PdfPageSelection.customRanges("1,3-5", 6) })
        assertEquals(PageSelectionException.Problem.REVERSED, problem { PdfPageSelection.customRanges("1-2;5-3", 6) })
        assertEquals(PageSelectionException.Problem.OUT_OF_RANGE, problem { PdfPageSelection.customRanges("1-2;7", 6) })
        assertEquals(PageSelectionException.Problem.CHUNK_SIZE, problem { PdfPageSelection.fixed(3, 4) })
        assertEquals(6, assertThrows(PageSelectionException::class.java) { PdfPageSelection.selected("7", 6) }.pageCount)
    }

    @Test fun typedPagesAreReadOrSayWhyNot() {
        // Reorganize PDF's field: blank selects nothing, and while it can't be read nothing changes.
        assertEquals(emptyList<Int>(), PdfPageSelection.read("", 6))
        assertEquals(listOf(1, 4, 5), PdfPageSelection.read("2,5-6", 6))
        assertNull(PdfPageSelection.read("2,", 6))
        assertNull(PdfPageSelection.read("1", 0))
        assertNull(PdfPageSelection.problem("", 6))
        assertNull(PdfPageSelection.problem("2,5-6", 6))
        assertEquals(PageSelectionException.Problem.FORMAT, PdfPageSelection.problem("2,", 6)?.problem)
        assertEquals(PageSelectionException.Problem.OUT_OF_RANGE, PdfPageSelection.problem("7", 6)?.problem)
        assertEquals(6, PdfPageSelection.problem("7", 6)?.pageCount)
        assertEquals(PageSelectionException.Problem.REVERSED, PdfPageSelection.problem("5-2", 6)?.problem)
        assertNull(PdfPageSelection.problem("1", 0))
    }

    @Test fun pagesAreWrittenAsRanges() {
        assertEquals("2,5-7", PdfPageSelection.format(listOf(6, 1, 4, 5)))
        assertEquals("1", PdfPageSelection.format(setOf(0)))
        assertEquals("1-2,4", PdfPageSelection.format(listOf(3, 1, 0, 1)))
        assertEquals("", PdfPageSelection.format(emptyList()))
        // What it writes reads back as the same pages.
        val pages = listOf(0, 2, 3, 4, 8, 9, 11)
        assertEquals(pages, PdfPageSelection.selected(PdfPageSelection.format(pages), 12))
    }

    @Test fun pagesAreWrittenInTheOrderGiven() {
        // Split's "Selected pages" keeps the order typed: tapping a page adds it at the end.
        assertEquals("10,1-3,5", PdfPageSelection.formatInOrder(listOf(9, 0, 1, 2, 4)))
        assertEquals("", PdfPageSelection.formatInOrder(emptyList()))
        assertEquals("3,2,1", PdfPageSelection.formatInOrder(listOf(2, 1, 0)))
        // Deselecting one page of a long range keeps it short.
        assertEquals("1-5,7-500", PdfPageSelection.formatInOrder((0 until 500).toList() - 5))
        // What it writes reads back as the same pages, in the same order.
        val pages = listOf(7, 2, 3, 4, 0, 11, 9, 10)
        assertEquals(pages, PdfPageSelection.selected(PdfPageSelection.formatInOrder(pages), 12))
    }

    @Test fun badChunkSizeExplainsTheLimit() {
        // The English message stays readable, not Kotlin's "Failed requirement."
        for (size in listOf(0, -1, 4)) {
            val error = assertThrows(IllegalArgumentException::class.java) { PdfPageSelection.fixed(3, size) }
            assertEquals("Pages per PDF must be between 1 and 3", error.message)
        }
    }
}
