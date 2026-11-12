package io.github.nath_goa.usugami.data

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDVariableText
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The fonts of Chinese, Japanese and Korean forms that PDFBox reads but can't write text in:
 * composite fonts whose encoding is one of the CJK ones PDF names (UniJIS-UTF16-H, GBK-EUC-H,
 * 90ms-RKSJ-H...). Usugami carries PDFBox's tables for them so that Find text reads their text.
 * Writing is another matter: PDFBox would redraw a form field set in one with the glyph numbers of
 * a stand-in font, which read as other characters or none, or stop with an error where it stopped
 * quietly while it couldn't open such a font at all. So their fields are left as they are, for
 * viewers or Android to draw, wherever PDFBox would redraw them.
 */
internal object CjkFormFonts {
    private const val SET_FONT = "Tf"

    /** The kinds of field (/FT) whose value PDFBox writes as text: text fields and lists. */
    private val TEXT_FIELDS = setOf("Tx", "Ch")

    /** How much of a PDF's own CMap is searched for the CMap it builds on, which it names at its start. */
    private const val CMAP_START = 64 * 1024

    /** A CMap building on another (`/UniJIS-UCS2-H usecmap`): the name. */
    private val USES_CMAP = Regex("""/([^\s/\[\]<>(){}%]+)\s+usecmap(?=[\s/\[\]<>(){}%]|$)""")
    private val COMMENT = Regex("%[^\r\n]*")

    /**
     * Whether [font], a font's dictionary, is one. Its encoding is one of the named CJK CMaps, or a
     * CMap of the PDF's own that builds on one (usecmap): PDFBox reads that from the same tables.
     */
    fun isOne(font: COSBase?): Boolean {
        val dictionary = (if (font is COSObject) font.`object` else font) as? COSDictionary ?: return false
        if (dictionary.getCOSName(COSName.SUBTYPE) != COSName.TYPE0) return false
        return when (val encoding = dictionary.getDictionaryObject(COSName.ENCODING)) {
            is COSName -> encoding != COSName.IDENTITY_H && encoding != COSName.IDENTITY_V
            is COSStream -> buildsOnOne(encoding)
            else -> false
        }
    }

