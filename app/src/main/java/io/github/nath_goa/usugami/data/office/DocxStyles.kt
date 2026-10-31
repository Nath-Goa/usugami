package io.github.nath_goa.usugami.data.office

import android.graphics.Color
import io.github.nath_goa.usugami.core.ListNumbering
import io.github.nath_goa.usugami.core.OfficeColors
import io.github.nath_goa.usugami.core.SymbolFonts

/** Run (character) formatting from a Word file. Null means "not set here": it comes from further up. */
internal data class RunProps(
    val font: String? = null,
    val size: Float? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val color: Int? = null,
    val background: Int? = null,
    val baseline: Int? = null,
    val caps: Boolean? = null,
    val smallCaps: Boolean? = null,
    val hidden: Boolean? = null,
    val spacing: Float? = null,
    /** The character style (w:rStyle), for direct formatting only. */
    val style: String? = null,
) {
    /** These properties, with [base]'s where these aren't set. */
    fun over(base: RunProps?): RunProps = if (base == null) this else RunProps(
        font ?: base.font, size ?: base.size, bold ?: base.bold, italic ?: base.italic, underline ?: base.underline,
        strike ?: base.strike, color ?: base.color, background ?: base.background, baseline ?: base.baseline,
        caps ?: base.caps, smallCaps ?: base.smallCaps, hidden ?: base.hidden, spacing ?: base.spacing, style ?: base.style,
    )

    fun toStyle(theme: OfficeTheme): TextStyle = TextStyle(
        font = theme.font(font) ?: theme.minorFont,
        // Word's own default, for files that don't say.
        size = size ?: 10f,
        bold = bold == true,
        italic = italic == true,
        underline = underline == true,
        strike = strike == true,
        color = color ?: Color.BLACK,
        background = background,
        baseline = baseline ?: 0,
        caps = caps == true,
        smallCaps = smallCaps == true,
        spacing = spacing ?: 0f,
    )

    companion object {
        /** Reads w:rPr, the reader being at it. */
        fun read(x: OfficeXml, theme: OfficeTheme): RunProps {
            var p = RunProps()
            x.children {
                p = when (x.name) {
                    "rStyle" -> p.copy(style = x.attr("val"))
                    "rFonts" -> p.copy(font = x.attr("ascii") ?: x.attr("asciiTheme") ?: x.attr("hAnsi") ?: x.attr("hAnsiTheme") ?: x.attr("cs") ?: x.attr("eastAsia"))
                    "b" -> p.copy(bold = x.flag())
                    "i" -> p.copy(italic = x.flag())
                    "u" -> p.copy(underline = x.attr("val").let { it == null || it != "none" })
                    "strike", "dstrike" -> p.copy(strike = x.flag())
                    "caps" -> p.copy(caps = x.flag())
                    "smallCaps" -> p.copy(smallCaps = x.flag())
                    "vanish" -> p.copy(hidden = x.flag())
                    "sz" -> x.intAttr("val")?.let { p.copy(size = it / 2f) } ?: p
                    "color" -> (OfficeColors.hex(x.attr("val")) ?: themeColor(x, theme))?.let { p.copy(color = it) } ?: p
                    "highlight" -> p.copy(background = OfficeColors.highlight(x.attr("val")) ?: p.background)
                    "shd" -> OfficeColors.hex(x.attr("fill"))?.let { p.copy(background = p.background ?: it) } ?: p
                    "vertAlign" -> p.copy(baseline = when (x.attr("val")) { "superscript" -> 1; "subscript" -> -1; else -> 0 })
                    "spacing" -> x.intAttr("val")?.let { p.copy(spacing = it / 20f) } ?: p
                    else -> p
                }
            }
            return p
        }

        /** A w:color given only as a theme colour, such as "accent1", with its shade or tint. */
        private fun themeColor(x: OfficeXml, theme: OfficeTheme): Int? {
            val key = when (val name = x.attr("themeColor") ?: return null) {
                "text1", "dark1" -> "dk1"
                "background1", "light1" -> "lt1"
                "text2", "dark2" -> "dk2"
                "background2", "light2" -> "lt2"
                "hyperlink" -> "hlink"
                "followedHyperlink" -> "folHlink"
                else -> name
            }
            val color = theme.colors[key] ?: return null
            val shade = x.attr("themeShade")?.toIntOrNull(16)
            val tint = x.attr("themeTint")?.toIntOrNull(16)
            return when {
                shade != null -> OfficeColors.modify(color, listOf("lumMod" to shade * 100_000 / 255))
                tint != null -> OfficeColors.modify(color, listOf("lumMod" to tint * 100_000 / 255, "lumOff" to (255 - tint) * 100_000 / 255))
                else -> color
            }
        }
    }
}

