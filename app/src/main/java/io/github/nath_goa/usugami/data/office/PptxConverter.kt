package io.github.nath_goa.usugami.data.office

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.withSave
import io.github.nath_goa.usugami.core.GroupFrame
import io.github.nath_goa.usugami.core.ListNumbering
import io.github.nath_goa.usugami.core.OfficeColors
import io.github.nath_goa.usugami.core.OfficeRect
import io.github.nath_goa.usugami.core.OfficeUnits
import io.github.nath_goa.usugami.core.SlideTableStyles
import io.github.nath_goa.usugami.core.SymbolFonts
import io.github.nath_goa.usugami.core.TableColumns
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * PowerPoint presentations (.pptx) to PDF: one page per slide, in the presentation's order, the
 * size of its slides. Hidden slides are left out.
 *
 * What converts: slide backgrounds (colours, gradients, pictures) and the shapes and pictures of
 * the slide master and layout behind each slide; shapes with their position, size, rotation,
 * fill and outline (rectangles, rounded rectangles, ovals, triangles, arrows and other common
 * shapes, lines and connectors with their arrowheads, and freeform shapes); pictures, cropped as on
 * the slide; groups, flipped or not; text boxes and placeholders, which take their position,
 * outline and text style from the slide layout and master (titles, bulleted and numbered body text
 * at every level), with alignment, spacing, bold, italic, underline, size and colour, right to left
 * too; text shrunk to fit its box when PowerPoint would shrink it; slide numbers; links; tables,
 * styled as in PowerPoint, with merged cells; SmartArt, from the drawing PowerPoint saves with it;
 * ink, equations and embedded objects, from the pictures PowerPoint saves of them.
 *
 * What is simplified: fonts become the phone's, so text can take a little more or less room;
 * shadows, glow, reflections, 3-D and other effects are left out, as are animations, videos (their
 * poster picture shows), speaker notes and comments. Vertical text whose characters stand upright
 * (Chinese, Japanese and Korean, or stacked letters) runs across its box, wrapped to its width.
 * Charts, and objects saved without a picture, become light grey boxes with their title.
 */
