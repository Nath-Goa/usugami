package io.github.nath_goa.usugami

import android.graphics.Bitmap
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabelRange
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabels
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquareCircle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceCharacteristicsDictionary
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
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfPageRef
import java.io.File

// Made-up PDFs for Reorganize PDF and Flatten PDF, built here rather than stored.

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
 * Two A4 pages saying "PAGE ONE" and "PAGE TWO", with a form whose fields nest by page, as forms
 * made in Adobe's designer do: topmostSubform[0].Page1[0].near holds "NEAR VALUE" and is its own
 * widget on page 1; topmostSubform[0].Page2[0].far holds "FAR VALUE" and has its widget, on page
 * 2, as its kid.
 */
fun nestedFormPdf(file: File): File = file.also {
    PDDocument().use { doc ->
        val pages = (0..1).map { index -> PDPage(PDRectangle.A4).also { doc.addPage(it); writeText(doc, it, "PAGE ${PAGE_WORDS[index]}") } }
        val form = newForm(doc)
        fun field(name: String, parent: COSDictionary?) = COSDictionary().apply {
            setString(COSName.T, name)
            if (parent != null) {
                setItem(COSName.PARENT, parent)
                (parent.getDictionaryObject(COSName.KIDS) as COSArray).add(this)
            }
            setItem(COSName.KIDS, COSArray())
        }
        fun COSDictionary.widgetOn(page: PDPage) {
            setItem(COSName.TYPE, COSName.ANNOT)
            setItem(COSName.SUBTYPE, COSName.WIDGET)
            setItem(COSName.RECT, PDRectangle(72f, 400f, 250f, 24f).cosArray)
            setItem(COSName.P, page.cosObject)
            setInt(COSName.F, 4)
            page.cosObject.setItem(COSName.ANNOTS, COSArray().apply { add(this@widgetOn) })
        }
        val top = field("topmostSubform[0]", null)
        form.cosObject.setItem(COSName.FIELDS, COSArray().apply { add(top) })
        field("near", field("Page1[0]", top)).apply {
            removeItem(COSName.KIDS)
            setItem(COSName.FT, COSName.TX)
            setString(COSName.V, "NEAR VALUE")
            widgetOn(pages[0])
        }
        val far = field("far", field("Page2[0]", top)).apply {
            setItem(COSName.FT, COSName.TX)
            setString(COSName.V, "FAR VALUE")
        }
        COSDictionary().apply {
            setItem(COSName.PARENT, far)
            (far.getDictionaryObject(COSName.KIDS) as COSArray).add(this)
            widgetOn(pages[1])
        }
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

/**
 * One A4 page with a form in Helvetica, which has no rupee sign and no Devanagari, that asks viewers
 * to draw its fields (NeedAppearances), as some form-filling apps leave them:
 * - a text field "amount" holding "₹ 5,000", with a white background (/MK) and the look it had
 *   before that value was set, saying "OLD AMOUNT", which such apps leave as it was
 * - a text field "name" holding "नाम", with no look at all
 * - with [unplacedField], a text field "city" holding "Lisbon", which Helvetica can draw, whose
 *   widget doesn't say which page it is on (no /P, which is optional)
 */
fun unencodableFormPdf(file: File, unplacedField: Boolean = false): File = file.also {
    PDDocument().use { doc ->
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        val form = newForm(doc)
        form.needAppearances = true
        val amount = textField(form, page, "amount", PDRectangle(72f, 600f, 250f, 24f), null)
        val widget = amount.widgets.single()
        widget.appearanceCharacteristics = PDAppearanceCharacteristicsDictionary(COSDictionary()).apply {
            background = PDColor(floatArrayOf(1f, 1f, 1f), PDDeviceRGB.INSTANCE)
        }
        val look = PDAppearanceStream(doc).apply {
            bBox = PDRectangle(250f, 24f)
            resources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
        }
        PDPageContentStream(doc, look).use { content ->
            content.beginText()
            content.setFont(PDType1Font.HELVETICA, 12f)
            content.newLineAtOffset(2f, 7f)
            content.showText("OLD AMOUNT")
            content.endText()
        }
        widget.appearance = PDAppearanceDictionary().apply { setNormalAppearance(look) }
        amount.cosObject.setString(COSName.V, "₹ 5,000")
        val name = textField(form, page, "name", PDRectangle(72f, 560f, 250f, 24f), null)
        name.cosObject.setString(COSName.V, "नाम")
        if (unplacedField) textField(form, page, "city", PDRectangle(72f, 520f, 250f, 24f), "Lisbon").widgets.single().cosObject.removeItem(COSName.P)
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

/** The bytes of each image in [sharedResources], one row of 16 gray pixels per line: findable in the file. */
val IMAGE_MARKERS = listOf("IMAGE ONE 111111", "IMAGE TWO 222222", "IMAGE THREE 3333")

/**
 * Three A4 pages saying "PAGE ONE" to "PAGE THREE", each drawing its own 16 by 2 pixel gray image
 * (see [IMAGE_MARKERS]) large at (100, 300) to (500, 500). All three images are in one list of
 * resources the pages share, as many PDF makers do; with [inherited], the pages inherit it from
 * the page tree, as scanners' PDFs often do.
 */
fun sharedResources(file: File, inherited: Boolean = false): File = file.also {
    PDDocument().use { doc ->
        val pages = (0..2).map { PDPage(PDRectangle.A4).also(doc::addPage) }
        val shared = PDResources()
        if (inherited) doc.pages.cosObject.setItem(COSName.RESOURCES, shared.cosObject) else pages.forEach { it.resources = shared }
        for ((index, page) in pages.withIndex()) {
            val stream = doc.document.createCOSStream()
            stream.createOutputStream(COSName.FLATE_DECODE).use { it.write((IMAGE_MARKERS[index] + IMAGE_MARKERS[index]).toByteArray()) }
            stream.setItem(COSName.TYPE, COSName.XOBJECT)
            stream.setItem(COSName.SUBTYPE, COSName.IMAGE)
            stream.setInt(COSName.WIDTH, 16)
            stream.setInt(COSName.HEIGHT, 2)
            stream.setInt(COSName.BITS_PER_COMPONENT, 8)
            stream.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY)
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true).use { content ->
                content.drawImage(PDImageXObject(PDStream(stream), null), 100f, 300f, 400f, 200f)
            }
            writeText(doc, page, "PAGE ${PAGE_WORDS[index]}")
        }
        check(pages.all { it.cosObject.containsKey(COSName.RESOURCES) != inherited })
        doc.save(file)
    }
}

/**
 * Three A4 pages saying "PAGE ONE" to "PAGE THREE", tagged for screen readers: under a Document
 * element, a figure for each page with the alternate text "SECRET ALT 1" to "SECRET ALT 3" and the
 * ID "fig1" to "fig3", and a paragraph running from page 1 onto page 2 whose ActualText is "SPANS
 * PAGES".
 */
fun taggedPages(file: File): File = file.also {
    PDDocument().use { doc ->
        val pages = (0..2).map { index ->
            PDPage(PDRectangle.A4).also { page ->
                doc.addPage(page)
                writeText(doc, page, "PAGE ${PAGE_WORDS[index]}")
                page.cosObject.setInt(COSName.STRUCT_PARENTS, index)
            }
        }
        val root = COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("StructTreeRoot")) }
        val document = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.STRUCT_ELEM)
            setName(COSName.S, "Document")
            setItem(COSName.P, root)
        }
        fun element(type: String, page: PDPage) = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.STRUCT_ELEM)
            setName(COSName.S, type)
            setItem(COSName.P, document)
            setItem(COSName.PG, page.cosObject)
        }
        // Marked content 0 of each page.
        val figures = pages.mapIndexed { index, page ->
            element("Figure", page).apply {
                setString(COSName.ALT, "SECRET ALT ${index + 1}")
                setInt(COSName.K, 0)
            }
        }
        // Marked content 1 of pages 1 and 2.
        val paragraph = element("P", pages[0]).apply {
            setString(COSName.ACTUAL_TEXT, "SPANS PAGES")
            setItem(COSName.K, COSArray().apply {
                add(COSInteger.get(1))
                add(COSDictionary().apply {
                    setItem(COSName.TYPE, COSName.getPDFName("MCR"))
                    setItem(COSName.PG, pages[1].cosObject)
                    setInt(COSName.MCID, 1)
                })
            })
        }
        document.setItem(COSName.K, COSArray().apply { figures.forEach(::add); add(paragraph) })
        root.setItem(COSName.K, document)
        root.setItem(COSName.PARENT_TREE, COSDictionary().apply {
            setItem(COSName.NUMS, COSArray().apply {
                for (index in pages.indices) {
                    add(COSInteger.get(index.toLong()))
                    add(COSArray().apply {
                        add(figures[index])
                        if (index < 2) add(paragraph)
                    })
                }
            })
        })
        // The figures' IDs, "fig1" to "fig3", indexed in two leaves: fig1 and fig2, then fig3.
        fun leaf(vararg indexes: Int) = COSDictionary().apply {
            setItem(COSName.NAMES, COSArray().apply { for (i in indexes) { add(COSString("fig${i + 1}")); add(figures[i]) } })
            setItem(COSName.LIMITS, COSArray().apply { add(COSString("fig${indexes.first() + 1}")); add(COSString("fig${indexes.last() + 1}")) })
        }
        figures.forEachIndexed { index, figure -> figure.setString(COSName.ID, "fig${index + 1}") }
        root.setItem(COSName.ID_TREE, COSDictionary().apply { setItem(COSName.KIDS, COSArray().apply { add(leaf(0, 1)); add(leaf(2)) }) })
        doc.documentCatalog.cosObject.setItem(COSName.STRUCT_TREE_ROOT, root)
        doc.documentCatalog.cosObject.setItem(COSName.MARK_INFO, COSDictionary().apply { setBoolean(COSName.getPDFName("Marked"), true) })
        doc.save(file)
    }
}

