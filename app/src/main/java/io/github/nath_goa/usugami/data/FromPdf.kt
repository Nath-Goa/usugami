package io.github.nath_goa.usugami.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import io.github.nath_goa.usugami.core.ExportImageFormat
import io.github.nath_goa.usugami.core.ExportImageSize
import io.github.nath_goa.usugami.core.ExportImages
import io.github.nath_goa.usugami.core.ExportText
import io.github.nath_goa.usugami.core.OcrPoint
import io.github.nath_goa.usugami.core.OcrQuad
import io.github.nath_goa.usugami.core.OcrScale
import io.github.nath_goa.usugami.core.OutputType
import io.github.nath_goa.usugami.core.isOutOfSpace
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// PDF to images or text, in the engine: a PDF's pages as JPG or PNG pictures, or its text as a file.

/**
 * How far a run is: [done] pages are finished and the next is being worked on, of [total].
 * [Stage.DOWNLOAD_OCR]: [done] per cent of [total] 100, or [total] 0 while the size isn't known.
 */
data class ExportProgress(val stage: Stage, val done: Int, val total: Int) {
    enum class Stage { IMAGES, TEXT, DOWNLOAD_OCR, READ_SCANS }
}

/** The pictures made, one a page in page order, and the zero-based [reduced] pages that got fewer pixels than the size chosen gives. */
class ImagesOutcome(val files: List<PdfFile>, val reduced: List<Int>)

/** Page [page] (zero-based) couldn't be drawn, not even with fewer pixels. */
class PageNotDrawn(val page: Int, cause: Throwable?) : IOException("Page ${page + 1} can't be drawn", cause)

/**
 * The text file made, and what the notes say of the pages chosen (zero-based, in order):
 * [withoutText], the pages with no text, also after text recognition where it ran; [read], the
 * pages text recognition read; [failed], the pages it failed on or never got to; [formsOrComments],
 * whether a page chosen has form fields or comments whose text is not in the file (text recognition
 * reads them with the page, as it is shown).
 */
class TextOutcome(val file: PdfFile, val withoutText: List<Int>, val read: List<Int>, val failed: List<Int>, val formsOrComments: Boolean)

/** No page chosen has text, not even after text recognition; [recognitionFailed] when it failed on some of them. */
class NoTextFound(val recognitionFailed: Boolean) : Exception("No page has text")

/**
 * The zero-based [pages] of [input] (sorted, each once, at least one) as pictures in [format] at
 * [size], in place of the files made before, named after [typedName] ("Report_p3.jpg"). Each is
 * the page as a viewer shows it: its crop box, turned by its /Rotate, on white, with what its
 * comments, stamps and filled-in fields show (see [PdfToolsEngine.previewView]), without Usugami's
 * own mark. A page too big for [ExportImages.MAX_PIXELS] or [ExportImages.MAX_SIDE], or for the
 * memory there is, gets fewer pixels and is listed in [ImagesOutcome.reduced].
 *
 * [check] is called before each page and throws to stop; [onProgress] is told before each page.
 * Throws [PageNotDrawn] for a page that can't be drawn, and an [IOException] when a file can't be
 * written (the storage full among them, also for the view, see viewOf). If it fails or is stopped,
 * no picture is left behind.
 */
internal fun PdfToolsEngine.pagesToImages(
    input: PdfInput,
    pages: List<Int>,
    format: ExportImageFormat,
    size: ExportImageSize,
    typedName: String,
    check: () -> Unit,
    onProgress: (ExportProgress) -> Unit,
): ImagesOutcome {
    require(pages.isNotEmpty() && pages.first() >= 0 && pages.last() < input.pageCount && pages.zipWithNext().all { (a, b) -> a < b }) {
        "Pages $pages aren't sorted, each once, within the PDF's ${input.pageCount}"
    }
    return newOutput {
        val view = viewOf(input, check)
        check()
        val sizes = shownPageSizes(view)
        val names = ExportImages.names(typedName, pages)
        val files = ArrayList<PdfFile>(pages.size)
        val reduced = ArrayList<Int>()
        ParcelFileDescriptor.open(view.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            // It refuses a PDF it can't open (a password): never one of Usugami's views.
            val renderer = try { PdfRenderer(descriptor) } catch (e: SecurityException) { throw IOException("The PDF renderer refused the PDF", e) }
            renderer.use {
                if (renderer.pageCount != sizes.size || pages.last() >= sizes.size) {
                    throw IOException("The PDF renderer reads ${renderer.pageCount} pages, PDFBox ${sizes.size}")
                }
                for ((done, index) in pages.withIndex()) {
                    check()
                    onProgress(ExportProgress(ExportProgress.Stage.IMAGES, done, pages.size))
                    val target = outputFile(names[done], format.type)
                    val (width, height) = sizes[index]
                    if (drawPage(renderer, index, width, height, format, size, target)) reduced += index
                    files += PdfFile(target, pageCount = 1, originalBytes = 0)
                }
            }
        }
        ImagesOutcome(files, reduced)
    }
}