internal class PptxConverter(
    private val pkg: OfficePackage,
    private val pdf: PagedPdf,
    private val images: OfficeImages,
    private val check: () -> Unit,
    private val progress: (slide: Int, total: Int) -> Unit,
) {
    private var slideWidth = 720f
    private var slideHeight = 540f
    /** The presentation's default text style, read with each theme and colour map it's drawn with. */
    private val defaultTexts = HashMap<Pair<OfficeTheme, Map<String, String>>, Map<Int, LevelLook>>()
    /** Masters, layouts and slides read, by part and the colour map they were read with. */
    private val templates = HashMap<Pair<String, Map<String, String>?>, Template>()
    private val colorMapOverrides = HashMap<String, Map<String, String>?>()

    /** The presentation's table styles, read with each theme and colour map they're drawn with. */
    private val tableStyles = HashMap<Pair<OfficeTheme, Map<String, String>>, Map<String, TableStyle>>()

    /** Converts every slide that isn't hidden; returns the number of pages. Throws [OfficeNothingToConvert] when none is left. */
    fun convert(): Int {
        val main = pkg.mainPart() ?: "ppt/presentation.xml"
        val ids = ArrayList<String>()
        var firstNumber = 1
        pkg.xml(main, check)?.use { x ->
            x.intAttr("firstSlideNum")?.let { firstNumber = it }
            x.children {
                when (x.name) {
                    "sldSz" -> {
                        x.longAttr("cx")?.takeIf { it > 0 }?.let { slideWidth = OfficeUnits.emu(it).coerceIn(72f, 14_400f) }
                        x.longAttr("cy")?.takeIf { it > 0 }?.let { slideHeight = OfficeUnits.emu(it).coerceIn(72f, 14_400f) }
                    }
                    "sldIdLst" -> x.children { if (x.name == "sldId") x.relId()?.let { ids += it } }
                }
            }
        }
        // A slide's number is its place among all the slides, hidden ones too, as PowerPoint numbers them.
        val slides = ids.mapIndexedNotNull { i, id -> pkg.target(main, id)?.let { firstNumber + i to it } }.filter { isShown(it.second) }
        if (slides.isEmpty()) throw OfficeNothingToConvert()
        slides.forEachIndexed { i, (number, slide) ->
            check()
            progress(i + 1, slides.size)
            drawSlide(slide, number)
        }
        return pdf.pageCount
    }

    /** The presentation's default text style, its colours (tx1...) those of [template]'s theme and colour map. */
    private fun defaultText(template: Template): Map<Int, LevelLook> = defaultTexts.getOrPut(template.theme to template.colorMap) {
        val main = pkg.mainPart() ?: "ppt/presentation.xml"
        var levels: Map<Int, LevelLook> = emptyMap()
        pkg.xml(main, check)?.use { x -> x.children { if (x.name == "defaultTextStyle") levels = readLevels(x, Ctx(main, template.theme, template.colorMap)) } }
        levels
    }

    private fun isShown(slide: String): Boolean = pkg.xml(slide, check)?.use { it.flag("show") } ?: false

    // ---- What slides are made of ----

    /** Colours by scheme name, for the master a part belongs to. */
    private inner class Ctx(val part: String, val theme: OfficeTheme = OfficeTheme.DEFAULT, val colorMap: Map<String, String> = emptyMap()) {

        fun scheme(name: String): Int? {
            val mapped = colorMap[name] ?: when (name) {
                "bg1" -> "lt1"
                "tx1" -> "dk1"
                "bg2" -> "lt2"
                "tx2" -> "dk2"
                else -> name
            }
            return theme.colors[mapped]
        }

        fun color(x: OfficeXml) = readColor(x, ::scheme)
        fun fill(x: OfficeXml) = readFill(x, pkg, part, ::scheme)
        fun outline(x: OfficeXml) = readOutline(x, ::scheme)
    }

    private class Ph(val type: String, val idx: Int?)
    private class Xfrm(val rect: OfficeRect, val rotation: Float, val flipH: Boolean, val flipV: Boolean)

    private enum class Kind { SHAPE, PICTURE, GROUP, TABLE, GRAPHIC }

    private class Shape(
        val kind: Kind,
        val name: String = "",
        val caption: String? = null,
        val hidden: Boolean = false,
        val ph: Ph? = null,
        val xfrm: Xfrm? = null,
        val geometry: String? = null,
        val custom: List<CustomPath>? = null,
        val adjust: Map<String, Long> = emptyMap(),
        val fill: Fill? = null,
        /** a:grpFill: filled as the group it is in is (a group: as the group it is in, in turn). */
        val groupFill: Boolean = false,
        val line: Outline? = null,
        val styleFill: Int? = null,
        val styleLine: Int? = null,
        val styleFont: Int? = null,
        val body: TextBody? = null,
        val textRect: OfficeRect? = null,
        val children: List<Shape> = emptyList(),
        val childOffset: Pair<Float, Float> = 0f to 0f,
        val childExtent: Pair<Float, Float> = 0f to 0f,
        val table: SlideTable? = null,
        /** A chart: drawn as a grey box with its title. Other graphic frames are objects this converter doesn't know. */
        val chart: Boolean = false,
    )

    private class CustomPath(val w: Float, val h: Float, val commands: List<FloatArray>, val kinds: List<String>, val filled: Boolean, val stroked: Boolean)

    private class Template(
        val shapes: List<Shape>,
        val background: Fill?,
        val showMasterShapes: Boolean,
        val theme: OfficeTheme,
        val colorMap: Map<String, String>,
        val textStyles: Map<String, Map<Int, LevelLook>>,
    )

    // ---- Text ----

    private data class RunLook(
        val size: Float? = null,
        val bold: Boolean? = null,
        val italic: Boolean? = null,
        val underline: Boolean? = null,
        val strike: Boolean? = null,
        val color: Int? = null,
        val font: String? = null,
        val baseline: Int? = null,
        val caps: Boolean? = null,
        val highlight: Int? = null,
        val spacing: Float? = null,
        /** a:sym: the symbol font (Wingdings...) of the run's private-use characters, from Insert > Symbol. */
        val symbol: String? = null,
    ) {
        fun over(base: RunLook?): RunLook = if (base == null) this else RunLook(
            size ?: base.size, bold ?: base.bold, italic ?: base.italic, underline ?: base.underline, strike ?: base.strike,
            color ?: base.color, font ?: base.font, baseline ?: base.baseline, caps ?: base.caps, highlight ?: base.highlight,
            spacing ?: base.spacing, symbol ?: base.symbol,
        )
    }

    private sealed class Bullet {
        data object None : Bullet()
        data class Char(val text: String) : Bullet()
        data class Number(val scheme: String, val start: Int) : Bullet()
    }

    /** spcPts (points) or spcPct (a share of the font size). */
    private class Gap(val points: Float?, val share: Float?) {
        fun points(fontSize: Float) = points ?: ((share ?: 0f) * fontSize * 1.2f)
    }

    private data class LevelLook(
        val marL: Float? = null,
        val indent: Float? = null,
        val align: Align? = null,
        val line: LineSpacing? = null,
        val before: Gap? = null,
        val after: Gap? = null,
        val bullet: Bullet? = null,
        val bulletColor: Int? = null,
        val bulletSize: Float? = null,
        val bulletFont: String? = null,
        val run: RunLook? = null,
        /** A right-to-left paragraph (Arabic, Hebrew): its indents and bullet are on the right. */
        val rtl: Boolean? = null,
    ) {
        fun over(base: LevelLook?): LevelLook = if (base == null) this else LevelLook(
            marL ?: base.marL, indent ?: base.indent, align ?: base.align, line ?: base.line, before ?: base.before,
            after ?: base.after, bullet ?: base.bullet, bulletColor ?: base.bulletColor, bulletSize ?: base.bulletSize,
            bulletFont ?: base.bulletFont, run?.over(base.run) ?: base.run, rtl ?: base.rtl,
        )
    }

    private sealed class Piece {
        abstract val look: RunLook?
        class Text(val text: String, override val look: RunLook?) : Piece()
        class Break(override val look: RunLook?) : Piece()
        class SlideNumber(val cached: String, override val look: RunLook?) : Piece()
    }

    /** A paragraph; [math] when it has an equation (a14:m), which this converter can't draw. */
    private class TextPara(val level: Int, val look: LevelLook?, val pieces: List<Piece>, val end: RunLook?, val math: Boolean = false)

    private data class BodyLook(
        val insets: List<Float?> = listOf(null, null, null, null),
        val anchor: Int? = null,
        val wrap: Boolean? = null,
        /** null: not set; 0: no autofit; 1: shrink text on overflow; 2: resize shape. */
        val autofit: Int? = null,
        val fontScale: Float? = null,
        val lineReduction: Float? = null,
        /**
         * Which way the text runs: 0 across, 1 down (turned a quarter clockwise), -1 up, [EAST_ASIAN]
         * down with Chinese, Japanese and Korean characters upright, [STACKED] down with every letter upright.
         */
        val vertical: Int? = null,
        /** spcFirstLastPara: the first paragraph's space before and the last one's space after count. */
        val firstLastSpacing: Boolean? = null,
    ) {
        fun over(base: BodyLook?): BodyLook = if (base == null) this else BodyLook(
            List(4) { insets[it] ?: base.insets[it] }, anchor ?: base.anchor, wrap ?: base.wrap, autofit ?: base.autofit,
            fontScale ?: base.fontScale, lineReduction ?: base.lineReduction, vertical ?: base.vertical,
            firstLastSpacing ?: base.firstLastSpacing,
        )
    }

    private class TextBody(val look: BodyLook, val levels: Map<Int, LevelLook>, val paragraphs: List<TextPara>) {
        val hasText get() = paragraphs.any { p -> p.pieces.any { it !is Piece.Text || it.text.isNotBlank() } }
    }

    // ---- Tables ----

    /** A cell; [hMerge] cells are covered by the one before, [vMerge] cells by the one above, which spans [rowSpan] rows. */
    private class SlideCell(
        val body: TextBody?, val span: Int, val rowSpan: Int, val hMerge: Boolean, val vMerge: Boolean,
        val fill: Fill?, val borders: Array<Outline?>, val margins: List<Float?>, val anchor: Int?,
    )
    /** A table; a right-to-left one ([rtl]) has its first column on the right. */
    private class SlideTable(val grid: List<Float>, val rows: List<Pair<Float, List<SlideCell>>>, val styleId: String?, val flags: Set<String>, val rtl: Boolean)

    /** A part of a table style (wholeTbl, firstRow, band1H...): its fill, borders and text. */
    private class TableStylePart(val fill: Fill?, val borders: Map<String, Outline>, val bold: Boolean?, val color: Int?)
    private class TableStyle(val parts: Map<String, TableStylePart>)

    // ---- Reading ----

    /**
     * The master, layout or slide in part [path] ([master] null for a master). Its colours are read
     * with [colorMap] when given (a slide's or layout's override of its master's), else with its
     * master's colour map.
     */
    private fun template(path: String, master: Template?, colorMap: Map<String, String>? = null): Template = templates.getOrPut(path to colorMap) {
        val theme = if (master == null) OfficeTheme.read(pkg, pkg.targetOfType(path, "/theme"), check) else master.theme
        // A master's colour map comes after its shapes in the file, but their colours depend on it.
        val colorMap = colorMap ?: master?.colorMap ?: readColorMap(path).orEmpty()
        val ctx = Ctx(path, theme, colorMap)
        var shapes: List<Shape> = emptyList()
        var background: Fill? = null
        var showMaster = true
        val styles = HashMap<String, Map<Int, LevelLook>>()
        pkg.xml(path, check)?.use { x ->
            showMaster = x.flag("showMasterSp")
            x.children {
                when (x.name) {
                    "cSld" -> x.children {
                        when (x.name) {
                            "bg" -> background = readBackground(x, ctx)
                            "spTree" -> shapes = readShapes(x, ctx)
                        }
                    }
                    "txStyles" -> x.children {
                        val kind = when (x.name) { "titleStyle" -> "title"; "bodyStyle" -> "body"; else -> "other" }
                        styles[kind] = readLevels(x, ctx)
                    }
                }
            }
        }
        Template(shapes, background, showMaster, theme, colorMap, styles.ifEmpty { master?.textStyles.orEmpty() })
    }

    /** A master's colour map (p:clrMap), or a layout's or slide's override of its master's (p:clrMapOvr); null when there's none. */
    private fun readColorMap(path: String): Map<String, String>? {
        val keys = listOf("bg1", "tx1", "bg2", "tx2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")
        var map: Map<String, String>? = null
        pkg.xml(path, check)?.use { x ->
            fun read() = keys.mapNotNull { key -> x.attr(key)?.let { key to it } }.toMap()
            x.children {
                when (x.name) {
                    "clrMap" -> map = read()
                    "clrMapOvr" -> x.children { if (x.name == "overrideClrMapping") map = read() }
                }
            }
        }
        return map
    }

    /** A layout's override of its master's colour map, read once. */
    private fun layoutColorMap(path: String): Map<String, String>? =
        if (path in colorMapOverrides) colorMapOverrides[path] else readColorMap(path).also { colorMapOverrides[path] = it }

    private fun readBackground(x: OfficeXml, ctx: Ctx): Fill? {
        var fill: Fill? = null
        x.children {
            when (x.name) {
                "bgPr" -> x.children { ctx.fill(x)?.let { if (fill == null) fill = it } }
                "bgRef" -> x.children { ctx.color(x)?.let { fill = Fill.Solid(it) } }
            }
        }
        return fill
    }

    private fun readShapes(x: OfficeXml, ctx: Ctx): List<Shape> {
        val shapes = ArrayList<Shape>()
        x.children {
            if (x.name == "AlternateContent") shapes += readAlternate(x, ctx)
            else readShape(x, ctx)?.let { shapes += it }
        }
        return shapes
    }

    /**
     * mc:AlternateContent: the shapes of its first mc:Choice, or those of its mc:Fallback when the
     * choice has something that can't be drawn here (ink, equations, 3-D models, Slide Zooms...),
     * which the fallback has as a picture.
     */
    private fun readAlternate(x: OfficeXml, ctx: Ctx): List<Shape> {
        var choice: List<Shape>? = null
        var fallback: List<Shape> = emptyList()
        x.children {
            when (x.name) {
                "Choice" -> if (choice == null) choice = readShapes(x, ctx)
                "Fallback" -> fallback = readShapes(x, ctx)
            }
        }
        val chosen = choice.orEmpty()
        return if (fallback.isNotEmpty() && (chosen.isEmpty() || !chosen.all(::drawable))) fallback else chosen
    }

    /** Whether [shape] can be drawn as it shows in PowerPoint (a chart as its grey box). */
    private fun drawable(shape: Shape): Boolean = when (shape.kind) {
        Kind.GRAPHIC -> shape.chart
        Kind.GROUP -> shape.children.all(::drawable)
        else -> shape.body?.paragraphs?.none { it.math } ?: true
    }

    private fun readShape(x: OfficeXml, ctx: Ctx): Shape? = when (x.name) {
        "sp", "cxnSp", "pic" -> readSp(x, ctx)
        "grpSp" -> readGroup(x, ctx)
        "graphicFrame" -> readFrame(x, ctx)
        else -> null
    }

    private fun readSp(x: OfficeXml, ctx: Ctx): Shape {
        val isPicture = x.name == "pic"
        var name = ""
        var caption: String? = null
        var hidden = false
        var ph: Ph? = null
        var xfrm: Xfrm? = null
        var geometry: String? = null
        var custom: List<CustomPath>? = null
        val adjust = HashMap<String, Long>()
        var fill: Fill? = null
        var groupFill = false
        var line: Outline? = null
        var styleFill: Int? = null
        var styleLine: Int? = null
        var styleFont: Int? = null
        var body: TextBody? = null
        var textRect: OfficeRect? = null
        var picture: Fill? = null
        x.children {
            when (x.name) {
                "nvSpPr", "nvCxnSpPr", "nvPicPr" -> x.children {
                    when (x.name) {
                        "cNvPr" -> {
                            name = x.attr("name").orEmpty()
                            caption = x.attr("descr")?.takeIf { it.isNotBlank() } ?: x.attr("title")?.takeIf { it.isNotBlank() }
                            hidden = x.flag("hidden", missing = false)
                        }
                        "nvPr" -> x.children { if (x.name == "ph") ph = Ph(x.attr("type") ?: "obj", x.intAttr("idx")) }
                    }
                }
                "spPr" -> x.children {
                    when (x.name) {
                        "xfrm" -> xfrm = readXfrm(x).first
                        "prstGeom" -> {
                            geometry = x.attr("prst")
                            x.children { if (x.name == "avLst") x.children { readGuide(x)?.let { adjust[it.first] = it.second } } }
                        }
                        "custGeom" -> custom = readCustom(x)
                        "ln" -> line = ctx.outline(x)
                        "grpFill" -> groupFill = true
                        else -> ctx.fill(x)?.let { fill = it }
                    }
                }
                "style" -> x.children {
                    val ref = x.name
                    val idx = x.intAttr("idx") ?: 0
                    x.children {
                        val color = ctx.color(x)
                        when (ref) {
                            "fillRef" -> if (idx > 0) styleFill = color
                            "lnRef" -> if (idx > 0) styleLine = color
                            "fontRef" -> styleFont = color
                        }
                    }
                }
                "txBody" -> body = readBody(x, ctx)
                "txXfrm" -> textRect = readXfrm(x).first?.rect
                "blipFill" -> picture = ctx.fill(x)
            }
        }
        return Shape(
            kind = if (isPicture) Kind.PICTURE else Kind.SHAPE,
            name = name, caption = caption, hidden = hidden, ph = ph, xfrm = xfrm,
            geometry = geometry, custom = custom, adjust = adjust,
            fill = if (isPicture) picture else fill,
            groupFill = groupFill && !isPicture,
            line = line, styleFill = styleFill, styleLine = styleLine, styleFont = styleFont,
            body = body, textRect = textRect,
        )
    }

    /** a:xfrm (or p:xfrm): the rectangle in points, and for groups the children's offset and extent. */
    private fun readXfrm(x: OfficeXml): Pair<Xfrm?, Pair<Pair<Float, Float>, Pair<Float, Float>>?> {
        val rotation = (x.intAttr("rot") ?: 0) / 60_000f
        val flipH = x.flag("flipH", missing = false)
        val flipV = x.flag("flipV", missing = false)
        var off = 0f to 0f
        var ext = 0f to 0f
        var chOff = 0f to 0f
        var chExt: Pair<Float, Float>? = null
        fun pair(a: String, b: String) = OfficeUnits.emu(x.longAttr(a) ?: 0L) to OfficeUnits.emu(x.longAttr(b) ?: 0L)
        x.children {
            when (x.name) {
                "off" -> off = pair("x", "y")
                "ext" -> ext = pair("cx", "cy")
                "chOff" -> chOff = pair("x", "y")
                "chExt" -> chExt = pair("cx", "cy")
            }
        }
        val xfrm = Xfrm(OfficeRect(off.first, off.second, ext.first, ext.second), rotation, flipH, flipV)
        return xfrm to chExt?.let { chOff to it }
    }

    private fun readGuide(x: OfficeXml): Pair<String, Long>? {
        if (x.name != "gd") return null
        val formula = x.attr("fmla") ?: return null
        val value = formula.removePrefix("val ").trim().toLongOrNull() ?: return null
        return (x.attr("name") ?: return null) to value
    }

    /** a:custGeom's paths; none when they can't be drawn here. */
    private fun readCustom(x: OfficeXml): List<CustomPath> {
        val paths = ArrayList<CustomPath>()
        var usable = true
        x.children {
            if (x.name == "pathLst") x.children {
                if (x.name != "path") return@children
                val w = (x.longAttr("w") ?: 0L).toFloat()
                val h = (x.longAttr("h") ?: 0L).toFloat()
                val filled = x.attr("fill") != "none"
                val stroked = x.flag("stroke")
                val commands = ArrayList<FloatArray>()
                val kinds = ArrayList<String>()
                x.children {
                    val kind = x.name
                    val values = ArrayList<Float>()
                    if (kind == "arcTo") {
                        listOf("wR", "hR", "stAng", "swAng").forEach { key ->
                            val v = x.attr(key)?.toFloatOrNull()
                            if (v == null) usable = false else values += v
                        }
                    }
                    x.children {
                        if (x.name == "pt") {
                            val px = x.attr("x")?.toFloatOrNull()
                            val py = x.attr("y")?.toFloatOrNull()
                            if (px == null || py == null) usable = false else { values += px; values += py }
                        }
                    }
                    kinds += kind
                    commands += values.toFloatArray()
                }
                paths += CustomPath(w, h, commands, kinds, filled, stroked)
            }
        }
        // Points given by formulas need PowerPoint's shape-guide engine: such shapes are left out
        // (their text still shows).
        return if (usable) paths else emptyList()
    }

    private fun readGroup(x: OfficeXml, ctx: Ctx): Shape {
        var name = ""
        var hidden = false
        var xfrm: Xfrm? = null
        var child: Pair<Pair<Float, Float>, Pair<Float, Float>>? = null
        var fill: Fill? = null
        var groupFill = false
        val children = ArrayList<Shape>()
        x.children {
            when (x.name) {
                "nvGrpSpPr" -> x.children {
                    if (x.name == "cNvPr") {
                        name = x.attr("name").orEmpty()
                        hidden = x.flag("hidden", missing = false)
                    }
                }
                "grpSpPr" -> x.children {
                    when (x.name) {
                        "xfrm" -> readXfrm(x).let {
                            xfrm = it.first
                            child = it.second
                        }
                        // The fill of shapes in the group that take the group's (a:grpFill).
                        "grpFill" -> groupFill = true
                        else -> ctx.fill(x)?.let { fill = it }
                    }
                }
                "AlternateContent" -> children += readAlternate(x, ctx)
                else -> readShape(x, ctx)?.let { children += it }
            }
        }
        val rect = xfrm?.rect
        return Shape(
            Kind.GROUP, name = name, hidden = hidden, xfrm = xfrm, children = children, fill = fill, groupFill = groupFill,
            childOffset = child?.first ?: (rect?.let { it.x to it.y } ?: (0f to 0f)),
            childExtent = child?.second ?: (rect?.let { it.width to it.height } ?: (0f to 0f)),
        )
    }

    private fun readFrame(x: OfficeXml, ctx: Ctx): Shape {
        var name = ""
        var caption: String? = null
        var hidden = false
        var ph: Ph? = null
        var xfrm: Xfrm? = null
        var table: SlideTable? = null
        var drawing: List<Shape>? = null
        var chart = false
        var preview: Fill? = null
        x.children {
            when (x.name) {
                "nvGraphicFramePr" -> x.children {
                    when (x.name) {
                        "cNvPr" -> {
                            name = x.attr("name").orEmpty()
                            caption = x.attr("descr")?.takeIf { it.isNotBlank() } ?: x.attr("title")?.takeIf { it.isNotBlank() }
                            hidden = x.flag("hidden", missing = false)
                        }
                        "nvPr" -> x.children { if (x.name == "ph") ph = Ph(x.attr("type") ?: "obj", x.intAttr("idx")) }
                    }
                }
                "xfrm" -> xfrm = readXfrm(x).first
                "graphic" -> x.children {
                    if (x.name == "graphicData") {
                        val uri = x.attr("uri").orEmpty()
                        x.children {
                            when {
                                x.name == "tbl" -> table = readTable(x, ctx)
                                x.name == "chart" -> {
                                    chart = true
                                    caption = caption ?: orNull { chartTitle(pkg.target(ctx.part, x.relId())) }
                                }
                                x.name == "relIds" && "diagram" in uri -> drawing = orNull { smartArt(ctx, x.relId("dm")) }
                                // An embedded object (an Excel sheet, an old equation...): the picture of it saved with it.
                                x.name == "oleObj" -> preview = preview ?: olePreview(x, ctx)
                                x.name == "AlternateContent" -> x.children {
                                    x.children { if (x.name == "oleObj") preview = preview ?: olePreview(x, ctx) }
                                }
                            }
                        }
                    }
                }
            }
        }
        val rect = xfrm?.rect
        if (drawing != null && rect != null) {
            return Shape(Kind.GROUP, name = name, hidden = hidden, xfrm = xfrm, children = drawing!!, childOffset = 0f to 0f, childExtent = rect.width to rect.height)
        }
        if (preview != null) return Shape(Kind.PICTURE, name = name, caption = caption, hidden = hidden, ph = ph, xfrm = xfrm, fill = preview)
        return Shape(if (table != null) Kind.TABLE else Kind.GRAPHIC, name = name, caption = caption, hidden = hidden, ph = ph, xfrm = xfrm, table = table, chart = chart)
    }

    /** The picture p:oleObj keeps of an embedded object, or null. */
    private fun olePreview(x: OfficeXml, ctx: Ctx): Fill? {
        var preview: Fill? = null
        x.children { if (x.name == "pic") readSp(x, ctx).fill?.let { if (it is Fill.Image) preview = it } }
        return preview
    }

    /** The title of the chart in part [path], to show in its grey box. */
    private fun chartTitle(path: String?): String? {
        val x = path?.let { pkg.xml(it, check) } ?: return null
        val title = StringBuilder()
        x.use {
            fun texts() { x.children { if (x.name == "t") title.append(x.text()) else texts() } }
            fun find() { x.children { if (x.name == "title") texts() else if (title.isEmpty() && x.name in setOf("chart", "plotArea")) find() } }
            find()
        }
        return title.toString().trim().takeIf { it.isNotEmpty() }
    }

    /** SmartArt, from the drawing PowerPoint saves alongside it; null when there's none. */
    private fun smartArt(ctx: Ctx, dataId: String?): List<Shape>? {
        val data = pkg.target(ctx.part, dataId) ?: return null
        var drawingId: String? = null
        pkg.xml(data, check)?.use { x ->
            fun find() { x.children { if (x.name == "dataModelExt") drawingId = x.attr("relId") else if (drawingId == null) find() } }
            find()
        }
        val drawing = pkg.target(ctx.part, drawingId) ?: return null
        var shapes: List<Shape>? = null
        pkg.xml(drawing, check)?.use { x ->
            x.children { if (x.name == "spTree") shapes = readShapes(x, Ctx(drawing, ctx.theme, ctx.colorMap)) }
        }
        return shapes
    }

    private fun readTable(x: OfficeXml, ctx: Ctx): SlideTable {
        val grid = ArrayList<Float>()
        val rows = ArrayList<Pair<Float, List<SlideCell>>>()
        var styleId: String? = null
        val flags = HashSet<String>()
        var rtl = false
        x.children {
            when (x.name) {
                "tblPr" -> {
                    rtl = x.flag("rtl", missing = false)
                    for (flag in listOf("firstRow", "lastRow", "firstCol", "lastCol", "bandRow", "bandCol")) if (x.flag(flag, missing = false)) flags += flag
                    x.children { if (x.name == "tableStyleId") styleId = x.text().trim() }
                }
                "tblGrid" -> x.children { if (x.name == "gridCol" && grid.size < MAX_COLUMNS) grid += OfficeUnits.emu(x.longAttr("w") ?: 0L) }
                "tr" -> {
                    val height = OfficeUnits.emu(x.longAttr("h") ?: 0L)
                    val cells = ArrayList<SlideCell>()
                    x.children {
                        if (x.name != "tc") return@children
                        val span = (x.intAttr("gridSpan") ?: 1).coerceIn(1, MAX_COLUMNS)
                        val rowSpan = (x.intAttr("rowSpan") ?: 1).coerceIn(1, MAX_ROWS)
                        val hMerge = x.flag("hMerge", missing = false)
                        val vMerge = x.flag("vMerge", missing = false)
                        var body: TextBody? = null
                        var fill: Fill? = null
                        val borders = arrayOfNulls<Outline>(4)
                        val margins = arrayOfNulls<Float>(4)
                        var anchor: Int? = null
                        x.children {
                            when (x.name) {
                                "txBody" -> body = readBody(x, ctx)
                                "tcPr" -> {
                                    listOf("marL", "marT", "marR", "marB").forEachIndexed { i, key -> x.longAttr(key)?.let { margins[i] = OfficeUnits.emu(it) } }
                                    anchor = anchorOf(x.attr("anchor"))
                                    x.children {
                                        when (x.name) {
                                            "lnL" -> borders[0] = ctx.outline(x)
                                            "lnT" -> borders[1] = ctx.outline(x)
                                            "lnR" -> borders[2] = ctx.outline(x)
                                            "lnB" -> borders[3] = ctx.outline(x)
                                            else -> ctx.fill(x)?.let { fill = it }
                                        }
                                    }
                                }
                            }
                        }
                        cells += SlideCell(body, span, rowSpan, hMerge, vMerge, fill, borders, margins.toList(), anchor)
                    }
                    rows += height to cells
                }
            }
        }
        return SlideTable(grid, rows, styleId, flags, rtl)
    }

    private fun anchorOf(value: String?) = when (value) { "ctr" -> 1; "b" -> 2; "t" -> 0; else -> null }

    private fun readBody(x: OfficeXml, ctx: Ctx): TextBody {
        var look = BodyLook()
        var levels: Map<Int, LevelLook> = emptyMap()
        val paragraphs = ArrayList<TextPara>()
        x.children {
            when (x.name) {
                "bodyPr" -> look = readBodyLook(x)
                "lstStyle" -> levels = readLevels(x, ctx)
                "p" -> paragraphs += readParagraph(x, ctx)
            }
        }
        return TextBody(look, levels, paragraphs)
    }

    private fun readBodyLook(x: OfficeXml): BodyLook {
        // The attributes are read first: once the children (such as a:normAutofit) are, the reader has left them.
        val insets = listOf("lIns", "tIns", "rIns", "bIns").map { key -> x.longAttr(key)?.let { OfficeUnits.emu(it) } }
        val anchor = anchorOf(x.attr("anchor"))
        val wrap = x.attr("wrap")?.let { it != "none" }
        val firstLastSpacing = x.attr("spcFirstLastPara")?.let { x.flag("spcFirstLastPara") }
        val vertical = when (x.attr("vert")) {
            "vert" -> 1
            "eaVert" -> EAST_ASIAN
            "wordArtVert", "wordArtVertRtl" -> STACKED
            "vert270" -> -1
            "horz" -> 0
            else -> null
        }
        var autofit: Int? = null
        var fontScale: Float? = null
        var reduction: Float? = null
        x.children {
            when (x.name) {
                "noAutofit" -> autofit = 0
                "normAutofit" -> {
                    autofit = 1
                    fontScale = x.percentAttr("fontScale")?.let { it / 100_000f }
                    reduction = x.percentAttr("lnSpcReduction")?.let { it / 100_000f }
                }
                "spAutoFit" -> autofit = 2
            }
        }
        return BodyLook(insets, anchor, wrap, autofit, fontScale, reduction, vertical, firstLastSpacing)
    }

    private fun readLevels(x: OfficeXml, ctx: Ctx): Map<Int, LevelLook> {
        val levels = HashMap<Int, LevelLook>()
        x.children {
            val level = when {
                x.name == "defPPr" -> -1
                x.name.startsWith("lvl") && x.name.endsWith("pPr") -> x.name.removePrefix("lvl").removeSuffix("pPr").toIntOrNull()?.minus(1)
                else -> null
            } ?: return@children
            levels[level] = readLevelLook(x, ctx)
        }
        // defPPr is the default for every level.
        val default = levels.remove(-1) ?: return levels
        return (0..8).associateWith { (levels[it] ?: LevelLook()).over(default) }
    }

    private fun readLevelLook(x: OfficeXml, ctx: Ctx): LevelLook {
        var look = LevelLook(
            marL = x.longAttr("marL")?.let { OfficeUnits.emu(it) },
            indent = x.longAttr("indent")?.let { OfficeUnits.emu(it) },
            align = when (x.attr("algn")) {
                "ctr" -> Align.CENTER
                "r" -> Align.END
                "just", "dist", "justLow", "thaiDist" -> Align.JUSTIFY
                "l" -> Align.START
                else -> null
            },
            rtl = x.attr("rtl")?.let { x.flag("rtl") },
        )
        fun gap(): Gap? {
            var gap: Gap? = null
            x.children {
                when (x.name) {
                    "spcPts" -> gap = Gap((x.intAttr("val") ?: 0) / 100f, null)
                    "spcPct" -> gap = Gap(null, (x.percentAttr("val") ?: 0) / 100_000f)
                }
            }
            return gap
        }
        x.children {
            look = when (x.name) {
                "lnSpc" -> gap()?.let { g -> look.copy(line = g.points?.let { LineSpacing.Exact(it) } ?: LineSpacing.Multiple(g.share ?: 1f)) } ?: look
                "spcBef" -> look.copy(before = gap())
                "spcAft" -> look.copy(after = gap())
                "buNone" -> look.copy(bullet = Bullet.None)
                "buChar" -> look.copy(bullet = Bullet.Char(x.attr("char") ?: "\u2022"))
                "buAutoNum" -> look.copy(bullet = Bullet.Number(x.attr("type") ?: "arabicPeriod", x.intAttr("startAt") ?: 1))
                "buBlip" -> look.copy(bullet = Bullet.Char("\u2022"))
                "buFont" -> look.copy(bulletFont = x.attr("typeface"))
                "buSzPct" -> look.copy(bulletSize = (x.percentAttr("val") ?: 100_000) / 100_000f)
                "buClr" -> {
                    var color: Int? = null
                    x.children { color = ctx.color(x) ?: color }
                    look.copy(bulletColor = color)
                }
                "defRPr" -> look.copy(run = readRunLook(x, ctx))
                else -> look
            }
        }
        return look
    }

    private fun readRunLook(x: OfficeXml, ctx: Ctx): RunLook {
        var look = RunLook(
            size = x.intAttr("sz")?.let { it / 100f },
            bold = x.attr("b")?.let { x.flag("b") },
            italic = x.attr("i")?.let { x.flag("i") },
            underline = x.attr("u")?.let { it != "none" },
            strike = x.attr("strike")?.let { it != "noStrike" },
            baseline = x.percentAttr("baseline")?.let { if (it > 0) 1 else if (it < 0) -1 else 0 },
            caps = x.attr("cap")?.let { it == "all" || it == "small" },
            spacing = x.intAttr("spc")?.let { it / 100f },
        )
        x.children {
            when (x.name) {
                "solidFill" -> x.children { ctx.color(x)?.let { look = look.copy(color = it) } }
                "gradFill" -> ctx.fill(x)?.mainColor?.let { look = look.copy(color = it) }
                "highlight" -> x.children { ctx.color(x)?.let { look = look.copy(highlight = it) } }
                "latin" -> x.attr("typeface")?.takeIf { it.isNotEmpty() }?.let { look = look.copy(font = it) }
                "sym" -> x.attr("typeface")?.takeIf { it.isNotEmpty() }?.let { look = look.copy(symbol = it) }
                "hlinkClick" -> {
                    // A link shows in the theme's link colour, underlined, whatever the text's own
                    // colour, unless it's set to keep it (ahyp:hlinkClr val="tx", PowerPoint 2019).
                    var ownColor = false
                    fun find() { x.children { if (x.name == "hlinkClr") ownColor = x.attr("val") == "tx" else find() } }
                    find()
                    if (!ownColor) look = look.copy(color = ctx.scheme("hlink") ?: look.color, underline = look.underline ?: true)
                }
            }
        }
        return look
    }

    private fun readParagraph(x: OfficeXml, ctx: Ctx): TextPara {
        var level = 0
        var look: LevelLook? = null
        var end: RunLook? = null
        var math = false
        val pieces = ArrayList<Piece>()
        x.children {
            when (x.name) {
                "m" -> math = true
                "pPr" -> {
                    level = (x.intAttr("lvl") ?: 0).coerceIn(0, 8)
                    look = readLevelLook(x, ctx)
                }
                "r" -> {
                    var run: RunLook? = null
                    var text = ""
                    x.children {
                        when (x.name) {
                            "rPr" -> run = readRunLook(x, ctx)
                            "t" -> text += x.text()
                        }
                    }
                    pieces += Piece.Text(text, run)
                }
                "br" -> {
                    var run: RunLook? = null
                    x.children { if (x.name == "rPr") run = readRunLook(x, ctx) }
                    pieces += Piece.Break(run)
                }
                "fld" -> {
                    var run: RunLook? = null
                    var text = ""
                    val slideNumber = x.attr("type") == "slidenum"
                    x.children {
                        when (x.name) {
                            "rPr" -> run = readRunLook(x, ctx)
                            "t" -> text += x.text()
                        }
                    }
                    pieces += if (slideNumber) Piece.SlideNumber(text, run) else Piece.Text(text, run)
                }
                "endParaRPr" -> end = readRunLook(x, ctx)
            }
        }
        return TextPara(level, look, pieces, end, math)
    }

    private fun tableStyle(id: String?, ctx: Ctx): TableStyle? {
        id ?: return null
        // Their colours are the theme's (accent1...), so slides of another master get their own.
        val all = tableStyles.getOrPut(ctx.theme to ctx.colorMap) {
            val found = HashMap<String, TableStyle>()
            val main = pkg.mainPart() ?: "ppt/presentation.xml"
            // Damaged, the styles read before the damage are kept, and tables of other styles get PowerPoint's default look.
            orNull {
                pkg.targetOfType(main, "/tableStyles")?.let { path ->
                    pkg.xml(path, check)?.use { x ->
                        x.children {
                            if (x.name != "tblStyle") return@children
                            val styleId = x.attr("styleId") ?: return@children
                            val parts = HashMap<String, TableStylePart>()
                            x.children { parts[x.name] = readTableStylePart(x, Ctx(path, ctx.theme, ctx.colorMap)) }
                            found[styleId] = TableStyle(parts)
                        }
                    }
                }
            }
            found
        }
        return all[id]
    }

    private fun readTableStylePart(x: OfficeXml, ctx: Ctx): TableStylePart {
        var fill: Fill? = null
        val borders = HashMap<String, Outline>()
        var bold: Boolean? = null
        var color: Int? = null
        x.children {
            when (x.name) {
                "tcTxStyle" -> {
                    bold = x.attr("b")?.let { it == "on" }
                    x.children {
                        if (x.name == "fontRef") x.children { ctx.color(x)?.let { color = it } }
                        else ctx.color(x)?.let { color = it }
                    }
                }
                "tcStyle" -> x.children {
                    when (x.name) {
                        "tcBdr" -> x.children {
                            val side = x.name
                            x.children { if (x.name == "ln") borders[side] = ctx.outline(x) }
                        }
                        "fill" -> x.children { ctx.fill(x)?.let { fill = it } }
                        "fillRef" -> x.children { ctx.color(x)?.let { fill = Fill.Solid(it) } }
                    }
                }
            }
        }
        return TableStylePart(fill, borders, bold, color)
    }

    // ---- Drawing ----

    private fun drawSlide(path: String, number: Int) {
        val layoutPath = pkg.targetOfType(path, "/slideLayout")
        val masterPath = layoutPath?.let { pkg.targetOfType(it, "/slideMaster") }
        val plainMaster = masterPath?.let { template(it, null) }
        // A slide's or its layout's own colour map (a dark title layout's, say) is the one for all
        // of the slide, the master's shapes and text styles too.
        val colorMap = (readColorMap(path) ?: layoutPath?.let(::layoutColorMap))?.takeIf { it != plainMaster?.colorMap }
        val master = if (colorMap == null) plainMaster else masterPath?.let { template(it, null, colorMap) }
        val layout = layoutPath?.let { template(it, master ?: NO_MASTER, colorMap) }
        val slide = template(path, master ?: layout ?: NO_MASTER, colorMap)
        currentTheme = slide.theme
        // A slide's own template entry is read once and not needed again.
        templates.remove(path to colorMap)
        val canvas = pdf.startPage(slideWidth, slideHeight)
        val page = RectF(0f, 0f, slideWidth.units, slideHeight.units)
        val background = slide.background ?: layout?.background ?: master?.background ?: Fill.Solid(Color.WHITE)
        canvas.drawColor(Color.WHITE)
        canvas.fill(background, Path().apply { addRect(page, Path.Direction.CW) }, page, ::picture)
        val scene = Scene(slide, layout, master, number)
        if (slide.showMasterShapes && layout?.showMasterShapes != false && master != null) {
            for (shape in master.shapes) if (shape.ph == null) guarded(canvas) { drawShape(canvas, shape, scene, master, null) }
        }
        if (slide.showMasterShapes && layout != null) {
            for (shape in layout.shapes) if (shape.ph == null) guarded(canvas) { drawShape(canvas, shape, scene, layout, null) }
        }
        for (shape in slide.shapes) guarded(canvas) { drawShape(canvas, shape, scene, slide, null) }
        pdf.finishPage()
    }

    /**
     * Draws one shape with [draw]. A shape that can't be drawn (such as one with values no slide
     * could have, or a damaged part or picture of its own) is left out, rather than the whole
     * presentation failing.
     */
    private inline fun guarded(canvas: Canvas, draw: () -> Unit) {
        val saved = canvas.save()
        try {
            draw()
        } catch (e: Exception) {
            rethrowCancel(e)
            // Left out.
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    /** What [read] reads from a part of a shape's own (a chart's, SmartArt's...), or null when that part is damaged. */
    private inline fun <T> orNull(read: () -> T?): T? = try {
        read()
    } catch (e: Exception) {
        rethrowCancel(e)
        null
    }

    private class Scene(val slide: Template, val layout: Template?, val master: Template?, val number: Int)

    private fun picture(fill: Fill.Image): Picture = ImagePicture(images, fill.path, slideWidth, slideHeight, fill.crop)

    /** The layout's and master's placeholders that [ph] takes its place and look from. */
    private fun inherited(ph: Ph, scene: Scene, owner: Template): List<Shape> {
        val out = ArrayList<Shape>()
        fun match(shapes: List<Shape>, ph: Ph): Shape? =
            shapes.firstOrNull { it.ph != null && ph.idx != null && it.ph.idx == ph.idx && ph.type != "title" && ph.type != "ctrTitle" }
                ?: shapes.firstOrNull { it.ph?.type == ph.type }
                ?: shapes.firstOrNull { it.ph != null && kindOf(it.ph.type) == kindOf(ph.type) && kindOf(ph.type) != "body" }
        val fromLayout = if (owner === scene.slide) scene.layout?.let { match(it.shapes, ph) } else null
        fromLayout?.let { out += it }
        if (owner !== scene.master) {
            val type = fromLayout?.ph?.type ?: ph.type
            val masterType = when (kindOf(type)) { "title" -> "title"; "body" -> "body"; else -> type }
            scene.master?.shapes?.firstOrNull { it.ph != null && (it.ph.type == masterType || kindOf(it.ph.type) == masterType) }?.let { out += it }
        }
        return out
    }

    /** title, body, or the placeholder's own type for dates, footers and slide numbers. */
    private fun kindOf(type: String) = when (type) {
        "title", "ctrTitle" -> "title"
        "dt", "ftr", "sldNum", "hdr" -> type
        else -> "body"
    }

    /**
     * Draws [shape], in a group placed by [group] and filled with [groupFill] (null outside groups).
     * Always called through [guarded], which saves the canvas and restores it afterwards, so a shape
     * can turn the canvas as it needs.
     */
    private fun drawShape(canvas: Canvas, shape: Shape, scene: Scene, owner: Template, group: GroupFrame?, groupFill: Fill? = null) {
        if (shape.hidden) return
        // An empty placeholder (only a slide's are drawn) doesn't show in PowerPoint's slide show
        // or its PDFs, whatever fill and outline it has: not even a space was typed in it.
        if (shape.ph != null && shape.kind == Kind.SHAPE && shape.body?.paragraphs?.all { it.pieces.isEmpty() } != false && shape.fill !is Fill.Image) return
        val parents = shape.ph?.let { inherited(it, scene, owner) }.orEmpty()
        val own = shape.xfrm ?: parents.firstNotNullOfOrNull { it.xfrm } ?: return
        // In a flipped group the shape is mirrored: where it is, which way it faces and turns.
        val xfrm = group?.mirror(own.rotation, own.flipH, own.flipV)?.let { (rotation, flipH, flipV) -> Xfrm(own.rect, rotation, flipH, flipV) } ?: own
        val rect = group?.place(xfrm.rect) ?: xfrm.rect
        when (shape.kind) {
            Kind.GROUP -> {
                val inner = GroupFrame(rect, shape.childOffset, shape.childExtent, xfrm.flipH, xfrm.flipV)
                val innerFill = if (shape.groupFill) groupFill else shape.fill
                rotate(canvas, xfrm, rect)
                for (child in shape.children) guarded(canvas) { drawShape(canvas, child, scene, owner, inner, innerFill) }
                return
            }
            Kind.TABLE -> {
                shape.table?.let { drawTable(canvas, it, rect, scene, owner) }
                return
            }
            Kind.GRAPHIC -> {
                StandInPicture(shape.caption).draw(canvas, rect.toUnits())
                return
            }
            else -> Unit
        }
        rotate(canvas, xfrm, rect)
        val r = rect.toUnits()
        // The outline, with its adjustments, is the shape's own, or else the placeholder's it takes after.
        val source = if (shape.geometry != null || shape.custom != null) shape else parents.firstOrNull { it.geometry != null || it.custom != null }
        val geometry = source?.geometry ?: if (source == null && shape.kind != Kind.PICTURE) "rect" else null
        val adjust = source?.adjust ?: shape.adjust
        val fill = if (shape.groupFill) groupFill ?: Fill.None
        else shape.fill ?: parents.firstNotNullOfOrNull { it.fill } ?: shape.styleFill?.let { Fill.Solid(it) }
        val line = (shape.line ?: parents.firstNotNullOfOrNull { it.line }).let { own ->
            when {
                own == null -> shape.styleLine?.let { Outline(it, 0.75f, false) }
                own.color == null && own.width > 0f -> shape.styleLine?.let { own.copy(color = it) }
                else -> own
            }
        }
        if (shape.kind == Kind.PICTURE) {
            (fill as? Fill.Image)?.let { image ->
                canvas.withSave {
                    if (xfrm.flipH) scale(-1f, 1f, r.centerX(), r.centerY())
                    if (xfrm.flipV) scale(1f, -1f, r.centerX(), r.centerY())
                    clipPath(presetPath(geometry, r, adjust))
                    ImagePicture(images, image.path, rect.width, rect.height, image.crop).draw(this, r)
                }
            }
            canvas.stroke(line, presetPath(geometry, r, adjust))
        } else {
            val custom = source?.custom
            // How much a group shrinks or stretches the shape: a path without a size of its own is in the shape's EMUs.
            val unit = group?.place(OfficeRect(0f, 0f, 1f, 1f))
            val paths = custom?.let { customPaths(it, r, xfrm, unit?.width ?: 1f, unit?.height ?: 1f) }
                ?: listOf(Triple(flipped(presetPath(geometry, r, adjust), xfrm, r), true, true))
            for ((i, drawn) in paths.withIndex()) {
                val (path, filled, stroked) = drawn
                if (filled && !isLineShape(geometry)) canvas.fill(fill, path, r) { image -> ImagePicture(images, image.path, rect.width, rect.height, image.crop) }
                if (!stroked) continue
                // A line that isn't closed can have arrowheads.
                if (if (custom != null) custom[i].kinds.lastOrNull() != "close" else isLineShape(geometry)) canvas.strokeLine(line, path)
                else canvas.stroke(line, path)
            }
        }
        val body = shape.body
        if (body != null && body.hasText) {
            val textRect = shape.textRect?.let { group?.place(it) ?: it }?.toUnits() ?: r
            // Flipped upside down, a shape's text is too: a vertical flip is a half turn and a mirroring,
            // and text follows the turn but is never mirrored. (SmartArt places its text by a frame of its own.)
            if (xfrm.flipV && shape.textRect == null) canvas.rotate(180f, textRect.centerX(), textRect.centerY())
            drawText(canvas, body, textRect, textChain(shape, parents, scene, owner), parents.mapNotNull { it.body?.look }, shape.styleFont, scene.number, fill)
        }
    }

    private fun rotate(canvas: Canvas, xfrm: Xfrm, rect: OfficeRect) {
        if (xfrm.rotation != 0f) canvas.rotate(xfrm.rotation, rect.centerX.units, rect.centerY.units)
    }

    private fun flipped(path: Path, xfrm: Xfrm, r: RectF): Path {
        if (!xfrm.flipH && !xfrm.flipV) return path
        val m = android.graphics.Matrix()
        m.setScale(if (xfrm.flipH) -1f else 1f, if (xfrm.flipV) -1f else 1f, r.centerX(), r.centerY())
        path.transform(m)
        return path
    }

    /** The freeform [paths] of a shape drawn in [r], which its group scales by [groupX] and [groupY]. */
    private fun customPaths(paths: List<CustomPath>, r: RectF, xfrm: Xfrm, groupX: Float, groupY: Float): List<Triple<Path, Boolean, Boolean>> = paths.map { p ->
        // Without a size of its own, a path is in the shape's EMUs, before its group scales it.
        val sx = if (p.w > 0f) r.width() / p.w else U / OfficeUnits.EMU_PER_POINT * groupX
        val sy = if (p.h > 0f) r.height() / p.h else U / OfficeUnits.EMU_PER_POINT * groupY
        val path = Path()
        var cx = 0f
        var cy = 0f
        for ((kind, v) in p.kinds.zip(p.commands)) {
            fun px(i: Int) = r.left + v[i] * sx
            fun py(i: Int) = r.top + v[i] * sy
            when (kind) {
                "moveTo" -> if (v.size >= 2) { path.moveTo(px(0), py(1)); cx = v[0]; cy = v[1] }
                "lnTo" -> if (v.size >= 2) { path.lineTo(px(0), py(1)); cx = v[0]; cy = v[1] }
                "cubicBezTo" -> if (v.size >= 6) { path.cubicTo(px(0), py(1), px(2), py(3), px(4), py(5)); cx = v[4]; cy = v[5] }
                "quadBezTo" -> if (v.size >= 4) { path.quadTo(px(0), py(1), px(2), py(3)); cx = v[2]; cy = v[3] }
                "arcTo" -> if (v.size >= 4) {
                    val wR = v[0]
                    val hR = v[1]
                    val start = v[2] / 60_000f
                    val sweep = v[3] / 60_000f
                    val rad = Math.toRadians(start.toDouble())
                    val centerX = cx - wR * cos(rad).toFloat()
                    val centerY = cy - hR * sin(rad).toFloat()
                    path.arcTo(RectF(r.left + (centerX - wR) * sx, r.top + (centerY - hR) * sy, r.left + (centerX + wR) * sx, r.top + (centerY + hR) * sy), start, sweep)
                    val end = Math.toRadians((start + sweep).toDouble())
                    cx = centerX + wR * cos(end).toFloat()
                    cy = centerY + hR * sin(end).toFloat()
                }
                "close" -> path.close()
            }
        }
        Triple(flipped(path, xfrm, r), p.filled, p.stroked)
    }

    /** The text styles a shape's paragraphs inherit, nearest first, per level. */
    private fun textChain(shape: Shape, parents: List<Shape>, scene: Scene, owner: Template): List<Map<Int, LevelLook>> {
        val chain = ArrayList<Map<Int, LevelLook>>()
        shape.body?.levels?.let { chain += it }
        shape.styleFont?.let { color -> chain += (0..8).associateWith { LevelLook(run = RunLook(color = color)) } }
        for (parent in parents) parent.body?.levels?.let { chain += it }
        val master = scene.master ?: owner
        val kind = shape.ph?.let { kindOf(parents.firstNotNullOfOrNull { p -> p.ph?.type } ?: it.type) }
        when (kind) {
            "title" -> master.textStyles["title"]?.let { chain += it }
            "body" -> master.textStyles["body"]?.let { chain += it }
            else -> {
                master.textStyles["other"]?.let { chain += it }
                chain += defaultText(master)
            }
        }
        return chain
    }

    private fun drawText(canvas: Canvas, body: TextBody, box: RectF, chain: List<Map<Int, LevelLook>>, inheritedLooks: List<BodyLook>, styleFont: Int?, slide: Int, fill: Fill?) {
        val look = inheritedLooks.fold(body.look) { acc, l -> acc.over(l) }
        val insets = listOf(7.2f, 3.6f, 7.2f, 3.6f).mapIndexed { i, d -> (look.insets[i] ?: d).units }
        canvas.withSave {
            var area = RectF(box.left + insets[0], box.top + insets[1], box.right - insets[2], box.bottom - insets[3])
            // PowerPoint keeps stacked letters, and Chinese, Japanese and Korean characters, upright in
            // vertical text: they come closest across the box, wrapped to its width so they stand one
            // under the other in a narrow box.
            val upright = look.vertical == STACKED || (look.vertical == EAST_ASIAN && hasUprightText(body))
            val turn = when {
                look.vertical == 1 || (look.vertical == EAST_ASIAN && !upright) -> 1
                look.vertical == -1 -> -1
                else -> 0
            }
            if (turn != 0) {
                // Vertical text: drawn turned a quarter, in a box as tall as the shape is wide.
                canvas.rotate(if (turn == 1) 90f else -90f, box.centerX(), box.centerY())
                val cx = box.centerX()
                val cy = box.centerY()
                area = RectF(cx - box.height() / 2 + insets[0], cy - box.width() / 2 + insets[1], cx + box.height() / 2 - insets[2], cy + box.width() / 2 - insets[3])
            }
            // In vertical text, wrap="none" keeps columns from breaking at the box's foot, not lines at its side.
            val wrap = upright || look.wrap != false
            val width = if (wrap) max(1, area.width().toInt()) else (slideWidth.units * 4).toInt()
            val darkFill = fill?.mainColor?.let(OfficeColors::isDark) == true
            var scale = look.fontScale ?: 1f
            val reduction = look.lineReduction ?: 0f
            // Unless the text body asks for them, PowerPoint leaves out the first paragraph's space
            // before and the last one's space after.
            val edges = look.firstLastSpacing == true
            fun height(blocks: List<Spaced>): Int {
                val all = blocks.sumOf { it.before + it.block.height + it.after }
                return if (edges) all else all - (blocks.firstOrNull()?.before ?: 0) - (blocks.lastOrNull()?.after ?: 0)
            }
            var blocks = layoutText(body, chain, width, scale, reduction, slide, styleFont, darkFill)
            // Text that overflows a box PowerPoint shrinks text in is shrunk until it fits.
            if (look.autofit == 1) {
                while (height(blocks) > area.height() && scale > 0.3f) {
                    scale *= 0.9f
                    blocks = layoutText(body, chain, width, scale, minOf(0.2f, reduction + 0.05f), slide, styleFont, darkFill)
                }
            }
            val anchor = look.anchor ?: 0
            var y = area.top + (area.height() - height(blocks)) * anchor / 2f
            for ((i, item) in blocks.withIndex()) {
                if (i > 0 || edges) y += item.before
                val block = item.block as ParagraphBlock
                // Text that isn't wrapped is laid out wider than the box, and placed by its side.
                val align = block.spec.align
                val x = when {
                    wrap -> area.left
                    align == Align.CENTER -> area.centerX() - width / 2f
                    (align == Align.END) != block.spec.rtl -> area.right - width
                    else -> area.left
                }
                block.draw(canvas, x, y)
                y += block.height + item.after
            }
        }
    }

    /** Whether [body] has characters PowerPoint keeps upright in vertical text: Chinese, Japanese or Korean ones. */
    private fun hasUprightText(body: TextBody) = body.paragraphs.any { p ->
        p.pieces.any { piece -> piece is Piece.Text && piece.text.codePoints().anyMatch { Character.UnicodeScript.of(it) in UPRIGHT_SCRIPTS } }
    }

    private fun layoutText(body: TextBody, chain: List<Map<Int, LevelLook>>, width: Int, scale: Float, reduction: Float, slide: Int, styleFont: Int?, darkFill: Boolean): List<Spaced> {
        val counters = IntArray(9)
        val out = ArrayList<Spaced>()
        for (p in body.paragraphs) {
            val level = p.level
            var look = p.look ?: LevelLook()
            for (levels in chain) look = look.over(levels[level])
            val rtl = look.rtl == true
            val baseRun = look.run ?: RunLook()
            val rich = RichText()
            var firstStyle: TextStyle? = null
            for (piece in p.pieces) {
                val run = (piece.look ?: RunLook()).over(baseRun)
                val style = textStyle(run, scale, styleFont, darkFill)
                if (firstStyle == null) firstStyle = style
                when (piece) {
                    // Characters of Symbol and Wingdings, which phones don't have, as the ones they show.
                    is Piece.Text -> rich.append(SymbolFonts.text(themeFont(run.font), piece.text, run.symbol), style)
                    is Piece.Break -> rich.append("\n", style)
                    is Piece.SlideNumber -> rich.append(slide.toString(), style)
                }
            }
            val mark = textStyle((p.end ?: RunLook()).over(baseRun), scale, styleFont, darkFill)
            val first = firstStyle ?: mark
            val hasText = rich.hasContent
            // Numbering counts consecutive numbered paragraphs at a level; a higher level starts it over.
            for (deeper in level + 1 until 9) counters[deeper] = 0
            val bullet = if (hasText) look.bullet else null
            val labelText = when (bullet) {
                is Bullet.Char -> SymbolFonts.bullet(look.bulletFont, bullet.text)
                is Bullet.Number -> {
                    counters[level] = if (counters[level] == 0) bullet.start else counters[level] + 1
                    val (format, prefix, suffix) = ListNumbering.powerPointScheme(bullet.scheme)
                    prefix + ListNumbering.format(counters[level], format) + suffix
                }
                else -> {
                    // An empty paragraph in a numbered list shows no number, and the list goes on counting after it, as in PowerPoint.
                    if (hasText || look.bullet !is Bullet.Number) counters[level] = 0
                    null
                }
            }
            val marL = (look.marL ?: 0f).units
            val firstStart = marL + (look.indent ?: 0f).units
            var textStart = firstStart
            val label = labelText?.takeIf { it.isNotEmpty() }?.let { text ->
                val style = first.copy(
                    size = first.size * (look.bulletSize ?: 1f),
                    color = look.bulletColor ?: first.color,
                    underline = false, strike = false, background = null, bold = first.bold && bullet is Bullet.Number,
                )
                val end = firstStart + style.paint().measureText(text)
                textStart = if (end <= marL) marL else end + style.size.units * 0.3f
                Label(text, style, firstStart.toInt())
            }
            val size = first.size
            val line = when (val l = look.line) {
                is LineSpacing.Multiple -> LineSpacing.Multiple(max(0.5f, l.factor - reduction))
                null -> LineSpacing.Multiple(1f - reduction)
                else -> l
            }
            val block = ParagraphBlock(
                ParagraphSpec(
                    text = rich.text,
                    base = mark,
                    width = width,
                    // DrawingML's l and r are the left and right sides, whichever way the paragraph reads.
                    align = when (val align = look.align) {
                        null -> Align.START
                        Align.START -> if (rtl) Align.END else Align.START
                        Align.END -> if (rtl) Align.START else Align.END
                        else -> align
                    },
                    firstIndent = textStart.toInt(),
                    indent = marL.toInt(),
                    spacing = line,
                    // PowerPoint's single spacing is 1.2 times the font size, whatever the font.
                    lineScale = OfficeFonts.lineScale(first, 1.2f),
                    label = label,
                    // A paragraph reads the way it says, not the way its first letter does; a
                    // right-to-left one has its indents and bullet on the right.
                    rtl = rtl,
                    mirrored = rtl,
                    directionFromText = false,
                    avoidSingleLines = false,
                ),
            )
            out += Spaced(block, (look.before?.points(size) ?: 0f).units.toInt(), (look.after?.points(size) ?: 0f).units.toInt())
        }
        return out
    }

    private fun textStyle(look: RunLook, scale: Float, styleFont: Int?, darkFill: Boolean): TextStyle = TextStyle(
        font = look.font,
        size = (look.size ?: 18f) * scale,
        bold = look.bold == true,
        italic = look.italic == true,
        underline = look.underline == true,
        strike = look.strike == true,
        color = look.color ?: styleFont ?: if (darkFill) Color.WHITE else Color.BLACK,
        background = look.highlight,
        baseline = look.baseline ?: 0,
        caps = look.caps == true,
        spacing = (look.spacing ?: 0f) * scale,
    ).let { style -> style.copy(font = themeFont(style.font)) }

    private var currentTheme: OfficeTheme = OfficeTheme.DEFAULT

    private fun themeFont(name: String?): String? = currentTheme.font(name ?: "+mn-lt")

    private fun drawTable(canvas: Canvas, table: SlideTable, rect: OfficeRect, scene: Scene, owner: Template) {
        val template = scene.master ?: owner
        val ctx = Ctx("", template.theme, template.colorMap)
        val style = tableStyle(table.styleId, ctx) ?: table.styleId?.let { defaultTableStyle(ctx) }
        // A column covered by a merged cell (hMerge) is counted once, with the cell spanning it.
        val spans = table.rows.maxOfOrNull { (_, cells) -> cells.filterNot { it.hMerge }.sumOf { it.span } } ?: return
        val columns = max(spans, table.grid.size).coerceAtMost(MAX_COLUMNS)
        // Without usable widths in its grid, the columns share the table's frame.
        val widths = TableColumns.widths(table.grid, columns, slideWidth * 4, rect.width).map { it.units }
        val starts = widths.runningFold(0f) { acc, w -> acc + w }
        val chain = listOfNotNull(scene.master?.textStyles?.get("other"), defaultText(template))
        val lastRow = table.rows.lastIndex
        // Cells merged down over the rows below (rowSpan): their text goes across those rows, once
        // their heights are known, and the rows they cover take their fill, with no line between.
        class Tall(val row: Int, val lastRow: Int, val box: CellBox)
        val tall = ArrayList<Tall>()
        val coveredBy = HashMap<Int, Tall>()
        val rows = table.rows.mapIndexed { r, (height, cells) ->
            var col = 0
            val boxes = cells.mapNotNull { cell ->
                // Covered by the cell before it, which spans this column too.
                if (cell.hMerge) return@mapNotNull null
                val start = col.coerceAtMost(columns - 1)
                val end = (col + cell.span).coerceAtMost(columns)
                col += cell.span
                val partStyles = SlideTableStyles.parts(table.flags, r, table.rows.size, start, end, columns)
                    .mapNotNull { name -> style?.parts?.get(name)?.let { name to it } }
                val fill = cell.fill ?: partStyles.lastOrNull { it.second.fill != null }?.second?.fill
                val bold = partStyles.lastOrNull { it.second.bold != null }?.second?.bold
                val color = partStyles.lastOrNull { it.second.color != null }?.second?.color
                // The strongest part with a border for the edge draws it, or leaves it out.
                fun border(i: Int, side: String, outer: Boolean): Border? {
                    val outline = cell.borders[i] ?: partStyles.asReversed().firstNotNullOfOrNull { (name, part) -> part.borders[SlideTableStyles.border(name, side, outer)] }
                    return outline?.color?.let { Border(outline.width, it) }
                }
                val margins = listOf(7.2f, 3.6f, 7.2f, 3.6f).mapIndexed { i, d -> (cell.margins[i] ?: d).units.toInt() }
                val width = starts[end] - starts[start]
                val content = cell.body?.takeIf { !cell.vMerge && it.hasText }?.let { body ->
                    val override = LevelLook(run = RunLook(bold = bold, color = color))
                    val levels = listOf(body.levels, (0..8).associateWith { override }) + chain
                    StackBlock(layoutText(body, levels, max(1, (width - margins[0] - margins[2]).toInt()), 1f, 0f, scene.number, null, fill?.mainColor?.let(OfficeColors::isDark) == true))
                }
                val over = if (cell.vMerge) coveredBy[start]?.takeIf { r <= it.lastRow } else null
                val spansDown = !cell.vMerge && cell.rowSpan > 1 && r < lastRow
                // A right-to-left table's columns go from the right, each cell's left side (its start) on its right.
                val startSide = border(0, "left", start == 0)
                val endSide = border(2, "right", end >= columns)
                val box = CellBox(
                    x = if (table.rtl) starts[columns] - starts[end] else starts[start], width = width, content = content,
                    fill = if (over != null) over.box.fill else (fill as? Fill.Solid)?.color ?: fill?.mainColor,
                    top = if (cell.vMerge) Border.NONE else border(1, "top", r == 0),
                    left = if (table.rtl) endSide else startSide,
                    bottom = if (spansDown || (over != null && r < over.lastRow)) Border.NONE else border(3, "bottom", r == lastRow),
                    right = if (table.rtl) startSide else endSide,
                    verticalAlign = cell.anchor ?: 0,
                    padLeft = margins[0], padTop = margins[1], padRight = margins[2], padBottom = margins[3],
                )
                if (spansDown) {
                    Tall(r, min(lastRow, r + cell.rowSpan - 1), box).also { tall += it; coveredBy[start] = it }
                    box.copy(content = null)
                } else {
                    box
                }
            }
            height.units.toInt() to boxes
        }
        val blocks = rows.map { (height, boxes) -> RowBlock(boxes, height, exact = false) }.toMutableList()
        // A merged cell whose text needs more room than its rows have makes the last of them taller.
        for (t in tall) {
            val need = (t.box.content?.height ?: 0) + t.box.padTop + t.box.padBottom
            val have = (t.row..t.lastRow).sumOf { blocks[it].height }
            if (need > have) blocks[t.lastRow] = RowBlock(rows[t.lastRow].second, blocks[t.lastRow].height + need - have, exact = false)
        }
        val x = rect.x.units
        val y = rect.y.units
        TableBlock(blocks, 0, 0f).draw(canvas, x, y)
        val tops = blocks.runningFold(0) { acc, row -> acc + row.height }
        for (t in tall) {
            val content = t.box.content ?: continue
            val free = tops[t.lastRow + 1] - tops[t.row] - t.box.padTop - t.box.padBottom - content.height
            content.draw(canvas, x + t.box.x + t.box.padLeft, y + tops[t.row] + t.box.padTop + if (free > 0) free * t.box.verticalAlign / 2f else 0f)
        }
    }

    /** PowerPoint's default table look (Medium Style 2, Accent 1), for styles the file doesn't include. */
    private fun defaultTableStyle(ctx: Ctx): TableStyle {
        val accent = ctx.scheme("accent1") ?: 0xFF4472C4.toInt()
        val light = ctx.scheme("lt1") ?: Color.WHITE
        val dark = ctx.scheme("dk1") ?: Color.BLACK
        val white = Outline(light, 1f, false)
        val grid = mapOf("left" to white, "right" to white, "top" to white, "bottom" to white, "insideH" to white, "insideV" to white)
        return TableStyle(
            mapOf(
                "wholeTbl" to TableStylePart(Fill.Solid(OfficeColors.modify(accent, listOf("tint" to 20_000))), grid, null, dark),
                "band1H" to TableStylePart(Fill.Solid(OfficeColors.modify(accent, listOf("tint" to 40_000))), emptyMap(), null, null),
                "firstRow" to TableStylePart(Fill.Solid(accent), emptyMap(), true, light),
                "lastRow" to TableStylePart(Fill.Solid(accent), emptyMap(), true, light),
            ),
        )
    }

    private companion object {
        /** Far more columns and rows than PowerPoint lets a table have (75 each): only a crafted file has more. */
        const val MAX_COLUMNS = 1000
        const val MAX_ROWS = 1000

        /** A text body's [BodyLook.vertical] for vert="eaVert", and for PowerPoint's stacked letters. */
        const val EAST_ASIAN = 2
        const val STACKED = 3

        /** Scripts PowerPoint keeps upright in East Asian vertical text. */
        val UPRIGHT_SCRIPTS = setOf(
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL, Character.UnicodeScript.BOPOMOFO,
        )

        /** For a slide without a master: Office's default theme and colour map. */
        val NO_MASTER = Template(emptyList(), null, true, OfficeTheme.DEFAULT, emptyMap(), emptyMap())
    }

    private fun OfficeRect.toUnits() = RectF(x.units, y.units, (x + width).units, (y + height).units)
}

