package io.github.nath_goa.usugami.data.office

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * An Office Open XML file (.docx, .pptx): a zip of XML parts, linked by relationships. Only read,
 * never extracted, so odd names inside the zip can't reach the file system.
 */
internal class OfficePackage(file: File) : Closeable {
    private val zip = ZipFile(file)
    // Part names are case-insensitive in OOXML, but zip entry names aren't.
    private val entries: Map<String, ZipEntry> = zip.entries().asSequence().associateBy { it.name.trimStart('/').lowercase() }
    private val relationships = HashMap<String, Map<String, Relationship>>()

    fun has(path: String) = entries.containsKey(path.lowercase())

    fun open(path: String): InputStream? = entries[path.lowercase()]?.let(zip::getInputStream)

    /** The XML of part [path], positioned at its root element, or null if there's no such part. */
    fun xml(path: String, check: () -> Unit): OfficeXml? {
        val stream = open(path) ?: return null
        return try {
            OfficeXml.open(stream, check)
        } catch (e: Exception) {
            stream.close()
            throw e
        }
    }

    /** The relationships of [part] (such as "word/document.xml"), by id. */
    fun relationships(part: String): Map<String, Relationship> = relationships.getOrPut(part.lowercase()) {
        val dir = part.substringBeforeLast('/', "")
        val relsPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + part.substringAfterLast('/') + ".rels"
        val found = HashMap<String, Relationship>()
        xml(relsPath) {}?.use { x ->
            x.children {
                if (x.name == "Relationship") {
                    val id = x.attr("Id")
                    val target = x.attr("Target")
                    if (id != null && target != null) {
                        val external = x.attr("TargetMode") == "External"
                        found[id] = Relationship(x.attr("Type").orEmpty(), if (external) target else resolve(dir, target), external)
                    }
                }
            }
        }
        found
    }

    /** The part relationship [id] of [part] points to, if it is inside the package. */
    fun target(part: String, id: String?): String? = id?.let { relationships(part)[it] }?.takeUnless { it.external }?.target

    /** The first part [part] links to with a relationship type ending in [typeSuffix], such as "/slideLayout". */
    fun targetOfType(part: String, typeSuffix: String): String? =
        relationships(part).values.firstOrNull { !it.external && it.type.endsWith(typeSuffix) }?.target

    /** The document's main part ("word/document.xml", "ppt/presentation.xml"), from the package relationships. */
    fun mainPart(): String? = relationships("").values.firstOrNull { !it.external && it.type.endsWith("/officeDocument") }?.target

    override fun close() = zip.close()

    private fun resolve(dir: String, target: String): String {
        val segments = ArrayList<String>()
        if (!target.startsWith("/")) segments += dir.split('/').filter { it.isNotEmpty() }
        for (segment in target.split('/')) when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
            else -> segments += segment
        }
        return segments.joinToString("/")
    }
}

internal class Relationship(val type: String, val target: String, val external: Boolean)

/** Which vocabulary an element comes from. Element names repeat between them (w:p, a:p, p:sp...). */
internal enum class Ns { WORD, DRAWING, SLIDE, WORD_DRAWING, PICTURE, COMPATIBILITY, MATH, VML, OTHER }

/**
 * A streaming reader for one XML part. Namespaces are resolved, so elements are matched by their
 * local [name] and [ns], whatever prefix the file uses; both the transitional and the strict
 * namespaces are understood.
 */
internal class OfficeXml private constructor(private val parser: XmlPullParser, private val input: InputStream, private val check: () -> Unit) : Closeable {
    private var events = 0

    val name: String get() = parser.name
    val ns: Ns get() = namespaceOf(parser.namespace)
    val depth: Int get() = parser.depth

    /** An attribute's value by its local name, whatever its prefix. */
    fun attr(local: String): String? {
        for (i in 0 until parser.attributeCount) if (parser.getAttributeName(i) == local) return parser.getAttributeValue(i)
        return null
    }

