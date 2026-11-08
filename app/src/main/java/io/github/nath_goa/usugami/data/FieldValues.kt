package io.github.nath_goa.usugami.data

import android.graphics.Color
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDVariableText
import com.tom_roush.pdfbox.util.Matrix
import io.github.nath_goa.usugami.core.TextItem
import java.io.IOException
import java.util.IdentityHashMap
import kotlin.math.min

/**
 * Form field values PDFBox can't draw in the field's own font (Hindi or Polish in a Latin-only
 * font, say), and that have no look of their own or only one their form says is out of date (see
 * [Flattening.redrawFields]), drawn by Android instead, as Edit PDF draws text ([PageText]), for
 * the copy the previews draw and Redact PDF searches and draws its pictures from: otherwise the
 * field would look empty there (or show an older value), Find text couldn't find its value, and a
 * redacted page would lose it. It is real text, so it can be found.
 */
internal object FieldValues {
    /** Room between a field's edge and its text, in points, as viewers leave. */
    private const val ROOM = 2f

    /** The size viewers draw a field's text at when it asks for "as big as fits" and wraps. */
    private const val WRAPPED_SIZE = 12f

    private val FONT_SIZE = Regex("""(\d+(?:\.\d+)?)\s+Tf""")

    /**
     * A field's value drawn at [PageText.REFERENCE]: [form] has it in a box [width] × [height] big.
     * [size] is the font size the field asks for (0: as big as fits), [align] its /Q (0 left, 1
     * centred, 2 right), and [multiline] says whether it is drawn from the top down.
     */
    class Value(val form: PDFormXObject, val width: Float, val height: Float, val size: Float, val align: Int, val multiline: Boolean)

    /**
     * The values of [fields] drawn for each of their widgets, by the widget's dictionary. Values no
     * font on the phone has, and passwords, are left out, as are all of them if they can't be drawn.
     */
    fun drawn(doc: PDDocument, fields: List<PDTerminalField>, check: () -> Unit): Map<COSDictionary, Value> {
        val texts = fields.mapNotNull { field ->
            val raw = when (field) {
                is PDTextField -> field.value.takeUnless { field.isPassword }
                is PDChoice -> field.value.joinToString("\n") { Flattening.shownChoice(field, it) }
                else -> null
            } ?: return@mapNotNull null
            val multiline = field is PDTextField && field.isMultiline
            var text = raw.replace("\r\n", "\n").replace('\r', '\n')
            if (multiline) {
                // Wrapped to the field's width at the size it is drawn at, as viewers do.
                val width = field.widgets.firstNotNullOfOrNull { it.rectangle?.width }?.takeIf { it > 2 * ROOM }
                val size = fontSize(field).takeIf { it > 0f } ?: WRAPPED_SIZE
                if (width != null) text = wrapped(text, (width - 2 * ROOM) * PageText.REFERENCE / size)
            } else if (field !is PDChoice) {
                text = text.replace('\n', ' ')
            }
            text.takeIf { it.isNotBlank() && PageText.canDraw(it) }?.let { field to it }
        }
        if (texts.isEmpty()) return emptyMap()
        check()
        val items = texts.mapIndexed { i, (_, text) ->
            val (width, height) = PageText.unitSize(text)
            TextItem(i.toLong(), 0, 0f, 0f, text, 1f, Color.BLACK, width, height)
        }
        val rendered = try {
            PageText.render(items, check)
        } catch (_: WatermarkFontMissing) {
            return emptyMap()
        }
        // Without them the fields would only look empty, as before: that is no reason to fail.
        val forms = try {
            // The text is copied out as it is imported, so the PDF it is drawn in can be closed then.
            PDDocument.load(rendered).use { drawn ->
                val layer = LayerUtility(doc)
                items.indices.map { layer.importPageAsForm(drawn, it) }
            }
        } catch (_: IOException) {
            return emptyMap()
        } catch (_: RuntimeException) {
            return emptyMap()
        }
        val values = IdentityHashMap<COSDictionary, Value>()
        texts.forEachIndexed { i, (field, _) ->
            val value = Value(
                forms[i], items[i].unitWidth * PageText.REFERENCE, items[i].unitHeight * PageText.REFERENCE,
                fontSize(field), (field as? PDVariableText)?.q ?: 0, field is PDTextField && field.isMultiline,
            )
            for (widget in field.widgets) values[widget.cosObject] = value
        }
        return values
    }

    /** Draws [value] in [widget]'s rectangle, cut to it, as a viewer draws the field's text. */
    fun draw(content: PDPageContentStream, widget: PDAnnotation, value: Value) {
        val rect = widget.rectangle ?: return
        if (rect.width <= 2 * ROOM || rect.height <= 2 * ROOM) return
        val fits = min((rect.width - 2 * ROOM) / value.width, (rect.height - 2 * ROOM) / value.height) * PageText.REFERENCE
        val size = when {
            value.size > 0f -> value.size
            value.multiline -> WRAPPED_SIZE
            else -> fits
        }
        val scale = size / PageText.REFERENCE
        val width = value.width * scale
        val height = value.height * scale
        val left = when (value.align) {
            1 -> rect.lowerLeftX + (rect.width - width) / 2
            2 -> rect.upperRightX - ROOM - width
            else -> rect.lowerLeftX + ROOM
        }
        val top = if (value.multiline) rect.upperRightY - ROOM else rect.upperRightY - (rect.height - height) / 2
        content.saveGraphicsState()
        content.addRect(rect.lowerLeftX, rect.lowerLeftY, rect.width, rect.height)
        content.clip()
        // The form's y points up; the text's box starts PAD in from its top-left corner.
        content.transform(Matrix(scale, 0f, 0f, scale, left - scale * PageText.PAD, top - scale * (value.form.bBox.upperRightY - PageText.PAD)))
        content.drawForm(value.form)
        content.restoreGraphicsState()
    }

    /** The font size [field]'s default appearance asks for; 0 for as big as fits. */
    private fun fontSize(field: PDTerminalField): Float =
        (field as? PDVariableText)?.defaultAppearance?.let { FONT_SIZE.find(it)?.groupValues?.get(1)?.toFloatOrNull() } ?: 0f

    /** [text] with its lines broken between words so that each is at most [width] wide at [PageText.REFERENCE]. */
    private fun wrapped(text: String, width: Float): String {
        val paint = PageText.paint(Color.BLACK)
        return text.split('\n').joinToString("\n") { paragraph ->
            val lines = ArrayList<String>()
            var line = ""
            for (word in paragraph.split(' ')) {
                val longer = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && paint.measureText(longer) > width) {
                    lines += line
                    line = word
                } else {
                    line = longer
                }
            }
            lines += line
            lines.joinToString("\n")
        }
    }
}
