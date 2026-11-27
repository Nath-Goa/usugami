package io.github.nath_goa.usugami.data

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.LocaleList
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.graphics.withTranslation
import io.github.nath_goa.usugami.core.BlockAlign
import io.github.nath_goa.usugami.core.Fit
import io.github.nath_goa.usugami.core.PageColors
import io.github.nath_goa.usugami.core.TextMeasurer
import kotlin.math.abs
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

    /**
     * How a translation is drawn: sans-serif, bold for a bold paragraph, [size] points (at [SCALE]),
     * in [color], with the letters' shapes of [language] when it is given.
     */
    fun paint(size: Float, bold: Boolean, color: Int, language: Language? = null): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
            typeface = if (bold) this@TranslationOverlay.bold else Typeface.SANS_SERIF
            textSize = size * SCALE
            this.color = color
            if (language != null) textLocales = language.locales
        }

    /** [width] points as layout pixels, rounded up: the same for measuring and drawing. */
    fun layoutWidth(width: Float): Int = ceil(width * SCALE - 0.001f).toInt().coerceAtLeast(1)

    /**
     * The language a translation is written in, by its tag (see [TranslationEngine]): whether it is
     * written right to left, and the shapes its letters take where languages share them (Japanese
     * kanji aren't drawn as Chinese ones, nor the other way round, whatever the phone's language).
     */
    class Language(tag: String) {
        val rightToLeft: Boolean = tag in RIGHT_TO_LEFT

        /** ML Kit's Chinese is simplified Chinese: said so, the phone needn't guess its script. */
        val locales: LocaleList = LocaleList.forLanguageTags(if (tag == "zh") "zh-Hans" else tag)
    }

    /** Arabic, Persian, Hebrew and Urdu, as ML Kit names them. */
    private val RIGHT_TO_LEFT = setOf("ar", "fa", "he", "ur")

    /**
     * Whether [text], a translation into [language], is written right to left: in a language written
     * so, when it has a letter of a script written so, however many Latin letters (a name, a brand)
     * it starts with or has; in another, when most of its letters are.
     */
    fun rightToLeft(text: String, language: Language): Boolean {
        if (!language.rightToLeft) return PageLineReader.rightToLeft(text)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            val direction = Character.getDirectionality(cp)
            if (direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return true
        }
        return false
    }

    /**
     * [text], a translation into [language], laid out with [paint] [width] points wide, in its
     * direction (see [rightToLeft]), without hyphenation, its lines as tall as the main font's (other
     * scripts' fonts on the phone are made to fit them), lined up as [align] says of its block,
     * which is written right to left when [rtl]: at the start of the translation's direction for
     * [BlockAlign.START], at its end for [BlockAlign.END]. A translation written the other way than
     * its block (French into Arabic) starts on its own reading side either way: a right-aligned date
     * stays at the right, and a paragraph lined up on the left of the page is read from its right.
     */
    @SuppressLint("WrongConstant")
    fun layout(text: String, paint: TextPaint, width: Float, align: BlockAlign, rtl: Boolean, language: Language): StaticLayout {
        val written = rightToLeft(text, language)
        val alignment = when {
            align == BlockAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            written != rtl -> Layout.Alignment.ALIGN_NORMAL
            align == BlockAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, layoutWidth(width))
            .setAlignment(alignment)
            .setTextDirection(if (written) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            // Layout's constants are LineBreaker's, which lint asks for but which only came in Android 10.
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
    }

    /** Measures text in [language] as [draw] draws it for a block that is [bold] and lined up as [align] says, in points. */
    fun measurer(bold: Boolean, align: BlockAlign, rtl: Boolean, language: Language): TextMeasurer {
        val paint = paint(1f, bold, BLACK, language)
        return TextMeasurer { text, size, width ->
            paint.textSize = size * SCALE
            layout(text, paint, width, align, rtl, language).height / SCALE
        }
    }

    /** Whether a font on this phone has every character of [text] that needs drawing, as [draw] draws it. */
    fun canDraw(text: String, bold: Boolean): Boolean = WatermarkText.canDraw(text, paint(12f, bold, BLACK))

    /**
     * Draws [text], in [language], on [canvas] (in points of the page) at [fit]'s place and size, in
     * [color], lined up as [align] says of its block, written right to left when [rtl].
     */
    fun draw(canvas: Canvas, text: String, fit: Fit, bold: Boolean, color: Int, align: BlockAlign, rtl: Boolean, language: Language) {
        val layout = layout(text, paint(fit.size, bold, color, language), fit.area.width, align, rtl, language)
        canvas.withTranslation(fit.area.left, fit.area.top) {
            scale(1 / SCALE, 1 / SCALE)
            layout.draw(this)
        }
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

    /**
     * The colours of [box] on [bitmap], read before anything is painted: what it sits on, and its
     * letters' (see [PageColors]), and whether what is around it is plain, leaving out the boxes of
     * the page's other lines, [text].
     */
    fun colors(bitmap: Bitmap, box: Rect, text: List<Rect> = emptyList()): Colors {
        // Only the box and a little around it, where the ring PageColors reads lies.
        val x0 = max(0, box.left - AROUND)
        val y0 = max(0, box.top - AROUND)
        val x1 = min(bitmap.width, box.right + AROUND)
        val y1 = min(bitmap.height, box.bottom + AROUND)
        if (x1 <= x0 || y1 <= y0) return Colors(PageColors.WHITE, BLACK, uniform = true)
        val w = x1 - x0
        val h = y1 - y0
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, x0, y0, w, h)
        val left = box.left - x0
        val top = box.top - y0
        val right = box.right - x0
        val bottom = box.bottom - y0
        val background = PageColors.background(pixels, w, h, left, top, right, bottom)
        val ink = PageColors.ink(pixels, w, h, left, top, right, bottom, background)
        // Whether what is around the box is plain, as paper or a band is, not a photo or a gradient:
        // a thin ring that is nearly all the background's colour, the same above as below and on
        // the left as on the right. Other lines' text ([text]'s boxes, and letters nearer the ink's
        // colour) doesn't count.
        val ringBox = Rect(box.left - RING, box.top - RING, box.right + RING, box.bottom + RING)
        val others = text.filter { Rect.intersects(it, ringBox) }
        val sides = Array(4) { LongArray(4) }
        var counted = 0
        var alike = 0
        for (y in max(0, top - RING) until min(h, bottom + RING)) for (x in max(0, left - RING) until min(w, right + RING)) {
            val side = when {
                y < top -> 0
                y >= bottom -> 1
                x < left -> 2
                x >= right -> 3
                else -> continue
            }
            if (others.any { it.contains(x + x0, y + y0) }) continue
            val p = pixels[y * w + x]
            val fromPaper = distance(p, background)
            if (distance(p, ink) < fromPaper) continue
            counted++
            if (fromPaper <= ALIKE_DISTANCE) alike++
            sides[side][0] += (p shr 16 and 0xFF).toLong()
            sides[side][1] += (p shr 8 and 0xFF).toLong()
            sides[side][2] += (p and 0xFF).toLong()
            sides[side][3]++
        }
        fun differ(a: LongArray, b: LongArray) = a[3] > 0 && b[3] > 0 && (0..2).any { abs(a[it] / a[3] - b[it] / b[3]) > SIDES_DIFFER }
        val uniform = counted == 0 || alike >= UNIFORM_SHARE * counted && !differ(sides[0], sides[1]) && !differ(sides[2], sides[3])
        return Colors(background, ink, uniform)
    }

    /** More than the widest ring PageColors reads around a box, in pixels. */
    private const val AROUND = 8

    /** The ring that tells a plain background from a varied one, in pixels… */
    private const val RING = 3

    /** …is plain when this share of it is within [ALIKE_DISTANCE] of the background's colour… */
    private const val UNIFORM_SHARE = 0.85f
    private const val ALIKE_DISTANCE = 30

    /** …and its sides' colours (above and below, left and right) are no further apart than this in any channel. */
    private const val SIDES_DIFFER = 5

    /**
     * What a block sits on, and the colour of its letters; [uniform] when what is around it is all
     * that one colour (paper, a band), not a photo or a gradient.
     */
    class Colors(val background: Int, val ink: Int, val uniform: Boolean)

    /** The distance between two colours in RGB, each channel 0 to 255. */
    private fun distance(a: Int, b: Int): Int {
        val dr = (a shr 16 and 0xFF) - (b shr 16 and 0xFF)
        val dg = (a shr 8 and 0xFF) - (b shr 8 and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble()).toInt()
    }

    /**
     * Paints the lines of translated paragraphs over on a page's picture, so no trace of their
     * letters is left, but never over [kept], what stays as it is on the page (a footnote's number, a
     * superscript, a paragraph left untranslated), where a line's box reaches over it. [text] is the
     * boxes of the page's lines, kept or to be painted: a varied background is never painted from
     * the colours inside one that is still there.
     */
    class Painter(private val bitmap: Bitmap, private val kept: List<Rect>, text: List<Rect> = emptyList()) {
        private val width = bitmap.width
        private val height = bitmap.height
        private val row = IntArray(max(width, height))

        /** The boxes of text still on the picture. */
        private val unpainted = ArrayList(text + kept)

        /**
         * [rect] grown over the strokes of its letters that reach past it: the box comes from the
         * font's measurements, and the picture's letters, drawn with a font of the renderer's when
         * the PDF has none of its own, may reach further (a descender, an accent). It grows a row
         * (or a column) at a time, while the next has some ink on [background] but isn't a rule (ink
         * along more than half of it), at most [down], [up] and [side] pixels; then a pixel more for
         * the letters' soft edge.
         */
        fun grown(rect: Rect, background: Int, up: Int, down: Int, side: Int): Rect {
            val r = Rect(rect)
            fun ink(x0: Int, y0: Int, x1: Int, y1: Int): Int {
                val n = (x1 - x0) * (y1 - y0)
                if (n <= 0) return 0
                bitmap.getPixels(row, 0, x1 - x0, x0, y0, x1 - x0, y1 - y0)
                var count = 0
                for (i in 0 until n) if (distance(row[i], background) > GROW_DISTANCE) count++
                return count
            }
            var grew = 0
            while (grew < down && r.bottom < height) {
                val count = ink(r.left, r.bottom, r.right, r.bottom + 1)
                if (count == 0 || count > RULE_SHARE * r.width()) break
                r.bottom++
                grew++
            }
            if (grew > 0 && r.bottom < height) r.bottom++
            grew = 0
            while (grew < up && r.top > 0) {
                val count = ink(r.left, r.top - 1, r.right, r.top)
                if (count == 0 || count > RULE_SHARE * r.width()) break
                r.top--
                grew++
            }
            if (grew > 0 && r.top > 0) r.top--
            grew = 0
            while (grew < side && r.left > 0) {
                val count = ink(r.left - 1, r.top, r.left, r.bottom)
                if (count == 0 || count > RULE_SHARE * r.height()) break
                r.left--
                grew++
            }
            if (grew > 0 && r.left > 0) r.left--
            grew = 0
            while (grew < side && r.right < width) {
                val count = ink(r.right, r.top, r.right + 1, r.bottom)
                if (count == 0 || count > RULE_SHARE * r.height()) break
                r.right++
                grew++
            }
            if (grew > 0 && r.right < width) r.right++
            return r
        }

        /**
         * Paints [rect], the box of the line [line] (one of [text]) grown over its letters, but not
         * where it overlaps [kept]: in [colors]' background where that is plain, else (a photo, a
         * gradient) each column from the colour just above the box to the colour just below it,
         * where there is no text still to be painted over; with only one of them, that one.
         */
        fun paint(rect: Rect, colors: Colors, line: Rect? = null) {
            val w = rect.width()
            val h = rect.height()
            if (line != null) unpainted.remove(line)
            if (w <= 0 || h <= 0) return
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, rect.left, rect.top, w, h)
            val keptHere = kept.filter { !it.isEmpty && Rect.intersects(it, rect) }
            var above = if (colors.uniform) null else edge(rect, above = true)
            var below = if (colors.uniform) null else edge(rect, above = false)
            if (above == null) above = below
            if (below == null) below = above
            for (y in 0 until h) {
                val py = rect.top + y
                val t = (y + 0.5f) / h
                for (x in 0 until w) {
                    val px = rect.left + x
                    if (keptHere.any { it.contains(px, py) }) continue
                    pixels[y * w + x] = if (above == null || below == null) colors.background else mix(above[x], below[x], t)
                }
            }
            bitmap.setPixels(pixels, 0, w, rect.left, rect.top, w, h)
        }

        /**
         * The colour just [above] (or below) each column of [rect]: the average of up to [EDGE]
         * rows there, outside the text still on the picture; where a column has none, its nearest
         * neighbour's; null where none has any.
         */
        private fun edge(rect: Rect, above: Boolean): IntArray? {
            val w = rect.width()
            val y0 = if (above) max(0, rect.top - EDGE) else min(height, rect.bottom)
            val y1 = if (above) rect.top else min(height, rect.bottom + EDGE)
            val strip = Rect(rect.left, y0, rect.right, y1)
            val inText = unpainted.filter { Rect.intersects(it, strip) }
            val r = LongArray(w)
            val g = LongArray(w)
            val b = LongArray(w)
            val n = IntArray(w)
            for (y in y0 until y1) {
                bitmap.getPixels(row, 0, w, rect.left, y, w, 1)
                for (x in 0 until w) {
                    if (inText.any { it.contains(rect.left + x, y) }) continue
                    val p = row[x]
                    r[x] += (p shr 16 and 0xFF).toLong()
                    g[x] += (p shr 8 and 0xFF).toLong()
                    b[x] += (p and 0xFF).toLong()
                    n[x]++
                }
            }
            if (n.all { it == 0 }) return null
            val out = IntArray(w) { x -> if (n[x] == 0) 0 else rgb((r[x] / n[x]).toInt(), (g[x] / n[x]).toInt(), (b[x] / n[x]).toInt()) }
            // A column without a colour of its own takes its nearest neighbour's.
            val before = IntArray(w)
            val after = IntArray(w)
            var last = -1
            for (x in 0 until w) {
                if (n[x] > 0) last = x
                before[x] = last
            }
            last = -1
            for (x in w - 1 downTo 0) {
                if (n[x] > 0) last = x
                after[x] = last
            }
            for (x in 0 until w) {
                if (n[x] > 0) continue
                val from = when {
                    before[x] < 0 -> after[x]
                    after[x] < 0 -> before[x]
                    x - before[x] <= after[x] - x -> before[x]
                    else -> after[x]
                }
                out[x] = out[from]
            }
            return smoothed(out)
        }

        /** [colors] with each the median of the [SMOOTH] around it, channel by channel: a letter's soft edge left in the row above doesn't streak down the box. */
        private fun smoothed(colors: IntArray): IntArray {
            val half = SMOOTH / 2
            val window = IntArray(SMOOTH)
            fun median(x: Int, shift: Int): Int {
                var n = 0
                for (i in max(0, x - half)..min(colors.size - 1, x + half)) window[n++] = colors[i] shr shift and 0xFF
                window.sort(0, n)
                return window[n / 2]
            }
            return IntArray(colors.size) { x -> rgb(median(x, 16), median(x, 8), median(x, 0)) }
        }

        private fun mix(a: Int, b: Int, t: Float): Int {
            fun channel(shift: Int) = ((a shr shift and 0xFF) * (1 - t) + (b shr shift and 0xFF) * t).toInt().coerceIn(0, 255)
            return rgb(channel(16), channel(8), channel(0))
        }

        private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** A pixel this far from the background is ink a painted box grows over… */
    private const val GROW_DISTANCE = 40

    /** …unless more than this share of the row (or column) is ink: a rule or a table's border. */
    private const val RULE_SHARE = 0.5f

    /** Rows above and below a box whose colours a varied background is painted from, smoothed across this many columns. */
    private const val EDGE = 3
    private const val SMOOTH = 7
}
