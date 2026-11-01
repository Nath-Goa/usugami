package io.github.nath_goa.usugami.data.office

import android.content.Context
import android.net.Uri
import android.os.Parcelable
import android.provider.OpenableColumns
import io.github.nath_goa.usugami.core.OfficeContainer
import io.github.nath_goa.usugami.core.OfficeFileType
import io.github.nath_goa.usugami.core.PageSize
import io.github.nath_goa.usugami.data.PdfCompression
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfToolsEngine
import kotlinx.parcelize.Parcelize
import java.io.File
import java.io.IOException

/** The kinds of document Usugami converts: two Office formats, and EPUB e-books. */
enum class OfficeKind(val extension: String, val fallbackName: String) {
    WORD("docx", "Document"),
    POWERPOINT("pptx", "Presentation"),
    EPUB("epub", "Book"),
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
        }
}

/** A picked Word, PowerPoint or EPUB file, copied into the cache. */
@Parcelize
data class OfficeInput(val id: Long, val path: String, val name: String, val kind: OfficeKind) : Parcelable {
    val file: File get() = File(path)
}

/** Why a file can't be converted, for a message in the user's language. */
sealed class OfficeFileProblem(message: String) : IOException(message)

/** An old binary .doc or .ppt file. */
class OfficeLegacyFormat : OfficeFileProblem("Old binary Office format")

/** A .docx or .pptx file protected with a password: Office encrypts the whole file. */
class OfficeEncrypted : OfficeFileProblem("Password-protected Office file")

/** Not a Word document or PowerPoint presentation (or not the kind asked for), or damaged. */
class OfficeNotRecognized : OfficeFileProblem("Not a Word or PowerPoint file")

/** A presentation with no slides, or only hidden ones; a book with nothing in its reading order. */
class OfficeNothingToConvert : OfficeFileProblem("No slides to convert")

/** How far a conversion has got: page, slide or chapter [done] of [total] (unknown for Word), then [compressing]. */
data class OfficeProgress(val done: Int, val total: Int?, val compressing: Boolean = false)

/**
 * Word, PowerPoint and EPUB to PDF, entirely on the phone: [DocxConverter], [PptxConverter] and
 * [EpubConverter] read the file's XML and draw its pages with Android's PDF backend, then the PDF
 * goes through Compress PDF (Recommended), which stores the pictures as JPEG, as that backend can't.
 * Copies of picked files and working files live in the cache, like the PDF tools' copies.
 */
class OfficeConverter(private val context: Context, private val engine: PdfToolsEngine) {
    private val inputDir = File(context.cacheDir, "office-inputs")
    private val workDir = File(context.cacheDir, "office-work")

    fun delete(input: OfficeInput) {
        input.file.delete()
    }

    fun deleteAllExcept(keep: OfficeInput?) {
        inputDir.listFiles()?.forEach { if (it.name != keep?.file?.name) it.delete() }
    }

    fun clearWork() {
        workDir.deleteRecursively()
    }

    /**
     * Copies the file at [uri] into the cache and checks it is a [kind] file Usugami can read.
     * Throws an [OfficeFileProblem] when it isn't. [check] is called between chunks and throws to stop.
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
            verify(file, kind)
            return OfficeInput(id, file.path, displayName(uri) ?: "${kind.fallbackName}.${kind.extension}", kind)
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
    }

    /** Throws an [OfficeFileProblem] unless [file] is a [kind] file Usugami can convert. */
    fun verify(file: File, kind: OfficeKind) {
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
        if (kind == OfficeKind.EPUB) return verifyBook(file, header)
        when (OfficeFileType.container(header)) {
            OfficeContainer.COMPOUND -> throw if (file.inputStream().buffered().use(OfficeFileType::isEncryptedPackage)) OfficeEncrypted() else OfficeLegacyFormat()
            OfficeContainer.OTHER -> throw OfficeNotRecognized()
            OfficeContainer.ZIP -> {
                val matches = try {
                    OfficePackage(file).use { pkg ->
                        val main = pkg.mainPart() ?: return@use false
                        pkg.xml(main) {}?.use { x ->
                            when (kind) {
                                OfficeKind.WORD -> x.name == "document" && x.ns == Ns.WORD
                                OfficeKind.POWERPOINT -> x.name == "presentation" && x.ns == Ns.SLIDE
                                OfficeKind.EPUB -> false
                            }
                        } ?: false
                    }
                } catch (_: Exception) {
                    false
                }
                if (!matches) throw OfficeNotRecognized()
            }
        }
    }

    /** A zip with an EPUB package in it, without DRM. */
    private fun verifyBook(file: File, header: ByteArray) {
        if (OfficeFileType.container(header) != OfficeContainer.ZIP) throw OfficeNotRecognized()
        try {
            EpubArchive(file).use { EpubBook.read(it) {} }
        } catch (e: OfficeFileProblem) {
            throw e
        } catch (_: Exception) {
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
    fun convert(input: OfficeInput, fileName: String, bookPage: PageSize, check: () -> Unit, progress: (OfficeProgress) -> Unit): PdfFile {
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
                OfficePackage(input.file).use { pkg ->
                    PagedPdf(workDir).use { pdf ->
                        val images = OfficeImages(pkg::open, pdf, check)
                        pages = when (input.kind) {
                            OfficeKind.WORD -> DocxConverter(pkg, pdf, images, check) { progress(OfficeProgress(it, null)) }.convert()
                            OfficeKind.POWERPOINT -> PptxConverter(pkg, pdf, images, check) { slide, total -> progress(OfficeProgress(slide, total)) }.convert()
                            OfficeKind.EPUB -> 0
                        }
                        pdf.finish()
                    }
                }
            }
            check()
            progress(OfficeProgress(pages, pages, compressing = true))
            // As the parts are joined (even a single part), the fonts shrink to the glyphs drawn, and a
            // book's bookmarks, title and language go in.
            val bookInfo = info
            val joined = engine.join(parts, File(workDir, "joined.pdf"), check) { doc ->
                EmbeddedFonts.shrink(doc, check)
                bookInfo?.applyTo(doc)
            }
            val compressed = engine.compress(PdfInput(input.id, joined.path, input.name, pages), PdfCompression.RECOMMENDED, fileName, check)
            return PdfFile(compressed.file, pages, 0)
        } catch (t: Throwable) {
            engine.clearOutput()
            throw t
        } finally {
            clearWork()
        }
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0).substringAfterLast('/').takeIf { it.isNotBlank() } else null
        }
    } catch (_: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
}