/** Paragraph formatting from a Word file. Lengths in points. Null means "not set here". */
internal data class ParaProps(
    val style: String? = null,
    val align: Align? = null,
    val before: Float? = null,
    val after: Float? = null,
    val line: LineSpacing? = null,
    val left: Float? = null,
    val right: Float? = null,
    /** Positive: the first line starts further in; negative: it hangs out (as list items do). */
    val firstLine: Float? = null,
    val numId: Int? = null,
    val level: Int? = null,
    val keepNext: Boolean? = null,
    val pageBreakBefore: Boolean? = null,
    val contextual: Boolean? = null,
    val widowControl: Boolean? = null,
    val shading: Int? = null,
    val rtl: Boolean? = null,
    val tabs: List<Float>? = null,
    /** How the paragraph mark looks: what an empty paragraph's height comes from. */
    val mark: RunProps? = null,
    /** Borders above and below, with their distance from the text in points. */
    val borderTop: Pair<Border, Float>? = null,
    val borderBottom: Pair<Border, Float>? = null,
) {
    fun over(base: ParaProps?): ParaProps = if (base == null) this else ParaProps(
        style ?: base.style, align ?: base.align, before ?: base.before, after ?: base.after, line ?: base.line,
        left ?: base.left, right ?: base.right, firstLine ?: base.firstLine, numId ?: base.numId, level ?: base.level,
        keepNext ?: base.keepNext, pageBreakBefore ?: base.pageBreakBefore, contextual ?: base.contextual,
        widowControl ?: base.widowControl, shading ?: base.shading, rtl ?: base.rtl, tabs ?: base.tabs, mark?.over(base.mark) ?: base.mark,
        borderTop ?: base.borderTop, borderBottom ?: base.borderBottom,
    )

    companion object {
        /** Reads w:pPr, the reader being at it. [section] is called with a w:sectPr inside it. */
        fun read(x: OfficeXml, theme: OfficeTheme, section: (OfficeXml) -> Unit = { }): ParaProps {
            var p = ParaProps()
            x.children {
                p = when (x.name) {
                    "pStyle" -> p.copy(style = x.attr("val"))
                    "jc" -> p.copy(align = when (x.attr("val")) {
                        "center" -> Align.CENTER
                        "right", "end" -> Align.END
                        "both", "distribute", "justify", "thaiDistribute", "lowKashida", "mediumKashida", "highKashida" -> Align.JUSTIFY
                        else -> Align.START
                    })
                    "spacing" -> {
                        val auto = x.attr("lineRule").let { it == null || it == "auto" }
                        val line = x.intAttr("line")?.let { if (auto) LineSpacing.Multiple(it / 240f) else if (x.attr("lineRule") == "exact") LineSpacing.Exact(it / 20f) else LineSpacing.AtLeast(it / 20f) }
                        p.copy(
                            before = if (x.flag("beforeAutospacing", missing = false)) 14f else x.intAttr("before")?.let { it / 20f } ?: p.before,
                            after = if (x.flag("afterAutospacing", missing = false)) 14f else x.intAttr("after")?.let { it / 20f } ?: p.after,
                            line = line ?: p.line,
                        )
                    }
                    "ind" -> p.copy(
                        left = (x.intAttr("left") ?: x.intAttr("start"))?.let { it / 20f } ?: p.left,
                        right = (x.intAttr("right") ?: x.intAttr("end"))?.let { it / 20f } ?: p.right,
                        firstLine = x.intAttr("hanging")?.let { -it / 20f } ?: x.intAttr("firstLine")?.let { it / 20f } ?: p.firstLine,
                    )
                    "numPr" -> {
                        var numId = p.numId
                        var level = p.level
                        x.children {
                            when (x.name) {
                                "numId" -> numId = x.intAttr("val")
                                "ilvl" -> level = x.intAttr("val")
                            }
                        }
                        p.copy(numId = numId, level = level)
                    }
                    "keepNext" -> p.copy(keepNext = x.flag())
                    "pageBreakBefore" -> p.copy(pageBreakBefore = x.flag())
                    "contextualSpacing" -> p.copy(contextual = x.flag())
                    "widowControl" -> p.copy(widowControl = x.flag())
                    "bidi" -> p.copy(rtl = x.flag())
                    "shd" -> p.copy(shading = OfficeColors.hex(x.attr("fill")))
                    "pBdr" -> {
                        var top = p.borderTop
                        var bottom = p.borderBottom
                        x.children {
                            val style = x.attr("val")
                            val border = if (style == null || style == "nil" || style == "none") Border.NONE
                            else Border(((x.intAttr("sz") ?: 4) / 8f).coerceIn(0.25f, 6f), OfficeColors.hex(x.attr("color")) ?: Color.BLACK)
                            val space = (x.intAttr("space") ?: 0).toFloat().coerceIn(0f, 31f)
                            when (x.name) {
                                "top" -> top = border to space
                                "bottom" -> bottom = border to space
                            }
                        }
                        p.copy(borderTop = top, borderBottom = bottom)
                    }
                    "tabs" -> {
                        val stops = ArrayList<Float>()
                        x.children { if (x.name == "tab" && x.attr("val") != "clear") x.intAttr("pos")?.let { stops += it / 20f } }
                        p.copy(tabs = stops.sorted())
                    }
                    "rPr" -> p.copy(mark = RunProps.read(x, theme))
                    "sectPr" -> {
                        section(x)
                        p
                    }
                    else -> p
                }
            }
            return p
        }
    }
}

