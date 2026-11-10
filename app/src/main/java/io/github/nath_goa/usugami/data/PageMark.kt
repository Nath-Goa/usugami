package io.github.nath_goa.usugami.data

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSBoolean
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageTree
import io.github.nath_goa.usugami.core.MarkLayout
import io.github.nath_goa.usugami.core.MarkStyle
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs

/**
 * Puts the small "Usugami" mark ([MarkStyle], see [MarkLayout]) on every page of a PDF Usugami made,
 * changes it, or takes it off again, in place: in the bottom-right corner of each page as it is shown
 * (its crop box, turned by its rotation), upright, in a dark grey at the chosen opacity. The word is
 * drawn as the outlines of its letters, filled shapes rather than text, so the PDF's text search and
 * copy, and screen readers, don't find it.
 *
 * What it adds is all marked with [MARKER], so it can be found and taken out again, leaving each page
 * as it was:
 * - a form XObject that draws the word, with its own opacity, one for the whole PDF;
 * - before each page's own content, a stream that saves the graphics state (q), one for the whole PDF;
 * - after it, a stream of the page's own that restores that state (Q), so nothing the page's content
 *   leaves behind can move or recolour the mark, and draws the form at the page's corner, marked as a
 *   pagination artifact so that screen readers and other accessibility tools pass over it. Its marker
 *   keeps the mark's version (1.6.1 wrote the word as text), the style, where on the page it was
 *   drawn, the form's name, what the page's content was, and which resources were added;
 * - the form's name in the page's XObject resources.
 *
 * A PDF with a signature that still holds would lose it if it was saved anew: the mark is appended to
 * it as an update instead, which leaves the signed PDF byte for byte as it was, and the markers keep
 * where it ends. Changing the mark or taking it off cuts the file back there first. A PDF whose
 * certification still holds and allows no change at all gets no mark ([MarkForbidden]).
 */
class PageMark(context: Context) {
    private val scratchDir = File(context.cacheDir, "pdfbox")

    init { PDFBoxResourceLoader.init(context.applicationContext) }

    /**
     * Brings [file] to carry [mark] on every page, or no mark when it is null, and returns whether it
     * changed. A file that already does is left as it is. A PDF with a password can't be changed: that
     * throws, as [MarkForbidden] does for a PDF still certified against any change. The new file is
     * written whole beside [file] before it takes its place, so a run that fails or is stopped by
     * [check] (called for each page) leaves [file] as it was.
     */
    fun apply(file: File, mark: MarkStyle?, check: () -> Unit = {}): Boolean {
        val temp = File(file.parentFile, "${file.name}.mark.tmp")
        val signed = File(file.parentFile, "${file.name}.signed.tmp")
        var markSigned = false
        try {
            val changed = PDDocument.load(file, "", memory()).use { doc ->
                // Saving it would take its password off. No tool's PDF has one.
                if (doc.isEncrypted) throw IOException("${file.name} has a password")
                val pages = doc.pages.toList()
                if (pages.all { carries(it, mark) }) return false
                val end = appendedAt(pages, file)
                when {
                    // A mark appended to a signed PDF comes off as the file is cut back to it.
                    end != null -> {
                        cut(file, end, if (mark == null) temp else signed)
                        markSigned = mark != null
                    }
                    mark != null && pages.none { ours(it.cosObject).isNotEmpty() } && signatures(doc, file).isNotEmpty() ->
                        append(doc, file, mark, check, temp)
                    else -> {
                        // Every page's mark goes first: pages that share their resources then never find
                        // another page's old mark there.
                        for (page in pages) {
                            check()
                            unmark(page.cosObject)
                        }
                        if (mark != null) markPages(doc, pages, mark, check, appendedAt = null)
                        check()
                        doc.save(temp)
                    }
                }
                // Saving can't be stopped part way: stopped meanwhile, the file stays as it was.
                check()
                true
            }
            if (markSigned) PDDocument.load(signed, "", memory()).use { append(it, signed, mark!!, check, temp) }
            if (changed && !temp.renameTo(file)) throw IOException("Couldn't replace ${file.name}")
            return changed
        } finally {
            temp.delete()
            signed.delete()
        }
    }

