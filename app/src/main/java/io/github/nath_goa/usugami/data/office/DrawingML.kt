package io.github.nath_goa.usugami.data.office

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.withClip
import io.github.nath_goa.usugami.core.OfficeColors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A theme (theme1.xml): the colour scheme and the heading ("major") and body ("minor") fonts that
 * Word and PowerPoint documents refer to by name.
 */
internal class OfficeTheme(val colors: Map<String, Int>, val majorFont: String?, val minorFont: String?) {
    /** A theme font reference such as "+mn-lt" or "minorHAnsi", or [name] itself. */
    fun font(name: String?): String? = when {
        name == null -> null
        name.startsWith("+mj") || name.startsWith("major") -> majorFont
        name.startsWith("+mn") || name.startsWith("minor") -> minorFont
        else -> name
    }

    companion object {
        /** Office's own default theme, for documents without one. */
        val DEFAULT = OfficeTheme(
            mapOf(
                "dk1" to 0xFF000000.toInt(), "lt1" to 0xFFFFFFFF.toInt(), "dk2" to 0xFF44546A.toInt(), "lt2" to 0xFFE7E6E6.toInt(),
                "accent1" to 0xFF4472C4.toInt(), "accent2" to 0xFFED7D31.toInt(), "accent3" to 0xFFA5A5A5.toInt(),
                "accent4" to 0xFFFFC000.toInt(), "accent5" to 0xFF5B9BD5.toInt(), "accent6" to 0xFF70AD47.toInt(),
                "hlink" to 0xFF0563C1.toInt(), "folHlink" to 0xFF954F72.toInt(),
            ),
            "Calibri Light", "Calibri",
        )

        fun read(pkg: OfficePackage, path: String?, check: () -> Unit): OfficeTheme {
            val x = path?.let { pkg.xml(it, check) } ?: return DEFAULT
            val colors = HashMap(DEFAULT.colors)
            var major: String? = null
            var minor: String? = null
            x.use {
                x.children {
                    if (x.name == "themeElements") x.children {
                        when (x.name) {
                            "clrScheme" -> x.children {
                                val key = x.name
                                x.children { readColor(x) { null }?.let { colors[key] = it } }
                            }
                            "fontScheme" -> x.children {
                                val which = x.name
                                x.children {
                                    if (x.name == "latin") {
                                        val face = x.attr("typeface")?.takeIf { it.isNotEmpty() }
                                        if (which == "majorFont") major = face else if (which == "minorFont") minor = face
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return OfficeTheme(colors, major ?: DEFAULT.majorFont, minor ?: DEFAULT.minorFont)
        }
    }
}

/**
 * Reads the DrawingML colour element the reader is at (a:srgbClr, a:schemeClr, a:sysClr...),
 * with its modifiers (lighter, darker, transparent...). [scheme] gives a scheme colour by name.
 * Null for an element that isn't a colour.
 */
internal fun readColor(x: OfficeXml, scheme: (String) -> Int?): Int? {
    val base = when (x.name) {
        "srgbClr" -> OfficeColors.hex(x.attr("val"))
        "schemeClr" -> x.attr("val")?.let(scheme)
        "sysClr" -> OfficeColors.hex(x.attr("lastClr")) ?: if (x.attr("val") == "window") 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        "prstClr" -> OfficeColors.preset(x.attr("val"))
        "scrgbClr" -> {
            fun c(name: String) = ((x.intAttr(name) ?: 0) / 100_000f * 255).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (c("r") shl 16) or (c("g") shl 8) or c("b")
        }
        "hslClr" -> OfficeColors.modify(0xFF808080.toInt(), listOf())
        else -> return null
    }
    val modifiers = ArrayList<Pair<String, Int>>()
    x.children { x.intAttr("val")?.let { modifiers += x.name to it } }
    return base?.let { OfficeColors.modify(it, modifiers) }
}

/** What fills a shape, a background or a table cell. */
internal sealed class Fill {
    data object None : Fill()
    data class Solid(val color: Int) : Fill()

    /** Colour stops (position 0..1, colour); [angle] in degrees, clockwise from left-to-right. */
    data class Gradient(val stops: List<Pair<Float, Int>>, val angle: Float, val radial: Boolean) : Fill()

    /** A picture, with the share of each side cropped off (left, top, right, bottom). */
    data class Image(val path: String, val crop: FloatArray?, val tile: Boolean) : Fill()

    /** A single colour standing in for this fill, for text contrast and the like. */
    val mainColor: Int?
        get() = when (this) {
            is Solid -> color
            is Gradient -> stops.firstOrNull()?.second
            else -> null
        }
}

/** A shape's outline. [width] in points. */
internal data class Outline(val color: Int?, val width: Float, val dashed: Boolean) {
    companion object {
        val NONE = Outline(null, 0f, false)
    }
}

/**
 * Reads the fill element the reader is at (a:solidFill, a:gradFill, a:blipFill, a:noFill,
 * a:pattFill), or returns null for anything else. [part] is the part the reader is in, for pictures.
 */
internal fun readFill(x: OfficeXml, pkg: OfficePackage, part: String, scheme: (String) -> Int?): Fill? = when (x.name) {
    "noFill" -> Fill.None
    "solidFill" -> {
        var color: Int? = null
        x.children { readColor(x, scheme)?.let { color = it } }
        color?.let { Fill.Solid(it) } ?: Fill.None
    }
    "pattFill" -> {
        var color: Int? = null
        x.children { if (x.name == "fgClr") x.children { readColor(x, scheme)?.let { color = it } } }
        color?.let { Fill.Solid(it) } ?: Fill.None
    }
    "gradFill" -> {
        val stops = ArrayList<Pair<Float, Int>>()
        var angle = 90f
        var radial = false
        x.children {
            when (x.name) {
                "gsLst" -> x.children {
                    val pos = (x.intAttr("pos") ?: 0) / 100_000f
                    x.children { readColor(x, scheme)?.let { stops += pos to it } }
                }
                "lin" -> angle = (x.intAttr("ang") ?: 0) / 60_000f
                "path" -> radial = true
            }
        }
        if (stops.isEmpty()) Fill.None else Fill.Gradient(stops.sortedBy { it.first }, angle, radial)
    }
    "blipFill" -> {
        var path: String? = null
        var crop: FloatArray? = null
        var tile = false
        x.children {
            when (x.name) {
                "blip" -> path = pkg.target(part, x.relId("embed"))
                "srcRect" -> crop = floatArrayOf(
                    (x.intAttr("l") ?: 0) / 100_000f, (x.intAttr("t") ?: 0) / 100_000f,
                    (x.intAttr("r") ?: 0) / 100_000f, (x.intAttr("b") ?: 0) / 100_000f,
                ).takeIf { c -> c.any { it != 0f } }
                "tile" -> tile = true
            }
        }
        path?.let { Fill.Image(it, crop, tile) } ?: Fill.None
    }
    "grpFill" -> null
    else -> null
}

/** Reads a:ln, the reader being at it. */
internal fun readOutline(x: OfficeXml, scheme: (String) -> Int?): Outline {
    val width = (x.longAttr("w") ?: 9525L) / 12_700f
    var color: Int? = null
    var none = false
    var dashed = false
    x.children {
        when (x.name) {
            "noFill" -> none = true
            "solidFill" -> x.children { readColor(x, scheme)?.let { color = it } }
            "gradFill" -> x.children { if (x.name == "gsLst") x.children { x.children { if (color == null) color = readColor(x, scheme) } } }
            "prstDash" -> dashed = x.attr("val").let { it != null && it != "solid" }
        }
    }
    return if (none) Outline.NONE else Outline(color, width, dashed)
}

/** Paints [fill] over [path] (in [bounds]); pictures are drawn by [picture]. */
internal fun Canvas.fill(fill: Fill?, path: Path, bounds: RectF, picture: (Fill.Image) -> Picture?) {
    when (fill) {
        null, Fill.None -> Unit
        is Fill.Solid -> drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill.color })
        is Fill.Gradient -> drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradient(fill, bounds) })
        is Fill.Image -> {
            val image = picture(fill) ?: return
            withClip(path) { image.draw(this, bounds) }
        }
    }
}

private fun gradient(fill: Fill.Gradient, r: RectF): Shader {
    val colors = fill.stops.map { it.second }.toIntArray().let { if (it.size == 1) intArrayOf(it[0], it[0]) else it }
    val positions = fill.stops.map { it.first }.toFloatArray().let { if (it.size == 1) floatArrayOf(0f, 1f) else it }
    if (fill.radial) return RadialGradient(r.centerX(), r.centerY(), maxOf(1f, maxOf(r.width(), r.height()) / 2), colors, positions, Shader.TileMode.CLAMP)
    val angle = Math.toRadians(fill.angle.toDouble())
    val dx = (cos(angle) * r.width() / 2).toFloat()
    val dy = (sin(angle) * r.height() / 2).toFloat()
    return LinearGradient(r.centerX() - dx, r.centerY() - dy, r.centerX() + dx, r.centerY() + dy, colors, positions, Shader.TileMode.CLAMP)
}

/** Draws [outline] along [path]. */
internal fun Canvas.stroke(outline: Outline?, path: Path) {
    val color = outline?.color ?: return
    if (outline.width <= 0f) return
    drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.color = color
        strokeWidth = outline.width.units
        if (outline.dashed) pathEffect = android.graphics.DashPathEffect(floatArrayOf(4 * strokeWidth, 3 * strokeWidth), 0f)
    })
}

/**
 * The outline of a preset shape (a:prstGeom) in [r]. The common shapes are drawn as they are;
 * others become their bounding rectangle, with rounded corners when the name suggests them.
 * [adjust] holds the shape's adjustments (a:gd), in 1/100,000ths.
 */
internal fun presetPath(name: String?, r: RectF, adjust: Map<String, Long> = emptyMap()): Path {
    val p = Path()
    val w = r.width()
    val h = r.height()
    val ss = min(w, h)
    fun adj(key: String, default: Long) = (adjust[key] ?: default) / 100_000f
    fun poly(vararg points: Float) {
        p.moveTo(r.left + points[0] * w, r.top + points[1] * h)
        for (i in 2 until points.size step 2) p.lineTo(r.left + points[i] * w, r.top + points[i + 1] * h)
        p.close()
    }
    when (name) {
        "ellipse", "flowChartConnector" -> p.addOval(r, Path.Direction.CW)
        "roundRect", "flowChartAlternateProcess" -> {
            val radius = ss * adj("adj", 16_667)
            p.addRoundRect(r, radius, radius, Path.Direction.CW)
        }
        "triangle", "flowChartExtract" -> poly(adj("adj", 50_000), 0f, 1f, 1f, 0f, 1f)
        "rtTriangle" -> poly(0f, 0f, 1f, 1f, 0f, 1f)
        "diamond", "flowChartDecision" -> poly(0.5f, 0f, 1f, 0.5f, 0.5f, 1f, 0f, 0.5f)
        "parallelogram", "flowChartInputOutput" -> {
            val a = ss * adj("adj", 25_000) / w
            poly(a, 0f, 1f, 0f, 1 - a, 1f, 0f, 1f)
        }
        "trapezoid" -> {
            val a = ss * adj("adj", 25_000) / w
            poly(a, 0f, 1 - a, 0f, 1f, 1f, 0f, 1f)
        }
        "hexagon" -> {
            val a = ss * adj("adj", 25_000) / w
            poly(a, 0f, 1 - a, 0f, 1f, 0.5f, 1 - a, 1f, a, 1f, 0f, 0.5f)
        }
        "octagon" -> {
            val a = ss * adj("adj", 29_289)
            val ax = a / w
            val ay = a / h
            poly(ax, 0f, 1 - ax, 0f, 1f, ay, 1f, 1 - ay, 1 - ax, 1f, ax, 1f, 0f, 1 - ay, 0f, ay)
        }
        "pentagon" -> poly(0.5f, 0f, 1f, 0.38f, 0.81f, 1f, 0.19f, 1f, 0f, 0.38f)
        "homePlate" -> {
            val a = ss * adj("adj", 50_000) / w
            poly(0f, 0f, 1 - a, 0f, 1f, 0.5f, 1 - a, 1f, 0f, 1f)
        }
        "chevron" -> {
            val a = ss * adj("adj", 50_000) / w
            poly(0f, 0f, 1 - a, 0f, 1f, 0.5f, 1 - a, 1f, 0f, 1f, a, 0.5f)
        }
        "rightArrow", "leftArrow" -> {
            val shaft = adj("adj1", 50_000)
            val head = ss * adj("adj2", 50_000) / w
            val t = (1 - shaft) / 2
            if (name == "rightArrow") poly(0f, t, 1 - head, t, 1 - head, 0f, 1f, 0.5f, 1 - head, 1f, 1 - head, 1 - t, 0f, 1 - t)
            else poly(1f, t, head, t, head, 0f, 0f, 0.5f, head, 1f, head, 1 - t, 1f, 1 - t)
        }
        "upArrow", "downArrow" -> {
            val shaft = adj("adj1", 50_000)
            val head = ss * adj("adj2", 50_000) / h
            val t = (1 - shaft) / 2
            if (name == "downArrow") poly(t, 0f, 1 - t, 0f, 1 - t, 1 - head, 1f, 1 - head, 0.5f, 1f, 0f, 1 - head, t, 1 - head)
            else poly(t, 1f, 1 - t, 1f, 1 - t, head, 1f, head, 0.5f, 0f, 0f, head, t, head)
        }
        "star5" -> {
            val outer = FloatArray(20)
            for (i in 0 until 10) {
                val angle = Math.toRadians(-90.0 + i * 36.0)
                val radius = if (i % 2 == 0) 0.5f else 0.19f
                outer[2 * i] = 0.5f + radius * cos(angle).toFloat()
                outer[2 * i + 1] = 0.5f + radius * 1.05f * sin(angle).toFloat() + 0.03f
            }
            poly(*outer)
        }
        "plus", "flowChartPredefinedProcess" -> if (name == "plus") {
            val a = ss * adj("adj", 25_000)
            val ax = a / w
            val ay = a / h
            poly(ax, 0f, 1 - ax, 0f, 1 - ax, ay, 1f, ay, 1f, 1 - ay, 1 - ax, 1 - ay, 1 - ax, 1f, ax, 1f, ax, 1 - ay, 0f, 1 - ay, 0f, ay, ax, ay)
        } else p.addRect(r, Path.Direction.CW)
        "line", "straightConnector1" -> {
            p.moveTo(r.left, r.top)
            p.lineTo(r.right, r.bottom)
        }
        "snip1Rect", "snip2SameRect", "round1Rect", "round2SameRect", "snipRoundRect", "round2DiagRect" -> {
            val radius = ss * 0.16667f
            p.addRoundRect(r, radius, radius, Path.Direction.CW)
        }
        else -> p.addRect(r, Path.Direction.CW)
    }
    return p
}

/** Whether a preset shape is a line, with nothing to fill. */
internal fun isLineShape(name: String?) = name == "line" || name?.contains("Connector") == true
