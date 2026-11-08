package io.github.nath_goa.usugami.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.icu.lang.UCharacter
import android.os.ParcelFileDescriptor
import android.text.Html
import androidx.core.graphics.createBitmap
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import io.github.nath_goa.usugami.core.Areas
import io.github.nath_goa.usugami.core.GlyphBox
import io.github.nath_goa.usugami.core.PageFrame
import io.github.nath_goa.usugami.core.PageText
import io.github.nath_goa.usugami.core.PixelSource
import io.github.nath_goa.usugami.core.RasterEncoder
import io.github.nath_goa.usugami.core.RedactBox
import io.github.nath_goa.usugami.core.Redaction
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.Writer
import java.text.Bidi
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs

/**
 * Redact PDF: true removal, not a black box on top.
 *
 * Each page with a box is drawn as a picture, the boxes are painted solid black into the picture,
 * and the page's content is replaced by that picture at the same size: the text, images and
 * drawings under the boxes are no longer in the file at all. Pages without boxes stay as they are.
 * Then whatever else could still hold the hidden content goes (see [removeLeaks]).
 *
 * Android's renderer leaves annotations out, so Usugami draws and searches a copy of the PDF in
 * which each visible annotation (comments, stamps, filled-in form fields) is part of its page
 * ([drawAnnotationsIntoPages]). The preview and the saved pictures both come from that copy, so
 * what the boxes cover on the screen is exactly what is blacked out.
 */
internal object Redactor {
    /** JPEG quality for pages that are mostly photographs. */
    private const val JPEG_QUALITY = 90

    /**
     * Pages are kept lossless (PNG-style) unless that takes more than this many times the space of
     * a JPEG: text and line art then stay perfectly crisp, while scans and photos use JPEG.
     */
    private const val LOSSLESS_ALLOWANCE = 1.5

    /** How far, in points, something may reach past the edge of a page and still count as shown on it. */
    private const val SLACK = 1f

    /** Annotation flags (/F): hidden, and not shown on the screen (though perhaps printed). */
    private const val HIDDEN = 1 shl 1
    private const val NO_VIEW = 1 shl 5

    /** A Launch action's parameters for Windows, and the keys of a file specification that name its file. */
    private val WIN = COSName.getPDFName("Win")
    private val FILE_NAMES = listOf(COSName.F, COSName.UF, COSName.DOS, COSName.MAC, COSName.UNIX)

    /** The kinds of comment that mark what the page itself shows: text, or a place drawn around. */
    private val MARKUP = listOf("Highlight", "Underline", "StrikeOut", "Squiggly", "Ink", "Square", "Circle", "Polygon", "PolyLine", "Line", "Caret")
        .map(COSName::getPDFName).toSet()

    /**
     * Draws each visible annotation's appearance into its page, then removes the annotations, as a
     * PDF viewer shows them. Returns whether any was drawn.
     */
    fun drawAnnotationsIntoPages(doc: PDDocument, check: () -> Unit): Boolean {
        // As Flatten does: fields whose values viewers draw themselves (NeedAppearances) are drawn
        // first. Those PDFBox can't draw are drawn by Android instead, so they don't look empty.
        val undrawn = doc.documentCatalog.getAcroForm(null)?.let { form -> Flattening.redrawFields(form, form.fieldTree.toList(), check) }.orEmpty()
        val values = FieldValues.drawn(doc, undrawn, check)
        var any = false
        for (page in doc.pages) {
            check()
            val annotations = try { page.annotations } catch (_: IOException) { continue }
            if (annotations.isEmpty()) continue
            // Links show nothing of their own: a PDF with only links needs no view drawn.
            val shown = annotations.filter { !it.isHidden && !it.isNoView && it !is PDAnnotationPopup && it !is PDAnnotationLink }
            if (shown.isNotEmpty()) {
                any = true
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { content ->
                    for (annotation in shown) {
                        // A field's value Android draws, as viewers would: the field has no look of
                        // its own, or only one its form says is out of date.
                        val value = values[annotation.cosObject]
                        if (value != null) {
                            FieldValues.draw(content, annotation, value)
                            continue
                        }
                        // Placed and made see-through (its /CA) as Flatten draws it.
                        val appearance = try { Flattening.appearanceOf(doc, annotation) } catch (_: IOException) { null }
                        if (appearance != null) Flattening.draw(content, annotation, appearance)
                    }
                }
            }
            page.cosObject.removeItem(COSName.ANNOTS)
        }
        return any
    }

    // ---- Find text ----

    /**
     * Every place [query] is found in [doc]'s text, on every page, as a box over each line it is on.
     * Letters match ignoring case and accents. Text that is a picture (a scan) has nothing to find.
     *
     * The text is read twice: in the order the PDF draws it, which keeps a column's lines together
     * so a phrase is found across a line break, and sorted by position along each direction, which
     * reads text that runs up or down the page as shown. Matches found both ways count once. Text
     * set with wide spacing between its letters, and words broken after a hyphen at the end of a
     * line, are found too (see [PageText]).
     */
    fun find(doc: PDDocument, query: String, check: () -> Unit): List<List<RedactBox>> {
        if (PageText.normalize(query).isEmpty()) return emptyList()
        val found = LinkedHashMap<List<Int>, List<RedactBox>>()
        for (sorted in listOf(false, true)) {
            val finder = Finder(query, check).apply { sortByPosition = sorted }
            finder.writeText(doc, NoWriter)
            for (match in finder.matches) {
                // Rounded to a thousandth of a point or so: the same glyphs give the same boxes.
                val key = match.flatMap { box ->
                    listOf(box.page) + listOf(box.left, box.top, box.right, box.bottom).map { (it * 1e5f).toInt() }
                }
                found.getOrPut(key) { match }
            }
        }
        // In page order, as they are on each page.
        return found.values.sortedBy { it.first().page }
    }

