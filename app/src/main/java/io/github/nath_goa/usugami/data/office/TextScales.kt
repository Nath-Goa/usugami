package io.github.nath_goa.usugami.data.office

import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import java.io.ByteArrayOutputStream

/**
 * Text drawn narrower or wider than its font (a paint's textScaleX, with which Excel's cells match
 * Office's font widths), as Android 8's PDF backend writes it: ten times too much. The scale (Tz)
 * is a percentage, and it writes 889 for 0.889, so viewers draw each letter about nine times as
 * wide, over the letters after it, and searching and copying the text lose letters. Whether this
 * phone's backend does is found once, on a tiny page made for it, and the scales are put right as
 * the parts are joined.
 */
internal object TextScales {
    private val tenfold: Boolean by lazy { probe() }

    /** Puts right the text scales on [doc]'s pages, if this phone's PDF backend writes them ten times too big. */
    fun fix(doc: PDDocument, check: () -> Unit) {
        if (!tenfold) return
        for (page in doc.pages) {
            check()
            val tokens = PDFStreamParser(page).apply { parse() }.tokens
            var changed = false
            for (i in 1 until tokens.size) {
                if ((tokens[i] as? Operator)?.name != "Tz") continue
                val scale = tokens[i - 1] as? COSNumber ?: continue
                tokens[i - 1] = COSFloat(scale.floatValue() / 10)
                changed = true
            }
            if (!changed) continue
            val content = PDStream(doc)
            content.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(tokens) }
            page.setContents(content)
        }
    }

    /** Whether text drawn half as wide comes out scaled by 500 %, not 50 %. */
    private fun probe(): Boolean = try {
        val document = PdfDocument()
        val bytes = try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(20, 20, 1).create())
            page.canvas.drawText("a", 2f, 12f, TextPaint().apply { textSize = 10f; textScaleX = 0.5f })
            document.finishPage(page)
            ByteArrayOutputStream().also(document::writeTo).toByteArray()
        } finally {
            document.close()
        }
        PDDocument.load(bytes).use { pdf ->
            val tokens = PDFStreamParser(pdf.getPage(0)).apply { parse() }.tokens
            (1 until tokens.size).any { i -> (tokens[i] as? Operator)?.name == "Tz" && ((tokens[i - 1] as? COSNumber)?.floatValue() ?: 0f) > 100f }
        }
    } catch (_: Exception) {
        false
    }
}
