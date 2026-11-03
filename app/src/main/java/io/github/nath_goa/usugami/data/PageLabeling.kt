package io.github.nath_goa.usugami.data

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabelRange
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabels
import java.io.IOException
import java.util.IdentityHashMap

/**
 * Page labels: the page numbers a viewer shows, such as a book's "iv" before its page "1", or "245"
 * for an article's first page. They are ranges that start at a page's place in the PDF, so taking
 * pages out or moving them would shift every label after them. These keep each page's own label
 * wherever it goes.
 */
internal object PageLabeling {
    /** A page's label: its range's style (null for none) and prefix, and its number in the range. */
    class Label(val style: String?, val prefix: String?, val number: Int)

    /**
     * The label of each of [doc]'s pages, in order, or null for a page without one of its own: every
     * page of a PDF without labels, or whose labels are only the numbers viewers show anyway (1, 2,
     * 3...), and the pages before its first range, which PDFs joined by other apps can have. Null if
     * its labels can't be read: they are then left as they are.
     */
    fun of(doc: PDDocument): List<Label?>? {
        val catalog = doc.documentCatalog
        val none = List<Label?>(doc.numberOfPages) { null }
        val tree = catalog.cosObject.getDictionaryObject(COSName.PAGE_LABELS) as? COSDictionary ?: return none
        val labels = try {
            catalog.pageLabels
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        } ?: return null
        val first = ((tree.getDictionaryObject(COSName.NUMS) as? COSArray)?.getObject(0) as? COSNumber)?.intValue() ?: 0
        // A range always starts at the first page.
        var range = labels.getPageLabelRange(0) ?: return null
        var start = 0
        var plain = true
        val result = (0 until doc.numberOfPages).map { index ->
            labels.getPageLabelRange(index)?.let {
                range = it
                start = index
            }
            if (index < first) return@map null
            val label = Label(range.style, range.prefix, range.start + index - start)
            if (label.style != PDPageLabelRange.STYLE_DECIMAL || !label.prefix.isNullOrEmpty() || label.number != index + 1) plain = false
            label
        }
        return if (plain) none else result
    }

    /** [pages]' [labels] (see [of]) by page, as [restore] takes them; those of several PDFs add up. */
    fun byPage(pages: List<PDPage>, labels: List<Label?>): MutableMap<COSDictionary, Label> =
        IdentityHashMap<COSDictionary, Label>().apply { for ((page, label) in pages.zip(labels)) if (label != null) put(page.cosObject, label) }

    /**
     * Gives [doc]'s pages, in their new order, the [labels] they had. A page without one (a blank
     * page added, a page of a PDF without labels) goes on from the page before it, as it would in
     * that page's range. Without any, viewers number the pages 1, 2, 3...
     */
    fun restore(doc: PDDocument, labels: Map<COSDictionary, Label>) {
        if (labels.isEmpty()) {
            doc.documentCatalog.cosObject.removeItem(COSName.PAGE_LABELS)
            return
        }
        val result = PDPageLabels(doc)
        var previous: Label? = null
        for ((index, page) in doc.pages.withIndex()) {
            val own = labels[page.cosObject]
            val label = own ?: previous?.let { Label(it.style, it.prefix, it.number + 1) } ?: Label(PDPageLabelRange.STYLE_DECIMAL, null, 1)
            // Pages numbered on from the page before share its range.
            val continues = previous != null && label.style == previous.style && label.prefix == previous.prefix &&
                (label.style == null || label.number == previous.number + 1)
            if (!continues) {
                result.setLabelItem(index, PDPageLabelRange().apply {
                    label.style?.let { style = it }
                    label.prefix?.let { prefix = it }
                    if (label.style != null && label.number != 1) start = label.number.coerceAtLeast(1)
                })
            }
            previous = label
        }
        doc.documentCatalog.pageLabels = result
    }
}