    /** A relationship id attribute (r:id, r:embed...): only one in the relationships namespace counts. */
    fun relId(local: String = "id"): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == local && parser.getAttributeNamespace(i).orEmpty().contains("relationships")) return parser.getAttributeValue(i)
        }
        return null
    }

    fun intAttr(local: String): Int? = attr(local)?.trim()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
    fun longAttr(local: String): Long? = attr(local)?.trim()?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

    /**
     * An on/off property such as w:b: on unless its value says otherwise. Missing from the element
     * altogether means [missing].
     */
    fun flag(local: String = "val", missing: Boolean = true): Boolean = when (attr(local)?.trim()?.lowercase()) {
        null -> missing
        "0", "false", "off", "none" -> false
        else -> true
    }

    /** Calls [block] at each child element of the current one, then leaves the reader at its end. */
    fun children(block: () -> Unit) {
        val parent = parser.depth
        while (true) {
            when (next()) {
                XmlPullParser.END_DOCUMENT -> return
                XmlPullParser.END_TAG -> if (parser.depth == parent) return
                XmlPullParser.START_TAG -> if (parser.depth == parent + 1) {
                    block()
                    // Children the block left unread are skipped.
                    if (parser.eventType == XmlPullParser.START_TAG && parser.depth == parent + 1) skip()
                }
            }
        }
    }

    /** Skips the current element, with everything in it. */
    fun skip() {
        var open = 1
        while (open > 0) {
            when (next()) {
                XmlPullParser.START_TAG -> open++
                XmlPullParser.END_TAG -> open--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** The text directly inside the current element (such as w:t), leaving the reader at its end. */
    fun text(): String {
        val out = StringBuilder()
        var open = 1
        while (open > 0) {
            when (next()) {
                XmlPullParser.TEXT -> if (open == 1) out.append(parser.text)
                XmlPullParser.START_TAG -> open++
                XmlPullParser.END_TAG -> open--
                XmlPullParser.END_DOCUMENT -> open = 0
            }
        }
        return out.toString()
    }

    private fun next(): Int {
        // Checked now and then: a large part can take a while to read.
        if (++events % 512 == 0) check()
        return parser.next()
    }

    override fun close() = input.close()

    companion object {
        fun open(input: InputStream, check: () -> Unit): OfficeXml {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(input, null)
            // Office never writes a DOCTYPE. One could declare entities that expand without end, so
            // the file is refused before anything in it is read.
            while (true) {
                when (parser.nextToken()) {
                    XmlPullParser.DOCDECL -> throw IOException("Unexpected DOCTYPE in an Office part")
                    XmlPullParser.START_TAG -> return OfficeXml(parser, input, check)
                    XmlPullParser.END_DOCUMENT -> throw IOException("Empty Office part")
                }
            }
        }

        private val known = java.util.concurrent.ConcurrentHashMap<String, Ns>()

        fun namespaceOf(uri: String?): Ns {
            if (uri.isNullOrEmpty()) return Ns.OTHER
            return known.getOrPut(uri) {
                when {
                    uri.endsWith("/wordprocessingml/2006/main") || uri.endsWith("/ooxml/wordprocessingml/main") -> Ns.WORD
                    uri.endsWith("/drawingml/2006/main") || uri.endsWith("/ooxml/drawingml/main") -> Ns.DRAWING
                    uri.endsWith("/presentationml/2006/main") || uri.endsWith("/ooxml/presentationml/main") -> Ns.SLIDE
                    uri.endsWith("wordprocessingDrawing") -> Ns.WORD_DRAWING
                    uri.endsWith("/drawingml/2006/picture") || uri.endsWith("/ooxml/drawingml/picture") -> Ns.PICTURE
                    uri.endsWith("/markup-compatibility/2006") -> Ns.COMPATIBILITY
                    uri.endsWith("/officeDocument/2006/math") || uri.endsWith("/ooxml/officeDocument/math") -> Ns.MATH
                    uri == "urn:schemas-microsoft-com:vml" -> Ns.VML
                    else -> Ns.OTHER
                }
            }
        }
    }
}