/**
 * The text of the zero-based [pages] of [input] (sorted, each once, at least one) as one UTF-8 file,
 * in place of the files made before, named after [typedName] ("Report.txt"): each line of a page a
 * line of the file, as [PageTextReader] reads it, and one empty line between two pages. It is read
 * from the PDF's own text, with its password. A page without text (none, no letter or digit, or
 * text that can't be read) adds nothing; when [ocr] is given, such pages are drawn and read with
 * text recognition in [language], their lines in reading order, once the engine is ready (only
 * then is it asked to be, and its model downloaded where it needs one). A page with nothing to
 * draw (no contents, no form field or comment shown) is left white, not read.
 *
 * [check] is called before each page and throws to stop; [onProgress] is told before each page
 * and while text recognition downloads. Throws [NoTextFound] when no page chosen has text, the
 * [OcrUnavailableException] of an engine that can't get ready, and an [IOException] when the file
 * can't be written, or the storage runs full (also while a page is read: that is no page without
 * text). If it fails or is stopped, nothing it made is left behind.
 *
 * Text recognition suspends, and while it does the file-work thread is free for the next task: the
 * cleanup when the tool is left, or the next run, which may then own the output folder. So after
 * it has started its output this never empties the folder: on a throw (a cancel too) it deletes
 * its own two files only, its spool and, if it had begun writing it, its text file; and nothing
 * suspends between its last check for a cancel and its return.
 */
internal suspend fun PdfToolsEngine.pagesToText(
    input: PdfInput,
    pages: List<Int>,
    ocr: OcrEngine?,
    language: OcrLanguage,
    typedName: String,
    check: () -> Unit,
    onProgress: (ExportProgress) -> Unit,
): TextOutcome {
    require(pages.isNotEmpty() && pages.first() >= 0 && pages.last() < input.pageCount && pages.zipWithNext().all { (a, b) -> a < b }) {
        "Pages $pages aren't sorted, each once, within the PDF's ${input.pageCount}"
    }
    val target = newOutput { outputFile(ExportText.baseName(typedName), OutputType.TEXT) }
    val spool = PageSpool(File(target.parentFile, "pages.tmp"))
    var writing = false
    try {
        // The text the PDF holds, read with its password. Usugami's own mark is outlines, not text.
        // Text extraction starts only the pages with contents; the others show at most their annotations.
        var annotated: Set<Int> = emptySet()
        val started = HashSet<Int>()
        load(input.file, input.password).use { doc ->
            val reader = PageTextReader(
                pages.toSet(),
                onStart = { index ->
                    started += index
                    check()
                    onProgress(ExportProgress(ExportProgress.Stage.TEXT, pages.binarySearch(index), pages.size))
                },
            ) { index, text -> if (text != null && ExportText.hasText(text)) spool.put(index, text) }
            reader.read(doc)
            annotated = withFormsOrComments(doc, pages)
        }

        val read = ArrayList<Int>()
        val failed = ArrayList<Int>()
        // A page with nothing drawn on it is white: no text to read, and no reason to ready (or download) recognition.
        val blank = pages.filter { it !in spool && (it in started || it in annotated) }
        // Whether text recognition read the pages with form fields and comments drawn in, as they are shown.
        var annotationsDrawn = false
        if (ocr != null && blank.isNotEmpty()) {
            ocr.ensureReady(language) { fraction ->
                check()
                onProgress(ExportProgress(ExportProgress.Stage.DOWNLOAD_OCR, if (fraction == null) 0 else (fraction * 100).toInt(), if (fraction == null) 0 else 100))
            }
            check()
            val view = viewOf(input, check)
            // The PDF itself (no view could be made) is drawn without its annotations.
            annotationsDrawn = view.path != input.path
            ParcelFileDescriptor.open(view.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                // It refuses a PDF it can't open (a password): never one of Usugami's views.
                val renderer = try { PdfRenderer(descriptor) } catch (e: SecurityException) { throw IOException("The PDF renderer refused the PDF", e) }
                renderer.use {
                    if (renderer.pageCount != input.pageCount) throw IOException("The PDF renderer reads ${renderer.pageCount} pages, PDFBox ${input.pageCount}")
                    ocr.open(language).use { recognizer ->
                        for ((done, index) in blank.withIndex()) {
                            currentCoroutineContext().ensureActive()
                            check()
                            onProgress(ExportProgress(ExportProgress.Stage.READ_SCANS, done, blank.size))
                            val text = try {
                                // The recognizer recycles the picture once it is done with it.
                                val lines = recognizer.recognize(drawForReading(renderer, index))
                                ExportText.ofLines(lines.map { it.text to OcrQuad(it.corners.map { p -> OcrPoint(p.x.toFloat(), p.y.toFloat()) }) })
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: OcrUnavailableException) {
                                // The model went since it was ready: no page after this one can be read either.
                                failed += blank.subList(done, blank.size)
                                break
                            } catch (_: Exception) {
                                failed += index
                                continue
                            } catch (_: OutOfMemoryError) {
                                failed += index
                                continue
                            }
                            // Outside the try: a spool that can't be written is no page recognition failed on.
                            if (ExportText.hasText(text)) {
                                spool.put(index, text)
                                read += index
                            }
                        }
                    }
                }
            }
        }

        val failedSet = failed.toHashSet()
        val withoutText = pages.filter { it !in spool && it !in failedSet }
        val readSet = read.toHashSet()
        val formsOrComments = annotated.any { !annotationsDrawn || it !in readSet }
        if (spool.pageCount == 0) throw NoTextFound(recognitionFailed = failed.isNotEmpty())

        // The last check for a cancel: nothing suspends from here on.
        currentCoroutineContext().ensureActive()
        writing = true
        FileOutputStream(target).buffered().use { out ->
            var first = true
            for ((i, index) in pages.withIndex()) {
                if (i % 50 == 0) check()
                if (index !in spool) continue
                // Each page's text ends with a line break: one more leaves an empty line between pages.
                if (!first) out.write('\n'.code)
                spool.copyTo(index, out)
                first = false
            }
        }
        val count = spool.pageCount
        spool.close()
        return TextOutcome(PdfFile(target, pageCount = count, originalBytes = 0), withoutText, read, failed, formsOrComments)
    } catch (t: Throwable) {
        spool.close()
        if (writing) target.delete()
        throw t
    }
}

