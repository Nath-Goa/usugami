package io.github.nath_goa.usugami.data

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import io.github.nath_goa.usugami.core.TextItem
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Edit PDF's text: laid out and drawn by Android, on the screen and in the PDF alike, so the PDF
 * has the text exactly where the preview showed it. As with watermarks ([WatermarkText]), the PDF's
 * text is drawn through Android's own PDF backend, which shapes every script the phone has fonts
 * for and embeds just the letters used, then put on the page as a form: it stays real text that can
 * be searched and selected.
 */
object PageText {
    /** Text is laid out and drawn at this size, in points, and scaled to its own. */
    const val REFERENCE = 100f

    /** Longer text is cut: every text box is kept in the screen state Android saves. */
    const val MAX_LENGTH = 500

    /** Room around each text's box in the PDF it is drawn in, at [REFERENCE], for letters that reach past it. */
    const val PAD = 50f

    /** How text is drawn, at [REFERENCE]. The preview draws with it too, so it matches. */
    fun paint(argb: Int) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        typeface = Typeface.SANS_SERIF
        textSize = REFERENCE
        color = argb
    }

    /**
     * [text] in lines, left-aligned, at [REFERENCE]: each line's baseline is [ascent] plus
     * [lineHeight] for each line above it, below the top of the box, which is [width] × [height].
     */
    class Layout(val lines: List<String>, val width: Float, val ascent: Float, val lineHeight: Float) {
        val height: Float get() = lineHeight * lines.size
    }

    fun layout(text: String, paint: Paint = paint(0)): Layout {
        val lines = text.split('\n')
        val metrics = paint.fontMetrics
        var top = metrics.ascent
        var bottom = metrics.descent
        // Scripts drawn from another font, such as Devanagari, can reach higher or lower than the
        // main font says; their own letters set the line height then.
        val ink = Rect()
        for (line in lines) {
            if (line.isEmpty()) continue
            paint.getTextBounds(line, 0, line.length, ink)
            top = min(top, ink.top.toFloat())
            bottom = max(bottom, ink.bottom.toFloat())
        }
        val width = lines.maxOf { paint.measureText(it) }
        return Layout(lines, max(width, 1f), -top, bottom - top)
    }

    /** The box [text] needs at a font size of one point, width then height (see [TextItem.unitWidth]). */
    fun unitSize(text: String): Pair<Float, Float> = layout(text).let { it.width / REFERENCE to it.height / REFERENCE }

    /** Draws [layout] with [paint], at [REFERENCE], with its box's top-left corner at the origin. */
    fun draw(canvas: Canvas, layout: Layout, paint: Paint) {
        layout.lines.forEachIndexed { i, line ->
            if (line.isNotEmpty()) canvas.drawText(line, 0f, layout.ascent + i * layout.lineHeight, paint)
        }
    }

    /** Whether a font on this phone has every character of [text] that needs drawing. */
    fun canDraw(text: String): Boolean = WatermarkText.canDraw(text, paint(0xFF000000.toInt()))

    /**
     * A PDF with a page for each of [items], in order, each with its text at [REFERENCE] and [PAD]
     * points of room around its box. Throws [WatermarkFontMissing] when a text can't be drawn.
     */
    fun render(items: List<TextItem>, check: () -> Unit): ByteArray {
        val document = PdfDocument()
        try {
            items.forEachIndexed { index, item ->
                check()
                if (!canDraw(item.text)) throw WatermarkFontMissing()
                val paint = paint(item.color)
                val layout = layout(item.text, paint)
                val width = ceil(layout.width + 2 * PAD).toInt()
                val height = ceil(layout.height + 2 * PAD).toInt()
                val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                page.canvas.translate(PAD, PAD)
                draw(page.canvas, layout, paint)
                document.finishPage(page)
            }
            return ByteArrayOutputStream().also(document::writeTo).toByteArray()
        } finally {
            document.close()
        }
    }
}
