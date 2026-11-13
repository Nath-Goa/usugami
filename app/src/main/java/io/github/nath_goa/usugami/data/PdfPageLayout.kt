package io.github.nath_goa.usugami.data

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties
import com.tom_roush.pdfbox.util.Matrix
import io.github.nath_goa.usugami.core.CropBoxes
import io.github.nath_goa.usugami.core.CropShare
import io.github.nath_goa.usugami.core.FileNames
import io.github.nath_goa.usugami.core.PageSize
import io.github.nath_goa.usugami.core.SheetLayout
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Page layout's Crop: saves [input] with each of [pages] (zero-based; null: all) cropped to [share]
 * of what it shows now (see [PageCropping.crop]), named after [fileName]. Nothing else of the PDF
 * changes. Throws what [edit] throws, and the cancellation [check] throws, leaving no file.
 */
internal fun PdfToolsEngine.cropPages(input: PdfInput, share: CropShare, pages: List<Int>?, fileName: String, check: () -> Unit = {}): PdfFile =
    edit(input, fileName, "Page_layout", check) { doc -> PageCropping.crop(doc, share, pages, check) }

/**
 * Page layout's Pages per sheet: a new PDF of sheets of [paper] (null: A4, or US Letter when the
 * first page is US Letter) holding [perSheet] of [input]'s pages each (see [PageSheets.impose]),
 * named after [fileName]. In place of the PDFs made before; nothing is left if it fails or is cancelled.
 */
internal fun PdfToolsEngine.pagesPerSheet(input: PdfInput, perSheet: Int, paper: PageSize?, border: Boolean, fileName: String, check: () -> Unit = {}): PdfFile {
    require(perSheet in SheetLayout.COUNTS) { "Pages per sheet must be one of ${SheetLayout.COUNTS}, not $perSheet" }
    require(paper == null || paper in SheetLayout.PAPERS) { "Sheets are A4 or US Letter, not $paper" }
    return newOutput {
        val target = outputFile(FileNames.sanitize(fileName, "Page_layout"))
        loadUnmarked(input.file, input.password).use { source ->
            // A document of its own, with PDFBox's scratch files as every document the engine opens.
            PDDocument(memory()).use { dest ->
                PageSheets.impose(source, dest, perSheet, paper, border, check)
                check()
                dest.save(target)
                PdfFile(target, dest.numberOfPages, 0)
            }
        }
    }
}

/** Crop: a new /CropBox for the pages chosen, and nothing else changed. */
internal object PageCropping {
    /**
     * Gives each of [pages] (zero-based; null: all) the crop box [share] of what it shows now (its
     * crop box, turned by its /Rotate), cut to its /MediaBox (see [CropBoxes.pageBox]). A page
     * named twice is cropped once, also one a damaged page tree lists twice. A page object the tree
     * lists at several places is one page, so cropping it at one place crops it at all of them, also
     * at the places not chosen (the page tree of a damaged file is not rewritten for it). A page
     * whose box would be too small to show is left as it is; so are its content, /Rotate, /MediaBox,
     * its other boxes and everything else of the PDF.
     */
    fun crop(doc: PDDocument, share: CropShare, pages: List<Int>?, check: () -> Unit) {
        // The page tree is walked once: finding each page by its number walks it again each time.
        val all = doc.pages.toList()
        val chosen = pages?.toHashSet()
        require(chosen == null || (chosen.isNotEmpty() && chosen.all { it in all.indices })) { "No such pages among ${all.size}: $pages" }
        // Cropping again would crop inside the new box: what shows of the page has changed.
        val cropped = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        for ((index, page) in all.withIndex()) {
            if (chosen != null && index !in chosen) continue
            if (!cropped.add(page.cosObject)) continue
            check()
            val media = page.mediaBox
            val box = CropBoxes.pageBox(
                page.frame(),
                share,
                floatArrayOf(media.lowerLeftX, media.lowerLeftY, media.upperRightX, media.upperRightY),
            ) ?: continue
            page.cropBox = PDRectangle(box[0], box[1], box[2] - box[0], box[3] - box[1])
        }
    }
}