    /**
     * Writes [file]'s first page to [target] without Usugami's mark, as it was made, for a picture of
     * it. Returns false, writing nothing, when the page has no mark: [file] shows it as it was made.
     */
    fun firstPageUnmarked(file: File, target: File): Boolean {
        PDDocument.load(file, "", memory()).use { doc ->
            val first = doc.getPage(0)
            if (!unmark(first.cosObject)) return false
            PDDocument(memory()).use { one ->
                // The page as it shows, alone: nothing that leads to the other pages comes along.
                one.addPage(
                    PDPage(first.mediaBox).apply {
                        cropBox = first.cropBox
                        rotation = first.rotation
                        resources = first.resources
                        first.cosObject.getItem(COSName.CONTENTS)?.let { cosObject.setItem(COSName.CONTENTS, it) }
                        first.cosObject.getItem(COSName.GROUP)?.let { cosObject.setItem(COSName.GROUP, it) }
                    },
                )
                one.save(target)
            }
        }
        return true
    }

    private fun memory() = pdfScratch(scratchDir.apply { mkdirs() })

    /**
     * Whether [page] carries [mark] once, drawn where it goes on the page as it is shown now, and
     * nothing else of Usugami's mark; or none of it when [mark] is null.
     */
    private fun carries(page: PDPage, mark: MarkStyle?): Boolean {
        val ours = ours(page.cosObject)
        if (mark == null) return ours.isEmpty()
        val marker = markers(page.cosObject).singleOrNull() ?: return false
        if (ours.size != 2 || marker.getInt(KEY_SIZE, -1) != mark.size || marker.getInt(KEY_OPACITY, -1) != mark.opacity) return false
        // One written as text, by 1.6.1, is drawn anew.
        if (marker.getInt(KEY_VERSION, 1) != VERSION) return false
        // A page turned or cropped since it was marked has the mark in the wrong place.
        val drawn = marker.getDictionaryObject(KEY_MATRIX) as? COSArray ?: return false
        val wanted = matrix(page, mark)
        return drawn.size() == wanted.size && wanted.indices.all { i ->
            val number = drawn.getObject(i) as? COSNumber ?: return false
            abs(number.floatValue() - wanted[i]) < MATRIX_SLACK
        }
    }

    /**
     * Draws the mark in [mark]'s style on [pages], none of which has it, and returns the dictionaries
     * that keep what changed on each: the page's, and the one its resources come from. [appendedAt]
     * is where the PDF as it was ends, when the mark is appended to it.
     */
    private fun markPages(doc: PDDocument, pages: List<PDPage>, mark: MarkStyle, check: () -> Unit, appendedAt: Long?): Set<COSDictionary> {
        val form = form(doc, mark)
        val before = stream(doc, "q\n").apply { setItem(MARKER, COSBoolean.TRUE) }
        val changed = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        for (page in pages) {
            check()
            changed += page.cosObject
            changed += mark(doc, page, form, before, mark, appendedAt)
        }
        return changed
    }

    /**
     * Marks [doc], read from [source], a PDF with a signature that holds, in [mark]'s style, and writes
     * it to [target] as [source] with an update appended: the pages and the resources the mark changed.
     * The PDF as it was stays byte for byte as it was, so its signatures still hold.
     */
    private fun append(doc: PDDocument, source: File, mark: MarkStyle, check: () -> Unit, target: File) {
        if (forbidsChanges(signatures(doc, source))) throw MarkForbidden(source.name)
        val changed = markPages(doc, doc.pages.toList(), mark, check, appendedAt = source.length())
        check()
        // Those given are written, and from them what they hold that is new or flagged as changed.
        target.outputStream().use { doc.saveIncremental(it, changed) }
        if (!startsWith(target, source)) throw IOException("${source.name} was changed, not added to")
    }

