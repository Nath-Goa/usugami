package com.nathan.pdfer.core

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
}