    /**
     * A box over each comment on [doc]'s pages, but those in [skip], whose text has [query]: what
     * it says, its author or its subject, which viewers list though the page doesn't show them.
     * The box covers the comment as the page shows it (its icon, a stamp, a text box), so that page
     * becomes a picture and the comment goes. A comment that marks the page's own content (a
     * highlight, a drawing) gets the smallest box, at its corner: the page becomes a picture all
     * the same, and what it marks, which needn't hold the text, isn't blacked out. The pages in
     * [skip] have the text on them, so they lose their comments anyway.
     *
     * So do links and buttons whose web or email address, or the file they open, has [query] (also
     * where the address has it only as %-escapes, as a name with a space does), and form fields
     * that hold it where no page shows it (see [fieldsHolding]): its name, tooltip, default value or
     * one of its choices, or the value of a field hidden on its page. They too get the smallest
     * box, so their pages become pictures and they go; a field's widgets all do, on every page, so
     * that the field goes with its value (see [removeLeaks]). The value of a field the page shows
     * is on the page, where it is found as its text. A field none of whose widgets is on a page
     * gets no box: [redact] takes it out, told what Find text looked for. Only when nothing else
     * has [query] is a page boxed for it, the one its widget names or the first, so that there is
     * something to redact.
     */
    fun findInComments(doc: PDDocument, query: String, skip: Set<Int>, check: () -> Unit): List<List<RedactBox>> {
        val wanted = PageText.normalize(query)
        if (wanted.isEmpty()) return emptyList()
        fun has(text: String) = PageText.normalize(text).contains(wanted, ignoreCase = true)
        val holding = fieldsHolding(doc, ::has, check)
        val found = ArrayList<List<RedactBox>>()
        doc.pages.forEachIndexed { index, page ->
            if (index in skip) return@forEachIndexed
            check()
            val annotations = page.cosObject.getCOSArray(COSName.ANNOTS) ?: return@forEachIndexed
            val frame = page.shownFrame()
            for (item in annotations) {
                val annotation = item.resolve() as? COSDictionary ?: continue
                val subtype = annotation.getCOSName(COSName.SUBTYPE)
                val widget = subtype == COSName.WIDGET
                val says = !widget && (listOf(COSName.CONTENTS, COSName.T, COSName.SUBJ).mapNotNull { annotation.getString(it) } + listOfNotNull(richText(annotation))).any(::has)
                val leads = addresses(annotation).any(::has)
                val holds = widget && fieldOf(annotation).any { it in holding.fields }
                if (!says && !leads && !holds) continue
                // A pop-up's window may be anywhere: it is boxed where its comment is.
                val placed = (annotation.getDictionaryObject(COSName.PARENT) as? COSDictionary)?.takeIf { subtype == COSName.getPDFName("Popup") } ?: annotation
                val rect = placed.getCOSArray(COSName.RECT)?.toFloatArray()?.takeIf { it.size == 4 } ?: FloatArray(4)
                val (x0, y0) = frame.toShown(rect[0], rect[1])
                val (x1, y1) = frame.toShown(rect[2], rect[3])
                val box = Redaction.between(index, x0 / frame.shownWidth, 1 - y0 / frame.shownHeight, x1 / frame.shownWidth, 1 - y1 / frame.shownHeight)
                // What the page shows there needn't hold the text: it isn't blacked out.
                val small = !says || placed.getCOSName(COSName.SUBTYPE) in MARKUP
                // Off the page, it is boxed at the nearest edge, so the page is still redacted.
                found += listOf(Redaction.clamp(if (small) box.copy(right = box.left, bottom = box.top) else box))
            }
        }
        if (found.isEmpty() && skip.isEmpty()) holding.elsewhere.firstOrNull()?.let { page -> found += listOf(Redaction.clamp(RedactBox(page, 0f, 0f, 0f, 0f))) }
        return found
    }

    /**
     * The form fields whose own text has [has] where no page shows it, for their widgets on pages to
     * be boxed ([Holding.fields]), and the pages to box for those none of whose widgets is on a page
     * ([Holding.elsewhere]: the one a widget names, or the first). A field's names (/T, /TM),
     * tooltip (/TU), default value (/DV) and choices (/Opt) never show; its value (/V, also of the
     * fields above it, from which it may inherit) doesn't where its widget is hidden, nor at all
     * when none of its widgets is on a page.
     */
    private fun fieldsHolding(doc: PDDocument, has: (String) -> Boolean, check: () -> Unit): Holding {
        val holders = identitySet<COSDictionary>()
        val onPages = identitySet<COSDictionary>()
        val pageOf = IdentityHashMap<COSDictionary, Int>()
        doc.pages.forEachIndexed { index, page ->
            check()
            pageOf[page.cosObject] = index
            val annotations = page.cosObject.getCOSArray(COSName.ANNOTS) ?: return@forEachIndexed
            for (item in annotations) {
                val widget = item.resolve() as? COSDictionary ?: continue
                if (widget.getCOSName(COSName.SUBTYPE) != COSName.WIDGET) continue
                onPages += widget
                val hidden = widget.getInt(COSName.F, 0) and (HIDDEN or NO_VIEW) != 0
                for (field in fieldOf(widget)) {
                    if (labels(field).any(has) || hidden && texts(field.getDictionaryObject(COSName.V)).any(has)) holders += field
                }
            }
        }
        val elsewhere = ArrayList<Int>()
        val fields = doc.documentCatalog.cosObject.getCOSDictionary(COSName.ACRO_FORM)?.getCOSArray(COSName.FIELDS)
        walkFields(fields, onPages) { field, page ->
            if (fieldTexts(field).any(has)) elsewhere += page?.let(pageOf::get) ?: 0
        }
        return Holding(holders, elsewhere)
    }

    /** What [fieldsHolding] found. */
    private class Holding(val fields: Set<COSDictionary>, val elsewhere: List<Int>)

