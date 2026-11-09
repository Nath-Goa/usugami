package io.github.nath_goa.usugami.data.office

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.TextPaint
import android.text.style.ReplacementSpan
import androidx.core.graphics.withRotation
import androidx.core.graphics.withScale
import io.github.nath_goa.usugami.core.GroupFrame
import io.github.nath_goa.usugami.core.ListNumbering
import io.github.nath_goa.usugami.core.OfficeColors
import io.github.nath_goa.usugami.core.OfficeRect
import io.github.nath_goa.usugami.core.OfficeUnits
import io.github.nath_goa.usugami.core.SymbolFonts
import io.github.nath_goa.usugami.core.TableColumns
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Word documents (.docx) to PDF, read and laid out on the phone.
 *
 * What converts: page size and margins (per section, portrait or landscape); paragraphs with their
 * alignment, spacing, indents and line spacing, shading, "keep with next" and page breaks before;
 * text that is bold, italic, underlined, struck through, coloured, highlighted, raised or lowered,
 * in capitals, or any size; Word's styles (document defaults, paragraph and character styles,
 * headings, Title, table styles' borders and spacing) and theme fonts; bulleted and numbered
 * lists, at every level; tables, with their column widths, merged cells, borders and shading,
 * header rows repeated on each page, and rows split across pages; pictures, in the text or floating,
 * turned or flipped, and in groups and drawing canvases; picture watermarks; page breaks and section
 * breaks; headers and footers, with page numbers in their format; the text of text boxes, equations
 * and fields; links, as text.
 *
 * What is simplified: fonts become the phone's (sans-serif, serif or monospace), so lines can break
 * in other places than in Word. Text doesn't wrap around floating pictures: they go on lines of
 * their own, or over the text when Word puts them in front of or behind it. Text boxes' text
 * follows the paragraph they're anchored to. Columns become a single column; right and centre tab
 * stops act as such only in paragraphs of one line (headers, footers, contents lines), and as left
 * ones in longer paragraphs; charts, SmartArt and drawings Android can't decode (such as EMF and
 * WMF) become light grey boxes. Comments and deleted text are left out.
 *
 * Footnotes go at the foot of the page their reference is on, under Word's short line, going on to the
 * next page under a long one when they don't fit; endnotes go at the end of the document, or of each
 * section. References in headers and footers, which Word doesn't allow, are left out.
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

    /** Whether even pages have headers and footers of their own (Word's "Different Odd & Even Pages"). */
    private var evenAndOddHeaders = false

    /** The document's own footnote and endnote settings, which its sections' add to. */
    private var documentFootnotes = NoteProps()
    private var documentEndnotes = NoteProps()
    private lateinit var footnotes: Notes
    private lateinit var endnotes: Notes

    /** The section being read, and the numbers footnotes and endnotes have reached. */
    private var readingSection = 0
    private val footnoteCount = NoteCount()
    private val endnoteCount = NoteCount()

    /** Endnotes referred to, with their marks, not yet printed. */
    private val endnotesDue = ArrayList<NoteSpan>()

    /** Notes with a mark of their own, which take no number. */
    private val customMarked = HashSet<NoteKey>()

    /** Converts the document; returns the number of pages. */
    fun convert(): Int {
        main = pkg.mainPart() ?: "word/document.xml"
        theme = OfficeTheme.read(pkg, pkg.targetOfType(main, "/theme"), check)
        styles = DocxStyles.read(pkg, pkg.targetOfType(main, "/styles"), theme, check)
        numbering = DocxNumbering.read(pkg, pkg.targetOfType(main, "/numbering"), theme, styles, check)
        readSettings()
        sections = readSections()
        footnotes = readNotes("footnote")
        endnotes = readNotes("endnote")
        val flow = Flow()
        val reader = Reader(main, body = true)
        var shown = false
        pkg.xml(main, check)?.use { x ->
            x.children {
                if (x.name == "body") reader.blocks(x, null) { item ->
                    check()
                    if (!shown) shown = shows(item)
                    flow.add(item)
                    if (item == SectionEnd) readingSection++
                }
            }
        }
        // A body kept as a web page or RTF file inside the document (w:altChunk), as some apps export
        // to Word, isn't read: the file is refused with a message rather than becoming a blank PDF.
        if (!shown && reader.altChunks > 0) throw OfficeEmbeddedContent()
        flow.finish()
        return pdf.pageCount
    }

    /**
     * Whether [item] has the page's number in it (a PAGE field), or a footnote numbered again on each
     * page, in its text or in a table cell's.
     */
    private fun hasPageNumber(item: DocItem): Boolean = when (item) {
        is DocPara -> item.inlines.any { it is PageNumberInline || (it is NoteRefInline && it.mark == null) }
        is DocTable -> item.rows.any { row -> row.cells.any { cell -> cell.content.any(::hasPageNumber) } }
        else -> false
    }

    /** Whether [item] puts anything on the page: text, a picture or a table. */
    private fun shows(item: DocItem): Boolean = when (item) {
        is DocPara -> item.floats.isNotEmpty() || item.inlines.any { it !is TextInline || it.text.isNotBlank() }
        is DocTable -> true
        else -> false
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

    /** The page's number, in [format] when its field asks for one (such as "PAGE \* roman"), else in its section's. */
    private class PageNumberInline(val style: TextStyle, val format: ListNumbering.Format?) : Inline
    private class PictureInline(val picture: Picture, val width: Float, val height: Float, val style: TextStyle) : Inline

    /**
     * A footnote's or endnote's reference: its [mark], or null for a footnote numbered again on each
     * page, which is numbered in [format] as it's laid out.
     */
    private class NoteRefInline(val key: NoteKey, val mark: String?, val format: String?, val style: TextStyle) : Inline

    /** A note's own mark, at its start (w:footnoteRef, w:endnoteRef): its reference's. */
    private class OwnMarkInline(val style: TextStyle) : Inline

    /** Word's line above footnotes or endnotes: short, or as wide as the text above those going on from the page before. */
    private class SeparatorInline(val continuation: Boolean, val style: TextStyle) : Inline

    private data class NoteKey(val endnote: Boolean, val id: Int)

    /** A document's footnotes or endnotes: each one's content by its id, and the lines put above them. */
    private class Notes(val content: Map<Int, List<DocItem>>, val separator: List<DocItem>?, val continuation: List<DocItem>?)

    /**
     * How footnotes or endnotes are numbered and where they go (w:footnotePr, w:endnotePr): in Word's
     * number [format], from [start], starting again ([restart]) "eachSect" or "eachPage", and at the
     * [position] "pageBottom" or "beneathText" (footnotes), "sectEnd" or "docEnd" (endnotes). Null where not set.
     */
    private data class NoteProps(val format: String? = null, val start: Int? = null, val restart: String? = null, val position: String? = null) {
        fun over(base: NoteProps) = NoteProps(format ?: base.format, start ?: base.start, restart ?: base.restart, position ?: base.position)

        companion object {
            fun read(x: OfficeXml): NoteProps {
                var p = NoteProps()
                x.children {
                    when (x.name) {
                        "numFmt" -> p = p.copy(format = x.attr("val"))
                        "numStart" -> p = p.copy(start = x.intAttr("val"))
                        "numRestart" -> p = p.copy(restart = x.attr("val"))
                        "pos" -> p = p.copy(position = x.attr("val"))
                    }
                }
                return p
            }
        }
    }

    /** The last number given to a footnote or endnote, and the section it was in. */
    private class NoteCount {
        var value = 0
        var section = -1
    }

    /** Where a floating picture goes: relative to [from] ("page", "margin", "paragraph"...), by [offset] points or [align]. */
    private class Position(val from: String, val offset: Float, val align: String?)

    /**
     * A floating picture. [overText] when Word puts it in front of or behind the text, rather than
     * wrapping text around it; [behind] when behind it.
     */
    private class Floating(
        val picture: Picture,
        val width: Float,
        val height: Float,
        val h: Position,
        val v: Position,
        val overText: Boolean,
        val behind: Boolean = false,
    )

    /** How a picture is turned: by [degrees] clockwise, after being mirrored across ([flipH]) or upside down ([flipV]). */
    private class Turn(val degrees: Float, val flipH: Boolean, val flipV: Boolean) {
        val isNone: Boolean get() = degrees % 360f == 0f && !flipH && !flipV

        /** Turned a quarter or three quarters: its box lies on its side. */
        val sideways: Boolean
            get() {
                val quarters = (degrees / 90f).roundToInt()
                return abs(degrees - quarters * 90f) < 1f && quarters.mod(2) == 1
            }

        companion object {
            val NONE = Turn(0f, flipH = false, flipV = false)
        }
    }

    /**
     * A picture found in a drawing: [path] with [crop], turned [turn], and where it goes in the
     * drawing's box, in points from its top left corner (null for all of it, as a lone picture).
     */
    private class Found(val path: String, val crop: FloatArray?, val box: OfficeRect?, val turn: Turn)

    /** A page's number, and how its section writes page numbers; [notes] footnotes begin on it before what is laid out. */
    private class PageNumber(val value: Int, val format: ListNumbering.Format, private var notes: Int = 0) {
        fun text(own: ListNumbering.Format?) = ListNumbering.format(value, own ?: format)

        /** The next footnote's number, for footnotes numbered again on each page. */
        fun nextNote() = ++notes
    }

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
        val pageFormat: ListNumbering.Format,
        val footnotes: NoteProps,
        val endnotes: NoteProps,
        /** Its endnotes go to the end of the next section ("Suppress endnotes"). */
        val noEndnote: Boolean,
    )

    /** The document's settings: whether even pages have headers and footers of their own, and how notes go. */
    private fun readSettings() {
        pkg.targetOfType(main, "/settings")?.let { path ->
            pkg.xml(path, check)?.use { x ->
                x.children {
                    when (x.name) {
                        "evenAndOddHeaders" -> evenAndOddHeaders = x.flag()
                        "footnotePr" -> documentFootnotes = NoteProps.read(x)
                        "endnotePr" -> documentEndnotes = NoteProps.read(x)
                    }
                }
            }
        }
    }

    /**
     * The footnotes ([kind] "footnote") or endnotes ("endnote") of the document. The separators are
     * kept for drawing; the continuation notice, and notes of other kinds, aren't printed.
     */
    private fun readNotes(kind: String): Notes {
        val content = HashMap<Int, List<DocItem>>()
        var separator: List<DocItem>? = null
        var continuation: List<DocItem>? = null
        val path = pkg.targetOfType(main, "/${kind}s") ?: return Notes(content, null, null)
        pkg.xml(path, check)?.use { x ->
            val reader = Reader(path)
            x.children {
                if (x.name != kind) return@children
                val id = x.intAttr("id") ?: return@children
                val type = x.attr("type") ?: "normal"
                val found = ArrayList<DocItem>()
                reader.blocks(x, null) { if (it is DocPara || it is DocTable) found += it }
                when (type) {
                    "normal" -> content[id] = found
                    "separator" -> separator = found
                    "continuationSeparator" -> continuation = found
                }
            }
        }
        return Notes(content, separator, continuation)
    }

    // ---- Sections: read ahead, since a section's page size is written at its end ----

    private fun readSections(): List<Section> {
        val found = ArrayList<Section>()
        pkg.xml(main, check)?.use { x ->
            x.descendants {
                when (x.name) {
                    "sectPr" -> {
                        found += readSection(x, found.lastOrNull())
                        false
                    }
                    "body", "p", "pPr", "sdt", "sdtContent", "customXml", "ins" -> true
                    else -> false
                }
            }
        }
        // US Letter with one-inch margins: Word's own default.
        return found.ifEmpty {
            listOf(Section(612f, 792f, 72f, 72f, 72f, 72f, 36f, 36f, "nextPage", emptyMap(), emptyMap(), false, null, ListNumbering.Format.DECIMAL, documentFootnotes, documentEndnotes, false))
        }
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
        var pageFormat = ListNumbering.Format.DECIMAL
        var footnotes = NoteProps()
        var endnotes = NoteProps()
        var noEndnote = false
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
                "pgNumType" -> {
                    pageStart = x.intAttr("start")
                    pageFormat = ListNumbering.wordFormat(x.attr("fmt")).takeUnless { it == ListNumbering.Format.BULLET || it == ListNumbering.Format.NONE }
                        ?: ListNumbering.Format.DECIMAL
                }
                "footnotePr" -> footnotes = NoteProps.read(x)
                "endnotePr" -> endnotes = NoteProps.read(x)
                "noEndnote" -> noEndnote = x.flag()
            }
        }
        width = width.coerceIn(72f, 14_400f)
        height = height.coerceIn(72f, 14_400f)
        // Keep at least an inch for the text, whatever the margins say.
        if (margin[2] + margin[3] > width - 72f) margin[2] = 36f.also { margin[3] = 36f }
        if (margin[0] + margin[1] > height - 72f) margin[0] = 36f.also { margin[1] = 36f }
        return Section(
            width, height, margin[0], margin[1], margin[2], margin[3], margin[4], margin[5], type, headers, footers, titlePage, pageStart, pageFormat,
            footnotes.over(documentFootnotes), endnotes.over(documentEndnotes), noEndnote,
        )
    }

    // ---- Reading ----

    /**
     * Reads block content from part [part] (the document, a header or footer, or the notes), whose
     * relationships its pictures use. Only in the document's [body] do footnote and endnote references count.
     */
    private inner class Reader(private val part: String, private val body: Boolean = false) {
        /** Fields being read, outermost first: their instruction, and whether their result has started. */
        private val fields = ArrayList<Field>()

        /** How many times content kept in another format (w:altChunk) was met: it isn't read. */
        var altChunks = 0
            private set

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
                "altChunk" -> altChunks++
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
                var emitted = false
                built.segments.forEachIndexed { i, inlines ->
                    if (i > 0) emit(PageBreak)
                    // The text before a page break; the paragraph mark always goes after it.
                    if (i == 0 && inlines.isEmpty() && built.segments.size > 1 && built.floats.isEmpty()) return@forEachIndexed
                    // A paragraph starting with a page break keeps its number and space before on the next page.
                    val first = !emitted
                    emitted = true
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
                    out.add(PageNumberInline(out.base.toStyle(theme), ListNumbering.fieldFormat(x.attr("instr").orEmpty())))
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
            var cachedComplex: TextStyle? = null
            fun style() = cached ?: props.toStyle(theme).also { cached = it }
            fun complexStyle() = cachedComplex ?: props.toComplexStyle(theme).also { cachedComplex = it }
            fun shown() = props.hidden != true && fields.none { !it.inResult || it.isPage }
            // Whether the run's text so far ended in complex-script letters (null: none yet).
            var complexBefore: Boolean? = null
            // A note reference whose mark is the text after it (w:customMarkFollows), waiting for that text.
            var customMark: NoteKey? = null
            fun text(s: String) {
                if (s.isEmpty() || !shown()) return
                customMark?.let { key ->
                    customMark = null
                    return reference(key, s, out, style())
                }
                val complex = complexStyle()
                when {
                    // Most runs look the same in every script: in one piece, as ever.
                    complex == style() -> out.add(TextInline(s, style()))
                    props.allComplex -> out.add(TextInline(s, complex))
                    else -> complexBefore = ComplexScript.split(s, complexBefore) { part, isComplex ->
                        out.add(TextInline(part, if (isComplex) complex else style()))
                    }
                }
            }

            fun child() {
                when (x.name) {
                    "rPr" -> {
                        val direct = RunProps.read(x, theme)
                        props = direct.over(styles.run(direct.style)).over(out.base)
                        cached = null
                        cachedComplex = null
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
                    "sym" -> x.attr("char")?.toIntOrNull(16)?.takeIf(Character::isValidCodePoint)?.let { code ->
                        text(SymbolFonts.character(x.attr("font"), String(Character.toChars(code))))
                    }
                    // Text with a phonetic guide (furigana): the text itself, without its guide.
                    "ruby" -> x.children { if (x.name == "rubyBase") x.children { content(x, out) } }
                    "drawing" -> if (shown()) drawing(x, out, style())
                    "pict", "object" -> if (shown()) vml(x, out, style())
                    "fldChar" -> when (x.attr("fldCharType")) {
                        "begin" -> fields += Field()
                        "separate" -> fields.lastOrNull()?.let { field ->
                            field.inResult = true
                            if (isPageField(field.instruction.toString()) && fields.count { !it.inResult || it.isPage } == 0) {
                                out.add(PageNumberInline(style(), ListNumbering.fieldFormat(field.instruction.toString())))
                                field.isPage = true
                            }
                        }
                        "end" -> fields.removeLastOrNull()?.let { field ->
                            val instruction = field.instruction.toString()
                            if (!field.inResult && isPageField(instruction)) out.add(PageNumberInline(style(), ListNumbering.fieldFormat(instruction)))
                        }
                    }
                    "instrText" -> fields.lastOrNull()?.instruction?.append(x.text())
                    "AlternateContent" -> x.children { if (x.name == "Choice") x.children { child() } }
                    "footnoteReference", "endnoteReference" -> if (body && shown()) x.intAttr("id")?.let { id ->
                        val key = NoteKey(x.name == "endnoteReference", id)
                        if (x.flag("customMarkFollows", missing = false)) customMark = key else reference(key, null, out, style())
                    }
                    "footnoteRef", "endnoteRef" -> if (shown()) out.add(OwnMarkInline(style()))
                    "separator", "continuationSeparator" -> out.add(SeparatorInline(x.name == "continuationSeparator", style()))
                }
            }
            x.children { child() }
            // A custom mark that never came: the note is numbered.
            customMark?.let { reference(it, null, out, style()) }
        }

        /**
         * The reference to footnote or endnote [key], marked [custom] (Word's custom mark), else numbered
         * as its section says: footnotes 1, 2, 3 and endnotes i, ii, iii unless it says otherwise. Endnotes
         * are kept to be printed at the end. A reference to a note the document hasn't is left out.
         */
        private fun reference(key: NoteKey, custom: String?, out: ParaBuilder, style: TextStyle) {
            if ((if (key.endnote) endnotes else footnotes).content[key.id] == null) return
            val section = sections[readingSection.coerceAtMost(sections.lastIndex)]
            val props = if (key.endnote) section.endnotes else section.footnotes
            // Numbered again on each page: numbered as it's laid out.
            val perPage = !key.endnote && custom == null && props.restart == "eachPage"
            val mark = custom ?: if (perPage) null else ListNumbering.noteMark(
                number(if (key.endnote) endnoteCount else footnoteCount, props),
                props.format,
                if (key.endnote) ListNumbering.Format.LOWER_ROMAN else ListNumbering.Format.DECIMAL,
            )
            out.add(NoteRefInline(key, mark, props.format, style))
            if (custom != null) customMarked += key
            if (key.endnote && mark != null) endnotesDue += NoteSpan(key, mark)
        }

        private fun isPageField(instruction: String?) =
            instruction?.trim()?.split(' ', '\\')?.firstOrNull()?.equals("PAGE", ignoreCase = true) == true

        /**
         * A DrawingML picture or shape (w:drawing), in the text or floating: a picture, the pictures
         * of a group or drawing canvas where the group puts them, and text boxes' text. One hidden
         * in Word (with the Selection Pane) is left out, as Word neither shows nor prints it.
         */
        private fun drawing(x: OfficeXml, out: ParaBuilder, style: TextStyle) {
            x.children {
                val inline = x.name == "inline"
                if (!inline && x.name != "anchor") return@children
                val behind = x.flag("behindDoc", missing = false)
                var width = 0f
                var height = 0f
                var caption: String? = null
                var hidden = false
                var standIn = false
                var wrapNone = false
                var h = Position("column", 0f, null)
                var v = Position("paragraph", 0f, null)
                val found = ArrayList<Found>()

                /**
                 * A picture (pic:pic) or shape (wps:wsp): its picture, or picture fill, and its text box's
                 * text. In a group, [place] takes its box to the drawing's.
                 */
                fun shape(place: ((OfficeRect) -> OfficeRect)?) {
                    var shapeHidden = false
                    var path: String? = null
                    var crop: FloatArray? = null
                    var frame: Pair<OfficeRect, Turn>? = null
                    fun scan() {
                        x.children {
                            if (shapeHidden) return@children
                            when (x.name) {
                                "cNvPr" -> shapeHidden = x.flag("hidden", missing = false)
                                "blip" -> if (path == null) path = pkg.target(part, x.relId("embed"))
                                "srcRect" -> if (crop == null) crop = floatArrayOf(
                                    (x.percentAttr("l") ?: 0) / 100_000f, (x.percentAttr("t") ?: 0) / 100_000f,
                                    (x.percentAttr("r") ?: 0) / 100_000f, (x.percentAttr("b") ?: 0) / 100_000f,
                                )
                                "xfrm" -> if (frame == null) frame = shapeFrame(x)
                                "txbxContent" -> blocks(x, null) { out.boxes += it }
                                else -> scan()
                            }
                        }
                    }
                    scan()
                    val picture = path ?: return
                    if (shapeHidden) return
                    val box = place?.let { frame?.first?.let(it) ?: return }
                    found += Found(picture, crop, box, frame?.second ?: Turn.NONE)
                }

                /** What's inside the current element, in a group placed by [place] (null outside groups). */
                fun contents(place: ((OfficeRect) -> OfficeRect)?) {
                    var inner = place
                    var groupHidden = false
                    x.children {
                        if (groupHidden) return@children
                        when (x.name) {
                            // A group hidden as a whole.
                            "cNvPr" -> groupHidden = x.flag("hidden", missing = false)
                            "grpSpPr" -> x.children { if (x.name == "xfrm") inner = groupFrame(x, place ?: { it }) }
                            "pic", "wsp" -> shape(inner)
                            // A group (wgp, grpSp) or drawing canvas (wpc): their shapes are placed in their box.
                            "wgp", "grpSp", "wpc" -> contents(inner ?: { it })
                            "txbxContent" -> blocks(x, null) { out.boxes += it }
                            else -> contents(inner)
                        }
                    }
                }

                x.children {
                    when (x.name) {
                        "extent" -> {
                            width = OfficeUnits.emu(x.longAttr("cx") ?: 0L)
                            height = OfficeUnits.emu(x.longAttr("cy") ?: 0L)
                        }
                        "docPr" -> {
                            caption = x.attr("descr")?.takeIf { it.isNotBlank() } ?: x.attr("title")?.takeIf { it.isNotBlank() }
                            hidden = x.flag("hidden", missing = false)
                        }
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
                        // The graphic comes after docPr, so a hidden drawing's text boxes aren't read either.
                        "graphic" -> if (!hidden) x.children {
                            if (x.name == "graphicData") {
                                val uri = x.attr("uri").orEmpty()
                                if ("chart" in uri || "diagram" in uri) standIn = true
                                contents(null)
                            }
                        }
                    }
                }
                if (hidden || width <= 0f || height <= 0f) return@children
                val lone = found.firstOrNull { it.box == null }
                // A picture turned a quarter takes the room of its turned box, as in Word.
                val sideways = lone?.turn?.sideways == true
                val picture: Picture = when {
                    lone != null -> turned(ImagePicture(images, lone.path, width, height, lone.crop), lone.turn, sideways)
                    found.isNotEmpty() -> GroupPicture(
                        width, height,
                        found.mapNotNull { f -> f.box?.let { turned(ImagePicture(images, f.path, it.width, it.height, f.crop), f.turn, false) to it } },
                    )
                    standIn -> StandInPicture(caption)
                    else -> return@children
                }
                val (w, ht) = if (sideways) height to width else width to height
                if (inline) out.add(PictureInline(picture, w, ht, style))
                else out.floats += Floating(picture, w, ht, h, v, wrapNone, behind)
            }
        }

        /** A shape's a:xfrm: its box in points, and how it's turned. */
        private fun shapeFrame(x: OfficeXml): Pair<OfficeRect, Turn> {
            val turn = Turn((x.longAttr("rot") ?: 0L) / 60_000f, x.flag("flipH", missing = false), x.flag("flipV", missing = false))
            var box = OfficeRect(0f, 0f, 0f, 0f)
            x.children {
                when (x.name) {
                    "off" -> box = box.copy(x = OfficeUnits.emu(x.longAttr("x") ?: 0L), y = OfficeUnits.emu(x.longAttr("y") ?: 0L))
                    "ext" -> box = box.copy(width = OfficeUnits.emu(x.longAttr("cx") ?: 0L), height = OfficeUnits.emu(x.longAttr("cy") ?: 0L))
                }
            }
            return box to turn
        }

        /** A group's a:xfrm, as a step from its children's coordinates to [outer]'s, in points. */
        private fun groupFrame(x: OfficeXml, outer: (OfficeRect) -> OfficeRect): (OfficeRect) -> OfficeRect {
            var box = OfficeRect(0f, 0f, 0f, 0f)
            var childOffset = 0f to 0f
            var childExtent = 0f to 0f
            x.children {
                fun length(name: String) = OfficeUnits.emu(x.longAttr(name) ?: 0L)
                when (x.name) {
                    "off" -> box = box.copy(x = length("x"), y = length("y"))
                    "ext" -> box = box.copy(width = length("cx"), height = length("cy"))
                    "chOff" -> childOffset = length("x") to length("y")
                    "chExt" -> childExtent = length("cx") to length("cy")
                }
            }
            val frame = GroupFrame(box, childOffset, childExtent)
            return { outer(frame.place(it)) }
        }

        /**
         * An older VML picture or text box (w:pict, w:object). A picture positioned absolutely floats,
         * as Word's picture watermarks do, lightened when Word washes it out; a hidden shape is left out.
         * Its size and place are those of the outermost shape holding it: a shape in a group (a Word 2003
         * drawing canvas) is placed in the group's own coordinates, so it goes where the group goes.
         */
        private fun vml(x: OfficeXml, out: ParaBuilder, style: TextStyle) {
            var path: String? = null
            var css: Map<String, String> = emptyMap()
            var veil = 0f
            var wrap: String? = null
            fun scan(owner: Map<String, String>, outermost: Map<String, String>?) {
                x.children {
                    var own = owner
                    var frame = outermost
                    if (x.ns == Ns.VML && x.name != "imagedata") {
                        val shapeCss = OfficeUnits.cssStyle(x.attr("style"))
                        if (shapeCss.isNotEmpty()) own = shapeCss
                        if (frame == null && ("width" in shapeCss || "height" in shapeCss)) frame = shapeCss
                    }
                    if (own["visibility"] == "hidden") return@children
                    when (x.name) {
                        "imagedata" -> if (path == null) {
                            path = pkg.target(part, x.relId() ?: x.attr("relid"))
                            css = frame.orEmpty()
                            veil = washout(x)
                        }
                        "wrap" -> if (wrap == null) wrap = x.attr("type") ?: "none"
                        "txbxContent" -> blocks(x, null) { out.boxes += it }
                        else -> scan(own, frame)
                    }
                }
            }
            scan(emptyMap(), null)
            val p = path ?: return
            val width = css["width"]?.let(OfficeUnits::cssLength) ?: 0f
            val height = css["height"]?.let(OfficeUnits::cssLength) ?: 0f
            if (width <= 0f || height <= 0f) return
            val picture = ImagePicture(images, p, width, height, null).let { if (veil > 0f) VeiledPicture(it, veil) else it }
            if (css["position"] == "absolute") {
                // In front of or behind the text unless text wraps around it; behind when below the text's layer.
                val behind = (css["z-index"]?.toIntOrNull() ?: 0) < 0
                out.floats += Floating(picture, width, height, vmlPosition(css, true), vmlPosition(css, false), wrap == null || wrap == "none", behind)
            } else {
                out.add(PictureInline(picture, width, height, style))
            }
        }

        /** Where a VML shape positioned absolutely goes, from its [css]: across when [horizontal], else down. */
        private fun vmlPosition(css: Map<String, String>, horizontal: Boolean): Position {
            val axis = if (horizontal) "horizontal" else "vertical"
            val from = when (css["mso-position-$axis-relative"]) {
                "page" -> "page"
                "margin" -> "margin"
                "left-margin-area", "inner-margin-area" -> if (horizontal) "leftMargin" else "topMargin"
                "right-margin-area", "outer-margin-area" -> if (horizontal) "rightMargin" else "bottomMargin"
                "top-margin-area" -> "topMargin"
                "bottom-margin-area" -> "bottomMargin"
                else -> if (horizontal) "column" else "paragraph"
            }
            val offset = (css[if (horizontal) "margin-left" else "margin-top"] ?: css[if (horizontal) "left" else "top"])?.let(OfficeUnits::cssLength) ?: 0f
            return Position(from, offset, css["mso-position-$axis"]?.takeIf { it != "absolute" })
        }

        /**
         * How much lighter a VML picture is drawn, 0 to 0.9: Word's "Washout" (as for watermarks) lowers
         * the contrast ("gain") and raises the brightness ("blacklevel"), much as a white veil would.
         */
        private fun washout(x: OfficeXml): Float {
            fun fraction(name: String) = x.attr(name)?.trim()?.let { if (it.endsWith("f")) it.dropLast(1).toFloatOrNull()?.div(65_536f) else it.toFloatOrNull() }
            val gain = fraction("gain") ?: return 0f
            val black = fraction("blacklevel") ?: return 0f
            return if (gain < 1f && black > 0f) (1f - gain).coerceIn(0f, 0.9f) else 0f
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
                    "tblGrid" -> x.children { if (x.name == "gridCol" && grid.size < MAX_COLUMNS) grid += (x.intAttr("w") ?: 0) / 20f }
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
                            "gridBefore" -> gridBefore = (x.intAttr("val") ?: 0).coerceIn(0, MAX_COLUMNS)
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
                        "gridSpan" -> span = (x.intAttr("val") ?: 1).coerceIn(1, MAX_COLUMNS)
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

    /** The next number of a footnote or endnote counted by [count], in the section being read, as [props] numbers them. */
    private fun number(count: NoteCount, props: NoteProps): Int {
        if (count.section < 0 || (props.restart == "eachSect" && count.section != readingSection)) count.value = (props.start ?: 1) - 1
        count.section = readingSection
        return ++count.value
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

        // This page's footnotes, under their separator: those going on from the page before first.
        private var noteSeparator: Block? = null
        private val pageNotes = ArrayList<Block>()
        private var notesHeight = 0
        /** Parts of footnotes that didn't fit on their page, for the next one. */
        private val carried = ArrayList<Block>()
        /** Notes placed: one referred to again (in a heading row repeated on each page) is placed once. */
        private val placedNotes = HashSet<Any>()
        /** How many footnotes begin on this page, for those numbered again on each page. */
        private var notesBegun = 0
        /** While endnotes go on from page to page: each page starts with their long line. */
        private var inEndnotes = false
        private val noteBlocks = HashMap<Pair<Any, String>, Block>()
        private val separators = HashMap<Boolean, Block>()

        private val left get() = section.left.units
        private val textWidth get() = (section.width - section.left - section.right).units
        private val textHeight get() = (section.height - section.top - section.bottom).units
        private val pageNumber get() = PageNumber(shownNumber, section.pageFormat, notesBegun)

        /** Where the text's room on this page ends: above its footnotes. */
        private val limit get() = bottom - notesHeight

        /**
         * A block waiting to go on the page, with its spacing and what it keeps with. One with the page's
         * number in it is laid out again by [renumbered] for the page it starts on, once that is known.
         */
        private inner class Placed(
            val block: Block,
            val before: Int = 0,
            val after: Int = 0,
            val style: String? = null,
            val contextual: Boolean = false,
            val keepNext: Boolean = false,
            val overlays: List<Floating> = emptyList(),
            val renumbered: ((PageNumber) -> Block)? = null,
        )

        fun add(item: DocItem) {
            when (item) {
                is DocPara -> {
                    if (item.props.pageBreakBefore == true && !empty && canvas != null) newPage(natural = false)
                    for (f in item.floats.filterNot { it.overText }) place(Placed(floatBlock(f, textWidth, textHeight, left)))
                    val p = item.props
                    // A very long paragraph goes in pieces, one after another, with its spacing around them all.
                    val pieces = paragraphBlock(item, textWidth.toInt(), pageNumber, textHeight, null).pieces()
                    val renumbered = if (pieces.size == 1 && hasPageNumber(item)) { page: PageNumber -> paragraphBlock(item, textWidth.toInt(), page, textHeight, null) } else null
                    for ((i, piece) in pieces.withIndex()) {
                        val last = i == pieces.lastIndex
                        place(
                            Placed(
                                piece,
                                if (i == 0) spaceBefore(p).units.toInt() else 0,
                                if (last) spaceAfter(p).units.toInt() else 0,
                                p.style ?: styles.defaultParagraphStyle,
                                p.contextual == true,
                                p.keepNext == true && last,
                                if (i == 0) item.floats.filter { it.overText } else emptyList(),
                                renumbered,
                            ),
                        )
                    }
                }
                is DocTable -> place(
                    Placed(
                        tableBlock(item, textWidth, pageNumber, textHeight, null),
                        renumbered = if (hasPageNumber(item)) { page -> tableBlock(item, textWidth, page, textHeight, null) } else null,
                    ),
                )
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
                if (!empty && y + need > limit && need <= textHeight) newPage(natural = true)
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
            // At the top of a page, the space before is kept as far as the paragraph still fits under it
            // (or, for one longer than the page, its first line): more would put the text below the page,
            // or split a short paragraph that fits.
            val room = (limit - y).toInt()
            val firstLine = (item.block as? ParagraphBlock)?.firstLineHeight ?: item.block.height
            val kept = if (item.block.height <= room) item.block.height else firstLine
            var gap = if (empty) (if (keepSpaceBefore) min(before, max(0, room - kept)) else 0) else before + after
            var block = item.renumbered?.invoke(pageNumber) ?: item.block
            var first = true
            while (true) {
                val parts = fit(block, gap, force = empty)
                if (parts == null) {
                    newPage(natural = true)
                    gap = 0
                    if (first) item.renumbered?.let { block = it(pageNumber) }
                    continue
                }
                draw(parts.first, item, gap, first)
                first = false
                y += gap + parts.first.height
                addNotes(parts.first.notes)
                block = parts.second ?: break
                newPage(natural = true)
                gap = 0
            }
            empty = false
            lastAfter = item.after
            lastStyle = item.style
            lastContextual = item.contextual
        }

        /**
         * The part of [block] that goes on this page, [gap] under the text before it, with room for the
         * footnotes it refers to; null when none of it does. Lines whose notes don't fit under them go on
         * to the next page, so a reference near the foot of the page takes its line along only when not
         * even its note's first lines fit, as in Word. With [force], something always goes.
         */
        private fun fit(block: Block, gap: Int, force: Boolean): Pair<Block, Block?>? {
            var space = (limit - y - gap).toInt()
            var tried = -1
            while (true) {
                val parts = if (block.height <= space) block to null else block.split(space, force) ?: return null
                val room = (bottom - y - gap - notesNeed(parts.first.notes)).toInt()
                if (parts.first.height <= room) return parts
                if (force && (room <= 0 || parts.first.height == tried)) return parts
                if (room <= 0) return null
                tried = parts.first.height
                space = room
            }
        }

        /**
         * How tall this page's footnotes must be for text referring to [spans] to go on it: the notes new
         * here in full, but the last, which can go on to the next page after its first lines (as many as
         * its paragraph keeps together: two, with widow and orphan control).
         */
        private fun notesNeed(spans: List<NoteSpan>): Int {
            val fresh = fresh(spans)
            if (fresh.isEmpty()) return notesHeight
            var need = notesHeight + if (noteSeparator == null) footnoteSeparator(false).height else 0
            fresh.forEachIndexed { i, span ->
                val block = noteBlock(span)
                need += if (i == fresh.lastIndex) block.leastHeight else block.height
            }
            return need
        }

        private fun fresh(spans: List<NoteSpan>) = spans.filter { it.note !in placedNotes }.distinctBy { it.note }

        /** Puts the footnotes [spans] refer to at the foot of the page; what doesn't fit goes on to the next. */
        private fun addNotes(spans: List<NoteSpan>) {
            for (span in fresh(spans)) {
                placedNotes += span.note
                if (span.note !in customMarked) notesBegun++
                val block = noteBlock(span)
                if (carried.isNotEmpty()) {
                    carried += block
                    continue
                }
                if (noteSeparator == null) startNotes(continuation = false)
                val room = (bottom - y - notesHeight).toInt()
                // As much as fits, keeping its lines together as its paragraphs say, unless the page has no room for that.
                val parts = if (block.height <= room) block to null else block.split(room, force = false) ?: block.split(room, force = true) ?: (block to null)
                addNote(parts.first)
                parts.second?.let { carried += it }
            }
        }

        /** Footnotes going on from the page before, under the long line; the text keeps at least an inch of the page. */
        private fun carryNotes() {
            val waiting = carried.toList()
            carried.clear()
            startNotes(continuation = true)
            val most = (bottom - top - 72f.units).toInt()
            for (block in waiting) {
                if (carried.isNotEmpty()) {
                    carried += block
                    continue
                }
                val room = most - notesHeight
                val parts = if (block.height <= room) block to null else block.split(room, force = pageNotes.isEmpty())
                if (parts == null) {
                    carried += block
                    continue
                }
                addNote(parts.first)
                parts.second?.let { carried += it }
            }
            if (pageNotes.isEmpty()) {
                noteSeparator = null
                notesHeight = 0
            }
        }

        private fun startNotes(continuation: Boolean) {
            val separator = footnoteSeparator(continuation)
            noteSeparator = separator
            notesHeight = separator.height
        }

        private fun addNote(block: Block) {
            pageNotes += block
            notesHeight += block.height
        }

        private fun footnoteSeparator(continuation: Boolean) = separators.getOrPut(continuation) { separatorBlock(footnotes, continuation) }

        /** The line above [notes]: the document's own, else a plain one; the long one above those going on from the page before. */
        private fun separatorBlock(notes: Notes, continuation: Boolean): Block {
            val plain = styles.paragraphRun(null, null).toStyle(theme)
            val items = (if (continuation) notes.continuation else notes.separator)
                ?: listOf(DocPara(ParaProps(after = 0f), listOf(SeparatorInline(continuation, plain)), null, plain, "tab", plain, emptyList()))
            return stack(items, textWidth.toInt(), pageNumber, textHeight, null)
        }

        /**
         * The note [span] marks, across the text, with [span]'s mark at its start. Kept by mark too: one
         * numbered again on each page is numbered again when its reference moves on to the next page.
         */
        private fun noteBlock(span: NoteSpan): Block = noteBlocks.getOrPut(span.note to span.mark) {
            val key = span.note as NoteKey
            stack(marked((if (key.endnote) endnotes else footnotes).content[key.id].orEmpty(), span.mark), textWidth.toInt(), pageNumber, textHeight, null)
        }

        /** This page's footnotes under their separator: at the foot of the page, or just under the text when the section says so. */
        private fun drawNotes(c: Canvas) {
            val separator = noteSeparator ?: return
            var at = if (section.footnotes.position == "beneathText") y else bottom - notesHeight
            separator.draw(c, left, at)
            at += separator.height
            for (block in pageNotes) {
                block.draw(c, left, at)
                at += block.height
            }
        }

        /** The endnotes referred to so far, under their separator, as text of their own. */
        private fun printEndnotes() {
            val due = endnotesDue.distinctBy { it.note }.filter { it.note !in placedNotes }
            endnotesDue.clear()
            if (due.isEmpty()) return
            flushPending()
            ensurePage()
            due.forEach { placedNotes += it.note }
            // The separator goes along with the first endnote's first line.
            val separator = separatorBlock(endnotes, continuation = false)
            if (!empty && y + separator.height + noteBlock(due.first()).leastHeight > limit) newPage(natural = true)
            put(Placed(separator))
            inEndnotes = true
            for (span in due) for (item in marked(endnotes.content[(span.note as NoteKey).id].orEmpty(), span.mark)) add(item)
            flushPending()
            inEndnotes = false
        }

        private fun draw(block: Block, item: Placed, gap: Int, first: Boolean) {
            val c = canvas ?: return
            // Pictures behind the text go under the paragraph they're anchored to, the others over it.
            if (first) for (overlay in item.overlays) if (overlay.behind) drawOverlay(c, overlay, y + gap)
            block.draw(c, left, y + gap)
            if (first) for (overlay in item.overlays) if (!overlay.behind) drawOverlay(c, overlay, y + gap)
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
            noteSeparator = null
            pageNotes.clear()
            notesHeight = 0
            notesBegun = 0
            if (carried.isNotEmpty()) carryNotes()
            // Endnotes going on from the page before start under their long line.
            if (inEndnotes) separatorBlock(endnotes, continuation = true).let {
                it.draw(c, left, y)
                y += it.height
            }
        }

        private fun finishPage() {
            val c = canvas ?: return
            drawNotes(c)
            pdf.finishPage()
            canvas = null
        }

        private fun nextSection() {
            flushPending()
            // Endnotes at the end of each section, unless the section leaves its own to the next.
            if (section.endnotes.position == "sectEnd" && !section.noEndnote) printEndnotes()
            val next = sections.getOrNull(++sectionIndex) ?: return
            val samePage = next.type == "continuous" && next.width == section.width && next.height == section.height
            if (!samePage) {
                finishPage()
                // A section starting on an odd (or even) page: when its first page's number wouldn't be, a
                // blank page of the section before goes first, as in Word.
                val parity = when (next.type) {
                    "oddPage" -> 1
                    "evenPage" -> 0
                    else -> null
                }
                if (parity != null && pdf.pageCount > 0 && (next.pageStart ?: (shownNumber + 1)).mod(2) != parity) {
                    startPage()
                    finishPage()
                }
            }
            section = next
            noteBlocks.clear()
            separators.clear()
            if (!samePage) {
                pageInSection = 0
                keepSpaceBefore = true
            }
        }

        private fun drawHeaderAndFooter(c: Canvas) {
            // Odd and even go by the number the page shows, as in Word; an even page without a header of its own has none.
            val kind = when {
                section.titlePage && pageInSection == 1 -> "first"
                evenAndOddHeaders && shownNumber % 2 == 0 -> "even"
                else -> "default"
            }
            val header = section.headers[kind]
            val footer = section.footers[kind]
            val headerOverlays = ArrayList<Floating>()
            val footerOverlays = ArrayList<Floating>()
            val head = header?.let { headerBlock(it, headerOverlays) }
            val foot = footer?.let { headerBlock(it, footerOverlays) }
            val headerAt = section.headerDistance.units
            val footerAt = (section.height - section.footerDistance).units - (foot?.height ?: 0)
            // Their pictures in front of or behind the text, such as a letterhead or a watermark, take no
            // room and go under everything, as in Word.
            for (f in headerOverlays) drawOverlay(c, f, headerAt)
            for (f in footerOverlays) drawOverlay(c, f, footerAt)
            head?.let { block ->
                block.draw(c, left, headerAt)
                top = max(top, headerAt + block.height)
            }
            foot?.let { block ->
                block.draw(c, left, footerAt)
                bottom = min(bottom, footerAt)
            }
            // However tall they are, the text keeps at least an inch of the page.
            if (bottom - top < 72f.units) {
                top = section.top.units
                bottom = (section.height - section.bottom).units
            }
        }

        /** A header or footer, laid out for this page (its page number); its pictures over the text go to [overlays]. */
        private fun headerBlock(part: String, overlays: MutableList<Floating>): Block? {
            val items = headers.getOrPut(part) {
                val found = ArrayList<DocItem>()
                pkg.xml(part, check)?.use { x -> Reader(part).blocks(x, null) { found += it } }
                found
            }
            if (items.isEmpty()) return null
            return stack(items, textWidth.toInt(), pageNumber, textHeight, null, overlays)
        }

        fun finish() {
            flushPending()
            printEndnotes()
            ensurePage()
            // Footnotes still going on, on pages of their own.
            while (carried.isNotEmpty()) newPage(natural = true)
            finishPage()
        }
    }

    /**
     * [picture] turned [turn] about its middle. It's drawn in its own box, centred where it goes: the
     * box it's given, or that box on its side when [sideways] (given the turned box).
     */
    private class TurnedPicture(private val picture: Picture, private val turn: Turn, private val sideways: Boolean) : Picture {
        override fun draw(canvas: Canvas, rect: RectF) {
            val cx = rect.centerX()
            val cy = rect.centerY()
            val w = if (sideways) rect.height() else rect.width()
            val h = if (sideways) rect.width() else rect.height()
            canvas.withRotation(turn.degrees, cx, cy) {
                withScale(if (turn.flipH) -1f else 1f, if (turn.flipV) -1f else 1f, cx, cy) {
                    picture.draw(this, RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2))
                }
            }
        }
    }

    /** Pictures where a group or drawing canvas [width] × [height] points big puts them, stretched with it. */
    private class GroupPicture(private val width: Float, private val height: Float, private val parts: List<Pair<Picture, OfficeRect>>) : Picture {
        override fun draw(canvas: Canvas, rect: RectF) {
            val sx = rect.width() / width
            val sy = rect.height() / height
            for ((picture, box) in parts) {
                picture.draw(canvas, RectF(rect.left + box.x * sx, rect.top + box.y * sy, rect.left + (box.x + box.width) * sx, rect.top + (box.y + box.height) * sy))
            }
        }
    }

    /** [picture] under a white veil [opacity] opaque: lighter, as Word washes out a watermark. */
    private class VeiledPicture(private val picture: Picture, private val opacity: Float) : Picture {
        override fun draw(canvas: Canvas, rect: RectF) {
            picture.draw(canvas, rect)
            canvas.drawRect(rect, Paint().apply { color = Color.WHITE; alpha = (opacity * 255).roundToInt() })
        }
    }

    /** Word's line above footnotes and endnotes, [width] units long, across the middle of its line. */
    private class RuleSpan(private val width: Int) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            fm?.let(paint::getFontMetricsInt)
            return width
        }

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val metrics = paint.fontMetrics
            val middle = y + (metrics.ascent + metrics.descent) / 2
            canvas.drawLine(x, middle, x + width, middle, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 0.5f.units })
        }
    }

    /** A picture on a line of its own. */
    private class FloatBlock(private val picture: Picture, private val width: Int, override val height: Int, private val x: Float) : Block {
        override fun draw(canvas: Canvas, x: Float, y: Float) = picture.draw(canvas, RectF(x + this.x, y, x + this.x + width, y + height))
        override fun split(space: Int, force: Boolean): Pair<Block, Block?>? = if (force) this to null else null
    }

    /**
     * [items] one under another in [width] units, as in a table cell or a header, on [behind] (the
     * shading under them, if any). Floating pictures go on lines of their own, but those in front of
     * or behind the text go to [overlays] when it's given.
     */
    private fun stack(items: List<DocItem>, width: Int, page: PageNumber, maxHeight: Float, behind: Int?, overlays: MutableList<Floating>? = null): Block {
        val placed = ArrayList<Spaced>()
        for (item in items) {
            // A table cell can hold a whole report: Cancel is heard while it's laid out.
            check()
            when (item) {
                is DocPara -> {
                    for (f in item.floats) {
                        if (f.overText && overlays != null) overlays += f
                        else placed += Spaced(floatBlock(f, width.toFloat(), maxHeight, 0f), 0, 0)
                    }
                    val pieces = paragraphBlock(item, width, page, maxHeight, behind).pieces()
                    for ((i, piece) in pieces.withIndex()) {
                        placed += Spaced(piece, if (i == 0) spaceBefore(item.props).units.toInt() else 0, if (i == pieces.lastIndex) spaceAfter(item.props).units.toInt() else 0)
                    }
                }
                is DocTable -> placed += Spaced(tableBlock(item, width.toFloat(), page, maxHeight, behind), 0, 0)
                else -> Unit
            }
        }
        return StackBlock(placed)
    }

    /** [items], a note's content, with [mark] where the note's own mark goes. */
    private fun marked(items: List<DocItem>, mark: String): List<DocItem> = items.map { item ->
        when (item) {
            is DocPara -> if (item.inlines.none { it is OwnMarkInline }) item else DocPara(
                item.props, item.inlines.map { if (it is OwnMarkInline) TextInline(mark, it.style) else it },
                item.label, item.labelStyle, item.labelSuffix, item.mark, item.floats,
            )
            is DocTable -> DocTable(
                item.props, item.grid,
                item.rows.map { row ->
                    DocRow(row.cells.map { DocCell(it.span, it.vMerge, it.fill, it.borders, it.verticalAlign, it.margins, marked(it.content, mark)) }, row.height, row.exact, row.header, row.gridBefore)
                },
            )
            else -> item
        }
    }

    /**
     * A floating picture on a line of its own, [width] units wide at most and [maxHeight] high, shrunk
     * to fit keeping its shape. [pageLeft] is the left margin, for pictures placed from the page's edge.
     */
    private fun floatBlock(f: Floating, width: Float, maxHeight: Float, pageLeft: Float): Block {
        val scale = min(1f, min(width / f.width.units, maxHeight / f.height.units))
        val w = max(1, (f.width.units * scale).toInt())
        val h = max(1, (f.height.units * scale).toInt())
        val x = when (f.h.align) {
            "center" -> (width - w) / 2
            "right", "outside" -> width - w
            "left", "inside" -> 0f
            else -> if (f.h.from == "page") f.h.offset.units - pageLeft else f.h.offset.units
        }.coerceIn(min(0f, width - w), max(0f, width - w))
        return FloatBlock(f.picture, w, h, x)
    }

    /** [picture] turned [turn]; [sideways] when the box it's drawn in is its turned box, not its own. */
    private fun turned(picture: Picture, turn: Turn, sideways: Boolean): Picture = if (turn.isNone) picture else TurnedPicture(picture, turn, sideways)

    /** Space before a paragraph, in points, with room for its top border. */
    private fun spaceBefore(p: ParaProps) = (p.before ?: 0f) + (p.borderTop?.let { (b, gap) -> if (b.width > 0f) b.width + gap else 0f } ?: 0f)

    /** Space after a paragraph, in points, with room for its bottom border. */
    private fun spaceAfter(p: ParaProps) = (p.after ?: 0f) + (p.borderBottom?.let { (b, gap) -> if (b.width > 0f) b.width + gap else 0f } ?: 0f)

    private fun rule(border: Pair<Border, Float>?): Rule? = border?.takeIf { it.first.width > 0f }?.let { (b, gap) -> Rule(b.width.units, b.color, gap.units) }

    /** [p] laid out [width] units wide on page [page], on [behind] (the shading under it, if any). */
    private fun paragraphBlock(p: DocPara, width: Int, page: PageNumber, maxHeight: Float, behind: Int?): ParagraphBlock {
        val shade = p.props.shading ?: behind
        val rich = RichText(shade)
        for (inline in p.inlines) when (inline) {
            is TextInline -> rich.append(inline.text, inline.style)
            is PageNumberInline -> rich.append(page.text(inline.format), inline.style)
            is PictureInline -> {
                // Pictures bigger than the space for text are shrunk to fit, keeping their shape.
                val w = inline.width.units
                val h = inline.height.units
                val scale = min(1f, min(width / w, maxHeight * 0.95f / h))
                rich.appendObject(PictureSpan(inline.picture, max(1, (w * scale).toInt()), max(1, (h * scale).toInt())), inline.style)
            }
            is NoteRefInline -> {
                val mark = inline.mark ?: ListNumbering.noteMark(page.nextNote(), inline.format, ListNumbering.Format.DECIMAL)
                // Endnotes are printed at the end, whichever page their reference is on.
                if (inline.key.endnote) rich.append(mark, inline.style) else rich.appendMark(mark, inline.style, inline.key)
            }
            // Its note's mark goes in before the note is laid out (see [marked]).
            is OwnMarkInline -> Unit
            is SeparatorInline -> rich.appendObject(RuleSpan(if (inline.continuation) width else min(width, SEPARATOR.units.toInt())), inline.style)
        }
        val props = p.props
        val left = (props.left ?: 0f).units
        val firstStart = left + (props.firstLine ?: 0f).units
        val defaultTab = 36f.units
        var tabs = props.tabs?.set.orEmpty().map { it.units }
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
            Label(text, p.labelStyle.copy(color = p.labelStyle.colorOn(shade)), firstStart.toInt())
        }
        alignedTabs(rich.text, props, textStart, width - (props.right ?: 0f).units, p.mark)?.let { tabs = it }
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
                // A paragraph starting with Hebrew or Arabic is still left to right unless it's set otherwise.
                directionFromText = false,
                // In a right-to-left paragraph, Word's left indent and hanging indent are on the right.
                mirrored = props.rtl == true,
                background = props.shading,
                tabs = tabs.map { it.toInt() },
                defaultTab = defaultTab.toInt(),
                avoidSingleLines = props.widowControl != false,
                ruleAbove = rule(props.borderTop),
                ruleBelow = rule(props.borderBottom),
            ),
        )
    }

    /**
     * The tab stops that put a one-line paragraph's [text], starting [start] units in, where Word's
     * right and centre stops put it, as plain left stops (Android's lines have only those): a header's
     * page number against the margin, say. Null when the paragraph has none of those stops, or more
     * than a line that ends by [end]. Android measures stops from where a line's text starts, so for a
     * first line starting past the left indent (indented, or after a list label) they're given that
     * much nearer.
     */
    private fun alignedTabs(text: CharSequence, props: ParaProps, start: Float, end: Float, style: TextStyle): List<Float>? {
        val stops = props.tabs?.takeIf { it.aligned.isNotEmpty() } ?: return null
        // A centred or right-aligned line moves as a whole: its stops are left as they are.
        if (props.rtl == true || props.align == Align.CENTER || props.align == Align.END || '\t' !in text || '\n' in text) return null
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).also { style.applyTo(it) }
        val widths = ArrayList<Float>()
        var from = 0
        for (i in 0..text.length) {
            if (i < text.length && text[i] != '\t') continue
            widths += Layout.getDesiredWidth(text, from, i, paint)
            from = i + 1
        }
        val positions = stops.positions(start, widths, 36f.units, 1f.units)
        if (positions.last() + widths.last() > end + 1f) return null
        val shift = max(0f, start - (props.left ?: 0f).units)
        return positions.map { it - shift }
    }

    /** [t] laid out in [available] units, on [behind] (the shading under it, if any). */
    private fun tableBlock(t: DocTable, available: Float, page: PageNumber, maxHeight: Float, behind: Int?): Block {
        if (t.rows.isEmpty()) return StackBlock(emptyList())
        val styleId = t.props.style ?: styles.defaultTableStyle
        val props = t.props.over(styles.table(styleId))
        val borders = props.borders ?: Borders()
        val margins = List(4) { props.margins?.get(it) ?: if (it % 2 == 0) 5.4f else 0f }
        val columns = t.rows.maxOf { row -> (row.gridBefore + row.cells.sumOf { it.span.toLong() }).coerceAtMost(MAX_COLUMNS.toLong()).toInt() }
        val availablePt = available / U
        val preferred = props.width?.let { if (props.widthShare) it * availablePt else it }
        // A little wider than the text is fine: Word lets tables reach into the margin.
        val widths = TableColumns.widths(t.grid, columns, availablePt * 1.04f, preferred).map { it.units }
        val tableWidth = widths.sum()
        // A right-to-left table has its first column on the right, and its indent from the right.
        val rtl = props.rtl == true
        val x = when (props.align) {
            Align.CENTER -> (available - tableWidth) / 2
            Align.END -> if (rtl) 0f else available - tableWidth
            else -> if (rtl) available - tableWidth - (props.indent ?: 0f).units else (props.indent ?: 0f).units
        }
        val starts = widths.runningFold(0f) { acc, w -> acc + w }
        // Which grid columns each row's cells start at, to find cells merged with the one below.
        val cellColumns = t.rows.map { row ->
            var col = row.gridBefore
            row.cells.map { cell -> col.also { col = (col + cell.span).coerceAtMost(MAX_COLUMNS) } }
        }
        fun mergedBelow(r: Int, col: Int): Boolean {
            val next = t.rows.getOrNull(r + 1) ?: return false
            val i = cellColumns[r + 1].indexOf(col)
            return i >= 0 && next.cells[i].vMerge == "continue"
        }
        val rows = t.rows.mapIndexed { r, row ->
            // A table thousands of rows long takes seconds to lay out: Cancel is heard meanwhile.
            check()
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
                    x = if (rtl) x + tableWidth - starts[end] else x + starts[col],
                    width = cellWidth,
                    content = if (continued || cell.content.isEmpty()) null
                    else stack(cell.content, max(1, (cellWidth - m[0] - m[2]).toInt()), page, maxHeight, cell.fill ?: behind),
                    fill = cell.fill,
                    top = if (continued) Border.NONE else own.top,
                    // A right-to-left table's left borders and margins are on the cells' right (their start).
                    left = if (rtl) own.right else own.left,
                    bottom = if (mergedBelow(r, cellColumns[r][i])) Border.NONE else own.bottom,
                    right = if (rtl) own.left else own.right,
                    verticalAlign = cell.verticalAlign,
                    padLeft = if (rtl) m[2] else m[0],
                    padTop = m[1],
                    padRight = if (rtl) m[0] else m[2],
                    padBottom = m[3],
                )
            }
            RowBlock(cells, (row.height ?: 0f).units.toInt(), row.exact)
        }
        val headerRows = t.rows.takeWhile { it.header }.size.takeIf { it < t.rows.size } ?: 0
        return TableBlock(rows, headerRows, 0f)
    }

    private companion object {
        /** Far more columns than Word lets a table have (63): only a crafted file has more. */
        const val MAX_COLUMNS = 1000

        /** The length of Word's line above footnotes and endnotes, in points. */
        const val SEPARATOR = 144f
    }
}
