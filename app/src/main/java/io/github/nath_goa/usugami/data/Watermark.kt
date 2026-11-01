package io.github.nath_goa.usugami.data

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Watermark colours, as ARGB. */
enum class WatermarkColor(val argb: Int) {
    GRAY(0xFF757575.toInt()),
    BLACK(0xFF000000.toInt()),
    RED(0xFFD32F2F.toInt()),
    BLUE(0xFF1565C0.toInt()),
    GREEN(0xFF2E7D32.toInt()),
}

/** How much of the page shows through the watermark. */
enum class WatermarkOpacity(val alpha: Float) { TRANSLUCENT(0.3f), SEMI(0.6f), OPAQUE(1f) }

/** No font on this phone can draw some of the watermark's characters. */
class WatermarkFontMissing : IOException("No font on this phone can draw the watermark text")

/**
 * The watermark's text, drawn as a one-page PDF just big enough for it.
 *
 * Android draws it, through the same PDF backend as [PdfDocument]: that shapes every script the
 * phone has fonts for (Hindi needs its vowel signs moved and letters joined, which PDFBox can't
 * do), picks the phone's font for each character, and embeds just the letters used. The text stays
 * real text in the PDF.
 */
object WatermarkText {
    /** Longer text would make the stamp page wider than PDF viewers accept. */
    const val MAX_LENGTH = 80

    /** Text size the stamp is drawn at, in points; it is scaled to fit each page. */
    private const val SIZE = 100f

    /** How the watermark is drawn. The preview on the screen draws with it too, so it matches. */
    fun paint(argb: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = SIZE
        color = argb
    }

    /** Where [paint] puts ink when it draws [text] at the origin: the stamp is just this big. */
    fun inkBounds(text: String, paint: Paint): Rect = Rect().also { paint.getTextBounds(text, 0, text.length, it) }

    /** Whether a font on this phone has every character of [text] that needs drawing with [paint]. */
    fun canDraw(text: String, paint: Paint = paint(0xFF000000.toInt())): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!needsGlyph(cp)) continue
            if (!paint.hasGlyph(String(Character.toChars(cp)))) return false
        }
        return true
    }

    /** Spaces, joiners and variation selectors change how text is drawn but have no glyph of their own. */
    private fun needsGlyph(cp: Int): Boolean {
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) return false
        if (cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF) return false
        return when (Character.getType(cp)) {
            Character.CONTROL.toInt(), Character.FORMAT.toInt() -> false
            else -> true
        }
    }

    /**
     * A one-page PDF with [text] in [argb], its page just around the ink. Throws
     * [WatermarkFontMissing] when [canDraw] is false, and [IllegalArgumentException] for blank text.
     */
    fun render(text: String, argb: Int): ByteArray {
        require(text.isNotBlank() && text.length <= MAX_LENGTH)
        if (!canDraw(text)) throw WatermarkFontMissing()
        val paint = paint(argb)
        val ink = inkBounds(text, paint)
        if (ink.isEmpty) throw WatermarkFontMissing()
        val pad = 2
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(ink.width() + 2 * pad, ink.height() + 2 * pad, 1).create())
            page.canvas.drawText(text, (pad - ink.left).toFloat(), (pad - ink.top).toFloat(), paint)
            document.finishPage(page)
            return ByteArrayOutputStream().also(document::writeTo).toByteArray()
        } finally {
            document.close()
        }
    }
}