    /**
     * Walks the form's fields under [kids] (and their widgets), calling [offPage] for each that has
     * no widget among [onPages], with the page one of its widgets names (/P), if any. Returns
     * whether any of them has a widget on a page, and otherwise the page one names.
     */
    private fun walkFields(
        kids: COSArray?,
        onPages: Set<COSDictionary>,
        seen: MutableSet<COSDictionary> = identitySet(),
        depth: Int = 0,
        offPage: (COSDictionary, COSDictionary?) -> Unit,
    ): Pair<Boolean, COSDictionary?> {
        var shown = false
        var named: COSDictionary? = null
        if (kids == null || depth > 32) return shown to named
        for (i in 0 until kids.size()) {
            val field = kids.getObject(i) as? COSDictionary ?: continue
            if (!seen.add(field)) continue
            val (below, namedBelow) = walkFields(field.getCOSArray(COSName.KIDS), onPages, seen, depth + 1, offPage)
            val onPage = below || field in onPages
            val page = namedBelow ?: field.getDictionaryObject(COSName.P) as? COSDictionary
            if (!onPage) offPage(field, page)
            shown = shown || onPage
            named = named ?: page
        }
        return shown to named
    }

    /** What a form field says of itself that no page shows: its names, tooltip, default value and choices. */
    private fun labels(field: COSDictionary): List<String> =
        listOf(COSName.T, COSName.TM, COSName.TU).mapNotNull { field.getString(it) } +
            texts(field.getDictionaryObject(COSName.DV)) + options(field.getDictionaryObject(COSName.OPT))

    /** All a form field holds as text: its [labels] and its value. */
    private fun fieldTexts(field: COSDictionary): List<String> = labels(field) + texts(field.getDictionaryObject(COSName.V))

    /** A choice field's choices (/Opt): each a text, or the value it gives and the text it shows. */
    private fun options(choices: COSBase?): List<String> {
        val array = choices as? COSArray ?: return emptyList()
        return (0 until array.size()).flatMap { i ->
            when (val choice = array.getObject(i)) {
                is COSString -> listOf(choice.string)
                is COSArray -> (0 until choice.size()).mapNotNull { (choice.getObject(it) as? COSString)?.string }
                else -> emptyList()
            }
        }
    }

    /** [widget] and the form fields it belongs to, up to the top. */
    private fun fieldOf(widget: COSDictionary): Sequence<COSDictionary> =
        generateSequence(widget) { it.getDictionaryObject(COSName.PARENT) as? COSDictionary }.take(32)

    /** A field's value as text: a text, a choice (a name) or the choices of a list. */
    private fun texts(value: COSBase?): List<String> = when (value) {
        is COSString -> listOf(value.string)
        is COSName -> listOf(value.name)
        is COSArray -> (0 until value.size()).flatMap { texts(value.getObject(it).takeIf { item -> item !is COSArray }) }
        else -> emptyList()
    }

    /**
     * The web and email addresses (URI actions) and files [annotation] leads to, each also as a
     * person reads it (see [Redaction.addressForms]): a link's, a button's, also when it is clicked,
     * pressed or left (/AA, and a widget's field's own, when it is typed in or changes), those that
     * follow (/Next), and a link's former one (/PA). The files are those that actions open, send a
     * form to or launch (another PDF, a web address, an app with its parameters).
     */
    private fun addresses(annotation: COSDictionary): List<String> {
        val pending = ArrayDeque<COSBase?>()
        pending += annotation.getDictionaryObject(COSName.A)
        pending += annotation.getDictionaryObject(COSName.getPDFName("PA"))
        val triggered = if (annotation.getCOSName(COSName.SUBTYPE) == COSName.WIDGET) fieldOf(annotation) else sequenceOf(annotation)
        for (holder in triggered) {
            (holder.getDictionaryObject(COSName.AA) as? COSDictionary)?.let { triggers -> triggers.keySet().forEach { pending += triggers.getDictionaryObject(it) } }
        }
        val seen = identitySet<COSBase>()
        val found = ArrayList<String>()
        while (pending.isNotEmpty() && seen.size < 64) {
            when (val action = pending.removeLast()) {
                is COSArray -> if (seen.add(action)) (0 until action.size()).forEach { pending += action.getObject(it) }
                is COSDictionary -> if (seen.add(action)) {
                    action.getString(COSName.URI)?.let(found::add)
                    // GoToR's other PDF, Launch's file, SubmitForm's address, and so on.
                    fileNames(action.getDictionaryObject(COSName.F), found)
                    // An app launched on Windows: its file, its folder and its parameters.
                    (action.getDictionaryObject(WIN) as? COSDictionary)?.let { win -> listOf(COSName.F, COSName.D, COSName.P).mapNotNullTo(found) { win.getString(it) } }
                    pending += action.getDictionaryObject(COSName.NEXT)
                }
                else -> Unit
            }
        }
        return found.flatMap(Redaction::addressForms)
    }

    /** Adds to [into] the names a file specification gives: a string, or a dictionary's /F, /UF and older names for each system. */
    private fun fileNames(spec: COSBase?, into: MutableList<String>) {
        when (spec) {
            is COSString -> into += spec.string
            is COSDictionary -> FILE_NAMES.mapNotNullTo(into) { spec.getString(it) }
            else -> Unit
        }
    }

    /** A comment's rich text (/RC, XHTML), as plain text. */
    private fun richText(annotation: COSDictionary): String? {
        val xhtml = when (val rich = annotation.getDictionaryObject(COSName.RC)) {
            is COSString -> rich.string
            is COSStream -> try { rich.createInputStream().use { String(it.readBytes(), Charsets.UTF_8) } } catch (_: IOException) { null }
            else -> null
        } ?: return null
        return Html.fromHtml(xhtml, Html.FROM_HTML_MODE_LEGACY).toString()
    }

