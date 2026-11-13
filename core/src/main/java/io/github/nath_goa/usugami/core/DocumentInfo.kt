package io.github.nath_goa.usugami.core

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The four entries of a PDF's info that Document info lets the user change. */
enum class InfoField { TITLE, AUTHOR, SUBJECT, KEYWORDS }

/** What to change: a field missing from [texts] stays as it is, "" removes it. */
data class InfoChange(val texts: Map<InfoField, String> = emptyMap(), val removeAll: Boolean = false)

/** The text of a PDF's info entries: how it is shown, cut, read from a PDF string and written to one. */
object InfoText {
    /** Characters a field shows and takes. */
    const val MAX_FIELD = 2000

    /** Characters of an app's name shown in "Made with". */
    const val MAX_SHOWN_APP = 200

    /** Characters shown of a date that can't be read as one. */
    const val MAX_SHOWN_RAW_DATE = 100

    private const val ESCAPE = '\u001B'

    /**
     * For showing and for saving typed text: language escapes and U+0000 dropped, every run of line
     * breaks, tabs and other control characters (Cc) turned into one space, trimmed.
     *
     * A language escape is what a PDF text string puts before text in another language: U+001B, a
     * two-letter language code, perhaps a two-letter country code, and U+001B again. Each code is two
     * bytes of ASCII, so in a UTF-16 string (FE FF, as ISO 32000 writes it) it is one character
     * whose two bytes are letters ("en" reads as U+656E), and in a UTF-8 string two letters. Only
     * that is dropped: an escape character with anything else after it is a control character like
     * the others, so two stray ones never take the text between them away.
     */
    fun clean(raw: String): String {
        val out = StringBuilder(raw.length)
        var spaced = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == ESCAPE) {
                val end = languageEscapeEnd(raw, i)
                if (end > 0) {
                    i = end
                    continue
                }
            }
            when {
                c == '\u0000' -> Unit
                c.isISOControl() || c == '\u2028' || c == '\u2029' -> {
                    if (!spaced) out.append(' ')
                    spaced = true
                }
                else -> {
                    out.append(c)
                    spaced = false
                }
            }
            i++
        }
        return out.toString().trim()
    }

    /**
     * Where the language escape starting at [start] ends (the index after its second U+001B), or -1:
     * 2 or 4 ASCII letters between the escapes (a UTF-8 or unmarked string), or 1 or 2 characters
     * each holding two ASCII letters in its two bytes (a UTF-16 string, either byte order).
     */
    private fun languageEscapeEnd(text: String, start: Int): Int =
        escapeEnd(text, start, 2, 4) { it.isAsciiLetter() }.takeIf { it > 0 }
            ?: escapeEnd(text, start, 1, 2) { it.isTwoAsciiLetters() }

    /** The index after a U+001B that follows [short] or [long] characters matching [isCode] after [start], or -1. */
    private inline fun escapeEnd(text: String, start: Int, short: Int, long: Int, isCode: (Char) -> Boolean): Int {
        var i = start + 1
        while (i < text.length && i - start - 1 < long && isCode(text[i])) i++
        val count = i - start - 1
        return if ((count == short || count == long) && i < text.length && text[i] == ESCAPE) i + 1 else -1
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isTwoAsciiLetters() =
        (code shr 8).toChar().isAsciiLetter() && (code and 0xFF).toChar().isAsciiLetter()

    /** [text] cut to [max] characters without splitting a surrogate pair. */
    fun cut(text: String, max: Int = MAX_FIELD): String {
        if (text.length <= max) return text
        val end = if (max > 0 && Character.isHighSurrogate(text[max - 1])) max - 1 else max
        return text.substring(0, end.coerceAtLeast(0))
    }

    /**
     * The text of a PDF string that starts with a Unicode byte order mark (FE FF UTF-16BE, FF FE
     * UTF-16LE, EF BB BF UTF-8), else null: the caller then lets PDFBox read it as PDFDocEncoding.
     */
    fun unicode(bytes: ByteArray): String? {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0xFE && at(1) == 0xFF -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            at(0) == 0xFF && at(1) == 0xFE -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            else -> null
        }
    }

    /**
     * A PDF text string's bytes: plain ASCII (0x20..0x7E only) as it is, anything else UTF-16BE after
     * FE FF. The same rule as [PdfSyntax.textString].
     */
    fun bytes(text: String): ByteArray {
        if (text.all { it in ' '..'~' }) return text.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
    }

    /** Keywords as a list for XMP's dc:subject: split at commas and semicolons, trimmed, empty ones and repeats dropped. */
    fun keywordList(keywords: String): List<String> =
        keywords.split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}

