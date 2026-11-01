package io.github.nath_goa.usugami.data.office

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.withSave
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import io.github.nath_goa.usugami.core.CssSheet
import io.github.nath_goa.usugami.core.HtmlText
import io.github.nath_goa.usugami.core.PageSize
import io.github.nath_goa.usugami.core.SimpleCss
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A bookmark in the PDF: its title, the page (0-based) and how far down it, in PDF points from the bottom. */
internal class OutlineItem(val title: String, val page: Int, val top: Float, val children: List<OutlineItem>)

/** What goes into the PDF besides its pages: bookmarks, and the book's title, author and language. */
internal class BookInfo(val outline: List<OutlineItem>, val title: String?, val author: String?, val language: String?) {
    /** Writes this into [doc], which has the book's pages. */
    fun applyTo(doc: PDDocument) {
        val pages = doc.numberOfPages
        if (outline.isNotEmpty() && pages > 0) {
            val root = PDDocumentOutline()
            fun add(parent: PDOutlineNode, item: OutlineItem) {
                val node = PDOutlineItem()
                node.title = item.title.take(MAX_TITLE)
                node.destination = PDPageXYZDestination().apply {
                    page = doc.getPage(item.page.coerceIn(0, pages - 1))
                    left = 0
                    top = item.top.roundToInt()
                }
                parent.addLast(node)
                item.children.forEach { add(node, it) }
            }
            outline.forEach { add(root, it) }
            doc.documentCatalog.documentOutline = root
        }
        title?.let { doc.documentInformation.title = it.take(MAX_TITLE) }
        author?.let { doc.documentInformation.author = it.take(MAX_TITLE) }
        language?.let { doc.documentCatalog.language = it.take(35) }
    }

    private companion object {
        const val MAX_TITLE = 500
    }
}

/**
 * EPUB e-books to PDF, laid out on the phone with the Word converter's text engine: the book's
 * documents in reading order, each starting on a new page, on A5 or A4 pages with book-like
 * margins and type. The cover comes first, on a page of its own; the table of contents becomes
 * the PDF's bookmarks. Fixed-layout books (comics, picture books) get one page per document, as
 * best they can. See [XhtmlReader] for what is read from each document.
 */