    /**
     * Whether [cmap], a CMap a PDF carries, builds on a named one other than Identity-H and -V.
     * Those are the ones PDFBox couldn't open while Usugami carried only the two Identity CMaps; a
     * CMap of the PDF's own that builds on none opened then too, and is left to PDFBox as it was.
     */
    private fun buildsOnOne(cmap: COSStream): Boolean {
        val start = try {
            cmap.createInputStream().use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (bytes.size() < CMAP_START) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    bytes.write(buffer, 0, read)
                }
                bytes.toString("ISO-8859-1")
            }
        } catch (_: IOException) {
            return false
        } catch (_: RuntimeException) {
            return false
        }
        return USES_CMAP.findAll(start.replace(COMMENT, "")).any { it.groupValues[1] != "Identity-H" && it.groupValues[1] != "Identity-V" }
    }

    /**
     * Whether [field]'s text is set in one: the font its default appearance (/DA), or a widget's,
     * names. Check boxes, radio buttons and signatures have no text PDFBox writes: never.
     */
    fun sets(form: PDAcroForm, field: PDTerminalField): Boolean {
        if (field !is PDVariableText) return false
        val widgets = try { field.widgets.map { it.cosObject } } catch (_: RuntimeException) { emptyList() }
        return setIn(form, field.cosObject, widgets)
    }

    /** Whether the text of [field], a field's dictionary, shown in [widgets], is set in one (see [sets]). */
    private fun setIn(form: PDAcroForm, field: COSDictionary, widgets: List<COSDictionary>): Boolean {
        // PDFBox looks a /DA's font up among the form's fonts, to which it first adds those of the
        // widgets' looks that the form doesn't have.
        val fonts = listOfNotNull(formFonts(form)) + widgets.mapNotNull(::lookFonts)
        val own = inherited(field, COSName.DA) ?: form.cosObject.getDictionaryObject(COSName.DA)
        val appearances = listOfNotNull(own) + widgets.mapNotNull { it.getDictionaryObject(COSName.DA) }
        return appearances.any { appearance -> fontName(appearance)?.let { name -> isOne(fonts.firstOrNull { it.containsKey(name) }?.getDictionaryObject(name)) } == true }
    }

    /** Whether the note or field [appearance] (a /DA) belongs to names one among [form]'s fonts. */
    fun names(form: PDAcroForm, appearance: COSBase?): Boolean {
        val name = fontName(appearance) ?: return false
        return isOne(formFonts(form)?.getDictionaryObject(name))
    }

    /** Whether PDFBox, redrawing all of [form]'s fields, would draw with one: whether any of its fields is set in one (see [sets]). */
    fun within(form: PDAcroForm): Boolean = try {
        form.fieldTree.any { it is PDTerminalField && sets(form, it) }
    } catch (_: RuntimeException) {
        false
    }

    /**
     * Whether, [form] listing no fields, a text field's widget on one of [doc]'s pages is set in
     * one: PDFBox, asked to redraw such a form, first makes fields of the widgets on its pages.
     */
    private fun widgetsWithin(doc: PDDocument, form: PDAcroForm): Boolean = try {
        form.fields.isEmpty() && doc.pages.any { page ->
            val annotations = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray
            annotations != null && (0 until annotations.size()).any { index ->
                val widget = annotations.getObject(index) as? COSDictionary
                widget != null && widget.getNameAsString(COSName.SUBTYPE) == PDAnnotationWidget.SUB_TYPE &&
                    (inherited(widget, COSName.FT) as? COSName)?.name in TEXT_FIELDS && setIn(form, widget, listOf(widget))
            }
        }
    } catch (_: RuntimeException) {
        false
    }

    /**
     * Stops [doc]'s form asking for its fields to be redrawn (NeedAppearances), if PDFBox, which
     * redraws them all when asked, would draw with one: a field's, or, in a form that lists no
     * fields, a widget's on a page, of which PDFBox then makes the fields. Returns whether it did ask.
     */
    fun silence(doc: PDDocument): Boolean {
        val form = doc.documentCatalog.getAcroForm(null) ?: return false
        if (!form.needAppearances || !(within(form) || widgetsWithin(doc, form))) return false
        form.needAppearances = false
        return true
    }

    /**
     * Joins [source] onto [dest] as [merger] does. PDFBox redraws the fields of a form that asks
     * viewers to draw them (NeedAppearances) whenever a PDF is joined, [dest]'s form too: it
     * doesn't here when it would draw with one of these fonts, and the joined PDF's form then
     * still asks.
     */
    fun append(merger: PDFMergerUtility, dest: PDDocument, source: PDDocument) {
        val destAsked = silence(dest)
        val sourceAsked = silence(source)
        merger.appendDocument(dest, source)
        if (destAsked || sourceAsked) dest.documentCatalog.getAcroForm(null)?.needAppearances = true
    }

    /** The form's fonts (/DR /Font), by name. */
    private fun formFonts(form: PDAcroForm): COSDictionary? =
        (form.cosObject.getDictionaryObject(COSName.DR) as? COSDictionary)?.getDictionaryObject(COSName.FONT) as? COSDictionary

    /** The fonts of [widget]'s normal look, by name, when it is one look (a field's on and off looks are a dictionary). */
    private fun lookFonts(widget: COSDictionary): COSDictionary? {
        val look = (widget.getDictionaryObject(COSName.AP) as? COSDictionary)?.getDictionaryObject(COSName.N) as? COSStream ?: return null
        return (look.getDictionaryObject(COSName.RESOURCES) as? COSDictionary)?.getDictionaryObject(COSName.FONT) as? COSDictionary
    }

    /** The [key] a field (or a widget) has or inherits: its own, or a parent's. */
    private fun inherited(field: COSDictionary, key: COSName): COSBase? {
        // A parent that is its own ancestor would never end otherwise.
        val seen = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        var node: COSDictionary? = field
        while (node != null && seen.add(node)) {
            node.getDictionaryObject(key)?.let { return it }
            node = node.getDictionaryObject(COSName.PARENT) as? COSDictionary
        }
        return null
    }

    /** The name of the font [appearance] (a /DA) sets, as PDFBox reads it: the first operand of its Tf. */
    private fun fontName(appearance: COSBase?): COSName? {
        val string = appearance as? COSString ?: return null
        return try {
            val parser = PDFStreamParser(string.bytes)
            var operands = ArrayList<Any>()
            var name: COSName? = null
            while (true) {
                val token = parser.parseNextToken() ?: break
                if (token is Operator) {
                    if (token.name == SET_FONT) (operands.firstOrNull() as? COSName)?.let { name = it }
                    operands = ArrayList()
                } else {
                    operands += token
                }
            }
            name
        } catch (_: IOException) {
            null
        }
    }
}
