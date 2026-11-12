package io.github.nath_goa.usugami

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.github.nath_goa.usugami.data.Flattening
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfPageRef
import io.github.nath_goa.usugami.data.PdfToolsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Chinese, Japanese and Korean text in fonts PDFBox reads with the CMaps Usugami packs, but must
 * not write in. Every PDF is made here, with fonts that have a made-up name, no font file and no
 * ToUnicode map.
 */
class CjkTextDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = PdfToolsEngine(context)
    private val results = File(context.getExternalFilesDir(null), "test-results").apply { mkdirs() }
    private var nextId = 3501L

    private fun import(file: File): PdfInput = engine.import(Uri.fromFile(file), nextId++)

    private fun save(file: File, name: String): File = file.copyTo(File(results, name), overwrite = true)

    private fun made(name: String) = File(context.cacheDir, name)

    /** One A4 page saying "PLAIN PAGE". */
    private fun plainPdf(file: File): File = file.also {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4).also(doc::addPage)
            writeText(doc, page, "PLAIN PAGE")
            doc.save(file)
        }
    }

    /** A text note (FreeText comment) at [rect] saying [says], with no look of its own, set in the font [appearance] (a /DA) names. */
    private fun note(page: PDPage, rect: PDRectangle, appearance: String, says: String): PDAnnotation =
        PDAnnotation.createAnnotation(COSDictionary().apply {
            setItem(COSName.TYPE, COSName.ANNOT)
            setName(COSName.SUBTYPE, "FreeText")
            setItem(COSName.RECT, rect.cosArray)
            setString(COSName.CONTENTS, says)
            setString(COSName.DA, appearance)
            setInt(COSName.F, 4)
            setItem(COSName.P, page.cosObject)
        })

    /**
     * [font] with its named encoding replaced by a CMap of the PDF's own that builds on the named
     * one (usecmap), as some PDFs carry them.
     */
    private fun buildingOn(font: COSDictionary): COSDictionary = font.apply {
        val named = getCOSName(COSName.ENCODING).name
        val cmap = "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n/$named usecmap\n" +
            "/CMapName /Usugami-Test-H def\n/CMapType 1 def\nendcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n"
        setItem(COSName.ENCODING, COSStream().apply {
            setName(COSName.TYPE, "CMap")
            createOutputStream().use { it.write(cmap.toByteArray(Charsets.US_ASCII)) }
        })
    }

    /**
     * One A4 page with a form that asks viewers to draw its fields (NeedAppearances), its values
     * set and no look made for them, as some apps fill forms. The form's fonts are [fonts]; each of
     * [fields] is a text field's name, its /DA and its value. [notes] adds comments to the page.
     * Without [listed], the form lists no fields: they are only widgets on the page.
     */
    private fun cjkFormPdf(
        file: File,
        fonts: Map<String, COSDictionary>,
        fields: List<Triple<String, String, String>>,
        listed: Boolean = true,
        notes: (PDPage) -> List<PDAnnotation> = { emptyList() },
    ): File = file.also {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4).also(doc::addPage)
            val form = PDAcroForm(doc)
            doc.documentCatalog.acroForm = form
            form.defaultResources = PDResources(COSDictionary().apply {
                setItem(COSName.FONT, COSDictionary().apply { for ((name, font) in fonts) setItem(name, font) })
            })
            form.needAppearances = true
            for ((index, field) in fields.withIndex()) {
                val (name, appearance, value) = field
                val text = PDTextField(form)
                text.partialName = name
                text.defaultAppearance = appearance
                text.widgets.single().apply {
                    rectangle = PDRectangle(72f, 700f - 60f * index, 250f, 24f)
                    this.page = page
                    isPrinted = true
                    page.annotations.add(this)
                }
                if (listed) form.fields.add(text)
                // Setting it through PDFBox would draw it.
                text.cosObject.setString(COSName.V, value)
            }
            page.annotations = page.annotations + notes(page)
            doc.save(file)
        }
    }

    /** [file]'s form fields by name, each with its value and whether a widget of it has a normal look. */
    private fun fieldsOf(file: File): Map<String, Pair<String, Boolean>> = PDDocument.load(file).use { doc ->
        // Without PDFBox's fix-ups, which would draw the fields.
        val form = doc.documentCatalog.getAcroForm(null)!!
        form.fieldTree.filterIsInstance<PDTerminalField>().associate { field ->
            field.partialName to (field.valueAsString to field.widgets.any { it.normalAppearanceStream != null })
        }
    }

    /** The widgets on [file]'s first page by name, each with its value and whether it has a normal look. */
    private fun widgetsOf(file: File): Map<String, Pair<String, Boolean>> = PDDocument.load(file).use { doc ->
        doc.getPage(0).annotations.filterIsInstance<PDAnnotationWidget>().associate { widget ->
            widget.cosObject.getString(COSName.T) to (widget.cosObject.getString(COSName.V) to (widget.normalAppearanceStream != null))
        }
    }

    /** Whether [file]'s form asks viewers to draw its fields (NeedAppearances). */
    private fun asksForLooks(file: File): Boolean = PDDocument.load(file).use { it.documentCatalog.getAcroForm(null)!!.needAppearances }

    /**
     * A form whose fields are set in CJK fonts PDFBox can't write in, one it would write in wrong
     * codes (a TrueType font that isn't embedded) and one it stops on: Merge PDF, Reorganize PDF
     * and Flatten PDF leave them as they are for viewers to draw, as 1.6.5 did, and the preview has
     * Android draw them. So are such a form's widgets when it lists no fields, and a field set in a
     * font whose CMap builds on a CJK one. A form that only carries such a font is still drawn by
     * PDFBox. A text note set in one is drawn without its text, and keeps it.
     */
    @Test fun formsSetInCjkFontsAreJoinedAndFlattenedAsTheyAre() {
        val mincho = "/Mincho 12 Tf 0 g"
        val fonts = mapOf("Mincho" to cjkFont("90ms-RKSJ-H", "Japan1", 2, "CIDFontType2"), "Kozuka" to cjkFont("UniJIS-UTF16-H", "Japan1", 6))
        val fields = listOf(Triple("a", mincho, "123"), Triple("b", "/Kozuka 12 Tf 0 g", "456"))
        val form = import(cjkFormPdf(made("cjk_form.pdf"), fonts, fields) { page ->
            listOf(
                note(page, PDRectangle(72f, 400f, 250f, 40f), "/Helv 12 Tf 0 g", "A note"),
                note(page, PDRectangle(72f, 300f, 250f, 40f), mincho, "Note two"),
            )
        })
        val plain = import(plainPdf(made("cjk_plain.pdf")))
        val asTheyAre = mapOf("a" to ("123" to false), "b" to ("456" to false))

        // Each result is checked before the next tool runs, which clears it away.
        val joined = save(engine.merge(listOf(form, plain), null, "joined").file, "cjk_form_joined.pdf")
        assertEquals("Merge PDF leaves the fields as they are", asTheyAre, fieldsOf(joined))
        assertTrue("and asks viewers to draw them", asksForLooks(joined))

        val result = engine.flatten(form, "flat")
        assertEquals("Both fields stay fields", 2, result.keptFields)
        val flat = save(result.file.file, "cjk_form_flattened.pdf")
        assertEquals(asTheyAre, fieldsOf(flat))
        assertTrue(asksForLooks(flat))
        PDDocument.load(flat).use { doc ->
            assertEquals("Only the fields' widgets are left", listOf("Widget", "Widget"), doc.getPage(0).annotations.map { it.subtype })
            val text = PDFTextStripper().getText(doc)
            assertTrue("The note in Helvetica is drawn into the page: $text", text.contains("A note"))
            assertFalse("The note in a CJK font is drawn without its text: $text", text.contains("Note two"))
        }

        assertEquals("The preview has Android draw the value", listOf(0), engine.findText(form, "123").map { it.single().page })

        val pages = listOf(PdfPageRef(form.id, 0), PdfPageRef(plain.id, 0))
        val rearranged = save(engine.reorganize(form, listOf(plain), pages, "rearranged").file, "cjk_form_reorganized.pdf")
        assertEquals("Reorganize PDF leaves the fields of the user's own PDF as they are", asTheyAre, fieldsOf(rearranged))
        assertTrue(asksForLooks(rearranged))

        val carried = import(cjkFormPdf(
            made("cjk_carried.pdf"),
            mapOf("Helv" to PDType1Font.HELVETICA.cosObject, "Kozuka" to cjkFont("UniJIS-UTF16-H", "Japan1", 6)),
            listOf(Triple("c", "/Helv 12 Tf 0 g", "789")),
        ))
        val joinedCarried = save(engine.merge(listOf(carried, plain), null, "carried").file, "cjk_carried_joined.pdf")
        assertEquals("A field in Helvetica is drawn though its form carries a CJK font", mapOf("c" to ("789" to true)), fieldsOf(joinedCarried))
        assertFalse(asksForLooks(joinedCarried))

        // PDFBox makes fields of the widgets on the pages of a form that lists none, and draws those.
        val unlisted = import(cjkFormPdf(made("cjk_unlisted.pdf"), fonts, fields, listed = false))
        val joinedUnlisted = save(engine.merge(listOf(unlisted, plain), null, "unlisted").file, "cjk_unlisted_joined.pdf")
        assertEquals("Merge PDF leaves the widgets of a form that lists no fields as they are", asTheyAre, widgetsOf(joinedUnlisted))
        assertTrue(asksForLooks(joinedUnlisted))

        val built = import(cjkFormPdf(
            made("cjk_usecmap.pdf"),
            mapOf("Built" to buildingOn(cjkFont("UniJIS-UTF16-H", "Japan1", 6))),
            listOf(Triple("e", "/Built 12 Tf 0 g", "135")),
        ))
        val joinedBuilt = save(engine.merge(listOf(built, plain), null, "usecmap").file, "cjk_usecmap_joined.pdf")
        assertEquals("and a field in a font whose CMap builds on a CJK one", mapOf("e" to ("135" to false)), fieldsOf(joinedBuilt))
        assertTrue(asksForLooks(joinedBuilt))

        PDDocument.load(form.file).use { doc ->
            val note = doc.getPage(0).annotations.single { it.contents == "Note two" }
            val look = Flattening.appearanceOf(doc, note)
            assertNotNull("The note in a CJK font has its box and border", look)
            val content = String(look!!.contentStream.toByteArray(), Charsets.ISO_8859_1)
            assertFalse("but no text: $content", content.contains("Tj"))
            assertEquals("and keeps what it says", "Note two", note.cosObject.getString(COSName.CONTENTS))
        }
    }
}
