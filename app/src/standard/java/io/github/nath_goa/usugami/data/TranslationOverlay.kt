package io.github.nath_goa.usugami.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import io.github.nath_goa.usugami.core.BlockAlign
import io.github.nath_goa.usugami.core.Fit
import io.github.nath_goa.usugami.core.PageColors
import io.github.nath_goa.usugami.core.TextMeasurer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * How Translate PDF puts a translation where its paragraph was: the paragraph painted over on the
 * page's picture in the colour around it, and the translation drawn by Android in the colour of
 * the paragraph's letters, as real text (see [WatermarkText]: Android's PDF backend shapes every
 * script the phone has a font for and embeds just the letters used).
 *
 * Text is laid out as it is measured for fitting (see [measurer]): the same paint, wrapped at the
 * same whole number of layout pixels, so what fits when measured fits when drawn.
 */
internal object TranslationOverlay {
    /**
     * Text is laid out at this many times its size in points, and drawn scaled back: Android's
     * layout gives lines whole pixels of height, which at this scale are a tenth of a point.
     */
    const val SCALE = 10f

    /** The colour the letters get when a block's own can't be told: never used for a block drawn. */
    private const val BLACK = 0xFF000000.toInt()

    private val bold: Typeface by lazy { Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }

    /** How a translation is drawn: sans-serif, bold for a bold paragraph, [size] points (at [SCALE]), in [color]. */
    fun paint(size: Float, bold: Boolean, color: Int): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
            typeface = if (bold) this@TranslationOverlay.bold else Typeface.SANS_SERIF
            textSize = size * SCALE
            this.color = color
        }

    /** [width] points as layout pixels, rounded up: the same for measuring and drawing. */
    fun layoutWidth(width: Float): Int = ceil(width * SCALE - 0.001f).toInt().coerceAtLeast(1)

    /**
     * [text] laid out with [paint] [width] points wide, lined up as [align] says in the direction its
     * first letters give (right to left for Arabic or Hebrew), without hyphenation, lines as tall
     * as the fonts of their letters need.
     */
    fun layout(text: String, paint: TextPaint, width: Float, align: BlockAlign): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, layoutWidth(width))
            .setAlignment(
                when (align) {
                    BlockAlign.START -> Layout.Alignment.ALIGN_NORMAL
                    BlockAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                    BlockAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
                },
            )
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .setUseLineSpacingFromFallbacks(true)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

    /** Measures text as [draw] draws it for a block that is [bold] and lined up as [align] says, in points. */
    fun measurer(bold: Boolean, align: BlockAlign): TextMeasurer {
        val paint = paint(1f, bold, BLACK)
        return TextMeasurer { text, size, width ->
            paint.textSize = size * SCALE
            layout(text, paint, width, align).height / SCALE
        }
    }

    /** Whether a font on this phone has every character of [text] that needs drawing, as [draw] draws it. */
    fun canDraw(text: String, bold: Boolean): Boolean = WatermarkText.canDraw(text, paint(12f, bold, BLACK))

    /** Draws [text] on [canvas] (in points of the page) at [fit]'s place and size, in [color], lined up as [align] says. */
    fun draw(canvas: Canvas, text: String, fit: Fit, bold: Boolean, color: Int, align: BlockAlign) {
        val layout = layout(text, paint(fit.size, bold, color), fit.area.width, align)
        canvas.save()
        canvas.translate(fit.area.left, fit.area.top)
        canvas.scale(1 / SCALE, 1 / SCALE)
        layout.draw(canvas)
        canvas.restore()
    }

    /**
     * The pixels of a picture [width] × [height] big, [perX] and [perY] pixels to the point, that
     * the box [left], [top], [right], [bottom] (points) covers once [padX] and [padY] points bigger
     * on each side: rounded outwards, so a pixel it only partly covers is in, and cut to the picture.
     */
    fun pixels(left: Float, top: Float, right: Float, bottom: Float, padX: Float, padY: Float, perX: Float, perY: Float, width: Int, height: Int): Rect = Rect(
        floor((left - padX) * perX).toInt().coerceIn(0, width),
        floor((top - padY) * perY).toInt().coerceIn(0, height),
        ceil((right + padX) * perX).toInt().coerceIn(0, width),
        ceil((bottom + padY) * perY).toInt().coerceIn(0, height),
    )

    /** The colours of [box] on [bitmap], read before anything is painted: what it sits on, and its letters' (see [PageColors]). */
    fun colors(bitmap: Bitmap, box: Rect): Colors {
        // Only the box and a little around it, where the ring PageColors reads lies.
        val x0 = max(0, box.left - AROUND)
        val y0 = max(0, box.top - AROUND)
        val x1 = min(bitmap.width, box.right + AROUND)
        val y1 = min(bitmap.height, box.bottom + AROUND)
        if (x1 <= x0 || y1 <= y0) return Colors(PageColors.WHITE, BLACK)
        val w = x1 - x0
        val h = y1 - y0
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, x0, y0, w, h)
        val left = box.left - x0
        val top = box.top - y0
        val right = box.right - x0
        val bottom = box.bottom - y0
        val background = PageColors.background(pixels, w, h, left, top, right, bottom)
        return Colors(background, PageColors.ink(pixels, w, h, left, top, right, bottom, background))
    }

    /** More than the widest ring PageColors reads around a box, in pixels. */
    private const val AROUND = 8

    /** What a block sits on, and the colour of its letters. */
    class Colors(val background: Int, val ink: Int)

    /**
     * Paints each of [boxes] on [bitmap] in its colour, without smoothing, so no trace of the letters
     * under it is left, but not over [kept], what stays as it is on the page (a footnote's number, a
     * superscript, a paragraph left untranslated) where a box would reach over it.
     */
    fun paintOver(bitmap: Bitmap, boxes: List<Pair<Rect, Int>>, kept: List<Rect>) {
        if (boxes.isEmpty()) return
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = false
        }
        canvas.save()
        for (rect in kept) if (!rect.isEmpty && boxes.any { Rect.intersects(it.first, rect) }) canvas.clipOutRect(rect)
        for ((rect, color) in boxes) {
            paint.color = color
            canvas.drawRect(rect, paint)
        }
        canvas.restore()
    }
}
