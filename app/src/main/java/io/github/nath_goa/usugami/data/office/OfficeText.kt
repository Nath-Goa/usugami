package io.github.nath_goa.usugami.data.office

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.TabStopSpan
import android.text.style.UnderlineSpan
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import io.github.nath_goa.usugami.core.PageBreaking
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Layout units per point. Android lays text out in whole units, and whole points would round
 * every line's height noticeably, so pages are drawn in tenths of a point.
 */
internal const val U = 10f

/** [this] many points, in layout units. */
internal val Float.units: Float get() = this * U

/**
 * The phone's fonts standing in for Office fonts. Phones don't have Calibri, Times New Roman and
 * the like, so each becomes the phone's sans-serif, serif or monospace font, in the same weight and
 * slant. Characters the font lacks (Hindi, for one) come from the phone's other fonts.
 */
internal object OfficeFonts {
    private val cache = HashMap<String, Typeface>()
    private val MONO = listOf("courier", "consolas", "lucida console", "monaco", "menlo", "mono", "source code", "inconsolata", "cascadia", "fira code")
    private val SERIF = listOf(
        "times", "cambria", "georgia", "garamond", "palatino", "book antiqua", "bookman", "century", "baskerville",
        "constantia", "bodoni", "didot", "minion", "merriweather", "lora", "serif", "perpetua", "goudy", "caslon",
        "charter", "rockwell", "sylfaen", "high tower", "californian", "cochin", "hoefler",
    )
    private val SCRIPT = listOf("script", "handwriting", "brush", "kunstler", "vivaldi", "chancery")

    fun typeface(name: String?, bold: Boolean, italic: Boolean): Typeface {
        val family = family(name, bold)
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return synchronized(cache) { cache.getOrPut("$family/$style") { Typeface.create(family, style) } }
    }

    /**
     * The height of a single-spaced line in the Office font [name], per point of font size, as
     * Word lays it out. Phone fonts have their own heights (the serif one is much taller than
     * Cambria), so lines are scaled to the Office font's, and pages break about where they do in Word.
     */
    fun officeLineHeight(name: String?): Float {
        val n = name.orEmpty().lowercase()
        return when {
            "calibri" in n || "verdana" in n || "aptos" in n -> 1.22f
            "segoe" in n -> 1.33f
            "tahoma" in n || "century gothic" in n -> 1.21f
            "times" in n || "arial" in n || "helvetica" in n || "liberation" in n -> 1.15f
            "georgia" in n || "courier" in n -> 1.14f
            "garamond" in n -> 1.12f
            else -> 1.17f
        }
    }

    private val phoneHeights = HashMap<Typeface, Float>()

    /** The height of a line in [typeface], per point of font size. */
    fun phoneLineHeight(typeface: Typeface): Float = synchronized(phoneHeights) {
        phoneHeights.getOrPut(typeface) {
            val metrics = TextPaint().apply {
                this.typeface = typeface
                textSize = 100f
            }.fontMetrics
            ((metrics.descent - metrics.ascent) / 100f).takeIf { it > 0.5f } ?: 1.17f
        }
    }

    /** How much to scale [style]'s lines so they're as tall as in Office, where single spacing is [officeHeight] per point. */
    fun lineScale(style: TextStyle, officeHeight: Float = officeLineHeight(style.font)): Float =
        (officeHeight / phoneLineHeight(typeface(style.font, style.bold, style.italic))).coerceIn(0.7f, 1.4f)

    /** The phone's font family closest to the Office font [name]. */
    fun family(name: String?, bold: Boolean = false): String {
        val n = name.orEmpty().lowercase()
        return when {
            MONO.any { it in n } -> "monospace"
            "sans" !in n && SERIF.any { it in n } -> "serif"
            SCRIPT.any { it in n } -> "cursive"
            "comic" in n -> "casual"
            !bold && ("light" in n || "thin" in n) -> "sans-serif-light"
            "narrow" in n || "condensed" in n -> "sans-serif-condensed"
            else -> "sans-serif"
        }
    }
}

/** How a run of text looks, with everything inherited already applied. Sizes in points. */
internal data class TextStyle(
    val font: String? = null,
    val size: Float = 11f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val color: Int = Color.BLACK,
    val background: Int? = null,
    /** 1 for superscript, -1 for subscript. */
    val baseline: Int = 0,
    val caps: Boolean = false,
    val smallCaps: Boolean = false,
    /** Extra space between letters, in points. */
    val spacing: Float = 0f,
) {
    /** The font size actually drawn: smaller for superscript and small capitals. */
    val drawnSize: Float get() = size * (if (baseline != 0) 0.65f else if (smallCaps) 0.8f else 1f)

    fun applyTo(paint: TextPaint) {
        paint.typeface = OfficeFonts.typeface(font, bold, italic)
        paint.textSize = max(1f, drawnSize.units)
        if (spacing != 0f && size > 0f) paint.letterSpacing = spacing / size
        when (baseline) {
            1 -> paint.baselineShift -= (size * 0.33f).units.roundToInt()
            -1 -> paint.baselineShift += (size * 0.14f).units.roundToInt()
        }
    }

    fun paint(): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).also {
        applyTo(it)
        it.color = color
        it.isUnderlineText = underline
        it.isStrikeThruText = strike
    }
}

