package io.github.nath_goa.usugami.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.util.Matrix
import io.github.nath_goa.usugami.core.BlockArea
import io.github.nath_goa.usugami.core.ExportText
import io.github.nath_goa.usugami.core.FileNames
import io.github.nath_goa.usugami.core.PageFrame
import io.github.nath_goa.usugami.core.SourceBlock
import io.github.nath_goa.usugami.core.SourceLine
import io.github.nath_goa.usugami.core.TextBlocks
import io.github.nath_goa.usugami.core.TextChunks
import io.github.nath_goa.usugami.core.TextFit
import io.github.nath_goa.usugami.core.TranslatableText
import io.github.nath_goa.usugami.core.TranslatedPages
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// Translate PDF, in the engine (standard edition only). The signatures here are the contract between
// the engine and the tool's screen.

/**
 * How far a translation run is. [Stage.DOWNLOAD]: language packs download, [done] per cent of
 * [total] 100, or [total] 0 while the size isn't known. [Stage.DOWNLOAD_OCR]: the same for text
 * recognition's model. [Stage.PAGE]: the page being worked on is the one after the first [done] of
 * the [total] pages chosen, and [done] is [total] once all are made. Pages with nothing to translate
 * before the first page with something are drawn once there is one, and told again as they are.
 */
data class TranslateProgress(val stage: Stage, val done: Int, val total: Int) {
    enum class Stage { DOWNLOAD, DOWNLOAD_OCR, PAGE }
}

/**
 * The PDF made, and what the result's notes say. Every page list is of zero-based pages of the PDF
 * that was translated, in page order, among the pages chosen:
 * - [noText]: pages with no text to translate (a picture, a blank page, only numbers): in the new PDF as they are.
 * - [unread]: pages that look scanned but couldn't be read (no text recognition for the language, or it failed on the page): as they are.
 * - [partly]: pages where a paragraph was left as it was, because translating it failed or this phone has no font that draws its translation.
 */
class TranslateOutcome(val file: PdfFile, val noText: List<Int>, val unread: List<Int>, val partly: List<Int>)

/** None of the pages chosen has text to translate; [unreadable] when some pages looked scanned but couldn't be read. */
class NothingToTranslate(val unreadable: Boolean) : Exception("No page has text to translate")

/**
 * The zero-based [pages] of [input] (sorted, each once, at least one) translated from [source] to
 * [target] (tags, see [TranslationEngine]), as a new PDF in place of the files made before, named
 * after [typedName]. Pages with real text are read from it; pages without are read with [ocr]
 * when it is given and reads [source]'s script (see [ocrLanguageFor]). Language packs are
 * downloaded first when missing. [check] is called often and throws to stop; [onProgress] is told as
 * the run goes. Throws [NothingToTranslate], [TranslationUnavailableException], [OcrUnavailableException]
 * (only when no page had anything to translate without text recognition: else the pages it would have
 * read are [TranslateOutcome.unread]) and an IOException when the file can't be written (see
 * isOutOfSpace); if it fails or is stopped, nothing it made is left behind.
 *
 * Each page of the new PDF is a picture of the page as shown (see [PdfToolsEngine.previewView]: with
 * what its comments and filled-in fields show), with each paragraph that was translated painted over
 * in the colour around it and its translation drawn in its place, in the colour of its letters, as
 * real text (see [TranslationOverlay]). How a page is read and drawn is [PdfTranslator]'s.
 *
 * Translating suspends, and while it does the file-work thread is free for the next task: the
 * cleanup when the tool is left, or the next run, which may then own the output folder. So once it
 * has started its output this never empties the folder: on a throw (a cancel too) it deletes its
 * own file only, if it had begun writing it; and nothing suspends between its last check for a
 * cancel and its return.
 */
internal suspend fun PdfToolsEngine.translatePdf(
    input: PdfInput,
    pages: List<Int>,
    translation: TranslationEngine,
    ocr: OcrEngine?,
    source: String,
    target: String,
    typedName: String,
    check: () -> Unit,
    onProgress: (TranslateProgress) -> Unit,
): TranslateOutcome {
    require(pages.isNotEmpty() && pages.first() >= 0 && pages.last() < input.pageCount && pages.zipWithNext().all { (a, b) -> a < b }) {
        "Pages $pages aren't sorted, each once, within the PDF's ${input.pageCount}"
    }
    check()
    translation.ensureReady(source, target) { fraction -> onProgress(downloading(TranslateProgress.Stage.DOWNLOAD, fraction)) }
    check()
    val file = newOutput { outputFile(FileNames.sanitize(typedName, FALLBACK_NAME)) }
    var writing = false
    try {
        return translation.open(source, target).use { translator ->
            PdfTranslator(this, input, pages, translator, ocr, source, target, check, onProgress).make(file) { writing = true }
        }
    } catch (t: Throwable) {
        if (writing) file.delete()
        throw t
    }
}

