package com.nathan.pdfer.core

/** One-based page input used by the PDF split screen. Returned indexes are zero-based. */
object PdfPageSelection {
    fun selected(text: String, pageCount: Int): List<Int> {
        require(pageCount > 0)
        val result = linkedSetOf<Int>()
        for (part in text.split(',')) {
            for (page in range(part, pageCount)) result += page
        }
        require(result.isNotEmpty()) { "Enter at least one page" }
        return result.toList()
    }

    fun customRanges(text: String, pageCount: Int): List<List<Int>> {
        require(pageCount > 0)
        val ranges = text.split(';').map { range(it, pageCount) }
        require(ranges.isNotEmpty())
        return ranges
    }

    fun fixed(pageCount: Int, pagesPerFile: Int): List<List<Int>> {
        require(pageCount > 0 && pagesPerFile in 1..pageCount) { "Pages per PDF must be between 1 and $pageCount" }
        return (0 until pageCount).chunked(pagesPerFile)
    }

    private fun range(text: String, pageCount: Int): List<Int> {
        val match = Regex("^\\s*(\\d+)\\s*(?:-\\s*(\\d+)\\s*)?$").matchEntire(text)
            ?: throw IllegalArgumentException("Use page numbers such as 1,3-5")
        val first = match.groupValues[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid page number")
        val last = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: first
        require(first in 1..pageCount && last in first..pageCount) { "Pages must be between 1 and $pageCount" }
        return (first..last).map { it - 1 }
    }
}