/** Pages per sheet: the pages placed, scaled, on sheets of a new PDF, as form XObjects. */
internal object PageSheets {
    /**
     * Lays [source]'s pages on sheets in [dest] (empty, new), [perSheet] to a sheet, across then
     * down, each page as it shows (turned by its /Rotate, cut to its crop box) fitted to its cell
     * (see [SheetLayout]). [paper] null: as [SheetLayout.paperLike] says for the first page. With
     * [border], a thin gray line round each page. Each page is drawn as a form XObject holding its
     * content, so text stays text and pictures keep their pixels; the looks of its comments and
     * filled-in form fields are drawn into it first. Links, bookmarks, form fields, page labels,
     * tags and attached files are not carried over; the document details, language and layers are.
     * [source] is changed in memory (the looks drawn in) and must stay open until [dest] is saved.
     */
    fun impose(source: PDDocument, dest: PDDocument, perSheet: Int, paper: PageSize?, border: Boolean, check: () -> Unit) {
        // For every page before the first is copied: drawing a look adds it to its page's
        // resources, and the copier copies a dictionary once, the first time it meets it, so
        // resources shared by several pages would otherwise be copied without later pages' looks.
        Redactor.drawAnnotationsIntoPages(source, check)
        val pages = source.pages.toList()
        if (pages.isEmpty()) throw IOException("This PDF has no pages")
        val frames = pages.map { it.frame() }
        val sizes = frames.map { it.shownWidth to it.shownHeight }
        val grid = SheetLayout.grid(perSheet, paper ?: SheetLayout.paperLike(sizes.first().first, sizes.first().second), sizes)
        // One copier for the whole run, so fonts and pictures pages share are copied once.
        val cloner = PDFCloneUtility(dest)
        dest.version = maxOf(dest.version, source.version)
        // Before any page, so that the layers the pages' resources name are these same copies.
        source.documentCatalog.ocProperties?.let { layers ->
            dest.documentCatalog.ocProperties = PDOptionalContentProperties(cloner.cloneForNewDocument(layers.cosObject) as COSDictionary)
        }
        for (first in pages.indices step perSheet) {
            check()
            val sheet = PDPage(PDRectangle(grid.width, grid.height))
            sheet.resources = PDResources()
            dest.addPage(sheet)
            PDPageContentStream(dest, sheet).use { content ->
                for (index in first until minOf(first + perSheet, pages.size)) {
                    check()
                    val page = pages[index]
                    val frame = frames[index]
                    // A page with no size to draw leaves its cell empty; the next page keeps its own cell.
                    val slot = SheetLayout.slot(grid, index - first, frame.shownWidth, frame.shownHeight) ?: continue
                    val form = pageForm(page, dest, cloner)
                    val m = SheetLayout.matrix(frame, slot)
                    content.saveGraphicsState()
                    content.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
                    content.drawForm(form)
                    content.restoreGraphicsState()
                    if (border) {
                        content.saveGraphicsState()
                        content.setLineWidth(SheetLayout.BORDER_WIDTH)
                        content.setStrokingColor(SheetLayout.BORDER_GRAY)
                        content.addRect(slot.x, slot.y, slot.width, slot.height)
                        content.stroke()
                        content.restoreGraphicsState()
                    }
                }
            }
        }
        dest.documentInformation = PDDocumentInformation(cloner.cloneForNewDocument(source.documentInformation.cosObject) as COSDictionary)
        dest.documentCatalog.language = source.documentCatalog.language
    }

    /**
     * [page] as a form XObject in [dest]: its content, in the page's own coordinates, clipped to its
     * crop box, with copies of the resources it draws with and of its transparency group. No /Matrix:
     * the matrix it is drawn with places it.
     */
    private fun pageForm(page: PDPage, dest: PDDocument, cloner: PDFCloneUtility): PDFormXObject {
        val form = PDFormXObject(PDStream(dest, page.contents, COSName.FLATE_DECODE))
        // Only what this page draws, as Split PDF copies a page (XObjectCleanup.drawnOnly): PDFBox writes
        // a form's resources inside the form (COSWriter marks them direct), so one list shared by all
        // the pages, as many PDF makers write it, with every page's picture in it, would be written
        // again for each page.
        form.resources = page.resources
            ?.let { PDResources(cloner.cloneForNewDocument(XObjectCleanup.drawnOnly(page, it.cosObject)) as COSDictionary) }
            ?: PDResources()
        // Clips the page to what it shows.
        form.bBox = page.cropBox
        page.cosObject.getDictionaryObject(COSName.GROUP)?.let { form.cosObject.setItem(COSName.GROUP, cloner.cloneForNewDocument(it)) }
        return form
    }
}