/** A table border: [width] in points. A width of 0 is a border explicitly turned off. */
internal data class Border(val width: Float, val color: Int) {
    companion object {
        val NONE = Border(0f, Color.TRANSPARENT)
    }
}

/** The borders of a table or a cell; null sides aren't set here. */
internal data class Borders(
    val top: Border? = null, val left: Border? = null, val bottom: Border? = null, val right: Border? = null,
    val insideH: Border? = null, val insideV: Border? = null,
) {
    fun over(base: Borders?): Borders = if (base == null) this else Borders(
        top ?: base.top, left ?: base.left, bottom ?: base.bottom, right ?: base.right, insideH ?: base.insideH, insideV ?: base.insideV,
    )

    companion object {
        fun read(x: OfficeXml): Borders {
            var b = Borders()
            x.children {
                val style = x.attr("val")
                val border = if (style == null || style == "nil" || style == "none") Border.NONE else Border(
                    ((x.intAttr("sz") ?: 4) / 8f).coerceIn(0.25f, 6f) * (if (style == "double") 1.5f else 1f),
                    OfficeColors.hex(x.attr("color")) ?: Color.BLACK,
                )
                b = when (x.name) {
                    "top" -> b.copy(top = border)
                    "left", "start" -> b.copy(left = border)
                    "bottom" -> b.copy(bottom = border)
                    "right", "end" -> b.copy(right = border)
                    "insideH" -> b.copy(insideH = border)
                    "insideV" -> b.copy(insideV = border)
                    else -> b
                }
            }
            return b
        }
    }
}

