package io.github.nath_goa.usugami.core

/** What of a page's text Translate PDF translates. */
object TranslatableText {
    /** A single run of characters longer than this with a digit in it is a code or an id, not words. */
    private const val LONG_TOKEN = 40

    private val WEB_ADDRESS = Regex("^(?:[a-z][a-z0-9+.-]*://|www\\.|mailto:)\\S+$", RegexOption.IGNORE_CASE)
    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    /**
     * example.com, example.co.uk/page, report.pdf, 报告.pdf, रिपोर्ट.pdf: names separated by dots, the
     * last (the ending) of two letters or more.
     */
    private val DOMAIN_OR_FILE = Regex("^(?:[\\p{L}\\p{M}\\p{N}_-]+\\.)+(\\p{L}{2,24})(?:[/:?#]\\S*)?$")

    /** "1.Introduction", "2.1.Scope": a heading numbered with a full stop, not a domain ("163.com"). */
    private val NUMBERED_HEADING = Regex("^(?:\\d{1,3}\\.)+\\p{Lu}\\p{Ll}")

    /** C:\Users, \\server\share, /home/me, ~/notes, ./a, ../b. */
    private val PATH = Regex("^(?:[A-Za-z]:[\\\\/].*|\\\\\\\\\\S+|~?/\\S+|\\.{1,2}/\\S+)$")

    /** Brackets, quotes and the punctuation that ends a sentence, around an address: as written with spaces, and as Chinese and Japanese write them. */
    private const val AROUND = "()[]{}<>\"'“”‘’«».,;:!?（）［］｛｝＜＞〈〉《》「」『』【】〔〕。、，．；：！？"

    /**
     * Whether [text] (a block of a page) is worth translating. Not when it has fewer than two
     * letters; when each of its words is a web address, an e-mail address or a file's name or path
     * (in any script; one that Chinese, Japanese or Thai words run into, with no space, is among words);
     * when it is a number, a date, an amount or a code with no word in it (digits with punctuation,
     * and letters only one at a time: "12.50", "03/04/2025", "A-12-B", "2025年3月"); or when it is
     * one run of more than 40 characters without a space, in writing that has spaces, that has a
     * digit in it (an id). A word is two letters or more in a row, so "Invoice 12" is translated, and
     * so is "U.S.A.", which has no digit. What isn't translated stays as it is on the page.
     */
    fun isWorthTranslating(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.codePoints().filter(Character::isLetter).limit(2).count() < 2) return false
        val tokens = trimmed.split(WHITESPACE)
        // A Windows path may have spaces in it (C:\Program Files\...).
        if (tokens.all(::isAddress) || PATH.matches(trimmed)) return false
        // Letters one at a time among digits: a code. Without digits ("U.S.A.") it may be an abbreviation.
        if (!hasWord(trimmed) && trimmed.any(Char::isDigit)) return false
        // Chinese, Japanese and Thai put no space between words, so a whole paragraph of them is one run.
        if (tokens.size == 1 && trimmed.length > LONG_TOKEN && trimmed.any(Char::isDigit) &&
            trimmed.codePoints().noneMatch(WordSpacing::unspaced)
        ) {
            return false
        }
        return true
    }

    /** Whether [token], without the brackets, quotes and punctuation around it, is a web or e-mail address or a file's name or path. */
    private fun isAddress(token: String): Boolean {
        val bare = token.trim { it in AROUND }
        if (bare.isEmpty()) return false
        // Chinese, Japanese and Thai put no space around an address either, so their words run into it
        // ("请发邮件至support@example.com与我们联系"): words with an address among them.
        if (scriptsMeet(bare)) return false
        return WEB_ADDRESS.matches(bare) || EMAIL.matches(bare) || isDomainOrFile(bare) || PATH.matches(bare)
    }

    /**
     * Whether [text] is a domain or a file's name. Not when its ending is in Chinese, Japanese or Thai
     * letters, or it starts with a number and a full stop and goes on with a word: a numbered heading
     * ("1.公司简介", "1.Introduction").
     */
    private fun isDomainOrFile(text: String): Boolean {
        val ending = DOMAIN_OR_FILE.matchEntire(text)?.groupValues?.get(1) ?: return false
        return ending.codePoints().noneMatch(WordSpacing::unspaced) && !NUMBERED_HEADING.containsMatchIn(text)
    }

    /**
     * Whether a character of writing without spaces (Chinese, Japanese, Thai...) stands right beside
     * a letter of other writing in [text] ("邮件support", "com与"), a mark going with the letter it is
     * on. An address written in those scripts keeps its dots, slashes and @ between them
     * ("https://zh.wikipedia.org/wiki/北京", "报告.pdf").
     */
    private fun scriptsMeet(text: String): Boolean {
        // What the character before was: 0 neither, 1 of writing without spaces, 2 a letter of other writing.
        var before = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            if (type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()) continue
            val kind = if (WordSpacing.unspaced(cp)) 1 else if (Character.isLetter(cp)) 2 else 0
            if (kind != 0 && before != 0 && kind != before) return true
            before = kind
        }
        return false
    }

    /** Whether [text] has two letters in a row somewhere. */
    private fun hasWord(text: String): Boolean {
        var run = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            // A combining mark belongs to the letter before it; a joiner (Persian writes one inside words) to the word.
            val type = Character.getType(cp)
            if (type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.FORMAT.toInt()) continue
            run = if (Character.isLetter(cp)) run + 1 else 0
            if (run >= 2) return true
        }
        return false
    }

    private val WHITESPACE = Regex("\\s+")
}