/** The dates of a PDF's info and of its XMP, read as moments. */
object PdfDates {
    /** Longer than any date either form can write. */
    private const val MAX_LENGTH = 64

    // D:YYYYMMDDHHmmSS, every part after the year optional but only after the one before it, then, after
    // the hour, Z (perhaps followed by 00'00', which some apps write) or an offset with or without
    // apostrophes. [0-9], not \d: on Android \d also matches other scripts' digits.
    private val PDF = Regex(
        "(?:D:)?([0-9]{4})(?:([0-9]{2})(?:([0-9]{2})(?:([0-9]{2})(?:([0-9]{2})(?:([0-9]{2}))?)?" +
            "(?:(Z)(?:[0-9]{2}'?(?:[0-9]{2}'?)?)?|([+-])([0-9]{2})(?:'?([0-9]{2}))?'?)?)?)?)?",
    )

    // YYYY, YYYY-MM, YYYY-MM-DD, then Thh:mm[:ss[.fraction]] and Z or +hh:mm (also +hhmm or +hh).
    private val ISO = Regex(
        "([0-9]{4})(?:-([0-9]{2})(?:-([0-9]{2})(?:T([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:\\.([0-9]+))?)?" +
            "(?:(Z)|([+-])([0-9]{2})(?::?([0-9]{2}))?)?)?)?)?",
    )

    /**
     * The moment a PDF date (D:YYYYMMDDHHmmSSOHH'mm', every part after the year optional, "D:"
     * optional, Z or an offset with or without apostrophes) or an XMP date (ISO 8601: YYYY, YYYY-MM,
     * YYYY-MM-DD, then Thh:mm[:ss[.fraction]] and Z or +hh:mm) names, in epoch milliseconds; null when
     * [text] is neither or names no real date (month 13, day 32, hour 25, year outside 1..9999). One
     * without an offset is taken in [zone]. Only the whole text counts: a date with anything after it
     * is not read, so the form shows it as the PDF has it rather than a date it may not mean.
     */
    fun parse(text: String, zone: ZoneId): Long? {
        val s = text.trim()
        if (s.isEmpty() || s.length > MAX_LENGTH) return null
        val iso = !s.startsWith("D:") && s.length > 4 && s[4] == '-'
        val match = (if (iso) ISO else PDF).matchEntire(s) ?: return null
        val g = match.groupValues
        fun part(index: Int, default: Int) = g[index].ifEmpty { null }?.toInt() ?: default
        val nanos = if (iso && g[7].isNotEmpty()) g[7].take(9).padEnd(9, '0').toInt() else 0
        val utc = if (iso) g[8] else g[7]
        val sign = if (iso) g[9] else g[8]
        val offsetHours = if (iso) g[10] else g[9]
        val offsetMinutes = if (iso) g[11] else g[10]
        return try {
            val year = part(1, 0)
            if (year < 1) return null
            val local = LocalDateTime.of(
                year, part(2, 1), part(3, 1), part(4, 0), part(5, 0), part(6, 0), nanos,
            )
            val moment = when {
                utc.isNotEmpty() -> local.atOffset(ZoneOffset.UTC)
                sign.isNotEmpty() -> {
                    val hours = offsetHours.toInt()
                    val minutes = offsetMinutes.ifEmpty { "0" }.toInt()
                    if (hours > 23 || minutes > 59) return null
                    val offset = if (sign == "-") {
                        ZoneOffset.ofHoursMinutes(-hours, -minutes)
                    } else {
                        ZoneOffset.ofHoursMinutes(hours, minutes)
                    }
                    local.atOffset(offset)
                }
                else -> null
            }
            moment?.toInstant()?.toEpochMilli() ?: local.atZone(zone).toInstant().toEpochMilli()
        } catch (_: DateTimeException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }
}

/** What an XMP packet says of the document; null where it says nothing. */
data class XmpInfo(
    val title: String?,
    val author: String?,
    val subject: String?,
    val keywords: String?,
    val creatorTool: String?,
    val producer: String?,
    val created: String?,
    val modified: String?,
)

/**
 * A PDF's XMP packet (the catalog's /Metadata), read with Usugami's own strict XML reader
 * ([XmlTree]): no platform parser, so no DOCTYPE or entity can do anything, and it reads the same on
 * the desktop JVM and on Android. A field changed is patched into the packet ([withTexts]), which is
 * otherwise written back as it was read.
 */
object XmpPacket {
    /** A packet longer than this counts as unreadable. */
    const val MAX_BYTES = 2 * 1024 * 1024