/** Table formatting: from w:tblPr, or a table style's. Lengths in points. */
internal data class TableProps(
    val style: String? = null,
    val borders: Borders? = null,
    /** Cell margins: left, top, right, bottom. */
    val margins: List<Float?>? = null,
    val indent: Float? = null,
    val align: Align? = null,
    /** Preferred width: in points, or as a share of the page's text width when [widthShare]. */
    val width: Float? = null,
    val widthShare: Boolean = false,
) {
    fun over(base: TableProps?): TableProps = if (base == null) this else TableProps(
        style ?: base.style, borders?.over(base.borders) ?: base.borders,
        margins?.let { m -> List(4) { m[it] ?: base.margins?.get(it) } } ?: base.margins,
        indent ?: base.indent, align ?: base.align, width ?: base.width, if (width != null) widthShare else base.widthShare,
    )

    companion object {
        fun read(x: OfficeXml): TableProps {
            var t = TableProps()
            x.children {
                t = when (x.name) {
                    "tblStyle" -> t.copy(style = x.attr("val"))
                    "tblBorders" -> t.copy(borders = Borders.read(x))
                    "tblCellMar" -> t.copy(margins = readMargins(x))
                    "tblInd" -> t.copy(indent = x.intAttr("w")?.let { it / 20f })
                    "jc" -> t.copy(align = when (x.attr("val")) { "center" -> Align.CENTER; "right", "end" -> Align.END; else -> Align.START })
                    "tblW" -> when (x.attr("type")) {
                        "pct" -> x.attr("w")?.let { w -> (w.removeSuffix("%").toFloatOrNull()?.let { if (w.endsWith("%")) it / 100f else it / 5000f }) }
                            ?.let { t.copy(width = it, widthShare = true) } ?: t
                        "dxa", null -> x.intAttr("w")?.takeIf { it > 0 }?.let { t.copy(width = it / 20f, widthShare = false) } ?: t
                        else -> t
                    }
                    else -> t
                }
            }
            return t
        }

        /** w:tblCellMar or w:tcMar: left, top, right, bottom, in points. */
        fun readMargins(x: OfficeXml): List<Float?> {
            val m = arrayOfNulls<Float>(4)
            x.children {
                val value = x.intAttr("w")?.let { it / 20f } ?: return@children
                when (x.name) {
                    "left", "start" -> m[0] = value
                    "top" -> m[1] = value
                    "right", "end" -> m[2] = value
                    "bottom" -> m[3] = value
                }
            }
            return m.toList()
        }
    }
}

private class StyleDef(val type: String, val basedOn: String?, val para: ParaProps, val run: RunProps, val table: TableProps?, val isDefault: Boolean)

/**
 * Word's styles (styles.xml): document defaults, then paragraph, character and table styles, each
 * possibly based on another.
 */
