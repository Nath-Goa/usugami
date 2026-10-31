package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
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
        assertEquals(PageSelectionException.Problem.OUT_OF_RANGE, problem { PdfPageSelection.selected("2-1", 6) })
        assertEquals(PageSelectionException.Problem.OUT_OF_RANGE, problem { PdfPageSelection.selected("99999999999", 6) })
        assertEquals(PageSelectionException.Problem.OUT_OF_RANGE, problem { PdfPageSelection.customRanges("1-99999999999", 6) })
        assertEquals(PageSelectionException.Problem.CHUNK_SIZE, problem { PdfPageSelection.fixed(3, 4) })
        assertEquals(6, assertThrows(PageSelectionException::class.java) { PdfPageSelection.selected("7", 6) }.pageCount)
    }

    @Test fun badChunkSizeExplainsTheLimit() {
        // The English message stays readable, not Kotlin's "Failed requirement."
        for (size in listOf(0, -1, 4)) {
            val error = assertThrows(IllegalArgumentException::class.java) { PdfPageSelection.fixed(3, size) }
            assertEquals("Pages per PDF must be between 1 and 3", error.message)
        }
    }
}