/** Text with each run's look attached, ready for [ParagraphBlock]. */
internal class RichText {
    val text = SpannableStringBuilder()
    val isEmpty: Boolean get() = text.isEmpty()

    /** Whether there's anything but spaces: an inline picture counts. */
    val hasContent: Boolean get() = text.any { !it.isWhitespace() }

    fun append(s: String, style: TextStyle) {
        if (s.isEmpty()) return
        val shown = if (style.caps || style.smallCaps) s.uppercase() else s
        val start = text.length
        text.append(shown)
        style(start, text.length, style)
    }

    /** A picture (or any other object) drawn inline, as one character. */
    fun appendObject(span: ReplacementSpan, style: TextStyle) {
        val start = text.length
        text.append(OBJECT)
        text.setSpan(span, start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        style(start, text.length, style)
    }

    private fun style(start: Int, end: Int, style: TextStyle) {
        fun span(what: Any) = text.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span(RunSpan(style))
        span(ForegroundColorSpan(style.color))
        style.background?.let { span(BackgroundColorSpan(it)) }
        if (style.underline) span(UnderlineSpan())
        if (style.strike) span(StrikethroughSpan())
    }

    companion object {
        const val OBJECT = "\uFFFC"
    }
}

/** A run's font and size. Colours and lines are separate spans. */
private class RunSpan(private val style: TextStyle) : MetricAffectingSpan() {
    override fun updateMeasureState(paint: TextPaint) = style.applyTo(paint)
    override fun updateDrawState(paint: TextPaint) = style.applyTo(paint)
}

internal enum class Align { START, CENTER, END, JUSTIFY }

/** How far apart a paragraph's lines are. */
internal sealed class LineSpacing {
    /** A multiple of the font's own line height (1 is single spacing). */
    data class Multiple(val factor: Float) : LineSpacing()

    /** Exactly this many points from line to line. */
    data class Exact(val points: Float) : LineSpacing()

    /** The font's own line height, but at least this many points. */
    data class AtLeast(val points: Float) : LineSpacing()

    companion object {
        val SINGLE = Multiple(1f)
    }
}

/** A line along a paragraph's top or bottom: [width] and [gap] (from the text) in units. */
internal class Rule(val width: Float, val color: Int, val gap: Float)

/** A list number or bullet, drawn before a paragraph's first line, [x] units from its left edge. */
internal class Label(val text: String, val style: TextStyle, val x: Int)

/** Everything about a paragraph its layout needs. Lengths in layout units. */
internal class ParagraphSpec(
    val text: CharSequence,
    /** For an empty paragraph's height: the paragraph mark's look. */
    val base: TextStyle,
    val width: Int,
    val align: Align = Align.START,
    /** From the paragraph's left edge to its first line, and to its other lines. */
    val firstIndent: Int = 0,
    val indent: Int = 0,
    val rightIndent: Int = 0,
    val spacing: LineSpacing = LineSpacing.SINGLE,
    /** Multiple line spacing is scaled by this, for the phone font's height (see [OfficeFonts.lineScale]). */
    val lineScale: Float = 1f,
    val label: Label? = null,
    val rtl: Boolean = false,
    val background: Int? = null,
    /** Tab stops, in units from the paragraph's left edge; between and beyond them, every [defaultTab]. */
    val tabs: List<Int> = emptyList(),
    val defaultTab: Int = 36f.units.toInt(),
    /** Keeps a lone first or last line from being stranded at a page break. */
    val avoidSingleLines: Boolean = true,
    /** Lines above and below the paragraph, drawn in the space before and after it. */
    val ruleAbove: Rule? = null,
    val ruleBelow: Rule? = null,
)

/** Something laid out to a width, which can be drawn anywhere and split between pages. Units throughout. */
internal interface Block {
    val height: Int

    fun draw(canvas: Canvas, x: Float, y: Float)

