package io.github.nath_goa.usugami.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** A block of cells: rows and columns counted from 0, both ends included. */
data class SheetRange(val firstRow: Int, val firstColumn: Int, val lastRow: Int, val lastColumn: Int) {
    fun contains(row: Int, column: Int): Boolean = row in firstRow..lastRow && column in firstColumn..lastColumn

    /** The smallest range holding this one and [other]. */
    fun union(other: SheetRange): SheetRange = SheetRange(
        minOf(firstRow, other.firstRow), minOf(firstColumn, other.firstColumn),
        maxOf(lastRow, other.lastRow), maxOf(lastColumn, other.lastColumn),
    )

    /** The cells this range and [other] share, or null when they share none. */
    fun intersect(other: SheetRange): SheetRange? {
        val top = maxOf(firstRow, other.firstRow)
        val left = maxOf(firstColumn, other.firstColumn)
        val bottom = minOf(lastRow, other.lastRow)
        val right = minOf(lastColumn, other.lastColumn)
        return if (top <= bottom && left <= right) SheetRange(top, left, bottom, right) else null
    }
}

/**
 * Cell references as spreadsheets write them: "B3", "$A$1:$F$20", whole columns ("A:C"), whole rows
 * ("1:3"), and the areas of a defined name, such as "'Sales 2024'!$A$1:$F$20,'Sales 2024'!$H$1:$J$5".
 */
object SheetRefs {
    /** The most rows and columns a sheet has: row 1,048,576 and column XFD. */
    const val MAX_ROWS = 1_048_576
    const val MAX_COLUMNS = 16_384

    /** The column [letters] name ("A", "AB", "XFD", any case), counted from 0; null when it isn't a column. */
    fun column(letters: String): Int? {
        if (letters.isEmpty() || letters.length > 3) return null
        var n = 0
        for (c in letters) {
            val digit = when (c) {
                in 'A'..'Z' -> c - 'A' + 1
                in 'a'..'z' -> c - 'a' + 1
                else -> return null
            }
            n = n * 26 + digit
        }
        return (n - 1).takeIf { it < MAX_COLUMNS }
    }

    /** Column [index] (from 0) as its letters. */
    fun columnName(index: Int): String {
        val out = StringBuilder()
        var n = index + 1
        while (n > 0) {
            val rest = (n - 1) % 26
            out.append('A' + rest)
            n = (n - 1) / 26
        }
        return out.reverse().toString()
    }

    /**
     * A cell reference such as "B3" or "$B$3": its row and column from 0, packed by [pack]. Null when
     * [ref] isn't one. Read once for every cell of a sheet, so it is parsed by hand.
     */
    fun cell(ref: String): Long? {
        var i = 0
        val n = ref.length
        if (i < n && ref[i] == '$') i++
        var column = 0
        var letters = 0
        while (i < n) {
            val c = ref[i]
            val digit = when (c) {
                in 'A'..'Z' -> c - 'A' + 1
                in 'a'..'z' -> c - 'a' + 1
                else -> break
            }
            column = column * 26 + digit
            letters++
            i++
        }
        if (letters == 0 || letters > 3 || column > MAX_COLUMNS) return null
        if (i < n && ref[i] == '$') i++
        if (i >= n) return null
        var row = 0
        while (i < n) {
            val c = ref[i]
            if (c !in '0'..'9' || row > MAX_ROWS) return null
            row = row * 10 + (c - '0')
            i++
        }
        if (row < 1 || row > MAX_ROWS) return null
        return pack(row - 1, column - 1)
    }

    /** A row and column held in one number, as [cell] gives them. */
    fun pack(row: Int, column: Int): Long = (row.toLong() shl 20) or column.toLong()
    fun rowOf(packed: Long): Int = (packed shr 20).toInt()
    fun columnOf(packed: Long): Int = (packed and 0xFFFFF).toInt()