    /**
     * Collects each page's glyphs as text extraction reads them, and boxes the matches when the page
     * ends. Text extraction gives a line's glyphs from left to right, as they are drawn; Hebrew and
     * Arabic read the other way, so their glyphs are put in the order they are read (see [readingOrder]).
     */
    private class Finder(private val query: String, private val check: () -> Unit) : PDFTextStripper() {
        val matches = ArrayList<List<RedactBox>>()
        private var text = PageText.Builder()
        private val glyphs = ArrayList<TextPosition>()

        /** The line being read, its glyphs left to right; null between two words. */
        private val line = ArrayList<TextPosition?>()

        override fun startPage(page: PDPage) {
            check()
            text = PageText.Builder()
            glyphs.clear()
            line.clear()
        }

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            line += textPositions
        }

        override fun writeWordSeparator() {
            line += null
        }

        override fun writeLineSeparator() = space()
        override fun writeParagraphStart() = space()
        override fun writeParagraphEnd() = space()
        override fun startArticle(isLTR: Boolean) = space()
        override fun endArticle() = space()

        /** A break between lines: the line read so far goes into the page's text first. */
        private fun space() {
            endLine()
            text.lineBreak()
        }

        private fun endLine() {
            for ((glyph, mirrored) in readingOrder(line)) {
                // Text extraction found a gap there, with no space in the text: between two words,
                // or between two letters of letter-spaced text.
                if (glyph == null) {
                    text.gap()
                    continue
                }
                val unicode = glyph.unicode ?: ""
                text.glyph(glyphs.size, if (mirrored) mirror(unicode) else unicode)
                glyphs += glyph
            }
            line.clear()
        }

