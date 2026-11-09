package io.github.nath_goa.usugami.core

import java.io.InputStream
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
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
     * A DrawingML percentage in thousandths of a percent (90000 is 90%): as Office writes it, or as
     * Strict Open XML writes it ("90%"). Null when [text] is neither.
     */
    fun percentage(text: String?): Int? {
        val t = text?.trim() ?: return null
        if (t.endsWith("%")) return t.dropLast(1).trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.let { (it * 1000).roundToInt() }
        return t.toIntOrNull() ?: t.toDoubleOrNull()?.toInt()
    }

    /**
     * A length from a VML style such as "width:120pt" or "1.5in", in points. Bare numbers are
     * pixels at 96 dpi, as in CSS. Null when [text] isn't a length.
     */
    fun cssLength(text: String): Float? {
        // No length is longer: a crafted one, thousands of digits long, would keep the pattern busy for minutes.
        if (text.length > 64) return null
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

    /**
     * [value] written in [format]. Letters go a…z, then aa…zz and on to 30 z's (780), as in Word;
     * roman numerals stop at 3999. Beyond those, as for 0 and less, the number is written in digits.
     */
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

    /**
     * The number format a Word field's "\*" switch asks for, as in "PAGE \* roman": roman numerals or
     * letters, in lower case when the switch is, or arabic. Null when there's no such switch.
     */
    fun fieldFormat(instruction: String): Format? {
        val switch = Regex("""\\\*\s*(\w+)""").findAll(instruction).map { it.groupValues[1] }
            .firstOrNull { it.lowercase() in listOf("roman", "alphabetic", "arabic") } ?: return null
        val lower = switch == switch.lowercase()
        return when (switch.lowercase()) {
            "roman" -> if (lower) Format.LOWER_ROMAN else Format.UPPER_ROMAN
            "alphabetic" -> if (lower) Format.LOWER_LETTER else Format.UPPER_LETTER
            else -> Format.DECIMAL
        }
    }

    /**
     * A footnote's or endnote's mark: [value] in Word's number format [numFmt] ("decimal",
     * "lowerRoman"...), or [default] when it gives none or one that writes no number. Word's "chicago"
     * is *, †, ‡ and §, then each twice, three times and so on.
     */
    fun noteMark(value: Int, numFmt: String?, default: Format): String {
        if (numFmt == "chicago") {
            if (value !in 1..40) return value.toString()
            return "*†‡§"[(value - 1) % 4].toString().repeat((value - 1) / 4 + 1)
        }
        val format = numFmt?.let(::wordFormat)?.takeUnless { it == Format.BULLET || it == Format.NONE } ?: default
        return format(value, format)
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
        // A page or list can start at any number: a huge one would make a label millions of letters long.
        if (value !in 1..780) return value.toString()
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

    /**
     * A character Word inserts with Insert > Symbol (w:sym), [text] in [font], as it should show:
     * Symbol's Greek letters, maths and arrows, and Wingdings' check boxes, ticks and bullets. A
     * character of a symbol font that isn't known becomes U+FFFD, never a bullet, so a symbol that
     * can't be shown doesn't pass for another.
     */
    fun character(font: String?, text: String): String {
        if (text.isEmpty()) return text
        val name = font.orEmpty().trim().lowercase()
        val code = text.codePointAt(0)
        val privateUse = code in 0xF000..0xF0FF
        val low = if (privateUse) code - 0xF000 else code
        val dingbats = name.startsWith("wingdings") || name.startsWith("webdings")
        if (!privateUse && (low > 0xFF || (!dingbats && name != "symbol"))) return text
        return when {
            name == "wingdings" -> WINGDINGS[low] ?: WINGDINGS_SYMBOLS[low]
            name == "wingdings 2" -> WINGDINGS_2[low]
            dingbats -> null
            // Symbol, or a private-use character in another font: Word's symbols are Symbol's.
            else -> SYMBOL_TEXT[low] ?: low.takeIf { it in 0x20..0x7E }?.toChar()?.toString()
        } ?: "\uFFFD"
    }

    /**
     * A run of text in [font] as it should show, as [character] maps it: every character when
     * [font] is Symbol, Wingdings or Webdings, and in any font the private-use characters F000–F0FF
     * that stand for a symbol font's: [symbol]'s (PowerPoint's a:sym) when given. Spaces and line
     * breaks stay.
     */
    fun text(font: String?, text: String, symbol: String? = null): String {
        val name = font.orEmpty().trim().lowercase()
        val symbolFont = name == "symbol" || name.startsWith("wingdings") || name.startsWith("webdings")
        if (!symbolFont && text.none { it.code in 0xF000..0xF0FF }) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val code = text.codePointAt(i)
            val char = String(Character.toChars(code))
            out.append(
                when {
                    code <= 0x20 -> char
                    code in 0xF000..0xF0FF -> character(symbol ?: font, char)
                    symbolFont -> character(font, char)
                    else -> char
                },
            )
            i += char.length
        }
        return out.toString()
    }

    /** Wingdings beyond the bullets: check boxes, and a few others found in forms. */
    private val WINGDINGS_SYMBOLS = mapOf(
        0xA8 to "\u25FB", // empty check box
        0xFE to "\u2611", // check box with a tick
        0xFD to "\u2612", // check box with a cross
        0x78 to "\u2327", // check box with a cross, filled
        0x70 to "\u25A1", // white square
        0x75 to "\u25C6", // black diamond
        0x4A to "\u263A", // smiling face
        0x4C to "\u2639", // frowning face
        0x28 to "\u260E", // telephone
    )

    private val WINGDINGS_2 = mapOf(
        0x4F to "\u2717", // cross
        0x50 to "\u2713", // tick
        0x51 to "\u2612", // check box with a cross
        0x52 to "\u2611", // check box with a tick
        0x53 to "\u2612",
        0x54 to "\u2612",
        0xA3 to "\u25A1", // empty check box
    )

    /** The Symbol font's characters that aren't the ASCII ones at the same place: Adobe's Symbol encoding. */
    private val SYMBOL_TEXT: Map<Int, String> = mapOf(
        0x22 to 0x2200, 0x24 to 0x2203, 0x27 to 0x220B, 0x2A to 0x2217, 0x2D to 0x2212, 0x40 to 0x2245,
        // Greek capitals
        0x41 to 0x0391, 0x42 to 0x0392, 0x43 to 0x03A7, 0x44 to 0x0394, 0x45 to 0x0395, 0x46 to 0x03A6,
        0x47 to 0x0393, 0x48 to 0x0397, 0x49 to 0x0399, 0x4A to 0x03D1, 0x4B to 0x039A, 0x4C to 0x039B,
        0x4D to 0x039C, 0x4E to 0x039D, 0x4F to 0x039F, 0x50 to 0x03A0, 0x51 to 0x0398, 0x52 to 0x03A1,
        0x53 to 0x03A3, 0x54 to 0x03A4, 0x55 to 0x03A5, 0x56 to 0x03C2, 0x57 to 0x03A9, 0x58 to 0x039E,
        0x59 to 0x03A8, 0x5A to 0x0396, 0x5C to 0x2234, 0x5E to 0x22A5, 0x60 to 0x203E,
        // Greek small letters
        0x61 to 0x03B1, 0x62 to 0x03B2, 0x63 to 0x03C7, 0x64 to 0x03B4, 0x65 to 0x03B5, 0x66 to 0x03C6,
        0x67 to 0x03B3, 0x68 to 0x03B7, 0x69 to 0x03B9, 0x6A to 0x03D5, 0x6B to 0x03BA, 0x6C to 0x03BB,
        0x6D to 0x03BC, 0x6E to 0x03BD, 0x6F to 0x03BF, 0x70 to 0x03C0, 0x71 to 0x03B8, 0x72 to 0x03C1,
        0x73 to 0x03C3, 0x74 to 0x03C4, 0x75 to 0x03C5, 0x76 to 0x03D6, 0x77 to 0x03C9, 0x78 to 0x03BE,
        0x79 to 0x03C8, 0x7A to 0x03B6, 0x7E to 0x223C,
        0xA0 to 0x20AC, 0xA1 to 0x03D2, 0xA2 to 0x2032, 0xA3 to 0x2264, 0xA4 to 0x2044, 0xA5 to 0x221E,
        0xA6 to 0x0192, 0xA7 to 0x2663, 0xA8 to 0x2666, 0xA9 to 0x2665, 0xAA to 0x2660, 0xAB to 0x2194,
        0xAC to 0x2190, 0xAD to 0x2191, 0xAE to 0x2192, 0xAF to 0x2193, 0xB0 to 0x00B0, 0xB1 to 0x00B1,
        0xB2 to 0x2033, 0xB3 to 0x2265, 0xB4 to 0x00D7, 0xB5 to 0x221D, 0xB6 to 0x2202, 0xB7 to 0x2022,
        0xB8 to 0x00F7, 0xB9 to 0x2260, 0xBA to 0x2261, 0xBB to 0x2248, 0xBC to 0x2026, 0xBD to 0x23D0,
        0xBE to 0x23AF, 0xBF to 0x21B5, 0xC0 to 0x2135, 0xC1 to 0x2111, 0xC2 to 0x211C, 0xC3 to 0x2118,
        0xC4 to 0x2297, 0xC5 to 0x2295, 0xC6 to 0x2205, 0xC7 to 0x2229, 0xC8 to 0x222A, 0xC9 to 0x2283,
        0xCA to 0x2287, 0xCB to 0x2284, 0xCC to 0x2282, 0xCD to 0x2286, 0xCE to 0x2208, 0xCF to 0x2209,
        0xD0 to 0x2220, 0xD1 to 0x2207, 0xD2 to 0x00AE, 0xD3 to 0x00A9, 0xD4 to 0x2122, 0xD5 to 0x220F,
        0xD6 to 0x221A, 0xD7 to 0x22C5, 0xD8 to 0x00AC, 0xD9 to 0x2227, 0xDA to 0x2228, 0xDB to 0x21D4,
        0xDC to 0x21D0, 0xDD to 0x21D1, 0xDE to 0x21D2, 0xDF to 0x21D3, 0xE0 to 0x25CA, 0xE1 to 0x27E8,
        0xE2 to 0x00AE, 0xE3 to 0x00A9, 0xE4 to 0x2122, 0xE5 to 0x2211, 0xE6 to 0x239B, 0xE7 to 0x239C,
        0xE8 to 0x239D, 0xE9 to 0x23A1, 0xEA to 0x23A2, 0xEB to 0x23A3, 0xEC to 0x23A7, 0xED to 0x23A8,
        0xEE to 0x23A9, 0xEF to 0x23AA, 0xF1 to 0x27E9, 0xF2 to 0x222B, 0xF3 to 0x2320, 0xF4 to 0x23AE,
        0xF5 to 0x2321, 0xF6 to 0x239E, 0xF7 to 0x239F, 0xF8 to 0x23A0, 0xF9 to 0x23A4, 0xFA to 0x23A5,
        0xFB to 0x23A6, 0xFC to 0x23AB, 0xFD to 0x23AC, 0xFE to 0x23AD,
    ).mapValues { String(Character.toChars(it.value)) }
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
     * "tint", "alpha") with their values in thousandths of a percent (75000 is 75%); those that take
     * no value ("inv", "gray", "comp") with any.
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
                "lumMod", "lumOff", "satMod", "satOff", "hueOff", "comp" -> {
                    val hsl = toHsl(r, g, b)
                    when (name) {
                        "lumMod" -> hsl[2] *= v
                        "lumOff" -> hsl[2] += v
                        "satMod" -> hsl[1] *= v
                        "satOff" -> hsl[1] += v
                        // The complementary colour: across the colour wheel.
                        "comp" -> hsl[0] = (hsl[0] + 0.5f).mod(1f)
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

/**
 * A Word paragraph's, list level's or style's own tab stops, in points: those it [set]s, and those it
 * [cleared] (w:tab w:val="clear") of the stops it would inherit. Those of the set stops that are
 * right or centre stops are [aligned]; the others are left stops.
 */
data class TabStops(val set: List<Float>, val cleared: List<Float> = emptyList(), val aligned: Map<Float, TabAlign> = emptyMap()) {
    /** These over [base]'s (its style's, say): as in Word, the stops [base] has, but those cleared here, and these. */
    fun over(base: TabStops?): TabStops = if (base == null) this else TabStops(
        (base.set.filter { it !in cleared } + set).distinct().sorted(),
        (cleared + base.cleared).filter { it !in set }.distinct(),
        base.aligned.filterKeys { it !in cleared && it !in set } + aligned,
    )

    /**
     * Where each tab of a line takes the text after it, as Word puts it: at a left stop, ending at a
     * right one, centred on a centre one, and past the stops at the next of every [defaultTab]. [start]
     * is where the line's text starts and [widths] the width of each piece of text between its tabs
     * (the first before any); [scale] turns these stops' points into the same units. Each place is
     * past the text before it, so tabs given these as plain left stops land just there.
     */
    fun positions(start: Float, widths: List<Float>, defaultTab: Float, scale: Float = 1f): List<Float> {
        val out = ArrayList<Float>()
        var x = start + (widths.firstOrNull() ?: 0f)
        for (width in widths.drop(1)) {
            val stop = set.firstOrNull { it * scale > x + 1f }
            val at = if (stop == null) {
                (floor(x / defaultTab) + 1) * defaultTab
            } else {
                when (aligned[stop]) {
                    TabAlign.RIGHT -> stop * scale - width
                    TabAlign.CENTER -> stop * scale - width / 2
                    null -> stop * scale
                }
            }
            x = max(at, x + 1f).also { out += it } + width
        }
        return out
    }
}

/** A Word tab stop that the text after the tab ends at ([RIGHT]) or is centred on ([CENTER]), rather than starts at. */
enum class TabAlign { RIGHT, CENTER }

/** Where a paragraph of text is split between two pages. */
object PageBreaking {
    /**
     * How many of a paragraph's lines go on this page when [space] is left, given each line's
     * bottom measured from the paragraph's top ([lineBottoms], increasing). Like Word's widow and
     * orphan control (when [avoidSingleLines]): a single first line isn't left alone at the bottom
     * of the page, and a single last line isn't carried alone to the next one. 0 means the whole
     * paragraph moves to the next page. With [force] (nothing else is on the page) at least one
     * line stays, however tall. Lines that follow on from the paragraph's lines laid out before them
     * ([keepFirst] false) or go on in lines laid out after them ([keepLast] false) are like any
     * other lines there: a single one is left alone.
     */
    fun linesThatFit(lineBottoms: IntArray, space: Int, avoidSingleLines: Boolean, force: Boolean, keepFirst: Boolean = true, keepLast: Boolean = true): Int {
        val count = lineBottoms.size
        var fit = lineBottoms.count { it <= space }
        if (avoidSingleLines && fit in 1 until count) {
            if (keepLast && fit == count - 1) fit -= 1
            if (keepFirst && fit == 1) fit = 0
        }
        return if (force) maxOf(fit, minOf(1, count)) else fit
    }
}

/**
 * Runs of text with no space wider than their line, as a pasted hash, link or base64 block can be,
 * broken as Word breaks them.
 */
object LongWords {
    /**
     * Where a run of text with no space, whose characters are [advances] wide (0 for one that goes with
     * the one before it, such as a mark), ends each of its lines but the last, each line as full as can
     * be: at the last place [breakable] allows (by the index of the character after it, as after a
     * hyphen) that fits, or, with none, after the last character that fits, as Word breaks a word wider
     * than the line. The run's first line has [room] for it, and the others are [other] wide. When there
     * is text before it on its first line ([lineStart] false), an end at 0 moves it to the next line.
     * Each line takes at least one character. [fits] has the last say on whether the run from one index
     * to another fits in a width: measured on its own, a line can come out a little wider.
     */
    fun lineEnds(
        advances: FloatArray,
        room: Float,
        other: Float,
        lineStart: Boolean = true,
        breakable: (Int) -> Boolean = { false },
        fits: (Int, Int, Float) -> Boolean = { _, _, _ -> true },
    ): IntArray {
        val out = ArrayList<Int>()
        var start = 0
        var limit = room
        var alone = lineStart
        while (start < advances.size) {
            var width = 0f
            var end = start
            while (end < advances.size) {
                val advance = advances[end]
                if (advance > 0f && width + advance > limit && (end > start || !alone)) break
                width += advance
                end++
            }
            if (end >= advances.size) break
            val at = (end downTo start + 1).firstOrNull { breakable(it) && fits(start, it, limit) } ?: if (!alone) start else {
                var cut = end
                while (!fits(start, cut, limit)) cut = (cut - 1 downTo start + 1).firstOrNull { advances[it] > 0f } ?: break
                cut
            }
            out += at
            start = at
            limit = other
            alone = true
        }
        return out.toIntArray()
    }
}

/** A rectangle in a slide's or group's coordinates. */
data class OfficeRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val centerX get() = x + width / 2
    val centerY get() = y + height / 2
}

/**
 * How a group shape places its children: their coordinates ([childOffset], [childExtent]) are
 * stretched onto the group's own rectangle ([frame]), and mirrored in it when the group is flipped
 * ([flipH], [flipV]).
 */
class GroupFrame(
    private val frame: OfficeRect,
    private val childOffset: Pair<Float, Float>,
    private val childExtent: Pair<Float, Float>,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
) {
    private val scaleX = if (abs(childExtent.first) > 0f) frame.width / childExtent.first else 1f
    private val scaleY = if (abs(childExtent.second) > 0f) frame.height / childExtent.second else 1f

    /** Where a child at [rect], in the group's child coordinates, lands. */
    fun place(rect: OfficeRect): OfficeRect {
        val x = frame.x + (rect.x - childOffset.first) * scaleX
        val y = frame.y + (rect.y - childOffset.second) * scaleY
        val width = rect.width * scaleX
        val height = rect.height * scaleY
        return OfficeRect(
            if (flipH) 2 * frame.centerX - x - width else x,
            if (flipV) 2 * frame.centerY - y - height else y,
            width,
            height,
        )
    }

    /**
     * A child's own turn ([rotation], degrees clockwise) and flips as they show once the group's
     * flips mirror it: each flip of the group adds one to the child's, and a single mirroring turns
     * it the other way.
     */
    fun mirror(rotation: Float, flipH: Boolean, flipV: Boolean): Triple<Float, Boolean, Boolean> =
        Triple(if (this.flipH != this.flipV) -rotation else rotation, flipH != this.flipH, flipV != this.flipV)
}

/**
 * PowerPoint table styles (Light Style 1, Medium Style 2...): which of a style's parts apply to a
 * cell, and which of a part's borders draws each of the cell's edges.
 */
object SlideTableStyles {
    /**
     * The parts that apply to the cell in [row] of [rows] covering columns [start] until [end] of
     * [columns], weakest first, for a table with [flags] (firstRow, bandRow...). Banded rows and
     * columns start after a header row or column.
     */
    fun parts(flags: Set<String>, row: Int, rows: Int, start: Int, end: Int, columns: Int): List<String> {
        val parts = arrayListOf("wholeTbl")
        val bodyRow = if ("firstRow" in flags) row - 1 else row
        val bodyColumn = if ("firstCol" in flags) start - 1 else start
        if ("bandRow" in flags && bodyRow >= 0) parts += if (bodyRow % 2 == 0) "band1H" else "band2H"
        if ("bandCol" in flags && bodyColumn >= 0) parts += if (bodyColumn % 2 == 0) "band1V" else "band2V"
        if ("lastCol" in flags && end >= columns) parts += "lastCol"
        if ("firstCol" in flags && start == 0) parts += "firstCol"
        if ("lastRow" in flags && row == rows - 1) parts += "lastRow"
        if ("firstRow" in flags && row == 0) parts += "firstRow"
        return parts
    }

    /**
     * The border of part [part] that draws a cell's [side] edge ("left", "top", "right" or
     * "bottom"): the side itself where the edge is on the outline of what the part covers (the
     * whole table, a row or a column), its insideH or insideV within it. [outer]: the edge is on the
     * table's outline.
     */
    fun border(part: String, side: String, outer: Boolean): String {
        val horizontal = side == "top" || side == "bottom"
        val onOutline = when (part) {
            "firstRow", "lastRow", "band1H", "band2H" -> horizontal || outer
            "firstCol", "lastCol", "band1V", "band2V" -> !horizontal || outer
            else -> outer
        }
        return if (onOutline) side else if (horizontal) "insideH" else "insideV"
    }
}
