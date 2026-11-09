package io.github.nath_goa.usugami.data

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import io.github.nath_goa.usugami.core.DetectionStrips
import io.github.nath_goa.usugami.core.LargeText
import io.github.nath_goa.usugami.core.OcrPoint
import io.github.nath_goa.usugami.core.OcrQuad
import io.github.nath_goa.usugami.core.OcrScale
import io.github.nath_goa.usugami.core.OcrText
import io.github.nath_goa.usugami.core.ReadingOrder
import io.github.nath_goa.usugami.core.TextDetection
import io.github.nath_goa.usugami.core.TextLines
import io.github.nath_goa.usugami.core.TextRecognition
import io.github.nath_goa.usugami.ocr.NcnnNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The model files in assets/ocr/ (made by tools/make_ocr_models.py). */
internal object PaddleModels {
    const val DETECTION = "ocr/PP-OCRv5_mobile_det"

    /** English, French and Spanish share the Latin-script model; Hindi's also reads English words. */
    fun recognition(language: OcrLanguage) = when (language) {
        OcrLanguage.ENGLISH, OcrLanguage.FRENCH, OcrLanguage.SPANISH -> "ocr/latin_PP-OCRv5_mobile_rec"
        OcrLanguage.HINDI -> "ocr/devanagari_PP-OCRv5_mobile_rec"
    }

    fun files(language: OcrLanguage): List<String> {
        val recognition = recognition(language)
        return listOf("$DETECTION.ncnn.param", "$DETECTION.ncnn.bin", "$recognition.ncnn.param", "$recognition.ncnn.bin", "$recognition.dict.txt")
    }
}

/**
 * Reads pages with PaddleOCR's PP-OCRv5 pipeline: the detection model finds the lines of text
 * (TextDetection turns its map into boxes, TextLines joins pieces of one line), and on a small copy
 * of the page the words too large for it at full size, which are read in place of the pieces of
 * them found there (LargeText); each line is cut
 * out, straightened and scaled to the recognition model's height, read, and decoded
 * (TextRecognition). Lines come in reading order (ReadingOrder), with corners in the page's pixels.
 * The models are loaded on the first page and freed by [close].
 */
internal class PaddleOcrRecognizer(private val assets: AssetManager, private val language: OcrLanguage) : OcrRecognizer {
    @Volatile
    private var models: Models? = null

    override suspend fun recognize(page: Bitmap): List<OcrLine> {
        try {
            return withContext(Dispatchers.Default) {
                val loaded = models ?: Models.load(assets, language).also { models = it }
                try {
                    if (page.config == Bitmap.Config.ARGB_8888) {
                        read(page, loaded)
                    } else {
                        val copy = page.copy(Bitmap.Config.ARGB_8888, false) ?: throw OutOfMemoryError("No memory to read the page")
                        try { read(copy, loaded) } finally { copy.recycle() }
                    }
                } finally {
                    // What recognition kept for its lines isn't needed while the next page is detected.
                    loaded.reader.trim()
                }
            }
        } finally {
            // Nothing reads the page after the work above has returned, even when it was cancelled.
            page.recycle()
        }
    }

    override fun close() {
        models?.close()
        models = null
    }

    private class Models(val detector: NcnnNet, val reader: NcnnNet, val characters: List<String>) : Closeable {
        override fun close() {
            detector.close()
            reader.close()
        }

        companion object {
            /** @throws IOException when the models or the engine can't be loaded */
            fun load(assets: AssetManager, language: OcrLanguage): Models {
                // OcrEngine.ensureReady has loaded the engine already; if it failed here anyway, this
                // reports it as a failed recognition rather than letting the error crash the app.
                try {
                    NcnnNet.loadLibrary()
                } catch (e: LinkageError) {
                    throw IOException("The text recognition engine can't be loaded", e)
                }
                val recognition = PaddleModels.recognition(language)
                val characters = assets.open("$recognition.dict.txt").bufferedReader().use { it.readLines() }
                // Detection runs on large strips, where keeping buffers would double its memory;
                // recognition on many small lines, where keeping them (until the page is read) saves time.
                val detector = NcnnNet.load(assets, "${PaddleModels.DETECTION}.ncnn.param", "${PaddleModels.DETECTION}.ncnn.bin", reuseMemory = false)
                try {
                    val reader = NcnnNet.load(assets, "$recognition.ncnn.param", "$recognition.ncnn.bin", reuseMemory = true)
                    return Models(detector, reader, characters)
                } catch (e: Throwable) {
                    detector.close()
                    throw e
                }
            }
        }
    }