/**
 * A block's text cut into pieces short enough for one call of the translator, and the translated
 * pieces joined again. A translation engine takes one paragraph at a time, and a long one is
 * slower and may be cut short by it.
 */
object TextChunks {
    /** The most characters a piece has. */
    const val MAX_CHARS = 1200

    /** Ends a sentence, when a space or the end of the text follows (after closing quotes or brackets). */
    private const val SENTENCE_ENDS = ".!?…।॥؟۔։።፧။"

    /** Ends a sentence of writing without spaces, so nothing needs to follow it. */
    private const val UNSPACED_SENTENCE_ENDS = "。！？｡．"

    /** May follow the end of a sentence: closing quotes and brackets. */
    private const val CLOSERS = "\"'”’»)]}」』）】》〉›"

    /**
     * [text] cut into pieces of at most [max] characters, each trimmed: after the end of a sentence
     * where one is within reach, else at a space, else (Chinese, Japanese, Thai and the like, or a
     * single word longer than [max]) between two characters, never between a letter and the
     * marks on it or the halves of an emoji. Blank text gives no pieces.
     */
    fun split(text: String, max: Int = MAX_CHARS): List<String> {
        require(max >= 2) { "A piece must be allowed two characters at least" }
        val s = text.trim()
        if (s.isEmpty()) return emptyList()
        val pieces = ArrayList<String>()
        var start = 0
        while (s.length - start > max) {
            // The piece is s[start, cut): cut is at most start + max, and past start.
            val limit = start + max
            var cut = sentenceEnd(s, start, limit)
            if (cut <= start) cut = space(s, start, limit)
            if (cut <= start) cut = unspacedBoundary(s, start, limit)
            if (cut <= start) cut = boundary(s, start, limit)
            val piece = s.substring(start, cut).trim()
            if (piece.isNotEmpty()) pieces += piece
            start = cut
            while (start < s.length && s[start].isWhitespace()) start++
        }
        val rest = s.substring(start).trim()
        if (rest.isNotEmpty()) pieces += rest
        return pieces
    }