internal class EpubConverter(
    private val archive: EpubArchive,
    private val book: EpubBook,
    private val pdf: PagedPdf,
    private val images: OfficeImages,
    pageSize: PageSize,
    private val check: () -> Unit,
    private val progress: (chapter: Int, total: Int) -> Unit,
) {
    private val small = pageSize.shortSide < 500.0
    private val portrait = pageSize.shortSide.toFloat() to pageSize.longSide.toFloat()
    /** The book's font size, in points: a paperback's on A5, a little bigger on A4. */
    private val base = if (small) 11f else 12f
    private val marginX = if (small) 44f else 66f
    private val marginTop = if (small) 46f else 64f
    private val marginBottom = if (small) 52f else 72f

    private var pageW = portrait.first
    private var pageH = portrait.second
    private val textW get() = pageW - 2 * marginX
    private val textH get() = pageH - marginTop - marginBottom

    private class Anchor(val page: Int, val top: Float)
    private val anchors = HashMap<String, Anchor>()
    private val headings = ArrayList<Pair<String, String>>()
    private val sheets = HashMap<String, CssSheet?>()
    private val sizes = HashMap<String, IntArray?>()
    private var coverDrawn = false
    private var coverDocument: String? = null

    /** Converts the book; returns what goes into the PDF besides the pages. */
    fun convert(): BookInfo {
        // Documents read only when linked to (notes, say) go after the main text, rather than nowhere.
        val documents = book.spine.filter { it.linear } + book.spine.filterNot { it.linear }
        val cover = book.coverImage?.takeIf { imageSize(it) != null }
        for ((index, document) in documents.withIndex()) {
            check()
            progress(index + 1, documents.size)
            convertDocument(document, index == 0, cover)
        }
        finish()
        if (pdf.pageCount == 0) throw OfficeNothingToConvert()
        coverDocument?.let { if (coverDrawn) anchors.putIfAbsent(it, Anchor(0, pageH)) }
        val toc = book.toc(archive, check)
        val outline = outline(toc).ifEmpty {
            headings.mapNotNull { (path, title) -> anchors[path]?.let { OutlineItem(title, it.page, it.top, emptyList()) } }
        }
        return BookInfo(outline, book.title, book.creator, book.language)
    }

    private fun outline(entries: List<TocEntry>): List<OutlineItem> = entries.flatMap { entry ->
        val children = outline(entry.children)
        val anchor = entry.path?.let { path -> entry.fragment?.let { anchors["$path#$it"] } ?: anchors[path] }
        // An entry that leads nowhere in the PDF gives way to its children.
        if (anchor == null || entry.title.isEmpty()) children else listOf(OutlineItem(entry.title, anchor.page, anchor.top, children))
    }

    private fun convertDocument(document: SpineItem, first: Boolean, cover: String?) {
        if (document.mediaType.startsWith("image/")) {
            // A picture straight in the reading order, as some comics have.
            val pixels = imageSize(document.path) ?: return
            if (document.path == cover) coverDrawn = true
            startDocument(document.path)
            fullPage(document.path, pixels[0].toFloat(), pixels[1].toFloat(), emptyList())
            return
        }
        val buffered = first || !document.linear || book.fixedLayout
        val buffer = ArrayList<BookEntry>()
        var streaming = !buffered
        fun showsCover() = buffer.any { (it.item as? BookImage)?.path == cover }
        fun stream() {
            if (first && cover != null && !coverDrawn && !showsCover()) drawCover(cover)
            streaming = true
            startDocument(document.path)
            buffer.forEach(::add)
            buffer.clear()
        }
        if (streaming) startDocument(document.path)
        val reader = XhtmlReader(document.path, base, textW, ::sheet, ::imageSize, images, check) { entry ->
            if (streaming) {
                add(entry)
            } else {
                buffer += entry
                // A long document is no cover or picture page: on with it, without holding it all.
                if (buffer.size > BUFFER) stream()
            }
        }
        archive.open(document.path)?.use(reader::read)
        reader.firstHeading?.let { headings += document.path to it }
        if (streaming) return
        val content = buffer.filter { it.item !is BookBreak }
        val picturesOnly = content.isNotEmpty() && content.all { it.item is BookImage }
        when {
            book.fixedLayout -> {
                if (first && cover != null && !coverDrawn && !showsCover()) drawCover(cover)
                startDocument(document.path)
                fixedPage(content, reader.viewport)
            }
            first && picturesOnly -> {
                // The cover page: its pictures fill the page.
                if (showsCover()) coverDrawn = true
                startDocument(document.path)
                for (entry in content) (entry.item as BookImage).let { fullPage(it.path, it.width, it.height, entry.ids) }
            }
            // A cover page read only when linked to: the cover is drawn already.
            !document.linear && picturesOnly && content.all { (it.item as BookImage).path == cover } && coverDrawn -> coverDocument = document.path
            else -> stream()
        }
        if (first && showsCover()) coverDocument = document.path
    }

    private fun drawCover(path: String) {
        val pixels = imageSize(path) ?: return
        fullPage(path, pixels[0].toFloat(), pixels[1].toFloat(), emptyList())
        coverDrawn = true
    }

    private fun sheet(path: String): CssSheet? = sheets.getOrPut(path) {
        try {
            archive.open(path)?.use { input ->
                val bytes = input.readNBytesCompat(MAX_CSS)
                SimpleCss.parse(String(bytes, Charsets.UTF_8).removePrefix("\uFEFF"))
            }
        } catch (e: Exception) {
            rethrowCancel(e)
            null
        }
    }

    private fun imageSize(path: String): IntArray? = sizes.getOrPut(path) {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            archive.open(path)?.use { BitmapFactory.decodeStream(it, null, options) }
            if (options.outWidth > 0 && options.outHeight > 0) intArrayOf(options.outWidth, options.outHeight) else null
        } catch (e: Exception) {
            rethrowCancel(e)
            null
        }
    }

    // ---- Pages ----

    private class Placed(val block: Block, val before: Int, val keepNext: Boolean, val ids: List<String>)

    private var canvas: Canvas? = null
    private var top = 0f
    private var bottom = 0f
    private var y = 0f
    private var empty = true
    /** At the top of a page, space before a block is kept only after a break the book asked for. */
    private var keepSpaceBefore = true
    private var breakBeforeNext = false
    private val pending = ArrayList<Placed>()
    private var documentPath = ""
    /** A document whose first block hasn't been placed yet, and earlier ones that had nothing to show. */
    private var documentStart: String? = null
    private val emptyDocuments = ArrayList<String>()

    private val left get() = marginX.units
    private val textWidth get() = textW.units
    private val textHeight get() = textH.units

    private fun startDocument(path: String) {
        flushPending()
        documentStart?.let { emptyDocuments += it }
        documentStart = path
        documentPath = path
        breakBeforeNext = true
    }

    private fun add(entry: BookEntry) {
        check()
        if (entry.item == BookBreak) {
            flushPending()
            breakBeforeNext = true
            return
        }
        val block = block(entry.item, textWidth.toInt()) ?: return
        val ids = entry.ids.map { "$documentPath#$it" }
        place(Placed(block, entry.before.units.toInt(), (entry.item as? BookPara)?.heading?.let { it > 0 } == true, ids))
    }

    private fun place(item: Placed) {
        if (breakBeforeNext) {
            breakBeforeNext = false
            if (canvas != null && !empty) newPage(natural = false)
            keepSpaceBefore = true
        }
        // Headings go with what follows them: they move on to the next page together.
        if (item.keepNext && pending.size < 4) {
            pending += item
            return
        }
        if (pending.isNotEmpty()) {
            ensurePage()
            val need = pending.sumOf { it.before + it.block.height } + item.before + min(item.block.height, (base * 4).units.toInt())
            if (!empty && y + need > bottom && need <= textHeight) newPage(natural = true)
            flushPending()
        }
        put(item)
    }

    private fun flushPending() {
        val waiting = pending.toList()
        pending.clear()
        waiting.forEach(::put)
    }

    private fun put(item: Placed) {
        ensurePage()
        var gap = if (empty) (if (keepSpaceBefore) min(item.before, (textHeight / 3).toInt()) else 0) else item.before
        var block = item.block
        var first = true
        while (true) {
            val space = (bottom - y - gap).toInt()
            if (block.height <= space) {
                draw(block, item, gap, first)
                y += gap + block.height
                break
            }
            val parts = block.split(space, force = empty)
            if (parts == null) {
                newPage(natural = true)
                gap = 0
                continue
            }
            draw(parts.first, item, gap, first)
            first = false
            val rest = parts.second
            if (rest == null) {
                y += gap + parts.first.height
                break
            }
            newPage(natural = true)
            gap = 0
            block = rest
        }
        empty = false
    }

    private fun draw(block: Block, item: Placed, gap: Int, first: Boolean) {
        val c = canvas ?: return
        if (first) mark(item.ids, y + gap)
        block.draw(c, left, y + gap)
    }

    /** Remembers where the elements [ids] start (at [at] units down the page), and the document, for bookmarks. */
    private fun mark(ids: List<String>, at: Float) {
        val page = pdf.pageCount
        documentStart?.let { path ->
            val start = Anchor(page, pageH)
            anchors.putIfAbsent(path, start)
            emptyDocuments.forEach { anchors.putIfAbsent(it, start) }
            emptyDocuments.clear()
            documentStart = null
        }
        if (ids.isNotEmpty()) {
            val anchor = Anchor(page, pageH - at / U)
            ids.forEach { anchors.putIfAbsent(it, anchor) }
        }
    }

    private fun ensurePage() {
        if (canvas == null) startPage()
    }

    private fun newPage(natural: Boolean) {
        finishPage()
        startPage()
        keepSpaceBefore = !natural
    }

    private fun startPage() {
        check()
        canvas = pdf.startPage(pageW, pageH)
        top = marginTop.units
        bottom = (pageH - marginBottom).units
        y = top
        empty = true
    }

    private fun finishPage() {
        if (canvas == null) return
        pdf.finishPage()
        canvas = null
    }

    private fun finish() {
        flushPending()
        finishPage()
        documentStart?.let { emptyDocuments += it }
        documentStart = null
        if (pdf.pageCount > 0) emptyDocuments.forEach { anchors.putIfAbsent(it, Anchor(pdf.pageCount - 1, pageH)) }
        emptyDocuments.clear()
    }

    /** A picture filling a page of its own, such as the cover: [width] × [height] in any unit, for its shape. */
    private fun fullPage(path: String, width: Float, height: Float, ids: List<String>) {
        flushPending()
        finishPage()
        val c = pdf.startPage(pageW, pageH)
        canvas = c
        mark(ids.map { "$documentPath#$it" }, 0f)
        val scale = min(pageW / width, pageH / height)
        val w = width * scale
        val h = height * scale
        val x = (pageW - w) / 2
        val yTop = (pageH - h) / 2
        ImagePicture(images, path, w, h, null).draw(c, RectF(x.units, yTop.units, (x + w).units, (yTop + h).units))
        finishPage()
        breakBeforeNext = false
        empty = true
    }

    /**
     * A fixed-layout document on one page: a single picture fills it; anything else is laid out and,
     * when it doesn't fit, shrunk until it does. The page turns sideways for a landscape [viewport].
     */
    private fun fixedPage(content: List<BookEntry>, viewport: Pair<Int, Int>?) {
        flushPending()
        finishPage()
        val landscape = viewport != null && viewport.first > viewport.second
        pageW = if (landscape) portrait.second else portrait.first
        pageH = if (landscape) portrait.first else portrait.second
        try {
            if (content.isEmpty()) return
            val single = (content.singleOrNull()?.item as? BookImage)
            if (single != null) return fullPage(single.path, single.width, single.height, content[0].ids)
            var width = textWidth.toInt()
            var stack = stack(content, width)
            var scale = 1f
            if (stack.height > textHeight) {
                scale = textHeight / stack.height
                width = (textWidth / scale).toInt()
                stack = stack(content, width)
                scale = min(textWidth / width, textHeight / max(1, stack.height))
            }
            startPage()
            mark(content.flatMap { e -> e.ids.map { "$documentPath#$it" } }, top)
            canvas!!.withSave {
                translate(left, top)
                scale(scale, scale)
                stack.draw(this, 0f, 0f)
            }
            finishPage()
            breakBeforeNext = false
        } finally {
            pageW = portrait.first
            pageH = portrait.second
        }
    }

    // ---- Blocks ----

    private fun stack(entries: List<BookEntry>, width: Int): StackBlock =
        StackBlock(entries.mapNotNull { e -> block(e.item, width)?.let { Spaced(it, e.before.units.toInt(), 0) } })

    private fun block(item: BookItem, width: Int): Block? = when (item) {
        is BookPara -> paragraph(item, width)
        is BookImage -> picture(item, width)
        is BookTable -> table(item, width)
        is BookRule -> RuleBlock(width, (base * 1.2f).units.toInt())
        BookBreak -> null
    }

    private fun paragraph(item: BookPara, width: Int): ParagraphBlock {
        val s = item.style
        val left = s.left.units
        val label = item.label?.let { (text, style) ->
            val paint = style.paint()
            val x = left - paint.measureText(text) - style.size.units * 0.45f
            Label(text, style, max(0f, x).toInt())
        }
        return ParagraphBlock(
            ParagraphSpec(
                text = item.text,
                base = item.base,
                width = width,
                align = item.style.align,
                firstIndent = max(0f, left + s.textIndent.units).toInt(),
                indent = left.toInt(),
                rightIndent = s.right.units.toInt(),
                label = label,
                rtl = s.rtl,
            ),
        )
    }

    private fun picture(item: BookImage, width: Int): Block {
        val s = item.style
        val available = max(1f, width - s.left.units - s.right.units)
        var w = min(available, item.share?.let { it * available } ?: item.width.units)
        var h = item.height.units * w / max(0.01f, item.width.units)
        val most = textHeight * 0.98f
        if (h > most) {
            w *= most / h
            h = most
        }
        val x = s.left.units + (available - w) / 2
        return PictureBlock(ImagePicture(images, item.path, w / U, h / U, null), max(1, w.toInt()), max(1, h.toInt()), x)
    }

    private fun table(item: BookTable, width: Int): Block {
        val s = item.style
        val columns = item.rows.maxOf { row -> row.sumOf { it.span } }.coerceIn(1, 64)
        // Columns share the width by how much text they hold.
        val weights = FloatArray(columns)
        val occupied = IntArray(columns)
        val placedRows = item.rows.map { row ->
            var c = 0
            val placed = row.mapNotNull { cell ->
                while (c < columns && occupied[c] > 0) c++
                if (c >= columns) return@mapNotNull null
                val span = min(cell.span, columns - c)
                val length = cell.items.sumOf { e -> ((e.item as? BookPara)?.text?.length ?: 12) }.toFloat()
                for (k in c until c + span) weights[k] = max(weights[k], min(length, 400f) / span)
                if (cell.rowSpan > 1) for (k in c until c + span) occupied[k] = cell.rowSpan
                val at = c
                c += span
                Triple(at, span, cell)
            }
            for (k in 0 until columns) if (occupied[k] > 0) occupied[k]--
            placed
        }
        val available = max(1f, width - s.left.units - s.right.units)
        val widths = HtmlText.columnWidths(weights.toList(), available)
        val starts = widths.runningFold(0f) { acc, w -> acc + w }
        val pad = (base * 0.3f).units.toInt()
        val border = Border(0.5f, BORDER)
        val rows = placedRows.map { row ->
            RowBlock(
                row.map { (at, span, cell) ->
                    val cellWidth = starts[at + span] - starts[at]
                    val inner = max(1, (cellWidth - 2 * pad).toInt())
                    val content = cell.items.mapNotNull { e -> block(e.item, inner)?.let { Spaced(it, e.before.units.toInt(), 0) } }
                    CellBox(
                        x = s.left.units + starts[at], width = cellWidth, content = content.takeIf { it.isNotEmpty() }?.let(::StackBlock),
                        fill = if (cell.header) HEADER else null, top = border, left = border, bottom = border, right = border,
                        verticalAlign = 0, padLeft = pad, padTop = pad, padRight = pad, padBottom = pad,
                    )
                },
                0, false,
            )
        }
        val headerRows = item.rows.takeWhile { row -> row.isNotEmpty() && row.all { it.header } }.size.takeIf { it < item.rows.size } ?: 0
        return TableBlock(rows, headerRows, 0f)
    }

    /** A picture on a line of its own, [x] units from the left. */
    private class PictureBlock(private val picture: Picture, private val width: Int, override val height: Int, private val x: Float) : Block {
        override fun draw(canvas: Canvas, x: Float, y: Float) = picture.draw(canvas, RectF(x + this.x, y, x + this.x + width, y + height))
        override fun split(space: Int, force: Boolean): Pair<Block, Block?>? = if (force) this to null else null
    }

    /** A short centred line, as books show a horizontal rule. */
    private class RuleBlock(private val width: Int, override val height: Int) : Block {
        override fun draw(canvas: Canvas, x: Float, y: Float) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = BORDER
                strokeWidth = 0.6f.units
            }
            val middle = y + height / 2f
            canvas.drawLine(x + width * 0.35f, middle, x + width * 0.65f, middle, paint)
        }

        override fun split(space: Int, force: Boolean): Pair<Block, Block?>? = if (force) this to null else null
    }

    private companion object {
        const val BUFFER = 48
        const val MAX_CSS = 512 * 1024
        val BORDER = Color.rgb(0x9E, 0x9E, 0x9E)
        val HEADER = Color.rgb(0xF0, 0xF0, 0xF0)
    }
}

/** Up to [limit] bytes of the stream (InputStream.readNBytes is Android 13 and newer). */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val n = read(buffer, 0, min(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