    private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val DC = "http://purl.org/dc/elements/1.1/"
    private const val PDF = "http://ns.adobe.com/pdf/1.3/"
    private const val XMP = "http://ns.adobe.com/xap/1.0/"

    /** How far below the root rdf:RDF is looked for. */
    private const val RDF_DEPTH = 3

    /**
     * What [packet] says, or null when it can't be read: over [MAX_BYTES], not UTF-8 or UTF-16, not
     * the strict XML [XmlTree] reads, or without an rdf:RDF element. Each value is cleaned
     * ([InfoText.clean]) but not cut; an empty one counts as absent, so a later description's
     * non-empty value of the same property is taken instead.
     */
    fun read(packet: ByteArray): XmpInfo? {
        val rdf = rdfOf(packet) ?: return null
        val found = HashMap<String, String>()
        for (description in rdf.children) {
            if (description !is XmlTree.Element) continue
            for ((name, value) in description.attributes) {
                val key = description.attributeKey(name) ?: continue
                if (key in PROPERTIES) found.putIfNotBlank(key, InfoText.clean(value))
            }
            for (property in description.children) {
                if (property !is XmlTree.Element) continue
                val key = property.key() ?: continue
                if (key !in PROPERTIES) continue
                found.putIfNotBlank(key, valueOf(property))
            }
        }
        return XmpInfo(
            title = found[DC + "title"],
            author = found[DC + "creator"],
            subject = found[DC + "description"],
            keywords = found[PDF + "Keywords"] ?: found[DC + "subject"],
            creatorTool = found[XMP + "CreatorTool"],
            producer = found[PDF + "Producer"],
            created = found[XMP + "CreateDate"],
            modified = found[XMP + "ModifyDate"],
        )
    }

    /**
     * [packet] with the fields in [texts] changed (an empty text: removed) and everything else as it
     * was, as UTF-8 between xpacket markers; null when the packet can't be read or has no rdf:RDF
     * element (see [read]).
     *
     * Each field's properties go from every description in rdf:RDF, written as attributes or as
     * elements: the title's dc:title, the author's dc:creator, the subject's dc:description, the
     * keywords' pdf:Keywords and dc:subject. The fields whose text isn't empty then come back in one
     * rdf:Description added at the end, which declares its own prefixes and has the rdf:about of the
     * packet's other descriptions (Adobe's reader refuses a packet whose descriptions differ there).
     * Fields not in [texts] are not touched. Characters XML can't carry (U+FFFE, U+FFFF, control
     * characters other than tab and line breaks, half a surrogate pair) are left out of the new
     * values: written, they would make the whole packet unreadable. Comments, processing instructions
     * and the packet's padding are not kept; everything else is written as it was read, white space
     * included.
     */
    fun withTexts(packet: ByteArray, texts: Map<InfoField, String>): ByteArray? {
        val rdf = rdfOf(packet) ?: return null
        val removed = texts.keys.flatMapTo(HashSet()) { propertiesOf(it) }
        val about = aboutOf(rdf)
        for (description in rdf.children) {
            if (description !is XmlTree.Element) continue
            // Every name resolved before the list changes.
            val attributes = description.attributes.filter { (name, _) -> description.attributeKey(name) !in removed }
            if (attributes.size != description.attributes.size) {
                description.attributes.clear()
                description.attributes.addAll(attributes)
            }
            removeProperties(description, removed)
        }
        newDescription(about, texts)?.let { append(rdf, it) }
        var root = rdf
        while (true) root = root.parent ?: break
        return write(root)
    }

    /** The properties that hold [field], as namespace + local name. */
    private fun propertiesOf(field: InfoField): List<String> = when (field) {
        InfoField.TITLE -> listOf(DC + "title")
        InfoField.AUTHOR -> listOf(DC + "creator")
        InfoField.SUBJECT -> listOf(DC + "description")
        InfoField.KEYWORDS -> listOf(PDF + "Keywords", DC + "subject")
    }

    /**
     * The rdf:about of the first description in [rdf] that has one, else the `about` without a prefix
     * of the first RDF element there that has one (older packets write it so, and Adobe's XMP toolkit
     * reads it as rdf:about), or "".
     */
    private fun aboutOf(rdf: XmlTree.Element): String {
        val descriptions = rdf.children.filterIsInstance<XmlTree.Element>()
        for (description in descriptions) {
            for ((name, value) in description.attributes) {
                if (description.attributeKey(name) == RDF + "about") return value
            }
        }
        for (description in descriptions) {
            if (description.uri() != RDF) continue
            for ((name, value) in description.attributes) {
                if (name == "about") return value
            }
        }
        return ""
    }

