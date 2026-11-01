package io.github.nath_goa.usugami

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquareCircle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDXFAResource
import java.io.File

// Made-up PDFs for Delete pages, Reorganize PDF and Flatten PDF, built here rather than stored.

/** The words written on page [index] (zero-based) of [chapters]: "PAGE ONE", "PAGE TWO"... */
val PAGE_WORDS = listOf("ONE", "TWO", "THREE", "FOUR", "FIVE")

/** Writes [text] in Helvetica on [page] at ([x], [y]). */
fun writeText(doc: PDDocument, page: PDPage, text: String, x: Float = 72f, y: Float = 700f) {
    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true).use { content ->
        content.beginText()
        content.setFont(PDType1Font.HELVETICA, 14f)
        content.newLineAtOffset(x, y)
        content.showText(text)
        content.endText()
    }
}

/** A form with Helvetica as its font, as form-filling apps make them. */
private fun newForm(doc: PDDocument): PDAcroForm = PDAcroForm(doc).also { form ->
    doc.documentCatalog.acroForm = form
    form.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
    form.defaultAppearance = "/Helv 12 Tf 0 g"
}

/** A text field called [name] on [page] at [rect], filled with [value] and drawn by PDFBox. */
private fun textField(form: PDAcroForm, page: PDPage, name: String, rect: PDRectangle, value: String?): PDTextField =
    PDTextField(form).also { field ->
        field.partialName = name
        field.defaultAppearance = "/Helv 12 Tf 0 g"
        val widget = field.widgets.single()
        widget.rectangle = rect
        widget.page = page
        widget.isPrinted = true
        form.fields.add(field)
        page.annotations = page.annotations + widget
        if (value != null) field.value = value
    }

/**
 * Five A4 pages saying "PAGE ONE" to "PAGE FIVE", with what can tie a document to its pages:
 * - a bookmark to each page ("Chapter ONE"...)
 * - on page 1, links to page 3 and to page 4
 * - a named destination, "four", to page 4
 * - a form field on page 1 ("keep", "KEEP VALUE") and one on page 4 ("secret", "SECRET VALUE")
 */
fun chapters(file: File): File = file.also {
    PDDocument().use { doc ->
        val pages = PAGE_WORDS.map { word -> PDPage(PDRectangle.A4).also { doc.addPage(it); writeText(doc, it, "PAGE $word") } }
        val outline = PDDocumentOutline()
        doc.documentCatalog.documentOutline = outline
        for ((index, page) in pages.withIndex()) {
            outline.addLast(PDOutlineItem().apply {
                title = "Chapter ${PAGE_WORDS[index]}"
                destination = PDPageFitDestination().apply { setPage(page) }
            })
        }
        outline.openNode()
        fun link(target: PDPage, y: Float) = PDAnnotationLink().apply {
            rectangle = PDRectangle(72f, y, 200f, 20f)
            destination = PDPageFitDestination().apply { setPage(target) }
        }
        pages[0].annotations = listOf<PDAnnotation>(link(pages[2], 600f), link(pages[3], 560f))
        doc.documentCatalog.cosObject.setItem(COSName.DESTS, COSDictionary().apply {
            setItem("four", PDPageFitDestination().apply { setPage(pages[3]) })
        })
        val form = newForm(doc)
        textField(form, pages[0], "keep", PDRectangle(72f, 400f, 250f, 24f), "KEEP VALUE")
        textField(form, pages[3], "secret", PDRectangle(72f, 400f, 250f, 24f), "SECRET VALUE")
        doc.save(file)
    }
}

/**
 * One A4 page saying "FORM PAGE" with:
 * - a text field "name" filled with [name], drawn by PDFBox
 * - a text field "city" holding "Lisbon" but never drawn (no appearance), as some apps leave them
 * - a ticked check box "agree", drawn as a blue square
 * - a red square comment with no appearance of its own, at (350, 400) to (500, 500)
 * - a sticky note with its popup
 * - a link to a web page
 * With [xfa], the form also has a dynamic XFA form, as some government forms do.
 */
