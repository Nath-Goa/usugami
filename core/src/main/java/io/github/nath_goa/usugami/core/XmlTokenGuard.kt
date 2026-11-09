package io.github.nath_goa.usugami.core

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * An Office part's bytes on their way to Android's XML parser, which markup no Office file has can keep
 * busy for minutes in one call, where Cancel can't stop it, or run out of memory. The part fails as
 * damaged before the parser gets that far when it has:
 * - a tag, or text between tags, longer than [MAX_RUN] characters: the parser keeps each whole in
 *   memory. It joins text across comments, CDATA and processing instructions, copying what it has so
 *   far at each one, so that text counts as one, and so does the copying ([MAX_COPIED] in all);
 * - more than [MAX_ATTRIBUTES] attributes on the elements open at once, a tag's own among them: the
 *   parser takes longer over each attribute of a tag than over the one before, and goes through every
 *   namespace declared on the open elements for each prefix it looks up;
 * - an XML declaration with more attributes than the three one can have, as the parser reads them all
 *   as it reads a tag's before it finds out;
 * - a DOCTYPE, or any other declaration but a comment or CDATA, which Office never writes.
 *
 * The characters are counted as the parser reads them: in UTF-16 or UTF-32 when the part starts with
 * a byte order mark or a "<" in one of them, else one byte at a time, as UTF-8. A part whose XML
 * declaration names an encoding in which those bytes could mean something else (UTF-16 after an
 * 8-bit declaration, say), which Office never writes, is refused, as the parser would read it in that
 * encoding. Comments, CDATA and processing instructions end where the parser ends them, so nothing in
 * one can hide a tag's attributes from the count.
 */
class XmlTokenGuard(input: InputStream) : FilterInputStream(input) {
    /** What the character being read is part of. */
    private enum class Markup {
        TEXT,

        /** Just after a "<", before it is known what it opens. */
        OPENED,
        ELEMENT,
        END_TAG,

        /** Just after "<!": a comment or CDATA follows, as nothing else is let through. */
        BANG,

        /** The rest of "<!--" or "<![CDATA[" ([expected]), which the parser reads before [then]. */
        OPENING,
        COMMENT,
        CDATA,
        INSTRUCTION,

        /** After a "<?" at the part's very start: "xml " makes it the XML declaration, anything else an instruction. */
        XML_NAME,
        DECLARATION,
    }

    /** The part's first four bytes, until they say how wide its characters are. */
    private val head = ByteArray(4)
    private var headLength = 0

    /** Bytes to a character: 0 until known, then 1, 2 (UTF-16) or 4 (UTF-32). */
    private var width = 0
    private var bigEndian = true
    private var unit = 0
    private var unitBytes = 0

    /** Characters of a byte order mark still to pass over: the parser drops it before reading. */
    private var bom = 0

    /** The XML declaration read so far, while the part starts with one in 8-bit characters. */
    private var declaration: StringBuilder? = null

    private var markup = Markup.TEXT

    /** No character but a byte order mark read yet: only there can an XML declaration start. */
    private var atStart = true

    /** The tag being read opens the part, so it may be the XML declaration. */
    private var opensPart = false
    private var expected = ""
    private var matched = 0
    private var then = Markup.TEXT

    /** How many of the characters that end a comment, CDATA or instruction ("--", "]]", "?") came last. */
    private var closing = 0

    /** Characters in the tag being read, or since the last tag: text, with the comments, CDATA and instructions in it. */
    private var run = 0

    /** Characters the parser copies joining the text since the last tag across those comments, CDATA and instructions. */
    private var copied = 0
    private var quote = 0
    private var slash = false
    private var attributes = 0

    /** The elements open, as far as read, and the attributes on those of them that have any. */
    private var open = 0
    private var inScope = 0

    /** For each open element with attributes, outermost first: its depth, then how many it has. */
    private var scopes = IntArray(32)
    private var scopeCount = 0

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

