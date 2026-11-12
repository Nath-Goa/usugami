package io.github.nath_goa.usugami.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import io.github.nath_goa.usugami.core.ExportImageFormat
import io.github.nath_goa.usugami.core.ExportImageSize
import io.github.nath_goa.usugami.core.ExportImages
import io.github.nath_goa.usugami.core.isOutOfSpace
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException

// PDF to images or text, in the engine: a PDF's pages as JPG or PNG pictures.

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

/** [input]'s pages as images, or its text as a file, in place of the files made before. The lane fills it in: nothing is made yet. */
internal fun PdfToolsEngine.fromPdf(input: PdfInput, fileName: String, check: () -> Unit = {}): List<PdfFile> = newOutput { emptyList() }

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
