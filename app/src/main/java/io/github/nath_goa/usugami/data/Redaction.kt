package io.github.nath_goa.usugami.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.DrawObject
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
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

    /**
     * Draws each visible annotation's appearance into its page, then removes the annotations, as a
     * PDF viewer shows them. Returns whether any was drawn.
     */
    fun drawAnnotationsIntoPages(doc: PDDocument, check: () -> Unit): Boolean {
        // As Flatten does: fields whose values viewers draw themselves (NeedAppearances) are drawn first.
        doc.documentCatalog.getAcroForm(null)?.let { form -> Flattening.redrawFields(form, form.fieldTree.toList(), check) }
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
                        // Placed and made see-through (its /CA) as Flatten draws it.
                        val appearance = try { Flattening.appearanceOf(doc, annotation) } catch (_: IOException) { null } ?: continue
                        Flattening.draw(content, annotation, appearance)
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
     * reads text that runs up or down the page as shown. Matches found both ways count once.
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

    /** Collects each page's glyphs as text extraction reads them, and boxes the matches when the page ends. */
    private class Finder(private val query: String, private val check: () -> Unit) : PDFTextStripper() {
        val matches = ArrayList<List<RedactBox>>()
        private var text = PageText.Builder()
        private val glyphs = ArrayList<TextPosition>()

        override fun startPage(page: PDPage) {
            check()
            text = PageText.Builder()
            glyphs.clear()
        }

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            for (glyph in textPositions) {
                this.text.glyph(glyphs.size, glyph.unicode ?: "")
                glyphs += glyph
            }
        }

        override fun writeWordSeparator() = text.space()
        override fun writeLineSeparator() = text.space()
        override fun writeParagraphStart() = text.space()
        override fun writeParagraphEnd() = text.space()
        override fun startArticle(isLTR: Boolean) = text.space()
        override fun endArticle() = text.space()

        override fun endPage(page: PDPage) {
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
     * showed), with the boxes black, then removes what could leak the hidden content.
     */
    fun redact(doc: PDDocument, view: File, boxes: List<RedactBox>, check: () -> Unit) {
        val byPage = boxes.groupBy { it.page }
        require(byPage.isNotEmpty() && byPage.keys.all { it in 0 until doc.numberOfPages })
        val redacted = byPage.keys.sorted()
        val redactedPages = identitySet<COSBase>()
        val removedAnnotations = identitySet<COSBase>()
        val drawnThere = identitySet<COSBase>()
        for (index in redacted) {
            check()
            val page = doc.getPage(index)
            redactedPages += page.cosObject
            page.cosObject.getCOSArray(COSName.ANNOTS)?.forEach { (it.resolve() as? COSDictionary)?.let(removedAnnotations::add) }
            drawnXObjects(page, drawnThere)
        }

        ParcelFileDescriptor.open(view, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount != doc.numberOfPages) throw IOException("The copy has ${renderer.pageCount} pages, not ${doc.numberOfPages}")
                for (index in redacted) {
                    check()
                    replaceWithPicture(doc, doc.getPage(index), renderer, index, byPage.getValue(index), check)
                }
            }
        }

        // Images and forms that only the redacted pages drew must not stay behind, as they would
        // where pages share one list of resources: they are emptied in place.
        val usedElsewhere = identitySet<COSBase>()
        for (index in 0 until doc.numberOfPages) {
            if (index in byPage) continue
            check()
            usedXObjects(doc.getPage(index), usedElsewhere)
        }
        collectXObjects(doc.documentCatalog.cosObject.getCOSDictionary(COSName.ACRO_FORM)?.getDictionaryObject(COSName.DR), usedElsewhere)
        for (xobject in drawnThere) if (xobject !in usedElsewhere && xobject is COSStream) empty(xobject)

        check()
        removeLeaks(doc, redactedPages, removedAnnotations)
    }

    /** Draws page [index] from [renderer] with [boxes] in black, and makes that picture the page's only content. */
    private fun replaceWithPicture(doc: PDDocument, page: PDPage, renderer: PdfRenderer, index: Int, boxes: List<RedactBox>, check: () -> Unit) {
        val frame = page.shownFrame()
        val (width, height) = Redaction.pixelSize(frame.shownWidth, frame.shownHeight)
        val bitmap = createBitmap(width, height)
        val image = try {
            bitmap.eraseColor(Color.WHITE)
            renderer.openPage(index).use { it.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
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

    /** Finds the images and forms [page]'s content draws, those inside forms included. */
    private class DrawnXObjects(private val drawn: MutableSet<COSBase>, private val contexts: MutableSet<COSBase>) : PDFStreamEngine() {
        init {
            addOperator(DrawObject())
        }

        override fun processOperator(operator: Operator, operands: List<COSBase>) {
            if (operator.name == "Do") {
                val name = operands.firstOrNull() as? COSName
                val xobject = if (name != null) try { resources?.getXObject(name) } catch (_: IOException) { null } else null
                if (xobject != null) {
                    drawn += xobject.cosObject
                    (xobject as? PDFormXObject)?.resources?.cosObject?.let(contexts::add)
                }
            }
            super.processOperator(operator, operands)
        }
    }

    /**
     * Adds to [into] the images and forms a redacted [page] draws. If its content can't be read,
     * everything its resources list counts, so nothing it might have drawn is missed.
     */
    private fun drawnXObjects(page: PDPage, into: MutableSet<COSBase>) {
        val found = identitySet<COSBase>()
        try {
            DrawnXObjects(found, identitySet()).processPage(page)
            into += found
        } catch (_: Exception) {
            collectXObjects(page.resources?.cosObject, into)
        }
    }

    /**
     * Adds to [into] every image and form an unredacted [page] might draw: what its content draws,
     * and, to be safe, all those reachable from its patterns, fonts, graphics states, shadings and
     * annotation appearances, which aren't followed in detail. If its content can't be read,
     * everything its resources list counts.
     */
    private fun usedXObjects(page: PDPage, into: MutableSet<COSBase>) {
        val contexts = identitySet<COSBase>()
        // Its own resources, or those it inherits from the page tree.
        page.resources?.cosObject?.let(contexts::add)
        try {
            DrawnXObjects(into, contexts).processPage(page)
            for (context in contexts) {
                val resources = context as? COSDictionary ?: continue
                for (key in listOf(COSName.PATTERN, COSName.FONT, COSName.EXT_G_STATE, COSName.SHADING, COSName.PROPERTIES)) {
                    collectXObjects(resources.getDictionaryObject(key), into)
                }
            }
        } catch (_: Exception) {
            collectXObjects(page.resources?.cosObject, into)
        }
        page.cosObject.getCOSArray(COSName.ANNOTS)?.forEach { annotation ->
            collectXObjects((annotation.resolve() as? COSDictionary)?.getDictionaryObject(COSName.AP), into)
        }
    }

    /** Adds to [into] every image and form stream reachable from [start], not following links up to pages. */
    private fun collectXObjects(start: COSBase?, into: MutableSet<COSBase>) {
        val seen = identitySet<COSBase>()
        val pending = ArrayDeque<COSBase>()
        start?.resolve()?.let(pending::add)
        while (pending.isNotEmpty()) {
            val item = pending.removeLast()
            if (!seen.add(item)) continue
            when (item) {
                is COSDictionary -> {
                    if (item is COSStream) {
                        val subtype = item.getCOSName(COSName.SUBTYPE)
                        if (subtype == COSName.IMAGE || subtype == COSName.FORM) into += item
                    }
                    for ((key, value) in item.entrySet()) {
                        if (key == COSName.PARENT || key == COSName.P) continue
                        value.resolve()?.let(pending::add)
                    }
                }
                is COSArray -> item.forEach { value -> value.resolve()?.let(pending::add) }
            }
        }
    }

    /** Empties an image or form that only redacted pages drew: a white pixel, or nothing to draw. */
    private fun empty(stream: COSStream) {
        val image = stream.getCOSName(COSName.SUBTYPE) == COSName.IMAGE
        val keep = if (image) setOf(COSName.TYPE, COSName.SUBTYPE) else setOf(COSName.TYPE, COSName.SUBTYPE, COSName.BBOX, COSName.MATRIX, COSName.FORMTYPE)
        for (key in stream.keySet().toList()) if (key !in keep) stream.removeItem(key)
        if (image) {
            stream.setInt(COSName.WIDTH, 1)
            stream.setInt(COSName.HEIGHT, 1)
            stream.setInt(COSName.BITS_PER_COMPONENT, 8)
            stream.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY)
            stream.createOutputStream().use { it.write(0xFF) }
        } else {
            stream.setItem(COSName.RESOURCES, COSDictionary())
            stream.createOutputStream().close()
        }
    }

    /**
     * Removes what could still hold the hidden content once the redacted pages are pictures:
     * - the document's information (title, author, …) and XMP metadata, which often repeat its text;
     * - bookmarks, whose titles can name what was hidden, and the tags (structure tree) for screen
     *   readers, which keep copies of the text, alternate text and ActualText;
     * - form fields whose only places were on redacted pages, with their values, and XFA form data;
     * - named destinations that lead to redacted pages, whose names can repeat their headings;
     * - attached files, JavaScript, applications' private data (PieceInfo, where some apps keep a
     *   whole editable copy), article threads, XMP metadata of pages, and page actions;
     * - replies and pop-ups on other pages that point at annotations that were removed.
     * The pages without boxes otherwise stay as they were, links and form fields included.
     */
    private fun removeLeaks(doc: PDDocument, redactedPages: Set<COSBase>, removedAnnotations: Set<COSBase>) {
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
            if (fields != null) pruneFields(fields, removedAnnotations, identitySet())
            if (fields == null || fields.size() == 0) {
                catalog.removeItem(COSName.ACRO_FORM)
            } else {
                // The calculation order mustn't keep removed fields alive.
                val remaining = identitySet<COSBase>()
                collectFields(fields, remaining, identitySet())
                form.getCOSArray(COSName.getPDFName("CO"))?.let { order ->
                    for (i in order.size() - 1 downTo 0) if (order.getObject(i) !in remaining) order.remove(i)
                }
            }
        }

        for (page in doc.pages) {
            val cos = page.cosObject
            if (cos in redactedPages) continue
            for (key in REMOVED_PAGE_KEYS) cos.removeItem(key)
            val annotations = cos.getCOSArray(COSName.ANNOTS) ?: continue
            for (i in annotations.size() - 1 downTo 0) {
                val annotation = annotations.getObject(i) as? COSDictionary ?: continue
                if (annotation.getCOSName(COSName.SUBTYPE) == COSName.getPDFName("FileAttachment")) {
                    annotations.remove(i)
                    continue
                }
                annotation.removeItem(COSName.getPDFName("AF"))
                for (key in listOf(COSName.getPDFName("IRT"), COSName.PARENT, COSName.getPDFName("Popup"))) {
                    if (annotation.getDictionaryObject(key) in removedAnnotations) annotation.removeItem(key)
                }
            }
        }
    }

    /** Removes from a name tree the entries [drop] says to, in every node. */
    private fun pruneNameTree(node: COSDictionary, seen: MutableSet<COSBase>, drop: (COSBase?) -> Boolean) {
        if (!seen.add(node)) return
        node.getCOSArray(COSName.NAMES)?.let { pairs ->
            var i = pairs.size() - 2
            while (i >= 0) {
                if (drop(pairs.get(i + 1))) {
                    pairs.remove(i + 1)
                    pairs.remove(i)
                }
                i -= 2
            }
        }
        node.getCOSArray(COSName.KIDS)?.forEach { kid -> (kid.resolve() as? COSDictionary)?.let { pruneNameTree(it, seen, drop) } }
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
    )

    private val REMOVED_PAGE_KEYS = listOf(
        COSName.METADATA, COSName.PIECE_INFO, COSName.AA, COSName.STRUCT_PARENTS, COSName.getPDFName("B"),
        COSName.getPDFName("AF"),
    )
}

/** The page as a viewer shows it: its crop box, turned by its rotation. */
internal fun PDPage.shownFrame(): PageFrame = cropBox.let { box -> PageFrame(box.lowerLeftX, box.lowerLeftY, box.width, box.height, rotation) }
