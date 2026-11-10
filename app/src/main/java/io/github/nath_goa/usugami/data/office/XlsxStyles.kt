package io.github.nath_goa.usugami.data.office

import android.graphics.Color
import io.github.nath_goa.usugami.core.SheetColors
import io.github.nath_goa.usugami.core.SheetStrings

/**
 * A colour as a spreadsheet's styles give it: as RGB, by theme colour or by its number in the
 * palette, lightened or darkened by [tint]. [auto] is the automatic colour.
 */
internal class SheetColor(val rgb: Int?, val theme: Int?, val indexed: Int?, val tint: Double, val auto: Boolean) {
    companion object {
        /** Reads a colour element (color, fgColor, bgColor...), the reader being at it. */
        fun read(x: OfficeXml) = SheetColor(
            SheetColors.rgb(x.attr("rgb")),
            x.intAttr("theme"),
            x.intAttr("indexed"),
            x.attr("tint")?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0,
            x.flag("auto", missing = false),
        )
    }
}

/**
 * Turns [SheetColor]s into ARGB, with the workbook's theme, and with the palette its styles give
 * when they give one (only old files do), else Excel's own.
 */
internal class SheetPalette(private val theme: OfficeTheme, private val custom: List<Int>) {
    /** [color] as ARGB; null for the automatic colour or one that can't be read. */
    fun argb(color: SheetColor?): Int? {
        if (color == null || color.auto) return null
        val base = color.rgb
            ?: color.theme?.let { SheetColors.themeKey(it) }?.let { theme.colors[it] }
            ?: color.indexed?.let { custom.getOrNull(it) ?: SheetColors.indexed(it) }
            ?: return null
        return SheetColors.tint(base, color.tint)
    }
}

/** A font as a cell draws it, with everything resolved. Size in points. */
internal data class SheetFont(
    val name: String?,
    val size: Float,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strike: Boolean,
    val color: Int,
    /** 1 for superscript, -1 for subscript. */
    val baseline: Int,
    /** Underlined twice, as totals often are. */
    val doubleUnderline: Boolean = false,
) {
    val style: TextStyle get() = TextStyle(name, size, bold, italic, underline, strike, color, baseline = baseline)

    companion object {
        /** Excel's own default, for a file that gives none. */
        val DEFAULT = SheetFont("Calibri", 11f, bold = false, italic = false, underline = false, strike = false, color = Color.BLACK, baseline = 0)
    }
}

/**
 * Font properties as a style or a run of rich text gives them; null means "as the font underneath":
 * the default font for a style's, the cell's font for a run's.
 */
internal class FontProps(
    val name: String? = null,
    val size: Float? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val color: SheetColor? = null,
    val baseline: Int? = null,
    /** "minor" or "major": the theme's body or heading font, for a font named by the theme alone. */
    val scheme: String? = null,
    val doubleUnderline: Boolean? = null,
) {
    /** These properties over [base], with colours from [palette] and theme fonts from [theme]. */
    fun over(base: SheetFont, palette: SheetPalette, theme: OfficeTheme): SheetFont = SheetFont(
        name = name ?: scheme?.let { if (it == "major") theme.majorFont else theme.minorFont } ?: base.name,
        size = size ?: base.size,
        bold = bold ?: base.bold,
        italic = italic ?: base.italic,
        underline = underline ?: base.underline,
        strike = strike ?: base.strike,
        color = palette.argb(color) ?: if (color != null) Color.BLACK else base.color,
        baseline = baseline ?: base.baseline,
        doubleUnderline = doubleUnderline ?: base.doubleUnderline,
    )

    companion object {
        /** Reads a font element (styles' font, or a run's rPr), the reader being at it. */
        fun read(x: OfficeXml): FontProps {
            var name: String? = null
            var size: Float? = null
            var bold: Boolean? = null
            var italic: Boolean? = null
            var underline: Boolean? = null
            var doubleUnderline: Boolean? = null
            var strike: Boolean? = null
            var color: SheetColor? = null
            var baseline: Int? = null
            var scheme: String? = null
            x.children {
                when (x.name) {
                    "name", "rFont" -> name = x.attr("val")?.takeIf { it.isNotBlank() }
                    "sz" -> size = x.attr("val")?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }?.coerceIn(1f, 409f)
                    "b" -> bold = x.flag()
                    "i" -> italic = x.flag()
                    "u" -> {
                        val kind = x.attr("val")
                        underline = kind != "none"
                        doubleUnderline = kind == "double" || kind == "doubleAccounting"
                    }
                    "strike" -> strike = x.flag()
                    "color" -> color = SheetColor.read(x)
                    "vertAlign" -> baseline = when (x.attr("val")) { "superscript" -> 1; "subscript" -> -1; else -> 0 }
                    "scheme" -> scheme = x.attr("val")?.takeIf { it == "major" || it == "minor" }
                }
            }
            return FontProps(name, size, bold, italic, underline, strike, color, baseline, scheme, doubleUnderline)
        }
    }
}

