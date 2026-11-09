package io.github.nath_goa.usugami.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.annotation.VisibleForTesting
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import io.github.nath_goa.usugami.core.FileNames
import io.github.nath_goa.usugami.core.ImagePlacement
import io.github.nath_goa.usugami.core.ImageTransform
import io.github.nath_goa.usugami.core.OcrScale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.text.Normalizer
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

data class OcrOutput(val files: List<PdfFile>, val lineCount: Int)

/**
 * Adds an invisible text layer to PDFs made from images, with the text an [OcrEngine] reads. The
 * text layer is the same whichever engine read it.
 */
class PdfOcr(private val context: Context) {
    /**
     * One recognized line, in pixels of the [pixelWidth] × [pixelHeight] image read: the page's own
     * image, drawn on the page as [placement] says, or else the whole page rendered. [corners] are the
     * text's top-left, top-right, bottom-right and bottom-left corners, which also give its angle;
     * without them the upright [bounds] are used.
     */
    @VisibleForTesting
    internal data class Line(
        val page: Int,
        val text: String,
        val bounds: Rect,
        val pixelWidth: Int,
        val pixelHeight: Int,
        val corners: List<Point>? = null,
        val placement: ImagePlacement? = null,
    )

    init { PDFBoxResourceLoader.init(context.applicationContext) }

