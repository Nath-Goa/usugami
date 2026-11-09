package io.github.nath_goa.usugami.data.office

import android.content.Context
import android.net.Uri
import android.os.Parcelable
import android.provider.OpenableColumns
import io.github.nath_goa.usugami.core.FileNames
import io.github.nath_goa.usugami.core.OfficeContainer
import io.github.nath_goa.usugami.core.OfficeFileType
import io.github.nath_goa.usugami.core.PageSize
import io.github.nath_goa.usugami.data.PdfCompression
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.data.moveCopy
import kotlinx.parcelize.Parcelize
import java.io.File
import java.io.IOException

/** The kinds of document Usugami converts: three Office formats, and EPUB e-books. New kinds go last, keeping the others' places. */
enum class OfficeKind(val extension: String, val fallbackName: String) {
    WORD("docx", "Document"),
    POWERPOINT("pptx", "Presentation"),
    EPUB("epub", "Book"),
    EXCEL("xlsx", "Spreadsheet"),
    ;

    /**
     * What the file picker offers: the current formats, and the old binary ones too, so that
     * picking one gets a message saying how to convert it rather than nothing.
     */
    val pickerTypes: Array<String>
        get() = when (this) {
            WORD -> arrayOf(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
                "application/vnd.ms-word.document.macroEnabled.12",
                "application/msword",
            )
            POWERPOINT -> arrayOf(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
                "application/vnd.ms-powerpoint.presentation.macroEnabled.12",
                "application/vnd.ms-powerpoint",
            )
            // Some file providers don't know EPUB and call it just "binary data": those files can be
            // picked too, and the content says whether they are e-books.
            EPUB -> arrayOf("application/epub+zip", "application/octet-stream")
            // Workbooks with macros convert too: the cells' values do, and the macros are left alone.
            EXCEL -> arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
                "application/vnd.ms-excel.sheet.macroEnabled.12",
                "application/vnd.ms-excel.template.macroEnabled.12",
                "application/vnd.ms-excel",
            )
        }
}

/** A picked Word, PowerPoint, Excel or EPUB file, copied into the cache. */
@Parcelize
data class OfficeInput(val id: Long, val path: String, val name: String, val kind: OfficeKind) : Parcelable {
    val file: File get() = File(path)
}

/** One of several files to convert at once: [input], into a PDF named [fileName]. */
data class ConvertJob(val input: OfficeInput, val fileName: String)

/** Why a file can't be converted, for a message in the user's language. */
sealed class OfficeFileProblem(message: String) : IOException(message)

/** An old binary .doc, .ppt or .xls file. */
class OfficeLegacyFormat : OfficeFileProblem("Old binary Office format")

/** A .docx, .pptx or .xlsx file protected with a password: Office encrypts the whole file. */
class OfficeEncrypted : OfficeFileProblem("Password-protected Office file")

/** Not a Word document, PowerPoint presentation or Excel workbook (or not the kind asked for), or damaged. */
class OfficeNotRecognized : OfficeFileProblem("Not an Office file of the kind asked for")

/**
 * A presentation with no slides, or only hidden ones; a book with nothing in its reading order; a
 * workbook with nothing on its visible sheets.
 */
class OfficeNothingToConvert : OfficeFileProblem("Nothing to convert")

/** A workbook that would make more pages, or hold more text, than a phone can lay out. */
class OfficeTooLarge : OfficeFileProblem("Too large to convert")

/** A Word document whose text is all kept in another format inside it (a web page or an RTF file), which isn't read. */
class OfficeEmbeddedContent : OfficeFileProblem("Text kept in another format")

/** How far a conversion has got: page, slide or chapter [done] of [total] (unknown for Word), then [compressing]. */
data class OfficeProgress(val done: Int, val total: Int?, val compressing: Boolean = false)

/**
 * Word, PowerPoint, Excel and EPUB to PDF, entirely on the phone: [DocxConverter], [PptxConverter],
 * [XlsxConverter] and [EpubConverter] read the file's XML and draw its pages with Android's PDF
 * backend, then the PDF goes through Compress PDF (Recommended), which stores the pictures as JPEG,
 * as that backend can't. Copies of picked files and working files live in the cache, like the PDF
 * tools' copies.
 */
class OfficeConverter(private val context: Context, private val engine: PdfToolsEngine) {
    private val inputDir = File(context.cacheDir, "office-inputs")
    private val workDir = File(context.cacheDir, "office-work")

    fun delete(input: OfficeInput) {
        input.file.delete()
    }

    fun deleteAllExcept(keep: Collection<OfficeInput>) {
        val names = keep.mapTo(HashSet()) { it.file.name }
        inputDir.listFiles()?.forEach { if (it.name !in names) it.delete() }
    }

    fun clearWork() {
        workDir.deleteRecursively()
    }