/**
 * The language the text of [input]'s first pages with text is written in, as [translation] detects
 * it from at most a few thousand letters; null when the PDF has no text or the language isn't clear.
 * Cheap enough to run when a PDF is picked. [check] throws to stop.
 *
 * The text is read as [translatePdf] reads it (see [PageLineReader]), from at most the first
 * [DETECT_PAGES] pages with text and [DETECT_LETTERS] letters, looking no further than the first
 * [DETECT_LOOK] pages: a scan has none to find.
 */
internal suspend fun PdfToolsEngine.detectSourceLanguage(input: PdfInput, translation: TranslationEngine, check: () -> Unit): String? {
    val text = StringBuilder()
    var letters = 0
    var withText = 0
    load(input.file, input.password).use { doc ->
        val last = min(doc.numberOfPages, DETECT_LOOK)
        var from = 0
        while (from < last && letters < DETECT_LETTERS && withText < DETECT_PAGES) {
            check()
            val batch = (from until min(last, from + DETECT_BATCH)).toSet()
            val read = sortedMapOf<Int, PageLines>()
            PageLineReader(batch, onStart = { check() }) { index, lines -> if (lines != null) read[index] = lines }.read(doc)
            for (lines in read.values) {
                if (letters >= DETECT_LETTERS || withText >= DETECT_PAGES) break
                val words = lines.lines.filter { line -> line.text.any(Char::isLetter) }
                if (words.isEmpty()) continue
                withText++
                for (line in words) {
                    if (letters >= DETECT_LETTERS) break
                    text.append(line.text).append('\n')
                    letters += line.text.count(Char::isLetter)
                }
            }
            from += DETECT_BATCH
        }
    }
    if (letters == 0) return null
    check()
    return translation.detect(text.toString())
}

/**
 * The language text recognition reads [tag]'s script in: Latin for the Latin-script languages ML Kit
 * translates (any of them reads as [OcrLanguage.ENGLISH], whose recognizer takes no language hint),
 * Devanagari for Hindi and Marathi ([OcrLanguage.HINDI]); null for the others (Arabic, Cyrillic,
 * Chinese...), whose scanned pages can't be read.
 */
internal fun ocrLanguageFor(tag: String): OcrLanguage? = when (tag) {
    "hi", "mr" -> OcrLanguage.HINDI
    "af", "ca", "cs", "cy", "da", "de", "en", "eo", "es", "et", "fi", "fr", "ga", "gl", "hr", "ht", "hu", "id",
    "is", "it", "lt", "lv", "ms", "mt", "nl", "no", "pl", "pt", "ro", "sk", "sl", "sq", "sv", "sw", "tl", "tr", "vi",
    -> OcrLanguage.ENGLISH
    else -> null
}

/** The new PDF's name when none is typed. */
private const val FALLBACK_NAME = "Translated"

/** Language detection reads at most this many pages with text… */
private const val DETECT_PAGES = 10

/** …and this many letters… */
private const val DETECT_LETTERS = 3000

/** …looking at no more than this many pages, so many pages at a time. */
private const val DETECT_LOOK = 50
private const val DETECT_BATCH = 10

/** A download's progress as the screen is told it: per cent of 100, or 0 of 0 while its size isn't known. */
private fun downloading(stage: TranslateProgress.Stage, fraction: Float?): TranslateProgress =
    if (fraction == null || !fraction.isFinite()) {
        TranslateProgress(stage, 0, 0)
    } else {
        TranslateProgress(stage, (fraction * 100).toInt().coerceIn(0, 100), 100)
    }