        override fun endPage(page: PDPage) {
            endLine()
            val matches = text.build().find(query)
            if (matches.isEmpty()) return
            val frame = page.shownFrame()
            val index = currentPageNo - 1
            for (match in matches) {
                val shapes = match.mapNotNull { glyphBox(glyphs[it], frame) }
                if (shapes.isNotEmpty()) this.matches += Redaction.boxesFor(index, shapes, frame.shownWidth, frame.shownHeight)
            }
        }
    }

    /**
     * [line]'s glyphs, given left to right as drawn (null between two words), in the order they are
     * read, each with whether it is in right-to-left text and so drawn as the mirror image of what
     * it says, as a bracket is. A line with no right-to-left letters stays as it is. Otherwise, as
     * PDFBox reads a word, the text as drawn is put through the Unicode bidirectional algorithm,
     * which, applied to text in the order it is drawn, turns it back into the order it is read; for
     * the whole line, so that the words of a phrase are in order too. A glyph moves as a whole, so
     * one that reads as several letters (a ligature) keeps them in order.
     */
    private fun readingOrder(line: List<TextPosition?>): List<Pair<TextPosition?, Boolean>> {
        val drawn = StringBuilder()
        val starts = IntArray(line.size)
        for ((i, glyph) in line.withIndex()) {
            starts[i] = drawn.length
            // A word break is a space, and a glyph that reads as nothing an object (a neutral).
            drawn.append(if (glyph == null) " " else glyph.unicode?.takeIf { it.isNotEmpty() } ?: OBJECT_CHARACTER)
        }
        // All one paragraph.
        for (i in drawn.indices) if (Character.getDirectionality(drawn[i]) == Character.DIRECTIONALITY_PARAGRAPH_SEPARATOR) drawn[i] = ' '
        val chars = drawn.toString().toCharArray()
        if (!Bidi.requiresBidi(chars, 0, chars.size)) return line.map { it to false }
        val bidi = Bidi(drawn.toString(), Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
        val levels = ByteArray(line.size) { bidi.getLevelAt(starts[it]).toByte() }
        val order = Array<Any>(line.size) { it }
        Bidi.reorderVisually(levels, 0, order, 0, order.size)
        return order.map { (it as Int).let { i -> line[i] to (levels[i].toInt() and 1 == 1) } }
    }

    /** U+FFFC, which stands for an object in text. */
    private val OBJECT_CHARACTER = Char(0xFFFC).toString()

    /** [text] with each character that has a mirror image (a bracket, say) turned into it. */
    private fun mirror(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            appendCodePoint(UCharacter.getMirror(c))
            i += Character.charCount(c)
        }
    }

    /**
     * Where [glyph] is drawn on the page as shown: from its font's descent to its ascent, along its
     * advance, turned with the text and with the page.
     */
    private fun glyphBox(glyph: TextPosition, frame: PageFrame): GlyphBox? {
        // Text extraction measures from the crop box's corner, in the page's own directions.
        val trm = glyph.textMatrix
        val startX = trm.translateX
        val startY = trm.translateY
        val endX = glyph.endX
        val endY = glyph.endY
        val (ascent, descent) = fontExtent(glyph)
        // One unit up in text space: the text's own "up", its size included.
        val upX = trm.shearX
        val upY = trm.scaleY
        val points = listOf(
            startX + upX * descent to startY + upY * descent, endX + upX * descent to endY + upY * descent,
            startX + upX * ascent to startY + upY * ascent, endX + upX * ascent to endY + upY * ascent,
        ).map { (x, y) ->
            val (shownX, shownY) = frame.toShown(x + frame.left, y + frame.bottom)
            shownX to frame.shownHeight - shownY
        }
        if (points.any { !it.first.isFinite() || !it.second.isFinite() }) return null
        // Whether the line runs across the page as shown, from the direction of the advance (or of "up").
        val (startShownX, startShownY) = points[0]
        val (endShownX, endShownY) = points[1]
        val (topShownX, topShownY) = points[2]
        val dx = endShownX - startShownX
        val dy = endShownY - startShownY
        val across = if (dx != 0f || dy != 0f) abs(dx) >= abs(dy) else abs(topShownY - startShownY) >= abs(topShownX - startShownX)
        return GlyphBox(points.minOf { it.first }, points.minOf { it.second }, points.maxOf { it.first }, points.maxOf { it.second }, across)
    }

    /**
     * How far the glyph's font reaches above and below the baseline, in text space (ems): the higher
     * of its ascent and its bounding box's top, so accents on capitals are covered, and the lower of
     * its descent and its bounding box's bottom. Values out of reason give way to typical ones.
     */
    private fun fontExtent(glyph: TextPosition): Pair<Float, Float> {
        val font = glyph.font ?: return 1f to -0.3f
        val scale = try { font.fontMatrix.scaleY } catch (_: Exception) { 0.001f }
        val descriptor = font.fontDescriptor
        val box = try { font.boundingBox } catch (_: Exception) { null }
        val ascent = listOfNotNull(descriptor?.ascent, box?.upperRightY).map { it * scale }.filter { it in 0.5f..1.3f }.maxOrNull() ?: 1f
        val descent = listOfNotNull(descriptor?.descent, box?.lowerLeftY).map { it * scale }.filter { it in -0.5f..-0.05f }.minOrNull() ?: -0.3f
        return ascent to descent
    }

    private object NoWriter : Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) = Unit
        override fun flush() = Unit
        override fun close() = Unit
    }

    // ---- Redacting ----

    /**
     * Replaces each page with [boxes] by a picture of it, drawn from [view] (the copy the preview
     * showed), with the boxes black, then removes what could leak the hidden content. Pages that
     * would keep some of it out of sight become pictures too (see [keepingHidden]). [searched] is
     * what Find text looked for: the form fields that hold it go too, once no page that stays shows
     * them (see [dropFieldsHolding]).
     */
    fun redact(doc: PDDocument, view: File, boxes: List<RedactBox>, searched: List<String>, check: () -> Unit) {
        val byPage = boxes.groupBy { it.page }
        require(byPage.isNotEmpty() && byPage.keys.all { it in 0 until doc.numberOfPages })
        // Pages that would keep out of sight what the boxes hide become pictures too, without boxes.
        val pictured = (byPage.keys + keepingHidden(doc, byPage, check)).toSortedSet()
        val redactedPages = identitySet<COSBase>()
        val removedAnnotations = identitySet<COSBase>()
        val drawnThere = identitySet<COSBase>()
        for (index in pictured) {
            check()
            val page = doc.getPage(index)
            if (index in byPage) redactedPages += page.cosObject
            page.cosObject.getCOSArray(COSName.ANNOTS)?.forEach { (it.resolve() as? COSDictionary)?.let(removedAnnotations::add) }
            XObjectCleanup.drawnBy(page, drawnThere)
        }

        ParcelFileDescriptor.open(view, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount != doc.numberOfPages) throw IOException("The copy has ${renderer.pageCount} pages, not ${doc.numberOfPages}")
                for (index in pictured) {
                    check()
                    replaceWithPicture(doc, doc.getPage(index), renderer, index, byPage[index].orEmpty(), check)
                }
            }
        }

        // Images and forms that only the pages now pictures drew must not stay behind, as they
        // would where pages share one list of resources: they are emptied in place.
        XObjectCleanup.emptyUnused(doc, drawnThere, (0 until doc.numberOfPages).filter { it !in pictured }.map(doc::getPage), check)

        check()
        removeLeaks(doc, redactedPages, removedAnnotations, searched)
    }

    /**
     * The pages without boxes that would keep, out of sight, something the boxes hide: they draw a
     * content stream of a page with boxes, or an image or form a box covers part of, but don't
     * show the part under the box, which would then stay in the file. A scanned spread split into
     * two pages is like that when each page draws the whole scan and its crop box shows one half.
     * Those pages become pictures too, of what they show. When a page without boxes shows that
     * part, as with a logo or a header every page draws, it is there for all to see: it stays in
     * the file through that page anyway, so no page becomes a picture because of it.
     */
    private fun keepingHidden(doc: PDDocument, byPage: Map<Int, List<RedactBox>>, check: () -> Unit): Set<Int> {
        // What the boxes hide: in the page's own coordinates, for each content stream that drew it…
        val inStreams = IdentityHashMap<COSBase, MutableList<Hidden>>()
        // …and in their own space, for the images and forms under them.
        val inXObjects = IdentityHashMap<COSBase, MutableList<Hidden>>()
        for ((index, boxes) in byPage) {
            check()
            val page = doc.getPage(index)
            val frame = page.shownFrame()
            val areas = boxes.map { box ->
                Flattening.pageArea(frame.matrix, box.left * frame.shownWidth, (1 - box.bottom) * frame.shownHeight, box.width * frame.shownWidth, box.height * frame.shownHeight)
            }
            for (stream in contentStreams(page)) inStreams.getOrPut(stream) { ArrayList() } += areas.map(::Hidden)
            for (placement in XObjectCleanup.placements(page).orEmpty()) {
                val inverse = Areas.inverted(placement.matrix) ?: continue
                val drawn = shownPart(Areas.mapped(placement.matrix, placement.extent), placement.clip) ?: continue
                for (area in areas) {
                    val covered = Areas.intersection(area, drawn) ?: continue
                    val own = Areas.intersection(Areas.mapped(inverse, covered), placement.extent) ?: continue
                    inXObjects.getOrPut(placement.xobject) { ArrayList() } += Hidden(own)
                }
            }
        }
        // For each page without boxes, what it keeps out of sight; and what some page shows.
        val keptBy = HashMap<Int, List<Hidden>>()
        val shown = identitySet<Hidden>()
        val reaches = IdentityHashMap<COSBase, Boolean>()
        for (index in 0 until doc.numberOfPages) {
            if (index in byPage) continue
            check()
            val page = doc.getPage(index)
            val crop = page.cropBox.let { floatArrayOf(it.lowerLeftX, it.lowerLeftY, it.upperRightX, it.upperRightY) }
            fun shows(area: FloatArray, clip: FloatArray?) = shownPart(crop, clip)?.let { Areas.inside(area, it, SLACK) } == true
            val kept = ArrayList<Hidden>()
            for (stream in contentStreams(page)) {
                for (part in inStreams[stream].orEmpty()) if (shows(part.area, null)) shown += part else kept += part
            }
            // A page whose content can't be read is left as it is.
            val drawnHere = ArrayList<XObjectCleanup.Placement>()
            if (inXObjects.isNotEmpty() && XObjectCleanup.mayDraw(page, inXObjects.keys, reaches)) {
                XObjectCleanup.placements(page)?.filterTo(drawnHere) { it.xobject in inXObjects }
            }
            // Its annotations' appearances draw them too, where they show; and a hidden annotation, or
            // a look not shown, keeps them out of sight as well, since they'd stay in the file for it.
            val unseen = identitySet<COSBase>()
            if (inXObjects.isNotEmpty()) drawnHere += XObjectCleanup.annotationPlacements(page, inXObjects.keys, unseen)
            for (xobject in drawnHere.mapTo(identitySet()) { it.xobject }.also { it += unseen }) {
                for (part in inXObjects.getValue(xobject)) {
                    val showing = drawnHere.any { it.xobject === xobject && shows(Areas.mapped(it.matrix, part.area), it.clip) }
                    if (showing) shown += part else kept += part
                }
            }
            if (kept.isNotEmpty()) keptBy[index] = kept
        }
        return keptBy.filterValues { parts -> parts.any { it !in shown } }.keys
    }

    /** Part of a content stream, image or form a box hides, as [area]; each is one object, told apart by identity. */
    private class Hidden(val area: FloatArray)

    /** What of [area] the forms it is drawn in let show, if they cut it to [clip]; null for nothing. */
    private fun shownPart(area: FloatArray, clip: FloatArray?): FloatArray? = if (clip == null) area else Areas.intersection(area, clip)

    /** The content streams [page] is drawn from. */
    private fun contentStreams(page: PDPage): List<COSBase> = when (val contents = page.cosObject.getDictionaryObject(COSName.CONTENTS)) {
        is COSStream -> listOf(contents)
        is COSArray -> (0 until contents.size()).mapNotNull { contents.getObject(it) as? COSStream }
        else -> emptyList()
    }

    /** Draws page [index] from [renderer] with [boxes] in black, and makes that picture the page's only content. */
    private fun replaceWithPicture(doc: PDDocument, page: PDPage, renderer: PdfRenderer, index: Int, boxes: List<RedactBox>, check: () -> Unit) {
        val frame = page.shownFrame()
        val (width, height) = Redaction.pixelSize(frame.shownWidth, frame.shownHeight)
        val bitmap = createBitmap(width, height)
        val image = try {
            bitmap.eraseColor(Color.WHITE)
            // Exactly as big as the page is, so the boxes, placed on the page itself, are over what they hide.
            renderer.openPage(index).use { PageRendering.render(it, bitmap, frame.shownWidth, frame.shownHeight) }
            check()
            // Without smoothing, every pixel a box touches is fully black.
            val black = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }
            val canvas = Canvas(bitmap)
            for (box in boxes) {
                val (left, top, right, bottom) = Redaction.pixelBounds(box, width, height).toList()
                canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), black)
            }
            encode(doc, bitmap, check)
        } finally {
            bitmap.recycle()
        }
        check()

        // Only the page's size and turn stay; everything else it had goes with its content.
        val cos = page.cosObject
        val media = page.mediaBox
        val crop = page.cropBox
        val rotation = page.rotation
        val kept = KEPT_PAGE_KEYS.mapNotNull { key -> cos.getItem(key)?.let { key to it } }
        cos.clear()
        for ((key, value) in kept) cos.setItem(key, value)
        page.mediaBox = media
        page.cropBox = crop
        page.rotation = rotation
        page.resources = PDResources()
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE, true).use { content ->
            val m = frame.matrix
            content.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
            content.drawImage(image, 0f, 0f, frame.shownWidth, frame.shownHeight)
        }
    }

    /**
     * The picture as a PDF image: lossless, in grey when it has no colour, unless a JPEG is much
     * smaller (a photo or a scan), which then stands in at high quality.
     */
    private fun encode(doc: PDDocument, bitmap: Bitmap, check: () -> Unit): PDImageXObject {
        val width = bitmap.width
        val height = bitmap.height
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
        check()
        val pixels = object : PixelSource {
            override val width get() = width
            override val height get() = height
            override fun readRow(y: Int, out: IntArray) = bitmap.getPixels(out, 0, width, 0, y, width, 1)
        }
        val gray = RasterEncoder.isGray(pixels, check)
        val lossless = CappedBuffer((jpeg.size * LOSSLESS_ALLOWANCE).toInt())
        val fits = try {
            RasterEncoder.writeFlate(pixels, gray, lossless, check)
            true
        } catch (_: CappedBuffer.Full) {
            false
        }
        if (!fits) return PDImageXObject(doc, ByteArrayInputStream(jpeg), COSName.DCT_DECODE, width, height, 8, PDDeviceRGB.INSTANCE)
        return PDImageXObject(doc, lossless.reader(), COSName.FLATE_DECODE, width, height, 8, if (gray) PDDeviceGray.INSTANCE else PDDeviceRGB.INSTANCE).apply {
            cosObject.setItem(
                COSName.DECODE_PARMS,
                COSDictionary().apply {
                    setInt(COSName.PREDICTOR, 15)
                    setInt(COSName.COLORS, if (gray) 1 else 3)
                    setInt(COSName.BITS_PER_COMPONENT, 8)
                    setInt(COSName.COLUMNS, width)
                },
            )
        }
    }

    /** A byte buffer that gives up once it would hold more than [capacity] bytes. */
    private class CappedBuffer(private val capacity: Int) : ByteArrayOutputStream() {
        class Full : IOException("Bigger than allowed")

        override fun write(b: Int) {
            if (count + 1 > capacity) throw Full()
            super.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (count + len > capacity) throw Full()
            super.write(b, off, len)
        }

        fun reader() = ByteArrayInputStream(buf, 0, count)
    }

    // ---- What else could leak ----

    /**
     * Removes what could still hold the hidden content once the redacted pages are pictures:
     * - the document's information (title, author, …) and XMP metadata, which often repeat its text;
     * - bookmarks, whose titles can name what was hidden, and the tags (structure tree) for screen
     *   readers, which keep copies of the text, alternate text and ActualText;
     * - form fields whose only places were on redacted pages, with their values, and XFA form data;
     *   and those that hold what was [searched] for and that no page that stays shows;
     * - what digital signatures, which no longer match, say of their signers;
     * - named destinations that lead to redacted pages, whose names can repeat their headings;
     * - attached files, JavaScript, applications' private data (PieceInfo, where some apps keep a
     *   whole editable copy), article threads, XMP metadata of pages, and page actions;
     * - the pop-ups of annotations that were removed, and what else on other pages points at them.
     * The pages without boxes otherwise stay as they were, links and form fields included.
     */
    private fun removeLeaks(doc: PDDocument, redactedPages: Set<COSBase>, removedAnnotations: Set<COSBase>, searched: List<String>) {
        doc.document.trailer.removeItem(COSName.INFO)
        val catalog = doc.documentCatalog.cosObject
        for (key in REMOVED_CATALOG_KEYS) catalog.removeItem(key)
        when (catalog.getCOSName(COSName.PAGE_MODE)) {
            COSName.getPDFName("UseOutlines"), COSName.getPDFName("UseAttachments") -> catalog.removeItem(COSName.PAGE_MODE)
            else -> Unit
        }
        // A destination stays; an action (perhaps JavaScript) goes.
        if (catalog.getDictionaryObject(COSName.OPEN_ACTION) is COSDictionary) catalog.removeItem(COSName.OPEN_ACTION)

        fun leadsToRedactedPage(destination: COSBase?): Boolean {
            val target = when (val d = destination?.resolve()) {
                is COSArray -> d
                is COSDictionary -> d.getDictionaryObject(COSName.D) as? COSArray
                else -> null
            }
            return target != null && target.size() > 0 && target.getObject(0) in redactedPages
        }
        // Only named destinations stay of the name trees: attached files and scripts go.
        val names = catalog.getCOSDictionary(COSName.NAMES)
        val dests = names?.getDictionaryObject(COSName.DESTS) as? COSDictionary
        if (dests != null) {
            pruneNameTree(dests, identitySet(), ::leadsToRedactedPage)
            catalog.setItem(COSName.NAMES, COSDictionary().apply { setItem(COSName.DESTS, dests) })
        } else {
            catalog.removeItem(COSName.NAMES)
        }
        (catalog.getDictionaryObject(COSName.DESTS) as? COSDictionary)?.let { old ->
            for (key in old.keySet().toList()) if (leadsToRedactedPage(old.getItem(key))) old.removeItem(key)
        }

        catalog.getCOSDictionary(COSName.ACRO_FORM)?.let { form ->
            form.removeItem(COSName.XFA)
            val fields = form.getCOSArray(COSName.FIELDS)
            if (fields != null) {
                pruneFields(fields, removedAnnotations, identitySet())
                dropFieldsHolding(doc, fields, searched)
            }
            if (fields == null || fields.size() == 0) {
                catalog.removeItem(COSName.ACRO_FORM)
            } else {
                // The calculation order mustn't keep removed fields alive.
                val remaining = identitySet<COSBase>()
                collectFields(fields, remaining, identitySet())
                form.getCOSArray(COSName.getPDFName("CO"))?.let { order ->
                    for (i in order.size() - 1 downTo 0) if (order.getObject(i) !in remaining) order.remove(i)
                }
                // A digital signature no longer matches anyway: what it says of the signer (name,
                // reason, place, certificate) goes, wherever its field is. The field stays, unsigned.
                for (field in remaining) {
                    if (field is COSDictionary && fieldType(field) == COSName.SIG) for (key in SIGNATURE_KEYS) field.removeItem(key)
                }
                form.removeItem(COSName.SIG_FLAGS)
            }
        }

        // Attached files go from the pages that stay too; then nothing there may still lead to an
        // annotation that went: a pop-up, the window of one, goes with it, and a reply or a field
        // forgets it. Otherwise the annotation, and an attached file with it, would stay in the file.
        val kept = doc.pages.filter { it.cosObject !in redactedPages }
        val gone = identitySet<COSBase>().apply { addAll(removedAnnotations) }
        for (page in kept) {
            val cos = page.cosObject
            for (key in REMOVED_PAGE_KEYS) cos.removeItem(key)
            val annotations = cos.getCOSArray(COSName.ANNOTS) ?: continue
            for (i in annotations.size() - 1 downTo 0) {
                val annotation = annotations.getObject(i) as? COSDictionary ?: continue
                if (annotation.getCOSName(COSName.SUBTYPE) == COSName.getPDFName("FileAttachment")) {
                    annotations.remove(i)
                    gone += annotation
                }
            }
        }
        for (page in kept) {
            val annotations = page.cosObject.getCOSArray(COSName.ANNOTS) ?: continue
            for (i in annotations.size() - 1 downTo 0) {
                val annotation = annotations.getObject(i) as? COSDictionary ?: continue
                if (annotation.getCOSName(COSName.SUBTYPE) == COSName.getPDFName("Popup") && annotation.getDictionaryObject(COSName.PARENT) in gone) {
                    annotations.remove(i)
                    continue
                }
                annotation.removeItem(COSName.getPDFName("AF"))
                for (key in listOf(COSName.getPDFName("IRT"), COSName.PARENT, COSName.getPDFName("Popup"))) {
                    if (annotation.getDictionaryObject(key) in gone) annotation.removeItem(key)
                }
            }
        }
    }

    /** A form field's type (/FT), which it may inherit from the fields above it. */
    private fun fieldType(field: COSDictionary): COSName? =
        generateSequence(field) { it.getDictionaryObject(COSName.PARENT) as? COSDictionary }.take(32).firstNotNullOfOrNull { it.getCOSName(COSName.FT) }

    /**
     * Removes from a name tree the entries [drop] says to, in every node, and the nodes left
     * empty; the first and last names each node keeps in its /Limits are set again, so those of
     * removed entries don't stay there. Returns whether [node] is left empty.
     */
    private fun pruneNameTree(node: COSDictionary, seen: MutableSet<COSBase>, drop: (COSBase?) -> Boolean, root: Boolean = true): Boolean {
        if (!seen.add(node)) return false
        var first: COSBase? = null
        var last: COSBase? = null
        val pairs = node.getCOSArray(COSName.NAMES)
        if (pairs != null) {
            var i = pairs.size() - 2
            while (i >= 0) {
                if (drop(pairs.get(i + 1))) {
                    pairs.remove(i + 1)
                    pairs.remove(i)
                }
                i -= 2
            }
            if (pairs.size() >= 2) {
                first = pairs.get(0)
                last = pairs.get(pairs.size() - 2 - pairs.size() % 2)
            }
        }
        val kids = node.getCOSArray(COSName.KIDS)
        if (kids != null) {
            for (i in kids.size() - 1 downTo 0) {
                val kid = kids.getObject(i) as? COSDictionary ?: continue
                if (pruneNameTree(kid, seen, drop, root = false)) kids.remove(i)
            }
            val limits = (0 until kids.size()).mapNotNull { (kids.getObject(it) as? COSDictionary)?.getCOSArray(COSName.LIMITS)?.takeIf { l -> l.size() == 2 } }
            if (first == null && limits.isNotEmpty()) {
                first = limits.first().get(0)
                last = limits.last().get(1)
            }
        }
        // The root has no /Limits.
        if (root || first == null || last == null) {
            node.removeItem(COSName.LIMITS)
        } else {
            node.setItem(COSName.LIMITS, COSArray().apply {
                add(first)
                add(last)
            })
        }
        return (pairs == null || pairs.size() == 0) && (kids == null || kids.size() == 0)
    }

    /**
     * Takes out of the form the fields under [kids] whose own text (see [fieldTexts]) has any of
     * [searched], and that no page that stays has a widget of: those whose widgets were all on pages
     * now pictures, and those with widgets on no page at all, which [pruneFields] leaves, as nothing
     * removed them. Their values go with them. Fields left with none under them go too. Those a
     * page that stays shows, the user left there.
     */
    private fun dropFieldsHolding(doc: PDDocument, kids: COSArray, searched: List<String>) {
        val wanted = searched.map(PageText::normalize).filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return
        fun has(text: String) = PageText.normalize(text).let { t -> wanted.any { t.contains(it, ignoreCase = true) } }
        val onPages = identitySet<COSDictionary>()
        for (page in doc.pages) page.cosObject.getCOSArray(COSName.ANNOTS)?.forEach { (it.resolve() as? COSDictionary)?.let(onPages::add) }
        val doomed = identitySet<COSBase>()
        walkFields(kids, onPages) { field, _ -> if (fieldTexts(field).any(::has)) doomed += field }
        if (doomed.isNotEmpty()) pruneFields(kids, doomed, identitySet())
    }

    /** Removes widgets in [removed] from the form's field tree, and fields left with none. */
    private fun pruneFields(kids: COSArray, removed: Set<COSBase>, seen: MutableSet<COSBase>) {
        for (i in kids.size() - 1 downTo 0) {
            val kid = kids.getObject(i) as? COSDictionary ?: continue
            if (!seen.add(kid)) {
                kids.remove(i)
                continue
            }
            val gone = kid in removed || kid.getCOSArray(COSName.KIDS)?.let { children ->
                pruneFields(children, removed, seen)
                children.size() == 0
            } ?: false
            if (gone) kids.remove(i)
        }
    }

    private fun collectFields(kids: COSArray, into: MutableSet<COSBase>, seen: MutableSet<COSBase>) {
        for (kid in kids) {
            val field = kid.resolve() as? COSDictionary ?: continue
            if (!seen.add(field)) continue
            into += field
            field.getCOSArray(COSName.KIDS)?.let { collectFields(it, into, seen) }
        }
    }

    private fun COSBase.resolve(): COSBase? = if (this is COSObject) `object` else this

    private fun <T> identitySet(): MutableSet<T> = Collections.newSetFromMap(IdentityHashMap())

    /** What a redacted page keeps besides its size, turn, picture and parent: the other page boxes and its unit. */
    private val KEPT_PAGE_KEYS = listOf(
        COSName.TYPE, COSName.PARENT, COSName.BLEED_BOX, COSName.TRIM_BOX, COSName.ART_BOX, COSName.getPDFName("UserUnit"),
    )

    private val REMOVED_CATALOG_KEYS = listOf(
        COSName.METADATA, COSName.OUTLINES, COSName.STRUCT_TREE_ROOT, COSName.MARK_INFO, COSName.AA,
        COSName.PIECE_INFO, COSName.THREADS, COSName.getPDFName("SpiderInfo"), COSName.getPDFName("AF"),
        COSName.getPDFName("Collection"), COSName.getPDFName("Perms"), COSName.getPDFName("Legal"),
        // The signers' certificates and what vouches for them.
        COSName.getPDFName("DSS"),
    )

    /** A signature field's signature and what it locks. */
    private val SIGNATURE_KEYS = listOf(COSName.V, COSName.getPDFName("Lock"), COSName.SV)

    private val REMOVED_PAGE_KEYS = listOf(
        COSName.METADATA, COSName.PIECE_INFO, COSName.AA, COSName.STRUCT_PARENTS, COSName.getPDFName("B"),
        COSName.getPDFName("AF"),
    )
}

/** The page as a viewer shows it: its crop box, turned by its rotation. */
internal fun PDPage.shownFrame(): PageFrame = cropBox.let { box -> PageFrame(box.lowerLeftX, box.lowerLeftY, box.width, box.height, rotation) }