    /**
     * A range such as "A1:C5" or "$A$1", whole columns ("A:C", "$B:$B") or whole rows ("1:3"), with
     * its corners in either order. Null when [ref] is none of these.
     */
    fun range(ref: String): SheetRange? {
        val parts = ref.trim().split(':')
        if (parts.size > 2 || parts.any { it.isEmpty() }) return null
        val first = parts[0].removePrefix("$")
        val last = parts.last().removePrefix("$")
        cell(parts[0])?.let { a ->
            val b = cell(parts.last()) ?: return null
            return SheetRange(minOf(rowOf(a), rowOf(b)), minOf(columnOf(a), columnOf(b)), maxOf(rowOf(a), rowOf(b)), maxOf(columnOf(a), columnOf(b)))
        }
        if (parts.size != 2) return null
        val fromColumn = column(first)
        val toColumn = column(last)
        if (fromColumn != null && toColumn != null) return SheetRange(0, minOf(fromColumn, toColumn), MAX_ROWS - 1, maxOf(fromColumn, toColumn))
        val fromRow = first.toIntOrNull()?.takeIf { it in 1..MAX_ROWS }
        val toRow = last.toIntOrNull()?.takeIf { it in 1..MAX_ROWS }
        if (fromRow != null && toRow != null) return SheetRange(minOf(fromRow, toRow) - 1, 0, maxOf(fromRow, toRow) - 1, MAX_COLUMNS - 1)
        return null
    }

    /**
     * The areas a defined name's formula refers to, each with the sheet it names (unquoted, or null
     * when it names none): "Sheet1!$A$1:$F$20,Sheet1!$H$1:$J$5" has two. Parts that aren't ranges
     * (a #REF! left by a deleted sheet, say) are left out.
     */
    fun areas(formula: String): List<Pair<String?, SheetRange>> {
        val out = ArrayList<Pair<String?, SheetRange>>()
        for (part in splitOutsideQuotes(formula.trim().removePrefix("="), ',')) {
            val bang = lastIndexOutsideQuotes(part, '!')
            val sheet = if (bang < 0) null else unquote(part.substring(0, bang).trim())
            val range = range(part.substring(bang + 1).trim()) ?: continue
            out += sheet to range
        }
        return out
    }

    /** A sheet name as written in a reference: 'It''s here' is It's here. */
    private fun unquote(name: String): String =
        if (name.length >= 2 && name.startsWith("'") && name.endsWith("'")) name.substring(1, name.length - 1).replace("''", "'") else name

    private fun splitOutsideQuotes(text: String, separator: Char): List<String> {
        val parts = ArrayList<String>()
        var quoted = false
        var start = 0
        for ((i, c) in text.withIndex()) {
            if (c == '\'') quoted = !quoted else if (c == separator && !quoted) {
                parts += text.substring(start, i)
                start = i + 1
            }
        }
        parts += text.substring(start)
        return parts
    }

    private fun lastIndexOutsideQuotes(text: String, char: Char): Int {
        var quoted = false
        var found = -1
        for ((i, c) in text.withIndex()) {
            if (c == '\'') quoted = !quoted else if (c == char && !quoted) found = i
        }
        return found
    }
}

/**
 * Sizes in spreadsheets. Column widths are counted in characters of the default font's widest digit
 * (with a few pixels of padding), which Excel turns into whole pixels at 96 dpi; a pixel is 0.75 points.
 */
object SheetUnits {
    /** Calibri 11's widest digit, 7 pixels, as Excel measures it. */
    const val DIGIT_WIDTH = 7

    /** A column [width] as a file stores it, in points, for a default font whose widest digit is [digitWidth] pixels. */
    fun columnPoints(width: Double, digitWidth: Int = DIGIT_WIDTH): Double {
        if (!(width > 0.0)) return 0.0
        val pixels = floor(((256 * width.coerceAtMost(255.0) + floor(128.0 / digitWidth)) / 256) * digitWidth)
        return pixels * 0.75
    }

    /**
     * The default column width of a sheet that gives only how many digits fit ([baseWidth], 8 when
     * it doesn't say): those digits and 5 pixels of padding, rounded up to 8 pixels, as Excel draws it.
     * Calibri 11's columns are 64 pixels wide this way.
     */
    fun defaultColumnPoints(baseWidth: Int, digitWidth: Int = DIGIT_WIDTH): Double =
        ceil((baseWidth.coerceIn(0, 255) * digitWidth + 5) / 8.0) * 8 * 0.75