/**
 * The zero-based [pages] (sorted) of [doc] with a form field or a comment the page shows: an
 * annotation that is not a link or a pop-up, is neither hidden nor left out of view, and has room on
 * the page (an invisible signature has none). Pages whose annotations can't be read count as having none.
 */
private fun withFormsOrComments(doc: PDDocument, pages: List<Int>): Set<Int> {
    val wanted = pages.toHashSet()
    val found = HashSet<Int>()
    for ((index, page) in doc.pages.withIndex()) {
        if (index > pages.last()) break
        if (index !in wanted) continue
        val annotations = try { page.annotations } catch (_: IOException) { continue }
        if (annotations.any { it !is PDAnnotationLink && it !is PDAnnotationPopup && !it.isHidden && !it.isNoView && hasRoom(it) }) found += index
    }
    return found
}

/** Whether [annotation]'s rectangle has room on the page: one missing, damaged or without width or height shows nothing. */
private fun hasRoom(annotation: PDAnnotation): Boolean {
    val rect = try { annotation.rectangle } catch (_: RuntimeException) { null } ?: return false
    return rect.width > 0f && rect.height > 0f
}

/**
 * Page [index] of [renderer] drawn on white for text recognition, as Usugami's OCR draws pages that
 * aren't read from their images (PdfOcr's RENDER_SIDE and MAX_ENLARGE). The page is closed before
 * the picture is read.
 */
private fun drawForReading(renderer: PdfRenderer, index: Int): Bitmap = renderer.openPage(index).use { page ->
    val scale = OcrScale.of(page.width, page.height, PdfOcr.RENDER_SIDE, PdfOcr.MAX_ENLARGE).toFloat()
    val bitmap = createBitmap(maxOf(1, (page.width * scale).toInt()), maxOf(1, (page.height * scale).toInt()))
    try {
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    } catch (t: Throwable) {
        bitmap.recycle()
        throw t
    }
    bitmap
}

/**
 * The text of each page read, kept in [file] rather than in memory until the text file is written:
 * a long PDF's text could be more than memory holds. One handle reads and writes it, and closing
 * deletes it.
 */
private class PageSpool(private val file: File) : Closeable {
    private val data = RandomAccessFile(file, "rw").apply { setLength(0) }

    /** Where each page's text is in [file], and how many bytes long. */
    private val placed = HashMap<Int, Pair<Long, Int>>()
    private var closed = false

    val pageCount: Int get() = placed.size

    operator fun contains(index: Int): Boolean = index in placed

