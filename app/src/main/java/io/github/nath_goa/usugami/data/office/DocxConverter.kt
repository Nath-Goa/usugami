package io.github.nath_goa.usugami.data.office

import android.graphics.Canvas
import android.graphics.RectF
import io.github.nath_goa.usugami.core.OfficeColors
import io.github.nath_goa.usugami.core.OfficeUnits
import io.github.nath_goa.usugami.core.SymbolFonts
import io.github.nath_goa.usugami.core.TableColumns
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Word documents (.docx) to PDF, read and laid out on the phone.
 *
 * What converts: page size and margins (per section, portrait or landscape); paragraphs with their
 * alignment, spacing, indents and line spacing, shading, "keep with next" and page breaks before;
 * text that is bold, italic, underlined, struck through, coloured, highlighted, raised or lowered,
 * in capitals, or any size; Word's styles (document defaults, paragraph and character styles,
 * headings, Title, table styles' borders and spacing) and theme fonts; bulleted and numbered
 * lists, at every level; tables, with their column widths, merged cells, borders and shading,
 * header rows repeated on each page, and rows split across pages; pictures, in the text or floating;
 * page breaks and section breaks; headers and footers, with page numbers; the text of text boxes,
 * equations and fields; links, as text.
 *
 * What is simplified: fonts become the phone's (sans-serif, serif or monospace), so lines can break
 * in other places than in Word. Text doesn't wrap around floating pictures: they go on lines of
 * their own, or over the text when Word puts them in front of or behind it. Text boxes' text
 * follows the paragraph they're anchored to. Columns become a single column, right and centre tab
 * stops are left tab stops, and charts, SmartArt and drawings Android can't decode (such as EMF and
 * WMF) become light grey boxes. Footnotes, endnotes, comments and deleted text are left out.
 */
