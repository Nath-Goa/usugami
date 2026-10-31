package io.github.nath_goa.usugami.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.annotation.VisibleForTesting
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import io.github.nath_goa.usugami.core.FileNames
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException

enum class OcrLanguage { ENGLISH, HINDI }

data class OcrOutput(val files: List<PdfFile>, val lineCount: Int)

/** Adds an invisible text layer to PDFs made from images. */
class PdfOcr(private val context: Context) {
    /** One recognized line; [bounds] is in pixels of the [pixelWidth] × [pixelHeight] page image. */
    @VisibleForTesting
    internal data class Line(val page: Int, val text: String, val bounds: Rect, val pixelWidth: Int, val pixelHeight: Int)

    init { PDFBoxResourceLoader.init(context.applicationContext) }

    /**
     * Returns [files] with a text layer added (as `<name>_searchable.pdf`; files without any text
     * are returned as they are). [onPage] receives the number of pages read so far, across all files.
     * If anything fails, every file is left as it was and the exception is rethrown.
     */
    suspend fun apply(files: List<PdfFile>, language: OcrLanguage, onPage: (Int) -> Unit = {}): OcrOutput {
        val made = ArrayList<PdfFile>()
        try {
            var count = 0
            var pagesDone = 0
            val results = newRecognizer(language).use { recognizer ->
                files.map { source ->
                    currentCoroutineContext().ensureActive()
                    val lines = recognize(source.file, recognizer) { onPage(++pagesDone) }
                    count += lines.size
                    if (lines.isEmpty()) source else embed(source, lines).also { made += it }
                }
            }
            // The originals are only replaced once every file has its text layer.
            files.filter { it !in results }.forEach { it.file.delete() }
            return OcrOutput(results, count)
        } catch (e: Throwable) {
            made.forEach { it.file.delete() }
            throw e
        }
    }

    private suspend fun recognize(file: File, recognizer: TextRecognizer, onPageDone: () -> Unit): List<Line> {
        val lines = ArrayList<Line>()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    currentCoroutineContext().ensureActive()
                    renderer.openPage(i).use { page ->
                        val scale = minOf(2.5f, 2600f / maxOf(page.width, page.height))
                        val width = maxOf(1, (page.width * scale).toInt())
                        val height = maxOf(1, (page.height * scale).toInt())
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val text = try {
                                Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                            } catch (e: ExecutionException) {
                                // Standard edition: Play services doesn't have the model (not
                                // downloaded yet, or removed since).
                                val cause = e.cause
                                if (cause is MlKitException && cause.errorCode == MlKitException.UNAVAILABLE) {
                                    throw OcrUnavailableException(OcrProblem.DOWNLOAD_FAILED, cause)
                                }
                                throw e
                            }
                            for (block in text.textBlocks) for (line in block.lines) {
                                val box = line.boundingBox ?: continue
                                if (line.text.isNotBlank()) lines += Line(i, line.text, box, width, height)
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    onPageDone()
                }
            }
        }
        return lines
    }

    /** Writes a copy of [source] with [lines] as invisible text; [source] itself is left untouched. */
    @VisibleForTesting
    internal suspend fun embed(source: PdfFile, lines: List<Line>): PdfFile {
        val original = source.file
        // Never overwrite another PDF, e.g. one made from an image that was itself named "x_searchable".
        val taken = original.parentFile?.list().orEmpty().mapTo(HashSet()) { FileNames.baseName(it) }
        val target = File(original.parentFile, FileNames.unique("${original.nameWithoutExtension}_searchable", taken) + ".pdf")
        val scratchDir = File(context.cacheDir, "pdfbox").apply { mkdirs() }
        PDDocument.load(original, "", MemoryUsageSetting.setupMixed(24L * 1024 * 1024).setTempDir(scratchDir)).use { doc ->
            // One glyph-less font covers Latin and Devanagari, so Hindi mode keeps English words too.
            // The text layer is invisible; see tools/make_ocr_font.py.
            val font = context.assets.open(OCR_FONT).use { PDType0Font.load(doc, it) }
            for (pageIndex in 0 until doc.numberOfPages) {
                currentCoroutineContext().ensureActive()
                val pageLines = lines.filter { it.page == pageIndex }
                if (pageLines.isEmpty()) continue
                val page = doc.getPage(pageIndex)
                val box = page.mediaBox
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                    for (line in pageLines) {
                        val printable = buildString {
                            var offset = 0
                            while (offset < line.text.length) {
                                val cp = line.text.codePointAt(offset)
                                val character = String(Character.toChars(cp))
                                // PDFBox throws IllegalArgumentException, not IOException, when the
                                // font has no glyph for a character. Such characters become spaces.
                                try { font.encode(character); append(character) }
                                catch (_: IOException) { append(' ') }
                                catch (_: IllegalArgumentException) { append(' ') }
                                offset += Character.charCount(cp)
                            }
                        }
                        if (printable.isBlank()) continue
                        val x = box.lowerLeftX + line.bounds.left * box.width / line.pixelWidth
                        val y = box.lowerLeftY + box.height - line.bounds.bottom * box.height / line.pixelHeight
                        val size = maxOf(3f, line.bounds.height() * box.height / line.pixelHeight * 0.9f)
                        val targetWidth = line.bounds.width() * box.width / line.pixelWidth
                        val textWidth = font.getStringWidth(printable) / 1000f * size
                        val stretch = if (textWidth > 0) (targetWidth / textWidth * 100f).coerceIn(20f, 200f) else 100f
                        stream.beginText()
                        stream.setRenderingMode(RenderingMode.NEITHER)
                        stream.setFont(font, size)
                        stream.setHorizontalScaling(stretch)
                        stream.newLineAtOffset(x, y)
                        stream.showText(printable)
                        stream.endText()
                    }
                }
            }
            doc.save(target)
        }
        return PdfFile(target, source.pageCount, source.originalBytes)
    }

    companion object {
        private const val OCR_FONT = "fonts/UsugamiOcr-Regular.ttf"

        /** Both editions use the same ML Kit API; only where the model comes from differs. */
        fun newRecognizer(language: OcrLanguage): TextRecognizer = when (language) {
            OcrLanguage.ENGLISH -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            OcrLanguage.HINDI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        }
    }
}
