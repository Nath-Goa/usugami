package io.github.nath_goa.usugami.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Parcelable
import android.provider.OpenableColumns
import io.github.nath_goa.usugami.core.Compressor
import io.github.nath_goa.usugami.core.FileNames
import io.github.nath_goa.usugami.core.PageFrame
import io.github.nath_goa.usugami.core.PageNumberPosition
import io.github.nath_goa.usugami.core.PageNumberSize
import io.github.nath_goa.usugami.core.PageItem
import io.github.nath_goa.usugami.core.PageNumbers
import io.github.nath_goa.usugami.core.RedactBox
import io.github.nath_goa.usugami.core.SignatureLayout
import io.github.nath_goa.usugami.core.SignaturePlacement
import io.github.nath_goa.usugami.core.TextItem
import io.github.nath_goa.usugami.core.WatermarkLayout
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.parcelize.Parcelize

@Parcelize
data class PdfInput(val id: Long, val path: String, val name: String, val pageCount: Int, val password: String = "") : Parcelable {
    val file: File get() = File(path)
}

/**
 * A page in a list of pages to save: page [page] (zero-based) of the PDF [inputId], turned [rotation]
 * degrees more than it is. Reorganize PDF also has blank pages: [inputId] is then [BLANK], [page] a
 * number that tells them apart, and [width] and [height] their size in points.
 */
@Parcelize
data class PdfPageRef(
    val inputId: Long,
    val page: Int,
    val rotation: Int = 0,
    val width: Float = 0f,
    val height: Float = 0f,
) : Parcelable {
    val isBlank: Boolean get() = inputId == BLANK

    /** Tells pages apart in lists, whatever their rotation. */
    val key: String get() = "$inputId:$page"

    companion object {
        const val BLANK = -1L

        fun blank(number: Int, width: Float, height: Float) = PdfPageRef(BLANK, number, 0, width, height)
    }
}

enum class PdfCompression(val dpi: Int, val quality: Float) {
    LESS(200, 0.85f), RECOMMENDED(150, 0.68f), EXTREME(100, 0.48f)
}

/** One of several PDFs to compress at once: [input] at [level], into a PDF named [fileName]. */
data class CompressJob(val input: PdfInput, val level: PdfCompression, val fileName: String)

/** One of several PDFs a tool makes at once with the same choices: of [input], named [fileName]. */
data class ToolJob(val input: PdfInput, val fileName: String)

/** One of several PDFs to number at once: [pages] (zero-based) of [input] get theirs, into [fileName]. */
data class NumberingJob(val input: PdfInput, val pages: List<Int>, val fileName: String)

/** Every page of a PDF was to be left without a number, so there is nothing to number. */
class NothingToNumber : IllegalArgumentException("Every page is skipped")

class PdfPasswordRequired(val input: PdfInput) : IOException("Password required")

/** The password given to [PdfToolsEngine.unlock] didn't open the PDF. */
class PdfWrongPassword : IOException("Wrong password")

/** The PDF is encrypted for a certificate (digital ID) rather than with a password; Usugami can't open those. */
class PdfCertificateEncrypted(cause: Throwable) : IOException("Certificate-encrypted PDFs aren't supported", cause)

/**
 * Flatten PDF's result: [droppedDynamicXfa] says a dynamic XFA form was removed, and [keptFields] is
 * how many form fields were left fillable because their text couldn't be drawn (see [PdfToolsEngine.flatten]).
 */
class FlattenResult(val file: PdfFile, val droppedDynamicXfa: Boolean, val keptFields: Int)

/**
 * Split, merge, compress, rotate, delete pages, reorganize, flatten, page numbers, watermarks,
 * signatures, Edit PDF's additions and redaction. Input copies, results and PDFBox's scratch files all live in
 * the cache, so the system can reclaim them (and "Clear cache" removes them); callers must cope with
 * a file that has gone.
 */
class PdfToolsEngine(private val context: Context) {
    private val inputDir = File(context.cacheDir, "pdf-inputs")
    private val outputDir = File(context.cacheDir, "output/tools")
    private val scratchDir = File(context.cacheDir, "pdfbox")

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    // The folder is created each time: the system may have cleared the cache since the last run.
    private fun memory() = pdfScratch(scratchDir.apply { mkdirs() })

    /** Deletes the PDFs made before, but [keep]: a result showing again after Android restarted Usugami. */
    fun clearOutput(keep: Collection<File> = emptyList()) { outputDir.deleteAllBut(keep, context) }

    /** Where a ZIP file of the results is made, beside them, so it goes when they do. */
    val zipDir get() = File(outputDir, "zip")

    /** Scratch files are deleted when PDFBox closes a document, but not when the app is killed mid-run. */
    fun clearScratch() { scratchDir.deleteRecursively() }

    fun delete(input: PdfInput) {
        input.file.delete()
        viewFile(input).delete()
    }

    fun deleteAllExcept(keep: Collection<PdfInput>) {
        val names = keep.mapTo(HashSet()) { it.file.name }
        inputDir.listFiles()?.forEach { if (it.name !in names) it.delete() }
    }