    /** Takes the property elements named in [removed] out of [description], each with the white space before it. */
    private fun removeProperties(description: XmlTree.Element, removed: Set<String>) {
        val children = description.children
        if (children.none { it is XmlTree.Element && it.key() in removed }) return
        val kept = ArrayList<XmlTree.Node>(children.size)
        for (child in children) {
            if (child is XmlTree.Element && child.key() in removed) {
                if (kept.lastOrNull().isSpace()) kept.removeAt(kept.size - 1)
                continue
            }
            kept += child
        }
        children.clear()
        children.addAll(kept)
    }

    /** Whether this is text of nothing but XML's white space. */
    private fun XmlTree.Node?.isSpace() =
        this is XmlTree.Text && value.all { it == ' ' || it == '\t' || it == '\n' || it == '\r' }

    /**
     * The rdf:Description holding the fields of [texts] whose text isn't empty, a property a line, or
     * null when there are none.
     */
    private fun newDescription(about: String, texts: Map<InfoField, String>): XmlTree.Element? {
        val values = texts.mapValues { (_, text) -> xmlText(text) }.filterValues { it.isNotEmpty() }
        if (values.isEmpty()) return null
        val description = element(
            "rdf:Description",
            "rdf:about" to about, "xmlns:rdf" to RDF, "xmlns:dc" to DC, "xmlns:pdf" to PDF,
        )
        fun line(property: XmlTree.Element) {
            description.add(XmlTree.Text("\n"))
            description.add(property)
        }
        values[InfoField.TITLE]?.let { line(element("dc:title").add(alt(it))) }
        values[InfoField.AUTHOR]?.let { line(element("dc:creator").add(items("rdf:Seq", listOf(it)))) }
        values[InfoField.SUBJECT]?.let { line(element("dc:description").add(alt(it))) }
        values[InfoField.KEYWORDS]?.let { keywords ->
            val list = InfoText.keywordList(keywords)
            if (list.isNotEmpty()) line(element("dc:subject").add(items("rdf:Bag", list)))
            line(element("pdf:Keywords").add(XmlTree.Text(keywords)))
        }
        description.add(XmlTree.Text("\n"))
        return description
    }

    /** An rdf:Alt holding [text] as its x-default item. */
    private fun alt(text: String) =
        element("rdf:Alt").add(element("rdf:li", "xml:lang" to "x-default").add(XmlTree.Text(text)))

    /** An rdf:Seq or rdf:Bag ([name]) holding [texts], an item each. */
    private fun items(name: String, texts: List<String>) =
        element(name).apply { for (text in texts) add(element("rdf:li").add(XmlTree.Text(text))) }

    private fun element(name: String, vararg attributes: Pair<String, String>) =
        XmlTree.Element(name, attributes.toMutableList())

    /** Adds [child] to this element's children, as its parent, and returns this element. */
    private fun XmlTree.Element.add(child: XmlTree.Node): XmlTree.Element {
        if (child is XmlTree.Element) child.parent = this
        children.add(child)
        return this
    }

    /**
     * Adds [description] at the end of [rdf], before the line break that ends it and indented as the
     * first description is, when the packet is laid out in lines.
     */
    private fun append(rdf: XmlTree.Element, description: XmlTree.Element) {
        description.parent = rdf
        val children = rdf.children
        val first = children.indexOfFirst { it is XmlTree.Element }
        val indent = children.getOrNull(first - 1)?.takeIf { it.isSpace() } as XmlTree.Text?
        val at = if (children.lastOrNull().isSpace()) children.size - 1 else children.size
        children.add(at, description)
        if (indent != null) children.add(at, XmlTree.Text(indent.value))
    }

    /** [text] without the characters XML can't carry. */
    private fun xmlText(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val code = text.codePointAt(i)
            val allowed = code == 0x9 || code == 0xA || code == 0xD || code in 0x20..0xD7FF ||
                code in 0xE000..0xFFFD || code in 0x10000..0x10FFFF
            if (allowed) out.appendCodePoint(code)
            i += Character.charCount(code)
        }
        return out.toString()
    }