    /**
     * Copies the file at [uri] into the cache and checks it is a [kind] file Usugami can read.
     * Throws an [OfficeFileProblem] when it isn't. [check] is called between chunks, and as the file
     * is checked, and throws to stop.
     */
    fun import(uri: Uri, id: Long, kind: OfficeKind, check: () -> Unit = {}): OfficeInput {
        inputDir.mkdirs()
        val file = File(inputDir, "$id.${kind.extension}")
        try {
            val source = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open the file")
            source.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        check()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            verify(file, kind, check)
            return OfficeInput(id, file.path, displayName(uri) ?: "${kind.fallbackName}.${kind.extension}", kind)
        } catch (t: Throwable) {
            file.delete()
            // Only a crafted file nests deeper than the stack goes: it is refused like a damaged one.
            if (t is StackOverflowError) throw OfficeNotRecognized()
            throw t
        }
    }

    /**
     * Takes [file], a [kind] file shared to Usugami and copied in already (see ShareInbox), as
     * [import] takes a picked one: it is moved in as file [id], named [name]. [check] is called as it
     * is checked, and throws to stop.
     */
    fun adopt(file: File, id: Long, kind: OfficeKind, name: String, check: () -> Unit): OfficeInput {
        val target = File(inputDir, "$id.${kind.extension}")
        try {
            moveCopy(file, target)
            verify(target, kind, check)
            return OfficeInput(id, target.path, name, kind)
        } catch (t: Throwable) {
            target.delete()
            file.delete()
            if (t is StackOverflowError) throw OfficeNotRecognized()
            throw t
        }
    }

    /**
     * Throws an [OfficeFileProblem] unless [file] is a [kind] file Usugami can convert. [check] is
     * called as its parts are read, and throws to stop: a crafted file can make them take a while.
     */
    fun verify(file: File, kind: OfficeKind, check: () -> Unit) {
        val header = ByteArray(8)
        val read = file.inputStream().use { input ->
            var total = 0
            while (total < header.size) {
                val count = input.read(header, total, header.size - total)
                if (count < 0) break
                total += count
            }
            total
        }
        if (read < header.size) throw OfficeNotRecognized()
        if (kind == OfficeKind.EPUB) return verifyBook(file, header, check)
        when (OfficeFileType.container(header)) {
            OfficeContainer.COMPOUND -> throw if (file.inputStream().buffered().use(OfficeFileType::isEncryptedPackage)) OfficeEncrypted() else OfficeLegacyFormat()
            OfficeContainer.OTHER -> throw OfficeNotRecognized()
            OfficeContainer.ZIP -> {
                val matches = try {
                    OfficePackage(file, check).use { pkg ->
                        val main = pkg.mainPart() ?: return@use false
                        pkg.xml(main, check)?.use { x ->
                            when (kind) {
                                OfficeKind.WORD -> x.name == "document" && x.ns == Ns.WORD
                                OfficeKind.POWERPOINT -> x.name == "presentation" && x.ns == Ns.SLIDE
                                OfficeKind.EXCEL -> x.name == "workbook" && x.ns == Ns.SHEET
                                OfficeKind.EPUB -> false
                            }
                        } ?: false
                    }
                } catch (e: Exception) {
                    rethrowCancel(e)
                    false
                }
                if (!matches) throw OfficeNotRecognized()
            }
        }
    }

    /** A zip with an EPUB package in it, without DRM. */
    private fun verifyBook(file: File, header: ByteArray, check: () -> Unit) {
        if (OfficeFileType.container(header) != OfficeContainer.ZIP) throw OfficeNotRecognized()
        try {
            EpubArchive(file).use { EpubBook.read(it, check) }
        } catch (e: OfficeFileProblem) {
            throw e
        } catch (e: Exception) {
            rethrowCancel(e)
            throw OfficeNotRecognized()
        }
    }

    /**
     * Converts [input] to a PDF named [fileName] in the PDF tools' output folder. [progress] hears of
     * each page, slide or chapter drawn; [check] is called often and throws to cancel, leaving nothing
     * behind. A book is laid out on A5 pages.
     */
    fun convert(input: OfficeInput, fileName: String, check: () -> Unit, progress: (OfficeProgress) -> Unit): PdfFile =
        convert(input, fileName, PageSize.A5, check, progress)

    /** As [convert], with a book laid out on [bookPage] (A5 or A4). Office files keep their own page size. */
    fun convert(input: OfficeInput, fileName: String, bookPage: PageSize, check: () -> Unit, progress: (OfficeProgress) -> Unit): PdfFile =
        convert(input, fileName, bookPage, SheetOptions(), check, progress)

