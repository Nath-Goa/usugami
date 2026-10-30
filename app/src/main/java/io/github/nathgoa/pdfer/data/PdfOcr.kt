package io.github.nathgoa.pdfer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

enum class OcrLanguage { ENGLISH, HINDI }

data class OcrOutput(val files: List<PdfFile>, val lineCount: Int)

/** Adds an invisible text layer to PDFs made from images. Recognition and font files are bundled. */
class PdfOcr(private val context: Context) {
    private data class Line(val page: Int, val text: String, val bounds: Rect, val pixelWidth: Int, val pixelHeight: Int)

    init { PDFBoxResourceLoader.init(context.applicationContext) }

    suspend fun apply(files: List<PdfFile>, language: OcrLanguage): OcrOutput {
        val recognizer = when (language) {
            OcrLanguage.ENGLISH -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            OcrLanguage.HINDI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        }
        var count = 0
        try {
            val results = files.map { source ->
                currentCoroutineContext().ensureActive()
                val lines = recognize(source.file, recognizer)
                count += lines.size
                if (lines.isEmpty()) source else embed(source, lines, language)
            }
            return OcrOutput(results, count)
        } finally {
            recognizer.close()
        }
    }

    private suspend fun recognize(file: File, recognizer: com.google.mlkit.vision.text.TextRecognizer): List<Line> {
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
                            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                            for (block in text.textBlocks) for (line in block.lines) {
                                val box = line.boundingBox ?: continue
                                if (line.text.isNotBlank()) lines += Line(i, line.text, box, width, height)
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
        return lines
    }

    private suspend fun embed(source: PdfFile, lines: List<Line>, language: OcrLanguage): PdfFile {
        val original = source.file
        val target = File(original.parentFile, "${original.nameWithoutExtension}_searchable.pdf")
        val scratchDir = File(context.cacheDir, "pdfbox").apply { mkdirs() }
        PDDocument.load(original, "", MemoryUsageSetting.setupMixed(24L * 1024 * 1024).setTempDir(scratchDir)).use { doc ->
            val asset = when (language) {
                OcrLanguage.ENGLISH -> "fonts/NotoSans-Regular.ttf"
                OcrLanguage.HINDI -> "fonts/NotoSansDevanagari-Regular.ttf"
            }
            val font = context.assets.open(asset).use { PDType0Font.load(doc, it) }
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
        original.delete()
        return PdfFile(target, source.pageCount, source.originalBytes)
    }
}