    private class Line(var quad: OcrQuad, val vertical: Boolean) {
        var text: OcrText? = null
    }

    private suspend fun read(page: Bitmap, models: Models): List<OcrLine> {
        // Words of one line that detection found apart are read together, so the space between them
        // is kept. Like PaddleOCR, a line running down the page is read turned a quarter
        // anticlockwise (its top-right corner is where the text starts).
        val found = detect(page, models.detector, DETECTION_SIDE)
        val boxes = TextLines.merge(LargeText.inPlaceOfPieces(found, largeWords(page, models, found))).map { it.quad }
        val vertical = TextLines.vertical(boxes)
        val lines = boxes.mapIndexed { i, box -> Line(if (vertical[i]) box.turned(1) else box, vertical[i]) }
        // Each group of lines is read the right way up: a page photographed upside down (or turned the
        // other way) reads with more confidence turned half round. The widest lines decide.
        for (group in lines.groupBy { it.vertical }.values) {
            val sample = group.sortedByDescending { it.quad.width }.take(ORIENTATION_SAMPLE)
            var upright = 0f
            var turned = 0f
            val turnedTexts = sample.map { line ->
                currentCoroutineContext().ensureActive()
                upright += readLine(page, line.quad, models).also { line.text = it }.confidence
                readLine(page, line.quad.turned(2), models).also { turned += it.confidence }
            }
            if (turned > upright + ORIENTATION_MARGIN * sample.size) {
                group.forEach { it.quad = it.quad.turned(2); it.text = null }
                sample.forEachIndexed { i, line -> line.text = turnedTexts[i] }
            }
        }
        val result = ArrayList<OcrLine>()
        for (index in ReadingOrder.of(lines.map { it.quad })) {
            currentCoroutineContext().ensureActive()
            val line = lines[index]
            val text = line.text ?: readLine(page, line.quad, models)
            if (text.confidence < MIN_CONFIDENCE || text.text.isBlank()) continue
            result += OcrLine(text.text.trim(), line.quad.corners.map { Point(it.x.roundToInt(), it.y.roundToInt()) })
        }
        return result
    }

    /**
     * The words on [page] too large for detection at full size, which found [found]: those found on
     * a copy [LARGE_TEXT_SIDE] pixels long (LargeText). The model finds letters only up to a few
     * hundred pixels high, whatever the size it detects at, so every page detected larger than that
     * copy is looked at again on it. A box found there counts only if it reads as text, turned as
     * the page's lines will be (TextLines.vertical) and either way up: one that doesn't is a picture
     * or a logo, and what was found inside it at full size is read instead.
     */
    private suspend fun largeWords(page: Bitmap, models: Models, found: List<OcrQuad>): List<OcrQuad> {
        val fineScale = OcrScale.of(page.width, page.height, DETECTION_SIDE, maxEnlarge = 1.0)
        if (max(page.width, page.height) * fineScale <= LARGE_TEXT_SIDE) return emptyList()
        val minHeight = LARGE_TEXT_MIN_HEIGHT * min(page.width, page.height)
        val candidates = LargeText.candidates(detect(page, models.detector, LARGE_TEXT_SIDE), found, minHeight)
        val vertical = TextLines.vertical(found + candidates)
        return candidates.filterIndexed { i, box ->
            currentCoroutineContext().ensureActive()
            val quad = if (vertical[found.size + i]) box.turned(1) else box
            listOf(quad, quad.turned(2)).any { turn ->
                val text = readLine(page, turn, models)
                text.confidence >= MIN_CONFIDENCE && text.text.isNotBlank()
            }
        }
    }