    /**
     * A local copy keeps access after the system document picker's grant expires. [check] is called
     * between chunks of the copy and throws to stop it: a large PDF from a cloud provider can take a while.
     */
    fun import(uri: Uri, id: Long, password: String = "", check: () -> Unit = {}): PdfInput {
        inputDir.mkdirs()
        val file = File(inputDir, "$id.pdf")
        val inputInfo = PdfInput(id, file.path, displayName(uri) ?: "Document.pdf", 0, password)
        // A view made for an earlier copy with this number would show the wrong PDF.
        viewFile(inputInfo).delete()
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open PDF")
            input.use { source ->
                file.outputStream().use { target ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        check()
                        val read = source.read(buffer)
                        if (read < 0) break
                        target.write(buffer, 0, read)
                    }
                }
            }
            if (!hasPdfHeader(file)) throw IOException("This file is not a PDF")
            return inspect(inputInfo, password)
        } catch (e: InvalidPasswordException) {
            // The copy is kept for the password dialog.
            throw PdfPasswordRequired(inputInfo)
        } catch (t: Throwable) {
            // Errors too (out of memory, a PDFBox class that fails to load): never leave the copy behind.
            file.delete()
            throw t
        }
    }

    /**
     * Takes [file], a PDF shared to Usugami and copied in already (see [ShareInbox]), as [import]
     * takes a picked one: it is moved in as PDF [id], named [name].
     */
    fun adopt(file: File, id: Long, name: String): PdfInput {
        val target = File(inputDir, "$id.pdf")
        val inputInfo = PdfInput(id, target.path, name, 0)
        viewFile(inputInfo).delete()
        try {
            moveCopy(file, target)
            if (!hasPdfHeader(target)) throw IOException("This file is not a PDF")
            return inspect(inputInfo, "")
        } catch (e: InvalidPasswordException) {
            // The copy is kept for the password dialog.
            throw PdfPasswordRequired(inputInfo)
        } catch (t: Throwable) {
            target.delete()
            file.delete()
            throw t
        }
    }

    /**
     * Reads [input]'s pages with [password]. A damaged page tree is mended (see [repairPageTree]) and
     * the copy saved again, without its password, so that every reader of it finds the same pages:
     * the tools, and Android's renderer, which draws the previews and Redact PDF's pictures.
     */
    fun inspect(input: PdfInput, password: String): PdfInput {
        // Written whole before it takes the copy's place, so a run cut short leaves the copy as it was.
        val mended = File(inputDir, "${input.id}-mended.tmp")
        try {
            val (pageCount, repaired) = load(input.file, password).use { doc ->
                // Every page found once, as the tools find them: a page tree nested deeper than PDFBox
                // goes (it calls itself for each level) fails here, as a damaged PDF does, not later.
                val changed = try {
                    repairPageTree(doc).also { doc.pages.count() }
                } catch (e: StackOverflowError) {
                    throw tooDeep(e)
                }
                if (doc.numberOfPages == 0) throw IOException("This PDF has no pages")
                if (changed) {
                    doc.isAllSecurityToBeRemoved = true
                    doc.save(mended)
                }
                doc.numberOfPages to changed
            }
            if (repaired && !mended.renameTo(input.file)) throw IOException("Couldn't keep the mended copy")
            return input.copy(pageCount = pageCount, password = password)
        } finally {
            mended.delete()
        }
    }

    /** Opens a copy that [import] found locked; throws [PdfWrongPassword] if [password] doesn't fit. */
    fun unlock(input: PdfInput, password: String): PdfInput = try {
        inspect(input, password)
    } catch (_: InvalidPasswordException) {
        throw PdfWrongPassword()
    }

    fun split(input: PdfInput, groups: List<List<Int>>, baseName: String, check: () -> Unit = {}): List<PdfFile> {
        require(groups.isNotEmpty() && groups.all { it.isNotEmpty() })
        clearOutput()
        outputDir.mkdirs()
        // A long name is cut the same for every part, so their names begin alike, and their numbers
        // and pages stay whole.
        val names = FileNames.withSuffixes(
            baseName,
            if (groups.size == 1) listOf("") else groups.mapIndexed(::partSuffix),
            "Split",
        )
        val taken = HashSet<String>()
        try {
            load(input.file, input.password).use { source ->
                // Read once for all the parts: finding a page in the page tree, and what each part
                // must be cut off from.
                val sourcePages = source.pages.toList()
                val cutFrom = PageCleanup.Source(source, sourcePages)
                return groups.mapIndexed { index, pages ->
                    check()
                    require(pages.all { it in sourcePages.indices })
                    val name = FileNames.unique(names[index], taken)
                    taken += name
                    val target = File(outputDir, "$name.pdf")
                    PDDocument(memory()).use { dest ->
                        // Each page's copy in the part, by the page, for the links between them.
                        val copies = IdentityHashMap<COSDictionary, COSDictionary>()
                        for (pageIndex in pages) {
                            check()
                            val page = sourcePages[pageIndex]
                            val imported = dest.importPage(page)
                            copies[page.cosObject] = imported.cosObject
                            // Its resources, also when inherited, listing only the pictures and forms
                            // it draws: pages often share one list that holds every page's.
                            page.resources?.let { imported.resources = PDResources(XObjectCleanup.drawnOnly(page, it.cosObject)) }
                        }
                        // The copies' links, comments and form fields still lead to the source's pages,
                        // which would bring the whole source into the part. Links to the part's own
                        // pages lead to their copies instead; the others are cut.
                        PageCleanup.keepLinksWithin(dest, cutFrom, copies)
                        PageCleanup.dropSourcePages(dest, cutFrom, check)
                        dest.save(target)
                    }
                    PdfFile(target, pages.size, 0)
                }
            }
        } catch (t: Throwable) {
            clearOutput()
            throw t
        }
    }

    /** [pages] null means concatenate complete documents in [inputs] order. */
    fun merge(inputs: List<PdfInput>, pages: List<PdfPageRef>?, fileName: String, check: () -> Unit = {}): PdfFile {
        require(inputs.size >= 2)
        clearOutput()
        outputDir.mkdirs()
        val target = File(outputDir, "${FileNames.sanitize(fileName, "Merged")}.pdf")
        try {
            PDDocument(memory()).use { dest ->
                val offsets = HashMap<Long, Int>()
                val merger = PDFMergerUtility()
                // Each PDF's own page labels, read before it is joined, and given back once the pages
                // are in place: joining leaves them wrong, numbering the pages of one without labels
                // on from the PDF before it, or leaving those before the first labels without any.
                val labels = IdentityHashMap<COSDictionary, PageLabeling.Label>()
                for (input in inputs) {
                    check()
                    val start = dest.numberOfPages
                    offsets[input.id] = start
                    load(input.file, input.password).use { source ->
                        val own = PageLabeling.of(source)
                        merger.appendDocument(dest, source)
                        if (own != null) labels += PageLabeling.byPage((start until dest.numberOfPages).map(dest::getPage), own)
                    }
                }
                if (pages != null) {
                    require(pages.isNotEmpty())
                    val originals = (0 until dest.numberOfPages).map(dest::getPage)
                    repeat(dest.numberOfPages) { dest.removePage(0) }
                    for (ref in pages) {
                        check()
                        val input = inputs.first { it.id == ref.inputId }
                        require(ref.page in 0 until input.pageCount)
                        val page = originals[offsets.getValue(ref.inputId) + ref.page]
                        page.rotation = Math.floorMod(page.rotation + ref.rotation, 360)
                        dest.addPage(page)
                    }
                    // Bookmarks, links and form fields of the pages left out mustn't carry them into the file.
                    PageCleanup.dropRemovedPages(dest, originals, check)
                }
                PageLabeling.restore(dest, labels)
                dest.save(target)
                return PdfFile(target, dest.numberOfPages, 0)
            }
        } catch (t: Throwable) {
            clearOutput()
            throw t
        }
    }

    /**
     * Joins the PDFs in [files] into [target], in order, letting [edit] add to the whole (such as
     * bookmarks) before it is saved. For PDFs Usugami made itself in parts.
     */
    fun join(files: List<File>, target: File, check: () -> Unit = {}, edit: (PDDocument) -> Unit = {}): File {
        PDDocument(memory()).use { dest ->
            val merger = PDFMergerUtility()
            for (file in files) {
                check()
                load(file, "").use { merger.appendDocument(dest, it) }
            }
            check()
            edit(dest)
            dest.save(target)
        }
        return target
    }

    /**
     * Turns pages clockwise by [rotations] (page index to degrees, a multiple of 90), on top of the
     * turn they already have. Only the pages' /Rotate changes; their content is left as it is.
     */
    fun rotate(input: PdfInput, rotations: Map<Int, Int>, fileName: String, check: () -> Unit = {}): PdfFile =
        edit(input, fileName, "Rotated", check) { doc ->
            for ((index, degrees) in rotations) {
                check()
                require(index in 0 until doc.numberOfPages && degrees % 90 == 0)
                val page = doc.getPage(index)
                page.rotation = Math.floorMod(page.rotation + degrees, 360)
            }
        }

    /**
     * Saves [input] without [pages] (zero-based), which must leave at least one page. Their
     * bookmarks, form fields and links to them go too, so nothing of them stays in the file. The
     * pages left keep their labels (see [PageLabeling]).
     */
    fun deletePages(input: PdfInput, pages: Collection<Int>, fileName: String, check: () -> Unit = {}): PdfFile =
        edit(input, fileName, "Pages_deleted", check) { doc ->
            val doomed = pages.toSortedSet()
            require(doomed.isNotEmpty() && doomed.size < doc.numberOfPages && doomed.all { it in 0 until doc.numberOfPages })
            val labels = PageLabeling.of(doc)?.let { PageLabeling.byPage(doc.pages.toList(), it) }
            val removed = doomed.map(doc::getPage)
            for (page in removed) {
                check()
                doc.removePage(page)
            }
            PageCleanup.dropRemovedPages(doc, removed, check)
            labels?.let { PageLabeling.restore(doc, it) }
        }

    /**
     * Saves [main] with the pages in [pages]' order: its own pages, pages of [extras] (other PDFs
     * the user added) and blank pages, each turned as it says. Pages left out are dropped as
     * [deletePages] drops them. [main]'s bookmarks, form, tags and document details are kept; an
     * extra PDF's come along with its pages, as in Merge. Each page keeps its label.
     */
    fun reorganize(main: PdfInput, extras: List<PdfInput>, pages: List<PdfPageRef>, fileName: String, check: () -> Unit = {}): PdfFile =
        edit(main, fileName, "Reorganized", check) { doc ->
            require(pages.isNotEmpty() && pages.map { it.key }.toSet().size == pages.size)
            val sources = HashMap<Long, List<PDPage>>()
            sources[main.id] = doc.pages.toList()
            // Each PDF's own page labels, read before an extra one is joined (see merge).
            val labels = PageLabeling.of(doc)?.let { PageLabeling.byPage(doc.pages.toList(), it) }
            val merger = PDFMergerUtility()
            for (extra in extras) {
                if (extra.id in sources || pages.none { it.inputId == extra.id }) continue
                check()
                val start = doc.numberOfPages
                load(extra.file, extra.password).use { source ->
                    val own = PageLabeling.of(source)
                    merger.appendDocument(doc, source)
                    sources[extra.id] = (start until doc.numberOfPages).map(doc::getPage)
                    if (own != null) labels?.putAll(PageLabeling.byPage(sources.getValue(extra.id), own))
                }
            }
            val before = doc.pages.toList()
            val order = pages.map { ref ->
                check()
                val page = if (ref.isBlank) {
                    require(ref.width > 0f && ref.height > 0f)
                    PDPage(PDRectangle(ref.width, ref.height)).apply { resources = PDResources() }
                } else {
                    val source = sources.getValue(ref.inputId)
                    require(ref.page in source.indices)
                    source[ref.page]
                }
                page.rotation = Math.floorMod(page.rotation + ref.rotation, 360)
                page
            }
            setPages(doc, order)
            PageCleanup.dropRemovedPages(doc, before, check)
            labels?.let { PageLabeling.restore(doc, it) }
        }

    /**
     * Makes [pages] the document's pages, in order, all straight under the page tree's root. Each
     * first gets the size, crop, turn and resources it inherited where it was, which it would
     * lose there.
     */
    private fun setPages(doc: PDDocument, pages: List<PDPage>) {
        for (page in pages) {
            val media = page.mediaBox
            val crop = page.cropBox
            val rotation = page.rotation
            page.mediaBox = media
            page.cropBox = crop
            page.rotation = rotation
            if (!page.cosObject.containsKey(COSName.RESOURCES)) page.resources = page.resources ?: PDResources()
        }
        val root = doc.pages.cosObject
        val kids = COSArray()
        for (page in pages) {
            page.cosObject.setItem(COSName.PARENT, root)
            kids.add(page.cosObject)
        }
        root.setItem(COSName.KIDS, kids)
        root.setInt(COSName.COUNT, pages.size)
    }

    /**
     * Bakes [input]'s form fields and annotations (comments, highlights, drawings, stamps) into its
     * pages, so nothing can be changed or filled in; links keep working. A dynamic XFA form is
     * dropped, and only its ordinary fields are flattened. A field whose text can't be drawn and
     * that has no look of its own stays a field rather than lose its value. Throws [NothingToFlatten]
     * when there is nothing to flatten, rather than saving the same PDF again.
     */
    fun flatten(input: PdfInput, fileName: String, check: () -> Unit = {}): FlattenResult {
        var flattened: Flattening.Flattened? = null
        val file = edit(input, fileName, "Flattened", check) { doc -> flattened = Flattening.flatten(doc, check) }
        return FlattenResult(file, flattened?.dynamicXfa == true, flattened?.keptFields ?: 0)
    }

    /** Flattens each of [jobs] in turn into a PDF of its own, as [flatten] does one. See [each]. */
    fun flattenEach(jobs: List<ToolJob>, check: () -> Unit = {}, onNext: (Int) -> Unit = {}): List<Result<FlattenResult>> {
        val names = FileNames.uniqueNames(jobs.map { it.fileName }, "Flattened")
        return each(jobs.size, check, onNext) { i ->
            var flattened: Flattening.Flattened? = null
            val file = editInto(jobs[i].input, outputFile(names[i]), check) { doc -> flattened = Flattening.flatten(doc, check) }
            FlattenResult(file, flattened?.dynamicXfa == true, flattened?.keptFields ?: 0)
        }
    }

    /**
     * Page [index] (zero-based) of [input] as shown: its crop box, turned by its rotation. Width
     * then height, in points; A4 if it can't be read.
     */
    fun shownPageSize(input: PdfInput, index: Int): Pair<Float, Float> = try {
        load(input.file, input.password).use { doc ->
            val frame = doc.getPage(index).frame()
            frame.shownWidth to frame.shownHeight
        }
    } catch (_: Exception) {
        PDRectangle.A4.width to PDRectangle.A4.height
    } catch (_: OutOfMemoryError) {
        PDRectangle.A4.width to PDRectangle.A4.height
    } catch (_: StackOverflowError) {
        PDRectangle.A4.width to PDRectangle.A4.height
    }

    /**
     * Writes on each of [pages] (zero-based) its own page number, as text in Helvetica, at
     * [position] on the page as it is shown.
     */
    fun addPageNumbers(
        input: PdfInput,
        pages: List<Int>,
        position: PageNumberPosition,
        size: PageNumberSize,
        fileName: String,
        check: () -> Unit = {},
    ): PdfFile = edit(input, fileName, "Numbered", check) { doc -> drawPageNumbers(doc, pages, position, size, check) }

    /**
     * Numbers each of [jobs] in turn into a PDF of its own, as [addPageNumbers] does one; one whose
     * pages are all skipped fails with [NothingToNumber]. See [each].
     */
    fun addPageNumbersEach(
        jobs: List<NumberingJob>,
        position: PageNumberPosition,
        size: PageNumberSize,
        check: () -> Unit = {},
        onNext: (Int) -> Unit = {},
    ): List<Result<PdfFile>> {
        val names = FileNames.uniqueNames(jobs.map { it.fileName }, "Numbered")
        return each(jobs.size, check, onNext) { i ->
            editInto(jobs[i].input, outputFile(names[i]), check) { doc -> drawPageNumbers(doc, jobs[i].pages, position, size, check) }
        }
    }

    private fun drawPageNumbers(doc: PDDocument, pages: List<Int>, position: PageNumberPosition, size: PageNumberSize, check: () -> Unit) {
        if (pages.isEmpty()) throw NothingToNumber()
        val font = PDType1Font.HELVETICA
        // Digits are as tall as capitals.
        val digitHeight = (font.fontDescriptor?.capHeight?.takeIf { it > 0 } ?: 718f) / 1000 * size.points
        for (index in pages) {
            check()
            require(index in 0 until doc.numberOfPages)
            val page = doc.getPage(index)
            val frame = page.frame()
            val label = (index + 1).toString()
            val width = font.getStringWidth(label) / 1000 * size.points
            val (x, y) = PageNumbers.origin(position, frame.shownWidth, frame.shownHeight, width, digitHeight)
            // Added after the page's own drawing, which is wrapped in q/Q, so whatever state that
            // leaves behind can't move or recolour the number.
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { content ->
                content.transform(frame.toMatrix())
                content.setNonStrokingColor(0f)
                content.beginText()
                content.setFont(font, size.points)
                content.newLineAtOffset(x, y)
                content.showText(label)
                content.endText()
            }
        }
    }

    /**
     * Draws [text] large, diagonally across the middle of every page as it is shown, in [color] and
     * with [opacity]. Throws [WatermarkFontMissing] if no font on the phone can draw [text].
     */
    fun addWatermark(
        input: PdfInput,
        text: String,
        color: WatermarkColor,
        opacity: WatermarkOpacity,
        fileName: String,
        check: () -> Unit = {},
    ): PdfFile {
        val stamp = WatermarkText.render(text.trim(), color.argb)
        // Kept open until the result is saved: the text's font is copied from it.
        return PDDocument.load(stamp).use { stampDoc ->
            edit(input, fileName, "Watermarked", check) { doc -> drawWatermark(doc, stampDoc, opacity, check) }
        }
    }

    /**
     * Puts the same watermark on each of [jobs] in turn, into a PDF of its own, as [addWatermark]
     * does on one. The text is drawn once for them all: if no font can draw it, it throws
     * [WatermarkFontMissing] before any PDF is made. See [each].
     */
    fun addWatermarkEach(
        jobs: List<ToolJob>,
        text: String,
        color: WatermarkColor,
        opacity: WatermarkOpacity,
        check: () -> Unit = {},
        onNext: (Int) -> Unit = {},
    ): List<Result<PdfFile>> {
        val stamp = WatermarkText.render(text.trim(), color.argb)
        val names = FileNames.uniqueNames(jobs.map { it.fileName }, "Watermarked")
        // Kept open until the last PDF is saved: the text's font is copied from it into each.
        return PDDocument.load(stamp).use { stampDoc ->
            each(jobs.size, check, onNext) { i ->
                editInto(jobs[i].input, outputFile(names[i]), check) { doc -> drawWatermark(doc, stampDoc, opacity, check) }
            }
        }
    }

    /** Draws [stampDoc]'s text large, diagonally across the middle of each of [doc]'s pages as shown. */
    private fun drawWatermark(doc: PDDocument, stampDoc: PDDocument, opacity: WatermarkOpacity, check: () -> Unit) {
        val form = LayerUtility(doc).importPageAsForm(stampDoc, 0)
        // As a transparency group, the opacity below applies to the watermark as a whole, even
        // if its own drawing sets an opacity.
        form.cosObject.setItem(COSName.GROUP, COSDictionary().apply { setItem(COSName.S, COSName.TRANSPARENCY) })
        val box = form.bBox
        val state = PDExtendedGraphicsState().apply {
            nonStrokingAlphaConstant = opacity.alpha
            strokingAlphaConstant = opacity.alpha
        }
        for (page in doc.pages) {
            check()
            val frame = page.frame()
            val place = WatermarkLayout.place(frame.shownWidth, frame.shownHeight, box.width, box.height)
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { content ->
                content.transform(frame.toMatrix())
                content.setGraphicsStateParameters(state)
                content.transform(Matrix.getTranslateInstance(place.centerX, place.centerY))
                content.transform(Matrix.getRotateInstance(Math.toRadians(place.degrees.toDouble()), 0f, 0f))
                content.transform(Matrix.getScaleInstance(place.scale, place.scale))
                content.transform(Matrix.getTranslateInstance(-box.lowerLeftX - box.width / 2, -box.lowerLeftY - box.height / 2))
                content.drawForm(form)
            }
        }
    }

    /**
     * Draws [signature] on page [pageIndex] (zero-based) where [placement] puts it on the page as
     * shown, or in its lower right when [placement] is null. It goes in as an image with
     * transparency, trimmed to the ink, at print resolution.
     */
    fun sign(
        input: PdfInput,
        signature: Signature,
        pageIndex: Int,
        placement: SignaturePlacement?,
        fileName: String,
        check: () -> Unit = {},
    ): PdfFile = edit(input, fileName, "Signed", check) { doc ->
        require(pageIndex in 0 until doc.numberOfPages && !signature.isEmpty)
        val page = doc.getPage(pageIndex)
        val frame = page.frame()
        val width = frame.shownWidth
        val height = frame.shownHeight
        val aspect = signature.aspect
        val place = SignatureLayout.clamp(placement ?: SignatureLayout.default(width, height, aspect), width, height, aspect)
        val area = SignatureLayout.pdfArea(place, width, height, aspect)
        val bitmap = signature.render(SignatureLayout.pixels(area.width, area.height).first)
        val image = try {
            LosslessFactory.createFromImage(doc, bitmap)
        } finally {
            bitmap.recycle()
        }
        check()
        // It goes over the form fields and comments under it, as the preview shows it.
        Flattening.bakeUnder(doc, page, listOf(Flattening.pageArea(frame.matrix, area.x, area.y, area.width, area.height)), check)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { content ->
            content.transform(frame.toMatrix())
            content.drawImage(image, area.x, area.y, area.width, area.height)
        }
    }

    /** Where [input]'s view (see [previewView]) is kept, beside the copy. */
    private fun viewFile(input: PdfInput) = File(inputDir, "${input.id}-view.pdf")

    /**
     * The PDF the tools' previews draw, and Redact PDF searches and draws its pictures from: [input]
     * itself, or, when it has a password or annotations, a copy without the password in which each
     * visible annotation is drawn into its page, as viewers show it (Android's renderer can't open a
     * PDF with a password, and leaves annotations out). Made once and kept beside the copy until the
     * PDF is put away.
     */
    fun previewView(input: PdfInput, check: () -> Unit = {}): PdfInput {
        val file = viewFile(input)
        if (file.isFile) return input.copy(path = file.path, password = "")
        load(input.file, input.password).use { doc ->
            val drawn = Redactor.drawAnnotationsIntoPages(doc, check)
            if (!drawn && input.password.isEmpty()) return input
            check()
            doc.isAllSecurityToBeRemoved = true
            // Written whole before it takes the view's name, so a run cut short leaves no half view.
            val partial = File(inputDir, "${input.id}-view.tmp")
            try {
                doc.save(partial)
                if (!partial.renameTo(file)) throw IOException("Couldn't keep the view")
            } finally {
                partial.delete()
            }
        }
        return input.copy(path = file.path, password = "")
    }

    /**
     * Every place [query] is found in [input]'s text, as boxes over each line of it (see [Redactor.find]),
     * and in its comments, as a box over each comment (see [Redactor.findInComments]). [view] is
     * its [previewView], if already made.
     */
    fun findText(input: PdfInput, query: String, view: PdfInput? = null, check: () -> Unit = {}): List<List<RedactBox>> {
        val view = view ?: previewView(input, check)
        val inText = load(view.file, view.password).use { doc -> Redactor.find(doc, query, check) }
        // The view has the comments drawn into the pages, without what they say: that is in the PDF itself.
        val pages = inText.mapTo(HashSet()) { it.first().page }
        val inComments = load(input.file, input.password).use { doc -> Redactor.findInComments(doc, query, pages, check) }
        return (inText + inComments).sortedBy { it.first().page }
    }

    /**
     * Redacts [input]: each page with [boxes] becomes a picture of itself with the boxes solid
     * black, and what else could leak the hidden content is removed (see [Redactor]). [view] is its
     * [previewView], if already made. [searched] is what Find text looked for in it: the form
     * fields that hold it go, though no page shows them (see [Redactor.redact]).
     */
    fun redact(
        input: PdfInput,
        boxes: List<RedactBox>,
        fileName: String,
        view: PdfInput? = null,
        searched: List<String> = emptyList(),
        check: () -> Unit = {},
    ): PdfFile {
        require(boxes.isNotEmpty())
        val view = view ?: previewView(input, check)
        return edit(input, fileName, "Redacted", check) { doc -> Redactor.redact(doc, view.file, boxes, searched, check) }
    }

    /**
     * Draws Edit PDF's [items] (text, strokes, shapes and pictures) on [input]'s pages, exactly where
     * the preview showed them on each page as shown. Pages without items are left as they are.
     * Throws [WatermarkFontMissing] if no font on the phone can draw some of the text.
     */
    fun editPages(input: PdfInput, items: List<PageItem>, fileName: String, check: () -> Unit = {}): PdfFile {
        require(items.isNotEmpty())
        val texts = items.filterIsInstance<TextItem>()
        val stamps = if (texts.isEmpty()) null else PDDocument.load(PageText.render(texts, check))
        // Kept open until the result is saved: the text's font is copied from it.
        return try {
            edit(input, fileName, "Edited", check) { doc -> PageEditing.draw(doc, items, stamps, check) }
        } finally {
            stamps?.close()
        }
    }

    /** The size of each of [input]'s pages as shown (see [shownPageSize]), in order. */
    fun shownPageSizes(input: PdfInput): List<Pair<Float, Float>> = load(input.file, input.password).use { doc ->
        doc.pages.map { page -> page.frame().let { it.shownWidth to it.shownHeight } }
    }

    /**
     * Opens [input], lets [block] change it, and saves it as [fileName] without a password. [check]
     * is called before saving too, since saving a large PDF takes a while.
     */
    private fun edit(input: PdfInput, fileName: String, fallbackName: String, check: () -> Unit, block: (PDDocument) -> Unit): PdfFile {
        clearOutput()
        outputDir.mkdirs()
        try {
            return editInto(input, outputFile(FileNames.sanitize(fileName, fallbackName)), check, block)
        } catch (t: Throwable) {
            clearOutput()
            throw t
        }
    }

    /** As [edit], into [target] beside the other PDFs made; nothing is left at [target] if it fails. */
    private fun editInto(input: PdfInput, target: File, check: () -> Unit, block: (PDDocument) -> Unit): PdfFile {
        try {
            load(input.file, input.password).use { doc ->
                block(doc)
                check()
                doc.isAllSecurityToBeRemoved = true
                doc.save(target)
                return PdfFile(target, doc.numberOfPages, 0)
            }
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
    }

    /** Where a PDF named [name] (a name already cleaned) is made, in the output folder. */
    internal fun outputFile(name: String): File = File(outputDir, "$name.pdf")

    private fun PageFrame.toMatrix(): Matrix = matrix.let { m -> Matrix(m[0], m[1], m[2], m[3], m[4], m[5]) }

    /** Re-encodes only opaque image XObjects; text, vectors, and untouched images stay as PDF objects. */
    fun compress(input: PdfInput, level: PdfCompression, fileName: String, check: () -> Unit = {}): PdfFile {
        clearOutput()
        outputDir.mkdirs()
        val target = File(outputDir, "${FileNames.sanitize(fileName, "Compressed")}.pdf")
        try {
            return compressTo(input, level, target, check)
        } catch (t: Throwable) {
            clearOutput()
            throw t
        }
    }

    /**
     * Compresses each of [jobs] in turn into a PDF of its own, as [compress] does one, named as each
     * asks (cleaned, and told apart when two ask for one name). See [each].
     */
    fun compressEach(jobs: List<CompressJob>, check: () -> Unit = {}, onNext: (Int) -> Unit = {}): List<Result<PdfFile>> {
        val names = FileNames.uniqueNames(jobs.map { it.fileName }, "Compressed")
        return each(jobs.size, check, onNext) { i -> compressTo(jobs[i].input, jobs[i].level, outputFile(names[i]), check) }
    }

    /**
     * Makes [count] PDFs one after another, in place of the PDFs made before: [make] makes number i
     * into the output folder (see [outputFile]), after [onNext] is told it starts. One that fails is
     * left out, its error in its place, and the others are still made. Cancelled through [check], it
     * leaves none behind.
     */
    fun <T> each(count: Int, check: () -> Unit, onNext: (Int) -> Unit, make: (Int) -> T): List<Result<T>> {
        clearOutput()
        outputDir.mkdirs()
        try {
            return List(count) { i ->
                check()
                onNext(i)
                try {
                    Result.success(make(i))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                } catch (e: OutOfMemoryError) {
                    // One PDF too big for memory doesn't stop the others: its objects are freed with it.
                    Result.failure(e)
                } catch (e: LinkageError) {
                    Result.failure(e)
                } catch (e: StackOverflowError) {
                    // Nested deeper than PDFBox goes when it copies or saves: as a damaged PDF.
                    Result.failure(IOException("Too deeply nested", e))
                }
            }
        } catch (t: Throwable) {
            clearOutput()
            throw t
        }
    }

    /**
     * Compresses [input] into [target]. An unprotected PDF that wouldn't get at least 1% smaller is
     * copied as it is instead, and its [PdfFile.keptOriginal] says so. Nothing is left at [target]
     * if it fails.
     */
    internal fun compressTo(input: PdfInput, level: PdfCompression, target: File, check: () -> Unit): PdfFile {
        val candidate = File(outputDir, "candidate.tmp")
        try {
            load(input.file, input.password).use { doc ->
                val maxPageSide = (0 until doc.numberOfPages).maxOf { p ->
                    val box = doc.getPage(p).mediaBox
                    maxOf(box.width, box.height)
                }
                val maxDimension = minOf(4000, (maxPageSide * level.dpi / 72f).toInt().coerceAtLeast(1))
                val seen = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
                val done = IdentityHashMap<COSBase, PDImageXObject?>()
                for (page in doc.pages) {
                    check()
                    compressResources(doc, page.resources, level, maxDimension, seen, done, check)
                }
                doc.isAllSecurityToBeRemoved = true
                doc.save(candidate)
            }
            // A saving under 1% isn't worth a re-encoded copy: the original is kept, as it says.
            val keep = input.password.isEmpty() && Compressor.savedPercent(input.file.length(), candidate.length()) == null
            (if (keep) input.file else candidate).copyTo(target, overwrite = true)
            return PdfFile(target, input.pageCount, input.file.length(), keptOriginal = keep)
        } catch (t: Throwable) {
            target.delete()
            throw t
        } finally {
            candidate.delete()
        }
    }

    private fun compressResources(
        doc: PDDocument,
        resources: PDResources?,
        level: PdfCompression,
        maxDimension: Int,
        seen: MutableSet<COSDictionary>,
        done: MutableMap<COSBase, PDImageXObject?>,
        check: () -> Unit,
    ) {
        if (resources == null || !seen.add(resources.cosObject)) return
        for (name in resources.xObjectNames) {
            check()
            val obj = try { resources.getXObject(name) } catch (_: IOException) { continue }
            when (obj) {
                is PDFormXObject -> compressResources(doc, obj.resources, level, maxDimension, seen, done, check)
                is PDImageXObject -> {
                    // Pages often share one image stream (a logo or background). Encode it once and
                    // reuse the result; decoding it again would hand back PDFBox's cached bitmap,
                    // which was recycled after the first use.
                    val stream = obj.cosObject
                    if (done.containsKey(stream)) {
                        done[stream]?.let { resources.put(name, it) }
                        continue
                    }
                    done[stream] = null
                    if (obj.isStencil || obj.softMask != null || obj.mask != null || obj.colorKeyMask != null) continue
                    if (!canDecodeFaithfully(obj)) continue
                    val originalLength = (obj.cosObject as? COSStream)?.length ?: continue
                    if (originalLength < 2048L || obj.width <= 0 || obj.height <= 0) continue
                    try {
                        val longest = maxOf(obj.width, obj.height)
                        val sample = maxOf(1, (longest + maxDimension - 1) / maxDimension)
                        // BitmapFactory returns null for JPEG streams it cannot decode, so the
                        // result must be treated as nullable.
                        val decoded: Bitmap? = if (obj.suffix == "jpg") {
                            // PDFBox-Android hands a JPEG to BitmapFactory only at full size: asked
                            // for a smaller one, it reads the still-compressed bytes as pixels and
                            // fails. BitmapFactory shrinks it while decoding instead, so even a huge
                            // scan or photo needs little memory. The stream gives the JPEG's bytes,
                            // as PDFBox gives them to BitmapFactory.
                            obj.createInputStream().use { stream ->
                                BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = Integer.highestOneBit(sample) })
                            }
                        } else try {
                            obj.getImage(null, sample)
                        } catch (_: IllegalArgumentException) {
                            // Some PDFBox-Android decoders fail while cropping a subsampled
                            // bitmap whose dimensions are not divisible by the sample factor.
                            // Decode at full size for ordinary images in that case.
                            if (obj.width.toLong() * obj.height > 20_000_000L) continue
                            obj.image
                        }
                        var bitmap: Bitmap = decoded ?: continue
                        try {
                            val scale = minOf(1f, maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height))
                            if (scale < 1f) {
                                val smaller = Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * scale).toInt()), maxOf(1, (bitmap.height * scale).toInt()), true)
                                bitmap.recycle()
                                bitmap = smaller
                            }
                            // Masked images were skipped above, so the image is opaque. PDFBox's raw
                            // decoder still marks its bitmaps as having alpha (gray ones use the gray
                            // value as alpha), which would make JPEGFactory add a bogus soft mask.
                            bitmap.setHasAlpha(false)
                            val replacement = JPEGFactory.createFromImage(doc, bitmap, level.quality)
                            if ((replacement.cosObject as COSStream).length < originalLength) {
                                done[stream] = replacement
                                resources.put(name, replacement)
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    } catch (_: IOException) {
                        // Leave an unsupported image unchanged; other images can still be reduced.
                    } catch (_: IllegalArgumentException) {
                        // Leave an unsupported image unchanged; other images can still be reduced.
                    } catch (_: OutOfMemoryError) {
                        // Leave an image too large to decode unchanged; its buffers are already unreachable.
                    }
                }
            }
        }
    }

    /**
     * JPEGs are decoded by BitmapFactory, which reads them as gray or RGB, and PDFBox-Android's raw
     * decoder only understands 8-bit RGB and gray. Every other color space (CMYK, indexed,
     * separation, DeviceN, Lab...) comes out as wrong or scrambled pixels without an error (a spot
     * color's tint read as gray turns into its negative), so those images are left unchanged.
     */
    private fun canDecodeFaithfully(obj: PDImageXObject): Boolean {
        if (obj.decode != null) return false
        // BitmapFactory reads a JPEG's own bit depth.
        if (obj.suffix != "jpg" && obj.bitsPerComponent != 8) return false
        return when (val colorSpace = obj.cosObject.getDictionaryObject(COSName.COLORSPACE)) {
            COSName.DEVICERGB, COSName.DEVICEGRAY -> true
            is COSArray -> colorSpace.size() == 2 && colorSpace.getObject(0) == COSName.ICCBASED &&
                (colorSpace.getObject(1) as? COSStream)?.getInt(COSName.N) in listOf(1, 3)
            else -> false
        }
    }

    /**
     * Opens [file] with PDFBox. Certificate encryption (Adobe.PubSec) needs BouncyCastle, which isn't
     * bundled: PDFBox then throws an IOException, or would hit a missing class, so both become
     * [PdfCertificateEncrypted]. A PDF nested deeper than PDFBox's reading goes (it reads arrays in
     * arrays by calling itself, without a limit) fails as a damaged one does.
     */
    private fun load(file: File, password: String): PDDocument = try {
        PDDocument.load(file, password, memory())
    } catch (e: InvalidPasswordException) {
        throw e
    } catch (e: Exception) {
        val certificate = try { usesCertificateEncryption(file) } catch (_: IOException) { false }
        if (certificate) throw PdfCertificateEncrypted(e)
        throw e
    } catch (e: LinkageError) {
        throw PdfCertificateEncrypted(e)
    } catch (e: StackOverflowError) {
        throw tooDeep(e)
    }

    /**
     * Mends a page tree that PDFBox would read otherwise than viewers do, and returns whether it
     * changed anything. Viewers walk the tree and take what ends each branch for a page. PDFBox finds
     * a page by the counts the tree gives, lists them with a walk that skips a page without its type,
     * and takes a page out through the part of the tree it says holds it: a tool would then fail on a
     * page, take the wrong one, or Merge's next PDF's page in its place. A page without its type gets
     * it. A tree that miscounts, leads back into itself or reaches a part of itself twice, ends a
     * branch with something other than a page, or whose pages and parts don't name the part that
     * holds them, is laid out again flat with the pages found.
     */
    private fun repairPageTree(doc: PDDocument): Boolean {
        val root = doc.documentCatalog.cosObject.getDictionaryObject(COSName.PAGES) as? COSDictionary ?: return false
        // A lone page in the tree's place, which PDFBox reads as a tree holding it.
        if (root.getCOSName(COSName.TYPE) == COSName.PAGE) return false
        val pages = ArrayList<COSDictionary>()
        val sound = walkPageTree(root, Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>()).apply { add(root) }, pages)
        var changed = false
        for (page in pages) {
            if (page.getCOSName(COSName.TYPE) != null) continue
            page.setItem(COSName.TYPE, COSName.PAGE)
            changed = true
        }
        if (!sound) {
            setPages(doc, pages.map { PDPage(it) })
            changed = true
        }
        return changed
    }

    /**
     * Adds the pages under [node] to [pages] in order, entering each part of the tree once (those in
     * [seen] are left out), and returns whether this part of the tree is sound (see [repairPageTree]).
     */
    private fun walkPageTree(node: COSDictionary, seen: MutableSet<COSDictionary>, pages: MutableList<COSDictionary>): Boolean {
        val first = pages.size
        var sound = true
        val kids = node.getDictionaryObject(COSName.KIDS) as? COSArray ?: COSArray()
        for (i in 0 until kids.size()) {
            val kid = kids.getObject(i) as? COSDictionary ?: continue
            if (kid.getDictionaryObject(COSName.PARENT) !== node) sound = false
            val type = kid.getCOSName(COSName.TYPE)
            when {
                // A part of the tree, as PDFBox tells them from pages.
                type == COSName.PAGES || kid.containsKey(COSName.KIDS) -> if (!seen.add(kid) || !walkPageTree(kid, seen, pages)) sound = false
                type == null || type == COSName.PAGE -> pages += kid
                else -> sound = false
            }
        }
        return sound && node.getInt(COSName.COUNT, -1) == pages.size - first
    }

    /** A PDF nested deeper than PDFBox goes, as a damaged one: only a crafted file nests that deep. */
    private fun tooDeep(e: StackOverflowError) = IOException("Too deeply nested", e)

    /**
     * Whether [file] names the certificate security handler. The encryption dictionary is never
     * inside a compressed object stream, so the name is plain text. Only read after a failed load.
     */
    private fun usesCertificateEncryption(file: File): Boolean {
        val needle = "/Adobe.PubSec".toByteArray()
        val buffer = ByteArray(64 * 1024)
        var carried = 0
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer, carried, buffer.size - carried)
                if (read < 0) return false
                val end = carried + read
                for (start in 0..end - needle.size) {
                    if (needle.indices.all { buffer[start + it] == needle[it] }) return true
                }
                // Keep the tail, in case the name straddles two reads.
                carried = minOf(needle.size - 1, end)
                System.arraycopy(buffer, end - carried, buffer, 0, carried)
            }
        }
    }

    /**
     * Whether "%PDF-" is in the first 1024 bytes, where viewers look for it: a byte order mark or a
     * blank line from the server that made the PDF may come first. Reads them without
     * InputStream.readNBytes, which needs API 33.
     */
    private fun hasPdfHeader(file: File): Boolean {
        val expected = "%PDF-".toByteArray()
        val start = ByteArray(1024)
        val read = file.inputStream().use { input ->
            var total = 0
            while (total < start.size) {
                val count = input.read(start, total, start.size - total)
                if (count < 0) break
                total += count
            }
            total
        }
        return (0..read - expected.size).any { at -> expected.indices.all { start[at + it] == expected[it] } }
    }

    /** The name the file at [uri] is shown under, if its app gives one. */
    internal fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0).substringAfterLast('/').takeIf { it.isNotBlank() } else null
        }
    } catch (_: Exception) { null }

    companion object {
        /**
         * What [split] adds to the name of part [index] (zero-based), which holds [pages]: its number
         * and its first and last pages, counted from 1, as in "_2_4-6".
         */
        fun partSuffix(index: Int, pages: List<Int>) = "_${index + 1}_${pages.first() + 1}-${pages.last() + 1}"
    }
}

/** The page as a viewer shows it: its crop box, turned by its rotation. */
internal fun PDPage.frame(): PageFrame = cropBox.let { box -> PageFrame(box.lowerLeftX, box.lowerLeftY, box.width, box.height, rotation) }

/**
 * PDFBox's room to work on one document: memory first, then files in [dir], up to all but a reserve of
 * the storage free now. A small stream in a PDF can inflate to many gigabytes, which would otherwise
 * fill the phone, failing other apps' writes until PDFBox gives up; past the limit it fails as full
 * storage does (see isOutOfSpace), leaving the reserve free. The storage free now is what counts, not
 * what Android could free by clearing caches: it clears them only for space asked for ahead.
 */
@SuppressLint("UsableSpace")
internal fun pdfScratch(dir: File): MemoryUsageSetting =
    MemoryUsageSetting.setupMixed(SCRATCH_MEMORY, maxOf(SCRATCH_MEMORY, dir.usableSpace - SCRATCH_RESERVE)).setTempDir(dir)

private const val SCRATCH_MEMORY = 24L * 1024 * 1024

/** Left free for the phone and its other apps to go on working; Android warns of low storage at about twice this. */
private const val SCRATCH_RESERVE = 256L * 1024 * 1024
