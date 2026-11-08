package io.github.nath_goa.usugami.data.office

import android.os.Build
import android.util.Xml
import dalvik.system.ZipPathValidator
import io.github.nath_goa.usugami.core.OfficeUnits
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * [file] opened as a zip. Since Android 14, apps targeting it can't open a zip with an entry whose
 * name starts with "/" (or has a ".." in it): some tools write names like "/word/document.xml", and
 * other readers open those files. Usugami's zips are only read, never extracted, so names starting
 * with "/" are let through; ".." still isn't, for any code that does extract.
 */
internal fun openZip(file: File): ZipFile {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) rootedNamesAllowed
    return ZipFile(file)
}

private val rootedNamesAllowed: Unit by lazy {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ZipPathValidator.setCallback(
            object : ZipPathValidator.Callback {
                override fun onZipEntryAccess(path: String) {
                    if (path.split('/').any { it == ".." }) throw ZipException("Invalid zip entry path: $path")
                }
            },
        )
    }
}

/**
 * An Office Open XML file (.docx, .pptx, .xlsx): a zip of XML parts, linked by relationships. Only read,
 * never extracted, so odd names inside the zip can't reach the file system.
 */
internal class OfficePackage(file: File) : Closeable {
    private val zip = openZip(file)
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

    /** The document's main part ("word/document.xml", "ppt/presentation.xml", "xl/workbook.xml"), from the package relationships. */
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

/**
 * Runs [block] on a thread of its own with a stack of [READING_STACK] bytes, and returns what it
 * returns or throws what it throws. Reading a Word or PowerPoint file goes a few calls deeper for each
 * level its elements nest, up to [OfficeXml.MAX_DEPTH] levels. That is more than the 1 MB stack of a
 * worker thread holds where calls take the most room, as in a debuggable app on Android 15 (about
 * 6 KB a level, so under 200 levels).
 */
internal fun <T> withReadingStack(block: () -> T): T {
    var result: Result<T>? = null
    val thread = Thread(null, { result = runCatching(block) }, "office-reader", READING_STACK)
    thread.start()
    // Waits for the reading to end even if interrupted, so that nothing is still writing the
    // work files when the caller clears them.
    var interrupted = false
    while (true) {
        try {
            thread.join()
            break
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
    return result!!.getOrThrow()
}

/** Room for [OfficeXml.MAX_DEPTH] levels several times over, as much as an app's main thread has. */
private const val READING_STACK = 8L * 1024 * 1024

/** Which vocabulary an element comes from. Element names repeat between them (w:p, a:p, p:sp...). */
internal enum class Ns { WORD, DRAWING, SLIDE, WORD_DRAWING, PICTURE, COMPATIBILITY, MATH, VML, SHEET, OTHER }

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

    /** A percentage attribute in thousandths of a percent, also as Strict Open XML writes it ("90%"): see [OfficeUnits.percentage]. */
    fun percentAttr(local: String): Int? = OfficeUnits.percentage(attr(local))

    /**
     * An on/off property such as w:b: on unless its value says otherwise. Missing from the element
     * altogether means [missing].
     */
    fun flag(local: String = "val", missing: Boolean = true): Boolean = when (attr(local)?.trim()?.lowercase()) {
        null -> missing
        "0", "false", "off", "none" -> false
        else -> true
    }

    /**
     * Calls [block] at each child element of the current one, then leaves the reader at its end.
     * Elements nested deeper than [MAX_DEPTH], which only a crafted file has, are skipped, so reading
     * them one level inside another can't run out of stack.
     */
    fun children(block: () -> Unit) {
        val parent = parser.depth
        if (parent >= MAX_DEPTH) return skip()
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

    /**
     * Calls [enter] at each element inside the current one, in order, going on into an element's own
     * children only when [enter] returns true for it, then leaves the reader at the current element's
     * end. Like [children] called one level inside another, but in a loop, so it uses no more stack
     * however deep it goes. Elements nested deeper than [MAX_DEPTH] are skipped, as [children] skips them.
     */
    fun descendants(enter: () -> Boolean) {
        val top = parser.depth
        while (true) {
            when (next()) {
                XmlPullParser.END_DOCUMENT -> return
                XmlPullParser.END_TAG -> if (parser.depth == top) return
                XmlPullParser.START_TAG -> {
                    val depth = parser.depth
                    // An element not gone into is skipped, unless [enter] has read it already.
                    if ((depth > MAX_DEPTH || !enter()) && parser.eventType == XmlPullParser.START_TAG && parser.depth == depth) skip()
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
        val event = parser.next()
        // The platform's parser takes longer over each level than over the one before, so a part nested
        // far deeper than [MAX_DEPTH], which only a crafted file is, is refused rather than skipped.
        if (event == XmlPullParser.START_TAG && parser.depth > MAX_NESTING) throw IOException("Office part nested too deeply")
        return event
    }

    override fun close() = input.close()

    companion object {
        /** Far deeper than Office nests anything: tables in text boxes in tables run to a few dozen levels. */
        const val MAX_DEPTH = 256

        /** How deep a part can nest at all, elements past [MAX_DEPTH] being skipped: the parser reads this deep in about a second. */
        private const val MAX_NESTING = 64 * MAX_DEPTH

        fun open(input: InputStream, check: () -> Unit): OfficeXml {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(TokenGuard(input), null)
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
                    uri.endsWith("/spreadsheetml/2006/main") || uri.endsWith("/ooxml/spreadsheetml/main") -> Ns.SHEET
                    else -> Ns.OTHER
                }
            }
        }
    }
}

/**
 * A part's bytes on their way to the XML parser, which keeps each tag and each run of text between
 * tags whole in memory, and takes longer for each attribute a tag has than for the one before. A tag
 * or text longer than [MAX_RUN] bytes, or a tag with more than [MAX_ATTRIBUTES] attributes, which only
 * a crafted file has, fails the part as damaged before the parser runs out of memory or time on it.
 * A part in UTF-16, which Office doesn't write, is let through as it is.
 */
private class TokenGuard(input: InputStream) : FilterInputStream(input) {
    private var sniffed = 0
    private var first = 0
    private var utf16 = false
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

    private fun see(byte: Int) {
        if (sniffed < 2) {
            // A byte order mark, or a < with a zero byte beside it.
            if (sniffed == 0) first = byte else utf16 = (first == 0xFE && byte == 0xFF) || (first == 0xFF && byte == 0xFE) || first == 0 || byte == 0
            sniffed++
        }
        if (utf16) return
        if (++run > MAX_RUN) throw IOException("Office part with a tag or text too long to read")
        if (!inTag) {
            if (byte == LT) {
                inTag = true
                afterOpening = true
                quote = 0
                attributes = 0
                run = 0
            } else if (byte == GT) {
                run = 0
            }
            return
        }
        if (afterOpening) {
            afterOpening = false
            special = byte == BANG || byte == QUESTION
        }
        when {
            quote != 0 -> if (byte == quote) quote = 0
            byte == GT -> {
                inTag = false
                run = 0
            }
            special -> Unit
            byte == DOUBLE_QUOTE || byte == SINGLE_QUOTE -> quote = byte
            byte == EQUALS -> if (++attributes > MAX_ATTRIBUTES) throw IOException("Office part with too many attributes in a tag")
        }
    }

    private companion object {
        /** Far longer than any text or tag Office writes: a cell holds 32,767 characters at most. */
        const val MAX_RUN = 8 * 1024 * 1024
        const val MAX_ATTRIBUTES = 4096
        const val LT = '<'.code
        const val GT = '>'.code
        const val BANG = '!'.code
        const val QUESTION = '?'.code
        const val DOUBLE_QUOTE = '"'.code
        const val SINGLE_QUOTE = '\''.code
        const val EQUALS = '='.code
    }
}