    /**
     * The boxes of the lines on [page], detected at [longSide] pixels (OcrScale), in the page's
     * pixels, as PaddleOCR orders their corners.
     */
    private suspend fun detect(page: Bitmap, detector: NcnnNet, longSide: Int): List<OcrQuad> {
        val scale = OcrScale.of(page.width, page.height, longSide, maxEnlarge = 1.0)
        val width = max(1, (page.width * scale).roundToInt())
        val height = max(1, (page.height * scale).roundToInt())
        val scaled = if (width == page.width && height == page.height) page else Bitmap.createScaledBitmap(page, width, height, true)
        // The model needs sides that are multiples of 32: the page is padded with white, not stretched.
        val mapWidth = (width + 31) / 32 * 32
        val mapHeight = (height + 31) / 32 * 32
        val map = ByteArray(mapWidth * mapHeight)
        try {
            for (strip in DetectionStrips.of(mapWidth, mapHeight, DETECTION_STRIP_PIXELS, DETECTION_STRIP_OVERLAP)) {
                currentCoroutineContext().ensureActive()
                // The part of the strip on the page; the rest is padding.
                val pieceWidth = min(strip.width, width - strip.x)
                val pieceHeight = min(strip.height, height - strip.y)
                val whole = pieceWidth == width && pieceHeight == height
                val piece = if (whole) scaled else Bitmap.createBitmap(scaled, strip.x, strip.y, pieceWidth, pieceHeight)
                val output = try {
                    detector.runToBytes(
                        NcnnNet.Input(piece, pieceWidth, pieceHeight, DETECTION_MEAN, DETECTION_NORM, padRight = strip.width - pieceWidth, padBottom = strip.height - pieceHeight, padValue = 255f),
                    )
                } finally {
                    if (!whole) piece.recycle()
                }
                check(output.channels == 1 && output.width == strip.width && output.height == strip.height) {
                    "Unexpected detection output ${output.channels} x ${output.height} x ${output.width}"
                }
                DetectionStrips.copy(output.values, strip, map, mapWidth)
            }
        } finally {
            if (scaled !== page) scaled.recycle()
        }
        val toPageX = page.width.toFloat() / width
        val toPageY = page.height.toFloat() / height
        return TextDetection.boxes(map, mapWidth, mapHeight).map { (quad, _) ->
            quad.map { OcrPoint((it.x * toPageX).coerceIn(0f, page.width - 1f), (it.y * toPageY).coerceIn(0f, page.height - 1f)) }
        }.filter { it.width > MIN_BOX_SIDE && it.height > MIN_BOX_SIDE } // PaddleOCR's filter_tag_det_res
    }

