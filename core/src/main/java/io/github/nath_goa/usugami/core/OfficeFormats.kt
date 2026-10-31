package io.github.nath_goa.usugami.core

import java.io.InputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Measurements in Word and PowerPoint files, as points (1/72 inch). Word uses twentieths of a point
 * (twips) and half-points; DrawingML uses English Metric Units, 12,700 to the point.
 */
object OfficeUnits {
    const val EMU_PER_POINT = 12_700f

    fun emu(value: Long): Float = value / EMU_PER_POINT
    fun twips(value: Int): Float = value / 20f
    fun halfPoints(value: Int): Float = value / 2f

    /**
     * A length from a VML style such as "width:120pt" or "1.5in", in points. Bare numbers are
     * pixels at 96 dpi, as in CSS. Null when [text] isn't a length.
     */
    fun cssLength(text: String): Float? {
        val match = Regex("""^\s*(-?\d*\.?\d+)\s*(pt|in|cm|mm|px|pc|emu)?\s*$""").find(text.lowercase()) ?: return null
        val number = match.groupValues[1].toFloatOrNull() ?: return null
        return when (match.groupValues[2]) {
            "pt" -> number
            "in" -> number * 72f
            "cm" -> number * 72f / 2.54f
            "mm" -> number * 72f / 25.4f
            "pc" -> number * 12f
            "emu" -> number / EMU_PER_POINT
            else -> number * 0.75f
        }
    }

    /** The declarations of a VML or CSS style attribute, such as "width:120pt;height:40pt", by lowercase name. */
    fun cssStyle(style: String?): Map<String, String> =
        style.orEmpty().split(';').mapNotNull { part ->
            val colon = part.indexOf(':')
            if (colon <= 0) null else part.substring(0, colon).trim().lowercase() to part.substring(colon + 1).trim()
        }.toMap()
}

/** What kind of file a Word or PowerPoint document turned out to be, from its first bytes. */
enum class OfficeContainer {
    /** A zip: the Office Open XML formats (.docx, .pptx). */
    ZIP,

    /** An OLE compound file: the old binary formats (.doc, .ppt), or a password-protected .docx or .pptx. */
    COMPOUND,

    /** Neither, such as an RTF or HTML file renamed to .doc. */
    OTHER,
}

object OfficeFileType {
    private val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val COMPOUND = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

    /** The container [header] (the file's first bytes, at least eight) starts. */
    fun container(header: ByteArray): OfficeContainer = when {
        header.startsWith(ZIP) -> OfficeContainer.ZIP
        header.startsWith(COMPOUND) -> OfficeContainer.COMPOUND
        else -> OfficeContainer.OTHER
    }

    /**
     * Whether a compound file holds an encrypted Office Open XML package, which is how Word and
     * PowerPoint save a document with a password. Its directory names a stream "EncryptedPackage",
     * written in UTF-16LE.
     */
    fun isEncryptedPackage(compound: InputStream): Boolean =
        contains(compound, "EncryptedPackage".flatMap { listOf(it.code.toByte(), 0) }.toByteArray())