/**
 * One run of [translatePdf]: [pages] of [input] translated with [translator] into a new PDF.
 *
 * The PDF is read and drawn from its view (see [viewOf]), so what is translated is what the page
 * shows, and where. Pages are read [READ_BATCH] at a time, and each page, in order:
 * 1. its lines (see [PageLineReader]) are grouped into blocks ([TextBlocks]), and those worth
 *    translating ([TranslatableText]) are chosen. A page with none, that shows no letters at all
 *    but has something drawn on it, looks scanned: it is read with text recognition once (when
 *    [ocr] reads [source]'s script, else it is [unread]), and its lines grouped in the same way.
 * 2. Each block chosen is translated (texts met before in the run are taken from [cache]). A block
 *    whose translation fails, or that the phone has no font for, is left as it was (the page is
 *    [partly] translated); one whose translation is its own text (a name) is left as it was too.
 * 3. The page is drawn at [TranslatedPages.DPI]; the colours of each block to replace are read
 *    from it; the blocks are painted over (never what stays as it is); the picture becomes the new
 *    page; and the translations are fitted (see [TextFit]) and drawn as real text on a page of a
 *    "stamp" PDF, put over the picture once [STAMP_PAGES] pages are stamped (or the run ends), so
 *    no more than that many pages' text is held at once.
 * A page with nothing to translate is in the new PDF as a picture of itself ([noText]); those
 * before the first page with something to translate are drawn once there is one, so a PDF with
 * nothing to translate is never drawn at all. Then the title and the bookmarks are translated.
 */
