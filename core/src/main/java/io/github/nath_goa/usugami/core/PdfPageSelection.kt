package io.github.nath_goa.usugami.core

/**
 * Page input the split screen can't use. [problem] says why, so the app can show it in the user's
 * language; the message is the English version.
 */
class PageSelectionException(val problem: Problem, val pageCount: Int) : IllegalArgumentException(
    when (problem) {
        Problem.FORMAT -> "Use page numbers such as 1,3-5"
        Problem.RANGES_FORMAT -> "Use ranges such as 1-3;5;7-9"
        Problem.OUT_OF_RANGE -> "Pages must be between 1 and $pageCount"
        Problem.REVERSED -> "Write ranges from low to high, such as 3-8"
        Problem.CHUNK_SIZE -> "Pages per PDF must be between 1 and $pageCount"
    },
) {
    /**
     * [FORMAT] is for page lists (1,3-5), [RANGES_FORMAT] for Split's custom ranges, which are
     * separated by semicolons (1-3;5). [REVERSED] is a range whose pages exist but run backwards (8-3).
     */
    enum class Problem { FORMAT, RANGES_FORMAT, OUT_OF_RANGE, REVERSED, CHUNK_SIZE }
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

    /** The pages [text] names (zero-based, in the order typed); none when it is blank, null while it can't be read. */
    fun read(text: String, pageCount: Int): List<Int>? = if (text.isBlank()) emptyList() else try {
        selected(text, pageCount)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Why [text] can't be read as pages of a PDF with [pageCount]; null when it is blank or can be. */
    fun problem(text: String, pageCount: Int): PageSelectionException? = if (text.isBlank() || pageCount <= 0) null else try {
        selected(text, pageCount)
        null
    } catch (e: PageSelectionException) {
        e
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

    /**
     * Like [format], but keeping [pages]' order, as typed into Split's "Selected pages", where the
     * order is the new PDF's: pages 9, 0, 1, 2 and 4 become "10,1-3,5". Only runs going up become ranges.
     */
    fun formatInOrder(pages: List<Int>): String {
        val parts = ArrayList<String>()
        var start = 0
        while (start < pages.size) {
            var end = start
            while (end + 1 < pages.size && pages[end + 1] == pages[end] + 1) end++
            parts += if (end == start) "${pages[start] + 1}" else "${pages[start] + 1}-${pages[end] + 1}"
            start = end + 1
        }
        return parts.joinToString(",")
    }

    fun customRanges(text: String, pageCount: Int): List<List<Int>> {
        require(pageCount > 0)
        val ranges = text.split(';').map { part ->
            try {
                range(part, pageCount)
            } catch (e: PageSelectionException) {
                // Typed like a page list ("1-3,5"): the message shows how ranges are separated.
                if (e.problem == PageSelectionException.Problem.FORMAT) throw PageSelectionException(PageSelectionException.Problem.RANGES_FORMAT, pageCount)
                throw e
            }
        }
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
        if (first in 1..pageCount && last in 1 until first) throw PageSelectionException(PageSelectionException.Problem.REVERSED, pageCount)
        if (first !in 1..pageCount || last !in first..pageCount) throw outOfRange
        return (first..last).map { it - 1 }
    }
}