    /**
     * The widest digit of the default font [name] at [size] points, in pixels at 96 dpi, as Excel
     * measures it for column widths: 7 for Calibri 11, Aptos Narrow 11 and Arial 10.
     */
    fun digitWidth(name: String?, size: Double): Int =
        (size.coerceIn(1.0, 409.0) * 96 / 72 * (digitEm(name) ?: 0.55)).roundToInt().coerceAtLeast(1)

    /**
     * How wide the digits of the Office font [name] are, as a share of its size; null for a font
     * Usugami doesn't know. Phone fonts are drawn narrower or wider by the ratio, so that text takes
     * the room in its cells that it takes in Excel.
     */
    fun digitEm(name: String?): Double? {
        val n = name.orEmpty().lowercase()
        return when {
            "calibri" in n || "aptos narrow" in n || "carlito" in n -> 0.507
            "aptos" in n -> 0.55
            "arial narrow" in n -> 0.456
            "arial" in n || "helvetica" in n || "liberation sans" in n || "arimo" in n -> 0.556
            "times" in n || "liberation serif" in n || "tinos" in n -> 0.5
            "cambria" in n || "caladea" in n -> 0.556
            "garamond" in n -> 0.47
            "book antiqua" in n || "palatino" in n -> 0.5
            "georgia" in n -> 0.61
            "verdana" in n -> 0.636
            "tahoma" in n -> 0.546
            "segoe ui" in n -> 0.559
            "trebuchet" in n -> 0.524
            "century gothic" in n -> 0.551
            "courier" in n || "consolas" in n || "lucida console" in n || "mono" in n -> 0.6
            else -> null
        }
    }
}

/** Splitting a printed sheet into pages, and how much it is shrunk to fit them. */
object SheetPages {
    /** Excel's smallest and largest print scales, in percent. */
    const val MIN_SCALE = 10
    const val MAX_SCALE = 400

    /**
     * Splits [sizes] (the printed rows' heights or columns' widths, in points before [scale]) into
     * bands that each fit in [space] points: a single row or column too big for a page gets one of its
     * own. A page breaks before each index in [breaks]. A band starting after [titleEnd] has
     * [titleSize] points less room, which the repeated title rows or columns take; with a [titleEnd]
     * below 0 every band has. Returns the first index of each band.
     */
    fun bands(
        sizes: DoubleArray,
        space: Double,
        scale: Double = 1.0,
        breaks: Set<Int> = emptySet(),
        titleSize: Double = 0.0,
        titleEnd: Int = -1,
    ): IntArray {
        val starts = ArrayList<Int>()
        walk(sizes, space, scale, breaks, titleSize, titleEnd) { starts += it }
        return starts.toIntArray()
    }

    /** How many bands [bands] makes, without keeping them. */
    fun count(sizes: DoubleArray, space: Double, scale: Double = 1.0, breaks: Set<Int> = emptySet(), titleSize: Double = 0.0, titleEnd: Int = -1): Int {
        var count = 0
        walk(sizes, space, scale, breaks, titleSize, titleEnd) { count++ }
        return count
    }

    private inline fun walk(sizes: DoubleArray, space: Double, scale: Double, breaks: Set<Int>, titleSize: Double, titleEnd: Int, start: (Int) -> Unit) {
        if (sizes.isEmpty()) return
        var used = 0.0
        var room = 0.0
        for (i in sizes.indices) {
            val size = sizes[i] * scale
            if (i == 0 || (breaks.isNotEmpty() && i in breaks) || (used > 0.0 && used + size > room + EPSILON)) {
                start(i)
                room = if (i > titleEnd) space - titleSize * scale else space
                used = 0.0
            }
            used += size
        }
    }