    /**
     * Draws [form], the mark in [mark]'s style, in [page]'s bottom-right corner as shown, after the
     * page's own content. Returns the dictionary [page]'s resources come from.
     */
    private fun mark(doc: PDDocument, page: PDPage, form: COSStream, before: COSStream, mark: MarkStyle, appendedAt: Long?): COSDictionary {
        val dict = page.cosObject
        val was = dict.getItem(COSName.CONTENTS)
        val found = resources(dict)
        val madeResources = found == null
        val resources = found ?: COSDictionary().also { dict.setItem(COSName.RESOURCES, it) }
        val foundXObjects = resources.getDictionaryObject(COSName.XOBJECT) as? COSDictionary
        val madeXObjects = foundXObjects == null
        val xObjects = foundXObjects ?: COSDictionary().also { resources.setItem(COSName.XOBJECT, it) }
        // Changed: an update appended to the PDF has them anew where they are objects of their own.
        resources.isNeedToBeUpdated = true
        xObjects.isNeedToBeUpdated = true
        // A name of its own, or the one an earlier page sharing these resources gave the form.
        val name = generateSequence(0) { it + 1 }
            .map { if (it == 0) FORM_NAME else COSName.getPDFName("${FORM_NAME.name}$it") }
            .first { candidate -> xObjects.getDictionaryObject(candidate).let { it == null || it === form } }
        xObjects.setItem(name, form)

        val matrix = matrix(page, mark)
        val after = stream(
            doc,
            "Q\nq\n/Artifact <</Type /Pagination /Subtype /Watermark>> BDC\n" +
                matrix.joinToString(" ") { number(it) } + " cm\n/${name.name} Do\nEMC\nQ\n",
        )
        after.setItem(
            MARKER,
            COSDictionary().apply {
                setInt(KEY_VERSION, VERSION)
                setInt(KEY_SIZE, mark.size)
                setInt(KEY_OPACITY, mark.opacity)
                setItem(KEY_MATRIX, COSArray().apply { matrix.forEach { add(COSFloat(number(it))) } })
                setItem(KEY_NAME, name)
                if (was != null) setItem(KEY_WAS, was)
                if (madeXObjects) setBoolean(KEY_MADE_XOBJECTS, true)
                if (madeResources) setBoolean(KEY_MADE_RESOURCES, true)
                if (appendedAt != null) setLong(KEY_ORIGINAL_LENGTH, appendedAt)
            },
        )
        dict.setItem(
            COSName.CONTENTS,
            COSArray().apply {
                add(before)
                contents(dict).forEach { add(it) }
                add(after)
            },
        )
        return if (madeResources) dict else holder(dict)
    }

    /** The page tree's node [page]'s resources come from: [page] itself, or the nearest above it that has them. */
    private fun holder(page: COSDictionary): COSDictionary {
        var node: COSDictionary? = page
        repeat(MAX_DEPTH) {
            val at = node ?: return page
            if (at.getDictionaryObject(COSName.RESOURCES) != null) return at
            node = at.getDictionaryObject(COSName.PARENT) as? COSDictionary
        }
        return page
    }

    /**
     * Where the form goes on [page] in [mark]'s size: from the form's space (a font size of 1, the
     * baseline's start at its origin) to the page's, in the bottom-right corner of the page as shown.
     */
    private fun matrix(page: PDPage, mark: MarkStyle): FloatArray {
        val frame = page.frame()
        val place = MarkLayout.place(mark, frame.shownWidth, frame.shownHeight)
        val m = frame.matrix
        val size = place.fontSize
        return floatArrayOf(
            m[0] * size, m[1] * size, m[2] * size, m[3] * size,
            m[0] * place.x + m[2] * place.y + m[4], m[1] * place.x + m[3] * place.y + m[5],
        )
    }