    /** As [convert], with a workbook's sheets put on pages as [sheets] says. */
    fun convert(input: OfficeInput, fileName: String, bookPage: PageSize, sheets: SheetOptions, check: () -> Unit, progress: (OfficeProgress) -> Unit): PdfFile {
        try {
            return convertWith(input, bookPage, sheets, check, progress) { joined -> engine.compress(joined, PdfCompression.RECOMMENDED, fileName, check) }
        } catch (t: Throwable) {
            engine.clearOutput()
            throw t
        }
    }

    /**
     * Converts each of [jobs] in turn into a PDF of its own, as [convert] does one, named as each asks
     * (cleaned, and told apart when two ask for one name); books are laid out on [bookPage]. [onNext]
     * is told which starts, and [progress] hears of its pages, slides or chapters. One that fails is
     * left out, its error in its place (see [PdfToolsEngine.each]).
     */
    fun convertEach(
        jobs: List<ConvertJob>,
        bookPage: PageSize,
        check: () -> Unit,
        onNext: (Int) -> Unit,
        progress: (OfficeProgress) -> Unit,
    ): List<Result<PdfFile>> = convertEach(jobs, bookPage, SheetOptions(), check, onNext, progress)

    /** As [convertEach], with workbooks' sheets put on pages as [sheets] says. */
    fun convertEach(
        jobs: List<ConvertJob>,
        bookPage: PageSize,
        sheets: SheetOptions,
        check: () -> Unit,
        onNext: (Int) -> Unit,
        progress: (OfficeProgress) -> Unit,
    ): List<Result<PdfFile>> {
        val names = FileNames.uniqueNames(jobs.map { it.fileName }, jobs.firstOrNull()?.input?.kind?.fallbackName ?: "Document")
        return engine.each(jobs.size, check, onNext) { i ->
            val target = engine.outputFile(names[i])
            convertWith(jobs[i].input, bookPage, sheets, check, progress) { joined -> engine.compressTo(joined, PdfCompression.RECOMMENDED, target, check) }
        }
    }

    /**
     * Draws [input]'s pages, joins them and lets [finish] compress the joined PDF into the one it
     * makes. Its working files go, whatever happens.
     */
    private fun convertWith(
        input: OfficeInput,
        bookPage: PageSize,
        sheets: SheetOptions,
        check: () -> Unit,
        progress: (OfficeProgress) -> Unit,
        finish: (PdfInput) -> PdfFile,
    ): PdfFile {
        clearWork()
        workDir.mkdirs()
        try {
            var pages = 0
            var info: BookInfo? = null
            val parts = if (input.kind == OfficeKind.EPUB) {
                EpubArchive(input.file).use { archive ->
                    val book = EpubBook.read(archive, check)
                    PagedPdf(workDir).use { pdf ->
                        val images = OfficeImages(archive::open, pdf, check)
                        info = EpubConverter(archive, book, pdf, images, bookPage, check) { chapter, total ->
                            progress(OfficeProgress(chapter, total))
                        }.convert()
                        pages = pdf.pageCount
                        pdf.finish()
                    }
                }
            } else {
                withReadingStack {
                    OfficePackage(input.file, check).use { pkg ->
                        PagedPdf(workDir).use { pdf ->
                            val images = OfficeImages(pkg::open, pdf, check)
                            pages = when (input.kind) {
                                OfficeKind.WORD -> DocxConverter(pkg, pdf, images, check) { progress(OfficeProgress(it, null)) }.convert()
                                OfficeKind.POWERPOINT -> PptxConverter(pkg, pdf, images, check) { slide, total -> progress(OfficeProgress(slide, total)) }.convert()
                                OfficeKind.EXCEL -> XlsxConverter(pkg, pdf, images, sheets, input.name, check) { page, total -> progress(OfficeProgress(page, total)) }.convert()
                                OfficeKind.EPUB -> 0
                            }
                            pdf.finish()
                        }
                    }
                }
            }
            check()
            progress(OfficeProgress(pages, pages, compressing = true))
            // As the parts are joined (even a single part), the fonts shrink to the glyphs drawn, and a
            // book's bookmarks, title and language go in.
            val bookInfo = info
            val joined = engine.join(parts, File(workDir, "joined.pdf"), check) { doc ->
                // Only Excel's cells draw text narrower or wider than its font.
                if (input.kind == OfficeKind.EXCEL) TextScales.fix(doc, check)
                EmbeddedFonts.shrink(doc, check)
                bookInfo?.applyTo(doc)
            }
            val compressed = finish(PdfInput(input.id, joined.path, input.name, pages))
            return PdfFile(compressed.file, pages, 0)
        } catch (t: Throwable) {
            // Only a crafted file nests deeper than the stack goes: it fails like a damaged one.
            if (t is StackOverflowError) throw IOException("Too deeply nested", t)
            throw t
        } finally {
            clearWork()
        }
    }

    /** The name of the file at [uri], as the app it comes from gives it, or null. */
    fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0).substringAfterLast('/').takeIf { it.isNotBlank() } else null
        }
    } catch (_: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
}