    /**
     * The scale, in whole percent, at which [widths] fit [pagesWide] pages of [width] points and
     * [heights] fit [pagesTall] pages of [height] (0 leaves that side free), as Excel's "Fit to" does:
     * the largest up to [most] percent (100, as it only ever shrinks), and never less than
     * [MIN_SCALE]. [columnTitle] and [rowTitle] are the repeated titles' sizes and ends (see [bands]).
     */
    fun fitScale(
        widths: DoubleArray,
        heights: DoubleArray,
        width: Double,
        height: Double,
        pagesWide: Int,
        pagesTall: Int,
        columnBreaks: Set<Int> = emptySet(),
        rowBreaks: Set<Int> = emptySet(),
        columnTitle: Pair<Double, Int> = 0.0 to -1,
        rowTitle: Pair<Double, Int> = 0.0 to -1,
        most: Int = 100,
    ): Int {
        fun fits(percent: Int): Boolean {
            val s = percent / 100.0
            if (pagesWide > 0 && count(widths, width, s, columnBreaks, columnTitle.first, columnTitle.second) > pagesWide) return false
            if (pagesTall > 0 && count(heights, height, s, rowBreaks, rowTitle.first, rowTitle.second) > pagesTall) return false
            return true
        }
        // A first guess from the totals; then a percent at a time, as bands break between whole rows.
        var guess = most.coerceIn(MIN_SCALE, MAX_SCALE).toDouble()
        if (pagesWide > 0) widths.sum().takeIf { it > 0 }?.let { guess = minOf(guess, width * pagesWide / it * 100) }
        if (pagesTall > 0) heights.sum().takeIf { it > 0 }?.let { guess = minOf(guess, height * pagesTall / it * 100) }
        var percent = floor(guess + EPSILON).toInt().coerceIn(MIN_SCALE, most.coerceIn(MIN_SCALE, MAX_SCALE))
        while (percent > MIN_SCALE && !fits(percent)) percent--
        return percent
    }

    /**
     * [pages], each a (row band, column band), in the order they print: down the sheet and then over
     * to the next columns, as Excel prints by default, or over and then down.
     */
    fun order(pages: Collection<Pair<Int, Int>>, downThenOver: Boolean): List<Pair<Int, Int>> =
        if (downThenOver) pages.sortedWith(compareBy({ it.second }, { it.first }))
        else pages.sortedWith(compareBy({ it.first }, { it.second }))

    private const val EPSILON = 1e-6
}

/** Paper sizes by the number Excel gives them (pageSetup's paperSize), in points, the short side first. */
object SheetPaper {
    private fun inches(w: Double, h: Double) = w * 72 to h * 72
    private fun mm(w: Double, h: Double) = w * 72 / 25.4 to h * 72 / 25.4

    private val SIZES: Map<Int, Pair<Double, Double>> = mapOf(
        1 to inches(8.5, 11.0), 2 to inches(8.5, 11.0), 3 to inches(11.0, 17.0), 4 to inches(11.0, 17.0),
        5 to inches(8.5, 14.0), 6 to inches(5.5, 8.5), 7 to inches(7.25, 10.5), 8 to mm(297.0, 420.0),
        9 to mm(210.0, 297.0), 10 to mm(210.0, 297.0), 11 to mm(148.0, 210.0), 12 to mm(257.0, 364.0),
        13 to mm(182.0, 257.0), 14 to inches(8.5, 13.0), 15 to mm(215.0, 275.0), 16 to inches(10.0, 14.0),
        17 to inches(11.0, 17.0), 18 to inches(8.5, 11.0), 19 to inches(3.875, 8.875), 20 to inches(4.125, 9.5),
        21 to inches(4.5, 10.375), 22 to inches(4.75, 11.0), 23 to inches(5.0, 11.5), 24 to inches(17.0, 22.0),
        25 to inches(22.0, 34.0), 26 to inches(34.0, 44.0), 27 to mm(110.0, 220.0), 28 to mm(162.0, 229.0),
        29 to mm(324.0, 458.0), 30 to mm(229.0, 324.0), 31 to mm(114.0, 162.0), 32 to mm(114.0, 229.0),
        33 to mm(250.0, 353.0), 34 to mm(176.0, 250.0), 35 to mm(176.0, 125.0), 36 to mm(110.0, 230.0),
        37 to inches(3.875, 7.5), 38 to inches(3.625, 6.5), 39 to inches(14.875, 11.0), 40 to inches(8.5, 12.0),
        41 to inches(8.5, 13.0), 43 to mm(100.0, 148.0), 66 to mm(420.0, 594.0), 70 to mm(105.0, 148.0),
    ).mapValues { (_, size) -> minOf(size.first, size.second) to maxOf(size.first, size.second) }

    /** Paper size [code]'s short and long sides in points, or null for a code Usugami doesn't know. */
    fun size(code: Int): Pair<Double, Double>? = SIZES[code]

