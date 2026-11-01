package io.github.nath_goa.usugami.core

/**
 * Page input the split screen can't use. [problem] says why, so the app can show it in the user's
 * language; the message is the English version.
 */
class PageSelectionException(val problem: Problem, val pageCount: Int) : IllegalArgumentException(
    when (problem) {
        Problem.FORMAT -> "Use page numbers such as 1,3-5"
        Problem.OUT_OF_RANGE -> "Pages must be between 1 and $pageCount"
        Problem.CHUNK_SIZE -> "Pages per PDF must be between 1 and $pageCount"
        Problem.ALL_PAGES -> "Keep at least one page"
    },
) {
    enum class Problem { FORMAT, OUT_OF_RANGE, CHUNK_SIZE, ALL_PAGES }
}

/** One-based page input used by the PDF screens. Returned indexes are zero-based. */
object PdfPageSelection {
    fun selected(text: String, pageCount: Int): List<Int> {
        require(pageCount > 0)
        val result = linkedSetOf<Int>()
        for (part in text.split(',')) {
            for (page in range(part, pageCount)) result += page
        }
        check(result.isNotEmpty()) // every part has at least one page
        return result.toList()
    }

    /**
     * The pages to delete, zero-based and in order, typed like "2,5-7". Throws
     * [PageSelectionException] for input it can't use, and with [PageSelectionException.Problem.ALL_PAGES]
     * when it names every page: a PDF needs at least one.
     */
    fun toDelete(text: String, pageCount: Int): List<Int> {
        val pages = selected(text, pageCount).sorted()
        if (pages.size == pageCount) throw PageSelectionException(PageSelectionException.Problem.ALL_PAGES, pageCount)
        return pages
    }

    /**
     * [pages] (zero-based) written the way people type them, one-based with runs as ranges:
     * pages 1, 4, 5 and 6 become "2,5-7". Blank for no pages.
     */
    fun format(pages: Collection<Int>): String {
        val sorted = pages.toSortedSet().toList()
        val parts = ArrayList<String>()
        var start = 0
        while (start < sorted.size) {
            var end = start
            while (end + 1 < sorted.size && sorted[end + 1] == sorted[end] + 1) end++
            parts += if (end == start) "${sorted[start] + 1}" else "${sorted[start] + 1}-${sorted[end] + 1}"
            start = end + 1
        }
        return parts.joinToString(",")
    }

    fun customRanges(text: String, pageCount: Int): List<List<Int>> {
        require(pageCount > 0)
        val ranges = text.split(';').map { range(it, pageCount) }
        require(ranges.isNotEmpty())
        return ranges
    }

    fun fixed(pageCount: Int, pagesPerFile: Int): List<List<Int>> {
        require(pageCount > 0)
        if (pagesPerFile !in 1..pageCount) throw PageSelectionException(PageSelectionException.Problem.CHUNK_SIZE, pageCount)
        return (0 until pageCount).chunked(pagesPerFile)
    }

    private fun range(text: String, pageCount: Int): List<Int> {
        val match = Regex("^\\s*(\\d+)\\s*(?:-\\s*(\\d+)\\s*)?$").matchEntire(text)
            ?: throw PageSelectionException(PageSelectionException.Problem.FORMAT, pageCount)
        // A number too long for an Int is out of range too.
        val outOfRange = PageSelectionException(PageSelectionException.Problem.OUT_OF_RANGE, pageCount)
        val first = match.groupValues[1].toIntOrNull() ?: throw outOfRange
        val last = match.groupValues[2].takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: throw outOfRange } ?: first
        if (first !in 1..pageCount || last !in first..pageCount) throw outOfRange
        return (first..last).map { it - 1 }
    }
}
