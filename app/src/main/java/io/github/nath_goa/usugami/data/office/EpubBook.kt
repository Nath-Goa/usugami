package io.github.nath_goa.usugami.data.office

import android.util.Xml
import io.github.nath_goa.usugami.core.EpubPaths
import io.github.nath_goa.usugami.core.EpubProtection
import io.github.nath_goa.usugami.core.HtmlEntities
import io.github.nath_goa.usugami.core.HtmlText
import io.github.nath_goa.usugami.core.XmlPrefix
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** An e-book protected with DRM: its text is encrypted, and only the store's app can read it. */
class EpubProtected : OfficeFileProblem("DRM-protected e-book")

/**
 * An EPUB file: a zip of XHTML, pictures and style sheets. Only read, never extracted, so odd names
 * inside the zip can't reach the file system.
 */
internal class EpubArchive(file: File) : Closeable {
    private val zip = ZipFile(file)
    private val entries: Map<String, ZipEntry>
    // EPUB paths are case-sensitive, but books made on Windows sometimes link with the wrong case.
    private val folded: Map<String, ZipEntry>

    init {
        val all = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
        entries = all.associateBy { it.name.trimStart('/') }
        folded = all.associateBy { it.name.trimStart('/').lowercase() }
    }

    private fun entry(path: String) = entries[path] ?: folded[path.lowercase()]

    fun has(path: String) = entry(path) != null

    fun open(path: String): InputStream? = entry(path)?.let(zip::getInputStream)

    /** The first file whose name ends with [suffix], for books whose container file is missing. */
    fun firstEndingWith(suffix: String): String? = entries.keys.sorted().firstOrNull { it.lowercase().endsWith(suffix) }

    override fun close() = zip.close()
}

/** An entry of the book's table of contents, pointing at a file in the book and maybe a place in it. */
internal class TocEntry(val title: String, val path: String?, val fragment: String?, val children: List<TocEntry>)

/** A document in the book's reading order. [linear] is false for ones read only when linked to, such as notes. */
internal class SpineItem(val path: String, val mediaType: String, val linear: Boolean)