    /**
     * A length as pageSetup's paperWidth and paperHeight write it, such as "210mm" or "8.5in", in
     * points; null when it isn't one, or is shorter than [MIN_SIDE] or longer than [MAX_SIDE], which
     * only a crafted file asks for.
     */
    fun length(text: String?): Double? {
        val t = text?.trim()?.lowercase()?.takeIf { it.length in 2..32 } ?: return null
        val unit = t.takeLastWhile { it.isLetter() }
        val number = t.dropLast(unit.length).trim().toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() } ?: return null
        val points = when (unit) {
            "mm" -> number * 72 / 25.4
            "cm" -> number * 72 / 2.54
            "in" -> number * 72
            "pt" -> number
            else -> null
        }
        return points?.takeIf { it in MIN_SIDE..MAX_SIDE }
    }

    /** An inch: no paper is smaller. */
    const val MIN_SIDE = 72.0

    /** 200 inches, the longest page PDF readers show. */
    const val MAX_SIDE = 14_400.0
}

/** Colours as spreadsheets give them: by number from a palette of 64, by theme, with a tint. */
object SheetColors {
    private val PALETTE = intArrayOf(
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
        0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
        0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
        0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
        0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
        0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333,
    )

    /** Indexed colour [index] of the default palette, as ARGB: 64 is the text colour (black), 65 the background (white). */
    fun indexed(index: Int): Int? = when (index) {
        in PALETTE.indices -> PALETTE[index] or OPAQUE
        64 -> 0x000000 or OPAQUE
        65 -> 0xFFFFFF or OPAQUE
        else -> null
    }

    /**
     * The theme colour a spreadsheet means by number [index]: Excel counts the first four in another
     * order than the theme lists them (background before text).
     */
    fun themeKey(index: Int): String? = THEME_KEYS.getOrNull(index)

    private val THEME_KEYS = listOf("lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")

    /**
     * [argb] made lighter (a [tint] up to 1) or darker (down to -1), as Excel tints a colour: its
     * lightness is moved that share of the way towards white or black.
     */
    fun tint(argb: Int, tint: Double): Int {
        if (tint == 0.0 || tint.isNaN()) return argb
        val t = tint.coerceIn(-1.0, 1.0)
        val modifiers = if (t < 0) listOf("lumMod" to ((1 + t) * 100_000).roundToInt())
        else listOf("lumMod" to ((1 - t) * 100_000).roundToInt(), "lumOff" to (t * 100_000).roundToInt())
        return OfficeColors.modify(argb, modifiers)
    }

    /**
     * An "rgb" attribute such as "FF4472C4", or "4472C4", as an opaque ARGB colour. Excel ignores
     * the alpha byte, which some apps write as 00.
     */
    fun rgb(text: String?): Int? {
        val t = text?.trim() ?: return null
        val hex = when (t.length) {
            8 -> t.substring(2)
            6 -> t
            else -> return null
        }
        return OfficeColors.hex(hex)
    }

    private const val OPAQUE = 0xFF shl 24
}

/** Dates in spreadsheets: numbers of days from the start of 1900 (or of 1904, in old Mac files). */
object SheetDates {
    /**
     * An ISO 8601 date and time as a cell of type "d" holds it ("2024-03-15", "2024-03-15T10:30:00",
     * or a time alone, "10:30:00"), as the number Excel stores for it; null when it isn't one.
     */
    fun serial(iso: String, date1904: Boolean = false): Double? {
        val text = iso.trim().removeSuffix("Z").takeIf { it.isNotEmpty() } ?: return null
        val match = ISO.matchEntire(text) ?: return null
        val (y, mo, d, h, mi, s) = match.destructured
        var days = 0.0
        if (y.isNotEmpty()) {
            val year = y.toInt()
            val month = mo.toInt()
            val day = d.toInt()
            if (month !in 1..12 || day !in 1..31) return null
            val sinceEpoch = daysFromCivil(year, month, day)
            days = if (date1904) (sinceEpoch - daysFromCivil(1904, 1, 1)).toDouble()
            else {
                // Excel counts a 29 February 1900 that never was, so later dates are a day further on.
                val from = sinceEpoch - daysFromCivil(1899, 12, 31)
                (if (from >= 60) from + 1 else from).toDouble()
            }
        }
        if (h.isNotEmpty()) {
            val seconds = h.toInt() * 3600 + mi.toInt() * 60 + (s.toDoubleOrNull() ?: 0.0)
            days += seconds / 86_400.0
        }
        return days
    }

