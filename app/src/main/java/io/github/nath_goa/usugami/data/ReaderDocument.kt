package io.github.nath_goa.usugami.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import io.github.nath_goa.usugami.core.PageGlyphs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * A PDF open for reading: its pages drawn with Android's renderer, its text read with PDFBox.
 * [file] has no password (a PDF with one is read from its view, see PdfToolsEngine.previewView):
 * on Android 8, once the renderer has refused a PDF it draws none until Usugami is closed.
 * The renderer draws one page at a time, so drawing has a thread of its own; so has reading text,
 * with a PDFBox document opened the first time text is asked for. Closing waits its turn on
 * both, then frees them; it may be called while the file is being deleted.
 */
internal class ReaderDocument private constructor(
    val file: File,
    /** Each page's size as shown, in points, exactly (PdfToolsEngine.shownPageSizes). */
    val sizes: List<Pair<Float, Float>>,
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    /** Opens [file] with PDFBox, for its text: called on [reading], the first time text is wanted. */
    private val text: () -> PDDocument,
) {
    val pageCount: Int get() = sizes.size

    /** The renderer's thread: it draws one page at a time. */
    private val drawing = Dispatchers.IO.limitedParallelism(1)

    /** PDFBox's thread, for the text: a search of a long PDF holds up no other file work. */
    private val reading = Dispatchers.IO.limitedParallelism(1)

    /** Where closing waits its turn on each thread, after what is drawn or read there now. */
    private val closing = CoroutineScope(SupervisorJob())

    @Volatile private var closed = false

    /** The PDFBox document the text is read from, once it is wanted; touched on [reading] only. */
    private var textDocument: PDDocument? = null

    /** Page [index] whole, [width] × [height] pixels, on white. Null when it can't be drawn, or this is closed. */
    suspend fun page(index: Int, width: Int, height: Int): Bitmap? = withContext(drawing) {
        // A page asked for and no longer wanted before its turn came is never drawn.
        ensureActive()
        drawn(index, width, height) { page, bitmap, shownWidth, shownHeight -> PageRendering.render(page, bitmap, shownWidth, shownHeight) }
    }

    /**
     * A part of page [index], [width] × [height] pixels, on white: the page drawn [scale] pixels to
     * the point, from [left], [top] pixels into it (see PageRendering.renderPart). Null when it can't
     * be drawn, or this is closed.
     */
    suspend fun part(index: Int, scale: Float, left: Int, top: Int, width: Int, height: Int): Bitmap? = withContext(drawing) {
        // A part asked for and no longer wanted before its turn came is never drawn.
        ensureActive()
        if (!(scale > 0f) || !scale.isFinite()) return@withContext null
        drawn(index, width, height) { page, bitmap, shownWidth, shownHeight ->
            PageRendering.renderPart(page, bitmap, shownWidth, shownHeight, scale, left.toFloat(), top.toFloat())
        }
    }

    /** A [width] × [height] bitmap on white with page [index] drawn into it by [draw]; on [drawing] only. */
    private fun CoroutineScope.drawn(index: Int, width: Int, height: Int, draw: (PdfRenderer.Page, Bitmap, Float, Float) -> Unit): Bitmap? {
        if (closed || index !in sizes.indices) return null
        val (shownWidth, shownHeight) = sizes[index]
        val bitmap = try {
            createBitmap(width, height)
        } catch (_: OutOfMemoryError) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            bitmap.eraseColor(Color.WHITE)
            renderer.openPage(index).use { draw(it, bitmap, shownWidth, shownHeight) }
            // Drawn for a page that has gone meanwhile: nobody will give it back.
            if (!isActive) {
                bitmap.recycle()
                null
            } else {
                bitmap
            }
        } catch (_: Exception) {
            bitmap.recycle()
            null
        } catch (_: OutOfMemoryError) {
            bitmap.recycle()
            null
        }
    }

    /**
     * Find text on [pages] (zero-based; see Redactor.findOnPages), on the text's own thread, after
     * what it reads now. Throws what the search throws; [check] is called between pages and throws to
     * stop it. Once this is closed, it throws CancellationException.
     */
    suspend fun find(query: String, pages: IntRange, check: () -> Unit): TextSearch = withContext(reading) {
        ensureActive()
        // Checked on this thread: closing comes after it here, so a search never opens a document again once closed.
        if (closed) throw CancellationException("The reader's PDF is closed")
        val doc = textDocument ?: text().also { textDocument = it }
        Redactor.findOnPages(doc, query, pages, check)
    }

    /**
     * Page [index]'s text for selecting (PageGlyphReader), on the text's own thread, after what it
     * reads now (a long-press during a search waits for one lot of pages at most). Throws what
     * reading it throws; once this is closed, CancellationException.
     */
    suspend fun glyphs(index: Int): PageGlyphs = withContext(reading) {
        ensureActive()
        // Checked on this thread: closing comes after it here, so nothing opens the document again once closed.
        if (closed) throw CancellationException("The reader's PDF is closed")
        val doc = textDocument ?: text().also { textDocument = it }
        PageGlyphReader(index).read(doc)
    }

    /**
     * Closes the PDFBox document the text is read from, once what it reads now is done, so that a PDF
     * read through for a search, or for selecting, doesn't keep what PDFBox holds of it for as long as
     * it shows. It opens again when text is next wanted.
     */
    fun releaseText() {
        if (closed) return
        closing.launch(reading) { closeText() }
    }

    /** Closes the text's document, if open; on [reading] only. */
    private fun closeText() {
        try {
            textDocument?.close()
        } catch (_: IOException) {
        }
        textDocument = null
    }

    fun close() {
        if (closed) return
        closed = true
        closing.launch(drawing) {
            try {
                renderer.close()
            } catch (_: Exception) {
            }
            try {
                descriptor.close()
            } catch (_: IOException) {
            }
        }
        closing.launch(reading) { closeText() }
    }

    companion object {
        /** A4 in points: the size taken for a page whose box gives none that can be used. */
        private val FALLBACK_SIZE = 595f to 842f

        /** [size], or A4 when it isn't a finite size of at least a point each way (a damaged page box): the layout divides by it. */
        private fun usable(size: Pair<Float, Float>): Pair<Float, Float> {
            val (width, height) = size
            return if (width.isFinite() && height.isFinite() && width >= 1f && height >= 1f) size else FALLBACK_SIZE
        }

        /** Opens [file]; [text] opens it with PDFBox when text is first wanted. Throws IOException when Android can't open it. */
        fun open(file: File, sizes: List<Pair<Float, Float>>, text: () -> PDDocument): ReaderDocument {
            val descriptor = try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: SecurityException) {
                throw IOException("Can't open the file", e)
            }
            try {
                val renderer = PdfRenderer(descriptor)
                val count = renderer.pageCount
                if (count != sizes.size) {
                    renderer.close()
                    throw IOException("Android finds $count pages, PDFBox ${sizes.size}")
                }
                return ReaderDocument(file, sizes.map(::usable), descriptor, renderer, text)
            } catch (e: IOException) {
                descriptor.close()
                throw e
            } catch (e: Exception) {
                // A password the renderer can't open (SecurityException), or any other refusal.
                descriptor.close()
                throw IOException("Android can't draw this PDF", e)
            }
        }
    }
}
