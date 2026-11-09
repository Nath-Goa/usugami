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
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Puts the small "Usugami" mark ([MarkStyle], see [MarkLayout]) on every page of a PDF Usugami made,
 * changes it, or takes it off again, in place: in the bottom-right corner of each page as it is shown
 * (its crop box, turned by its rotation), upright, in Helvetica Bold (one of the fonts every PDF viewer
 * has, so nothing is embedded), in a dark grey at the chosen opacity.
 *
 * What it adds is all marked with [MARKER], so it can be found and taken out again, leaving each page
 * as it was:
 * - a form XObject that draws the word, with its own font and opacity, one for the whole PDF;
 * - before each page's own content, a stream that saves the graphics state (q), one for the whole PDF;
 * - after it, a stream of the page's own that restores that state (Q), so nothing the page's content
 *   leaves behind can move or recolour the mark, and draws the form at the page's corner, marked as a
 *   pagination artifact so that screen readers and other accessibility tools pass over it. Its marker
 *   keeps the style, where on the page it was drawn, the form's name, what the page's content was,
 *   and which resources were added;
 * - the form's name in the page's XObject resources.
 */
class PageMark(context: Context) {
    private val scratchDir = File(context.cacheDir, "pdfbox")

    init { PDFBoxResourceLoader.init(context.applicationContext) }

    /**
     * Brings [file] to carry [mark] on every page, or no mark when it is null, and returns whether it
     * changed. A file that already does is left as it is. A PDF with a password can't be changed: that
     * throws. The new file is written whole beside [file] before it takes its place, so a run that
     * fails or is stopped by [check] (called for each page) leaves [file] as it was.
     */
    fun apply(file: File, mark: MarkStyle?, check: () -> Unit = {}): Boolean {
        val temp = File(file.parentFile, "${file.name}.mark.tmp")
        try {
            val changed = PDDocument.load(file, "", memory()).use { doc ->
                // Saving it would take its password off. No tool's PDF has one.
                if (doc.isEncrypted) throw IOException("${file.name} has a password")
                val pages = doc.pages.toList()
                if (pages.all { carries(it, mark) }) return false
                // Every page's mark goes first: pages that share their resources then never find
                // another page's old mark there.
                for (page in pages) {
                    check()
                    unmark(page.cosObject)
                }
                if (mark != null) {
                    val form = form(doc, mark)
                    val before = stream(doc, "q\n").apply { setItem(MARKER, COSBoolean.TRUE) }
                    for (page in pages) {
                        check()
                        mark(doc, page, form, before, mark)
                    }
                }
                check()
                doc.save(temp)
                // Saving can't be stopped part way: stopped meanwhile, the file stays as it was.
                check()
                true
            }
            if (changed && !temp.renameTo(file)) throw IOException("Couldn't replace ${file.name}")
            return changed
        } finally {
            temp.delete()
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
        val ours = contents(page.cosObject).map { resolve(it) }.filter { (it as? COSStream)?.containsKey(MARKER) == true }
        if (mark == null) return ours.isEmpty()
        val markers = ours.mapNotNull { (it as COSStream).getDictionaryObject(MARKER) as? COSDictionary }
        val marker = markers.singleOrNull() ?: return false
        if (ours.size != 2 || marker.getInt(KEY_SIZE, -1) != mark.size || marker.getInt(KEY_OPACITY, -1) != mark.opacity) return false
        // A page turned or cropped since it was marked has the mark in the wrong place.
        val drawn = marker.getDictionaryObject(KEY_MATRIX) as? COSArray ?: return false
        val wanted = matrix(page, mark)
        return drawn.size() == wanted.size && wanted.indices.all { i ->
            val number = drawn.getObject(i) as? COSNumber ?: return false
            abs(number.floatValue() - wanted[i]) < MATRIX_SLACK
        }
    }

    /** Draws [form], the mark in [mark]'s style, in [page]'s bottom-right corner as shown, after the page's own content. */
    private fun mark(doc: PDDocument, page: PDPage, form: COSStream, before: COSStream, mark: MarkStyle) {
        val dict = page.cosObject
        val was = dict.getItem(COSName.CONTENTS)
        val found = resources(dict)
        val madeResources = found == null
        val resources = found ?: COSDictionary().also { dict.setItem(COSName.RESOURCES, it) }
        val foundXObjects = resources.getDictionaryObject(COSName.XOBJECT) as? COSDictionary
        val madeXObjects = foundXObjects == null
        val xObjects = foundXObjects ?: COSDictionary().also { resources.setItem(COSName.XOBJECT, it) }
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
                setInt(KEY_SIZE, mark.size)
                setInt(KEY_OPACITY, mark.opacity)
                setItem(KEY_MATRIX, COSArray().apply { matrix.forEach { add(COSFloat(number(it))) } })
                setItem(KEY_NAME, name)
                if (was != null) setItem(KEY_WAS, was)
                if (madeXObjects) setBoolean(KEY_MADE_XOBJECTS, true)
                if (madeResources) setBoolean(KEY_MADE_RESOURCES, true)
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
        val font = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.FONT)
            setItem(COSName.SUBTYPE, COSName.TYPE1)
            setItem(COSName.BASE_FONT, COSName.getPDFName("Helvetica-Bold"))
            setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING)
        }
        val state = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.EXT_G_STATE)
            setFloat(COSName.CA_NS, mark.alpha)
        }
        val resources = COSDictionary().apply {
            setItem(COSName.FONT, COSDictionary().apply { setItem(FONT_NAME, font) })
            setItem(COSName.EXT_G_STATE, COSDictionary().apply { setItem(STATE_NAME, state) })
        }
        val gray = number(MarkLayout.GRAY)
        return stream(doc, "/${STATE_NAME.name} gs\n$gray g\nBT\n/${FONT_NAME.name} 1 Tf\n(${MarkLayout.TEXT}) Tj\nET\n").apply {
            setItem(COSName.TYPE, COSName.XOBJECT)
            setItem(COSName.SUBTYPE, COSName.FORM)
            // Round the word's letters, with room to spare: "U" and "i" reach up 0.72, "g" down 0.22.
            setItem(COSName.BBOX, COSArray().apply { listOf(0f, -0.3f, MarkLayout.TEXT_WIDTH + 0.1f, 0.8f).forEach { add(COSFloat(it)) } })
            setItem(COSName.RESOURCES, resources)
            setItem(MARKER, COSBoolean.TRUE)
        }
    }

    private fun stream(doc: PDDocument, content: String): COSStream = doc.document.createCOSStream().apply {
        createOutputStream().use { it.write(content.toByteArray(Charsets.US_ASCII)) }
    }

    companion object {
        /** In the dictionary of each stream the mark adds. */
        val MARKER: COSName = COSName.getPDFName("UsugamiMark")

        private val FORM_NAME = COSName.getPDFName("UsugamiMark")
        private val FONT_NAME = COSName.getPDFName("F")
        private val STATE_NAME = COSName.getPDFName("GS")
        private val KEY_SIZE = COSName.getPDFName("Size")
        private val KEY_OPACITY = COSName.getPDFName("Opacity")
        private val KEY_MATRIX = COSName.getPDFName("Matrix")
        private val KEY_NAME = COSName.getPDFName("Name")
        private val KEY_WAS = COSName.getPDFName("Was")
        private val KEY_MADE_XOBJECTS = COSName.getPDFName("MadeXObjects")
        private val KEY_MADE_RESOURCES = COSName.getPDFName("MadeResources")

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
            val markers = ours.mapNotNull { (resolve(elements[it]) as COSStream).getDictionaryObject(MARKER) as? COSDictionary }
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