    /** Reads the line in [quad] (corners as the text reads) on [page]. */
    private fun readLine(page: Bitmap, quad: OcrQuad, models: Models): OcrText {
        if (quad.height < 1f) return OcrText("", 0f)
        val width = ceil(LINE_HEIGHT * quad.width / quad.height).toInt().coerceIn(1, MAX_LINE_WIDTH)
        val crop = Bitmap.createBitmap(width, LINE_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            crop.eraseColor(Color.WHITE)
            val from = FloatArray(8) { if (it % 2 == 0) quad.corners[it / 2].x else quad.corners[it / 2].y }
            val to = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), LINE_HEIGHT.toFloat(), 0f, LINE_HEIGHT.toFloat())
            val matrix = Matrix()
            if (!matrix.setPolyToPoly(from, 0, to, 0, 4)) return OcrText("", 0f)
            Canvas(crop).drawBitmap(page, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            if (width <= MAX_PIECE_WIDTH) return recognize(crop, models)
            // Too long to read well in one go: read it in pieces, cut between words where possible.
            val cuts = TextRecognition.cuts(inkPerColumn(crop), MAX_PIECE_WIDTH, spaceWidth = LINE_HEIGHT / 4)
            val text = StringBuilder()
            var weighted = 0f
            var start = 0
            for ((i, end) in (cuts.map { it.at } + width).withIndex()) {
                val piece = Bitmap.createBitmap(crop, start, 0, end - start, LINE_HEIGHT)
                val read = try { recognize(piece, models) } finally { piece.recycle() }
                if (text.isNotEmpty() && read.text.isNotEmpty() && cuts[i - 1].betweenWords) text.append(' ')
                text.append(read.text)
                weighted += read.confidence * read.text.length
                start = end
            }
            return OcrText(text.toString(), if (text.isEmpty()) 0f else weighted / text.length)
        } finally {
            crop.recycle()
        }
    }

    private fun recognize(line: Bitmap, models: Models): OcrText {
        // Narrow lines are padded to the width the model was trained on, with the value 0 after
        // normalization (mid grey), as PaddleOCR does.
        val output = models.reader.run(
            NcnnNet.Input(line, line.width, LINE_HEIGHT, LINE_MEAN, LINE_NORM, padRight = max(0, MIN_LINE_WIDTH - line.width), padValue = 127.5f),
        )
        // Steps along the line × classes (blank, the characters, space).
        return TextRecognition.decode(output.values, output.channels * output.height, output.width, models.characters)
    }

    /** How many dark pixels each column of [line] has. */
    private fun inkPerColumn(line: Bitmap): IntArray {
        val pixels = IntArray(line.width * line.height)
        line.getPixels(pixels, 0, line.width, 0, 0, line.width, line.height)
        val luminance = IntArray(pixels.size) { (Color.red(pixels[it]) * 299 + Color.green(pixels[it]) * 587 + Color.blue(pixels[it]) * 114) / 1000 }
        val darkest = luminance.min()
        val lightest = luminance.max()
        val ink = IntArray(line.width)
        if (lightest - darkest < MIN_CONTRAST) return ink
        val threshold = (darkest + lightest) / 2
        for (i in luminance.indices) if (luminance[i] < threshold) ink[i % line.width]++
        return ink
    }

    private companion object {
        /**
         * The longest side the detection model sees, or for a longer picture than 4:3 as many pixels
         * as a 4:3 one has then (OcrScale). Pages come at up to 3000 pixels (PdfOcr: the picked
         * images, or pages rendered at up to 2600), long screenshots up to 8192; a rendered A4 page
         * (2105) is detected at full size, which on photographed pages found clearly more small print
         * than 1600 or 960 (measured with PaddleOCR's own pipeline).
         */
        const val DETECTION_SIDE = 2240

        /**
         * The long side, in pixels, of the copy large words are found on: there, the model finds
         * letters up to more than half the page's height (on a page detected at [DETECTION_SIDE],
         * only up to about a sixth).
         */
        const val LARGE_TEXT_SIDE = 640

        /** Only boxes at least this share of the page's short side high across their text are taken from that copy. */
        const val LARGE_TEXT_MIN_HEIGHT = 0.1f

        /**
         * Larger pages are detected in strips of at most this many pixels, overlapping by
         * [DETECTION_STRIP_OVERLAP] (DetectionStrips): the model needs about 160 MB per million pixels
         * (measured on x86_64; ARM phones run the same 32-bit floats). An A4 page is 3 strips.
         */
        const val DETECTION_STRIP_PIXELS = 1_250_000
        const val DETECTION_STRIP_OVERLAP = 128
        val DETECTION_MEAN = floatArrayOf(0.485f * 255, 0.456f * 255, 0.406f * 255)
        val DETECTION_NORM = floatArrayOf(1 / (0.229f * 255), 1 / (0.224f * 255), 1 / (0.225f * 255))
        const val MIN_BOX_SIDE = 3f

        /** The recognition model reads lines 48 pixels high, normalized to -1..1. */
        const val LINE_HEIGHT = 48
        val LINE_MEAN = floatArrayOf(127.5f, 127.5f, 127.5f)
        val LINE_NORM = floatArrayOf(1 / 127.5f, 1 / 127.5f, 1 / 127.5f)
        const val MIN_LINE_WIDTH = 320

        /** PaddleOCR's largest width for its recognition models; longer lines are read in pieces. */
        const val MAX_PIECE_WIDTH = 3200
        const val MAX_LINE_WIDTH = 4 * MAX_PIECE_WIDTH
        const val MIN_CONTRAST = 32

        /** PaddleOCR's usual threshold: what is read with less confidence is mostly not text. */
        const val MIN_CONFIDENCE = 0.5f
        const val ORIENTATION_SAMPLE = 3
        const val ORIENTATION_MARGIN = 0.05f
    }
}