    /** Days from 1 January 1970 to the date given, in the calendar used today. */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    private val ISO = Regex("""^(?:(\d{4})-(\d{2})-(\d{2}))?(?:T?(\d{2}):(\d{2})(?::(\d{2}(?:\.\d+)?))?)?$""")
}

/** Text in spreadsheets. Excel writes characters XML can't hold as _xHHHH_: a carriage return is _x000D_. */
object SheetStrings {
    /** [text] with each _xHHHH_ turned back into its character (_x005F_ is the underscore itself). */
    fun unescape(text: String): String {
        if (!text.contains("_x")) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '_' && i + 6 < text.length && text[i + 1] == 'x' && text[i + 6] == '_') {
                val code = text.substring(i + 2, i + 6).toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    i += 7
                    continue
                }
            }
            out.append(text[i])
            i++
        }
        return out.toString()
    }
}

/**
 * Formulas, for the cells whose file keeps a formula but not the value it last gave, which then show
 * the formula itself. A formula shared by a block of cells is written once, for its first cell.
 */
object SheetFormulas {
    /**
     * [formula], written for the first cell of a shared formula, as it reads [rows] rows down and
     * [columns] columns across from it: its relative references move, the absolute parts ($A$1)
     * don't, and text in quotes, sheet names and function names stay as they are. A reference moved
     * off the sheet becomes #REF!, as in Excel.
     */
    fun shift(formula: String, rows: Int, columns: Int): String {
        if (rows == 0 && columns == 0) return formula
        val out = StringBuilder(formula.length + 8)
        val n = formula.length
        var i = 0
        while (i < n) {
            val c = formula[i]
            when {
                c == '"' || c == '\'' -> {
                    // Text, or a quoted sheet name: to the matching quote, a doubled quote being part of it.
                    var end = i + 1
                    while (end < n) {
                        if (formula[end] == c) {
                            if (end + 1 < n && formula[end + 1] == c) end += 2 else break
                        } else end++
                    }
                    end = minOf(n, end + 1)
                    out.append(formula, i, end)
                    i = end
                }
                c.isDigit() -> {
                    val start = i
                    while (i < n && (formula[i].isDigit() || formula[i] == '.')) i++
                    if (i < n && (formula[i] == 'E' || formula[i] == 'e') && i + 1 < n && (formula[i + 1].isDigit() || formula[i + 1] == '+' || formula[i + 1] == '-')) {
                        i += 2
                        while (i < n && formula[i].isDigit()) i++
                    }
                    out.append(formula, start, i)
                }
                c == '$' || c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < n && (formula[i].isLetterOrDigit() || formula[i] == '$' || formula[i] == '_' || formula[i] == '.')) i++
                    val word = formula.substring(start, i)
                    val next = if (i < n) formula[i] else ' '
                    // A function's name, or the sheet a reference is on, isn't a reference.
                    out.append(if (next == '(' || next == '!') word else shiftReference(word, rows, columns) ?: word)
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** [word] moved, when it is a cell reference such as B3, $B3, B$3 or $B$3; null when it is something else. */
    private fun shiftReference(word: String, rows: Int, columns: Int): String? {
        val match = REFERENCE.matchEntire(word) ?: return null
        val (absColumn, letters, absRow, digits) = match.destructured
        val column = SheetRefs.column(letters) ?: return null
        val row = digits.toIntOrNull()?.takeIf { it in 1..SheetRefs.MAX_ROWS } ?: return null
        val newColumn = if (absColumn.isEmpty()) column + columns else column
        val newRow = if (absRow.isEmpty()) row + rows else row
        if (newColumn !in 0 until SheetRefs.MAX_COLUMNS || newRow !in 1..SheetRefs.MAX_ROWS) return "#REF!"
        return absColumn + SheetRefs.columnName(newColumn) + absRow + newRow
    }

    private val REFERENCE = Regex("""(\$?)([A-Za-z]{1,3})(\$?)(\d{1,7})""")
}

/** A piece of a header or footer in one look: [size] in points and [font] are null for the default. */
data class HeaderRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** 1 for superscript, -1 for subscript. */
    val baseline: Int = 0,
    val size: Float? = null,
    val font: String? = null,
    /** An RGB colour (without alpha), or null for black. */
    val color: Int? = null,
)

/**
 * Headers and footers as spreadsheets write them: codes such as "&LPage &P of &N&R&D" for a left
 * part with the page number and a right one with the date. A part's text can change look as it goes:
 * &B bold, &I italic, &U underline, &S strikethrough, &X and &Y raised and lowered, &"Arial,Bold" a
 * font, &12 a size, &KFF0000 a colour. Pictures (&G) are left out.
 */
object SheetHeaders {
    /** What the codes in a header or footer stand for, on one page. */
    class Fields(val page: Int, val pages: Int, val sheet: String, val file: String, val date: String, val time: String)

