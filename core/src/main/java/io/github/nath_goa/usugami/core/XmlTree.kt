package io.github.nath_goa.usugami.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Usugami's own small, strict XML reader, for the XMP packets of PDFs ([XmpPacket]). No platform
 * parser reads this untrusted input, so no DOCTYPE or entity can make it do anything, it reads the
 * same on the desktop JVM and on Android, and the tree it makes can be written back as it was read.
 *
 * What it reads:
 * - UTF-8 (with or without its byte order mark), or UTF-16BE or LE told by a byte order mark or by
 *   the first two bytes `00 3C` / `3C 00`; anything else (UTF-32, Latin-1 bytes in what should be
 *   UTF-8, a character XML doesn't allow) is unreadable rather than read with replacement characters;
 * - start tags with attributes (`"` or `'` quoted), end tags, empty-element tags, text, and CDATA
 *   (taken as text); comments and processing instructions (`<?xpacket …?>`, `<?xml …?>`) are skipped
 *   and not kept. A DOCTYPE or any other `<!…` declaration is unreadable;
 * - only the entities `&lt; &gt; &amp; &quot; &apos;` and numeric ones naming a character XML allows.
 *
 * It never recurses. A packet is unreadable when its elements go more than [MAX_DEPTH] deep, it has
 * more than [MAX_NODES] elements and texts, an end tag doesn't match, text or an element follows the
 * root (white space, comments and processing instructions may), an element or attribute uses a prefix
 * no xmlns attribute declares, an element has the same attribute twice, or the input ends inside
 * anything.
 */
internal object XmlTree {
    const val XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace"
    const val MAX_DEPTH = 64
    const val MAX_NODES = 200_000

    /** The longest entity read between "&" and ";" ("#x10FFFF" with a few leading zeros). */
    private const val MAX_REFERENCE = 12

    sealed class Node

    /** Text as it is, entities decoded, white space included, so the tree is laid out as the input was. */
    class Text(var value: String) : Node()

    /**
     * An element: [name] as written, with its prefix; [attributes] in order, xmlns ones included,
     * values decoded. [parent] is null for the root.
     */
    class Element(
        val name: String,
        val attributes: MutableList<Pair<String, String>> = mutableListOf(),
        val children: MutableList<Node> = mutableListOf(),
    ) : Node() {
        var parent: Element? = null

        val localName: String get() = localName(name)

        /** The prefixes this element's own xmlns attributes declare ("" for the default namespace). */
        private var declared: Map<String, String>? = null

        /**
         * The namespace [prefix] ("" for an unprefixed element name) stands for here, looked up in the
         * xmlns attributes of this element and its ancestors; null when none declares it. "xml" is
         * always the XML namespace. Each element's declarations are gathered once, when first asked.
         */
        fun namespace(prefix: String): String? {
            if (prefix == "xml") return XML_NAMESPACE
            var element: Element? = this
            while (element != null) {
                element.declaredPrefixes()[prefix]?.let { return it.ifEmpty { null } }
                element = element.parent
            }
            return null
        }

        private fun declaredPrefixes(): Map<String, String> = declared ?: declarations().also { declared = it }

        private fun declarations(): Map<String, String> {
            var map: HashMap<String, String>? = null
            for ((name, value) in attributes) {
                val prefix = when {
                    name == "xmlns" -> ""
                    name.startsWith("xmlns:") -> name.substring(6)
                    else -> continue
                }
                if (map == null) map = HashMap()
                map[prefix] = value
            }
            return map ?: emptyMap()
        }

        /** The namespace of this element's name, or null when it has none. */
        fun uri(): String? = namespace(prefixOf(name) ?: "")

        /** The namespace of this element's attribute [name]: null when unprefixed (attributes then have none) or xmlns. */
        fun attributeUri(name: String): String? {
            val prefix = prefixOf(name) ?: return null
            return if (prefix == "xmlns") null else namespace(prefix)
        }

        /** The text directly inside this element (not inside its child elements). */
        fun text(): String {
            var only: String? = null
            var joined: StringBuilder? = null
            for (child in children) {
                if (child !is Text) continue
                when {
                    joined != null -> joined.append(child.value)
                    only == null -> only = child.value
                    else -> joined = StringBuilder(only).append(child.value)
                }
            }
            return joined?.toString() ?: only ?: ""
        }
    }

    fun prefixOf(name: String): String? = name.indexOf(':').let { if (it < 0) null else name.substring(0, it) }

    fun localName(name: String): String = name.substring(name.indexOf(':') + 1)

    /** The root element of [bytes], or null when they are not XML this reader reads (see the class). */
    fun parse(bytes: ByteArray): Element? {
        val text = decode(bytes) ?: return null
        return try {
            Parser(text).document()
        } catch (_: Unreadable) {
            null
        }
    }

    /**
     * The first element, in document order, of [root] or below it at most [maxDepth] levels down that
     * passes [test], or null.
     */
    fun find(root: Element, maxDepth: Int, test: (Element) -> Boolean): Element? {
        val stack = ArrayDeque<Pair<Element, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            val (element, depth) = stack.removeLast()
            if (test(element)) return element
            if (depth < maxDepth) {
                for (child in element.children.asReversed()) if (child is Element) stack.addLast(child to depth + 1)
            }
        }
        return null
    }

    /** [bytes] as text by the encodings the class names, or null. */
    private fun decode(bytes: ByteArray): String? {
        fun at(index: Int) = if (index < bytes.size) bytes[index].toInt() and 0xFF else -1
        val (charset: Charset, skip) = when {
            // UTF-32: big-endian starts 00 00 (its mark or a "<"), little-endian FF FE 00 00 or 3C 00 00 00.
            at(0) == 0x00 && at(1) == 0x00 -> return null
            at(0) == 0xFF && at(1) == 0xFE && at(2) == 0x00 && at(3) == 0x00 -> return null
            at(0) == 0x3C && at(1) == 0x00 && at(2) == 0x00 && at(3) == 0x00 -> return null
            at(0) == 0xFE && at(1) == 0xFF -> Charsets.UTF_16BE to 2
            at(0) == 0xFF && at(1) == 0xFE -> Charsets.UTF_16LE to 2
            at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> Charsets.UTF_8 to 3
            at(0) == 0x00 && at(1) == 0x3C -> Charsets.UTF_16BE to 0
            at(0) == 0x3C && at(1) == 0x00 -> Charsets.UTF_16LE to 0
            else -> Charsets.UTF_8 to 0
        }
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /** Why a packet can't be read; caught in [parse]. */
    private class Unreadable : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class Parser(private val s: String) {
        private var i = 0
        private var nodes = 0
        private val open = ArrayList<Element>()
        private val text = StringBuilder()
        private var hasText = false

        fun document(): Element {
            checkCharacters()
            var root: Element? = null
            while (i < s.length) {
                if (s[i] != '<') {
                    if (open.isEmpty()) {
                        // Outside the root only white space may stand between the markup.
                        if (!isSpace(s[i])) fail()
                        i++
                    } else {
                        var end = s.indexOf('<', i)
                        if (end < 0) end = s.length
                        decode(end, text, attribute = false)
                        hasText = true
                    }
                    continue
                }
                when {
                    s.startsWith("<?", i) -> i = after("?>", i + 2)
                    s.startsWith("<!--", i) -> i = after("-->", i + 4)
                    s.startsWith("<![CDATA[", i) -> {
                        if (open.isEmpty()) fail()
                        val end = s.indexOf("]]>", i + 9)
                        if (end < 0) fail()
                        text.append(s, i + 9, end)
                        hasText = true
                        i = end + 3
                    }
                    s.startsWith("<!", i) -> fail()
                    s.startsWith("</", i) -> {
                        flushText()
                        endTag()
                    }
                    else -> {
                        if (root != null && open.isEmpty()) fail()
                        flushText()
                        val element = startTag()
                        if (root == null) root = element
                    }
                }
            }
            if (root == null || open.isNotEmpty()) fail()
            return root
        }

        /** Every character must be one XML allows; the strict decoder has already refused lone surrogates. */
        private fun checkCharacters() {
            for (c in s) {
                if (c < ' ' && c != '\t' && c != '\n' && c != '\r') fail()
                if (c == '\uFFFE' || c == '\uFFFF') fail()
            }
        }

        /** The index after the next [marker] from [from]. */
        private fun after(marker: String, from: Int): Int {
            val end = s.indexOf(marker, from)
            if (end < 0) fail()
            return end + marker.length
        }

        private fun flushText() {
            if (!hasText) return
            node()
            open.last().children.add(Text(text.toString()))
            text.setLength(0)
            hasText = false
        }

        private fun node() {
            if (++nodes > MAX_NODES) fail()
        }

        private fun startTag(): Element {
            i++
            val element = Element(readName())
            val names = HashSet<String>()
            var empty = false
            while (true) {
                val spaced = skipSpace()
                if (i >= s.length) fail()
                val c = s[i]
                if (c == '>') {
                    i++
                    break
                }
                if (c == '/') {
                    if (i + 1 >= s.length || s[i + 1] != '>') fail()
                    i += 2
                    empty = true
                    break
                }
                if (!spaced) fail()
                val name = readName()
                skipSpace()
                if (i >= s.length || s[i] != '=') fail()
                i++
                skipSpace()
                if (i >= s.length) fail()
                val quote = s[i]
                if (quote != '"' && quote != '\'') fail()
                i++
                val end = s.indexOf(quote, i)
                if (end < 0) fail()
                val value = StringBuilder()
                decode(end, value, attribute = true)
                i = end + 1
                if (!names.add(name)) fail()
                element.attributes.add(name to value.toString())
            }
            if (open.size >= MAX_DEPTH) fail()
            node()
            val parent = open.lastOrNull()
            element.parent = parent
            parent?.children?.add(element)
            checkNamespaces(element)
            if (!empty) open.add(element)
            return element
        }

        private fun checkNamespaces(element: Element) {
            prefixOf(element.name)?.let { if (it == "xmlns" || element.namespace(it) == null) fail() }
            for ((name, value) in element.attributes) {
                when {
                    name == "xmlns" -> Unit
                    name.startsWith("xmlns:") -> if (value.isEmpty() || name == "xmlns:xmlns") fail()
                    else -> prefixOf(name)?.let { if (element.namespace(it) == null) fail() }
                }
            }
        }

        private fun endTag() {
            i += 2
            val name = readName()
            skipSpace()
            if (i >= s.length || s[i] != '>') fail()
            i++
            if (open.isEmpty() || open.last().name != name) fail()
            open.removeAt(open.size - 1)
        }

        /** A name, at most one colon in it and not at either end. */
        private fun readName(): String {
            val start = i
            if (i >= s.length || !isNameStart(s[i])) fail()
            i++
            while (i < s.length && isNameChar(s[i])) i++
            val name = s.substring(start, i)
            val colon = name.indexOf(':')
            if (colon >= 0 && (colon == 0 || colon == name.length - 1 || name.indexOf(':', colon + 1) >= 0)) fail()
            return name
        }

        private fun skipSpace(): Boolean {
            val start = i
            while (i < s.length && isSpace(s[i])) i++
            return i > start
        }

        /**
         * Text or an attribute's value from [i] up to [end] into [out], entities decoded. In an attribute
         * value "<" is not allowed and a tab or line break written as itself is a space, as XML has it
         * (one written as an entity stays what it is).
         */
        private fun decode(end: Int, out: StringBuilder, attribute: Boolean) {
            while (i < end) {
                val c = s[i]
                when {
                    c == '&' -> reference(end, out)
                    !attribute -> {
                        out.append(c)
                        i++
                    }
                    c == '<' -> fail()
                    c == '\r' -> {
                        out.append(' ')
                        i++
                        if (i < end && s[i] == '\n') i++
                    }
                    c == '\t' || c == '\n' -> {
                        out.append(' ')
                        i++
                    }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
        }

        private fun reference(end: Int, out: StringBuilder) {
            val limit = minOf(end, i + 2 + MAX_REFERENCE)
            var semicolon = i + 1
            while (semicolon < limit && s[semicolon] != ';') semicolon++
            if (semicolon >= limit) fail()
            when (val body = s.substring(i + 1, semicolon)) {
                "lt" -> out.append('<')
                "gt" -> out.append('>')
                "amp" -> out.append('&')
                "quot" -> out.append('"')
                "apos" -> out.append('\'')
                else -> {
                    val hex = body.startsWith("#x")
                    val digits = when {
                        hex -> body.substring(2)
                        body.startsWith("#") -> body.substring(1)
                        else -> fail()
                    }
                    if (digits.isEmpty() || !digits.all { if (hex) it.isHexDigit() else it in '0'..'9' }) fail()
                    val code = digits.toIntOrNull(if (hex) 16 else 10) ?: fail()
                    if (!isXmlCharacter(code)) fail()
                    out.appendCodePoint(code)
                }
            }
            i = semicolon + 1
        }

        private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        private fun fail(): Nothing = throw Unreadable()
    }

    private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    private fun isXmlCharacter(code: Int) =
        code == 0x9 || code == 0xA || code == 0xD || code in 0x20..0xD7FF || code in 0xE000..0xFFFD ||
            code in 0x10000..0x10FFFF

    /** XML's NameStartChar; a surrogate stands for the characters above U+FFFF, which all are one. */
    private fun isNameStart(c: Char) =
        c in 'a'..'z' || c in 'A'..'Z' || c == '_' || c == ':' ||
            c in '\u00C0'..'\u00D6' || c in '\u00D8'..'\u00F6' || c in '\u00F8'..'\u02FF' ||
            c in '\u0370'..'\u037D' || c in '\u037F'..'\u1FFF' || c in '\u200C'..'\u200D' ||
            c in '\u2070'..'\u218F' || c in '\u2C00'..'\u2FEF' || c in '\u3001'..'\uD7FF' ||
            c in '\uD800'..'\uDFFF' || c in '\uF900'..'\uFDCF' || c in '\uFDF0'..'\uFFFD'

    private fun isNameChar(c: Char) =
        isNameStart(c) || c == '-' || c == '.' || c in '0'..'9' || c == '\u00B7' ||
            c in '\u0300'..'\u036F' || c in '\u203F'..'\u2040'
}