/**
 * A line along a side of a cell: [width] in points, [dashes] its lengths on and off in points (null
 * for a solid line), and [double] for two thin lines [width] apart.
 */
internal class CellLine(val width: Float, val color: Int, val dashes: FloatArray?, val double: Boolean = false) {
    companion object {
        /**
         * The line of border style [style] in [color]; null for none. Widths are about Excel's in
         * print, where a thin line is the finest it draws but hairlines.
         */
        fun of(style: String?, color: Int): CellLine? = when (style) {
            "hair" -> CellLine(0.3f, color, null)
            "thin" -> CellLine(0.6f, color, null)
            "medium" -> CellLine(1.4f, color, null)
            "thick" -> CellLine(2.2f, color, null)
            "double" -> CellLine(2f, color, null, double = true)
            "dotted" -> CellLine(0.6f, color, floatArrayOf(0.75f, 0.75f))
            "dashed" -> CellLine(0.6f, color, floatArrayOf(2.25f, 0.75f))
            "mediumDashed" -> CellLine(1.4f, color, floatArrayOf(6.75f, 2.25f))
            "dashDot" -> CellLine(0.6f, color, floatArrayOf(6.75f, 2.25f, 2.25f, 2.25f))
            "mediumDashDot", "slantDashDot" -> CellLine(1.4f, color, floatArrayOf(6.75f, 2.25f, 2.25f, 2.25f))
            "dashDotDot" -> CellLine(0.6f, color, floatArrayOf(6.75f, 2.25f, 2.25f, 2.25f, 2.25f, 2.25f))
            "mediumDashDotDot" -> CellLine(1.4f, color, floatArrayOf(6.75f, 2.25f, 2.25f, 2.25f, 2.25f, 2.25f))
            else -> null
        }
    }
}

/** The lines around a cell, and across it. Null sides have none. */
internal class CellBorders(
    val left: CellLine?,
    val top: CellLine?,
    val right: CellLine?,
    val bottom: CellLine?,
    /** From the bottom left corner to the top right one, and from the top left to the bottom right. */
    val diagonalUp: CellLine?,
    val diagonalDown: CellLine?,
)

/** How a cell's text lines up across it. [GENERAL] puts text on the left (on the right when it reads right to left), numbers on the right and TRUE or FALSE in the middle. */
internal enum class SheetAlign { GENERAL, LEFT, CENTER, RIGHT, FILL, JUSTIFY, CENTER_ACROSS, DISTRIBUTED }

/** How a cell's text lines up from top to bottom. */
internal enum class SheetVAlign { TOP, CENTER, BOTTOM, JUSTIFY, DISTRIBUTED }

/** A cell format (an entry of cellXfs), with everything it refers to resolved. */
internal class CellFormat(
    val numberFormat: String,
    val font: SheetFont,
    val fill: Int?,
    val borders: CellBorders?,
    val horizontal: SheetAlign,
    val vertical: SheetVAlign,
    val wrap: Boolean,
    /** Steps of three spaces' width in from the side the text lines up with. */
    val indent: Int,
    val shrink: Boolean,
    /** Degrees: 1 to 90 turn the text up, 91 to 180 turn it down by the amount over 90, and 255 stacks its letters. */
    val rotation: Int,
) {
    /** Whether a cell in this format shows on paper even when empty. */
    val showsEmpty: Boolean get() = fill != null || borders != null

    /** Whether its text goes on several lines in its cell, as wrapped and justified text does. */
    val wraps: Boolean
        get() = wrap || horizontal == SheetAlign.JUSTIFY || horizontal == SheetAlign.DISTRIBUTED ||
            vertical == SheetVAlign.JUSTIFY || vertical == SheetVAlign.DISTRIBUTED
}