    /**
     * The part of this block that fits in [space] and the rest (null when it all fits), or null when
     * nothing fits. With [force], something always fits.
     */
    fun split(space: Int, force: Boolean): Pair<Block, Block?>?
}

/** A paragraph of text, laid out with Android's own line breaking, so every script is shaped right. */
internal class ParagraphBlock private constructor(
    val spec: ParagraphSpec,
    private val text: CharSequence,
    private val first: Int,
    private val label: Label?,
    /** A justified paragraph's part before a page break: its last line is justified too. */
    private val continues: Boolean,
    /** Whether this part has the paragraph's first line, and its last. */
    private val isFirst: Boolean,
    private val isLast: Boolean,
) : Block {
    constructor(spec: ParagraphSpec) : this(spec, spec.text, spec.firstIndent, spec.label, false, true, true)

    private val left = min(first, spec.indent)
    val layout: StaticLayout = build()
    private val lines = if (continues) layout.lineCount - 1 else layout.lineCount
    override val height: Int = if (lines > 0) layout.getLineBottom(lines - 1) else 0

    /** The baseline of the first line, from the top. */
    val firstBaseline: Int get() = layout.getLineBaseline(0)

    /** The widest line, in units: for text that isn't wrapped. */
    val contentWidth: Float get() = (0 until lines).maxOfOrNull { layout.getLineWidth(it) } ?: 0f

    @SuppressLint("WrongConstant")
    private fun build(): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).also { spec.base.applyTo(it) }
        val content = SpannableStringBuilder(if (text.isEmpty()) " " else text)
        if (text.isEmpty()) content.setSpan(RunSpan(spec.base), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (continues) {
            content.append(RichText.OBJECT)
            content.setSpan(LineFiller(spec.width), content.length - 1, content.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        content.setSpan(LineRule(spec.spacing, spec.lineScale), 0, content.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        if ('\t' in content) {
            val stops = spec.tabs.map { it - left }.filter { it > 0 }
            for (stop in stops) content.setSpan(TabStopSpan.Standard(stop), 0, content.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            // Android repeats the last stop's spacing past the stops given; Office's default stops do the same.
            var next = ((stops.maxOrNull() ?: 0) / spec.defaultTab + 1) * spec.defaultTab
            repeat(64) {
                content.setSpan(TabStopSpan.Standard(next), 0, content.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                next += spec.defaultTab
            }
        }
        val width = max(1, spec.width - left - spec.rightIndent)
        val builder = StaticLayout.Builder.obtain(content, 0, content.length, paint, width)
            .setAlignment(
                when (spec.align) {
                    Align.CENTER -> Layout.Alignment.ALIGN_CENTER
                    Align.END -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_NORMAL
                },
            )
            .setTextDirection(if (spec.rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            // Word fills each line before starting the next, and doesn't hyphenate. (Layout's
            // constants are LineBreaker's, which lint asks for but which only came in Android 10.)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setIndents(intArrayOf(max(0, first - left), max(0, spec.indent - left)), null)
        if (spec.align == Align.JUSTIFY) builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        // Lines of Hindi and other scripts from the phone's other fonts get room for them.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setUseLineSpacingFromFallbacks(true)
        return builder.build()
    }

    override fun draw(canvas: Canvas, x: Float, y: Float) {
        spec.background?.let {
            val fill = Paint().apply { color = it }
            canvas.drawRect(x + left, y, x + spec.width - spec.rightIndent, y + height, fill)
        }
        label?.let {
            val paint = it.style.paint()
            canvas.drawText(it.text, x + it.x, y + firstBaseline, paint)
        }
        if (isFirst) spec.ruleAbove?.let { rule(canvas, it, x, y - it.gap - it.width / 2) }
        if (isLast) spec.ruleBelow?.let { rule(canvas, it, x, y + height + it.gap + it.width / 2) }
        canvas.withTranslation(x + left, y) { layout.draw(this) }
    }

    private fun rule(canvas: Canvas, rule: Rule, x: Float, y: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = rule.color
            strokeWidth = rule.width
        }
        canvas.drawLine(x + min(spec.firstIndent, spec.indent), y, x + spec.width - spec.rightIndent, y, paint)
    }

    override fun split(space: Int, force: Boolean): Pair<Block, Block?>? {
        val bottoms = IntArray(lines) { layout.getLineBottom(it) }
        val fit = PageBreaking.linesThatFit(bottoms, space, spec.avoidSingleLines, force)
        if (fit == 0) return null
        if (fit >= lines) return this to null
        // The lines are laid out again on each side of the break. Lines fill greedily, so they break
        // just where they did.
        val cut = layout.getLineStart(fit)
        val head = ParagraphBlock(spec, text.subSequence(0, cut), first, label, spec.align == Align.JUSTIFY, isFirst, false)
        val tail = ParagraphBlock(spec, text.subSequence(cut, text.length), spec.indent, null, continues, false, isLast)
        return head to tail
    }
}

/** Sets each line's height as the paragraph's line spacing says. The extra goes above the text, as in Word. */
private class LineRule(private val spacing: LineSpacing, private val scale: Float) : LineHeightSpan {
    private var setAscent = Int.MIN_VALUE
    private var setDescent = Int.MIN_VALUE
    private var naturalAscent = 0
    private var naturalDescent = 0

    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
        // Within one run of text, Android hands each line the metrics set here for the line before
        // (they would add up line after line); that line's own metrics were saved then.
        if (fm.ascent == setAscent && fm.descent == setDescent) {
            fm.ascent = naturalAscent
            fm.descent = naturalDescent
        } else {
            naturalAscent = fm.ascent
            naturalDescent = fm.descent
        }
        try {
            adjust(text, start, end, fm)
        } finally {
            setAscent = fm.ascent
            setDescent = fm.descent
        }
    }

    private fun adjust(text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt) {
        val natural = fm.descent - fm.ascent
        if (natural <= 0) return
        // A picture in the line keeps all its height.
        val picture = text is Spanned && text.getSpans(start, end, PictureSpan::class.java).isNotEmpty()
        val target = when (spacing) {
            is LineSpacing.Multiple -> (natural * (spacing.factor * scale).let { if (picture) max(1f, it) else it }).roundToInt()
            is LineSpacing.Exact -> spacing.points.units.roundToInt()
            is LineSpacing.AtLeast -> max(natural, spacing.points.units.roundToInt())
        }
        fm.ascent -= target - natural
        fm.top = fm.ascent
        fm.bottom = fm.descent
    }
}

/** A full-width, invisible object: put after a justified paragraph's last line, it gets justified too. */
private class LineFiller(private val width: Int) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        fm?.apply { ascent = 0; descent = 0; top = 0; bottom = 0; leading = 0 }
        return width
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) = Unit
}

/** Something drawn in a rectangle: a picture, or a stand-in for content Usugami can't draw. */
internal fun interface Picture {
    fun draw(canvas: Canvas, rect: RectF)
}

/** [bitmap], or the part of it in [crop], stretched over the rectangle. */
internal class BitmapPicture(private val bitmap: Bitmap, private val crop: Rect? = null) : Picture {
    override fun draw(canvas: Canvas, rect: RectF) {
        canvas.drawBitmap(bitmap, crop, rect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }
}

/** A light grey box, with [caption] in it when there is one: for charts and the like. */
internal class StandInPicture(private val caption: String?) : Picture {
    override fun draw(canvas: Canvas, rect: RectF) {
        canvas.drawRect(rect, Paint().apply { color = 0xFFF1F1F1.toInt() })
        canvas.drawRect(rect, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFC8C8C8.toInt(); strokeWidth = 0.75f.units })
        val text = caption?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val size = minOf(10f.units, rect.height() / 3, rect.width() / 6)
        if (size < 3f.units) return
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            color = 0xFF757575.toInt()
            typeface = OfficeFonts.typeface(null, bold = false, italic = false)
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, (rect.width() * 0.9f).toInt()))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(3)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()
        canvas.withClip(rect) {
            translate(rect.left + rect.width() * 0.05f, rect.centerY() - layout.height / 2f)
            layout.draw(this)
        }
    }
}

/** A picture inside a line of text, sitting on the baseline like a large letter. */
internal class PictureSpan(private val picture: Picture, private val width: Int, private val height: Int) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            paint.getFontMetricsInt(fm)
            fm.ascent = min(fm.ascent, -height)
            fm.top = fm.ascent
        }
        return width
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        picture.draw(canvas, RectF(x, (y - height).toFloat(), x + width, y.toFloat()))
    }
}

