package io.github.nath_goa.usugami.data.office

import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ScaleXSpan
import androidx.core.graphics.withClip
import androidx.core.graphics.withRotation
import io.github.nath_goa.usugami.core.CellText
import io.github.nath_goa.usugami.core.SheetLocale
import io.github.nath_goa.usugami.core.SheetUnits
import io.github.nath_goa.usugami.core.SpreadsheetNumbers
import java.text.DateFormat
import java.text.DateFormatSymbols
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * What a cell shows: its [text] (with each run's look attached, for rich text), its cell's [font]
 * and the [color] to draw in, and how it lines up across the cell, General resolved.
 */
internal class ShownText(
    val text: CharSequence,
    val font: SheetFont,
    val color: Int,
    val align: SheetAlign,
    /** A number, date or time: it never runs on into the next cells, and shows as #### when too wide. */
    val number: Boolean,
    val rich: Boolean,
)

/**
 * The text of a sheet's cells: what each shows, how much room it takes, and drawing it. The phone's
 * fonts stand in for the workbook's, drawn narrower or wider to the width of the font they stand in
 * for (see [SheetUnits.digitEm]), so text fits its cells, wraps and runs on into the next ones about
 * where it does in Excel. Lengths are in points; the canvas draws in layout units ([U] per point).
 */
internal class CellTexts(
    private val styles: SheetStyles,
    private val theme: OfficeTheme,
    private val numbers: CellNumbers,
    private val date1904: Boolean,
) {
    private val paints = HashMap<SheetFont, TextPaint>()
    private val phoneDigits = HashMap<Typeface, Float>()

    /** One step of a cell's indent: three spaces of the default font. */
    val indentStep: Float = 7.5f * styles.defaultFont.size / 11f

    /** [font]'s paint, at its size in layout units, at the width of the Office font. Shared: never changed. */
    fun paint(font: SheetFont): TextPaint = paints.getOrPut(font) {
        font.style.paint().also { it.textScaleX = widthScale(font, it.typeface) }
    }

    private fun widthScale(font: SheetFont, typeface: Typeface): Float {
        val office = SheetUnits.digitEm(font.name) ?: return 1f
        val phone = phoneDigits.getOrPut(typeface) {
            TextPaint().apply { this.typeface = typeface; textSize = 100f }.measureText("0") / 100f
        }
        return if (phone > 0f) (office.toFloat() / phone).coerceIn(0.75f, 1.25f) else 1f
    }

    /** How tall a line of [font] is, in points, as Excel fits rows to it: 15 points for Calibri 11. */
    fun lineHeight(font: SheetFont): Float = font.size * lineFactor(font)

    private fun lineFactor(font: SheetFont) = OfficeFonts.officeLineHeight(font.name) + 0.14f

    /**
     * What [cell] shows in [format], or null when it holds nothing. A number is written as its
     * format says, with no more characters than fit [width] points when its format is General.
     */
    fun shown(cell: SheetCell, format: CellFormat, width: Float): ShownText? {
        val kind = cell.kind ?: return null
        val font = format.font
        return when (kind) {
            CellKind.NUMBER -> {
                val digit = paint(font).measureText("0") / U
                val room = width - 2 * PAD - format.indent * indentStep
                val maxChars = if (digit > 0f) floor(room / digit).toInt().coerceIn(1, 11) else 11
                val (text, color) = numbers.format(cell.number, format.numberFormat, date1904, maxChars)
                ShownText(text, font, color ?: font.color, resolve(format.horizontal, SheetAlign.RIGHT), number = true, rich = false)
            }
            CellKind.TEXT, CellKind.FORMULA -> {
                val value = cell.text ?: return null
                val runs = value.runs
                if (runs == null || kind == CellKind.FORMULA) {
                    val (text, color) = numbers.formatText(value.text, format.numberFormat)
                    ShownText(lines(text, format.wraps), font, color ?: font.color, resolve(format.horizontal, SheetAlign.LEFT), number = false, rich = false)
                } else {
                    ShownText(richText(value.text, runs, font, format.wraps), font, font.color, resolve(format.horizontal, SheetAlign.LEFT), number = false, rich = true)
                }
            }
            CellKind.BOOLEAN ->
                ShownText(if (cell.number != 0.0) "TRUE" else "FALSE", font, font.color, resolve(format.horizontal, SheetAlign.CENTER), number = false, rich = false)
            CellKind.ERROR ->
                ShownText(cell.text?.text.orEmpty(), font, font.color, resolve(format.horizontal, SheetAlign.CENTER), number = false, rich = false)
        }
    }

    private fun resolve(align: SheetAlign, general: SheetAlign) = if (align == SheetAlign.GENERAL) general else align

    /**
     * [text] with its line breaks as a cell shows them: only wrapped text goes on several lines; in
     * other cells a line break shows as a space. Tabs show as spaces too. The length stays the same,
     * so rich text's runs still fit it.
     */
    private fun lines(text: String, wraps: Boolean): String {
        if (text.none { it == '\n' || it == '\r' || it == '\t' }) return text
        val out = StringBuilder(text.length)
        for ((i, c) in text.withIndex()) {
            out.append(
                when (c) {
                    '\r' -> if (i + 1 < text.length && text[i + 1] == '\n') ' ' else if (wraps) '\n' else ' '
                    '\n' -> if (wraps) '\n' else ' '
                    '\t' -> ' '
                    else -> c
                },
            )
        }
        return out.toString()
    }

    /** Rich text: each run in its own font over the cell's, the text between runs in the cell's. */
    private fun richText(text: String, runs: List<TextRun>, cellFont: SheetFont, wraps: Boolean): CharSequence {
        val shown = lines(text, wraps)
        val rich = RichText()
        var at = 0
        fun piece(end: Int, font: SheetFont) {
            if (end <= at) return
            val start = rich.text.length
            rich.append(shown.substring(at, end), font.style)
            rich.text.setSpan(ScaleXSpan(paint(font).textScaleX), start, rich.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            at = end
        }
        for (run in runs.sortedBy { it.start }) {
            val start = run.start.coerceIn(at, shown.length)
            piece(start, cellFont)
            piece(run.end.coerceIn(at, shown.length), run.font.over(cellFont, styles.palette, theme))
        }
        piece(shown.length, cellFont)
        return rich.text
    }

    /** How wide [shown] is on one line, in points. Rich text's runs carry their own width (see [richText]). */
    fun width(shown: ShownText): Float =
        if (shown.rich) Layout.getDesiredWidth(shown.text, TextPaint().also { shown.font.style.applyTo(it) }) / U
        else paint(shown.font).measureText(shown.text.toString()) / U

    /** How tall [shown] is in a cell [boxWidth] points wide, wrapped as [format] wraps it, in points: what a row grows to fit. */
    fun height(shown: ShownText, format: CellFormat, boxWidth: Float): Float = when {
        format.wraps || format.rotation == STACKED -> paragraph(shown, format, room(shown, format, boxWidth)).height / U
        shown.rich -> paragraph(shown, format, width(shown) + 1f).height / U
        else -> lineHeight(shown.font)
    }

    /** How wide the text of a cell [boxWidth] points wide can be: its width less the padding and indent. */
    private fun room(shown: ShownText, format: CellFormat, boxWidth: Float): Float {
        val indent = if (shown.align == SheetAlign.LEFT || shown.align == SheetAlign.RIGHT) format.indent * indentStep else 0f
        return max(1f, boxWidth - 2 * PAD - indent)
    }

    /** [shown] as a paragraph [width] points wide, its lines as tall as in Excel. */
    private fun paragraph(shown: ShownText, format: CellFormat, width: Float): ParagraphBlock {
        val style = shown.font.style.copy(color = shown.color)
        val text = if (shown.rich) shown.text else RichText().also { rich ->
            val content = if (format.rotation == STACKED) shown.text.toString().toCharArray().joinToString("\n") else shown.text.toString()
            rich.append(content, style)
            rich.text.setSpan(ScaleXSpan(paint(shown.font).textScaleX), 0, rich.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }.text
        val align = when {
            format.rotation == STACKED -> Align.CENTER
            shown.align == SheetAlign.CENTER || shown.align == SheetAlign.CENTER_ACROSS -> Align.CENTER
            shown.align == SheetAlign.RIGHT -> Align.END
            shown.align == SheetAlign.JUSTIFY || shown.align == SheetAlign.DISTRIBUTED -> Align.JUSTIFY
            else -> Align.START
        }
        return ParagraphBlock(
            ParagraphSpec(
                text = text,
                base = style,
                width = max(1, (width * U).toInt()),
                align = align,
                lineScale = OfficeFonts.lineScale(style, lineFactor(shown.font)),
                avoidSingleLines = false,
            ),
        )
    }

    /**
     * Draws [shown] in [box] (the cell, the merged cells or the cells its text runs on into), lined up
     * as [format] says, and cut off at [clip]. Points, on a canvas in layout units.
     */
    fun draw(canvas: Canvas, shown: ShownText, format: CellFormat, box: RectF, clip: RectF) {
        if (shown.text.isEmpty()) return
        val visible = RectF(box)
        if (!visible.intersect(clip)) return
        val indent = format.indent * indentStep
        val left = box.left + PAD + if (shown.align == SheetAlign.LEFT) indent else 0f
        val right = box.right - PAD - if (shown.align == SheetAlign.RIGHT) indent else 0f
        when {
            format.rotation == STACKED || format.wraps -> {
                val block = paragraph(shown, format, room(shown, format, box.width()))
                val top = top(format.vertical, box, block.height / U)
                drawIn(canvas, visible, RectF(left, top, left + block.spec.width / U, top + block.height / U)) { block.draw(this, left * U, top * U) }
            }
            format.rotation in 1..180 -> drawIn(canvas, visible, null) { drawTurned(this, shown, format, box) }
            else -> drawLine(canvas, shown, format, box, left, right, visible)
        }
    }

    /**
     * Runs [draw], cut off at [visible] unless what it draws ([bounds], null when not known) is
     * inside it already. Most cells' text is, and cutting off each would make the PDF much larger and
     * slower to make and to open.
     */
    private inline fun drawIn(canvas: Canvas, visible: RectF, bounds: RectF?, crossinline draw: Canvas.() -> Unit) {
        val inside = bounds != null && bounds.left >= visible.left - SLACK && bounds.right <= visible.right + SLACK &&
            bounds.top >= visible.top - SLACK && bounds.bottom <= visible.bottom + SLACK
        if (inside) canvas.draw() else canvas.withClip(visible.left * U, visible.top * U, visible.right * U, visible.bottom * U) { draw() }
    }

    /** Where text [height] points tall starts down [box], as [vertical] lines it up. */
    private fun top(vertical: SheetVAlign, box: RectF, height: Float): Float = when (vertical) {
        SheetVAlign.TOP, SheetVAlign.JUSTIFY, SheetVAlign.DISTRIBUTED -> box.top
        SheetVAlign.CENTER -> (box.top + box.bottom - height) / 2
        SheetVAlign.BOTTOM -> box.bottom - height - BOTTOM_PAD
    }

    /** Text on one line, which may run on past its cell (the box then holds the cells it runs into). */
    private fun drawLine(canvas: Canvas, shown: ShownText, format: CellFormat, box: RectF, left: Float, right: Float, visible: RectF) {
        val room = right - left
        var text = shown.text
        var width = width(shown)
        var paint = paint(shown.font)
        // The phone's fonts are near the workbook's widths but not on them: a number a little wider
        // than the room inside the cell's padding still shows, using the padding.
        val squeezed = shown.number && width > room && width <= box.width() - NUMBER_SLACK
        if (shown.number && width > room && !squeezed) {
            // A number that doesn't fit shows as #, as many as fit, as in Excel.
            val hash = paint.measureText("#") / U
            text = "#".repeat(max(1, floor(room / hash).toInt()))
            width = paint.measureText(text.toString()) / U
        } else if (format.horizontal == SheetAlign.FILL && width > 0f && !shown.rich) {
            // Fill repeats the text across the cell, as many whole times as fit.
            val times = max(1, floor(room / width).toInt())
            text = text.toString().repeat(min(times, MAX_REPEATS))
            width *= min(times, MAX_REPEATS)
        } else if (format.shrink && width > room && room > 0f) {
            paint = TextPaint(paint).apply { textSize *= max(0.1f, room / width) }
            width = room
        }
        if (shown.color != shown.font.color && !shown.rich) paint = TextPaint(paint).apply { color = shown.color }
        val x = when {
            squeezed -> box.left + (box.width() - width) / 2
            shown.align == SheetAlign.RIGHT -> right - width
            shown.align == SheetAlign.CENTER || shown.align == SheetAlign.CENTER_ACROSS -> (box.left + box.right - width) / 2
            else -> left
        }
        if (shown.rich) {
            val block = paragraph(ShownText(text, shown.font, shown.color, SheetAlign.LEFT, shown.number, rich = true), format, ceil(width) + 1f)
            val top = top(format.vertical, box, block.height / U)
            drawIn(canvas, visible, RectF(x, top, x + width, top + block.height / U)) { block.draw(this, x * U, top * U) }
            return
        }
        val height = lineHeight(shown.font)
        val metrics = paint.fontMetrics
        val baseline = top(format.vertical, box, height) + height - metrics.descent / U
        val bounds = RectF(x, baseline + metrics.ascent / U, x + width, baseline + metrics.descent / U)
        drawIn(canvas, visible, bounds) { drawText(text.toString(), x * U, baseline * U, paint) }
    }

    /** Text turned up or down by [CellFormat.rotation], drawn about the middle of its cell. */
    private fun drawTurned(canvas: Canvas, shown: ShownText, format: CellFormat, box: RectF) {
        val degrees = if (format.rotation <= 90) -format.rotation.toFloat() else (format.rotation - 90).toFloat()
        var paint = paint(shown.font)
        if (shown.color != shown.font.color) paint = TextPaint(paint).apply { color = shown.color }
        val text = shown.text.toString()
        val width = paint.measureText(text)
        val metrics = paint.fontMetrics
        val cx = box.centerX() * U
        val cy = box.centerY() * U
        canvas.withRotation(degrees, cx, cy) {
            drawText(text, cx - width / 2, cy - (metrics.ascent + metrics.descent) / 2, paint)
        }
    }

    companion object {
        /** The room left and right of a cell's text, as Excel leaves it: 2 pixels. */
        const val PAD = 1.5f

        /** Text on the bottom line of a cell stays a pixel above it. */
        private const val BOTTOM_PAD = 0.75f

        /** textRotation for letters stacked one under another. */
        const val STACKED = 255

        /** A number shows if it is this much narrower than its cell, in points, even if it takes some of the padding. */
        private const val NUMBER_SLACK = 1f

        /** How far, in points, text can reach past its box without being cut off: less than a pixel. */
        private const val SLACK = 0.5f

        /** Fill alignment repeats text across a cell: no cell is wider than this many repeats of a letter. */
        private const val MAX_REPEATS = 2000
    }
}

/**
 * Numbers as their cells' formats show them (see [SpreadsheetNumbers]), written the way [locale]
 * writes them, as Excel writes them in the language of the computer it runs on: its decimal point,
 * digit grouping, and month and day names. The short date (built-in format 14) is [locale]'s too.
 */
internal class CellNumbers(locale: Locale) {
    private val sheetLocale = sheetLocale(locale)
    private val shortDate = shortDate(locale)

    /** The format code of built-in number format [id]: General for one that doesn't exist. */
    fun builtIn(id: Int): String = when (id) {
        14 -> shortDate
        22 -> "$shortDate h:mm"
        else -> SpreadsheetNumbers.builtInFormat(id) ?: "General"
    }

    /** [value] in format [code], General in at most [maxChars] characters, with the colour the format asks for. */
    fun format(value: Double, code: String, date1904: Boolean, maxChars: Int): CellText =
        SpreadsheetNumbers.format(value, code, sheetLocale, date1904, maxChars)

    /** [text] in the text section of format [code], with the colour it asks for. */
    fun formatText(text: String, code: String): CellText = SpreadsheetNumbers.formatText(text, code)

    private companion object {
        fun sheetLocale(locale: Locale): SheetLocale {
            val numbers = DecimalFormatSymbols.getInstance(locale)
            val dates = DateFormatSymbols.getInstance(locale)
            return SheetLocale(
                decimal = numbers.decimalSeparator,
                grouping = numbers.groupingSeparator,
                months = dates.months.take(12),
                shortMonths = dates.shortMonths.take(12),
                // Java counts weekdays from 1, Sunday first.
                days = dates.weekdays.drop(1).take(7),
                shortDays = dates.shortWeekdays.drop(1).take(7),
                am = dates.amPmStrings.getOrNull(0) ?: SheetLocale.ENGLISH.am,
                pm = dates.amPmStrings.getOrNull(1) ?: SheetLocale.ENGLISH.pm,
            )
        }

        /**
         * [locale]'s short date as an Excel format code, with the year in full, as Windows writes
         * it: "m/d/yyyy" in the United States, "dd/mm/yyyy" in Britain, "dd.mm.yyyy" in Germany.
         */
        fun shortDate(locale: Locale): String {
            val pattern = (DateFormat.getDateInstance(DateFormat.SHORT, locale) as? SimpleDateFormat)?.toPattern() ?: return US_DATE
            val code = StringBuilder()
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                var run = 1
                while (i + run < pattern.length && pattern[i + run] == c) run++
                when {
                    c == 'd' -> code.append("d".repeat(min(run, 2)))
                    c == 'M' || c == 'L' -> code.append("m".repeat(min(run, 2)))
                    c == 'y' -> code.append("yyyy")
                    c == '\'' -> {
                        // Quoted text, such as the letter after a year in some languages.
                        val end = pattern.indexOf('\'', i + 1).let { if (it < 0) pattern.length else it }
                        if (end > i + 1) code.append('"').append(pattern, i + 1, end).append('"')
                        run = end - i + 1
                    }
                    c.isLetter() -> Unit
                    c in "/-. ,:" -> code.append(c.toString().repeat(run))
                    else -> code.append('"').append(c.toString().repeat(run)).append('"')
                }
                i += run
            }
            val text = code.toString().trim()
            return if ('d' in text && 'm' in text && 'y' in text) text else US_DATE
        }

        const val US_DATE = "m/d/yyyy"
    }
}