    /**
     * Returns [files] with a text layer added (as `<name>_searchable.pdf`; files without any text
     * are returned as they are), read by [engine] in [language]. A PDF just made from images is read
     * from those images ([PdfFile.images]), others page by page as rendered. [onPage] receives the
     * number of pages read so far, across all files. If anything fails, every file is left as it was
     * and the exception is rethrown.
     */
    suspend fun apply(files: List<PdfFile>, engine: OcrEngine, language: OcrLanguage, onPage: (Int) -> Unit = {}): OcrOutput {
        val made = ArrayList<PdfFile>()
        try {
            var count = 0
            var pagesDone = 0
            val results = engine.open(language).use { recognizer ->
                files.map { source ->
                    currentCoroutineContext().ensureActive()
                    // The pages as rendered also when the system has cleared a picked image from the cache.
                    val lines = if (source.images.size == source.pageCount && source.images.all { File(it.item.path).isFile }) {
                        recognize(source.images, recognizer) { onPage(++pagesDone) }
                    } else {
                        recognize(source.file, recognizer) { onPage(++pagesDone) }
                    }
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

    /**
     * Reads the images a PDF was just made from as they were picked, not as compressed into it: small
     * print reads far better (at Extreme, the PDF keeps 100 dpi). Each line goes where the page draws it.
     */
    private suspend fun recognize(images: List<PlacedImage>, recognizer: OcrRecognizer, onPageDone: () -> Unit): List<Line> {
        val lines = ArrayList<Line>()
        images.forEachIndexed { page, image ->
            currentCoroutineContext().ensureActive()
            val bitmap = shownImage(image.item, image.placement.transform)
            val width = bitmap.width
            val height = bitmap.height
            // The recognizer recycles the bitmap once it is done with it.
            for (line in recognizer.recognize(bitmap)) {
                if (line.text.isNotBlank()) lines += Line(page, line.text, line.corners.bounds(), width, height, line.corners, image.placement)
            }
            onPageDone()
        }
        return lines
    }

    /**
     * [item] as its page shows it (turned by [transform]: EXIF orientation, then the user's turn), on
     * white, [READ_SIDE] pixels on its long side: larger photos are shrunk, smaller images enlarged at
     * most [MAX_ENLARGE] times, as far as pages used to be rendered for reading. A long screenshot
     * keeps the pixels of a 4:3 photo instead, so its text stays readable ([OcrScale]). If memory
     * runs short, it is read smaller.
     */
    private fun shownImage(item: ImageItem, transform: ImageTransform): Bitmap {
        val shownWidth = if (transform.swapsAxes) item.height else item.width
        val shownHeight = if (transform.swapsAxes) item.width else item.height
        var scale = OcrScale.of(shownWidth, shownHeight, READ_SIDE, MAX_ENLARGE)
        while (true) {
            val width = max(1, (shownWidth * scale).roundToInt())
            val height = max(1, (shownHeight * scale).roundToInt())
            // Decoded at the largest power-of-two reduction that keeps the pixels needed.
            var sample = 1
            while (sample * 2 * scale <= 1) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
            var decoded: Bitmap? = null
            try {
                decoded = BitmapFactory.decodeFile(item.path, options) ?: throw ImageUnreadableException(item.name)
                val shown = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                shown.eraseColor(Color.WHITE)
                // The transform maps the stored image's 0..1 coordinates onto the shown image's.
                val matrix = android.graphics.Matrix()
                matrix.setValues(
                    floatArrayOf(
                        width * transform.a / decoded.width.toFloat(), width * transform.b / decoded.height.toFloat(), width * transform.c.toFloat(),
                        height * transform.d / decoded.width.toFloat(), height * transform.e / decoded.height.toFloat(), height * transform.f.toFloat(),
                        0f, 0f, 1f,
                    ),
                )
                Canvas(shown).drawBitmap(decoded, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                return shown
            } catch (e: OutOfMemoryError) {
                if (max(width, height) <= MIN_READ_SIDE) throw e
                scale /= 2
            } finally {
                decoded?.recycle()
            }
        }
    }

    private suspend fun recognize(file: File, recognizer: OcrRecognizer, onPageDone: () -> Unit): List<Line> {
        val lines = ArrayList<Line>()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    currentCoroutineContext().ensureActive()
                    renderer.openPage(i).use { page ->
                        val scale = OcrScale.of(page.width, page.height, RENDER_SIDE, MAX_ENLARGE).toFloat()
                        val width = maxOf(1, (page.width * scale).toInt())
                        val height = maxOf(1, (page.height * scale).toInt())
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        } catch (e: Throwable) {
                            bitmap.recycle()
                            throw e
                        }
                        // The recognizer recycles the bitmap once it is done with it.
                        for (line in recognizer.recognize(bitmap)) {
                            if (line.text.isNotBlank()) lines += Line(i, line.text, line.corners.bounds(), width, height, line.corners)
                        }
                    }
                    onPageDone()
                }
            }
        }
        return lines
    }

    /**
     * Writes a copy of [source] with [lines] as invisible text; [source] itself is left untouched.
     * If this fails, no partial copy is left behind.
     */
    @VisibleForTesting
    internal suspend fun embed(source: PdfFile, lines: List<Line>): PdfFile {
        val original = source.file
        // Never overwrite another PDF, e.g. one made from an image that was itself named "x_searchable".
        val taken = original.parentFile?.list().orEmpty().mapTo(HashSet()) { FileNames.baseName(it) }
        val target = File(original.parentFile, FileNames.unique("${original.nameWithoutExtension}_searchable", taken) + ".pdf")
        try {
            writeTextLayer(original, lines, target)
        } catch (e: Throwable) {
            // E.g. storage ran out while saving: a half-written copy would only take up more space.
            target.delete()
            throw e
        }
        return PdfFile(target, source.pageCount, source.originalBytes)
    }

    private suspend fun writeTextLayer(original: File, lines: List<Line>, target: File) {
        val scratchDir = File(context.cacheDir, "pdfbox").apply { mkdirs() }
        PDDocument.load(original, "", pdfScratch(scratchDir)).use { doc ->
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
                        val printable = printable(font, line.text)
                        if (printable.isBlank()) continue
                        // From image pixels (y down) to PDF points (y up): where the page draws that
                        // point of its image, or of the page as rendered.
                        fun toPage(p: Point): PointF {
                            val placement = line.placement ?: return PointF(
                                box.lowerLeftX + p.x * box.width / line.pixelWidth,
                                box.lowerLeftY + box.height - p.y * box.height / line.pixelHeight,
                            )
                            val (x, y) = placement.toPage(p.x.toDouble() / line.pixelWidth, p.y.toDouble() / line.pixelHeight)
                            return PointF(x.toFloat(), y.toFloat())
                        }
                        val corners = line.corners?.takeIf { it.size == 4 } ?: line.bounds.corners()
                        val (topLeft, topRight, _, bottomLeft) = corners.map(::toPage)
                        // The text starts at the bottom-left corner and runs along the top edge, so a
                        // line on a tilted photo gets its real size and angle, not its upright bounds'.
                        val width = hypot(topRight.x - topLeft.x, topRight.y - topLeft.y)
                        val height = hypot(topLeft.x - bottomLeft.x, topLeft.y - bottomLeft.y)
                        val angle = atan2(topRight.y - topLeft.y, topRight.x - topLeft.x)
                        val size = maxOf(3f, height * 0.9f)
                        val textWidth = font.getStringWidth(printable) / 1000f * size
                        val stretch = if (textWidth > 0) (width / textWidth * 100f).coerceIn(20f, 200f) else 100f
                        stream.beginText()
                        stream.setRenderingMode(RenderingMode.NEITHER)
                        stream.setFont(font, size)
                        stream.setHorizontalScaling(stretch)
                        stream.setTextMatrix(Matrix.getRotateInstance(angle.toDouble(), bottomLeft.x, bottomLeft.y))
                        stream.showText(printable)
                        stream.endText()
                    }
                }
            }
            doc.save(target)
        }
    }

    /**
     * [text] as [font] can write it. A character it has no glyph for is written in its compatibility
     * form (NFKC) when the font has that, such as "u" for a mathematical italic u (the font can't
     * have characters beyond U+FFFF: see tools/make_ocr_font.py), or else as a space.
     */
    @VisibleForTesting
    internal fun printable(font: PDType0Font, text: String): String = buildString {
        // PDFBox throws IllegalArgumentException, not IOException, when the font has no glyph for a character.
        fun writable(characters: String) = try {
            font.encode(characters)
            true
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
        var offset = 0
        while (offset < text.length) {
            val cp = text.codePointAt(offset)
            val character = String(Character.toChars(cp))
            if (writable(character)) {
                append(character)
            } else {
                val compatible = Normalizer.normalize(character, Normalizer.Form.NFKC)
                append(if (compatible != character && compatible.isNotBlank() && writable(compatible)) compatible else " ")
            }
            offset += Character.charCount(cp)
        }
    }

    private fun Rect.corners() = listOf(Point(left, top), Point(right, top), Point(right, bottom), Point(left, bottom))

    private fun List<Point>.bounds() = Rect(minOf { it.x }, minOf { it.y }, maxOf { it.x }, maxOf { it.y })

    private companion object {
        /** Not in Usugami Lite, which has no text recognition (see PruneAssets in app/build.gradle.kts). */
        const val OCR_FONT = "fonts/UsugamiOcr-Regular.ttf"

        /**
         * The long side, in pixels, images are read at: 27 MB for a 4:3 photo (and no more for a
         * longer picture), so it stays safe on a phone with 2 GB of memory.
         */
        const val READ_SIDE = 3000

        /** The long side, in pixels, pages are rendered at when they aren't read from their images. */
        const val RENDER_SIDE = 2600

        /** Pages used to be rendered at up to 2.5 times their size in points for reading. */
        const val MAX_ENLARGE = 2.5

        /** Below this, a shortage of memory is reported rather than read even smaller. */
        const val MIN_READ_SIDE = 750
    }
}