    /** The left, centre and right parts of [code], each a list of runs; text before any &L, &C or &R is the centre's. */
    fun parts(code: String, fields: Fields): List<List<HeaderRun>> {
        val parts = List(3) { ArrayList<HeaderRun>() }
        var part = 1
        var look = HeaderRun("")
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) parts[part] += look.copy(text = text.toString())
            text.setLength(0)
        }
        var i = 0
        while (i < code.length) {
            val c = code[i]
            if (c != '&' || i + 1 >= code.length) {
                text.append(c)
                i++
                continue
            }
            val next = code[i + 1]
            i += 2
            when {
                next == '&' -> text.append('&')
                next == 'L' || next == 'C' || next == 'R' -> {
                    // Each part starts in the default look.
                    flush()
                    part = "LCR".indexOf(next)
                    look = HeaderRun("")
                }
                next == 'P' -> {
                    // The page number, perhaps with an offset: &P+1, &P-2.
                    var offset = 0
                    if (i < code.length && (code[i] == '+' || code[i] == '-')) {
                        val sign = if (code[i] == '-') -1 else 1
                        var j = i + 1
                        while (j < code.length && j - i <= 4 && code[j].isDigit()) j++
                        code.substring(i + 1, j).toIntOrNull()?.let { offset = sign * it }
                        if (j > i + 1) i = j
                    }
                    text.append(fields.page + offset)
                }
                next == 'N' -> text.append(fields.pages)
                next == 'D' -> text.append(fields.date)
                next == 'T' -> text.append(fields.time)
                next == 'A' -> text.append(fields.sheet)
                next == 'F' -> text.append(fields.file)
                next == 'B' -> { flush(); look = look.copy(bold = !look.bold) }
                next == 'I' -> { flush(); look = look.copy(italic = !look.italic) }
                next == 'U' || next == 'E' -> { flush(); look = look.copy(underline = !look.underline) }
                next == 'S' -> { flush(); look = look.copy(strike = !look.strike) }
                next == 'X' -> { flush(); look = look.copy(baseline = if (look.baseline == 1) 0 else 1) }
                next == 'Y' -> { flush(); look = look.copy(baseline = if (look.baseline == -1) 0 else -1) }
                next == '"' -> {
                    flush()
                    val end = code.indexOf('"', i).let { if (it < 0) code.length else it }
                    val (font, style) = code.substring(i, end).split(',', limit = 2).let { it[0].trim() to it.getOrElse(1) { "" }.lowercase() }
                    look = look.copy(
                        font = font.takeIf { it.isNotEmpty() && it != "-" } ?: look.font,
                        bold = "bold" in style || (look.bold && style.isEmpty()),
                        italic = "italic" in style || "oblique" in style || (look.italic && style.isEmpty()),
                    )
                    i = minOf(code.length, end + 1)
                }
                next.isDigit() -> {
                    flush()
                    var j = i - 1
                    while (j < code.length && j - (i - 1) < 3 && code[j].isDigit()) j++
                    code.substring(i - 1, j).toIntOrNull()?.takeIf { it in 1..409 }?.let { look = look.copy(size = it.toFloat()) }
                    i = j
                }
                next == 'K' -> {
                    flush()
                    val value = code.substring(i, minOf(code.length, i + 6))
                    if (value.length == 6 && value.all { Character.digit(it, 16) >= 0 }) look = look.copy(color = value.toInt(16))
                    i = minOf(code.length, i + 6)
                }
                // Pictures, the file's folder, outline and shadow: nothing to show.
                else -> Unit
            }
        }
        flush()
        return parts
    }
}