    /** The form that draws the word in [mark]'s opacity, at a font size of 1, its baseline starting at the origin. */
    private fun form(doc: PDDocument, mark: MarkStyle): COSStream {
        val state = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.EXT_G_STATE)
            setFloat(COSName.CA_NS, mark.alpha)
        }
        val resources = COSDictionary().apply {
            setItem(COSName.EXT_G_STATE, COSDictionary().apply { setItem(STATE_NAME, state) })
        }
        val gray = number(MarkLayout.GRAY)
        return stream(doc, "/${STATE_NAME.name} gs\n$gray g\n${MarkLayout.pdfWord()}", compressed = true).apply {
            setItem(COSName.TYPE, COSName.XOBJECT)
            setItem(COSName.SUBTYPE, COSName.FORM)
            // Round the word's letters, with room to spare.
            val bounds = listOf(MarkLayout.INK_LEFT - 0.1f, -MarkLayout.DESCENT - 0.1f, MarkLayout.INK_RIGHT + 0.1f, MarkLayout.INK_TOP + 0.1f)
            setItem(COSName.BBOX, COSArray().apply { bounds.forEach { add(COSFloat(number(it))) } })
            setItem(COSName.RESOURCES, resources)
            setItem(MARKER, COSBoolean.TRUE)
        }
    }

    private fun stream(doc: PDDocument, content: String, compressed: Boolean = false): COSStream = doc.document.createCOSStream().apply {
        (if (compressed) createOutputStream(COSName.FLATE_DECODE) else createOutputStream()).use { it.write(content.toByteArray(Charsets.US_ASCII)) }
    }

    /** Whether [file] starts with [start]'s bytes. */
    private fun startsWith(file: File, start: File): Boolean {
        if (file.length() < start.length()) return false
        DataInputStream(file.inputStream().buffered()).use { a ->
            start.inputStream().use { b ->
                val ours = ByteArray(BUFFER)
                val theirs = ByteArray(BUFFER)
                while (true) {
                    val read = b.read(theirs)
                    if (read < 0) return true
                    a.readFully(ours, 0, read)
                    for (i in 0 until read) if (ours[i] != theirs[i]) return false
                }
            }
        }
    }

    /**
     * The signature values in [doc], read from [file], that still hold there: of its signature fields
     * (a /V with a /ByteRange), and of its permissions (a certification, or usage rights). Saving it
     * anew would break each of them.
     */
    private fun signatures(doc: PDDocument, file: File): List<COSDictionary> {
        val found = ArrayList<COSDictionary>()
        val seen = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        fun take(value: COSBase?) {
            if (value is COSDictionary && value.containsKey(COSName.BYTERANGE) && seen.add(value)) found += value
        }
        fun walk(fields: COSArray?, depth: Int) {
            if (fields == null || depth > MAX_DEPTH) return
            for (i in 0 until fields.size()) {
                val field = fields.getObject(i) as? COSDictionary ?: continue
                if (!seen.add(field)) continue
                take(field.getDictionaryObject(COSName.V))
                walk(field.getDictionaryObject(COSName.KIDS) as? COSArray, depth + 1)
            }
        }
        val catalog = doc.documentCatalog.cosObject
        val form = catalog.getDictionaryObject(COSName.ACRO_FORM) as? COSDictionary
        walk(form?.getDictionaryObject(COSName.FIELDS) as? COSArray, 0)
        val perms = catalog.getDictionaryObject(COSName.PERMS) as? COSDictionary
        perms?.keySet()?.forEach { take(perms.getDictionaryObject(it)) }
        if (found.isEmpty()) return found
        return RandomAccessFile(file, "r").use { bytes -> found.filter { fits(it, bytes) } }
    }

    /**
     * Whether [value]'s /ByteRange fits [bytes] as signing left it: all of a revision, from the start
     * to an end of file mark, but the /Contents string between. Saved anew, a PDF keeps the value, but
     * no longer the signature: its bytes have moved.
     */
    private fun fits(value: COSDictionary, bytes: RandomAccessFile): Boolean {
        val range = value.getDictionaryObject(COSName.BYTERANGE) as? COSArray ?: return false
        if (range.size() != 4) return false
        val (start, gap, after, last) = (0 until 4).map { (range.getObject(it) as? COSNumber)?.longValue() ?: return false }
        // Not after + last, which a crafted range's huge numbers would turn negative.
        if (start != 0L || gap < 1 || after <= gap || last < 0 || after > bytes.length() || last > bytes.length() - after) return false
        fun at(position: Long): Int = bytes.run {
            seek(position)
            read()
        }
        return at(gap) == '<'.code && at(after - 1) == '>'.code && endsWithEof(bytes, after + last)
    }

    /** Whether the bytes of [bytes] before [end] end with an end of file mark, but for line breaks and the like. */
    private fun endsWithEof(bytes: RandomAccessFile, end: Long): Boolean {
        val tail = ByteArray(minOf(end, EOF_TAIL).toInt())
        bytes.seek(end - tail.size)
        bytes.readFully(tail)
        return String(tail, Charsets.ISO_8859_1).trimEnd { it.isWhitespace() || it == '\u0000' }.endsWith("%%EOF")
    }

    /** Whether one of [signatures] certifies its PDF allowing no change at all (DocMDP, /P 1). */
    private fun forbidsChanges(signatures: List<COSDictionary>): Boolean = signatures.any { signature ->
        val references = signature.getDictionaryObject(COSName.REFERENCE) as? COSArray ?: return@any false
        (0 until references.size()).any { i ->
            val reference = references.getObject(i) as? COSDictionary
            reference?.getCOSName(COSName.TRANSFORM_METHOD) == COSName.DOCMDP &&
                (reference.getDictionaryObject(COSName.TRANSFORM_PARAMS) as? COSDictionary)?.getInt(COSName.P, 2) == 1
        }
    }

    companion object {
        /** In the dictionary of each stream the mark adds. */
        val MARKER: COSName = COSName.getPDFName("UsugamiMark")

        private val FORM_NAME = COSName.getPDFName("UsugamiMark")
        private val STATE_NAME = COSName.getPDFName("GS")
        private val KEY_VERSION = COSName.getPDFName("Version")
        private val KEY_SIZE = COSName.getPDFName("Size")
        private val KEY_OPACITY = COSName.getPDFName("Opacity")
        private val KEY_MATRIX = COSName.getPDFName("Matrix")
        private val KEY_NAME = COSName.getPDFName("Name")
        private val KEY_WAS = COSName.getPDFName("Was")
        private val KEY_MADE_XOBJECTS = COSName.getPDFName("MadeXObjects")
        private val KEY_MADE_RESOURCES = COSName.getPDFName("MadeResources")
        private val KEY_ORIGINAL_LENGTH = COSName.getPDFName("OriginalLength")

        /** The mark's version: 1.6.1's, without one, wrote the word as text; this one draws its outlines. */
        private const val VERSION = 2

        /** How deep a page tree or a form's fields are followed. */
        private const val MAX_DEPTH = 64

        private const val BUFFER = 64 * 1024

        /** How an update PDFBox appends starts: a line break, then its first object. */
        private val UPDATE_START = Regex("^\r\n\\d+ \\d+ obj")
        private const val UPDATE_HEAD = 32L

        /** How far before a signed revision's end its end of file mark is looked for. */
        private const val EOF_TAIL = 1024L

        /** The end of file mark each revision of a PDF ends with. */
        private val EOF_MARK = "%%EOF".toByteArray(Charsets.US_ASCII)

        /** How far a number of the mark's place may be from where it goes: in points, or font sizes. */
        private const val MATRIX_SLACK = 0.01f

        /**
         * Takes Usugami's mark off [doc]'s pages, in memory, and returns whether any had it. The tools
         * make their PDFs of the pages as they were, without it: once made, a PDF gets the mark the
         * setting asks for, or none. The file [doc] was read from isn't changed.
         */
        fun unmark(doc: PDDocument): Boolean {
            var found = false
            for (page in doc.pages) if (unmark(page.cosObject)) found = true
            return found
        }

        /** Takes Usugami's mark off [page], if it has one, and puts back what was there before; says whether it had one. */
        private fun unmark(page: COSDictionary): Boolean {
            val elements = contents(page)
            val ours = elements.indices.filter { (resolve(elements[it]) as? COSStream)?.containsKey(MARKER) == true }
            if (ours.isEmpty()) return false
            val markers = markers(page)
            for (marker in markers) {
                val resources = resources(page) ?: break
                val xObjects = resources.getDictionaryObject(COSName.XOBJECT) as? COSDictionary
                val name = marker.getCOSName(KEY_NAME)
                // Its own form only: another page sharing these resources may have taken it out already.
                if (name != null && (xObjects?.getDictionaryObject(name) as? COSStream)?.containsKey(MARKER) == true) xObjects.removeItem(name)
                if (xObjects != null && xObjects.size() == 0 && marker.getBoolean(KEY_MADE_XOBJECTS, false)) resources.removeItem(COSName.XOBJECT)
                if (resources.size() == 0 && marker.getBoolean(KEY_MADE_RESOURCES, false) && page.getDictionaryObject(COSName.RESOURCES) === resources) {
                    page.removeItem(COSName.RESOURCES)
                }
            }
            val left = elements.filterIndexed { i, _ -> i !in ours }
            val was = markers.firstNotNullOfOrNull { it.getItem(KEY_WAS) }
            when {
                // The page's content as it was, the very object, when nothing else has been added since.
                was != null && sameStreams(was, left) -> page.setItem(COSName.CONTENTS, was)
                left.isEmpty() -> page.removeItem(COSName.CONTENTS)
                else -> page.setItem(COSName.CONTENTS, COSArray().apply { left.forEach { add(it) } })
            }
            return true
        }

        /** [page]'s content streams the mark added. */
        private fun ours(page: COSDictionary): List<COSStream> =
            contents(page).mapNotNull { resolve(it) as? COSStream }.filter { it.containsKey(MARKER) }

        /** The markers of the mark on [page]: one, unless something went wrong. */
        private fun markers(page: COSDictionary): List<COSDictionary> = ours(page).mapNotNull { it.getDictionaryObject(MARKER) as? COSDictionary }

        /**
         * Where the PDF as it was ends in [file], when the mark on [pages] was appended to it as an
         * update: each marker says the same, and an update as PDFBox writes one starts there (a line
         * break, then an object), whatever that PDF ends with, and is the file's last (see
         * [endsOnce]). Null otherwise. That PDF, signed, is [file]'s first bytes up to there (see [cut]).
         */
        fun appendedAt(pages: Iterable<PDPage>, file: File): Long? {
            val ends = pages.flatMap { markers(it.cosObject) }.map { it.getLong(KEY_ORIGINAL_LENGTH, -1) }.toSet()
            val end = ends.singleOrNull()?.takeIf { it in 1 until file.length() } ?: return null
            val head = ByteArray(minOf(file.length() - end, UPDATE_HEAD).toInt())
            RandomAccessFile(file, "r").use {
                it.seek(end)
                it.readFully(head)
            }
            return end.takeIf { UPDATE_START.containsMatchIn(String(head, Charsets.ISO_8859_1)) && endsOnce(file, end) }
        }

        /**
         * Whether [file] has one end of file mark from [start] on: the update there is its last. An
         * update another app appended since, signing the PDF again, filling it in or adding a note or
         * a page, ends with a mark of its own: cut back to [start], the file would lose it.
         */
        private fun endsOnce(file: File, start: Long): Boolean {
            var ends = 0
            var matched = 0
            RandomAccessFile(file, "r").use { bytes ->
                bytes.seek(start)
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = bytes.read(buffer)
                    if (read < 0) break
                    for (i in 0 until read) {
                        val byte = buffer[i]
                        matched = when {
                            byte == EOF_MARK[matched] -> matched + 1
                            // One % more than the mark starts with: the last two still start it.
                            byte == EOF_MARK[0] -> if (matched == 2) 2 else 1
                            else -> 0
                        }
                        if (matched == EOF_MARK.size) {
                            if (++ends > 1) return false
                            matched = 0
                        }
                    }
                }
            }
            return ends == 1
        }

        /** Writes [file]'s first [length] bytes to [target]. */
        fun cut(file: File, length: Long, target: File) {
            file.inputStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER)
                    var left = length
                    while (left > 0) {
                        val read = input.read(buffer, 0, minOf(left, BUFFER.toLong()).toInt())
                        if (read < 0) throw IOException("${file.name} is shorter than its mark says")
                        output.write(buffer, 0, read)
                        left -= read
                    }
                }
            }
        }

        /** [page]'s content streams as it lists them: references, mostly. */
        private fun contents(page: COSDictionary): List<COSBase> = when (val contents = page.getDictionaryObject(COSName.CONTENTS)) {
            is COSArray -> (0 until contents.size()).map { contents.get(it) }
            is COSStream -> listOf(page.getItem(COSName.CONTENTS))
            else -> emptyList()
        }

        /** The resources [page] uses: its own, or those it takes from the page tree. */
        private fun resources(page: COSDictionary): COSDictionary? =
            PDPageTree.getInheritableAttribute(page, COSName.RESOURCES) as? COSDictionary

        /** Whether [was], a page's content as it was, is the streams [left], in order. */
        private fun sameStreams(was: COSBase, left: List<COSBase>): Boolean {
            val streams = when (val resolved = resolve(was)) {
                is COSArray -> (0 until resolved.size()).map { resolve(resolved.get(it)) }
                else -> listOf(resolved)
            }
            return streams.size == left.size && streams.indices.all { streams[it] === resolve(left[it]) }
        }

        private fun resolve(base: COSBase?): COSBase? = if (base is COSObject) base.getObject() else base

        /** [value] as a PDF number: no exponent, at most 4 decimals. */
        private fun number(value: Float): String =
            BigDecimal(value.toDouble()).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}

/** A PDF whose certification signature allows no change at all: the mark can't go on it. */
class MarkForbidden(name: String) : IOException("$name is certified against any change")
