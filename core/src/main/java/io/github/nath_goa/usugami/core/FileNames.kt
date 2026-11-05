package io.github.nath_goa.usugami.core

object FileNames {
    private const val MAX_UTF8_BYTES = 200 // leaves room for " (99).pdf" within the usual 255-byte limit
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    /**
     * Turns user input into a safe PDF base name (no extension): characters that file systems or
     * Android's storage providers reject become '_', surrounding spaces and dots are trimmed,
     * a trailing ".pdf" is dropped and the result is kept to a sensible length.
     */
    fun sanitize(input: String, fallback: String): String {
        // The end is trimmed before the extension check too, so "Report.pdf." loses its ".pdf" as well
        var name = trimEnd(input.trim().replace(ILLEGAL, "_"))
        if (name.endsWith(".pdf", ignoreCase = true)) name = name.dropLast(4)
        name = trimEnd(name).trimStart { it.isWhitespace() || it == '.' }
        name = trimEnd(truncateUtf8(name, MAX_UTF8_BYTES))
        return name.ifEmpty { fallback }
    }

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

    private fun truncateUtf8(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            sb.appendCodePoint(cp)
            bytes += size
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
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