/**
 * Six A4 pages numbered as a book's are: "i" to "iii", then "1" to "3". With [plain], they are
 * labelled 1 to 6 instead, which is what viewers show anyway.
 */
fun labelledPages(file: File, plain: Boolean = false): File = file.also {
    PDDocument().use { doc ->
        repeat(6) { index -> PDPage(PDRectangle.A4).also { doc.addPage(it); writeText(doc, it, "PAGE ${index + 1}") } }
        val labels = PDPageLabels(doc)
        labels.setLabelItem(0, PDPageLabelRange().apply { style = if (plain) PDPageLabelRange.STYLE_DECIMAL else PDPageLabelRange.STYLE_ROMAN_LOWER })
        if (!plain) labels.setLabelItem(3, PDPageLabelRange().apply { style = PDPageLabelRange.STYLE_DECIMAL })
        doc.documentCatalog.pageLabels = labels
        doc.save(file)
    }
}

/** The label each page of [file] shows in a viewer. */
fun pageLabels(file: File): List<String> = PDDocument.load(file).use { doc ->
    (doc.documentCatalog.pageLabels ?: PDPageLabels(doc)).labelsByPageIndices.toList()
}

/**
 * A PDF Compress PDF can't make smaller: a line of [text] over a picture of noise with see-through
 * parts, which compressing leaves as it is, saved by PDFBox as Usugami saves PDFs.
 */
