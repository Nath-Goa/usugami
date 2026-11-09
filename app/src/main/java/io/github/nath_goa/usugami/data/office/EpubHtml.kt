package io.github.nath_goa.usugami.data.office

import io.github.nath_goa.usugami.core.CssNode
import io.github.nath_goa.usugami.core.CssSheet
import io.github.nath_goa.usugami.core.CssValues
import io.github.nath_goa.usugami.core.EpubPaths
import io.github.nath_goa.usugami.core.HtmlText
import io.github.nath_goa.usugami.core.ListNumbering
import io.github.nath_goa.usugami.core.SimpleCss
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import kotlin.math.max

/**
 * Where a block of a book goes across the page, from its HTML and CSS. Indents in points: [left] and
 * [right] hold [start] and [end], the indents of lists and block quotes, which go on the side a
 * paragraph starts and ends on (right and left for one that reads right to left). [align]'s START
 * and END are the left and right when [physical] (CSS's text-align: left and right), the paragraph's
 * own start and end otherwise.
 */
internal class BlockStyle(
    val align: Align,
    val left: Float,
    val right: Float,
    val textIndent: Float,
    val rtl: Boolean,
    val physical: Boolean = false,
    val start: Float = 0f,
    val end: Float = 0f,
)

/** What a book's page is made of, as read from its XHTML. */
internal sealed interface BookItem

/** A paragraph, heading ([heading] 1 to 6) or list item (with its [label]). */
internal class BookPara(
    val text: CharSequence,
    val style: BlockStyle,
    val base: TextStyle,
    val label: Pair<String, TextStyle>?,
    val heading: Int,
) : BookItem

/** A picture on a line of its own: [width] × [height] points at its natural size, or a [share] of the width the CSS asks for. */
internal class BookImage(val path: String, val width: Float, val height: Float, val share: Float?, val style: BlockStyle) : BookItem

internal class BookTable(val rows: List<List<BookCell>>, val style: BlockStyle) : BookItem

internal class BookCell(val items: List<BookEntry>, val span: Int, val rowSpan: Int, val header: Boolean)

/** A horizontal rule (hr), often a scene break. */
internal class BookRule(val style: BlockStyle) : BookItem

/** A page break the book's CSS asks for. */
internal data object BookBreak : BookItem

/** An item with the space to leave above it, in points, and the ids of the elements that start with it. */
internal class BookEntry(val item: BookItem, val before: Float, val ids: List<String>)

/**
 * Reads one XHTML document of a book, forgivingly, into [BookItem]s handed to [sink] as they are
 * read, so a long chapter needn't fit in memory. Understood: headings, paragraphs, line breaks,
 * bold, italic, underlined and struck text, superscripts, lists, block quotes, definition lists,
 * preformatted text, simple tables, pictures (also in SVG covers), rules, and links as text; from
 * CSS (linked, in style elements or attributes, with simple selectors): text-align, font-weight,
 * font-style, font-size, font-family's generic family, text-decoration, text-indent, vertical
 * margins and side margins, display:none, list-style-type, white-space and page breaks. Scripts,
 * forms, audio and video are left out.
 */