/** Blocks one under another, with space between them: a table cell's content, for one. */
internal class StackBlock(private val items: List<Spaced>) : Block {
    override val height: Int = items.sumOf { it.before + it.block.height + it.after }

    override fun draw(canvas: Canvas, x: Float, y: Float) {
        var top = y
        for (item in items) {
            top += item.before
            item.block.draw(canvas, x, top)
            top += item.block.height + item.after
        }
    }

    override fun split(space: Int, force: Boolean): Pair<Block, Block?>? {
        var used = 0
        for ((index, item) in items.withIndex()) {
            val end = used + item.before + item.block.height + item.after
            if (end <= space) {
                used = end
                continue
            }
            val room = space - used - item.before
            val parts = if (room > 0 || (force && index == 0)) item.block.split(room, force && index == 0) else null
            val head = items.take(index) + listOfNotNull(parts?.let { Spaced(it.first, item.before, 0) })
            val tail = listOfNotNull(parts?.second?.let { Spaced(it, 0, item.after) } ?: item.takeIf { parts == null }) + items.drop(index + 1)
            if (head.isEmpty()) return null
            return StackBlock(head) to tail.takeIf { it.isNotEmpty() }?.let(::StackBlock)
        }
        return this to null
    }
}

/** A block with the space to leave above and below it. */
internal class Spaced(val block: Block, val before: Int, val after: Int)