private class PdfTranslator(
    private val engine: PdfToolsEngine,
    private val input: PdfInput,
    private val pages: List<Int>,
    private val translator: TextTranslator,
    private val ocr: OcrEngine?,
    private val source: String,
    private val target: String,
    private val check: () -> Unit,
    private val onProgress: (TranslateProgress) -> Unit,
) {
    private val noText = ArrayList<Int>()
    private val unread = ArrayList<Int>()
    private val partly = ArrayList<Int>()

    /** Short texts already translated in this run (a running head, a label), most recently used last. */
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE_ENTRIES
    }

    /** Whether a page so far had something to translate. */
    private var translatedAny = false

    /** Pages with nothing to translate before the first page with something: drawn once there is one. */
    private val waiting = ArrayList<Int>()

    /** Text recognition, once a scanned page needed it. */
    private var recognizer: OcrRecognizer? = null

    /** Why text recognition can't be had in this run, once it couldn't: the scanned pages after are unread too. */
    private var ocrUnavailable: OcrUnavailableException? = null

    /** The stamp being drawn, and the new pages waiting for its text, in order. */
    private var stamp: PdfDocument? = null
    private val stamped = ArrayList<Stamped>()

    private lateinit var view: PDDocument
    private lateinit var renderer: PdfRenderer
    private lateinit var out: PDDocument

    /** Makes the new PDF at [file], calling [onWriting] just before it starts writing it. */
    suspend fun make(file: File, onWriting: () -> Unit): TranslateOutcome {
        val shown = engine.viewOf(input, check)
        check()
        engine.load(shown.file, shown.password).use { doc ->
            view = doc
            ParcelFileDescriptor.open(shown.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                // It refuses a PDF it can't open (a password): never one of Usugami's views.
                renderer = try { PdfRenderer(descriptor) } catch (e: SecurityException) { throw IOException("The PDF renderer refused the PDF", e) }
                renderer.use {
                    if (renderer.pageCount != doc.numberOfPages || pages.last() >= doc.numberOfPages) {
                        throw IOException("The PDF renderer reads ${renderer.pageCount} pages, PDFBox ${doc.numberOfPages}")
                    }
                    PDDocument(engine.memory()).use { made ->
                        out = made
                        try {
                            translatePages()
                        } finally {
                            recognizer?.close()
                            stamp?.close()
                        }
                        if (!translatedAny) {
                            // Only text recognition could have found something to translate: why it couldn't is the run's answer.
                            ocrUnavailable?.let { throw it }
                            throw NothingToTranslate(unreadable = unread.isNotEmpty())
                        }
                        describe()
                        onProgress(TranslateProgress(TranslateProgress.Stage.PAGE, pages.size, pages.size))
                        // The last check for a cancel: nothing suspends from here on.
                        check()
                        currentCoroutineContext().ensureActive()
                        onWriting()
                        made.save(file)
                        return TranslateOutcome(PdfFile(file, made.numberOfPages, 0), noText, unread, partly)
                    }
                }
            }
        }
    }

    private suspend fun translatePages() {
        // A page with no contents shows only what its comments and fields draw, if anything.
        val annotated = withFormsOrComments(view, pages)
        var done = 0
        for (batch in pages.chunked(READ_BATCH)) {
            check()
            val read = HashMap<Int, PageLines?>()
            PageLineReader(batch.toSet(), onStart = { check() }) { index, lines -> read[index] = lines }.read(view)
            for (index in batch) {
                check()
                currentCoroutineContext().ensureActive()
                onProgress(TranslateProgress(TranslateProgress.Stage.PAGE, done, pages.size))
                // A page without contents is neither started nor given.
                page(index, read[index], started = index in read, annotated = index in annotated)
                done++
            }
        }
        flushStamp()
    }

    /**
     * Page [index]: its [lines], or null when it [started] but couldn't be read; [annotated] when it
     * has comments or fields that show.
     */
    private suspend fun page(index: Int, lines: PageLines?, started: Boolean, annotated: Boolean) {
        val frame = view.getPage(index).shownFrame()
        val width = frame.shownWidth
        val height = frame.shownHeight
        if (!(width > 0f && height > 0f && width.isFinite() && height.isFinite())) throw PageNotDrawn(index, null)
        var blocks = lines?.let { TextBlocks.group(it.lines, width, height) }.orEmpty()
        var chosen = blocks.filter { TranslatableText.isWorthTranslating(it.text) }
        if (chosen.isEmpty()) {
            // Something drawn, but no letters: a scan, perhaps with a page number. One that shows
            // letters PageLineReader leaves alone (turned text, say) has nothing to translate.
            if ((started || annotated) && lines?.hasText != true) {
                val scanned = readScan(index, width, height)
                if (scanned == null) {
                    unread += index
                    plain(index)
                    return
                }
                blocks = TextBlocks.group(scanned, width, height)
                chosen = blocks.filter { TranslatableText.isWorthTranslating(it.text) }
            }
            if (chosen.isEmpty()) {
                noText += index
                plain(index)
                return
            }
        }
        translatedAny = true
        flushWaiting(index)

        val drawn = ArrayList<Pair<SourceBlock, String>>()
        var leftAsItWas = false
        for (block in chosen) {
            val translated = translate(block.text)
            when {
                translated == null || translated.isBlank() -> leftAsItWas = true
                // A name, or text already in the target language: it keeps its own look.
                translated.trim() == block.text.trim() -> Unit
                !TranslationOverlay.canDraw(translated, block.bold) -> leftAsItWas = true
                else -> drawn += block to translated.trim()
            }
        }
        if (leftAsItWas) partly += index
        if (drawn.isEmpty()) {
            write(newPage(width, height), picture(index, width, height) {}, null)
            return
        }

        val replaced = Collections.newSetFromMap(IdentityHashMap<SourceBlock, Boolean>()).apply { drawn.forEach { add(it.first) } }
        val kept = blocks.filter { it !in replaced }
        var colors: List<TranslationOverlay.Colors> = emptyList()
        val image = picture(index, width, height) { bitmap ->
            val perX = bitmap.width / width
            val perY = bitmap.height / height
            fun rect(left: Float, top: Float, right: Float, bottom: Float, size: Float, padded: Boolean): Rect {
                val padX = if (padded) max(MIN_PAD, PAD_X * size) else 0f
                val padY = if (padded) max(MIN_PAD, PAD_Y * size) else 0f
                return TranslationOverlay.pixels(left, top, right, bottom, padX, padY, perX, perY, bitmap.width, bitmap.height)
            }
            fun reach(share: Float, size: Float, per: Float) = (share * size * per).toInt()
            // Each line's own box, where its letters are.
            val boxes = IdentityHashMap<SourceLine, Rect>()
            for (block in blocks) for (line in block.lines) boxes[line] = rect(line.left, line.top, line.right, line.bottom, block.size, padded = false)
            // Read before anything is painted over: the colours of what is replaced, and all of the
            // letters of what stays near it, which painting must leave whole.
            colors = drawn.map { (block, _) ->
                val others = blocks.filter { it !== block }.flatMap { it.lines }.mapNotNull(boxes::get)
                TranslationOverlay.colors(bitmap, rect(block.left, block.top, block.right, block.bottom, block.size, padded = true), others)
            }
            val nobody = TranslationOverlay.Painter(bitmap, emptyList())
            val near = drawn.map { (block, _) -> rect(block.left, block.top, block.right, block.bottom, block.size * 2, padded = true) }
            val keep = kept.flatMap { block -> block.lines.map { line -> line to block.size } }.mapNotNull { (line, size) ->
                val box = rect(line.left, line.top, line.right, line.bottom, size, padded = false)
                if (near.none { Rect.intersects(it, box) }) return@mapNotNull null
                val reach = reach(KEPT_GROW, size, perY)
                nobody.grown(box, TranslationOverlay.colors(bitmap, box).background, reach, reach, reach(KEPT_GROW, size, perX))
            }
            // Line by line, top to bottom, so a box over a varied background takes its colours from what is painted above it.
            val lines = drawn.flatMapIndexed { i, (block, _) -> block.lines.map { line -> Triple(line, block.size, colors[i]) } }.sortedBy { it.first.top }
            val painter = TranslationOverlay.Painter(bitmap, keep, lines.mapNotNull { boxes[it.first] })
            for ((line, size, lineColors) in lines) {
                val box = rect(line.left, line.top, line.right, line.bottom, size, padded = true)
                val grown = painter.grown(box, lineColors.background, reach(GROW_UP, size, perY), reach(GROW_DOWN, size, perY), reach(GROW_SIDE, size, perX))
                painter.paint(grown, lineColors, boxes[line])
            }
        }
        val page = newPage(width, height)

        val stampDoc = stamp ?: PdfDocument().also { stamp = it }
        val (stampWidth, stampHeight) = stampSize(width, height)
        val onStamp = stampDoc.startPage(PdfDocument.PageInfo.Builder(stampWidth, stampHeight, stamped.size + 1).create())
        // Drawn in points of the page, and put over it scaled back exactly (see write).
        onStamp.canvas.scale(stampWidth / width, stampHeight / height)
        for ((i, pair) in drawn.withIndex()) {
            val (block, text) = pair
            val area = BlockArea(block.left, block.top, block.right, block.bottom)
            val limits = TextFit.limits(block, blocks, width, height)
            val fit = TextFit.fit(text, block.size, area, limits, TranslationOverlay.measurer(block.bold, block.align, block.rtl))
            TranslationOverlay.draw(onStamp.canvas, text, fit, block.bold, colors[i].ink, block.align, block.rtl)
        }
        stampDoc.finishPage(onStamp)
        stamped += Stamped(page, image, stampWidth, stampHeight)
        if (stamped.size >= STAMP_PAGES) flushStamp()
    }

    /** Page [index] as a picture of itself: now, or once a page has something to translate (see [waiting]). */
    private fun plain(index: Int) {
        if (!translatedAny) {
            waiting += index
            return
        }
        drawPlain(index)
    }

    /**
     * The pages waiting to be drawn as they are, now that page [current] has something to translate:
     * each told as it is drawn, then [current] again.
     */
    private fun flushWaiting(current: Int) {
        if (waiting.isEmpty()) return
        for (index in waiting) {
            check()
            tellPage(index)
            drawPlain(index)
        }
        waiting.clear()
        tellPage(current)
    }

    /** Tells that page [index] is being worked on. */
    private fun tellPage(index: Int) {
        // The pages are sorted, each once.
        onProgress(TranslateProgress(TranslateProgress.Stage.PAGE, pages.binarySearch(index), pages.size))
    }

    private fun drawPlain(index: Int) {
        val frame = view.getPage(index).shownFrame()
        write(newPage(frame.shownWidth, frame.shownHeight), picture(index, frame.shownWidth, frame.shownHeight) {}, null)
    }

    /** A new page of the new PDF, [width] × [height] points, after those before it. */
    private fun newPage(width: Float, height: Float): PDPage = PDPage(PDRectangle(width, height)).also(out::addPage)

    /**
     * Page [index] drawn as shown, [width] × [height] points, at [TranslatedPages.DPI], changed by
     * [edit], as an image of the new PDF. With half the pixels each time memory runs out.
     */
    private fun picture(index: Int, width: Float, height: Float, edit: (Bitmap) -> Unit): PDImageXObject = try {
        renderer.openPage(index).use { page -> picture(page, index, width, height, edit) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        // The storage (PDFBox's scratch files), not the page: also PageNotDrawn itself.
        throw e
    } catch (e: Exception) {
        throw PageNotDrawn(index, e)
    }

    private fun picture(page: PdfRenderer.Page, index: Int, width: Float, height: Float, edit: (Bitmap) -> Unit): PDImageXObject {
        var (pixelsWide, pixelsHigh) = TranslatedPages.pixelSize(width, height)
        while (true) {
            var bitmap: Bitmap? = null
            try {
                bitmap = createBitmap(pixelsWide, pixelsHigh)
                bitmap.eraseColor(Color.WHITE)
                PageRendering.render(page, bitmap, width, height)
                check()
                edit(bitmap)
                return Redactor.encode(out, bitmap, check)
            } catch (e: OutOfMemoryError) {
                bitmap?.recycle()
                bitmap = null
                if (pixelsWide.toLong() * pixelsHigh <= MIN_PIXELS) throw PageNotDrawn(index, e)
                pixelsWide = max(1, (pixelsWide / SQRT_2).toInt())
                pixelsHigh = max(1, (pixelsHigh / SQRT_2).toInt())
            } finally {
                bitmap?.recycle()
            }
        }
    }

    /** The stamp's page for a page [width] × [height] points: whole points, at most [MAX_STAMP_SIDE] on a side. */
    private fun stampSize(width: Float, height: Float): Pair<Int, Int> {
        val scale = min(1f, MAX_STAMP_SIDE / max(width, height))
        return max(1, ceil(width * scale).toInt()) to max(1, ceil(height * scale).toInt())
    }

    /** Puts the stamp's text over the pages waiting for it, as forms, and closes the stamp. */
    private fun flushStamp() {
        val doc = stamp ?: return
        stamp = null
        val bytes = try {
            ByteArrayOutputStream().also(doc::writeTo).toByteArray()
        } finally {
            doc.close()
        }
        check()
        // The text and its fonts are copied out as they are imported, so the stamp can go then.
        PDDocument.load(bytes).use { forms ->
            val layer = LayerUtility(out)
            for ((i, waiting) in stamped.withIndex()) {
                check()
                write(waiting.page, waiting.image, layer.importPageAsForm(forms, i), waiting.stampWidth, waiting.stampHeight)
            }
        }
        stamped.clear()
    }

    /** [page]'s content: [image] over all of it, and [form], a stamp page [stampWidth] × [stampHeight] points, scaled to it. */
    private fun write(page: PDPage, image: PDImageXObject, form: PDFormXObject?, stampWidth: Int = 1, stampHeight: Int = 1) {
        val width = page.mediaBox.width
        val height = page.mediaBox.height
        PDPageContentStream(out, page, PDPageContentStream.AppendMode.OVERWRITE, true).use { content ->
            content.saveGraphicsState()
            content.drawImage(image, 0f, 0f, width, height)
            content.restoreGraphicsState()
            if (form != null) {
                content.saveGraphicsState()
                content.transform(Matrix(width / stampWidth, 0f, 0f, height / stampHeight, 0f, 0f))
                content.drawForm(form)
                content.restoreGraphicsState()
            }
        }
    }

    /**
     * [text] translated, its pieces one by one (see [TextChunks]); null when the translator fails
     * on it. Packs gone ([TranslationUnavailableException]) and a cancel end the run.
     */
    private suspend fun translate(text: String): String? {
        cache[text]?.let { return it }
        val pieces = TextChunks.split(text)
        if (pieces.isEmpty()) return text
        val translated = ArrayList<String>(pieces.size)
        for (piece in pieces) {
            // ML Kit can't stop a translation once it has started: only the wait for it stops.
            check()
            translated += try {
                translator.translate(piece)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranslationUnavailableException) {
                throw e
            } catch (_: Exception) {
                return null
            }
            check()
        }
        return TextChunks.join(translated).also { if (text.length <= CACHED_LENGTH) cache[text] = it }
    }

    /**
     * The lines text recognition reads on scanned page [index], [width] × [height] points; null when
     * it can't be read: no text recognition for [source]'s script, it can't be had on this phone now
     * (see [ocrUnavailable]), or it failed on the page. Text recognition is made ready (its model
     * downloaded) the first time a page needs it.
     */
    private suspend fun readScan(index: Int, width: Float, height: Float): List<SourceLine>? {
        val language = ocrLanguageFor(source) ?: return null
        val engine = ocr ?: return null
        // A page that only looks scanned (a photo, a chart) is no reason to lose the pages translated.
        if (ocrUnavailable != null) return null
        var downloaded = false
        val reader = recognizer ?: try {
            engine.ensureReady(language) { fraction ->
                check()
                downloaded = true
                onProgress(downloading(TranslateProgress.Stage.DOWNLOAD_OCR, fraction))
            }
            check()
            engine.open(language).also { recognizer = it }
        } catch (e: OcrUnavailableException) {
            ocrUnavailable = e
            if (downloaded) tellPage(index)
            return null
        }
        // The download is over: back to the page.
        if (downloaded) tellPage(index)
        val picture = try {
            drawForReading(renderer, index)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        } catch (_: OutOfMemoryError) {
            return null
        }
        val toPointsX = width / picture.width
        val toPointsY = height / picture.height
        val lines = try {
            // The recognizer recycles the picture once it is done with it.
            reader.recognize(picture)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OcrUnavailableException) {
            ocrUnavailable = e
            return null
        } catch (_: Exception) {
            return null
        } catch (_: OutOfMemoryError) {
            return null
        }
        check()
        return ScannedLines.of(lines, toPointsX, toPointsY)
    }

    /** The new PDF's title (the PDF's own, translated) and language, and its bookmarks to the pages it has. */
    private suspend fun describe() {
        out.documentCatalog.language = target
        val title = view.documentInformation?.title?.trim().orEmpty()
        if (title.isNotEmpty()) {
            val translated = try {
                if (TranslatableText.isWorthTranslating(title)) translate(title) else title
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The pages are made: the title is no reason to fail them.
                null
            }
            if (!translated.isNullOrBlank()) out.documentInformation.title = translated
        }
        try {
            bookmarks()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            out.documentCatalog.documentOutline = null
        } catch (_: StackOverflowError) {
            out.documentCatalog.documentOutline = null
        }
    }

    /**
     * The PDF's bookmarks that lead to the pages chosen, their titles translated, each leading to its
     * page in the new PDF; those under a bookmark to a page left out move up to its place.
     */
    private suspend fun bookmarks() {
        val outline = view.documentCatalog.documentOutline ?: return
        val indexOf = IdentityHashMap<COSDictionary, Int>()
        view.pages.forEachIndexed { i, page -> indexOf[page.cosObject] = i }
        val newIndex = HashMap<Int, Int>()
        pages.forEachIndexed { i, index -> newIndex[index] = i }
        val seen = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        var count = 0
        val root = PDDocumentOutline()

        suspend fun copy(from: PDOutlineNode, into: PDOutlineNode, depth: Int) {
            if (depth > MAX_BOOKMARK_DEPTH) return
            var item: PDOutlineItem? = from.firstChild
            while (item != null && seen.add(item.cosObject) && count++ < MAX_BOOKMARKS) {
                check()
                val page = try { item.findDestinationPage(view) } catch (_: IOException) { null } catch (_: RuntimeException) { null }
                val at = page?.let { indexOf[it.cosObject] }?.let { newIndex[it] }
                if (at == null) {
                    copy(item, into, depth + 1)
                } else {
                    val title = item.title?.trim().orEmpty()
                    val node = PDOutlineItem()
                    node.title = (if (TranslatableText.isWorthTranslating(title)) translate(title) else null) ?: title
                    node.destination = destination(item, out.getPage(at), view.getPage(pages[at]).shownFrame())
                    into.addLast(node)
                    copy(item, node, depth + 1)
                }
                item = item.nextSibling
            }
        }
        copy(outline, root, 0)
        if (root.hasChildren()) out.documentCatalog.documentOutline = root
    }

    /** Where [item] leads, on [page] of the new PDF: as far down it as on the page it led to ([frame], as shown), else the whole page. */
    private fun destination(item: PDOutlineItem, page: PDPage, frame: PageFrame): PDPageDestination {
        val old = try {
            when (val d = item.destination ?: (item.action as? PDActionGoTo)?.destination) {
                is PDNamedDestination -> view.documentCatalog.findNamedDestinationPage(d)
                else -> d
            }
        } catch (_: IOException) {
            null
        }
        if (old is PDPageXYZDestination && old.top >= 0 && (old.left >= 0 || frame.rotation % 180 == 0)) {
            val left = if (old.left >= 0) old.left.toFloat() else frame.left
            val (_, shownY) = frame.toShown(left, old.top.toFloat())
            if (shownY.isFinite()) {
                return PDPageXYZDestination().apply {
                    this.page = page
                    top = shownY.roundToInt().coerceIn(0, page.mediaBox.height.roundToInt())
                }
            }
        }
        return PDPageFitDestination().apply { this.page = page }
    }

    /** A new page with its picture, waiting for the stamp's text. */
    private class Stamped(val page: PDPage, val image: PDImageXObject, val stampWidth: Int, val stampHeight: Int)

    companion object {
        /** Pages read at a time. */
        const val READ_BATCH = 20

        /** Pages of a stamp PDF: then its text is put on its pages and it is closed. */
        const val STAMP_PAGES = 20

        /** Viewers take pages up to 200 inches on a side. */
        const val MAX_STAMP_SIDE = 14_400f

        /** Below this many pixels a page that runs out of memory isn't drawn smaller again. */
        const val MIN_PIXELS = 10_000L
        val SQRT_2 = sqrt(2.0).toFloat()

        /** A block is painted over this share of its size beyond its letters' boxes, sideways and up and down, and at least [MIN_PAD] points. */
        const val PAD_X = 0.06f
        const val PAD_Y = 0.04f
        const val MIN_PAD = 0.5f

        /** How far, in font sizes, a painted line's box may grow over its letters' strokes beyond it (see TranslationOverlay.Painter.grown). */
        const val GROW_UP = 0.25f
        const val GROW_DOWN = 0.3f
        const val GROW_SIDE = 0.25f

        /** How far, in font sizes, what stays near a painted box is kept beyond its letters' boxes, over its strokes. */
        const val KEPT_GROW = 0.15f

        /** Texts up to this long are kept once translated, at most [CACHE_ENTRIES] of them. */
        const val CACHED_LENGTH = 300
        const val CACHE_ENTRIES = 500

        const val MAX_BOOKMARKS = 10_000
        const val MAX_BOOKMARK_DEPTH = 32
    }
}