/** What the book's package file (OPF) says: its title, reading order, cover and table of contents. */
internal class EpubBook(
    val title: String?,
    val creator: String?,
    val language: String?,
    val spine: List<SpineItem>,
    val coverImage: String?,
    /** Each document is one page drawn at a fixed size, as in comics and picture books. */
    val fixedLayout: Boolean,
    private val navPath: String?,
    private val ncxPath: String?,
) {
    /**
     * The table of contents: from the EPUB 3 navigation document, else the EPUB 2 NCX file. Empty
     * when the book has neither, or they can't be read.
     */
    fun toc(archive: EpubArchive, check: () -> Unit): List<TocEntry> {
        val nav = navPath?.let { path -> runCatching { readNav(archive, path, check) }.onFailure(::rethrowCancel).getOrNull() }
        if (!nav.isNullOrEmpty()) return nav
        return ncxPath?.let { path -> runCatching { readNcx(archive, path, check) }.onFailure(::rethrowCancel).getOrNull() }.orEmpty()
    }

    private fun readNav(archive: EpubArchive, path: String, check: () -> Unit): List<TocEntry> = archive.open(path)?.use { stream ->
        val p = LenientXml.parser(stream)
        // <nav epub:type="toc"> holds an <ol>; each <li> has an <a> (or a <span> heading) and maybe a nested <ol>.
        class Item(val title: StringBuilder = StringBuilder(), var href: String? = null, val children: ArrayList<TocEntry> = ArrayList())
        val stack = ArrayList<Item>()
        val top = ArrayList<TocEntry>()
        var navDepth = -1
        var labelDepth = -1
        var done = false
        var events = 0
        try {
            while (!done) {
                if (++events % 512 == 0) check()
                when (p.next()) {
                    XmlPullParser.START_TAG -> {
                        val tag = p.localName()
                        if (navDepth < 0) {
                            if (tag == "nav" && p.attr("type")?.split(' ')?.contains("toc") == true) navDepth = p.depth
                        } else when (tag) {
                            "li" -> stack += Item()
                            "a", "span" -> if (stack.isNotEmpty() && labelDepth < 0) {
                                labelDepth = p.depth
                                if (tag == "a") stack.last().href = p.attr("href")
                            }
                        }
                    }
                    XmlPullParser.TEXT -> if (labelDepth >= 0) stack.lastOrNull()?.title?.append(p.text)
                    XmlPullParser.END_TAG -> {
                        if (navDepth < 0) continue
                        if (p.depth == labelDepth) labelDepth = -1
                        when {
                            p.depth == navDepth -> done = true
                            p.localName() == "li" -> stack.removeLastOrNull()?.let { item ->
                                val target = item.href?.let { EpubPaths.resolve(path, it) }
                                val entry = TocEntry(clean(item.title), target?.first, target?.second, item.children)
                                (stack.lastOrNull()?.children ?: top) += entry
                            }
                        }
                    }
                    XmlPullParser.END_DOCUMENT -> done = true
                }
            }
        } catch (e: Exception) {
            rethrowCancel(e)
            // A damaged navigation document: the entries read so far.
        }
        top
    }.orEmpty()

    private fun readNcx(archive: EpubArchive, path: String, check: () -> Unit): List<TocEntry> = archive.open(path)?.use { stream ->
        val p = LenientXml.parser(stream)
        class Point(val title: StringBuilder = StringBuilder(), var src: String? = null, val children: ArrayList<TocEntry> = ArrayList())
        val stack = ArrayList<Point>()
        val top = ArrayList<TocEntry>()
        var inLabel = false
        var events = 0
        try {
            while (true) {
                if (++events % 512 == 0) check()
                when (p.next()) {
                    XmlPullParser.START_TAG -> when (p.localName()) {
                        "navPoint" -> stack += Point()
                        "navLabel" -> inLabel = stack.isNotEmpty()
                        "content" -> stack.lastOrNull()?.let { if (it.src == null) it.src = p.attr("src") }
                        // The page list and other lists aren't the table of contents.
                        "pageList", "navList" -> break
                    }
                    XmlPullParser.TEXT -> if (inLabel) stack.lastOrNull()?.title?.append(p.text)
                    XmlPullParser.END_TAG -> when (p.localName()) {
                        "navLabel" -> inLabel = false
                        "navPoint" -> stack.removeLastOrNull()?.let { point ->
                            val target = point.src?.let { EpubPaths.resolve(path, it) }
                            (stack.lastOrNull()?.children ?: top) += TocEntry(clean(point.title), target?.first, target?.second, point.children)
                        }
                    }
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
        } catch (e: Exception) {
            rethrowCancel(e)
        }
        top
    }.orEmpty()

    private fun clean(title: CharSequence) = HtmlText.collapse(title.toString(), afterSpace = true).trim()

    companion object {
        private const val CONTAINER = "META-INF/container.xml"

        /**
         * Reads the book's package. Throws [EpubProtected] for a book with DRM, [OfficeNotRecognized]
         * for a file that isn't an EPUB, and [OfficeNothingToConvert] for one with nothing to show.
         */
        fun read(archive: EpubArchive, check: () -> Unit): EpubBook {
            if (isProtected(archive, check)) throw EpubProtected()
            val opf = packagePath(archive, check) ?: throw OfficeNotRecognized()
            return readPackage(archive, opf, check)
        }

        private fun isProtected(archive: EpubArchive, check: () -> Unit): Boolean {
            // Adobe's DRM keeps its licence in rights.xml; Readium LCP in license.lcpl.
            val rights = archive.has("META-INF/rights.xml") || archive.has("META-INF/license.lcpl")
            val algorithms = ArrayList<String>()
            archive.open("META-INF/encryption.xml")?.let { stream ->
                try {
                    val p = LenientXml.parser(stream)
                    var events = 0
                    while (true) {
                        if (++events % 512 == 0) check()
                        when (p.next()) {
                            XmlPullParser.START_TAG -> if (p.localName() == "EncryptionMethod") algorithms += p.attr("Algorithm").orEmpty()
                            XmlPullParser.END_DOCUMENT -> break
                        }
                    }
                } catch (e: Exception) {
                    rethrowCancel(e)
                    // An encryption file that can't be read: assume the worst rather than print gibberish.
                    algorithms += "unreadable"
                } finally {
                    stream.close()
                }
            }
            return EpubProtection.isProtected(rights, algorithms)
        }

        /** Where the package file is, from META-INF/container.xml (or the first .opf file, in books without one). */
        private fun packagePath(archive: EpubArchive, check: () -> Unit): String? {
            val fromContainer = archive.open(CONTAINER)?.use { stream ->
                try {
                    val p = LenientXml.parser(stream)
                    var found: String? = null
                    var events = 0
                    while (found == null) {
                        if (++events % 512 == 0) check()
                        when (p.next()) {
                            XmlPullParser.START_TAG -> if (p.localName() == "rootfile") {
                                val type = p.attr("media-type")
                                if (type == null || type == "application/oebps-package+xml") found = p.attr("full-path")
                            }
                            XmlPullParser.END_DOCUMENT -> break
                        }
                    }
                    found
                } catch (e: Exception) {
                    rethrowCancel(e)
                    null
                }
            }
            return fromContainer?.trim()?.trimStart('/')?.let(EpubPaths::decode)?.takeIf(archive::has) ?: archive.firstEndingWith(".opf")
        }

        private class ManifestItem(val path: String, val mediaType: String, val properties: Set<String>)

        private fun readPackage(archive: EpubArchive, opf: String, check: () -> Unit): EpubBook {
            val stream = archive.open(opf) ?: throw OfficeNotRecognized()
            val p = try {
                LenientXml.parser(stream)
            } catch (e: Exception) {
                stream.close()
                rethrowCancel(e)
                throw OfficeNotRecognized()
            }
            var title: String? = null
            var creator: String? = null
            var language: String? = null
            var coverId: String? = null
            var fixed = false
            val manifest = HashMap<String, ManifestItem>()
            val spineRefs = ArrayList<Triple<String, Boolean, Set<String>>>()
            var tocId: String? = null
            var sawPackage = false
            var text: StringBuilder? = null
            var textFor: String? = null
            var events = 0
            try {
                while (true) {
                    if (++events % 512 == 0) check()
                    when (p.next()) {
                        XmlPullParser.START_TAG -> when (val tag = p.localName()) {
                            "package" -> sawPackage = true
                            // Dublin Core's, the only elements with these names in a package file.
                            "title", "creator", "language" -> {
                                text = StringBuilder()
                                textFor = tag
                            }
                            "meta" -> {
                                if (p.attr("name") == "cover") coverId = p.attr("content")
                                if (p.attr("property") == "rendition:layout") {
                                    text = StringBuilder()
                                    textFor = "layout"
                                }
                            }
                            "item" -> {
                                val id = p.attr("id")
                                val href = p.attr("href")
                                val target = href?.let { EpubPaths.resolve(opf, it) }?.first
                                if (id != null && target != null) {
                                    manifest[id] = ManifestItem(target, p.attr("media-type").orEmpty().lowercase(), p.attr("properties").orEmpty().split(' ').toSet())
                                }
                            }
                            "spine" -> tocId = p.attr("toc")
                            "itemref" -> p.attr("idref")?.let { spineRefs += Triple(it, p.attr("linear") != "no", p.attr("properties").orEmpty().split(' ').toSet()) }
                        }
                        XmlPullParser.TEXT -> text?.append(p.text)
                        XmlPullParser.END_TAG -> if (text != null && (p.localName() == textFor || (textFor == "layout" && p.localName() == "meta"))) {
                            val value = text.toString().trim()
                            when (textFor) {
                                "title" -> if (title == null && value.isNotEmpty()) title = value
                                "creator" -> if (creator == null && value.isNotEmpty()) creator = value
                                "language" -> if (language == null && value.isNotEmpty()) language = value
                                "layout" -> fixed = value == "pre-paginated"
                            }
                            text = null
                            textFor = null
                        }
                        XmlPullParser.END_DOCUMENT -> break
                    }
                }
            } catch (e: Exception) {
                rethrowCancel(e)
                if (!sawPackage || manifest.isEmpty()) throw OfficeNotRecognized()
            } finally {
                stream.close()
            }
            if (!sawPackage) throw OfficeNotRecognized()
            val spine = spineRefs.mapNotNull { (id, linear, _) ->
                val item = manifest[id] ?: return@mapNotNull null
                if (!archive.has(item.path)) return@mapNotNull null
                val readable = item.mediaType in DOCUMENT_TYPES || item.mediaType.startsWith("image/") && item.mediaType != "image/svg+xml" ||
                    item.mediaType.isEmpty() && Regex("""\.x?html?$""", RegexOption.IGNORE_CASE).containsMatchIn(item.path)
                if (readable) SpineItem(item.path, item.mediaType, linear) else null
            }
            if (spine.isEmpty()) throw OfficeNothingToConvert()
            // Fixed layout can also be set for single documents; most books set it for the whole book.
            if (!fixed && spineRefs.isNotEmpty() && spineRefs.all { "rendition:layout-pre-paginated" in it.third }) fixed = true
            val cover = (manifest.values.firstOrNull { "cover-image" in it.properties } ?: coverId?.let { manifest[it] })
                ?.takeIf { it.mediaType.startsWith("image/") || it.mediaType.isEmpty() }?.path?.takeIf(archive::has)
            val nav = manifest.values.firstOrNull { "nav" in it.properties }?.path?.takeIf(archive::has)
            val ncx = (tocId?.let { manifest[it] } ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" })?.path?.takeIf(archive::has)
            return EpubBook(title, creator, language, spine, cover, fixed, nav, ncx)
        }

        private val DOCUMENT_TYPES = setOf("application/xhtml+xml", "text/html", "application/xml", "text/xml")
    }
}

/** Cancelling (from check()) goes on up; other problems were the file's. */
internal fun rethrowCancel(e: Throwable) {
    if (e is java.util.concurrent.CancellationException) throw e
}

/**
 * XML as e-books write it, read forgivingly: HTML's named characters (&nbsp;, &eacute;...) are
 * known, mismatched or unclosed tags are tolerated, and the DOCTYPE is dropped before parsing, so
 * nothing it declares can expand. Namespaces aren't processed: elements and attributes are matched
 * by their local names ([localName], [attr]).
 */
internal object LenientXml {
    private const val RELAXED = "http://xmlpull.org/v1/doc/features.html#relaxed"
    private const val HEAD = 8192

    fun parser(input: InputStream): XmlPullParser {
        val stream = BufferedInputStream(input, 16 * 1024)
        stream.mark(HEAD)
        val head = ByteArray(HEAD)
        val read = readFully(stream, head)
        stream.reset()
        val reader = InputStreamReader(stream, XmlPrefix.charset(head, read))
        val start = CharArray(HEAD)
        val count = readFully(reader, start)
        val prefix = XmlPrefix.stripDoctype(String(start, 0, count).removePrefix("\uFEFF"))
            ?: throw IOException("No root element near the start, or an unfinished DOCTYPE")
        // Android's parser comes processing DOCTYPEs and namespaces; neither is wanted here (and
        // entities can't be defined while DOCTYPEs are processed).
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        try {
            parser.setFeature(RELAXED, true)
        } catch (_: Exception) {
            // Strict, then: the parts of a malformed document before the error are still read.
        }
        parser.setInput(JoinedReader(StringReader(prefix), reader))
        for ((name, value) in HtmlEntities.all) {
            try {
                parser.defineEntityReplacementText(name, value)
            } catch (_: Exception) {
                break
            }
        }
        return parser
    }

    private fun readFully(input: InputStream, into: ByteArray): Int {
        var total = 0
        while (total < into.size) {
            val n = input.read(into, total, into.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun readFully(input: Reader, into: CharArray): Int {
        var total = 0
        while (total < into.size) {
            val n = input.read(into, total, into.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /** [first], then [second]. */
    private class JoinedReader(private val first: Reader, private val second: Reader) : Reader() {
        private var onFirst = true

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (onFirst) {
                val n = first.read(cbuf, off, len)
                if (n >= 0) return n
                onFirst = false
            }
            return second.read(cbuf, off, len)
        }

        override fun close() {
            first.close()
            second.close()
        }
    }
}

/** The element's name without its prefix ("dc:title" is "title"). */
internal fun XmlPullParser.localName(): String = name.substringAfter(':')

/** An attribute by its local name, whatever its prefix (href and xlink:href alike). */
internal fun XmlPullParser.attr(local: String): String? {
    for (i in 0 until attributeCount) {
        val name = getAttributeName(i)
        if (name == local || name.endsWith(":$local")) return getAttributeValue(i)
    }
    return null
}