/**
 * A spreadsheet's cell formats (styles.xml): number formats, fonts, fills, borders, and the
 * cellXfs that combine them with alignment. Cells refer to formats by their place in cellXfs.
 * Differential formats (conditional formatting, table styles) aren't read. Each list is read up to
 * [MAX_ENTRIES], past Excel's own limit of 64,000 formats: more come only from a crafted file.
 */
internal class SheetStyles private constructor(
    private val formats: List<CellFormat>,
    val defaultFont: SheetFont,
    val palette: SheetPalette,
) {
    /** Cell format [index]; the first one (the Normal style) for one that doesn't exist. */
    fun format(index: Int): CellFormat = formats.getOrNull(index) ?: formats[0]

    private class Alignment(
        val horizontal: SheetAlign,
        val vertical: SheetVAlign,
        val wrap: Boolean,
        val indent: Int,
        val shrink: Boolean,
        val rotation: Int,
    )

    private class Xf(val numFmtId: Int, val fontId: Int, val fillId: Int, val borderId: Int, val styleXf: Int, val alignment: Alignment?)

    private class FillDef(val pattern: String?, val fg: SheetColor?, val bg: SheetColor?, val gradient: List<SheetColor>)

    private class BorderDef(val sides: Map<String, Pair<String, SheetColor?>>, val up: Boolean, val down: Boolean)

    companion object {
        /** Reads styles.xml at [path], with built-in number formats as [numbers] writes them; a workbook without one gets Excel's defaults. */
        fun read(pkg: OfficePackage, path: String?, theme: OfficeTheme, numbers: CellNumbers, check: () -> Unit): SheetStyles {
            val numFmts = HashMap<Int, String>()
            val fonts = ArrayList<FontProps>()
            val fills = ArrayList<FillDef>()
            val borders = ArrayList<BorderDef>()
            val styleXfs = ArrayList<Xf>()
            val cellXfs = ArrayList<Xf>()
            val custom = ArrayList<Int>()
            path?.let { pkg.xml(it, check) }?.use { x ->
                x.children {
                    when (x.name) {
                        "numFmts" -> x.children {
                            val id = x.intAttr("numFmtId")
                            val code = x.attr("formatCode")
                            if (x.name == "numFmt" && id != null && code != null && numFmts.size < MAX_ENTRIES) numFmts[id] = SheetStrings.unescape(code)
                        }
                        "fonts" -> x.children { if (x.name == "font" && fonts.size < MAX_ENTRIES) fonts += FontProps.read(x) }
                        "fills" -> x.children { if (x.name == "fill" && fills.size < MAX_ENTRIES) fills += readFill(x) }
                        "borders" -> x.children { if (x.name == "border" && borders.size < MAX_ENTRIES) borders += readBorder(x) }
                        "cellStyleXfs" -> x.children { if (x.name == "xf" && styleXfs.size < MAX_ENTRIES) styleXfs += readXf(x) }
                        "cellXfs" -> x.children { if (x.name == "xf" && cellXfs.size < MAX_ENTRIES) cellXfs += readXf(x) }
                        "colors" -> x.children {
                            if (x.name == "indexedColors") x.children { if (x.name == "rgbColor" && custom.size < MAX_ENTRIES) custom += SheetColors.rgb(x.attr("rgb")) ?: Color.BLACK }
                        }
                    }
                }
            }
            val palette = SheetPalette(theme, custom)
            val defaultFont = fonts.firstOrNull()?.over(SheetFont.DEFAULT.copy(name = theme.minorFont ?: SheetFont.DEFAULT.name), palette, theme)
                ?: SheetFont.DEFAULT
            val resolvedFonts = fonts.map { it.over(defaultFont, palette, theme) }
            val resolvedFills = fills.map { fillColor(it, palette) }
            val resolvedBorders = borders.map { resolveBorder(it, palette) }
            val formats = cellXfs.ifEmpty { listOf(Xf(0, 0, 0, 0, 0, null)) }.map { xf ->
                val alignment = xf.alignment ?: styleXfs.getOrNull(xf.styleXf)?.alignment
                CellFormat(
                    numberFormat = numFmts[xf.numFmtId] ?: numbers.builtIn(xf.numFmtId),
                    font = resolvedFonts.getOrNull(xf.fontId) ?: defaultFont,
                    fill = resolvedFills.getOrNull(xf.fillId),
                    borders = resolvedBorders.getOrNull(xf.borderId),
                    horizontal = alignment?.horizontal ?: SheetAlign.GENERAL,
                    vertical = alignment?.vertical ?: SheetVAlign.BOTTOM,
                    wrap = alignment?.wrap == true,
                    indent = alignment?.indent ?: 0,
                    shrink = alignment?.shrink == true,
                    rotation = alignment?.rotation ?: 0,
                )
            }
            return SheetStyles(formats, defaultFont, palette)
        }

        private const val MAX_ENTRIES = 65_536

        private fun readXf(x: OfficeXml): Xf {
            val numFmtId = x.intAttr("numFmtId") ?: 0
            val fontId = x.intAttr("fontId") ?: 0
            val fillId = x.intAttr("fillId") ?: 0
            val borderId = x.intAttr("borderId") ?: 0
            val styleXf = x.intAttr("xfId") ?: 0
            var alignment: Alignment? = null
            x.children {
                if (x.name == "alignment") {
                    alignment = Alignment(
                        horizontal = when (x.attr("horizontal")) {
                            "left" -> SheetAlign.LEFT
                            "center" -> SheetAlign.CENTER
                            "right" -> SheetAlign.RIGHT
                            "fill" -> SheetAlign.FILL
                            "justify" -> SheetAlign.JUSTIFY
                            "centerContinuous" -> SheetAlign.CENTER_ACROSS
                            "distributed" -> SheetAlign.DISTRIBUTED
                            else -> SheetAlign.GENERAL
                        },
                        vertical = when (x.attr("vertical")) {
                            "top" -> SheetVAlign.TOP
                            "center" -> SheetVAlign.CENTER
                            "justify" -> SheetVAlign.JUSTIFY
                            "distributed" -> SheetVAlign.DISTRIBUTED
                            else -> SheetVAlign.BOTTOM
                        },
                        wrap = x.flag("wrapText", missing = false),
                        indent = (x.intAttr("indent") ?: 0).coerceIn(0, 250),
                        shrink = x.flag("shrinkToFit", missing = false),
                        rotation = x.intAttr("textRotation") ?: 0,
                    )
                }
            }
            return Xf(numFmtId, fontId, fillId, borderId, styleXf, alignment)
        }

        private fun readFill(x: OfficeXml): FillDef {
            var pattern: String? = null
            var fg: SheetColor? = null
            var bg: SheetColor? = null
            val gradient = ArrayList<SheetColor>()
            x.children {
                when (x.name) {
                    "patternFill" -> {
                        pattern = x.attr("patternType") ?: "none"
                        x.children {
                            when (x.name) {
                                "fgColor" -> fg = SheetColor.read(x)
                                "bgColor" -> bg = SheetColor.read(x)
                            }
                        }
                    }
                    "gradientFill" -> x.children { if (x.name == "stop") x.children { if (x.name == "color") gradient += SheetColor.read(x) } }
                }
            }
            return FillDef(pattern, fg, bg, gradient)
        }

        /**
         * The colour a fill shows, or null for none. Patterns are shown as the blend of their two
         * colours they look like from a little way off; a gradient as the colour halfway along it.
         */
        private fun fillColor(fill: FillDef, palette: SheetPalette): Int? {
            if (fill.gradient.isNotEmpty()) {
                val first = palette.argb(fill.gradient.first()) ?: Color.WHITE
                val last = palette.argb(fill.gradient.last()) ?: Color.WHITE
                return blend(first, last, 0.5f)
            }
            val share = when (fill.pattern) {
                null, "none" -> return null
                "solid" -> 1f
                "gray0625" -> 0.0625f
                "gray125" -> 0.125f
                "lightGray" -> 0.25f
                "mediumGray" -> 0.5f
                "darkGray" -> 0.75f
                else -> if (fill.pattern.startsWith("light")) 0.25f else 0.5f
            }
            // A pattern's automatic foreground is black, its automatic background white.
            val fg = palette.argb(fill.fg) ?: Color.BLACK
            if (share == 1f) return fg
            return blend(palette.argb(fill.bg) ?: Color.WHITE, fg, share)
        }

        private fun blend(from: Int, to: Int, share: Float): Int {
            fun channel(shift: Int) = (((from shr shift) and 0xFF) * (1 - share) + ((to shr shift) and 0xFF) * share).toInt().coerceIn(0, 255)
            return Color.rgb(channel(16), channel(8), channel(0))
        }

        private fun readBorder(x: OfficeXml): BorderDef {
            val sides = HashMap<String, Pair<String, SheetColor?>>()
            val up = x.flag("diagonalUp", missing = false)
            val down = x.flag("diagonalDown", missing = false)
            x.children {
                val side = when (x.name) {
                    "left", "start" -> "left"
                    "right", "end" -> "right"
                    "top", "bottom", "diagonal" -> x.name
                    else -> return@children
                }
                val style = x.attr("style")?.takeIf { it != "none" } ?: return@children
                var color: SheetColor? = null
                x.children { if (x.name == "color") color = SheetColor.read(x) }
                sides[side] = style to color
            }
            return BorderDef(sides, up, down)
        }

        private fun resolveBorder(border: BorderDef, palette: SheetPalette): CellBorders? {
            fun line(side: String) = border.sides[side]?.let { (style, color) -> CellLine.of(style, palette.argb(color) ?: Color.BLACK) }
            val diagonal = line("diagonal")
            val result = CellBorders(
                line("left"), line("top"), line("right"), line("bottom"),
                diagonal.takeIf { border.up }, diagonal.takeIf { border.down },
            )
            return result.takeIf { listOf(it.left, it.top, it.right, it.bottom, it.diagonalUp, it.diagonalDown).any { line -> line != null } }
        }
    }
}