internal class DocxStyles private constructor(
    private val styles: Map<String, StyleDef>,
    private val defaultRun: RunProps,
    private val defaultPara: ParaProps,
    val theme: OfficeTheme,
) {
    val defaultParagraphStyle: String? = styles.entries.firstOrNull { it.value.type == "paragraph" && it.value.isDefault }?.key
    val defaultTableStyle: String? = styles.entries.firstOrNull { it.value.type == "table" && it.value.isDefault }?.key

    private val paraCache = HashMap<String, ParaProps>()
    private val runCache = HashMap<String, RunProps>()
    private val tableCache = HashMap<String, TableProps>()

    /** Everything a style and the styles it's based on set, nearest first. */
    private fun chain(id: String?): List<StyleDef> {
        val out = ArrayList<StyleDef>()
        var next = id
        val seen = HashSet<String>()
        while (next != null && seen.add(next) && seen.size < 32) {
            val style = styles[next] ?: break
            out += style
            next = style.basedOn
        }
        return out
    }

    fun para(id: String?): ParaProps = paraCache.getOrPut(id.orEmpty()) { chain(id).fold(ParaProps()) { acc, s -> acc.over(s.para) } }
    fun run(id: String?): RunProps = runCache.getOrPut(id.orEmpty()) { chain(id).fold(RunProps()) { acc, s -> acc.over(s.run) } }
    fun table(id: String?): TableProps = tableCache.getOrPut(id.orEmpty()) { chain(id).fold(TableProps()) { acc, s -> acc.over(s.table) } }

    /**
     * A paragraph's formatting: [direct], then its style, inside a table the table style, then the
     * document defaults. Table style formatting gives way to a paragraph style, but not to the
     * default paragraph style ("Normal"), as in Word.
     */
    fun resolvePara(direct: ParaProps, tableStyle: String?): ParaProps {
        val id = direct.style ?: defaultParagraphStyle
        val own = para(id)
        val table = tableStyle?.let(::para)
        val styled = if (table != null && id == defaultParagraphStyle) table.over(own) else own.over(table)
        return direct.over(styled).over(defaultPara)
    }

    /** The run formatting a paragraph's text starts from: its style's, then the table style's, then the defaults. */
    fun paragraphRun(paraStyle: String?, tableStyle: String?): RunProps {
        val id = paraStyle ?: defaultParagraphStyle
        val own = run(id)
        val table = tableStyle?.let(::run)
        val styled = if (table != null && id == defaultParagraphStyle) table.over(own) else own.over(table)
        return styled.over(defaultRun)
    }

    companion object {
        fun read(pkg: OfficePackage, path: String?, theme: OfficeTheme, check: () -> Unit): DocxStyles {
            val styles = HashMap<String, StyleDef>()
            var defaultRun = RunProps()
            var defaultPara = ParaProps()
            path?.let { pkg.xml(it, check) }?.use { x ->
                x.children {
                    when (x.name) {
                        "docDefaults" -> x.children {
                            when (x.name) {
                                "rPrDefault" -> x.children { if (x.name == "rPr") defaultRun = RunProps.read(x, theme) }
                                "pPrDefault" -> x.children { if (x.name == "pPr") defaultPara = ParaProps.read(x, theme) }
                            }
                        }
                        "style" -> {
                            val id = x.attr("styleId") ?: return@children
                            val type = x.attr("type") ?: "paragraph"
                            val isDefault = x.flag("default", missing = false)
                            var basedOn: String? = null
                            var para = ParaProps()
                            var run = RunProps()
                            var table: TableProps? = null
                            x.children {
                                when (x.name) {
                                    "basedOn" -> basedOn = x.attr("val")
                                    "pPr" -> para = ParaProps.read(x, theme)
                                    "rPr" -> run = RunProps.read(x, theme)
                                    "tblPr" -> table = TableProps.read(x)
                                }
                            }
                            styles[id] = StyleDef(type, basedOn, para, run, table, isDefault)
                        }
                    }
                }
            }
            return DocxStyles(styles, defaultRun, defaultPara, theme)
        }
    }
}

/** One level of a Word list: how its number or bullet looks, and how far it's indented. */
internal class ListLevel(
    val format: ListNumbering.Format,
    val text: String,
    val start: Int,
    val para: ParaProps,
    val run: RunProps,
    /** What comes between the number and the text: "tab", "space" or "nothing". */
    val suffix: String,
)

/**
 * Word's lists (numbering.xml), and their running counts. Lists sharing a definition continue each
 * other's numbering unless they restart it, as in Word.
 */