    /** The translations of the pieces of a text joined: with a space, or nothing between two characters of writing without spaces. */
    fun join(pieces: List<String>): String {
        val out = StringBuilder()
        for (piece in pieces) {
            val text = piece.trim()
            if (text.isEmpty()) continue
            if (out.isNotEmpty()) out.append(WordSpacing.between(out.codePointBefore(out.length), text.codePointAt(0)))
            out.append(text)
        }
        return out.toString()
    }

    /** The latest place in (start, limit] right after the end of a sentence, or -1. */
    private fun sentenceEnd(s: String, start: Int, limit: Int): Int {
        for (cut in limit downTo start + 1) {
            var j = cut - 1
            while (j > start && s[j] in CLOSERS) j--
            val c = s[j]
            if (c in UNSPACED_SENTENCE_ENDS) return cut
            if (c in SENTENCE_ENDS && (cut == s.length || s[cut].isWhitespace())) return cut
        }
        return -1
    }

    /** The latest place in (start, limit] where a space starts, or -1. */
    private fun space(s: String, start: Int, limit: Int): Int {
        for (cut in limit downTo start + 1) if (s[cut].isWhitespace()) return cut
        return -1
    }

    /** The latest place in (start, limit] beside a character of writing without spaces, not before a mark, or -1. */
    private fun unspacedBoundary(s: String, start: Int, limit: Int): Int {
        for (cut in limit downTo start + 1) {
            if (!canCut(s, cut)) continue
            if (WordSpacing.unspaced(s.codePointBefore(cut)) || WordSpacing.unspaced(s.codePointAt(cut))) return cut
        }
        return -1
    }

    /** The latest place in (start, limit] that splits no character from its marks, or limit itself when there is none. */
    private fun boundary(s: String, start: Int, limit: Int): Int {
        for (cut in limit downTo start + 1) if (canCut(s, cut)) return cut
        return limit
    }

    /** Whether [s] may be cut before [cut]: not between the halves of a character, nor before a mark that goes on the one before. */
    private fun canCut(s: String, cut: Int): Boolean {
        if (cut >= s.length) return true
        if (Character.isLowSurrogate(s[cut]) && Character.isHighSurrogate(s[cut - 1])) return false
        val type = Character.getType(s.codePointAt(cut))
        return type != Character.NON_SPACING_MARK.toInt() && type != Character.COMBINING_SPACING_MARK.toInt() &&
            type != Character.ENCLOSING_MARK.toInt() && s[cut] != '\u200D'
    }
}

/** Where words are written with spaces between them and where they aren't. */
internal object WordSpacing {
    /**
     * Whether [codePoint] belongs to writing that puts no spaces between its words: Chinese
     * characters, kana and bopomofo with their punctuation (、。「」, the full-width and half-width
     * forms among it), and Thai, Lao, Khmer and Myanmar letters. Korean has spaces.
     */
    fun unspaced(codePoint: Int): Boolean {
        if (codePoint < 0x0E00 || codePoint > Character.MAX_CODE_POINT) return false
        return when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.BOPOMOFO, Character.UnicodeScript.THAI, Character.UnicodeScript.LAO,
            Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR,
            -> true
            // 、。「」 and the like; kana's voiced marks and ゠ ・ ー; full-width punctuation and half-width ｡｢｣､･ ｰ ﾞ ﾟ.
            else -> codePoint in 0x3001..0x303F || codePoint in 0x3099..0x309C || codePoint == 0x30A0 || codePoint == 0x30FB ||
                codePoint == 0x30FC || codePoint in 0xFF01..0xFF0F || codePoint in 0xFF1A..0xFF20 || codePoint in 0xFF3B..0xFF40 ||
                codePoint in 0xFF5B..0xFF65 || codePoint == 0xFF70 || codePoint == 0xFF9E || codePoint == 0xFF9F
        }
    }

    /** What goes between two pieces of text, the first ending with [before] and the next starting with [after]: nothing between two unspaced characters, else a space. */
    fun between(before: Int, after: Int): String = if (unspaced(before) && unspaced(after)) "" else " "
}