/** A cell's text, with the look of each run when it is rich text ([runs] null when it is all in the cell's font). */
internal class SheetText(val text: String, val runs: List<TextRun>?)

/** A run of rich text: characters [start] until [end], in [font] over the cell's own. */
internal class TextRun(val start: Int, val end: Int, val font: FontProps)

/**
 * A workbook's shared strings (sharedStrings.xml), which text cells refer to by number. Kept as one
 * long string and where each starts, so that a workbook with a great many of them takes little
 * more memory than their characters. Rich text keeps its runs; phonetic guides are left out.
 */
internal class SharedStrings private constructor(
    private val chars: StringBuilder,
    private val starts: IntArray,
    val size: Int,
    private val runs: Map<Int, List<TextRun>>,
) {
    /** String [index], or null when there's no such string. */
    operator fun get(index: Int): SheetText? {
        if (index !in 0 until size) return null
        return SheetText(chars.substring(starts[index], starts[index + 1]), runs[index])
    }

    companion object {
        val NONE = SharedStrings(StringBuilder(), IntArray(1), 0, emptyMap())

        /**
         * Reads sharedStrings.xml at [path]. Throws [OfficeTooLarge] once their characters pass
         * [MAX_CHARS], rather than running out of memory.
         */
        fun read(pkg: OfficePackage, path: String?, check: () -> Unit): SharedStrings {
            val x = path?.let { pkg.xml(it, check) } ?: return NONE
            val chars = StringBuilder()
            var starts = IntArray(1024)
            var count = 0
            val runs = HashMap<Int, List<TextRun>>()
            x.use {
                x.children {
                    if (x.name != "si") return@children
                    if (count + 1 >= starts.size) starts = starts.copyOf(starts.size * 2)
                    starts[count] = chars.length
                    val item = readItem(x)
                    chars.append(item.text)
                    if (chars.length > MAX_CHARS) throw OfficeTooLarge()
                    item.runs?.let { runs[count] = it }
                    count++
                }
            }
            starts[count] = chars.length
            return SharedStrings(chars, starts, count, runs)
        }

        /** Reads a string item (si, or an inline string's is), the reader being at it. */
        fun readItem(x: OfficeXml): SheetText {
            val text = StringBuilder()
            val runs = ArrayList<TextRun>()
            x.children {
                when (x.name) {
                    "t" -> text.append(SheetStrings.unescape(x.text()))
                    "r" -> {
                        var font: FontProps? = null
                        val start = text.length
                        x.children {
                            when (x.name) {
                                "rPr" -> font = FontProps.read(x)
                                "t" -> text.append(SheetStrings.unescape(x.text()))
                            }
                        }
                        val runFont = font
                        if (runFont != null && text.length > start) runs += TextRun(start, text.length, runFont)
                    }
                }
            }
            return SheetText(text.toString(), runs.ifEmpty { null })
        }

        /** About 48 MB of text: more than any workbook a phone can lay out holds. */
        private const val MAX_CHARS = 24_000_000
    }
}