internal class XhtmlReader(
    private val path: String,
    /** The book's font size, in points. */
    private val base: Float,
    /** The width of the text, in points: what CSS percentages are of. */
    private val textWidth: Float,
    private val sheet: (String) -> CssSheet?,
    /** A picture's size in pixels, or null when it can't be decoded (such as SVG). */
    private val imageSize: (String) -> IntArray?,
    private val images: OfficeImages,
    private val check: () -> Unit,
    private val sink: (BookEntry) -> Unit,
) {
    /** A fixed-layout page's size, from the viewport in the document's head. */
    var viewport: Pair<Int, Int>? = null
        private set

    /** The document's first main heading (h1 to h3), for bookmarks when the book has no table of contents. */
    var firstHeading: String? = null
        private set

    /** Formatting inherited from the elements around. Sizes and indents in points. */
    private data class Inherited(
        val font: String,
        val size: Float,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val baseline: Int = 0,
        val highlight: Boolean = false,
        val align: Align = Align.START,
        /** Whether [align] is CSS's left or right: see [BlockStyle.physical]. */
        val physical: Boolean = false,
        val textIndent: Float = 0f,
        val left: Float = 0f,
        val right: Float = 0f,
        /** The parts of [left] and [right] that go on a paragraph's start and end side: see [BlockStyle]. */
        val start: Float = 0f,
        val end: Float = 0f,
        val pre: Boolean = false,
        val rtl: Boolean = false,
        val heading: Int = 0,
        val listFormat: ListNumbering.Format? = ListNumbering.Format.BULLET,
    ) {
        fun text() = TextStyle(
            font = font, size = size, bold = bold, italic = italic, underline = underline, strike = strike,
            baseline = baseline, background = if (highlight) HIGHLIGHT else null,
        )
    }

    private class Frame(val node: CssNode, val inherited: Inherited, val block: Boolean, val end: (() -> Unit)? = null)

    private val root = Inherited(font = "serif", size = base)
    private val frames = ArrayList<Frame>()
    private val sheets = ArrayList<CssSheet>()

    /** How the document's lines run, as its root or body says (see [CssValues.acrossFromVertical]); null when neither says. */
    private var writingMode: String? = null

    /** The html element's font size, which rem lengths are of, and its style attribute. */
    private var rem = base
    private var rootStyle: String? = null

    // Parts of the document that are skipped or read differently, by how deep in them the reader is.
    private var skip = 0
    private var headDepth = 0
    private var svgDepth = 0
    private var styleDepth = 0
    private var styleText = StringBuilder()

    // The paragraph being read.
    private var para: RichText? = null
    private var paraStyle: Inherited = root
    private var paraIds = ArrayList<String>()
    private var paraContinued = false
    private var afterSpace = true

    /** Where the paragraph ends in a space made of a line break after a Chinese or Japanese character; -1 when it doesn't. */
    private var wideBreakAt = -1

    /** Space before the next item, the largest of the margins met since the last one (as CSS collapses them). */
    private var space = 0f
    private val pendingIds = ArrayList<String>()
    private var label: Pair<String, TextStyle>? = null

    private class ListState(val format: ListNumbering.Format?, var counter: Int)
    private val lists = ArrayList<ListState>()

    private class TableState {
        val rows = ArrayList<List<BookCell>>()
        var row: ArrayList<BookCell>? = null
        val caption = ArrayList<BookEntry>()
    }
    private val tables = ArrayList<TableState>()
    /** Where items go inside table cells and captions; the [sink] otherwise. */
    private val sinks = ArrayList<MutableList<BookEntry>>()
    private var sinkFailure: Exception? = null

    /** Reads the document from [input]. A part that can't be parsed ends it: what came before is kept. */
    fun read(input: InputStream) {
        try {
            val p = LenientXml.parser(input)
            var events = 0
            while (true) {
                if (++events % 256 == 0) check()
                when (p.next()) {
                    XmlPullParser.START_TAG -> start(p)
                    XmlPullParser.END_TAG -> end(p.localName().lowercase())
                    XmlPullParser.TEXT -> text(p.text)
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
        } catch (e: Exception) {
            rethrowCancel(e)
            // The pages couldn't be written (a full disk, say): that's no fault of the document's.
            if (e === sinkFailure) throw e
            // Malformed beyond what the parser forgives.
        }
        skip = 0
        headDepth = 0
        svgDepth = 0
        styleDepth = 0
        while (frames.isNotEmpty()) frames.removeAt(frames.lastIndex).end?.invoke()
        flushPara()
    }

    private val current: Inherited get() = frames.lastOrNull()?.inherited ?: root
    private val currentBlock: Inherited get() = frames.lastOrNull { it.block }?.inherited ?: root

    private fun start(p: XmlPullParser) {
        val tag = p.localName().lowercase()
        // An empty element written HTML-style (<br>, <meta>) has no end tag: it isn't counted.
        if (skip > 0) return run { if (tag !in VOID) skip++ }
        if (styleDepth > 0) return run { styleDepth++ }
        if (tag == "style") {
            styleDepth = 1
            styleText = StringBuilder()
            return
        }
        // A missing </head> doesn't hide the body.
        if (headDepth > 0 && tag == "body") headDepth = 0
        if (headDepth > 0) {
            if (tag !in VOID) headDepth++
            head(tag, p)
            return
        }
        if (tag == "head") return run { headDepth = 1 }
        if (svgDepth > 0) {
            svgDepth++
            if (tag == "image") image(p, current, emptyMap(), fromSvg = true)
            return
        }
        if (tag == "body") restyleRoot()
        val parent = frames.lastOrNull()
        val inheritedFrom = current
        val id = p.attr("id")?.trim()?.takeIf { it.isNotEmpty() }
        val classes = p.attr("class")?.split(' ', '\t', '\n')?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        val node = CssNode(tag, id, classes, parent?.node)
        val inline = p.attr("style")
        if (tag == "html") rootStyle = inline
        val cascaded = if (sheets.isEmpty() && inline.isNullOrBlank()) emptyMap() else CssSheet.cascade(node, sheets, inline)
        // A book written in columns down the page says so on its root or body; its margins are then turned to suit lines across it.
        if (tag == "html" || tag == "body") CssValues.writingMode(cascaded)?.let { writingMode = it }
        val css = CssValues.acrossFromVertical(cascaded, writingMode)
        val display = css["display"]?.lowercase()
        // An empty element (a hidden picture, say) has no inside to skip: it is just left out.
        if (tag in SKIPPED || display == "none" || p.attr("hidden") != null) return run { if (tag !in VOID) skip = 1 }
        // Older books mark places with <a name="...">, which links lead to as they do to an id.
        val anchors = listOfNotNull(id, p.attr("name")?.trim()?.takeIf { tag == "a" && it.isNotEmpty() && it != id })
        val block = when (display) {
            "block", "list-item", "table", "flex", "grid" -> true
            "inline", "inline-block" -> false
            else -> tag in BLOCKS
        }
        if (anchors.isNotEmpty()) {
            // A block (or rule) starts below the paragraph being read: that one ends first, so its place isn't taken for the block's.
            if (block || tag == "hr") flushPara()
            (if (para != null) paraIds else pendingIds) += anchors
        }
        if (tag == "svg") return run { svgDepth = 1 }
        val inherited = inherit(tag, inheritedFrom, css, p, block)
        when (tag) {
            "img" -> {
                image(p, inherited, css, fromSvg = false)
                return
            }
            "br" -> {
                startPara()
                para!!.append("\n", inheritedFrom.text())
                afterSpace = true
                // Text split into lines with line breaks (a whole book in one paragraph, say) goes on as a new paragraph at one.
                if (para!!.text.length > SPLIT_AT) flushPara(continued = true)
                return
            }
            "hr" -> {
                flushPara()
                space = max(space, 0.5f * inherited.size)
                emit(BookRule(blockStyle(inherited)))
                space = max(space, 0.5f * inherited.size)
                return
            }
            "wbr", "col", "input", "area", "source", "track", "embed", "param", "meta", "link", "base" -> return
            // An object shows its picture when it is one Android can draw; else, as in a browser, what it
            // holds in its place (the PNG or the words standing in for an SVG chart, say).
            "object" -> p.attr("data")?.takeIf { data -> EpubPaths.resolve(path, data)?.first?.let(imageSize) != null }?.let { data ->
                image(p, inherited, css, fromSvg = false, source = data)
                skip = 1
                return
            }
            "q" -> {
                appendText("\u201C", inherited)
                frames += Frame(node, inherited, block = false) { appendText("\u201D", inherited) }
                return
            }
        }
        val table = tables.lastOrNull()
        when {
            tag == "table" -> startTable(node, inherited, css)
            tag == "tr" && table != null -> {
                table.row?.let { if (it.isNotEmpty()) table.rows += it }
                table.row = ArrayList()
                frames += Frame(node, inherited, block = true) {
                    flushPara()
                    table.row?.let { if (it.isNotEmpty()) table.rows += it }
                    table.row = null
                }
            }
            (tag == "td" || tag == "th") && table != null -> startCell(node, inherited, p, table, header = tag == "th")
            tag == "caption" && table != null -> {
                flushPara()
                sinks += table.caption
                frames += Frame(node, inherited, block = true) {
                    flushPara()
                    sinks.removeAt(sinks.lastIndex)
                }
            }
            tag == "ul" || tag == "ol" -> {
                val ordered = tag == "ol"
                val format = listStyle(css)?.let { HtmlText.listFormat(it, ordered) } ?: if (css.isListNone()) null
                else HtmlText.listFormat(p.attr("type")?.takeIf { ordered }, ordered)
                lists += ListState(format, (p.attr("start")?.trim()?.toIntOrNull() ?: 1) - 1)
                startBlock(node, inherited.copy(listFormat = format), css, tag) { lists.removeAt(lists.lastIndex) }
            }
            tag == "li" -> {
                val list = lists.lastOrNull()
                val format = listStyle(css)?.let { HtmlText.listFormat(it, list?.format != ListNumbering.Format.BULLET) }
                    ?: if (css.isListNone()) null else inheritedFrom.listFormat
                startBlock(node, inherited, css, tag) { label = null }
                val counter = p.attr("value")?.trim()?.toIntOrNull() ?: ((list?.counter ?: 0) + 1)
                list?.counter = counter
                label = when (format) {
                    null, ListNumbering.Format.NONE -> null
                    ListNumbering.Format.BULLET -> BULLETS[(lists.size - 1).coerceIn(0, BULLETS.lastIndex)]
                    else -> ListNumbering.format(counter, format) + "."
                }?.let { it to inherited.text().copy(underline = false, strike = false, baseline = 0) }
            }
            block -> startBlock(node, inherited, css, tag)
            else -> frames += Frame(node, inherited, block = false)
        }
    }

    /**
     * The end of element [name]. An empty element (img, br...) has nothing to end: HTML-style, it has
     * no end tag, and the parser then gives its parent's end tag first; anything malformed files put
     * inside one is read as if outside it.
     */
    private fun end(name: String) {
        if (skip > 0) return run { if (name !in VOID) skip-- }
        if (styleDepth > 0) {
            if (--styleDepth == 0) {
                val parsed = SimpleCss.parse(styleText.toString())
                if (!parsed.isEmpty) sheets += parsed
                styleText = StringBuilder()
            }
            return
        }
        if (headDepth > 0) return run { if (name == "head") headDepth = 0 else if (name !in VOID) headDepth-- }
        if (svgDepth > 0) return run { svgDepth-- }
        if (name in VOID) return
        frames.removeLastOrNull()?.end?.invoke()
    }

    /**
     * The html element's font size and writing mode, read again once the head's style sheets are (it
     * starts before them): the body's sizes, in rems or not, are of it, and its margins follow the mode.
     */
    private fun restyleRoot() {
        val i = frames.indexOfFirst { it.node.tag == "html" }
        if (i < 0) return
        val frame = frames[i]
        val css = CssSheet.cascade(frame.node, sheets, rootStyle)
        CssValues.writingMode(css)?.let { writingMode = it }
        val size = css["font-size"]
        rem = CssValues.rootSize(size, base) ?: return
        CssValues.fontSize(size, base, base)?.let { frames[i] = Frame(frame.node, frame.inherited.copy(size = it), frame.block, frame.end) }
    }

    private fun head(tag: String, p: XmlPullParser) {
        when (tag) {
            // An alternate style sheet (a book's horizontal look besides its vertical one, say) is only for readers to pick.
            "link" -> if (p.attr("rel")?.lowercase()?.split(' ')?.let { "stylesheet" in it && "alternate" !in it } == true) {
                val target = p.attr("href")?.let { EpubPaths.resolve(path, it) }?.first
                target?.let(sheet)?.let { if (!it.isEmpty) sheets += it }
            }
            "meta" -> if (p.attr("name")?.lowercase() == "viewport") viewport = HtmlText.viewport(p.attr("content"))
        }
    }

    private fun text(raw: String) {
        if (styleDepth > 0) {
            if (styleText.length < MAX_STYLE) styleText.append(raw)
            return
        }
        if (skip > 0 || headDepth > 0 || svgDepth > 0) return
        val inherited = current
        if (inherited.pre) {
            val text = raw.replace("\r\n", "\n").replace('\r', '\n')
            // Like a long paragraph, long preformatted text goes on as a new one, at a line's end.
            append(text, '\n', inherited)
            return
        }
        appendText(raw, inherited)
    }

    private fun appendText(raw: String, inherited: Inherited) {
        val sofar = para?.text
        val before = if (sofar.isNullOrEmpty()) 0 else Character.codePointBefore(sofar, sofar.length)
        val text = HtmlText.collapse(raw, afterSpace || para == null, before)
        if (text.isEmpty()) return
        // A line break that ended the text before, in another element, goes too between Chinese or Japanese characters.
        if (sofar != null && wideBreakAt == sofar.length - 1 && HtmlText.isWide(text.codePointAt(0))) sofar.delete(wideBreakAt, sofar.length)
        // A paragraph as long as a few pages goes on as a new one, after a space: laying out a huge one is slow.
        append(text, ' ', inherited)
        wideBreakAt = if (HtmlText.endsInWideBreak(raw, before)) para?.text?.length?.minus(1) ?: -1 else -1
    }

    /**
     * Adds [text] to the paragraph. Once that is longer than [SPLIT_AT], it goes on as a new
     * paragraph after the next [breakAfter], even in the middle of [text] (a whole chapter can be one).
     */
    private fun append(text: String, breakAfter: Char, inherited: Inherited) {
        var from = 0
        while (from < text.length) {
            startPara()
            val rich = para!!
            val at = text.indexOf(breakAfter, from + max(0, SPLIT_AT - rich.text.length))
            val to = if (at < 0) text.length else at + 1
            rich.append(text.substring(from, to), inherited.text())
            afterSpace = text[to - 1] == breakAfter
            if (rich.text.length > SPLIT_AT && afterSpace) flushPara(continued = true)
            from = to
        }
    }

    private fun startPara() {
        if (para != null) return
        para = RichText()
        paraStyle = currentBlock
        afterSpace = true
    }

    /** Ends the paragraph being read; a [continued] one goes on in a new paragraph, without indent or space. */
    private fun flushPara(continued: Boolean = false) {
        val rich = para ?: return
        para = null
        wideBreakAt = -1
        val text = rich.text
        var end = text.length
        while (end > 0 && text[end - 1].let { it == ' ' || it == '\n' }) end--
        if (end < text.length) text.delete(end, text.length)
        pendingIds += paraIds
        paraIds = ArrayList()
        val style = paraStyle
        val wasContinued = paraContinued
        paraContinued = false
        // Only white space: nothing to show. A no-break space alone keeps an empty line, as books use it.
        if (text.none { !it.isWhitespace() || it == '\u00A0' }) return
        if (wasContinued) space = 0f
        emit(BookPara(text, blockStyle(style, indent = !wasContinued), style.text(), label, style.heading))
        label = null
        if (firstHeading == null && style.heading in 1..3) firstHeading = HtmlText.collapse(text.toString(), afterSpace = true).trim().takeIf { it.isNotEmpty() }
        if (continued) {
            para = RichText()
            paraStyle = style
            paraContinued = true
        }
    }

    private fun emit(item: BookItem) {
        val ids = if (pendingIds.isEmpty()) emptyList() else pendingIds.toList().also { pendingIds.clear() }
        val entry = BookEntry(item, space, ids)
        space = 0f
        sinks.lastOrNull()?.add(entry) ?: deliver(entry)
    }

    private fun pageBreak() {
        flushPara()
        // Not inside a table: a cell can't be split that way.
        if (sinks.isNotEmpty()) return
        deliver(BookEntry(BookBreak, 0f, emptyList()))
        space = 0f
    }

    /** Hands [entry] to the [sink], whose failures [read] lets through rather than taking them for the document's. */
    private fun deliver(entry: BookEntry) {
        try {
            sink(entry)
        } catch (e: Exception) {
            sinkFailure = e
            throw e
        }
    }

    private fun blockStyle(style: Inherited, indent: Boolean = true) =
        BlockStyle(style.align, style.left, style.right, if (indent) style.textIndent else 0f, style.rtl, style.physical, style.start, style.end)

    private fun startBlock(node: CssNode, inherited: Inherited, css: Map<String, String>, tag: String, onEnd: (() -> Unit)? = null) {
        flushPara()
        val em = inherited.size
        // A list in a list has no space of its own around it. (The list starting is on the list of lists already.)
        val nested = (tag == "ul" || tag == "ol") && lists.size > 1
        val ua = if (nested) 0f else UA_MARGINS[tag] ?: 0f
        val (top, bottom) = margins(css, em, ua * em, if (tag == "li") 0f else ua * em)
        if (CssValues.pageBreak(css["break-before"])) pageBreak()
        space = max(space, top)
        val breakAfter = CssValues.pageBreak(css["break-after"])
        frames += Frame(node, inherited, block = true) {
            flushPara()
            onEnd?.invoke()
            space = max(space, bottom)
            if (breakAfter) pageBreak()
        }
    }

    private fun margins(css: Map<String, String>, em: Float, top: Float, bottom: Float): Pair<Float, Float> {
        var t = top
        var b = bottom
        // The margin shorthand comes as its sides (SimpleCss), so the cascade decides each.
        CssValues.length(css["margin-top"], em, base, rem = rem)?.let { t = it }
        CssValues.length(css["margin-bottom"], em, base, rem = rem)?.let { b = it }
        // Negative margins and huge ones (some books push a title down with a third of a page) are tamed.
        return t.coerceIn(0f, base * 8f) to b.coerceIn(0f, base * 8f)
    }

    private fun startTable(node: CssNode, inherited: Inherited, css: Map<String, String>) {
        flushPara()
        if (CssValues.pageBreak(css["break-before"])) pageBreak()
        val em = inherited.size
        val (top, bottom) = margins(css, em, 0.8f * em, 0.8f * em)
        space = max(space, top)
        // Kept for the table (or its caption): its cells start without space of their own.
        val before = space
        val state = TableState()
        tables += state
        frames += Frame(node, inherited, block = true) {
            flushPara()
            state.row?.let { if (it.isNotEmpty()) state.rows += it }
            tables.removeAt(tables.lastIndex)
            space = max(space, before)
            for (entry in state.caption) {
                pendingIds.addAll(0, entry.ids)
                space = max(space, entry.before)
                emit(entry.item)
            }
            if (state.rows.isNotEmpty()) emit(BookTable(state.rows, blockStyle(inherited)))
            space = max(space, bottom)
        }
    }

    private fun startCell(node: CssNode, inherited: Inherited, p: XmlPullParser, table: TableState, header: Boolean) {
        flushPara()
        val items = ArrayList<BookEntry>()
        sinks += items
        space = 0f
        val span = (p.attr("colspan")?.trim()?.toIntOrNull() ?: 1).coerceIn(1, 64)
        val rowSpan = (p.attr("rowspan")?.trim()?.toIntOrNull() ?: 1).coerceIn(1, 1000)
        // A cell's text is indented from the cell, not from the page.
        frames += Frame(node, inherited.copy(left = 0f, right = 0f, start = 0f, end = 0f, textIndent = 0f), block = true) {
            flushPara()
            sinks.removeAt(sinks.lastIndex)
            space = 0f
            (table.row ?: ArrayList<BookCell>().also { table.row = it }) += BookCell(items, span, rowSpan, header)
        }
    }

    /** A picture: an img, an SVG image ([fromSvg]) or an object, whose [source] is given. */
    private fun image(p: XmlPullParser, inherited: Inherited, css: Map<String, String>, fromSvg: Boolean, source: String? = null) {
        val src = source ?: (if (fromSvg) p.attr("href") else p.attr("src") ?: p.attr("href")) ?: return
        val target = EpubPaths.resolve(path, src)?.first
        val pixels = target?.let(imageSize)
        if (target == null || pixels == null) {
            // Not a picture in the book that Android can draw (an SVG, or one written into the page as
            // data: or kept on the web): its description stands in, if it has one.
            p.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }?.let { appendText(" $it ", inherited.copy(italic = true)) }
            return
        }
        // Pictures are as big as at 16 px to the font size the book was written for.
        val scale = base / 16f
        var width = pixels[0] * scale
        var height = pixels[1] * scale
        var share: Float? = null
        if (!fromSvg) {
            val cssWidth = css["width"] ?: p.attr("width")?.trim()?.let { if (it.isNotEmpty() && it.all(Char::isDigit)) "${it}px" else it }
            val cssHeight = css["height"] ?: p.attr("height")?.trim()?.let { if (it.isNotEmpty() && it.all(Char::isDigit)) "${it}px" else it }
            val percent = cssWidth?.trim()?.takeIf { it.endsWith("%") }?.removeSuffix("%")?.trim()?.toFloatOrNull()
            val w = cssWidth?.let { CssValues.length(it, inherited.size, base, rem = rem) }?.takeIf { it > 0f }
            val h = cssHeight?.takeIf { !it.trim().endsWith("%") }?.let { CssValues.length(it, inherited.size, base, rem = rem) }?.takeIf { it > 0f }
            when {
                percent != null && percent > 0f -> share = (percent / 100f).coerceIn(0.05f, 1f)
                w != null -> {
                    height *= w / width
                    width = w
                }
                h != null -> {
                    width *= h / height
                    height = h
                }
            }
        }
        // A small picture in a line of text, such as a symbol, stays in the line.
        val rich = para
        if (rich != null && share == null && height <= inherited.size * 2.2f && rich.hasContent) {
            val picture = ImagePicture(images, target, width, height, null)
            rich.appendObject(PictureSpan(picture, max(1, width.units.toInt()), max(1, height.units.toInt())), inherited.text())
            afterSpace = false
            return
        }
        flushPara()
        // A little room around it, as most books' style sheets give pictures.
        space = max(space, 0.5f * inherited.size)
        emit(BookImage(target, width, height, share, blockStyle(currentBlock)))
        space = max(space, 0.5f * inherited.size)
    }

    // The list-style shorthand comes as its list-style-type (SimpleCss).
    private fun listStyle(css: Map<String, String>): String? = css["list-style-type"]

    private fun Map<String, String>.isListNone() = this["list-style-type"]?.trim() == "none"

    private fun inherit(tag: String, parent: Inherited, css: Map<String, String>, p: XmlPullParser, block: Boolean): Inherited {
        var size = parent.size * (UA_SIZES[tag] ?: 1f)
        CssValues.fontSize(css["font-size"], parent.size, base, rem)?.let { size = it }
        var bold = parent.bold || tag in BOLD
        CssValues.bold(css["font-weight"])?.let { bold = it }
        var italic = parent.italic || tag in ITALIC
        when (css["font-style"]?.trim()?.lowercase()) {
            "italic", "oblique" -> italic = true
            "normal" -> italic = false
        }
        var underline = parent.underline || tag == "u" || tag == "ins"
        var strike = parent.strike || tag == "s" || tag == "strike" || tag == "del"
        (css["text-decoration"] ?: css["text-decoration-line"])?.lowercase()?.let { decoration ->
            if ("none" in decoration) {
                underline = false
                strike = false
            }
            if ("underline" in decoration) underline = true
            if ("line-through" in decoration) strike = true
        }
        var font = if (tag in MONO) "monospace" else parent.font
        CssValues.fontFamily(css["font-family"])?.let { font = it }
        var baseline = when (tag) {
            "sup" -> 1
            "sub" -> -1
            else -> parent.baseline
        }
        when (css["vertical-align"]?.trim()?.lowercase()) {
            "super" -> baseline = 1
            "sub" -> baseline = -1
            "baseline" -> baseline = 0
        }
        var align = if (tag in CENTERED) Align.CENTER else parent.align
        var physical = parent.physical && tag !in CENTERED
        (css["text-align"] ?: p.attr("align"))?.trim()?.lowercase()?.let {
            align = when (it) {
                "center", "middle" -> Align.CENTER
                "right", "end" -> Align.END
                "justify" -> Align.JUSTIFY
                "left", "start" -> Align.START
                else -> align
            }
            // Left and right stay where they are in a paragraph that reads right to left; start and end don't.
            physical = when (it) {
                "left", "right" -> true
                "center", "middle", "justify", "start", "end" -> false
                else -> physical
            }
        }
        var textIndent = parent.textIndent
        CssValues.length(css["text-indent"], size, base, textWidth, rem)?.let { textIndent = it.coerceIn(-textWidth / 3, textWidth / 3) }
        var left = parent.left
        var right = parent.right
        var start = parent.start
        var end = parent.end
        if (block) {
            val indent = (UA_INDENTS[tag] ?: 0f) * size
            left += indent
            start += indent
            if (tag == "blockquote") {
                right += indent
                end += indent
            }
            CssValues.length(css["margin-left"], size, base, textWidth, rem)?.let { if (it > 0f) left += it }
            CssValues.length(css["margin-right"], size, base, textWidth, rem)?.let { if (it > 0f) right += it }
            // Always room for the text: no more than half the width in indents.
            val most = textWidth * 0.5f
            if (left + right > most) {
                val shrink = most / (left + right)
                left *= shrink
                right *= shrink
                start *= shrink
                end *= shrink
            }
        }
        var pre = parent.pre || tag in PRE
        when (css["white-space"]?.trim()?.lowercase()) {
            "pre", "pre-wrap", "pre-line", "break-spaces" -> pre = true
            "normal", "nowrap" -> pre = false
        }
        val rtl = when (p.attr("dir")?.trim()?.lowercase()) {
            "rtl" -> true
            "ltr" -> false
            else -> parent.rtl
        }
        val heading = tag.takeIf { it.length == 2 && it[0] == 'h' && it[1] in '1'..'6' }?.let { it[1] - '0' } ?: parent.heading
        return parent.copy(
            font = font, size = size, bold = bold, italic = italic, underline = underline, strike = strike, baseline = baseline,
            highlight = parent.highlight || tag == "mark", align = align, physical = physical, textIndent = textIndent,
            left = left, right = right, start = start, end = end,
            pre = pre, rtl = rtl, heading = heading,
        )
    }

    private companion object {
        const val SPLIT_AT = 12_000
        const val MAX_STYLE = 256 * 1024
        const val HIGHLIGHT = 0xFFFFF59D.toInt()
        val BULLETS = listOf("\u2022", "\u25E6", "\u25AA")

        /** Elements that are always empty. */
        val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
        val SKIPPED = setOf(
            "script", "noscript", "template", "iframe", "video", "audio", "canvas", "button", "select", "textarea",
            "map", "rt", "rp", "datalist", "dialog", "colgroup", "title",
            // A formula's TeX or other source beside the MathML shown, and MathML in epub:switch, whose fallback is shown.
            "annotation", "annotation-xml", "case",
        )
        val BLOCKS = setOf(
            "html", "body", "div", "p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "ul", "ol", "li", "dl", "dt", "dd", "pre",
            "section", "article", "aside", "header", "footer", "nav", "figure", "figcaption", "address", "center", "main", "hgroup",
            "details", "summary", "fieldset", "legend", "form", "table", "caption", "tr", "td", "th", "thead", "tbody", "tfoot",
            "listing", "xmp", "plaintext",
        )
        val BOLD = setOf("b", "strong", "h1", "h2", "h3", "h4", "h5", "h6", "th", "dt")
        val ITALIC = setOf("i", "em", "cite", "dfn", "var", "address")
        val MONO = setOf("code", "tt", "kbd", "samp", "pre", "listing", "xmp", "plaintext")
        val PRE = setOf("pre", "listing", "xmp", "plaintext")
        val CENTERED = setOf("center", "th", "caption", "figcaption")
        val UA_SIZES = mapOf(
            "h1" to 1.8f, "h2" to 1.5f, "h3" to 1.25f, "h4" to 1.1f, "h5" to 1f, "h6" to 0.9f,
            "small" to 0.85f, "big" to 1.15f, "figcaption" to 0.9f, "code" to 0.92f, "tt" to 0.92f, "kbd" to 0.92f,
            "samp" to 0.92f, "pre" to 0.9f,
        )
        /** Space above and below, in em of the element's own font size. */
        val UA_MARGINS = mapOf(
            "p" to 0.5f, "h1" to 0.8f, "h2" to 0.8f, "h3" to 0.8f, "h4" to 0.8f, "h5" to 0.8f, "h6" to 0.8f,
            "blockquote" to 0.6f, "ul" to 0.5f, "ol" to 0.5f, "dl" to 0.5f, "li" to 0.15f, "pre" to 0.5f, "figure" to 0.6f,
            "address" to 0.5f, "fieldset" to 0.5f,
        )
        /** Indents on the start side (for block quotes, both sides), in em: see [BlockStyle]. */
        val UA_INDENTS = mapOf("blockquote" to 1.5f, "ul" to 1.8f, "ol" to 1.8f, "dd" to 1.8f, "figure" to 0f)
    }
}