    /**
     * [root] written out as UTF-8 between xpacket markers, without padding: names and attributes as
     * kept; in text & < > escaped; in attribute values & < " escaped and tab, CR and LF written as
     * character references (written as themselves, they would be read back as spaces); an element
     * without children as <a/>. No recursion: the open elements are kept on a list.
     */
    private fun write(root: XmlTree.Element): ByteArray {
        val out = StringBuilder()
        out.append(PACKET_BEGIN).append('\n')
        val open = ArrayList<XmlTree.Element>()
        val next = ArrayList<Int>()
        fun start(element: XmlTree.Element) {
            out.append('<').append(element.name)
            for ((name, value) in element.attributes) {
                out.append(' ').append(name).append("=\"")
                escape(value, out, attribute = true)
                out.append('"')
            }
            if (element.children.isEmpty()) {
                out.append("/>")
            } else {
                out.append('>')
                open.add(element)
                next.add(0)
            }
        }
        start(root)
        while (open.isNotEmpty()) {
            val top = open.size - 1
            val element = open[top]
            val index = next[top]
            if (index == element.children.size) {
                out.append("</").append(element.name).append('>')
                open.removeAt(top)
                next.removeAt(top)
                continue
            }
            next[top] = index + 1
            when (val child = element.children[index]) {
                is XmlTree.Element -> start(child)
                is XmlTree.Text -> escape(child.value, out, attribute = false)
            }
        }
        out.append('\n').append(PACKET_END)
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    private fun escape(text: String, out: StringBuilder, attribute: Boolean) {
        for (c in text) {
            when {
                c == '&' -> out.append("&amp;")
                c == '<' -> out.append("&lt;")
                c == '>' && !attribute -> out.append("&gt;")
                c == '"' && attribute -> out.append("&quot;")
                c == '\t' && attribute -> out.append("&#x9;")
                c == '\r' && attribute -> out.append("&#xD;")
                c == '\n' && attribute -> out.append("&#xA;")
                else -> out.append(c)
            }
        }
    }

    private const val PACKET_BEGIN = "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
    private const val PACKET_END = "<?xpacket end=\"w\"?>"

    /** The properties [read] takes, as namespace + local name. */
    private val PROPERTIES = setOf(
        DC + "title", DC + "creator", DC + "description", DC + "subject",
        PDF + "Keywords", PDF + "Producer",
        XMP + "CreatorTool", XMP + "CreateDate", XMP + "ModifyDate",
    )

    private fun HashMap<String, String>.putIfNotBlank(key: String, value: String) {
        if (value.isNotEmpty() && key !in this) this[key] = value
    }

    /**
     * A property element's value, cleaned: the items of the rdf:Alt, rdf:Seq or rdf:Bag it holds (an
     * Alt's x-default one, else its first; all of a Seq's or Bag's, joined with ", "), else its own text.
     */
    private fun valueOf(property: XmlTree.Element): String {
        val container = property.children.firstOrNull {
            it is XmlTree.Element && it.uri() == RDF && it.localName in CONTAINERS
        } as XmlTree.Element? ?: return InfoText.clean(property.text())
        val items = container.children.filterIsInstance<XmlTree.Element>()
            .filter { it.uri() == RDF && it.localName == "li" }
        if (container.localName == "Alt") {
            val chosen = items.firstOrNull { item ->
                item.attributes.any { (name, value) ->
                    item.attributeKey(name) == XmlTree.XML_NAMESPACE + "lang" && value.equals("x-default", ignoreCase = true)
                }
            } ?: items.firstOrNull() ?: return ""
            return InfoText.clean(chosen.text())
        }
        return items.map { InfoText.clean(it.text()) }.filter { it.isNotEmpty() }.joinToString(", ")
    }

    private val CONTAINERS = setOf("Alt", "Seq", "Bag")

    /** [packet]'s first rdf:RDF element (the root or below it, at most three levels down), or null. */
    private fun rdfOf(packet: ByteArray): XmlTree.Element? {
        if (packet.size > MAX_BYTES) return null
        val root = XmlTree.parse(packet) ?: return null
        return XmlTree.find(root, RDF_DEPTH) { it.uri() == RDF && it.localName == "RDF" }
    }

    /** An element's namespace + local name, or null when its prefix has no namespace. */
    private fun XmlTree.Element.key(): String? = uri()?.let { it + localName }

    /** An attribute's namespace + local name, or null for an unprefixed or xmlns one. */
    private fun XmlTree.Element.attributeKey(name: String): String? =
        attributeUri(name)?.let { it + XmlTree.localName(name) }
}
