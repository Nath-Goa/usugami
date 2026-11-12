package io.github.nath_goa.usugami.core

import java.text.BreakIterator
import java.util.Locale

object FileNames {
    private const val MAX_UTF8_BYTES = 200 // leaves room for " (99).pdf" within the usual 255-byte limit
    private const val MAX_PAGES_TEXT = 40

    /** How many names a message lists before it counts the rest (see [listed]). */
    const val LISTED = 3
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    /**
     * The names of Windows' devices, which it won't give a file, in any case and whatever follows a
     * dot ("nul.pdf" and "Con.notes.pdf" too), so such a file can't be copied to a PC or unzipped there.
     */
    private val WINDOWS_DEVICE = Regex("^(CON|PRN|AUX|NUL|COM[0-9¹²³]|LPT[0-9¹²³])(?= *(\\.|$))", RegexOption.IGNORE_CASE)

    /**
     * Turns user input into a safe PDF base name (no extension): characters that file systems or
     * Android's storage providers reject become '_', surrounding spaces and dots are trimmed,
     * a trailing ".pdf" is dropped and the result is kept to a sensible length. A name Windows
     * keeps for a device gets '_' after it: "CON" becomes "CON_".
     */
    fun sanitize(input: String, fallback: String): String {
        // The end is trimmed before the extension check too, so "Report.pdf." loses its ".pdf" as well
        var name = trimEnd(input.trim().replace(ILLEGAL, "_"))
        if (name.endsWith(".pdf", ignoreCase = true)) name = name.dropLast(4)
        name = trimEnd(name).trimStart { it.isWhitespace() || it == '.' }
        name = trimEnd(truncateUtf8(name, MAX_UTF8_BYTES))
        return name.replace(WINDOWS_DEVICE, "$1_").ifEmpty { fallback }
    }

    /**
     * [base] made safe ([sanitize], with [fallback] for a blank one) and followed by [suffix], such
     * as "Report_redacted". When the two are too long together, [base] is cut rather than [suffix],
     * which tells the file from the one it was made from. [spare] bytes more are left free, for an
     * ending still to be added.
     */
    fun withSuffix(base: String, suffix: String, fallback: String, spare: Int = 0): String {
        val room = MAX_UTF8_BYTES - spare - utf8Size(suffix)
        return sanitize(trimEnd(truncateUtf8(sanitize(base, fallback), room)) + suffix, fallback)
    }

    /**
     * Names for files made together from one [base], each followed by its own one of [suffixes], such
     * as "Report_p1-3" and "Report_p4-6". A long [base] is cut once, leaving room for the longest
     * suffix, so the names all begin alike and sort together.
     */
    fun withSuffixes(base: String, suffixes: List<String>, fallback: String): List<String> {
        val longest = suffixes.maxOfOrNull(::utf8Size) ?: 0
        return suffixes.map { withSuffix(base, it, fallback, spare = longest - utf8Size(it)) }
    }

    /**
     * Names for the PDFs Split PDF makes from [base], one for each list of zero-based [parts]: the
     * name followed by the pages the part holds, counted from 1, "Report_p3" for page 3 and
     * "Report_p3-5" for pages 3 to 5, others as they are typed ("Report_p10,1-3,5"), and a long
     * list by its first pages and its last ("Report_p1,3,5,…,99"). A long [base] is cut alike for
     * all, so they sort together, and two parts of the same pages are told apart as [unique] does.
     */
    fun pageParts(base: String, parts: List<List<Int>>, fallback: String): List<String> {
        val taken = HashSet<String>()
        return withSuffixes(base, parts.map { "_p" + pagesText(it) }, fallback).map { name ->
            unique(name, taken).also { taken += it }
        }
    }

    /** [pages] as the name of a part says them (see [pageParts]): at most [MAX_PAGES_TEXT] characters. */
    internal fun pagesText(pages: List<Int>): String {
        val all = PdfPageSelection.formatInOrder(pages)
        if (all.length <= MAX_PAGES_TEXT) return all
        val items = all.split(',')
        val end = ",…," + items.last()
        val start = StringBuilder(items.first())
        for (item in items.subList(1, items.size - 1)) {
            if (start.length + 1 + item.length + end.length > MAX_PAGES_TEXT) break
            start.append(',').append(item)
        }
        return start.toString() + end
    }

    /**
     * The names a message about the files [names] lists, and how many more it only counts: all of
     * them when there are few, else the first [LISTED], so a message about hundreds of files stays
     * short. One name more is listed rather than counted as one more.
     */
    fun listed(names: List<String>): Pair<List<String>, Int> =
        if (names.size <= LISTED + 1) names to 0 else names.take(LISTED) to names.size - LISTED

    /** The original file name without its extension, e.g. "IMG_1234.jpg" -> "IMG_1234". */
    fun baseName(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }

    /** [base] if it is not in [taken] (case-insensitively), otherwise "base (2)", "base (3)", ... */
    fun unique(base: String, taken: Set<String>): String {
        val lower = taken.mapTo(HashSet()) { it.lowercase() }
        if (base.lowercase() !in lower) return base
        var n = 2
        while ("$base ($n)".lowercase() in lower) n++
        return "$base ($n)"
    }

    /**
     * Names for files made together, from the names [wanted] for them, in order: each made safe
     * ([sanitize], with [fallback] for a blank one) and unique among them, ignoring case, as [unique]
     * does. Two PDFs both named "Report" become "Report" and "Report (2)".
     */
    fun uniqueNames(wanted: List<String>, fallback: String): List<String> {
        val taken = HashSet<String>()
        return wanted.map { name -> unique(sanitize(name, fallback), taken).also { taken += it } }
    }

    private fun trimEnd(text: String) = text.trimEnd { it.isWhitespace() || it == '.' }

    /**
     * [text] cut to at most [maxBytes] bytes of UTF-8 between whole characters as people see them:
     * a letter keeps its accents and vowel signs, and an emoji made of several (a family, a flag)
     * stays whole or goes. Only a single such character too long for the limit is cut inside.
     */
    private fun truncateUtf8(text: String, maxBytes: Int): String {
        if (utf8Size(text) <= maxBytes) return text
        val characters = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        var end = 0
        var bytes = 0
        var next = characters.next()
        while (next != BreakIterator.DONE) {
            val size = utf8Size(text.substring(end, next))
            if (bytes + size > maxBytes) break
            bytes += size
            end = next
            next = characters.next()
        }
        if (end > 0) return text.substring(0, end)
        // The first character alone is too long: whole code points of it, then.
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val size = utf8Size(String(Character.toChars(cp)))
            if (bytes + size > maxBytes) break
            sb.appendCodePoint(cp)
            bytes += size
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun utf8Size(text: String) = text.toByteArray(Charsets.UTF_8).size
}

/** Orders names the way people expect: "IMG_2" before "IMG_10", ignoring case. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isAsciiDigit() && cb.isAsciiDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isAsciiDigit()) i++
                while (j < b.length && b[j].isAsciiDigit()) j++
                val numA = a.substring(startA, i).trimStart('0')
                val numB = b.substring(startB, j).trimStart('0')
                if (numA.length != numB.length) return numA.length - numB.length
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}
