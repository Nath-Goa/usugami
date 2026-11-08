package io.github.nath_goa.usugami.core

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.floor

/**
 * Symbols and names used to show numbers and dates the way the phone's language writes them.
 * [months] and [shortMonths] hold the twelve months from January, [days] and [shortDays] the seven
 * days from Sunday. A name missing from them is taken from [ENGLISH].
 */
data class SheetLocale(
    val decimal: Char,
    val grouping: Char,
    val months: List<String>,
    val shortMonths: List<String>,
    val days: List<String>,
    val shortDays: List<String>,
    val am: String,
    val pm: String,
) {
    companion object {
        /** American English, the language Excel's built-in formats are written for. */
        val ENGLISH = SheetLocale(
            decimal = '.',
            grouping = ',',
            months = listOf(
                "January", "February", "March", "April", "May", "June",
                "July", "August", "September", "October", "November", "December",
            ),
            shortMonths = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"),
            days = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"),
            shortDays = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"),
            am = "AM",
            pm = "PM",
        )
    }
}

/** How a cell's value reads, and the colour its number format asks for ([Red] etc.), as ARGB or null. */
data class CellText(val text: String, val colour: Int?)

/**
 * Numbers, dates and times in spreadsheet cells, shown as Excel shows them through the cell's number
 * format code, such as "#,##0.00", "d-mmm-yy" or "[Red]0%;(0%)".
 *
 * A code has up to four sections: for positive numbers, negative numbers, zero and text. Conditions
 * such as [>=100] can pick the section instead. Numbers keep Excel's 15 significant digits and round
 * half away from zero. Dates are counts of days from 1900 (with the 29 February 1900 that Excel took
 * over from Lotus 1-2-3) or from 1904, and times are fractions of a day. A code Excel wouldn't accept
 * shows the number as General.
 */
object SpreadsheetNumbers {
    /** Excel keeps codes to 255 characters; a much longer one is treated as broken, so a crafted file can't slow every cell. */
    private const val MAX_CODE = 1024

    /** The serials of 31 December 9999, the last day Excel shows, in the 1900 and 1904 systems. */
    private const val LAST_DAY_1900 = 2_958_465L
    private const val LAST_DAY_1904 = 2_957_003L

    /** General never takes more characters than this, however wide the column. */
    private const val GENERAL_WIDTH = 11

    /** A fraction's denominator has at most this many digits: nearer fractions than that are finer than a double. */
    private const val MAX_DENOMINATOR_DIGITS = 7

    private val PRECISION = MathContext(15, RoundingMode.HALF_UP)

    private val BUILT_IN = mapOf(
        0 to "General",
        1 to "0",
        2 to "0.00",
        3 to "#,##0",
        4 to "#,##0.00",
        5 to "\"$\"#,##0_);\\(\"$\"#,##0\\)",
        6 to "\"$\"#,##0_);[Red]\\(\"$\"#,##0\\)",
        7 to "\"$\"#,##0.00_);\\(\"$\"#,##0.00\\)",
        8 to "\"$\"#,##0.00_);[Red]\\(\"$\"#,##0.00\\)",
        9 to "0%",
        10 to "0.00%",
        11 to "0.00E+00",
        12 to "# ?/?",
        13 to "# ??/??",
        14 to "m/d/yyyy",
        15 to "d-mmm-yy",
        16 to "d-mmm",
        17 to "mmm-yy",
        18 to "h:mm AM/PM",
        19 to "h:mm:ss AM/PM",
        20 to "h:mm",
        21 to "h:mm:ss",
        22 to "m/d/yyyy h:mm",
        37 to "#,##0_);(#,##0)",
        38 to "#,##0_);[Red](#,##0)",
        39 to "#,##0.00_);(#,##0.00)",
        40 to "#,##0.00_);[Red](#,##0.00)",
        41 to "_(* #,##0_);_(* \\(#,##0\\);_(* \"-\"_);_(@_)",
        42 to "_(\"$\"* #,##0_);_(\"$\"* \\(#,##0\\);_(\"$\"* \"-\"_);_(@_)",
        43 to "_(* #,##0.00_);_(* \\(#,##0.00\\);_(* \"-\"??_);_(@_)",
        44 to "_(\"$\"* #,##0.00_);_(\"$\"* \\(#,##0.00\\);_(\"$\"* \"-\"??_);_(@_)",
        45 to "mm:ss",
        46 to "[h]:mm:ss",
        47 to "mm:ss.0",
        48 to "##0.0E+0",
        49 to "@",
    )

    private val NAMED_COLOURS = mapOf(
        "black" to 0x000000,
        "blue" to 0x0000FF,
        "cyan" to 0x00FFFF,
        "green" to 0x00FF00,
        "magenta" to 0xFF00FF,
        "red" to 0xFF0000,
        "white" to 0xFFFFFF,
        "yellow" to 0xFFFF00,
    )

    /** Excel's default palette, the colours of [Color1] to [Color56]. */
    private val PALETTE = intArrayOf(
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
        0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
        0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
        0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
        0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
        0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333,
    )