fun formPdf(file: File, name: String = "Jane Doe", xfa: Boolean = false): File = file.also {
    PDDocument().use { doc ->
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        writeText(doc, page, "FORM PAGE", y = 760f)
        val form = newForm(doc)
        textField(form, page, "name", PDRectangle(72f, 600f, 250f, 24f), name)
        val city = textField(form, page, "city", PDRectangle(72f, 560f, 250f, 24f), null)
        city.cosObject.setString(COSName.V, "Lisbon")

        val box = PDCheckBox(form)
        box.partialName = "agree"
        val widget = box.widgets.single()
        widget.rectangle = PDRectangle(72f, 520f, 14f, 14f)
        widget.page = page
        widget.isPrinted = true
        val on = PDAppearanceStream(doc).apply { bBox = PDRectangle(14f, 14f); resources = PDResources() }
        PDPageContentStream(doc, on).use { content ->
            content.setNonStrokingColor(0f, 0f, 1f)
            content.addRect(1f, 1f, 12f, 12f)
            content.fill()
        }
        val off = PDAppearanceStream(doc).apply { bBox = PDRectangle(14f, 14f) }
        widget.appearance = PDAppearanceDictionary().apply {
            normalAppearance = PDAppearanceEntry(COSDictionary().apply {
                setItem("Yes", on)
                setItem("Off", off)
            })
        }
        widget.setAppearanceState("Yes")
        box.cosObject.setItem(COSName.V, COSName.getPDFName("Yes"))
        form.fields.add(box)

        val square = PDAnnotationSquareCircle(PDAnnotationSquareCircle.SUB_TYPE_SQUARE).apply {
            rectangle = PDRectangle(350f, 400f, 150f, 100f)
            color = PDColor(floatArrayOf(1f, 0f, 0f), PDDeviceRGB.INSTANCE)
            interiorColor = PDColor(floatArrayOf(1f, 0f, 0f), PDDeviceRGB.INSTANCE)
            isPrinted = true
        }
        val note = PDAnnotationText().apply {
            rectangle = PDRectangle(500f, 700f, 20f, 20f)
            contents = "Check the date"
        }
        val popup = PDAnnotationPopup().apply {
            rectangle = PDRectangle(300f, 650f, 200f, 100f)
            cosObject.setItem(COSName.PARENT, note)
        }
        note.popup = popup
        val link = PDAnnotationLink().apply {
            rectangle = PDRectangle(72f, 300f, 200f, 20f)
            action = PDActionURI().apply { uri = "https://example.org/form" }
        }
        page.annotations = page.annotations + listOf(widget, square, note, popup, link)

        if (xfa) {
            val packet = COSStream().apply { createOutputStream().use { it.write("<xdp:xdp xmlns:xdp=\"http://ns.adobe.com/xdp/\"/>".toByteArray()) } }
            form.xfa = PDXFAResource(packet)
            doc.documentCatalog.cosObject.setBoolean(COSName.getPDFName("NeedsRendering"), true)
        }
        doc.save(file)
    }
}

/** One page with a dynamic XFA form and nothing else: no ordinary fields, no comments. */
fun xfaOnlyPdf(file: File): File = file.also {
    PDDocument().use { doc ->
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        writeText(doc, page, "Please wait while the form loads")
        val form = newForm(doc)
        val packet = COSStream().apply { createOutputStream().use { it.write("<xdp:xdp xmlns:xdp=\"http://ns.adobe.com/xdp/\"/>".toByteArray()) } }
        form.xfa = PDXFAResource(packet)
        doc.documentCatalog.cosObject.setBoolean(COSName.getPDFName("NeedsRendering"), true)
        doc.save(file)
    }
}

/**
 * Three A4 pages saying "MAIN ONE" to "MAIN THREE" in a nested page tree: pages 1 and 2 hang under
 * a node that gives them a US Letter size and a quarter turn, which they inherit. Page 1 links to
 * page 3.
 */
fun nestedPages(file: File): File = file.also {
    PDDocument().use { doc ->
        val pages = (0..2).map { index -> PDPage(PDRectangle.A4).also { doc.addPage(it); writeText(doc, it, "MAIN ${PAGE_WORDS[index]}") } }
        pages[0].annotations = listOf<PDAnnotation>(PDAnnotationLink().apply {
            rectangle = PDRectangle(72f, 600f, 200f, 20f)
            destination = PDPageFitDestination().apply { setPage(pages[2]) }
        })
        val root = doc.pages.cosObject
        val node = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.PAGES)
            setItem(COSName.PARENT, root)
            setItem(COSName.KIDS, COSArray().apply { add(pages[0].cosObject); add(pages[1].cosObject) })
            setInt(COSName.COUNT, 2)
            setItem(COSName.MEDIA_BOX, PDRectangle.LETTER.cosArray)
            setInt(COSName.ROTATE, 90)
        }
        for (page in pages.take(2)) {
            page.cosObject.removeItem(COSName.MEDIA_BOX)
            page.cosObject.setItem(COSName.PARENT, node)
        }
        root.setItem(COSName.KIDS, COSArray().apply { add(node); add(pages[2].cosObject) })
        root.setInt(COSName.COUNT, 3)
        doc.save(file)
    }
}

/**
 * Everything [file] holds, as text: every object's dictionary and every stream decoded. A deleted
 * page's words must not be in it anywhere.
 */
fun everythingIn(file: File): String = PDDocument.load(file).use { doc ->
    val cos = doc.document
    buildString {
        for (key in cos.xrefTable.keys) {
            val obj = cos.getObjectFromPool(key).`object`
            append(obj.toString()).append('\n')
            if (obj is COSStream) append(obj.createInputStream().use { String(it.readBytes(), Charsets.ISO_8859_1) }).append('\n')
        }
    }
}
