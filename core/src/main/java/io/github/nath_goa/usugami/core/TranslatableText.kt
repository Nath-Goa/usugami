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

    /**
     * "1.Introduction", "2.1.Scope", "1.مقدمة": a heading numbered with a full stop, its word
     * capitalised or not in Latin letters; not a domain or a file's name ("163.com", "1.txt", "1.JPG").
     */
    private val NUMBERED_HEADING = Regex("^(?:\\p{Nd}{1,3}\\.)+(?:\\p{Lu}\\p{Ll}|[^A-Za-z])")

    /** C:\Users, \\server\share, /home/me, ~/notes, ./a, ../b. */
    private val PATH = Regex("^(?:[A-Za-z]:[\\\\/].*|\\\\\\\\\\S+|~?/\\S+|\\.{1,2}/\\S+)$")

    /** Brackets, quotes and the punctuation that ends a sentence, around an address: as written with spaces, and as Chinese and Japanese write them. */
    private const val AROUND = "()[]{}<>\"'“”‘’«».,;:!?（）［］｛｝＜＞〈〉《》「」『』【】〔〕。、，．；：！？"

    // Addresses among words, for [protect]: in Latin letters, so that Chinese or Japanese around one ends it.
    private val EMAIL_IN = Regex("""(?<![A-Za-z0-9._%+\-])[A-Za-z0-9][A-Za-z0-9._%+\-]*@(?:[A-Za-z0-9](?:[A-Za-z0-9\-]*[A-Za-z0-9])?\.)+[A-Za-z]{2,24}(?![A-Za-z0-9\-])""")
    private val WEB_IN = Regex("""(?<![A-Za-z0-9@._\-/])(?:(?:https?|ftp)://|www\.)[A-Za-z0-9\-._~:/?#@!$&*+=%()]+""", RegexOption.IGNORE_CASE)

    /** A domain or a file's name among words: its first name of two characters at least ("e.g.", "p.ej." are words), its ending of small letters. */
    private val DOMAIN_IN = Regex("""(?<![A-Za-z0-9@._\-/\\])((?:[A-Za-z0-9_\-]{2,}\.)(?:[A-Za-z0-9_\-]+\.)*([a-z]{2,24}))(?![A-Za-z0-9_\-@])(?:/[A-Za-z0-9\-._~/?#=&%+]*)?""")
    private val PATH_IN = Regex("""(?<![^\s(\[{"'«“‘])(?:[A-Za-z]:\\[^\s]*|\\\\[^\s]+|~?/[A-Za-z0-9._\-]+(?:/[A-Za-z0-9._\-]+)+/?|~/[A-Za-z0-9._\-]+)""")

    /** Endings of domains other than countries' two letters, and of files' names. */
    private val ENDINGS = setOf(
        "com", "org", "net", "edu", "gov", "mil", "int", "info", "biz", "pro", "mobi", "asia", "aero", "coop", "museum", "app", "dev",
        "xyz", "online", "site", "store", "shop", "cloud", "tech", "blog", "email", "eus", "cat", "gal", "bzh", "paris", "berlin", "london",
        "pdf", "txt", "doc", "docx", "xls", "xlsx", "xlsm", "ppt", "pptx", "odt", "ods", "odp", "rtf", "csv", "tsv", "json", "xml", "html",
        "htm", "css", "jpg", "jpeg", "png", "gif", "bmp", "tif", "tiff", "webp", "svg", "heic", "wav", "avi", "mov", "mkv", "zip", "rar",
        "tar", "tgz", "apk", "exe", "msi", "dmg", "iso", "epub", "log", "ini", "cfg", "conf", "yml", "yaml", "java", "sql", "eml", "msg", "ics", "vcf",
    )

    // A word's kinds for [protect].
    private val CODE_WORD = Regex("[A-Z0-9]+(?:[-/.][A-Z0-9]+)*")
    private val DIGIT_GROUP = Regex("""\+?\d+""")
    private val PHONE_GROUP = Regex("""\+?\(\d{1,5}\)\d*""")
    private val HYPHENED_DIGITS = Regex("""\d+(?:-\d+)+""")
    private val DOTTED_DIGITS = Regex("""\d+(?:\.\d+){3,}""")

    /** A bank's code (SWIFT's), and what it is called before it. */
    private val BIC = Regex("[A-Z]{6}[A-Z0-9]{2}(?:[A-Z0-9]{3})?")
    private val BIC_NAMES = setOf("BIC", "SWIFT", "BIC/SWIFT", "SWIFT/BIC", "BIC-SWIFT", "SWIFT-BIC")

    /** What parts the words of a postcode, an IBAN or a phone number: one space, or one that doesn't break. */
    private const val JOINING_SPACES = " \u00A0\u202F"

    /**
     * Whether [text] (a block of a page) is worth translating. Not when it has fewer than two
     * letters; when each of its words is a web address, an e-mail address or a file's name or path
     * (in any script; one that Chinese, Japanese or Thai words run into, with no space, is among words);
     * when it is a number, a date, an amount or a code with no word in it (digits with punctuation,
     * and letters only one at a time: "12.50", "03/04/2025", "A-12-B", "2025年3月"); when it is one run
     * of capitals and digits, a code ("LB-204", "AMP-12"); or when it is
     * one run of more than 40 characters without a space, in writing that has spaces, that has a
     * digit in it (an id). A word is two letters or more in a row, so "Invoice 12" is translated, and
     * so is "U.S.A.", which has no digit. What isn't translated stays as it is on the page.
     */
    fun isWorthTranslating(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.codePoints().filter(Character::isLetter).limit(2).count() < 2) return false
        val tokens = trimmed.split(WHITESPACE)
        if (isAddresses(trimmed)) return false
        // Letters one at a time among digits: a code. Without digits ("U.S.A.") it may be an abbreviation.
        if (!hasWord(trimmed) && trimmed.any(Char::isDigit)) return false
        // One run of capitals and digits: an item's code or a reference ("LB-204", "AMP-12", "F-2026-0412"),
        // which a translator would change ("Lb-204").
        if (tokens.size == 1 && isCode(trimmed)) return false
        // Chinese, Japanese and Thai put no space between words, so a whole paragraph of them is one run.
        if (tokens.size == 1 && trimmed.length > LONG_TOKEN && trimmed.any(Char::isDigit) &&
            trimmed.codePoints().noneMatch(WordSpacing::unspaced)
        ) {
            return false
        }
        return true
    }

    /**
     * Whether each word of [text] is a web or e-mail address or a file's name or path (see
     * [isWorthTranslating]), or the whole of it is a path: a Windows path may have spaces in it
     * (C:\Program Files\...). Blank text isn't.
     */
    fun isAddresses(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return trimmed.split(WHITESPACE).all(::isAddress) || PATH.matches(trimmed)
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
     * letters, or it is numbered as a heading is: those are headings ("1.公司简介", "一.概述", "1.Introduction").
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

    /**
     * Whether [text], a run without spaces, is a code: digits and capital letters, every letter a
     * capital ("LB-204", "COVID-19"); not letters of writing without capitals among digits ("東京2020").
     */
    private fun isCode(text: String): Boolean =
        text.any(Char::isDigit) && text.any(Char::isUpperCase) && text.none { it.isLetter() && !it.isUpperCase() }

    /**
     * [text] with what the translator must give back as it is replaced by numbered placeholders
     * written as [form] says, for it doesn't know such things and rewrites them ("LET-2026-0457" to
     * "LAG-2026-0457", "BS6 5RT" in Hindi letters, a letter of an e-mail address changed). Those are:
     * - e-mail and web addresses, domains and files' names ("example.co.uk", "report_2026.pdf"), paths;
     * - codes and references: capitals and digits, maybe parted by - / or ., with a capital and a digit
     *   in them ("LET-2026-0457", "CMCIFR2A", "AMP-12", "3B"), and those beside each other with the
     *   digit groups and capitals around them: a postcode ("BS6 5RT", "M3 3JE"), an IBAN
     *   ("FR76 3000 6000 0112 3456 7890 189", "GB33 BUKB 2045 7741 2678 90");
     * - digits joined by hyphens, five or more or in three groups at least ("441-10", "20-45-77"), or
     *   by full stops in four groups at least ("01.23.45.67.89");
     * - phone numbers: groups of digits with eight digits at least, maybe with a + or a group in
     *   brackets before ("0117 496 0321", "+44 (0)161 496 0732"); and such digits after a country's
     *   two capitals, an id ("GB 412 6789 03", "DE 412 678 903").
     * Not amounts and dates, which the translator writes its language's way ("742,00 €", "15/10/2026",
     * "1 234 567": digits in groups of three after the first are a number), ordinary words in capitals
     * ("TOTAL", "BIC"; but a bank's code after "BIC" or "SWIFT" is kept, "BNPAFRPP"), nor names: no rule
     * knows a name. Placeholders written in [text] already are kept too, so that each placeholder in
     * [ProtectedText.masked] is one of its own.
     */
    fun protect(text: String, form: Placeholder = Placeholder.BRACES): ProtectedText {
        val spans = ArrayList<IntRange>()
        for (pattern in listOf(EMAIL_IN, WEB_IN, DOMAIN_IN, PATH_IN)) {
            for (match in pattern.findAll(text)) {
                val end = trimmedEnd(text, match.range.first, match.range.last + 1)
                if (pattern === DOMAIN_IN && !isDomainEnding(match.groupValues[2], match.groupValues[1])) continue
                if (end > match.range.first) spans += match.range.first until end
            }
        }
        for (placeholder in Placeholder.entries) placeholder.found.findAll(text).forEach { spans += it.range }
        codesAndNumbers(text, spans)
        if (spans.isEmpty()) return ProtectedText(text, emptyList(), form)
        // The longest of those starting first, then the next that doesn't overlap it.
        spans.sortWith(compareBy<IntRange> { it.first }.thenByDescending { it.last })
        val masked = StringBuilder()
        val tokens = ArrayList<String>()
        var at = 0
        for (span in spans) {
            if (span.first < at) continue
            masked.append(text, at, span.first)
            tokens += text.substring(span.first, span.last + 1)
            masked.append(form.of(tokens.size))
            at = span.last + 1
        }
        masked.append(text, at, text.length)
        return ProtectedText(masked.toString(), tokens, form)
    }

    /** Where an address matched from [start] to [end] in [text] ends without the punctuation after it, and a closing bracket it doesn't open. */
    private fun trimmedEnd(text: String, start: Int, end: Int): Int {
        var e = end
        while (e > start) {
            val c = text[e - 1]
            val unopened = c == ')' && text.substring(start, e).count { it == '(' } < text.substring(start, e).count { it == ')' }
            if (c in ".,;:!?'\"" || unopened) e-- else break
        }
        return e
    }

    /**
     * Whether a name ending in [ending] is a domain or a file's name: the ending of a country (two
     * letters) or one of [ENDINGS], and a letter in the name before it, so that two words a full stop
     * joins without a space ("maison.les") stay words.
     */
    private fun isDomainEnding(ending: String, name: String): Boolean =
        (ending.length == 2 || ending in ENDINGS) && name.dropLast(ending.length + 1).any { it.isLetter() }

    /**
     * Adds to [spans] the codes, postcodes, IBANs, joined digits and phone numbers of [text] (see
     * [protect]). Its words are what spaces part, and the writing without spaces (Chinese,
     * Japanese, Thai...) around them: such writing runs into a code ("型番ABC-1234X-JPの").
     */
    private fun codesAndNumbers(text: String, spans: MutableList<IntRange>) {
        val words = ArrayList<Word>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isWhitespace(cp) || cp == 0xA0 || cp == 0x202F || WordSpacing.unspaced(cp)) {
                i += Character.charCount(cp)
                continue
            }
            var end = i
            while (end < text.length) {
                val c = text.codePointAt(end)
                if (Character.isWhitespace(c) || c == 0xA0 || c == 0x202F || WordSpacing.unspaced(c)) break
                end += Character.charCount(c)
            }
            words += Word.of(text, i, end)
            i = end
        }
        // A bank's BIC has no digit, often ("BNPAFRPP"), but follows its name ("BIC: BNPAFRPP").
        for ((k, word) in words.withIndex()) {
            if (word.core !in BIC_NAMES) continue
            val next = words.getOrNull(if (words.getOrNull(k + 1)?.core?.isEmpty() == true) k + 2 else k + 1) ?: continue
            if (BIC.matches(next.core)) spans += next.lead until next.trail
        }
        var start = 0
        while (start < words.size) {
            var end = start + 1
            if (words[start].kind != Kind.OTHER) {
                // Beside each other: one space between, no punctuation.
                while (end < words.size && words[end].kind != Kind.OTHER && words[end - 1].trail == words[end - 1].end &&
                    words[end].lead == words[end].start && words[end].start - words[end - 1].end == 1 && text[words[end - 1].end] in JOINING_SPACES
                ) {
                    end++
                }
                run(words.subList(start, end), spans)
            }
            start = end
        }
    }

    /** Adds to [spans] what of [run], words beside each other none of which is [Kind.OTHER], is a code or a phone number. */
    private fun run(run: List<Word>, spans: MutableList<IntRange>) {
        val first = run.indexOfFirst { it.kind == Kind.CODE }
        if (first >= 0) {
            // From the first code to the last, the capitals and digits between them included ("BS6 5RT",
            // "GB33 BUKB 2045"); not a word in capitals before ("BIC", "IBAN") or after.
            var last = run.indexOfLast { it.kind == Kind.CODE }
            var end = run.size - 1
            while (end > last && run[end].kind == Kind.CAPITALS) end--
            // The digit groups after it when there are two at least (an IBAN's), not a number that only follows a reference.
            if (run.subList(last + 1, end + 1).count { it.kind == Kind.DIGITS || it.kind == Kind.JOINED_DIGITS } >= 2) last = end
            spans += run[first].lead until run[last].trail
            return
        }
        // No code: the digit groups between words in capitals, each with two capitals before it, a
        // country's: then they are an id, also in groups of three ("GB 412 6789 03", "DE 412 678 903").
        var i = 0
        while (i < run.size) {
            if (run[i].kind == Kind.CAPITALS) {
                i++
                continue
            }
            var j = i
            while (j < run.size && run[j].kind != Kind.CAPITALS) j++
            val groups = run.subList(i, j)
            val country = i > 0 && run[i - 1].core.length == 2
            if (isPhone(groups) || country && groups.sumOf { w -> w.core.count(Char::isDigit) } >= 8) {
                spans += run[if (country) i - 1 else i].lead until run[j - 1].trail
            } else {
                groups.filter { it.kind == Kind.JOINED_DIGITS }.forEach { spans += it.lead until it.trail }
            }
            i = j
        }
    }

    /** Whether [groups] of digits, beside each other, are a phone number: eight digits at least, in two groups at least, not a number in thousands. */
    private fun isPhone(groups: List<Word>): Boolean {
        if (groups.size < 2 || groups.sumOf { w -> w.core.count(Char::isDigit) } < 8) return false
        val plain = groups.all { w -> w.core.all(Char::isDigit) }
        val thousands = plain && groups[0].core.length <= 3 && groups.drop(1).all { it.core.length == 3 }
        return !thousands
    }

    /** What a word is to [protect]. */
    private enum class Kind { CODE, CAPITALS, DIGITS, JOINED_DIGITS, OTHER }

    /**
     * A word of a text from [start] to [end]; [lead] to [trail] its [core], without the quotes,
     * brackets and punctuation around it (but a phone number's group in brackets, "(0161)").
     */
    private class Word(val start: Int, val end: Int, val lead: Int, val trail: Int, val core: String, val kind: Kind) {
        companion object {
            fun of(text: String, start: Int, end: Int): Word {
                var trail = end
                while (trail > start && text[trail - 1] in ".,;:!?\"'»”’…") trail--
                var lead = start
                while (lead < trail && text[lead] in "\"'«“‘¿¡") lead++
                val quoted = text.substring(lead, trail)
                if (PHONE_GROUP.matches(quoted)) return Word(start, end, lead, trail, quoted, Kind.DIGITS)
                while (trail > lead && text[trail - 1] in ")]}") trail--
                while (lead < trail && text[lead] in "([{") lead++
                val core = text.substring(lead, trail)
                val kind = when {
                    CODE_WORD.matches(core) && core.any(Char::isDigit) && core.any { it in 'A'..'Z' } -> Kind.CODE
                    core.length in 2..4 && core.all { it in 'A'..'Z' } -> Kind.CAPITALS
                    DIGIT_GROUP.matches(core) -> Kind.DIGITS
                    HYPHENED_DIGITS.matches(core) && (core.count { it == '-' } >= 2 || core.count(Char::isDigit) >= 5) -> Kind.JOINED_DIGITS
                    DOTTED_DIGITS.matches(core) -> Kind.JOINED_DIGITS
                    else -> Kind.OTHER
                }
                return Word(start, end, lead, trail, core, kind)
            }
        }
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
 * How [TranslatableText.protect] writes its placeholders, tried in this order: {1}, which ML Kit's
 * translator gave back as it was in nearly every sentence tried (French, English, German and Spanish
 * into English, French, German, Hindi, Arabic, Japanese and Chinese), then [1], then ⟦1⟧, which it
 * kept in the dense French paragraphs it lost or doubled the others in going into Hindi, though not
 * into Japanese.
 */
enum class Placeholder(private val open: Char, private val close: Char, pattern: String) {
    BRACES('{', '}', """[{｛]\s*(\p{Nd}{1,4})\s*[}｝]"""),
    BRACKETS('[', ']', """[\[［【〔]\s*(\p{Nd}{1,4})\s*[\]］】〕]"""),
    WHITE_BRACKETS('⟦', '⟧', """[⟦〚]\s*(\p{Nd}{1,4})\s*[⟧〛]"""),
    ;

    /** Placeholder number [n]. */
    fun of(n: Int): String = "$open$n$close"

    /** A placeholder as a translator may give it back: its brackets of another width, spaces inside them, its digits of another script. */
    internal val found = Regex(pattern)
}

/** A text with what must come back from the translator as it is, its [tokens], replaced by placeholders (see [TranslatableText.protect]). */
class ProtectedText internal constructor(
    /** What goes to the translator. */
    val masked: String,
    /** What the placeholders stand for, in order: the first for placeholder 1. */
    val tokens: List<String>,
    private val form: Placeholder,
) {
    /**
     * [translated], the translation of [masked], with each placeholder replaced by what it stands for;
     * null when one is missing, is there twice, isn't one of [masked]'s, or has a digit right after it,
     * which none has in [masked] ("⟦1⟧1°"): the translator didn't keep them, and what it made of the
     * text can't be put together again. In a translation written right to left, [leftToRight] puts
     * each between left-to-right marks: else digits parted by spaces ("0117 496 0321") are laid out
     * from the right, their last group first.
     */
    fun restore(translated: String, leftToRight: Boolean = false): String? {
        if (tokens.isEmpty()) return translated
        val seen = BooleanArray(tokens.size)
        val out = StringBuilder(translated.length + tokens.sumOf { it.length + 2 })
        var at = 0
        for (match in form.found.findAll(translated)) {
            val n = number(match.groupValues[1])
            if (n !in 1..tokens.size || seen[n - 1]) return null
            if (match.range.last + 1 < translated.length && Character.isDigit(translated.codePointAt(match.range.last + 1))) return null
            seen[n - 1] = true
            out.append(translated, at, match.range.first)
            if (leftToRight) out.append(LEFT_TO_RIGHT_MARK).append(tokens[n - 1]).append(LEFT_TO_RIGHT_MARK) else out.append(tokens[n - 1])
            at = match.range.last + 1
        }
        if (!seen.all { it }) return null
        return out.append(translated, at, translated.length).toString()
    }

    /**
     * [translated], a translation of the text itself (not [masked]), when the translator gave each of
     * [tokens] back as it was, as often as the text has it and not run into a word: its sentence,
     * which the translator saw whole, reads better than one with placeholders, which it may translate
     * less well around them. Null when it changed one. [leftToRight] as in [restore].
     */
    fun unchangedIn(translated: String, leftToRight: Boolean = false): String? {
        val places = ArrayList<IntRange>()
        for ((token, count) in tokens.groupingBy { it }.eachCount()) {
            var found = 0
            var at = translated.indexOf(token)
            while (at >= 0) {
                val end = at + token.length
                if ((at == 0 || !latin(translated[at - 1])) && (end == translated.length || !latin(translated[end]))) {
                    found++
                    places += at until end
                }
                at = translated.indexOf(token, at + 1)
            }
            if (found != count) return null
        }
        if (!leftToRight || places.isEmpty()) return translated
        places.sortBy { it.first }
        val out = StringBuilder(translated.length + 2 * places.size)
        var at = 0
        for (place in places) {
            if (place.first < at) continue
            out.append(translated, at, place.first).append(LEFT_TO_RIGHT_MARK).append(translated, place.first, place.last + 1).append(LEFT_TO_RIGHT_MARK)
            at = place.last + 1
        }
        return out.append(translated, at, translated.length).toString()
    }

    private companion object {
        const val LEFT_TO_RIGHT_MARK = '\u200E'

        /** Whether [c] makes a token it touches part of another word: a Latin letter or a digit. */
        fun latin(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9'
    }

    /** The number [digits] writes, in any script's digits. */
    private fun number(digits: String): Int {
        var n = 0
        var i = 0
        while (i < digits.length) {
            val cp = digits.codePointAt(i)
            n = n * 10 + Character.digit(cp, 10)
            i += Character.charCount(cp)
        }
        return n
    }
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

    /** The most closing quotes and brackets looked past for the end of a sentence, so a long run of them is cut as fast as any long word. */
    private const val MAX_CLOSERS = 7

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
            while (j > start && j >= cut - MAX_CLOSERS && s[j] in CLOSERS) j--
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