    /** Keeps [text] as page [index]'s. */
    fun put(index: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val at = data.length()
        data.seek(at)
        data.write(bytes)
        placed[index] = at to bytes.size
    }

    /** Writes page [index]'s text into [out]. */
    fun copyTo(index: Int, out: OutputStream) {
        val (at, length) = placed[index] ?: return
        data.seek(at)
        val buffer = ByteArray(minOf(length, 64 * 1024).coerceAtLeast(1))
        var left = length
        while (left > 0) {
            val got = data.read(buffer, 0, minOf(left, buffer.size))
            if (got < 0) throw IOException("The spooled text of page ${index + 1} is cut short")
            out.write(buffer, 0, got)
            left -= got
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            data.close()
        } catch (_: IOException) {
            // Nothing more is read from it: it goes all the same.
        } finally {
            file.delete()
        }
    }
}

/**
 * The PDF to draw [input]'s pages from: its [PdfToolsEngine.previewView]. When that can't be made, a
 * PDF without a password is drawn as it is (its comments' and fields' looks left out, as the
 * previews are then); one with a password never goes to Android's renderer, which, refusing it on
 * Android 8, draws no PDF at all until Usugami is closed (see PdfThumbnail.kt). Nor is a PDF drawn
 * as it is when the storage was too full for its view (isOutOfSpace, PDFBox's own room among it):
 * its pictures would leave out its filled-in fields and comments without a word, so that failure
 * goes up as it is.
 */
private fun PdfToolsEngine.viewOf(input: PdfInput, check: () -> Unit): PdfInput {
    fun drawnAsItIs(e: Throwable): PdfInput = if (input.password.isEmpty() && !e.isOutOfSpace()) input else throw e
    return try {
        previewView(input, check)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        drawnAsItIs(e)
    } catch (e: LinkageError) {
        drawnAsItIs(e)
    } catch (e: StackOverflowError) {
        drawnAsItIs(e)
    } catch (e: OutOfMemoryError) {
        drawnAsItIs(e)
    }
}

/**
 * Draws page [index], [width] × [height] points as shown, into [target] at [size], with half the
 * pixels each time memory runs out (see [ExportImages.smaller]). True when it got fewer pixels than
 * [size] gives. A failure to write [target] goes up as it is, as Android's encoder can't tell it
 * from a page it couldn't draw.
 */
private fun drawPage(renderer: PdfRenderer, index: Int, width: Float, height: Float, format: ExportImageFormat, size: ExportImageSize, target: File): Boolean {
    var pixels = try { ExportImages.pixelSize(width, height, size.dpi) } catch (e: IllegalArgumentException) { throw PageNotDrawn(index, e) }
    var reduced = pixels.reduced
    try {
        renderer.openPage(index).use { page ->
            while (true) {
                var bitmap: Bitmap? = null
                try {
                    bitmap = createBitmap(pixels.width, pixels.height)
                    bitmap.eraseColor(Color.WHITE)
                    PageRendering.render(page, bitmap, width, height)
                    bitmap.setHasAlpha(false)
                    write(bitmap, format, target)
                    return reduced
                } catch (e: OutOfMemoryError) {
                    bitmap?.recycle()
                    bitmap = null
                    pixels = ExportImages.smaller(pixels) ?: throw PageNotDrawn(index, e)
                    reduced = true
                } finally {
                    bitmap?.recycle()
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        // The file, not the page: also PageNotDrawn itself.
        throw e
    } catch (e: Exception) {
        throw PageNotDrawn(index, e)
    }
}

/** Writes [bitmap] into [target] as [format]: a JPEG at [ExportImages.JPEG_QUALITY], or a PNG. */
private fun write(bitmap: Bitmap, format: ExportImageFormat, target: File) {
    FailureKeeping(FileOutputStream(target)).use { out ->
        val written = when (format) {
            ExportImageFormat.JPG -> bitmap.compress(Bitmap.CompressFormat.JPEG, ExportImages.JPEG_QUALITY, out)
            ExportImageFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        // Android's encoder swallows the file's failure (the storage full, say) and only says false.
        if (!written) throw out.failure ?: IOException("Couldn't encode page picture")
    }
}

/** A stream that keeps the [failure] of a write, which Bitmap.compress swallows. */
private class FailureKeeping(out: OutputStream) : FilterOutputStream(out) {
    var failure: IOException? = null
        private set

    override fun write(b: Int) = kept { out.write(b) }

    override fun write(b: ByteArray, off: Int, len: Int) = kept { out.write(b, off, len) }

    private inline fun kept(write: () -> Unit) {
        try {
            write()
        } catch (e: IOException) {
            failure = e
            throw e
        }
    }
}