internal class DocxNumbering private constructor(
    private val abstracts: Map<Int, Map<Int, ListLevel>>,
    private val nums: Map<Int, Pair<Int, Map<Int, Int>>>,
    private val overrides: Map<Int, Map<Int, ListLevel>>,
) {
    private val counters = HashMap<String, IntArray>()

    fun level(numId: Int, level: Int): ListLevel? {
        val (abstractId, _) = nums[numId] ?: return null
        return overrides[numId]?.get(level) ?: abstracts[abstractId]?.get(level)
    }

    /** The next label of list [numId] at [level], counting it. Null when there's no such list. */
    fun next(numId: Int, level: Int): String? {
        val lvl = level(numId, level) ?: return null
        val (abstractId, starts) = nums.getValue(numId)
        val key = if (starts.isEmpty() && overrides[numId].isNullOrEmpty()) "a$abstractId" else "n$numId"
        val counter = counters.getOrPut(key) { IntArray(9) { -1 } }
        val at = level.coerceIn(0, 8)
        fun startOf(l: Int) = starts[l] ?: level(numId, l)?.start ?: 1
        counter[at] = if (counter[at] < 0) startOf(at) else counter[at] + 1
        for (deeper in at + 1 until 9) counter[deeper] = -1
        for (above in 0 until at) if (counter[above] < 0) counter[above] = startOf(above)
        if (lvl.format == ListNumbering.Format.BULLET) return SymbolFonts.bullet(lvl.run.font, lvl.text)
        if (lvl.format == ListNumbering.Format.NONE && !lvl.text.contains('%')) return lvl.text
        val formats = (0..at).map { level(numId, it)?.format ?: ListNumbering.Format.DECIMAL }
        return ListNumbering.wordLabel(lvl.text, counter.copyOf(at + 1), formats)
    }

    companion object {
        val NONE = DocxNumbering(emptyMap(), emptyMap(), emptyMap())

        fun read(pkg: OfficePackage, path: String?, theme: OfficeTheme, check: () -> Unit): DocxNumbering {
            val x = path?.let { pkg.xml(it, check) } ?: return NONE
            val abstracts = HashMap<Int, Map<Int, ListLevel>>()
            val nums = HashMap<Int, Pair<Int, Map<Int, Int>>>()
            val overrides = HashMap<Int, Map<Int, ListLevel>>()
            x.use {
                x.children {
                    when (x.name) {
                        "abstractNum" -> {
                            val id = x.intAttr("abstractNumId") ?: return@children
                            val levels = HashMap<Int, ListLevel>()
                            x.children { if (x.name == "lvl") readLevel(x, theme)?.let { levels[it.first] = it.second } }
                            abstracts[id] = levels
                        }
                        "num" -> {
                            val id = x.intAttr("numId") ?: return@children
                            var abstractId = -1
                            val starts = HashMap<Int, Int>()
                            val levels = HashMap<Int, ListLevel>()
                            x.children {
                                when (x.name) {
                                    "abstractNumId" -> abstractId = x.intAttr("val") ?: -1
                                    "lvlOverride" -> {
                                        val level = x.intAttr("ilvl") ?: 0
                                        x.children {
                                            when (x.name) {
                                                "startOverride" -> x.intAttr("val")?.let { starts[level] = it }
                                                "lvl" -> readLevel(x, theme)?.let { levels[level] = it.second }
                                            }
                                        }
                                    }
                                }
                            }
                            nums[id] = abstractId to starts
                            if (levels.isNotEmpty()) overrides[id] = levels
                        }
                    }
                }
            }
            return DocxNumbering(abstracts, nums, overrides)
        }

        private fun readLevel(x: OfficeXml, theme: OfficeTheme): Pair<Int, ListLevel>? {
            val level = x.intAttr("ilvl") ?: return null
            var format = ListNumbering.Format.DECIMAL
            var text = "%${level + 1}."
            var start = 1
            var para = ParaProps()
            var run = RunProps()
            var suffix = "tab"
            x.children {
                when (x.name) {
                    "numFmt" -> format = ListNumbering.wordFormat(x.attr("val"))
                    "lvlText" -> text = x.attr("val").orEmpty()
                    "start" -> start = x.intAttr("val") ?: 1
                    "suff" -> suffix = x.attr("val") ?: "tab"
                    "pPr" -> para = ParaProps.read(x, theme)
                    "rPr" -> run = RunProps.read(x, theme)
                }
            }
            return level to ListLevel(format, text, start, para, run, suffix)
        }
    }
}