/**
 * What text recognition read on a scanned page, as the lines of the page, each with its font size
 * and a box as a font's would be (from its ascent to its descent), in points; a tilted line's
 * translation is drawn level, over the bounds of its corners.
 *
 * Text recognition's boxes reach from a line's tallest letters to its lowest, so how much of the
 * font size they take depends on the letters: about 0.7 of it for capitals, digits and letters
 * with an ascender or an accent ("Service des achats"), 0.88 with a descender as well ("Paris,
 * gâteaux"), 0.48 for small letters alone ("une rue"). A line's size is its height over that share,
 * then the median of the sizes of the page's lines within [SIMILAR] of it, so the lines of a
 * paragraph get one size and stay together (see [TextBlocks]). Its baseline is its box's bottom, or
 * a descender's depth above it; its box is then [ASCENT] of the size above that, [DESCENT] below.
 */
internal object ScannedLines {
    /** Sizes within this share of each other are one size. */
    private const val SIMILAR = 0.15f

    /** How high capitals, digits, ascenders and accents reach, and small letters alone, as shares of the size… */
    private const val CAPITALS = 0.7f
    private const val SMALL_LETTERS = 0.48f

    /** …and how deep a descender reaches. */
    private const val DESCENDER = 0.18f

    /** Writing other than the Latin alphabet's: its box's height as a share of the size. */
    private const val OTHER_SCRIPT = 0.9f