internal class DocxConverter(
    private val pkg: OfficePackage,
    private val pdf: PagedPdf,
    private val images: OfficeImages,
    private val check: () -> Unit,
    private val progress: (page: Int) -> Unit,
) {
    private lateinit var theme: OfficeTheme
    private lateinit var styles: DocxStyles
    private lateinit var numbering: DocxNumbering
    private lateinit var sections: List<Section>
    private lateinit var main: String
    private val headers = HashMap<String, List<DocItem>>()

    /** Converts the document; returns the number of pages. */
    fun convert(): Int {
        main = pkg.mainPart() ?: "word/document.xml"
        theme = OfficeTheme.read(pkg, pkg.targetOfType(main, "/theme"), check)
        styles = DocxStyles.read(pkg, pkg.targetOfType(main, "/styles"), theme, check)
        numbering = DocxNumbering.read(pkg, pkg.targetOfType(main, "/numbering"), theme, check)
        sections = readSections()
        val flow = Flow()
        val reader = Reader(main)
        pkg.xml(main, check)?.use { x ->
            x.children {
                if (x.name == "body") reader.blocks(x, null) { item ->
                    check()
                    flow.add(item)
                }
            }
        }
        flow.finish()
        return pdf.pageCount
    }

    // ---- What the document is made of, as read ----

    private sealed interface DocItem

    private class DocPara(
        val props: ParaProps,
        val inlines: List<Inline>,
        val label: String?,
        val labelStyle: TextStyle,
        val labelSuffix: String,
        val mark: TextStyle,
        val floats: List<Floating>,
    ) : DocItem

    private class DocTable(val props: TableProps, val grid: List<Float>, val rows: List<DocRow>) : DocItem
    private class DocRow(val cells: List<DocCell>, val height: Float?, val exact: Boolean, val header: Boolean, val gridBefore: Int)
    private class DocCell(
        val span: Int,
        val vMerge: String?,
        val fill: Int?,
        val borders: Borders?,
        val verticalAlign: Int,
        val margins: List<Float?>?,
        val content: List<DocItem>,
    )

    private data object PageBreak : DocItem
    private data object SectionEnd : DocItem

    private sealed interface Inline
    private class TextInline(val text: String, val style: TextStyle) : Inline
    private class PageNumberInline(val style: TextStyle) : Inline
    private class PictureInline(val picture: Picture, val width: Float, val height: Float, val style: TextStyle) : Inline

    /** Where a floating picture goes: relative to [from] ("page", "margin", "paragraph"...), by [offset] points or [align]. */
    private class Position(val from: String, val offset: Float, val align: String?)

    /** A floating picture. [overText] when Word puts it in front of or behind the text, rather than wrapping text around it. */
    private class Floating(val picture: Picture, val width: Float, val height: Float, val h: Position, val v: Position, val overText: Boolean)

    private class Section(
        val width: Float,
        val height: Float,
        val top: Float,
        val bottom: Float,
        val left: Float,
        val right: Float,
        val headerDistance: Float,
        val footerDistance: Float,
        val type: String,
        val headers: Map<String, String>,
        val footers: Map<String, String>,
        val titlePage: Boolean,
        val pageStart: Int?,
    )

    // ---- Sections: read ahead, since a section's page size is written at its end ----

    private fun readSections(): List<Section> {
        val found = ArrayList<Section>()
        pkg.xml(main, check)?.use { x ->
            fun walk() {
                x.children {
                    when (x.name) {
                        "sectPr" -> found += readSection(x, found.lastOrNull())
                        "body", "p", "pPr", "sdt", "sdtContent", "customXml", "ins" -> walk()
                    }
                }
            }
            walk()
        }
        return found.ifEmpty { listOf(DEFAULT_SECTION) }
    }

    private fun readSection(x: OfficeXml, previous: Section?): Section {
        var width = 612f
        var height = 792f
        val margin = floatArrayOf(72f, 72f, 72f, 72f, 36f, 36f) // top, bottom, left, right, header, footer
        var type = "nextPage"
        val headers = HashMap(previous?.headers.orEmpty())
        val footers = HashMap(previous?.footers.orEmpty())
        var titlePage = false
        var pageStart: Int? = null
        x.children {
            when (x.name) {
                "pgSz" -> {
                    x.intAttr("w")?.takeIf { it > 0 }?.let { width = it / 20f }
                    x.intAttr("h")?.takeIf { it > 0 }?.let { height = it / 20f }
                }
                "pgMar" -> {
                    listOf("top", "bottom", "left", "right", "header", "footer").forEachIndexed { i, key ->
                        x.intAttr(key)?.let { margin[i] = abs(it) / 20f }
                    }
                    x.intAttr("gutter")?.let { margin[2] += abs(it) / 20f }
                }
                "type" -> type = x.attr("val") ?: "nextPage"
                "headerReference" -> pkg.target(main, x.relId())?.let { headers[x.attr("type") ?: "default"] = it }
                "footerReference" -> pkg.target(main, x.relId())?.let { footers[x.attr("type") ?: "default"] = it }
                "titlePg" -> titlePage = x.flag()
                "pgNumType" -> pageStart = x.intAttr("start")
            }
        }
        width = width.coerceIn(72f, 14_400f)
        height = height.coerceIn(72f, 14_400f)
        // Keep at least an inch for the text, whatever the margins say.
        if (margin[2] + margin[3] > width - 72f) margin[2] = 36f.also { margin[3] = 36f }
        if (margin[0] + margin[1] > height - 72f) margin[0] = 36f.also { margin[1] = 36f }
        return Section(width, height, margin[0], margin[1], margin[2], margin[3], margin[4], margin[5], type, headers, footers, titlePage, pageStart)
    }

    // ---- Reading ----

    /** Reads block content from part [part] (the document, or a header or footer), whose relationships its pictures use. */
    private inner class Reader(private val part: String) {
        /** Fields being read, outermost first: their instruction, and whether their result has started. */
        private val fields = ArrayList<Field>()

        private inner class Field {
            val instruction = StringBuilder()
            var inResult = false
            var isPage = false
        }

        fun blocks(x: OfficeXml, tableStyle: String?, emit: (DocItem) -> Unit) = x.children { block(x, tableStyle, emit) }

        private fun block(x: OfficeXml, tableStyle: String?, emit: (DocItem) -> Unit) {
            when (x.name) {
                "p" -> if (x.ns == Ns.WORD) paragraph(x, tableStyle, emit)
                "tbl" -> if (x.ns == Ns.WORD) emit(table(x))
                "sdt" -> x.children { if (x.name == "sdtContent") blocks(x, tableStyle, emit) }
                "customXml", "ins", "moveTo" -> blocks(x, tableStyle, emit)
                "AlternateContent" -> x.children { if (x.name == "Choice") blocks(x, tableStyle, emit) }
            }
        }

        private inner class ParaBuilder(val base: RunProps) {
            val segments = arrayListOf(ArrayList<Inline>())
            val floats = ArrayList<Floating>()
            val boxes = ArrayList<DocItem>()
            fun add(inline: Inline) = segments.last().add(inline)
        }

        private fun paragraph(x: OfficeXml, tableStyle: String?, emit: (DocItem) -> Unit) {
            var direct = ParaProps()
            var endsSection = false
            var out: ParaBuilder? = null
            fun builder() = out ?: ParaBuilder(styles.paragraphRun(direct.style, tableStyle)).also { out = it }
            x.children {
                if (x.name == "pPr" && x.ns == Ns.WORD) direct = ParaProps.read(x, theme) { endsSection = true }
                else content(x, builder())
            }
            val built = builder()
            // Formatting: direct, then the list level's indents, then the style's.
            val styled = styles.resolvePara(ParaProps(style = direct.style), tableStyle)
            val listed = direct.over(styled)
            val numId = listed.numId?.takeIf { it > 0 }
            val level = listed.level ?: 0
            val listLevel = numId?.let { numbering.level(it, level) }
            val props = direct.over(listLevel?.para).over(styled)
            val markRun = (direct.mark ?: RunProps()).over(built.base)
            val visible = built.segments.any { it.isNotEmpty() } || built.floats.isNotEmpty() || markRun.hidden != true
            if (visible) {
                val label = numId?.let { numbering.next(it, level) }
                val labelStyle = (listLevel?.run ?: RunProps()).over(markRun).copy(underline = false, background = null).toStyle(theme)
                val mark = markRun.toStyle(theme)
                built.segments.forEachIndexed { i, inlines ->
                    if (i > 0) emit(PageBreak)
                    // The text before a page break; the paragraph mark always goes after it.
                    if (i == 0 && inlines.isEmpty() && built.segments.size > 1 && built.floats.isEmpty()) return@forEachIndexed
                    val first = i == 0
                    emit(
                        DocPara(
                            if (first) props else props.copy(before = 0f, pageBreakBefore = false),
                            inlines,
                            if (first) label else null,
                            labelStyle,
                            listLevel?.suffix ?: "tab",
                            mark,
                            if (first) built.floats else emptyList(),
                        ),
                    )
                }
            }
            built.boxes.forEach(emit)
            if (endsSection) emit(SectionEnd)
        }

        /** Paragraph content: runs, and what can hold runs. */
        private fun content(x: OfficeXml, out: ParaBuilder) {
            when (x.name) {
                "r" -> run(x, out)
                "hyperlink", "ins", "moveTo", "smartTag", "customXml", "dir", "bdo", "sdtContent" -> x.children { content(x, out) }
                "sdt" -> x.children { if (x.name == "sdtContent") x.children { content(x, out) } }
                "AlternateContent" -> x.children { if (x.name == "Choice") x.children { content(x, out) } }
                "fldSimple" -> if (isPageField(x.attr("instr"))) {
                    out.add(PageNumberInline(out.base.toStyle(theme)))
                } else {
                    x.children { content(x, out) }
                }
                "oMathPara", "oMath" -> {
                    val text = StringBuilder()
                    allText(x, text)
                    if (text.isNotEmpty()) out.add(TextInline(text.toString(), out.base.toStyle(theme)))
                }
            }
        }

        private fun allText(x: OfficeXml, into: StringBuilder) {
            x.children { if (x.name == "t") into.append(x.text()) else allText(x, into) }
        }

        private fun run(x: OfficeXml, out: ParaBuilder) {
            var props = out.base
            var cached: TextStyle? = null
            fun style() = cached ?: props.toStyle(theme).also { cached = it }
            fun shown() = props.hidden != true && fields.none { !it.inResult || it.isPage }
            fun text(s: String) {
                if (s.isNotEmpty() && shown()) out.add(TextInline(s, style()))
            }

            fun child() {
                when (x.name) {
                    "rPr" -> {
                        val direct = RunProps.read(x, theme)
                        props = direct.over(styles.run(direct.style)).over(out.base)
                        cached = null
                    }
                    "t" -> text(x.text().replace('\n', ' ').replace('\r', ' '))
                    "tab", "ptab" -> text("\t")
                    "br" -> if (x.attr("type") == "page") {
                        if (props.hidden != true) out.segments.add(ArrayList())
                    } else {
                        text("\n")
                    }
                    "cr" -> text("\n")
                    "noBreakHyphen" -> text("\u2011")
                    "softHyphen" -> text("\u00AD")
                    "sym" -> x.attr("char")?.toIntOrNull(16)?.let { code ->
                        text(SymbolFonts.bullet(x.attr("font"), String(Character.toChars(code))))
                    }
                    "drawing" -> if (shown()) drawing(x, out, style())
                    "pict", "object" -> if (shown()) vml(x, out, style())
                    "fldChar" -> when (x.attr("fldCharType")) {
                        "begin" -> fields += Field()
                        "separate" -> fields.lastOrNull()?.let { field ->
                            field.inResult = true
                            if (isPageField(field.instruction.toString()) && fields.count { !it.inResult || it.isPage } == 0) {
                                out.add(PageNumberInline(style()))
                                field.isPage = true
                            }
                        }
                        "end" -> fields.removeLastOrNull()?.let { field ->
                            if (!field.inResult && isPageField(field.instruction.toString())) out.add(PageNumberInline(style()))
                        }
                    }
                    "instrText" -> fields.lastOrNull()?.instruction?.append(x.text())
                    "AlternateContent" -> x.children { if (x.name == "Choice") x.children { child() } }
                }
            }
            x.children { child() }
        }

        private fun isPageField(instruction: String?) =
            instruction?.trim()?.split(' ', '\\')?.firstOrNull()?.equals("PAGE", ignoreCase = true) == true

        /** A DrawingML picture or shape (w:drawing), in the text or floating. */
        private fun drawing(x: OfficeXml, out: ParaBuilder, style: TextStyle) {
            x.children {
                val inline = x.name == "inline"
                if (!inline && x.name != "anchor") return@children
                var width = 0f
                var height = 0f
                var caption: String? = null
                var path: String? = null
                var crop: FloatArray? = null
                var standIn = false
                var wrapNone = false
                var h = Position("column", 0f, null)
                var v = Position("paragraph", 0f, null)
                fun scan() {
                    x.children {
                        when (x.name) {
                            "blip" -> if (path == null) path = pkg.target(part, x.relId("embed"))
                            "srcRect" -> crop = floatArrayOf(
                                (x.intAttr("l") ?: 0) / 100_000f, (x.intAttr("t") ?: 0) / 100_000f,
                                (x.intAttr("r") ?: 0) / 100_000f, (x.intAttr("b") ?: 0) / 100_000f,
                            )
                            "txbxContent" -> blocks(x, null) { out.boxes += it }
                            "graphicData" -> {
                                val uri = x.attr("uri").orEmpty()
                                if ("chart" in uri || "diagram" in uri) standIn = true
                                scan()
                            }
                            else -> scan()
                        }
                    }
                }
                x.children {
                    when (x.name) {
                        "extent" -> {
                            width = OfficeUnits.emu(x.longAttr("cx") ?: 0L)
                            height = OfficeUnits.emu(x.longAttr("cy") ?: 0L)
                        }
                        "docPr" -> caption = x.attr("descr")?.takeIf { it.isNotBlank() } ?: x.attr("title")?.takeIf { it.isNotBlank() }
                        "wrapNone" -> wrapNone = true
                        "positionH", "positionV" -> {
                            val horizontal = x.name == "positionH"
                            val from = x.attr("relativeFrom") ?: if (horizontal) "column" else "paragraph"
                            var offset = 0f
                            var align: String? = null
                            x.children {
                                when (x.name) {
                                    "posOffset" -> offset = OfficeUnits.emu(x.text().trim().toLongOrNull() ?: 0L)
                                    "align" -> align = x.text().trim()
                                }
                            }
                            if (horizontal) h = Position(from, offset, align) else v = Position(from, offset, align)
                        }
                        "graphic" -> scan()
                    }
                }
                val picture: Picture? = path?.let { ImagePicture(images, it, width, height, crop) } ?: if (standIn) StandInPicture(caption) else null
                if (picture == null || width <= 0f || height <= 0f) return@children
                if (inline) out.add(PictureInline(picture, width, height, style))
                else out.floats += Floating(picture, width, height, h, v, wrapNone)
            }
        }

        /** An older VML picture or text box (w:pict, w:object). */
        private fun vml(x: OfficeXml, out: ParaBuilder, style: TextStyle) {
            var width = 0f
            var height = 0f
            var path: String? = null
            fun scan() {
                x.children {
                    if (x.ns == Ns.VML && x.name != "imagedata") {
                        val css = OfficeUnits.cssStyle(x.attr("style"))
                        css["width"]?.let(OfficeUnits::cssLength)?.let { if (width == 0f) width = it }
                        css["height"]?.let(OfficeUnits::cssLength)?.let { if (height == 0f) height = it }
                    }
                    when (x.name) {
                        "imagedata" -> if (path == null) path = pkg.target(part, x.relId() ?: x.attr("relid"))
                        "txbxContent" -> blocks(x, null) { out.boxes += it }
                        else -> scan()
                    }
                }
            }
            scan()
            path?.let { if (width > 0f && height > 0f) out.add(PictureInline(ImagePicture(images, it, width, height, null), width, height, style)) }
        }

        private fun table(x: OfficeXml): DocTable {
            var props = TableProps()
            val grid = ArrayList<Float>()
            val rows = ArrayList<DocRow>()
            fun tableStyle() = props.style ?: styles.defaultTableStyle
            fun rowsIn() {
                x.children {
                    when (x.name) {
                        "tr" -> rows += row(x, tableStyle())
                        "sdt", "sdtContent", "customXml" -> rowsIn()
                    }
                }
            }
            x.children {
                when (x.name) {
                    "tblPr" -> props = TableProps.read(x)
                    "tblGrid" -> x.children { if (x.name == "gridCol") grid += (x.intAttr("w") ?: 0) / 20f }
                    "tr" -> rows += row(x, tableStyle())
                    "sdt", "customXml" -> rowsIn()
                }
            }
            return DocTable(props, grid, rows)
        }

        private fun row(x: OfficeXml, tableStyle: String?): DocRow {
            var height: Float? = null
            var exact = false
            var header = false
            var gridBefore = 0
            val cells = ArrayList<DocCell>()
            fun cellsIn() {
                x.children {
                    when (x.name) {
                        "tc" -> cells += cell(x, tableStyle)
                        "sdt", "sdtContent", "customXml" -> cellsIn()
                    }
                }
            }
            x.children {
                when (x.name) {
                    "trPr", "tblPrEx" -> x.children {
                        when (x.name) {
                            "trHeight" -> {
                                height = x.intAttr("val")?.let { it / 20f }
                                exact = x.attr("hRule") == "exact"
                            }
                            "tblHeader" -> header = x.flag()
                            "gridBefore" -> gridBefore = x.intAttr("val") ?: 0
                        }
                    }
                    "tc" -> cells += cell(x, tableStyle)
                    "sdt", "customXml" -> cellsIn()
                }
            }
            return DocRow(cells, height, exact, header, gridBefore)
        }

        private fun cell(x: OfficeXml, tableStyle: String?): DocCell {
            var span = 1
            var vMerge: String? = null
            var fill: Int? = null
            var borders: Borders? = null
            var verticalAlign = 0
            var margins: List<Float?>? = null
            val content = ArrayList<DocItem>()
            x.children {
                if (x.name == "tcPr") x.children {
                    when (x.name) {
                        "gridSpan" -> span = (x.intAttr("val") ?: 1).coerceAtLeast(1)
                        "vMerge" -> vMerge = x.attr("val") ?: "continue"
                        "shd" -> fill = OfficeColors.hex(x.attr("fill"))
                        "tcBorders" -> borders = Borders.read(x)
                        "vAlign" -> verticalAlign = when (x.attr("val")) { "center" -> 1; "bottom" -> 2; else -> 0 }
                        "tcMar" -> margins = TableProps.readMargins(x)
                    }
                } else {
                    block(x, tableStyle) { content += it }
                }
            }
            return DocCell(span, vMerge, fill, borders, verticalAlign, margins, content)
        }
    }

    // ---- Layout ----

    /** Lays the document out page after page, in layout units. */
    private inner class Flow {
        private var sectionIndex = 0
        private var section = sections[0]
        private var canvas: Canvas? = null
        private var top = 0f
        private var bottom = 0f
        private var y = 0f
        private var shownNumber = 0
        private var pageInSection = 0
        private var empty = true
        /** A paragraph at the top of the page keeps its space before only after an explicit break, as in Word. */
        private var keepSpaceBefore = true
        private var lastAfter = 0
        private var lastStyle: String? = null
        private var lastContextual = false
        private val pending = ArrayList<Placed>()

        private val left get() = section.left.units
        private val textWidth get() = (section.width - section.left - section.right).units
        private val textHeight get() = (section.height - section.top - section.bottom).units

        /** A block waiting to go on the page, with its spacing and what it keeps with. */
        private inner class Placed(
            val block: Block,
            val before: Int = 0,
            val after: Int = 0,
            val style: String? = null,
            val contextual: Boolean = false,
            val keepNext: Boolean = false,
            val overlays: List<Floating> = emptyList(),
        )

        fun add(item: DocItem) {
            when (item) {
                is DocPara -> {
                    if (item.props.pageBreakBefore == true && !empty && canvas != null) newPage(natural = false)
                    for (f in item.floats.filterNot { it.overText }) place(Placed(floatBlock(f)))
                    val p = item.props
                    place(
                        Placed(
                            paragraphBlock(item, textWidth.toInt(), shownNumber, textHeight),
                            (spaceBefore(p)).units.toInt(),
                            (spaceAfter(p)).units.toInt(),
                            p.style ?: styles.defaultParagraphStyle,
                            p.contextual == true,
                            p.keepNext == true,
                            item.floats.filter { it.overText },
                        ),
                    )
                }
                is DocTable -> place(Placed(tableBlock(item, textWidth, shownNumber, textHeight)))
                PageBreak -> {
                    flushPending()
                    ensurePage()
                    newPage(natural = false)
                }
                SectionEnd -> nextSection()
            }
        }

        private fun place(item: Placed) {
            // Headings go with what follows them: they move on to the next page together.
            if (item.keepNext && pending.size < 4) {
                pending += item
                return
            }
            if (pending.isNotEmpty()) {
                ensurePage()
                val need = pending.sumOf { it.before + it.block.height + it.after } + item.before + min(item.block.height, 40f.units.toInt())
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
            var after = lastAfter
            var before = item.before
            if (item.style != null && item.style == lastStyle) {
                if (item.contextual) before = 0
                if (lastContextual) after = 0
            }
            var gap = if (empty) (if (keepSpaceBefore) before else 0) else before + after
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
            lastAfter = item.after
            lastStyle = item.style
            lastContextual = item.contextual
        }

        private fun draw(block: Block, item: Placed, gap: Int, first: Boolean) {
            val c = canvas ?: return
            block.draw(c, left, y + gap)
            if (first) for (overlay in item.overlays) drawOverlay(c, overlay, y + gap)
        }

        /** A picture in front of or behind the text, where Word puts it on the page. */
        private fun drawOverlay(c: Canvas, f: Floating, paragraphTop: Float) {
            val w = f.width.units
            val h = f.height.units
            val pageW = section.width.units
            val pageH = section.height.units
            val (x0, extentX) = when (f.h.from) {
                "page" -> 0f to pageW
                "leftMargin", "insideMargin" -> 0f to left
                "rightMargin", "outsideMargin" -> (pageW - section.right.units) to section.right.units
                else -> left to textWidth
            }
            val (y0, extentY) = when (f.v.from) {
                "page" -> 0f to pageH
                "margin", "insideMargin", "outsideMargin" -> section.top.units to textHeight
                "topMargin" -> 0f to section.top.units
                "bottomMargin" -> (pageH - section.bottom.units) to section.bottom.units
                else -> paragraphTop to 0f
            }
            val x = x0 + when (f.h.align) {
                "center" -> (extentX - w) / 2
                "right", "outside" -> extentX - w
                "left", "inside" -> 0f
                else -> f.h.offset.units
            }
            val y = y0 + when (f.v.align) {
                "center" -> (extentY - h) / 2
                "bottom", "outside" -> extentY - h
                "top", "inside" -> 0f
                else -> f.v.offset.units
            }
            f.picture.draw(c, RectF(x, y, x + w, y + h))
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
            if (pageInSection == 0) section.pageStart?.let { shownNumber = it - 1 }
            shownNumber++
            pageInSection++
            val c = pdf.startPage(section.width, section.height)
            canvas = c
            progress(pdf.pageCount + 1)
            top = section.top.units
            bottom = (section.height - section.bottom).units
            drawHeaderAndFooter(c)
            y = top
            empty = true
            lastAfter = 0
            lastStyle = null
        }

        private fun finishPage() {
            if (canvas == null) return
            pdf.finishPage()
            canvas = null
        }

        private fun nextSection() {
            flushPending()
            val next = sections.getOrNull(++sectionIndex) ?: return
            val samePage = next.type == "continuous" && next.width == section.width && next.height == section.height
            section = next
            if (!samePage) {
                finishPage()
                pageInSection = 0
                keepSpaceBefore = true
            }
        }

        private fun drawHeaderAndFooter(c: Canvas) {
            val first = section.titlePage && pageInSection == 1
            val header = if (first) section.headers["first"] else section.headers["default"]
            val footer = if (first) section.footers["first"] else section.footers["default"]
            header?.let { headerBlock(it) }?.let { block ->
                val at = section.headerDistance.units
                block.draw(c, left, at)
                top = max(top, at + block.height)
            }
            footer?.let { headerBlock(it) }?.let { block ->
                val at = (section.height - section.footerDistance).units - block.height
                block.draw(c, left, at)
                bottom = min(bottom, at)
            }
        }

        /** A header or footer, laid out for this page (its page number). */
        private fun headerBlock(part: String): Block? {
            val items = headers.getOrPut(part) {
                val found = ArrayList<DocItem>()
                pkg.xml(part, check)?.use { x -> Reader(part).blocks(x, null) { found += it } }
                found
            }
            if (items.isEmpty()) return null
            return stack(items, textWidth.toInt(), shownNumber, textHeight)
        }

        private fun floatBlock(f: Floating): Block {
            val scale = min(1f, min(textWidth / f.width.units, textHeight / f.height.units))
            val w = (f.width.units * scale).toInt()
            val h = (f.height.units * scale).toInt()
            val x = when (f.h.align) {
                "center" -> (textWidth - w) / 2
                "right", "outside" -> textWidth - w
                "left", "inside" -> 0f
                else -> if (f.h.from == "page") f.h.offset.units - left else f.h.offset.units
            }.coerceIn(min(0f, textWidth - w), max(0f, textWidth - w))
            return FloatBlock(f.picture, w, h, x)
        }

        fun finish() {
            flushPending()
            ensurePage()
            finishPage()
        }
    }

    /** A picture on a line of its own. */
    private class FloatBlock(private val picture: Picture, private val width: Int, override val height: Int, private val x: Float) : Block {
        override fun draw(canvas: Canvas, x: Float, y: Float) = picture.draw(canvas, RectF(x + this.x, y, x + this.x + width, y + height))
        override fun split(space: Int, force: Boolean): Pair<Block, Block?>? = if (force) this to null else null
    }

    /** [items] one under another in [width] units, as in a table cell or a header. */
    private fun stack(items: List<DocItem>, width: Int, page: Int, maxHeight: Float): Block {
        val placed = ArrayList<Spaced>()
        for (item in items) when (item) {
            is DocPara -> {
                for (f in item.floats) placed += Spaced(FloatBlock(f.picture, min(width, f.width.units.toInt()), f.height.units.toInt(), 0f), 0, 0)
                placed += Spaced(paragraphBlock(item, width, page, maxHeight), spaceBefore(item.props).units.toInt(), spaceAfter(item.props).units.toInt())
            }
            is DocTable -> placed += Spaced(tableBlock(item, width.toFloat(), page, maxHeight), 0, 0)
            else -> Unit
        }
        return StackBlock(placed)
    }

    /** Space before a paragraph, in points, with room for its top border. */
    private fun spaceBefore(p: ParaProps) = (p.before ?: 0f) + (p.borderTop?.let { (b, gap) -> if (b.width > 0f) b.width + gap else 0f } ?: 0f)

    /** Space after a paragraph, in points, with room for its bottom border. */
    private fun spaceAfter(p: ParaProps) = (p.after ?: 0f) + (p.borderBottom?.let { (b, gap) -> if (b.width > 0f) b.width + gap else 0f } ?: 0f)

    private fun rule(border: Pair<Border, Float>?): Rule? = border?.takeIf { it.first.width > 0f }?.let { (b, gap) -> Rule(b.width.units, b.color, gap.units) }

    private fun paragraphBlock(p: DocPara, width: Int, page: Int, maxHeight: Float): ParagraphBlock {
        val rich = RichText()
        for (inline in p.inlines) when (inline) {
            is TextInline -> rich.append(inline.text, inline.style)
            is PageNumberInline -> rich.append(page.toString(), inline.style)
            is PictureInline -> {
                // Pictures bigger than the space for text are shrunk to fit, keeping their shape.
                val w = inline.width.units
                val h = inline.height.units
                val scale = min(1f, min(width / w, maxHeight * 0.95f / h))
                rich.appendObject(PictureSpan(inline.picture, max(1, (w * scale).toInt()), max(1, (h * scale).toInt())), inline.style)
            }
        }
        val props = p.props
        val left = (props.left ?: 0f).units
        val firstStart = left + (props.firstLine ?: 0f).units
        val defaultTab = 36f.units
        val tabs = props.tabs.orEmpty().map { it.units }
        var textStart = firstStart
        val label = p.label?.takeIf { it.isNotEmpty() }?.let { text ->
            val paint = p.labelStyle.paint()
            val end = firstStart + paint.measureText(text)
            textStart = when (p.labelSuffix) {
                "space" -> end + paint.measureText(" ")
                "nothing" -> end
                // A tab: to the hanging indent if the label ends before it, else to the next tab stop.
                else -> if (end < left - 1f) left else (tabs.firstOrNull { it > end + 1f } ?: (ceil((end + 1f) / defaultTab) * defaultTab))
            }
            Label(text, p.labelStyle, firstStart.toInt())
        }
        return ParagraphBlock(
            ParagraphSpec(
                text = rich.text,
                base = p.mark,
                width = width,
                align = props.align ?: Align.START,
                firstIndent = textStart.toInt(),
                indent = left.toInt(),
                rightIndent = (props.right ?: 0f).units.toInt(),
                spacing = props.line ?: LineSpacing.SINGLE,
                lineScale = OfficeFonts.lineScale(p.mark),
                label = label,
                rtl = props.rtl == true,
                background = props.shading,
                tabs = tabs.map { it.toInt() },
                defaultTab = defaultTab.toInt(),
                avoidSingleLines = props.widowControl != false,
                ruleAbove = rule(props.borderTop),
                ruleBelow = rule(props.borderBottom),
            ),
        )
    }

    private fun tableBlock(t: DocTable, available: Float, page: Int, maxHeight: Float): Block {
        if (t.rows.isEmpty()) return StackBlock(emptyList())
        val styleId = t.props.style ?: styles.defaultTableStyle
        val props = t.props.over(styles.table(styleId))
        val borders = props.borders ?: Borders()
        val margins = List(4) { props.margins?.get(it) ?: if (it % 2 == 0) 5.4f else 0f }
        val columns = t.rows.maxOf { row -> row.gridBefore + row.cells.sumOf { it.span } }
        val availablePt = available / U
        val preferred = props.width?.let { if (props.widthShare) it * availablePt else it }
        // A little wider than the text is fine: Word lets tables reach into the margin.
        val widths = TableColumns.widths(t.grid, columns, availablePt * 1.04f, preferred).map { it.units }
        val tableWidth = widths.sum()
        val x = when (props.align) {
            Align.CENTER -> (available - tableWidth) / 2
            Align.END -> available - tableWidth
            else -> (props.indent ?: 0f).units
        }
        val starts = widths.runningFold(0f) { acc, w -> acc + w }
        // Which grid columns each row's cells start at, to find cells merged with the one below.
        val cellColumns = t.rows.map { row ->
            var col = row.gridBefore
            row.cells.map { cell -> col.also { col += cell.span } }
        }
        fun mergedBelow(r: Int, col: Int): Boolean {
            val next = t.rows.getOrNull(r + 1) ?: return false
            val i = cellColumns[r + 1].indexOf(col)
            return i >= 0 && next.cells[i].vMerge == "continue"
        }
        val rows = t.rows.mapIndexed { r, row ->
            val cells = row.cells.mapIndexed { i, cell ->
                val col = cellColumns[r][i].coerceAtMost(columns - 1)
                val end = (col + cell.span).coerceAtMost(columns)
                val inherited = Borders(
                    top = if (r == 0) borders.top else borders.insideH,
                    bottom = if (r == t.rows.lastIndex) borders.bottom else borders.insideH,
                    left = if (col == 0) borders.left else borders.insideV,
                    right = if (end >= columns) borders.right else borders.insideV,
                )
                val own = (cell.borders ?: Borders()).over(inherited)
                val m = List(4) { cell.margins?.get(it) ?: margins[it] }.map { it.units.toInt() }
                val cellWidth = starts[end] - starts[col]
                val continued = cell.vMerge == "continue"
                CellBox(
                    x = x + starts[col],
                    width = cellWidth,
                    content = if (continued || cell.content.isEmpty()) null else stack(cell.content, max(1, (cellWidth - m[0] - m[2]).toInt()), page, maxHeight),
                    fill = cell.fill,
                    top = if (continued) Border.NONE else own.top,
                    left = own.left,
                    bottom = if (mergedBelow(r, cellColumns[r][i])) Border.NONE else own.bottom,
                    right = own.right,
                    verticalAlign = cell.verticalAlign,
                    padLeft = m[0],
                    padTop = m[1],
                    padRight = m[2],
                    padBottom = m[3],
                )
            }
            RowBlock(cells, (row.height ?: 0f).units.toInt(), row.exact)
        }
        val headerRows = t.rows.takeWhile { it.header }.size.takeIf { it < t.rows.size } ?: 0
        return TableBlock(rows, headerRows, 0f)
    }

    private companion object {
        /** US Letter with one-inch margins: Word's own default. */
        val DEFAULT_SECTION = Section(612f, 792f, 72f, 72f, 72f, 72f, 36f, 36f, "nextPage", emptyMap(), emptyMap(), false, null)
    }
}
