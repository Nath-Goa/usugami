package io.github.nath_goa.usugami.core

import java.text.Normalizer
import java.util.Locale

/**
 * Finds tools by what is typed into Home's search field. Each word typed must begin a word of the
 * tool's name, its description or its hidden keywords, in any order, so "red siz" finds "reduce
 * size". Case and accents don't matter ("resume" finds "résumé"), and neither does the Devanagari
 * nukta or candrabindu, which people often leave out or swap for an anusvara ("जोडें" finds "जोड़ें").
 */
object ToolSearch {
    private const val NUKTA = '़'
    private const val CANDRABINDU = 'ँ'
    private const val ANUSVARA = 'ं'

    /**
     * The words of [text], folded for comparing: in lower case, without accents, split at anything
     * that isn't a letter, digit or vowel sign (spaces, commas, hyphens, apostrophes, dots).
     */
    fun words(text: String): List<String> {
        // Lower case first: a few capitals (such as the Turkish dotted İ) only lose their accent once
        // lowered. NFKD then splits é into e and its accent, and ligatures and full-width forms
        // into plain letters.
        val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
        val folded = StringBuilder(decomposed.length)
        var i = 0
        while (i < decomposed.length) {
            val c = decomposed.codePointAt(i)
            i += Character.charCount(c)
            when {
                // Accents of Latin letters, the nukta and zero-width joiners are dropped. Other
                // scripts' marks, like Devanagari vowel signs, are part of their word.
                c in 0x0300..0x036F || c == NUKTA.code || Character.getType(c) == Character.FORMAT.toInt() -> Unit
                c == CANDRABINDU.code -> folded.append(ANUSVARA)
                Character.isLetterOrDigit(c) || isMark(c) -> folded.appendCodePoint(c)
                else -> folded.append(' ')
            }
        }
        return folded.split(' ').filter { it.isNotEmpty() }
    }

    private fun isMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    /** Whether each of [wanted] begins one of [words]. */
    private fun covers(words: List<String>, wanted: List<String>): Boolean =
        wanted.all { want -> words.any { it.startsWith(want) } }

    /** Whether every word of [query] begins a word of [text]. A query without words matches nothing. */
    fun matches(query: String, text: String): Boolean {
        val wanted = words(query)
        return wanted.isNotEmpty() && covers(words(text), wanted)
    }

    /**
     * The [tools] that [query] finds, in their order, except that those found by their [name] alone
     * come before those found through [moreText] (their description and keywords). A query without
     * words, such as only spaces or punctuation, finds none.
     */
    fun <T> search(tools: List<T>, query: String, name: (T) -> String, moreText: (T) -> String): List<T> {
        val wanted = words(query)
        if (wanted.isEmpty()) return emptyList()
        val byName = ArrayList<T>()
        val byMore = ArrayList<T>()
        for (tool in tools) {
            val nameWords = words(name(tool))
            when {
                covers(nameWords, wanted) -> byName += tool
                covers(nameWords + words(moreText(tool)), wanted) -> byMore += tool
            }
        }
        return byName + byMore
    }
}