    /** A font's box, as shares of its size: up from the baseline and down from it. */
    private const val ASCENT = 0.9f
    private const val DESCENT = 0.22f

    private const val TALL = "bdfhklt"
    private const val DEEP = "gjpqyçQ,;()[]{}|/"

    /** [lines] read on a picture [toPointsX] and [toPointsY] points to the pixel, as lines of the page. */
    fun of(lines: List<OcrLine>, toPointsX: Float, toPointsY: Float): List<SourceLine> {
        class Read(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float, val size: Float, val deep: Boolean)
        fun distance(a: Point, b: Point) = hypot((a.x - b.x) * toPointsX, (a.y - b.y) * toPointsY)
        val read = lines.mapNotNull { line ->
            val text = ExportText.clean(line.text).trim()
            if (text.isEmpty()) return@mapNotNull null
            val xs = line.corners.map { it.x * toPointsX }
            val ys = line.corners.map { it.y * toPointsY }
            // Across the line, from its top edge to its bottom edge, however it is tilted.
            val (topLeft, topRight, bottomRight, bottomLeft) = line.corners
            val height = (distance(topLeft, bottomLeft) + distance(topRight, bottomRight)) / 2
            if (!(height > 0f)) return@mapNotNull null
            val deep = text.any { it in DEEP }
            Read(text, xs.min(), ys.min(), xs.max(), ys.max(), height / share(text, deep), deep)
        }
        return read.map { line ->
            val size = PageLineReader.median(read.filter { abs(it.size - line.size) <= SIMILAR * line.size }.map { it.size })
            val baseline = line.bottom - if (line.deep) DESCENDER * size else 0f
            SourceLine(
                line.text, line.left, min(line.top, baseline - ASCENT * size), line.right, max(line.bottom, baseline + DESCENT * size), size,
                bold = false, rtl = PageLineReader.rightToLeft(line.text),
            )
        }
    }

    /** How much of its font size a line of [text] takes from its tallest letters to its lowest, [deep] when it has a descender. */
    private fun share(text: String, deep: Boolean): Float {
        if (text.any { it.isLetter() && it.code > 0x24F }) return OTHER_SCRIPT
        val tall = text.any { it.isUpperCase() || it.isDigit() || it in TALL || it in "ij" || it.isLetter() && it.code in 0xC0..0x24F }
        return (if (tall) CAPITALS else SMALL_LETTERS) + if (deep) DESCENDER else 0f
    }
}