    /** Codes already read: a sheet uses a handful of them across thousands of cells. */
    private val parsed = object : LinkedHashMap<String, Format>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Format>?) = size > 256
    }

    /**
     * The format code of built-in number format [id] (0..49, the ids Excel doesn't store in styles.xml),
     * or null. Ids 5 to 8 are Excel's dollar formats; 23 to 26 have no format of their own and are
     * General. Ids 27 to 36 are East Asian dates and times, which come out here as the short date, or
     * as times for 32 and 33.
     */
    fun builtInFormat(id: Int): String? = when (id) {
        in 23..26 -> "General"
        in 27..31, in 34..36 -> "m/d/yyyy"
        32 -> "h:mm"
        33 -> "h:mm:ss"
        else -> BUILT_IN[id]
    }

    /** Whether [formatCode] shows a date or time (so the cell's number is a date serial). */
    fun isDateFormat(formatCode: String): Boolean = formatOf(formatCode).numeric.any { it.isDate }

    /**
     * [value] shown with [formatCode] as Excel shows it; General uses at most [maxChars] characters
     * (not counting a minus) like Excel's General in a narrow column. A date out of Excel's range, such
     * as a negative one, fills [maxChars] characters with #, as Excel fills the cell.
     */
    fun format(
        value: Double,
        formatCode: String,
        locale: SheetLocale = SheetLocale.ENGLISH,
        date1904: Boolean = false,
        maxChars: Int = 11,
    ): CellText {
        if (!value.isFinite()) return CellText("#NUM!", null)
        val (section, unsigned) = choose(formatOf(formatCode), value) ?: return CellText(general(value, locale, maxChars), null)
        val text = if (section.isDate) {
            dateTime(if (unsigned) abs(value) else value, section, locale, date1904, maxChars)
        } else {
            number(abs(value), value < 0 && !unsigned, section, locale, maxChars)
        }
        return CellText(text, section.colour)
    }

    /** A text cell shown with the text section of [formatCode] (the 4th section with @), or as itself. */
    fun formatText(text: String, formatCode: String): CellText {
        val section = formatOf(formatCode).text ?: return CellText(text, null)
        val shown = StringBuilder()
        for (token in section.tokens) {
            when (token) {
                Token.At -> shown.append(text)
                is Token.Literal -> shown.append(token.text)
                else -> {}
            }
        }
        return CellText(shown.toString(), section.colour)
    }

    /** One piece of a section of a format code. */
    private sealed interface Token {
        /** Text shown as it is. */
        data class Literal(val text: String) : Token

        /** A digit placeholder: 0 shows a digit or 0, # a digit or nothing, and ? a digit or a space. */
        data class Digit(val kind: Char) : Token

        data object Point : Token

        /** A comma: a thousands separator between digits, a division by 1000 after them, or else itself. */
        data object Comma : Token

        /** % or ‰, which also multiply the number by 100 or 1000. */
        data class Percent(val sign: Char) : Token

        /** E+ or E- (or e+, e-) before the exponent's digits; with E- a positive exponent has no sign. */
        data class Exponent(val letter: Char, val plus: Boolean) : Token

        data object Slash : Token

        /** A fraction's fixed denominator, such as the 16 of "# ??/16". */
        data class Denominator(val value: Int) : Token

        data object General : Token

        /** @, where a text section puts the cell's text. */
        data object At : Token

        /**
         * A part of a date or time, its letter written [count] times: y (year), m (month), n (a minute,
         * written m), d (day), h (hour), s (second) or e (year).
         */
        data class DatePart(val kind: Char, val count: Int) : Token

        /** [h], [mm] or [ss]: the hours, minutes or seconds in all, which don't start again each day or hour. */
        data class Elapsed(val kind: Char, val count: Int) : Token

        /** AM/PM, or A/P when [short]. As in Excel, A/P is in small letters when [lower], and AM/PM is always in capitals. */
        data class AmPm(val short: Boolean, val lower: Boolean) : Token

        /** Tenths, hundredths or thousandths of a second, [digits] of them, after the seconds. */
        data class SubSecond(val digits: Int) : Token
    }

    /** What a section of a code is read as: digits, a date or time, or the text section. */
    private enum class Mode { NUMBER, DATE, TEXT }

    /** A section's condition, such as [>=100]: the section shows the numbers that meet it. */
    private class Condition(val op: String, val limit: Double) {
        fun holds(v: Double): Boolean = when (op) {
            "<" -> v < limit
            "<=" -> v <= limit
            ">" -> v > limit
            ">=" -> v >= limit
            "=" -> v == limit
            else -> v != limit
        }

        /** Whether only negative numbers (and zero) meet it, as with [<0]: those show without their minus. */
        val negativeOnly get() = limit == 0.0 && (op == "<" || op == "<=")
    }

    /** One section of a code, read: its pieces, colour and condition. */
    private class Section(val tokens: List<Token>, val colour: Int?, val condition: Condition?, mode: Mode) {
        val isDate = mode == Mode.DATE
        val layout = if (mode == Mode.NUMBER) NumberLayout(tokens) else null
        val subSecondDigits = tokens.maxOfOrNull { (it as? Token.SubSecond)?.digits ?: 0 } ?: 0
        val twelveHour = tokens.any { it is Token.AmPm }
    }

    /** A whole code: the sections for numbers, at most three, and the one for text, if any. No sections means General. */
    private class Format(val numeric: List<Section>, val text: Section?)

    private val GENERAL = Format(emptyList(), null)

    /**
     * Where a number section puts its digits: the token indices of the placeholders of the whole part,
     * the decimals, the exponent, and a fraction's numerator and denominator, each left to right.
     */
    private class NumberLayout(val tokens: List<Token>) {
        val general = tokens.indexOf(Token.General)
        val exponent: Int
        val exponentAt: IntArray
        val point: Int
        val slash: Int
        val numeratorAt: IntArray
        val denominatorAt: IntArray
        val fixedDenominator: Int
        val wholeAt: IntArray
        val decimalsAt: IntArray

        /** Commas that separate thousands or divide by 1000, and so show nothing themselves. */
        val hidden = BooleanArray(tokens.size)
        val grouped: Boolean

        /** The power of ten the number is multiplied by: 2 for each %, 3 for each ‰, -3 for each comma at the end. */
        val shift: Int

        init {
            fun isDigit(i: Int) = tokens.getOrNull(i) is Token.Digit
            fun run(from: Int): IntArray {
                var end = from
                while (isDigit(end)) end++
                return IntArray(end - from) { from + it }
            }
            fun digitsIn(range: IntRange) = range.filter(::isDigit).toIntArray()

            exponent = tokens.indices.firstOrNull { tokens[it] is Token.Exponent && isDigit(it + 1) } ?: -1
            exponentAt = if (exponent >= 0) run(exponent + 1) else IntArray(0)
            val mantissaEnd = if (exponent >= 0) exponent else tokens.size
            point = (0 until mantissaEnd).firstOrNull { tokens[it] == Token.Point } ?: -1
            slash = if (exponent >= 0 || point >= 0) {
                -1
            } else {
                (1 until tokens.size - 1).firstOrNull {
                    tokens[it] == Token.Slash && isDigit(it - 1) && (isDigit(it + 1) || tokens[it + 1] is Token.Denominator)
                } ?: -1
            }
            if (slash >= 0) {
                var start = slash - 1
                while (isDigit(start - 1)) start--
                numeratorAt = IntArray(slash - start) { start + it }
                fixedDenominator = (tokens[slash + 1] as? Token.Denominator)?.value ?: 0
                denominatorAt = if (fixedDenominator > 0) IntArray(0) else run(slash + 1)
                wholeAt = digitsIn(0 until start)
                decimalsAt = IntArray(0)
            } else {
                numeratorAt = IntArray(0)
                denominatorAt = IntArray(0)
                fixedDenominator = 0
                wholeAt = digitsIn(0 until (if (point >= 0) point else mantissaEnd))
                decimalsAt = if (point >= 0) digitsIn(point + 1 until mantissaEnd) else IntArray(0)
            }

            var group = false
            var scale = 0
            val lastDigit = maxOf(wholeAt.lastOrNull() ?: -1, decimalsAt.lastOrNull() ?: -1, point)
            // A comma right after the last digit, or after another such comma, divides by 1000: #,##0, shows thousands.
            fun trailing(i: Int) =
                slash < 0 && i in lastDigit + 1 until mantissaEnd && (lastDigit + 1 until i).all { tokens[it] == Token.Comma }
            for (i in tokens.indices) {
                if (tokens[i] != Token.Comma) continue
                if (wholeAt.any { it < i } && wholeAt.any { it > i }) {
                    group = true
                    hidden[i] = true
                } else if (trailing(i)) {
                    scale -= 3
                    hidden[i] = true
                }
            }
            grouped = group
            shift = scale + tokens.sumOf {
                when ((it as? Token.Percent)?.sign) {
                    '%' -> 2
                    '‰' -> 3
                    else -> 0
                }
            }
        }
    }

    /** [code] read, from the codes already read when it is one of them. */
    private fun formatOf(code: String): Format {
        synchronized(parsed) { parsed[code] }?.let { return it }
        val format = parse(code)
        synchronized(parsed) { parsed[code] = format }
        return format
    }

    /** Reads a whole code; a broken one (an unclosed quote or bracket, five sections) reads as General. */
    private fun parse(code: String): Format {
        if (code.length > MAX_CODE || code.isBlank() || code.trim().equals("General", ignoreCase = true)) return GENERAL
        val parts = split(code) ?: return GENERAL
        if (parts.size > 4) return GENERAL
        // The fourth section is for text; with fewer, a last section with @ in it is.
        val last = section(parts.last(), Mode.TEXT)
        val textIndex = when {
            parts.size == 4 -> 3
            last?.tokens?.contains(Token.At) == true -> parts.lastIndex
            else -> -1
        }
        val numeric = ArrayList<Section>()
        for ((i, part) in parts.withIndex()) {
            if (i == textIndex) continue
            numeric += section(part, if (showsDate(part)) Mode.DATE else Mode.NUMBER) ?: return GENERAL
        }
        return Format(numeric, if (textIndex >= 0) last ?: return GENERAL else null)
    }

    /**
     * [code] cut at its semicolons, but not at those in quotes or brackets or after \, _ or *; null
     * when a quote or bracket is left open.
     */
    private fun split(code: String): List<String>? {
        val parts = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < code.length) {
            when (code[i]) {
                '"' -> i = code.indexOf('"', i + 1).takeIf { it >= 0 }?.plus(1) ?: return null
                '[' -> i = code.indexOf(']', i + 1).takeIf { it >= 0 }?.plus(1) ?: return null
                '\\', '_', '*' -> i += 2
                ';' -> {
                    parts += code.substring(start, i)
                    start = ++i
                }
                else -> i++
            }
        }
        parts += code.substring(minOf(start, code.length))
        return parts
    }

    /** Whether a section shows a date or time: it has y, m, d, h or s outside quotes and brackets, or [h], [m] or [s]. */
    private fun showsDate(part: String): Boolean {
        var i = 0
        while (i < part.length) {
            val c = part[i]
            when (c) {
                '"' -> i = part.indexOf('"', i + 1).takeIf { it >= 0 }?.plus(1) ?: return false
                '[' -> {
                    val end = part.indexOf(']', i + 1).takeIf { it >= 0 } ?: return false
                    if (elapsedUnit(part.substring(i + 1, end)) != null) return true
                    i = end + 1
                }
                '\\', '_', '*' -> i += 2
                else -> {
                    if (c.lowercaseChar() in "ymdhs") return true
                    i++
                }
            }
        }
        return false
    }

    /** The unit of an elapsed-time bracket, h, m or s for [h], [mm] or [ss], or null for any other bracket. */
    private fun elapsedUnit(inner: String): Char? {
        val unit = inner.firstOrNull()?.lowercaseChar() ?: return null
        return unit.takeIf { it in "hms" && inner.all { c -> c.lowercaseChar() == unit } }
    }

    /** Reads one section in [mode]; null when a quote or bracket in it isn't closed. */
    private fun section(part: String, mode: Mode): Section? {
        val tokens = ArrayList<Token>()
        var colour: Int? = null
        var condition: Condition? = null
        var i = 0
        while (i < part.length) {
            val c = part[i]
            when {
                c == '"' -> {
                    val end = part.indexOf('"', i + 1).takeIf { it >= 0 } ?: return null
                    tokens += Token.Literal(part.substring(i + 1, end))
                    i = end + 1
                }
                c == '\\' || c == '_' || c == '*' -> {
                    // The character after them, a whole one even beyond the BMP: shown for \, its width as a space
                    // for _, and repeated to fill the cell for *, which a PDF's fixed text leaves out.
                    val next = if (i + 1 < part.length) Character.charCount(part.codePointAt(i + 1)) else 0
                    if (c == '\\') tokens += Token.Literal(part.substring(i + 1, i + 1 + next))
                    if (c == '_') tokens += Token.Literal(" ")
                    i += 1 + next
                }
                c == '[' -> {
                    val end = part.indexOf(']', i + 1).takeIf { it >= 0 } ?: return null
                    val inner = part.substring(i + 1, end)
                    val name = inner.lowercase()
                    val unit = elapsedUnit(inner)
                    when {
                        name in NAMED_COLOURS -> colour = argb(NAMED_COLOURS.getValue(name))
                        name.startsWith("color") -> {
                            val number = name.drop(5).toIntOrNull()
                            if (number != null && number in 1..56) colour = argb(PALETTE[number - 1])
                        }
                        inner.startsWith('<') || inner.startsWith('>') || inner.startsWith('=') -> condition = condition(inner) ?: condition
                        // A currency, [$€-x-euro2] or [$₹-4009], shows its symbol; a bare locale, [$-409], shows nothing.
                        inner.startsWith('$') -> {
                            val symbol = inner.drop(1).substringBefore('-')
                            if (symbol.isNotEmpty()) tokens += Token.Literal(symbol)
                        }
                        mode == Mode.DATE && unit != null -> tokens += Token.Elapsed(unit, inner.length)
                    }
                    i = end + 1
                }
                mode == Mode.TEXT -> {
                    tokens += if (c == '@') Token.At else Token.Literal(c.toString())
                    i++
                }
                mode == Mode.NUMBER -> i = numberToken(part, i, tokens)
                else -> i = dateToken(part, i, tokens)
            }
        }
        if (mode == Mode.DATE) markMinutes(tokens)
        return Section(tokens, colour, condition, mode)
    }

    /** Reads the number-section token at [i] into [tokens]; returns where the next one starts. */
    private fun numberToken(part: String, i: Int, tokens: MutableList<Token>): Int {
        val c = part[i]
        val next = part.getOrNull(i + 1)
        when {
            c == '0' || c == '#' || c == '?' -> tokens += Token.Digit(c)
            c == '.' -> tokens += Token.Point
            c == ',' -> tokens += Token.Comma
            c == '%' || c == '‰' -> tokens += Token.Percent(c)
            c == '@' -> tokens += Token.At
            (c == 'E' || c == 'e') && (next == '+' || next == '-') -> {
                tokens += Token.Exponent(c, next == '+')
                return i + 2
            }
            (c == 'G' || c == 'g') && part.regionMatches(i, "General", 0, 7, ignoreCase = true) -> {
                tokens += Token.General
                return i + 7
            }
            c == '/' -> {
                tokens += Token.Slash
                // A fixed denominator, as in "# ?/8"; nine digits at most so it stays an Int.
                if (next != null && next in '1'..'9') {
                    var end = i + 1
                    while (end < part.length && end - i <= 9 && part[end] in '0'..'9') end++
                    tokens += Token.Denominator(part.substring(i + 1, end).toInt())
                    return end
                }
            }
            else -> tokens += Token.Literal(c.toString())
        }
        return i + 1
    }

    /** Reads the date-section token at [i] into [tokens]; returns where the next one starts. */
    private fun dateToken(part: String, i: Int, tokens: MutableList<Token>): Int {
        val c = part[i]
        val letter = c.lowercaseChar()
        val last = tokens.lastOrNull()
        when {
            letter in "ymdhse" || letter == 'g' -> {
                var end = i + 1
                while (end < part.length && part[end].lowercaseChar() == letter) end++
                // g is the era of Japanese dates, which the Gregorian calendar here doesn't have.
                if (letter != 'g') tokens += Token.DatePart(letter, end - i)
                return end
            }
            letter == 'a' && part.regionMatches(i, "AM/PM", 0, 5, ignoreCase = true) -> {
                tokens += Token.AmPm(short = false, lower = false)
                return i + 5
            }
            letter == 'a' && part.regionMatches(i, "A/P", 0, 3, ignoreCase = true) -> {
                tokens += Token.AmPm(short = true, lower = c.isLowerCase())
                return i + 3
            }
            c == '.' && part.getOrNull(i + 1) == '0' && (last.isUnit('s')) -> {
                var end = i + 1
                while (end < part.length && end - i <= 3 && part[end] == '0') end++
                tokens += Token.SubSecond(end - i - 1)
                return end
            }
            else -> tokens += Token.Literal(c.toString())
        }
        return i + 1
    }

    /** Whether this is a date part or elapsed time of [unit] (h or s). */
    private fun Token?.isUnit(unit: Char) = (this is Token.DatePart && kind == unit) || (this is Token.Elapsed && kind == unit)

    /** Turns each m or mm that means minutes into n: one right after an hour, or right before a second. */
    private fun markMinutes(tokens: MutableList<Token>) {
        for (i in tokens.indices) {
            val m = tokens[i] as? Token.DatePart ?: continue
            if (m.kind != 'm' || m.count > 2) continue
            val before = tokens.subList(0, i).lastOrNull { it is Token.DatePart || it is Token.Elapsed }
            val after = tokens.subList(i + 1, tokens.size).firstOrNull { it is Token.DatePart || it is Token.Elapsed }
            if (before.isUnit('h') || after.isUnit('s')) tokens[i] = Token.DatePart('n', m.count)
        }
    }

    /** The condition in a bracket such as [>=100] or [<>0]; null when its number isn't one. */
    private fun condition(inner: String): Condition? {
        val op = listOf("<>", "<=", ">=", "<", ">", "=").first { inner.startsWith(it) }
        val limit = inner.substring(op.length).trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return Condition(op, limit)
    }

    private fun argb(rgb: Int) = rgb or (0xFF shl 24)

    /**
     * The section that shows [v], and whether it shows it without its minus, as the negative section
     * does; null when none does, and General shows it. Without conditions, two sections split at zero
     * and a third takes zero itself. With them, the first section whose condition holds is used, and a
     * section without one takes what is left.
     */
    private fun choose(format: Format, v: Double): Pair<Section, Boolean>? {
        val sections = format.numeric
        if (sections.isEmpty()) return null
        val c0 = sections[0].condition
        val c1 = sections.getOrNull(1)?.condition
        if (c0 == null && c1 == null && sections.getOrNull(2)?.condition == null) {
            return when {
                sections.size == 1 -> sections[0] to false
                v < 0 -> sections[1] to true
                v == 0.0 && sections.size == 3 -> sections[2] to false
                else -> sections[0] to false
            }
        }
        if (c0?.holds(v) ?: (if (sections.size == 3) v > 0 else v >= 0)) return sections[0] to (v < 0 && c0?.negativeOnly == true)
        if (sections.size >= 2 && (c1?.holds(v) ?: (sections.size == 2 || v < 0))) {
            val unsigned = c1?.negativeOnly ?: (c0 == null || c0.limit == 0.0 && (c0.op == ">=" || c0.op == ">" && sections.size == 3))
            return sections[1] to (v < 0 && unsigned)
        }
        return if (sections.size == 3) sections[2] to false else null
    }

    /** [x] rounded to the 15 significant digits Excel keeps, from the shortest decimal that reads back as it. */
    private fun excelValue(x: Double): BigDecimal = BigDecimal(x.toString()).round(PRECISION)

    /** [value] in General: as many digits as fit in [maxChars] (at most 11), scientific when that shows more of them. */
    private fun general(value: Double, locale: SheetLocale, maxChars: Int): String {
        if (value == 0.0) return "0"
        val digits = generalDigits(excelValue(abs(value)), maxChars.coerceIn(1, GENERAL_WIDTH))
        val text = if (locale.decimal == '.') digits else digits.replace('.', locale.decimal)
        return if (value < 0) "-$text" else text
    }

    /** [x] (not negative) in General's digits, in [width] characters where it can be. */
    private fun generalDigits(x: BigDecimal, width: Int): String {
        if (x.signum() == 0) return "0"
        val magnitude = x.precision() - x.scale() - 1
        val scientific = scientificGeneral(x, magnitude, width)
        val plain = plainGeneral(x, magnitude, width) ?: return scientific.first
        // On a tie the plain form wins, so 0.0001 stays 0.0001 rather than 1E-04.
        return if (plain.second >= scientific.second) plain.first else scientific.first
    }

    /** [x] written out in [width] characters, and how many significant digits that shows; null when it doesn't fit or rounds to 0. */
    private fun plainGeneral(x: BigDecimal, magnitude: Int, width: Int): Pair<String, Int>? {
        val wholeDigits = if (magnitude >= 0) magnitude + 1 else 1
        if (wholeDigits > width) return null
        val rounded = x.setScale((width - wholeDigits - 1).coerceAtLeast(0), RoundingMode.HALF_UP).stripTrailingZeros()
        val text = rounded.toPlainString()
        // Rounding can carry into one digit more: 99999999999.5 becomes 100000000000.
        if (rounded.signum() == 0 || text.length > width) return null
        return text to rounded.precision()
    }

    /** [x] as 1.23457E+11, as many digits as [width] has room for, and how many significant digits that shows. */
    private fun scientificGeneral(x: BigDecimal, magnitude: Int, width: Int): Pair<String, Int> {
        var exponentDigits = maxOf(2, abs(magnitude).toString().length)
        while (true) {
            // The lead digit, its point, the decimals and E+ with the exponent's digits.
            val decimals = (width - 4 - exponentDigits).coerceAtLeast(0)
            val mantissa = x.round(MathContext(decimals + 1, RoundingMode.HALF_UP))
            val exponent = mantissa.precision() - mantissa.scale() - 1
            val needed = maxOf(2, abs(exponent).toString().length)
            if (needed > exponentDigits) {
                exponentDigits = needed
                continue
            }
            val lead = mantissa.movePointLeft(exponent).stripTrailingZeros().toPlainString()
            val text = lead + "E" + (if (exponent < 0) "-" else "+") + abs(exponent).toString().padStart(2, '0')
            return text to mantissa.stripTrailingZeros().precision()
        }
    }

    /** The number [magnitude] (not negative) shown by a number section, after a minus when [negative]. */
    private fun number(magnitude: Double, negative: Boolean, section: Section, locale: SheetLocale, maxChars: Int): String {
        val layout = section.layout!!
        val tokens = section.tokens
        val out = arrayOfNulls<String>(tokens.size)
        when {
            layout.general >= 0 -> out[layout.general] = general(magnitude, locale, maxChars)
            layout.slash >= 0 -> fraction(excelValue(magnitude).movePointRight(layout.shift), layout, out)
            layout.exponent >= 0 -> scientific(excelValue(magnitude).movePointRight(layout.shift), layout, locale, out)
            else -> decimal(excelValue(magnitude).movePointRight(layout.shift), layout, locale, out)
        }
        val shown = StringBuilder()
        if (negative) shown.append('-')
        for (i in tokens.indices) shown.append(out[i] ?: plain(tokens[i], layout.hidden[i]))
        return shown.toString()
    }

    /** What a token shows when no digits were put in it. */
    private fun plain(token: Token, hidden: Boolean): String = when (token) {
        is Token.Literal -> token.text
        is Token.Percent -> token.sign.toString()
        is Token.Exponent -> token.letter + if (token.plus) "+" else "-"
        is Token.Denominator -> token.value.toString()
        Token.Point -> "."
        Token.Slash -> "/"
        Token.Comma -> if (hidden) "" else ","
        else -> ""
    }

    /** [x] rounded to the decimal placeholders and put in them, its whole part before the point. */
    private fun decimal(x: BigDecimal, layout: NumberLayout, locale: SheetLocale, out: Array<String?>) {
        val digits = x.setScale(layout.decimalsAt.size, RoundingMode.HALF_UP).toPlainString()
        val whole = digits.substringBefore('.').takeIf { it != "0" }.orEmpty()
        placeWhole(whole, layout, layout.wholeAt, if (layout.grouped) locale.grouping else null, out)
        placeDecimals(digits.substringAfter('.', ""), layout, out)
        if (layout.point >= 0) out[layout.point] = (if (layout.wholeAt.isEmpty()) whole else "") + locale.decimal
    }

    /**
     * [x] as a mantissa and exponent. With more than one whole-part placeholder, as in ##0.0E+0, the
     * exponent is a multiple of their number (engineering notation); otherwise the mantissa has one digit.
     */
    private fun scientific(x: BigDecimal, layout: NumberLayout, locale: SheetLocale, out: Array<String?>) {
        val step = maxOf(1, layout.wholeAt.size)
        val decimals = layout.decimalsAt.size
        var exponent = 0
        var mantissa = x.setScale(decimals, RoundingMode.HALF_UP)
        if (x.signum() != 0) {
            exponent = Math.floorDiv(x.precision() - x.scale() - 1, step) * step
            mantissa = x.movePointLeft(exponent).setScale(decimals, RoundingMode.HALF_UP)
            // Rounding carried over: 9.999 with two decimals is 1.00E+01, not 10.00E+00.
            if (mantissa >= BigDecimal.TEN.pow(step)) {
                exponent += step
                mantissa = x.movePointLeft(exponent).setScale(decimals, RoundingMode.HALF_UP)
            }
        }
        decimal(mantissa, layout, locale, out)
        val e = layout.tokens[layout.exponent] as Token.Exponent
        out[layout.exponent] = e.letter + if (exponent < 0) "-" else if (e.plus) "+" else ""
        placeWhole(abs(exponent).toString(), layout, layout.exponentAt, null, out)
    }

    /**
     * [x] as a fraction: the nearest one with a denominator that fits its placeholders (or the fixed one),
     * after a whole part when the format has one, as in "# ?/?", or improper without, as in "?/?".
     */
    private fun fraction(x: BigDecimal, layout: NumberLayout, out: Array<String?>) {
        val mixed = layout.wholeAt.isNotEmpty()
        var whole = x.setScale(0, RoundingMode.FLOOR).toBigInteger()
        val part = x.subtract(BigDecimal(whole))
        var numerator: BigInteger
        val denominator: Long
        if (layout.fixedDenominator > 0) {
            denominator = layout.fixedDenominator.toLong()
            val n = (if (mixed) part else x).multiply(BigDecimal.valueOf(denominator)).setScale(0, RoundingMode.HALF_UP)
            numerator = n.toBigInteger()
        } else {
            val digits = layout.denominatorAt.size.coerceAtMost(MAX_DENOMINATOR_DIGITS)
            val nearest = nearestFraction(part.toDouble(), BigInteger.TEN.pow(digits).toLong() - 1)
            denominator = nearest[1]
            numerator = BigInteger.valueOf(nearest[0])
            if (!mixed) numerator += whole * BigInteger.valueOf(denominator)
        }
        if (mixed && numerator == BigInteger.valueOf(denominator)) {
            whole += BigInteger.ONE
            numerator = BigInteger.ZERO
        }
        if (mixed) {
            val wholeText = if (whole.signum() > 0) whole.toString() else if (numerator.signum() == 0) "0" else ""
            placeWhole(wholeText, layout, layout.wholeAt, null, out)
            if (numerator.signum() == 0) {
                // A whole number: the fraction's place is left blank, so whole numbers line up with the rest.
                for (i in layout.numeratorAt + layout.denominatorAt) out[i] = " "
                out[layout.slash] = " "
                if (layout.fixedDenominator > 0) out[layout.slash + 1] = " ".repeat(layout.fixedDenominator.toString().length)
                return
            }
        }
        placeWhole(numerator.toString(), layout, layout.numeratorAt, null, out)
        val denominatorText = denominator.toString()
        // Never longer than its placeholders: the denominator was chosen to fit them.
        layout.denominatorAt.forEachIndexed { j, i ->
            out[i] = when {
                j < denominatorText.length -> denominatorText[j].toString()
                (layout.tokens[i] as Token.Digit).kind == '?' -> " "
                else -> ""
            }
        }
    }

    /**
     * The fraction nearest [f] (0 to 1) whose denominator is at most [maxDenominator], as numerator and
     * denominator: the best of the continued fraction's convergents and the step short of the next one.
     */
    private fun nearestFraction(f: Double, maxDenominator: Long): LongArray {
        var p0 = 0L
        var q0 = 1L
        var p1 = 1L
        var q1 = 0L
        var x = f
        var steps = 0
        while (steps++ < 64) {
            val a = floor(x)
            if (a > maxDenominator) break
            val q2 = q0 + a.toLong() * q1
            if (q2 > maxDenominator) break
            val p2 = p0 + a.toLong() * p1
            p0 = p1
            q0 = q1
            p1 = p2
            q1 = q2
            if (x == a) break
            x = 1 / (x - a)
        }
        val k = (maxDenominator - q0) / q1
        val p = p0 + k * p1
        val q = q0 + k * q1
        return if (abs(p1.toDouble() / q1 - f) <= abs(p.toDouble() / q - f)) longArrayOf(p1, q1) else longArrayOf(p, q)
    }

    /**
     * [digits] put right to left in the placeholders at [at]; the leftmost one also takes any digits left
     * over. Empty places show 0, a space or nothing as their placeholder asks, and [separator], when
     * given, goes between thousands.
     */
    private fun placeWhole(digits: String, layout: NumberLayout, at: IntArray, separator: Char?, out: Array<String?>) {
        val n = at.size
        for (j in 0 until n) {
            val place = n - 1 - j
            val shown = StringBuilder()
            if (j == 0) {
                for (p in digits.length - 1 downTo n) {
                    shown.append(digits[digits.length - 1 - p])
                    if (separator != null && p % 3 == 0) shown.append(separator)
                }
            }
            val kind = (layout.tokens[at[j]] as Token.Digit).kind
            val digit = if (place < digits.length) digits[digits.length - 1 - place] else if (kind == '0') '0' else null
            if (digit != null) {
                shown.append(digit)
                if (separator != null && place > 0 && place % 3 == 0) shown.append(separator)
            } else if (kind == '?') {
                shown.append(' ')
            }
            out[at[j]] = shown.toString()
        }
    }

    /** [digits] put left to right in the decimal placeholders; zeros at the end go where # asks and become spaces where ? does. */
    private fun placeDecimals(digits: String, layout: NumberLayout, out: Array<String?>) {
        val at = layout.decimalsAt
        val kinds = CharArray(at.size) { (layout.tokens[at[it]] as Token.Digit).kind }
        var end = at.size
        while (end > 0 && digits[end - 1] == '0' && kinds[end - 1] != '0') end--
        for (j in at.indices) out[at[j]] = if (j < end) digits[j].toString() else if (kinds[j] == '?') " " else ""
    }

    /** The date and time [serial] stands for, shown by a date section; one Excel can't show fills the room with #. */
    private fun dateTime(serial: Double, section: Section, locale: SheetLocale, date1904: Boolean, maxChars: Int): String {
        val lastDay = if (date1904) LAST_DAY_1904 else LAST_DAY_1900
        val hashes = "#".repeat(maxChars.coerceIn(1, 255))
        if (serial < 0 || serial >= lastDay + 1) return hashes
        // Rounded to the second, or to the fraction of one shown, as Excel does; the minutes then aren't rounded.
        val perSecond = BigInteger.TEN.pow(section.subSecondDigits).toLong()
        val perDay = 86_400L * perSecond
        val units = excelValue(serial).multiply(BigDecimal.valueOf(perDay)).setScale(0, RoundingMode.HALF_UP).toLong()
        val day = units / perDay
        if (day > lastDay) return hashes
        val seconds = units / perSecond
        val hour = (seconds / 3600 % 24).toInt()
        val (year, month, dayOfMonth) = calendarDate(day, date1904)
        // Excel counts 1 January 1900 as a Sunday, so its weekdays before March 1900 run on from the false 29 February.
        val weekday = ((day + (if (date1904) 5 else 6)) % 7).toInt()
        val shown = StringBuilder()
        for (token in section.tokens) {
            when (token) {
                is Token.Literal -> shown.append(token.text)
                is Token.DatePart -> shown.append(
                    when (token.kind) {
                        'y' -> if (token.count <= 2) (year % 100).toString().padStart(2, '0') else year.toString()
                        'e' -> year.toString()
                        'm' -> when (token.count) {
                            1 -> month.toString()
                            2 -> month.toString().padStart(2, '0')
                            3 -> name(locale.shortMonths, SheetLocale.ENGLISH.shortMonths, month - 1)
                            5 -> firstLetter(name(locale.months, SheetLocale.ENGLISH.months, month - 1))
                            else -> name(locale.months, SheetLocale.ENGLISH.months, month - 1)
                        }
                        'd' -> when (token.count) {
                            1 -> dayOfMonth.toString()
                            2 -> dayOfMonth.toString().padStart(2, '0')
                            3 -> name(locale.shortDays, SheetLocale.ENGLISH.shortDays, weekday)
                            else -> name(locale.days, SheetLocale.ENGLISH.days, weekday)
                        }
                        'h' -> twoDigits(if (section.twelveHour) (hour + 11) % 12 + 1 else hour, token.count)
                        'n' -> twoDigits((seconds / 60 % 60).toInt(), token.count)
                        else -> twoDigits((seconds % 60).toInt(), token.count)
                    },
                )
                is Token.Elapsed -> {
                    val total = when (token.kind) {
                        'h' -> seconds / 3600
                        'm' -> seconds / 60
                        else -> seconds
                    }
                    shown.append(total.toString().padStart(token.count, '0'))
                }
                is Token.AmPm -> {
                    val word = if (hour < 12) locale.am else locale.pm
                    val written = if (token.short) firstLetter(word) else word
                    shown.append(if (token.lower) written.lowercase() else written)
                }
                is Token.SubSecond -> {
                    val fraction = (units % perSecond).toString().padStart(section.subSecondDigits, '0')
                    shown.append(locale.decimal).append(fraction, 0, token.digits)
                }
                else -> {}
            }
        }
        return shown.toString()
    }

    /** Year, month and day of day [day]: in the 1900 system day 0 is 0 January 1900 and day 60 is 29 February 1900, as in Excel. */
    private fun calendarDate(day: Long, date1904: Boolean): Triple<Int, Int, Int> {
        val date = when {
            date1904 -> LocalDate.of(1904, 1, 1).plusDays(day)
            day == 0L -> return Triple(1900, 1, 0)
            day == 60L -> return Triple(1900, 2, 29)
            day < 60 -> LocalDate.of(1899, 12, 31).plusDays(day)
            else -> LocalDate.of(1899, 12, 30).plusDays(day)
        }
        return Triple(date.year, date.monthValue, date.dayOfMonth)
    }

    private fun twoDigits(value: Int, count: Int) = if (count >= 2) value.toString().padStart(2, '0') else value.toString()

    private fun name(names: List<String>, english: List<String>, index: Int) = names.getOrNull(index) ?: english[index]

    private fun firstLetter(word: String) = if (word.isEmpty()) word else word.substring(0, word.offsetByCodePoints(0, 1))
}
