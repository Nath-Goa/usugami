package io.github.nath_goa.usugami.core

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * An Office part's bytes on their way to Android's XML parser, which keeps each tag and each run of
 * text between tags whole in memory, and takes longer for each attribute a tag has than for the one
 * before. A tag or text longer than [MAX_RUN] characters, or a tag with more than [MAX_ATTRIBUTES]
 * attributes, which only a crafted file has, fails the part as damaged before the parser runs out of
 * memory or time on it.
 *
 * The characters are counted as the parser reads them: in UTF-16 or UTF-32 when the part starts with
 * a byte order mark or a "<" in one of them, else one byte at a time, as UTF-8. A part whose XML
 * declaration names an encoding in which those bytes could mean something else (UTF-16 after an
 * 8-bit declaration, say), which Office never writes, is refused, as the parser would read it in that
 * encoding.
 */
class XmlTokenGuard(input: InputStream) : FilterInputStream(input) {
    /** The part's first four bytes, until they say how wide its characters are. */
    private val head = ByteArray(4)
    private var headLength = 0

    /** Bytes to a character: 0 until known, then 1, 2 (UTF-16) or 4 (UTF-32). */
    private var width = 0
    private var bigEndian = true
    private var unit = 0
    private var unitBytes = 0

    /** The XML declaration read so far, while the part starts with one in 8-bit characters. */
    private var declaration: StringBuilder? = null

    private var run = 0
    private var inTag = false
    private var afterOpening = false

    /** In a comment, CDATA or declaration, where quotes and = aren't counted. */
    private var special = false
    private var quote = 0
    private var attributes = 0

    override fun read(): Int = super.read().also { if (it >= 0) see(it) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { n ->
        for (i in off until off + n) see(b[i].toInt() and 0xFF)
    }

    /** Looks at the part's next [byte]; the first four are held until they say how wide its characters are. */
    private fun see(byte: Int) {
        if (width != 0) return take(byte)
        head[headLength++] = byte.toByte()
        if (headLength < head.size) return
        width = widthOf(head.fold(0) { start, b -> (start shl 8) or (b.toInt() and 0xFF) })
        for (b in head) take(b.toInt() and 0xFF)
    }

    /** As Android's parser tells the encoding from the first four bytes ([start]); sets [bigEndian]. */
    private fun widthOf(start: Int): Int = when {
        start == 0x0000FEFF || start == 0x0000003C -> 4
        start == 0xFFFE0000.toInt() || start == 0x3C000000 -> 4.also { bigEndian = false }
        start == 0x003C003F || start ushr 16 == 0xFEFF -> 2
        start == 0x3C003F00 || start ushr 16 == 0xFFFE -> 2.also { bigEndian = false }
        else -> 1.also { if (start == XML_DECLARATION) declaration = StringBuilder() }
    }

    /** Puts [byte] into the character being read, and looks at that character once it is whole. */
    private fun take(byte: Int) {
        if (width == 1) return character(byte.toChar())
        unit = if (bigEndian) (unit shl 8) or byte else unit or (byte shl (8 * unitBytes))
        if (++unitBytes < width) return
        // Only ASCII characters mean anything here; any other is text or part of a name.
        character(if (unit in 0..0x7F) unit.toChar() else 'x')
        unit = 0
        unitBytes = 0
    }

    /** Counts [c] into the tag or text it is part of, and fails the part once one goes past a limit. */
    private fun character(c: Char) {
        declaration?.let { text ->
            text.append(c)
            if (c == '>') {
                declaration = null
                checkEncoding(text.toString())
            } else if (text.length > MAX_DECLARATION) {
                throw IOException("Office part with an XML declaration too long to read")
            }
        }
        if (++run > MAX_RUN) throw IOException("Office part with a tag or text too long to read")
        if (!inTag) {
            if (c == '<') {
                inTag = true
                afterOpening = true
                quote = 0
                attributes = 0
                run = 0
            } else if (c == '>') {
                run = 0
            }
            return
        }
        if (afterOpening) {
            afterOpening = false
            special = c == '!' || c == '?'
        }
        when {
            quote != 0 -> if (c.code == quote) quote = 0
            c == '>' -> {
                inTag = false
                run = 0
            }
            special -> Unit
            c == '"' || c == '\'' -> quote = c.code
            c == '=' -> if (++attributes > MAX_ATTRIBUTES) throw IOException("Office part with too many attributes in a tag")
        }
    }

    /**
     * Refuses the part when its XML declaration ([text], up to its ">") names an encoding the parser
     * would read the rest in that isn't one byte to an ASCII character, as Android's parser finds it.
     */
    private fun checkEncoding(text: String) {
        var at = text.indexOf("encoding")
        if (at < 0) return
        while (at < text.length && text[at] != '"' && text[at] != '\'') at++
        // Without a quoted name the parser fails on the declaration itself.
        if (at >= text.length) return
        val end = text.indexOf(text[at], at + 1)
        if (end < 0) return
        if (!EIGHT_BIT.matches(text.substring(at + 1, end))) throw IOException("Office part in an encoding Office doesn't write")
    }

    private companion object {
        /** Far longer than any text or tag Office writes: a cell holds 32,767 characters at most. */
        const val MAX_RUN = 8 * 1024 * 1024
        const val MAX_ATTRIBUTES = 4096

        /** As long as the parser reads a declaration: it fails on a longer one. */
        const val MAX_DECLARATION = 8192

        /** "<?xm" in 8-bit characters: the parser then reads the encoding the declaration names. */
        const val XML_DECLARATION = 0x3C3F786D

        /** Encodings with one byte to each ASCII character, in which this counting holds. */
        val EIGHT_BIT = Regex("""utf-?8|(us-)?ascii|iso[-_]?8859[-_]\d+|latin-?1|(windows|cp)-?125\d""", RegexOption.IGNORE_CASE)
    }
}