    /** As Android's parser tells the encoding from the first four bytes ([start]); sets [bigEndian] and [bom]. */
    private fun widthOf(start: Int): Int = when {
        start == 0x0000FEFF -> 4.also { bom = 1 }
        start == 0xFFFE0000.toInt() -> 4.also {
            bigEndian = false
            bom = 1
        }
        start == 0x0000003C -> 4
        start == 0x3C000000 -> 4.also { bigEndian = false }
        start == 0x003C003F -> 2
        start == 0x3C003F00 -> 2.also { bigEndian = false }
        start ushr 16 == 0xFEFF -> 2.also { bom = 1 }
        start ushr 16 == 0xFFFE -> 2.also {
            bigEndian = false
            bom = 1
        }
        start ushr 8 == UTF8_BOM -> 1.also { bom = 3 }
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

    /** Counts [c] into the markup or text it is part of, and fails the part once one goes past a limit. */
    private fun character(c: Char) {
        if (bom > 0) {
            bom--
            return
        }
        declaration?.let { text ->
            text.append(c)
            if (c == '>') {
                declaration = null
                checkEncoding(text.toString())
            } else if (text.length > MAX_DECLARATION) {
                throw IOException("Office part with an XML declaration too long to read")
            }
        }
        val first = atStart
        atStart = false
        if (++run > MAX_RUN) throw IOException("Office part with a tag or text too long to read")
        when (markup) {
            Markup.TEXT -> if (c == '<') {
                markup = Markup.OPENED
                opensPart = first
            }
            Markup.OPENED -> opened(c)
            Markup.ELEMENT, Markup.DECLARATION -> tag(c)
            Markup.END_TAG -> if (c == '>') endTag()
            Markup.BANG -> when (c) {
                '-' -> expect("-", Markup.COMMENT)
                '[' -> expect("CDATA[", Markup.CDATA)
                else -> throw IOException("Office part with a DOCTYPE or declaration Office doesn't write")
            }
            Markup.OPENING -> {
                // The parser fails on anything else here too.
                if (c != expected[matched]) throw IOException("Office part with a broken comment or CDATA")
                if (++matched == expected.length) {
                    markup = then
                    closing = 0
                }
            }
            Markup.COMMENT -> special(c, '-', 2)
            Markup.CDATA -> special(c, ']', 2)
            Markup.INSTRUCTION -> special(c, '?', 1)
            Markup.XML_NAME -> if (c.lowercaseChar() == XML_NAME[matched]) {
                if (++matched == XML_NAME.length) startTag(Markup.DECLARATION)
            } else {
                markup = Markup.INSTRUCTION
                closing = 0
                special(c, '?', 1)
            }
        }
    }

    /** [c] comes just after a "<". */
    private fun opened(c: Char) {
        when (c) {
            '/' -> {
                markup = Markup.END_TAG
                run = 0
                copied = 0
            }
            '!', '?' -> {
                // Text before and after this is joined, the text so far copied each time.
                copied += run
                if (copied > MAX_COPIED) throw IOException("Office part with text broken up too often to read")
                if (c == '!') {
                    markup = Markup.BANG
                } else if (opensPart) {
                    markup = Markup.XML_NAME
                    matched = 0
                } else {
                    markup = Markup.INSTRUCTION
                    closing = 0
                }
            }
            else -> {
                startTag(Markup.ELEMENT)
                run = 0
                copied = 0
            }
        }
    }

    /** Reads [text], what must follow, before going on to [next]. */
    private fun expect(text: String, next: Markup) {
        markup = Markup.OPENING
        expected = text
        matched = 0
        then = next
    }

    /** Starts reading a start tag or the XML declaration ([kind]). */
    private fun startTag(kind: Markup) {
        markup = kind
        attributes = 0
        quote = 0
        slash = false
    }

    /** [c] in a start tag or the XML declaration, where quotes are tracked and each "=" outside them is an attribute. */
    private fun tag(c: Char) {
        when {
            quote != 0 -> if (c.code == quote) quote = 0
            c == '"' || c == '\'' -> {
                quote = c.code
                slash = false
            }
            c == '=' -> {
                attributes++
                if (markup == Markup.DECLARATION) {
                    if (attributes > DECLARATION_ATTRIBUTES) throw IOException("Office part with too many attributes in its XML declaration")
                } else if (inScope + attributes > MAX_ATTRIBUTES) {
                    throw IOException("Office part with too many attributes on its open elements")
                }
                slash = false
            }
            c == '/' -> slash = true
            c == '>' -> {
                if (markup == Markup.ELEMENT && !slash) push()
                markup = Markup.TEXT
                run = 0
                copied = 0
            }
            c > ' ' -> slash = false
        }
    }

    /** The start tag just read leaves its element open, with its [attributes] in scope until it ends. */
    private fun push() {
        open++
        if (attributes == 0) return
        if (scopeCount + 2 > scopes.size) scopes = scopes.copyOf(scopes.size * 2)
        scopes[scopeCount++] = open
        scopes[scopeCount++] = attributes
        inScope += attributes
    }

    /** An end tag closes the innermost open element, taking its attributes out of scope. */
    private fun endTag() {
        if (open > 0) {
            if (scopeCount > 0 && scopes[scopeCount - 2] == open) {
                inScope -= scopes[scopeCount - 1]
                scopeCount -= 2
            }
            open--
        }
        markup = Markup.TEXT
        run = 0
        copied = 0
    }

    /** [c] in a comment, CDATA or instruction, which ends at a ">" after [count] of [closer] in a row. */
    private fun special(c: Char, closer: Char, count: Int) {
        if (c == '>' && closing >= count) {
            markup = Markup.TEXT
            return
        }
        closing = if (c == closer) closing + 1 else 0
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

        /** Text joined across comments copied in all: a few times [MAX_RUN], a fraction of a second's work. */
        const val MAX_COPIED = 4 * MAX_RUN

        /** Far more than Office writes: a few dozen on one element, under a hundred on all those open at once. */
        const val MAX_ATTRIBUTES = 4096

        /** Version, encoding and standalone. */
        const val DECLARATION_ATTRIBUTES = 3

        /** As long as the parser reads a declaration: it fails on a longer one. */
        const val MAX_DECLARATION = 8192

        /** "<?xm" in 8-bit characters: the parser then reads the encoding the declaration names. */
        const val XML_DECLARATION = 0x3C3F786D

        /** A byte order mark in UTF-8. */
        const val UTF8_BOM = 0xEFBBBF

        /** What makes a "<?" at the part's start its XML declaration, in any case but the space. */
        const val XML_NAME = "xml "

        /** Encodings with one byte to each ASCII character, in which this counting holds. */
        val EIGHT_BIT = Regex("""utf-?8|(us-)?ascii|iso[-_]?8859[-_]\d+|latin-?1|(windows|cp)-?125\d""", RegexOption.IGNORE_CASE)
    }
}