fun compactPdf(file: File, text: String): File = file.also {
    val random = java.util.Random(3)
    val pixels = IntArray(300 * 300) { (0x80 + random.nextInt(0x80)) shl 24 or random.nextInt(0x1000000) }
    val bitmap = Bitmap.createBitmap(pixels, 300, 300, Bitmap.Config.ARGB_8888)
    PDDocument().use { doc ->
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        PDPageContentStream(doc, page).use { it.drawImage(LosslessFactory.createFromImage(doc, bitmap), 72f, 300f, 300f, 300f) }
        writeText(doc, page, text)
        doc.save(file)
    }
    bitmap.recycle()
}

/** A font as older CJK PDFs have them: named, not embedded, without a ToUnicode map. [encoding] is a CMap's name. */
fun cjkFont(encoding: String, ordering: String, supplement: Int, subtype: String = "CIDFontType0", name: String = "Usugami-Test"): COSDictionary {
    val descriptor = COSDictionary().apply {
        setItem(COSName.TYPE, COSName.FONT_DESC)
        setName(COSName.FONT_NAME, name)
        setInt(COSName.FLAGS, 6)
        setItem(COSName.FONT_BBOX, COSArray().apply { listOf(0L, -120L, 1000L, 880L).forEach { add(COSInteger.get(it)) } })
        setInt(COSName.ITALIC_ANGLE, 0)
        setInt(COSName.ASCENT, 880)
        setInt(COSName.DESCENT, -120)
        setInt(COSName.CAP_HEIGHT, 700)
        setInt(COSName.STEM_V, 80)
    }
    val collection = COSDictionary().apply {
        setString(COSName.REGISTRY, "Adobe")
        setString(COSName.ORDERING, ordering)
        setInt(COSName.SUPPLEMENT, supplement)
    }
    val descendant = COSDictionary().apply {
        setItem(COSName.TYPE, COSName.FONT)
        setName(COSName.SUBTYPE, subtype)
        setName(COSName.BASE_FONT, name)
        setItem(COSName.CIDSYSTEMINFO, collection)
        setItem(COSName.FONT_DESC, descriptor)
        setInt(COSName.DW, 1000)
    }
    return COSDictionary().apply {
        setItem(COSName.TYPE, COSName.FONT)
        setItem(COSName.SUBTYPE, COSName.TYPE0)
        setName(COSName.BASE_FONT, "$name-$encoding")
        setName(COSName.ENCODING, encoding)
        setItem(COSName.DESCENDANT_FONTS, COSArray().apply { add(descendant) })
    }
}

/** Pages [indexes] (zero-based) of [input], not turned: a page list for PdfToolsEngine.reorganize. */
fun pagesOf(input: PdfInput, vararg indexes: Int): List<PdfPageRef> = indexes.map { PdfPageRef(input.id, it) }