    /** Whether [needle] occurs in [input], read in chunks. */
    fun contains(input: InputStream, needle: ByteArray): Boolean {
        require(needle.isNotEmpty())
        val buffer = ByteArray(maxOf(64 * 1024, needle.size * 2))
        var carried = 0
        while (true) {
            val read = input.read(buffer, carried, buffer.size - carried)
            if (read < 0) return false
            val end = carried + read
            for (start in 0..end - needle.size) {
                if (needle.indices.all { buffer[start + it] == needle[it] }) return true
            }
            // Keep the tail, in case the needle straddles two reads.
            carried = minOf(needle.size - 1, end)
            System.arraycopy(buffer, end - carried, buffer, 0, carried)
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}

/** Numbers for numbered lists, as Word and PowerPoint write them. */
object ListNumbering {
    enum class Format { DECIMAL, DECIMAL_ZERO, LOWER_LETTER, UPPER_LETTER, LOWER_ROMAN, UPPER_ROMAN, BULLET, NONE }

    /** Word's w:numFmt. Formats Usugami doesn't know, such as Chinese counting, become decimal. */
    fun wordFormat(numFmt: String?): Format = when (numFmt) {
        "decimalZero" -> Format.DECIMAL_ZERO
        "lowerLetter" -> Format.LOWER_LETTER
        "upperLetter" -> Format.UPPER_LETTER
        "lowerRoman" -> Format.LOWER_ROMAN
        "upperRoman" -> Format.UPPER_ROMAN
        "bullet" -> Format.BULLET
        "none" -> Format.NONE
        else -> Format.DECIMAL
    }

    /** [value] written in [format]. Letters go a…z, then aa…zz, as in Word; roman numerals stop at 3999. */
    fun format(value: Int, format: Format): String = when (format) {
        Format.DECIMAL -> value.toString()
        Format.DECIMAL_ZERO -> if (value in 0..9) "0$value" else value.toString()
        Format.LOWER_LETTER -> letters(value)
        Format.UPPER_LETTER -> letters(value).uppercase()
        Format.LOWER_ROMAN -> roman(value).lowercase()
        Format.UPPER_ROMAN -> roman(value)
        Format.BULLET, Format.NONE -> ""
    }

    /**
     * Word's level text, such as "%1." or "%1.%2)", with each %n replaced by the number of level n
     * (1-based) from [counters], written in that level's format from [formats].
     */
    fun wordLabel(levelText: String, counters: IntArray, formats: List<Format>): String {
        val out = StringBuilder()
        var i = 0
        while (i < levelText.length) {
            val c = levelText[i]
            val level = if (c == '%' && i + 1 < levelText.length) levelText[i + 1].digitToIntOrNull() else null
            if (level != null && level in 1..counters.size) {
                out.append(format(counters[level - 1], formats.getOrElse(level - 1) { Format.DECIMAL }))
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** A PowerPoint autonumber scheme such as "arabicPeriod" or "alphaLcParenBoth": its format and the text around the number. */
    fun powerPointScheme(scheme: String?): Triple<Format, String, String> {
        val s = scheme.orEmpty()
        val format = when {
            s.startsWith("alphaLc") -> Format.LOWER_LETTER
            s.startsWith("alphaUc") -> Format.UPPER_LETTER
            s.startsWith("romanLc") -> Format.LOWER_ROMAN
            s.startsWith("romanUc") -> Format.UPPER_ROMAN
            else -> Format.DECIMAL
        }
        val (prefix, suffix) = when {
            s.endsWith("ParenBoth") -> "(" to ")"
            s.endsWith("ParenR") -> "" to ")"
            s.endsWith("Plain") -> "" to ""
            s.endsWith("Minus") -> "" to " -"
            else -> "" to "."
        }
        return Triple(format, prefix, suffix)
    }

    private fun letters(value: Int): String {
        if (value <= 0) return value.toString()
        val letter = 'a' + (value - 1) % 26
        return letter.toString().repeat((value - 1) / 26 + 1)
    }

    private fun roman(value: Int): String {
        if (value !in 1..3999) return value.toString()
        val numerals = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
        var rest = value
        return buildString {
            for ((n, s) in numerals) while (rest >= n) {
                append(s)
                rest -= n
            }
        }
    }
}

/**
 * Bullets written in symbol fonts. Word and PowerPoint draw many bullets with characters of Symbol
 * or Wingdings, whose shapes aren't the letters' usual ones; phones don't have those fonts, so the
 * common ones become the matching Unicode characters, and any other one a plain bullet.
 */
object SymbolFonts {
    private val WINGDINGS = mapOf(
        0xA7 to "\u25AA", // small square
        0xD8 to "\u27A2", // arrowhead
        0xFC to "\u2713", // check mark
        0xFB to "\u2717", // cross
        0x76 to "\u2756", // diamond of four
        0x6E to "\u25A0", // black square
        0x6F to "\u25A1", // white square
        0x71 to "\u2751", // shadowed square
        0x6C to "\u25CF", // black circle
        0xA1 to "\u25CB", // white circle
        0x77 to "\u2B25", // diamond
        0xE0 to "\u2192", // right arrow
        0xE8 to "\u2794", // heavy right arrow
        0xF0 to "\u21E8", // white right arrow
    )
    private val SYMBOL = mapOf(0xB7 to "\u2022", 0x2D to "\u2013", 0xAE to "\u2192", 0xD8 to "\u00AC", 0xA8 to "\u2666")

    /**
     * [text] as it should show. A character in the private-use range F000–F0FF stands for that
     * font's character at its low byte, as Word writes symbols.
     */
    fun bullet(font: String?, text: String): String {
        if (text.isEmpty()) return text
        val name = font.orEmpty().lowercase()
        val symbolFont = name.startsWith("wingdings") || name == "symbol" || name.startsWith("webdings")
        val code = text.codePointAt(0)
        val privateUse = code in 0xF000..0xF0FF
        if (!symbolFont && !privateUse) return text
        val low = if (privateUse) code - 0xF000 else code
        return when {
            name == "symbol" || (privateUse && !name.startsWith("wingdings")) -> SYMBOL[low] ?: "\u2022"
            name.startsWith("wingdings") -> WINGDINGS[low] ?: "\u2022"
            else -> "\u2022"
        }
    }
}

/** Colours in Office files, as ARGB. */
object OfficeColors {
    /** "4472C4" (with or without alpha), or null for "auto" and anything else. */
    fun hex(text: String?): Int? {
        val t = text?.trim()?.removePrefix("#") ?: return null
        if (t.length != 6 || t.any { Character.digit(it, 16) < 0 }) return null
        return (0xFF000000 or t.toLong(16)).toInt()
    }

    /** Word's highlight colours (w:highlight). */
    fun highlight(name: String?): Int? = when (name) {
        "yellow" -> 0xFFFFFF00
        "green" -> 0xFF00FF00
        "cyan" -> 0xFF00FFFF
        "magenta" -> 0xFFFF00FF
        "blue" -> 0xFF0000FF
        "red" -> 0xFFFF0000
        "darkBlue" -> 0xFF000080
        "darkCyan" -> 0xFF008080
        "darkGreen" -> 0xFF008000
        "darkMagenta" -> 0xFF800080
        "darkRed" -> 0xFF800000
        "darkYellow" -> 0xFF808000
        "darkGray" -> 0xFF808080
        "lightGray" -> 0xFFC0C0C0
        "black" -> 0xFF000000
        "white" -> 0xFFFFFFFF
        else -> null
    }?.toInt()

    /** The few DrawingML preset colours (a:prstClr) that show up in practice; others are black. */
    fun preset(name: String?): Int = when (name?.lowercase()) {
        "white" -> 0xFFFFFFFF
        "red" -> 0xFFFF0000
        "green" -> 0xFF008000
        "lime" -> 0xFF00FF00
        "blue" -> 0xFF0000FF
        "yellow" -> 0xFFFFFF00
        "gray", "grey" -> 0xFF808080
        "silver", "ltgray", "lightgray" -> 0xFFC0C0C0
        "dkgray", "darkgray" -> 0xFFA9A9A9
        "orange" -> 0xFFFFA500
        "purple" -> 0xFF800080
        "navy" -> 0xFF000080
        "cyan", "aqua" -> 0xFF00FFFF
        "magenta", "fuchsia" -> 0xFFFF00FF
        else -> 0xFF000000
    }.toInt()

    /**
     * Applies DrawingML colour changes, in order: [modifiers] are element names (such as "lumMod",
     * "tint", "alpha") with their values in thousandths of a percent (75000 is 75%).
     */
    fun modify(argb: Int, modifiers: List<Pair<String, Int>>): Int {
        var a = (argb ushr 24 and 0xFF) / 255f
        var r = (argb shr 16 and 0xFF) / 255f
        var g = (argb shr 8 and 0xFF) / 255f
        var b = (argb and 0xFF) / 255f
        for ((name, raw) in modifiers) {
            val v = raw / 100_000f
            when (name) {
                "alpha" -> a = v
                "alphaMod" -> a *= v
                "alphaOff" -> a += v
                "lumMod", "lumOff", "satMod", "satOff", "hueOff" -> {
                    val hsl = toHsl(r, g, b)
                    when (name) {
                        "lumMod" -> hsl[2] *= v
                        "lumOff" -> hsl[2] += v
                        "satMod" -> hsl[1] *= v
                        "satOff" -> hsl[1] += v
                        else -> hsl[0] = (hsl[0] + raw / 60_000f / 360f).mod(1f)
                    }
                    hsl[1] = hsl[1].coerceIn(0f, 1f)
                    hsl[2] = hsl[2].coerceIn(0f, 1f)
                    val rgb = fromHsl(hsl[0], hsl[1], hsl[2])
                    r = rgb[0]; g = rgb[1]; b = rgb[2]
                }
                // Towards white, or towards black, by the share given.
                "tint" -> { r = 1 - (1 - r) * v; g = 1 - (1 - g) * v; b = 1 - (1 - b) * v }
                "shade" -> { r *= v; g *= v; b *= v }
                "gray" -> { val y = 0.3f * r + 0.59f * g + 0.11f * b; r = y; g = y; b = y }
                "inv" -> { r = 1 - r; g = 1 - g; b = 1 - b }
            }
        }
        fun channel(x: Float) = (x.coerceIn(0f, 1f) * 255).roundToInt()
        return (channel(a) shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    private fun toHsl(r: Float, g: Float, b: Float): FloatArray {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2
        if (max == min) return floatArrayOf(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2 - max - min) else d / (max + min)
        val h = when (max) {
            r -> (g - b) / d + (if (g < b) 6 else 0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } / 6
        return floatArrayOf(h, s, l)
    }

    private fun fromHsl(h: Float, s: Float, l: Float): FloatArray {
        if (s == 0f) return floatArrayOf(l, l, l)
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun hue(t0: Float): Float {
            val t = t0.mod(1f)
            return when {
                t < 1f / 6 -> p + (q - p) * 6 * t
                t < 1f / 2 -> q
                t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
                else -> p
            }
        }
        return floatArrayOf(hue(h + 1f / 3), hue(h), hue(h - 1f / 3))
    }

    /** Whether text in black would be hard to read on [argb]: a rough check of its brightness. */
    fun isDark(argb: Int): Boolean {
        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b < 128
    }
}

/** Column widths of a table. */
object TableColumns {
    /**
     * Widths for [count] columns from the table's grid ([grid], as saved by Word or PowerPoint),
     * scaled down to [available] if wider. Without a usable grid the columns share [preferred]
     * (or [available]) equally. A grid with fewer columns than [count] gets the missing ones at the
     * average width.
     */
    fun widths(grid: List<Float>, count: Int, available: Float, preferred: Float? = null): List<Float> {
        val n = maxOf(count, grid.size, 1)
        val usable = grid.filter { it > 0f }
        val widths = if (usable.isEmpty()) {
            val total = (preferred?.takeIf { it > 0f } ?: available).coerceAtMost(available)
            List(n) { total / n }
        } else {
            val average = usable.average().toFloat()
            List(n) { i -> grid.getOrNull(i)?.takeIf { it > 0f } ?: average }
        }
        val total = widths.sum()
        return if (total > available && total > 0f) widths.map { it * available / total } else widths
    }
}

/** Where a paragraph of text is split between two pages. */
object PageBreaking {
    /**
     * How many of a paragraph's lines go on this page when [space] is left, given each line's
     * bottom measured from the paragraph's top ([lineBottoms], increasing). Like Word's widow and
     * orphan control (when [avoidSingleLines]): a single first line isn't left alone at the bottom
     * of the page, and a single last line isn't carried alone to the next one. 0 means the whole
     * paragraph moves to the next page. With [force] (nothing else is on the page) at least one
     * line stays, however tall.
     */
    fun linesThatFit(lineBottoms: IntArray, space: Int, avoidSingleLines: Boolean, force: Boolean): Int {
        val count = lineBottoms.size
        var fit = lineBottoms.count { it <= space }
        if (avoidSingleLines && fit in 1 until count) {
            if (fit == count - 1) fit -= 1
            if (fit == 1) fit = 0
        }
        return if (force) maxOf(fit, minOf(1, count)) else fit
    }
}

/** A rectangle in a slide's or group's coordinates. */
data class OfficeRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val centerX get() = x + width / 2
    val centerY get() = y + height / 2
}

/**
 * How a group shape places its children: their coordinates ([childOffset], [childExtent]) are
 * stretched onto the group's own rectangle ([frame]).
 */
class GroupFrame(private val frame: OfficeRect, private val childOffset: Pair<Float, Float>, private val childExtent: Pair<Float, Float>) {
    private val scaleX = if (abs(childExtent.first) > 0f) frame.width / childExtent.first else 1f
    private val scaleY = if (abs(childExtent.second) > 0f) frame.height / childExtent.second else 1f

    /** Where a child at [rect], in the group's child coordinates, lands. */
    fun place(rect: OfficeRect): OfficeRect = OfficeRect(
        frame.x + (rect.x - childOffset.first) * scaleX,
        frame.y + (rect.y - childOffset.second) * scaleY,
        rect.width * scaleX,
        rect.height * scaleY,
    )
}
