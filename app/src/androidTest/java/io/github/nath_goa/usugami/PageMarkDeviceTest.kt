package io.github.nath_goa.usugami

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PointF
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.github.nath_goa.usugami.core.Compression
import io.github.nath_goa.usugami.core.MarkLayout
import io.github.nath_goa.usugami.core.MarkStyle
import io.github.nath_goa.usugami.core.PageFrame
import io.github.nath_goa.usugami.data.MarkSetting
import io.github.nath_goa.usugami.data.PageMark
import io.github.nath_goa.usugami.data.PdfCompression
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.data.SettingsStore
import io.github.nath_goa.usugami.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The Usugami mark on a PDF's pages: in the bottom-right corner of each page as shown, upright, in the
 * size and opacity chosen, drawn as outlines rather than written as text; changed and taken off again,
 * leaving each page as it was. PDFs made from images get it as they are made, and lose it again when
 * it is turned off. The tools make their PDFs of a marked PDF's pages without it.
 */
class PageMarkDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val dir = File(app.cacheDir, "test-mark").apply { mkdirs() }
    private val marks = PageMark(app)

    @After fun tearDown() {
        dir.deleteRecursively()
        // Later tests start with the mark off, as it is at first.
        SettingsStore(app).setMark(MarkSetting())
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(100)
        }
    }

    /**
     * Three pages: A4 upright, Letter turned a quarter clockwise with a crop box smaller than the page,
     * and a blank one with neither content nor resources. The first two share their resources.
     */
    private fun pdf(): File {
        PDFBoxResourceLoader.init(app)
        val file = File(dir, "pages.pdf")
        PDDocument().use { doc ->
            val shared = PDResources().apply { put(COSName.getPDFName("F1"), PDType1Font.HELVETICA) }
            val upright = PDPage(PDRectangle.A4).apply { resources = shared }
            val turned = PDPage(PDRectangle.LETTER).apply {
                resources = shared
                rotation = 90
                cropBox = PDRectangle(50f, 60f, 500f, 650f)
            }
            for (page in listOf(upright, turned)) doc.addPage(page)
            writeText(doc, upright, "PAGE ONE")
            writeText(doc, turned, "PAGE TWO", x = 100f, y = 400f)
            doc.addPage(PDPage(PDRectangle.A4).apply { cosObject.removeItem(COSName.RESOURCES) })
            doc.save(file)
        }
        return file
    }

    /** What each page is: its content's bytes, whether it has any, and its resources' names. */
    private fun pages(file: File): List<Triple<ByteArray, Boolean, Set<String>>> = PDDocument.load(file).use { doc ->
        doc.pages.map { page ->
            val content = page.contentStreams.asSequence().flatMap { it.createInputStream().use { s -> s.readBytes() }.asSequence() }.toList().toByteArray()
            val resources = page.cosObject.getDictionaryObject(COSName.RESOURCES) as? COSDictionary
            val names = resources?.keySet()?.flatMap { key ->
                val inner = resources.getDictionaryObject(key) as? COSDictionary
                inner?.keySet()?.map { "${key.name}/${it.name}" } ?: listOf(key.name)
            }.orEmpty().toSet()
            Triple(content, page.cosObject.containsKey(COSName.CONTENTS), names)
        }
    }

    /** A shape filled on a page: the points of its outline on the page, and its grey and opacity. */
    private class Fill(val points: List<PointF>, val gray: Float, val alpha: Float)

    /** The shapes filled on [page], as a PDF viewer draws them. */
    private fun fills(page: PDPage): List<Fill> = ArrayList<Fill>().also { fills ->
        val points = ArrayList<PointF>()
        object : PDFGraphicsStreamEngine(page) {
            private var current = PointF()

            override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
                points += listOf(p0, p1, p2, p3)
            }

            override fun drawImage(pdImage: PDImage) = Unit
            override fun clip(windingRule: Path.FillType) = Unit

            override fun moveTo(x: Float, y: Float) {
                current = PointF(x, y)
                points += current
            }

            override fun lineTo(x: Float, y: Float) = moveTo(x, y)

            override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
                points += listOf(PointF(x1, y1), PointF(x2, y2))
                moveTo(x3, y3)
            }

            override fun getCurrentPoint() = current
            override fun closePath() = Unit
            override fun endPath() = points.clear()
            override fun strokePath() = points.clear()

            override fun fillPath(windingRule: Path.FillType) {
                val state = graphicsState
                fills += Fill(points.toList(), state.nonStrokingColor.components.single(), state.nonStrokeAlphaConstant.toFloat())
                points.clear()
            }

            override fun fillAndStrokePath(windingRule: Path.FillType) = fillPath(windingRule)
            override fun shadingFill(shadingName: COSName) = Unit
        }.processPage(page)
    }

    /** Where the word's outline starts, in font sizes from its baseline's start: its "U". */
    private val start: PointF by lazy {
        var first: PointF? = null
        MarkLayout.drawWord(
            object : MarkLayout.Pen {
                override fun moveTo(x: Float, y: Float) {
                    if (first == null) first = PointF(x, y)
                }
                override fun lineTo(x: Float, y: Float) = Unit
                override fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = Unit
                override fun close() = Unit
            },
        )
        first!!
    }

    /**
     * Checks that each page of [file] has the mark once, in [style], where it goes on the page as shown,
     * upright, filled in its grey and opacity; and that it isn't text, which a search would find.
     */
    private fun checkMarked(file: File, style: MarkStyle) = PDDocument.load(file).use { doc ->
        assertFalse(PDFTextStripper().getText(doc).contains(MarkLayout.TEXT))
        for ((index, page) in doc.pages.withIndex()) {
            val fill = fills(page).singleOrNull() ?: throw AssertionError("Page ${index + 1} has the mark once")
            val box = page.cropBox
            val frame = PageFrame(box.lowerLeftX, box.lowerLeftY, box.width, box.height, page.rotation)
            val place = MarkLayout.place(style, frame.shownWidth, frame.shownHeight)
            val f = place.fontSize
            val shown = fill.points.map { frame.toShown(it.x, it.y) }
            // Its outlines reach as far as the word's letters do, from where the word starts.
            assertEquals("Page ${index + 1}: its left", place.x + MarkLayout.INK_LEFT * f, shown.minOf { it.first }, 0.05f)
            assertEquals("Page ${index + 1}: its right", place.x + MarkLayout.INK_RIGHT * f, shown.maxOf { it.first }, 0.05f)
            assertEquals("Page ${index + 1}: its bottom", place.y - MarkLayout.DESCENT * f, shown.minOf { it.second }, 0.05f)
            assertEquals("Page ${index + 1}: its top", place.y + MarkLayout.INK_TOP * f, shown.maxOf { it.second }, 0.05f)
            // Upright as shown: its "U" starts where it does in the word.
            assertEquals(place.x + start.x * f, shown.first().first, 0.05f)
            assertEquals(place.y + start.y * f, shown.first().second, 0.05f)
            assertTrue("Page ${index + 1}: the word ends before the page's right edge", shown.maxOf { it.first } < frame.shownWidth)
            assertEquals(MarkLayout.GRAY, fill.gray, 0.001f)
            assertEquals(style.alpha, fill.alpha, 0.0001f)
        }
    }

    @Test fun marksEachPageAsShownChangesItAndTakesItOffAgain() {
        // The word is as wide as Helvetica Bold made it before.
        PDFBoxResourceLoader.init(app)
        assertEquals(PDType1Font.HELVETICA_BOLD.getStringWidth(MarkLayout.TEXT) / 1000, MarkLayout.TEXT_WIDTH, 0.001f)
        val file = pdf()
        val before = pages(file)

        val style = MarkStyle()
        assertTrue(marks.apply(file, style))
        checkMarked(file, style)
        // As a pagination artifact, which screen readers pass over.
        val drawn = PDDocument.load(file).use { doc -> doc.getPage(0).contentStreams.asSequence().last().createInputStream().use { String(it.readBytes()) } }
        assertTrue(drawn, drawn.contains("/Artifact <</Type /Pagination /Subtype /Watermark>> BDC"))
        // Asked again, it is already there: the file is left as it is.
        val marked = file.readBytes()
        assertFalse(marks.apply(file, style))
        assertArrayEquals(marked, file.readBytes())
        // The result screen's picture of the first page is drawn from a copy of it without the mark.
        val first = File(dir, "first.pdf")
        assertTrue(marks.firstPageUnmarked(file, first))
        assertArrayEquals(before[0].first, pages(first).single().first)

        // At its largest, the word starts on the middle of each page as shown; once, fully opaque.
        val largest = MarkStyle(size = 100, opacity = 100)
        assertTrue(marks.apply(file, largest))
        checkMarked(file, largest)
        PDDocument.load(file).use { doc ->
            for (page in doc.pages) {
                val frame = PageFrame(page.cropBox.lowerLeftX, page.cropBox.lowerLeftY, page.cropBox.width, page.cropBox.height, page.rotation)
                assertEquals(frame.shownWidth / 2, MarkLayout.place(largest, frame.shownWidth, frame.shownHeight).x, 0.01f)
            }
        }

        // Taken off, each page is as it was: the same content and resources.
        assertTrue(marks.apply(file, null))
        val after = pages(file)
        for (i in before.indices) {
            assertArrayEquals("Page ${i + 1}'s content", before[i].first, after[i].first)
            assertEquals("Page ${i + 1} has content as before", before[i].second, after[i].second)
            assertEquals("Page ${i + 1}'s resources", before[i].third, after[i].third)
        }
        PDDocument.load(file).use { doc ->
            assertFalse(doc.getPage(2).cosObject.containsKey(COSName.RESOURCES))
            assertTrue(doc.pages.all { fills(it).isEmpty() })
        }
        assertFalse(marks.apply(file, null))
        assertFalse(marks.firstPageUnmarked(file, File(dir, "unmarked.pdf")))
    }

    /**
     * A PDF marked by Usugami 1.6.1, which wrote the word as text in Helvetica Bold: its mark is taken
     * off as exactly, and asked for again, it is drawn anew, as outlines.
     */
    @Test fun aMarkWrittenAsTextIsTakenOffAndDrawnAnew() {
        val file = pdf()
        val before = pages(file)
        val style = MarkStyle()
        for (wanted in listOf(null, style)) {
            markAsText(file, style)
            PDDocument.load(file).use { assertTrue(PDFTextStripper().getText(it).contains(MarkLayout.TEXT)) }
            assertTrue(marks.apply(file, wanted))
            if (wanted == null) {
                val after = pages(file)
                for (i in before.indices) assertArrayEquals("Page ${i + 1}'s content", before[i].first, after[i].first)
            } else {
                checkMarked(file, style)
            }
        }
    }

    /** Marks [file] in [style] as Usugami 1.6.1 did: the word written as text, and no version in its markers. */
    private fun markAsText(file: File, style: MarkStyle) {
        assertTrue(marks.apply(file, style))
        val old = File(dir, "old.pdf")
        PDDocument.load(file).use { doc ->
            for (page in doc.pages) {
                for (stream in page.contentStreams) (stream.cosObject.getDictionaryObject(PageMark.MARKER) as? COSDictionary)?.removeItem(COSName.getPDFName("Version"))
            }
            val resources = doc.getPage(0).resources
            val form = resources.xObjectNames.map { resources.getXObject(it) }.single { it.cosObject.containsKey(PageMark.MARKER) } as PDFormXObject
            form.cosObject.createOutputStream().use { it.write("/GS gs\n0.2 g\nBT\n/F 1 Tf\n(${MarkLayout.TEXT}) Tj\nET\n".toByteArray()) }
            form.resources.put(COSName.getPDFName("F"), PDType1Font.HELVETICA_BOLD)
            doc.save(old)
        }
        assertTrue(old.renameTo(file))
    }

    /** A page turned since it was marked, as Rotate PDF turns one, has the mark put back in its corner. */
    @Test fun aPageTurnedSinceItWasMarkedIsMarkedAgain() {
        val file = pdf()
        val style = MarkStyle()
        assertTrue(marks.apply(file, style))
        val turned = File(dir, "turned.pdf")
        PDDocument.load(file).use { doc ->
            doc.getPage(0).rotation = 90
            doc.save(turned)
        }
        assertTrue(marks.apply(turned, style))
        checkMarked(turned, style)
    }

    /**
     * The tools make their PDFs of a marked PDF's pages as they were, without the mark: also Split,
     * which puts each page's content in one stream, and Compress, which then doesn't keep the PDF as
     * it was. The previews, which Redact PDF draws its pictures from, show the pages without it too.
     */
    @Test fun toolsMakeTheirPdfsWithoutAnEarlierMark() {
        val file = pdf()
        val before = pages(file)
        assertTrue(marks.apply(file, MarkStyle(size = 100)))
        val engine = PdfToolsEngine(app)
        val input = PdfInput(4242, file.path, "marked.pdf", 3)
        val parts = engine.split(input, listOf(listOf(0), listOf(1), listOf(2)), "part")
        assertEquals(listOf(listOf(0), listOf(0), listOf(0)), marksOn(parts))
        for (i in parts.indices) assertArrayEquals("Part ${i + 1}'s content", before[i].first, pages(parts[i].file).single().first)
        assertEquals(listOf(listOf(0, 0, 0)), marksOn(listOf(engine.rotate(input, mapOf(1 to 90), "turned"))))
        val compressed = engine.compress(input, PdfCompression.RECOMMENDED, "compressed")
        assertFalse(compressed.keptOriginal)
        assertEquals(listOf(listOf(0, 0, 0)), marksOn(listOf(compressed)))
        // Made beside the PDFs picked, which are copied in there.
        File(app.cacheDir, "pdf-inputs").mkdirs()
        val view = engine.previewView(input)
        assertNotEquals(input.path, view.path)
        assertEquals(listOf(listOf(0, 0, 0)), marksOn(listOf(PdfFile(view.file, 3, 0))))
        engine.clearOutput()
        engine.delete(input)
    }

    /**
     * A PDF restricted by an owner password opens without one, but the mark can't go on it as it is:
     * Compress makes it anew, without the restriction, as every tool does, rather than keep it.
     */
    @Test fun aRestrictedPdfIsNeitherMarkedNorKeptAsItIs() {
        PDFBoxResourceLoader.init(app)
        val file = File(dir, "restricted.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            doc.protect(StandardProtectionPolicy("owner", "", AccessPermission().apply { setCanModify(false) }))
            doc.save(file)
        }
        assertThrows(IOException::class.java) { marks.apply(file, MarkStyle()) }
        val engine = PdfToolsEngine(app)
        val compressed = engine.compress(PdfInput(4243, file.path, "restricted.pdf", 1), PdfCompression.RECOMMENDED, "compressed")
        assertFalse(compressed.keptOriginal)
        PDDocument.load(compressed.file).use { assertFalse(it.isEncrypted) }
        assertTrue(marks.apply(compressed.file, MarkStyle()))
        engine.clearOutput()
    }

    /** How many times each PDF of [files] has the mark, page by page: the streams that draw it, which say so. */
    private fun marksOn(files: List<PdfFile>): List<List<Int>> = files.map { pdf ->
        PDDocument.load(pdf.file).use { doc ->
            doc.pages.map { page -> page.contentStreams.asSequence().count { it.cosObject.getDictionaryObject(PageMark.MARKER) is COSDictionary } }
        }
    }

    /**
     * Images to PDF, one PDF for each image: with the mark on, each PDF has it once it shows; taken off
     * on the result screen, it goes from each, and the result is no longer saved.
     */
    @Test fun imagesToPdfsEachGetTheMarkAndLoseItAgain() {
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        val images = (1..2).map { n ->
            val file = File(dir, "photo$n.jpg")
            file.outputStream().use { Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }
            Uri.fromFile(file)
        }
        onMain {
            vm.setMark(true)
            vm.addImages(images)
        }
        waitUntil("the images to be added") { vm.items.value.size == 2 && vm.pendingImports.value == 0 }
        onMain { vm.convert(merge = false, fileName = "marked", compression = Compression.RECOMMENDED) }
        waitUntil("the PDFs to be made") { vm.result.value != null }
        val made = onMain { vm.result.value!! }
        assertEquals(listOf(listOf(1), listOf(1)), marksOn(made))
        for (pdf in made) checkMarked(pdf.file, MarkStyle())
        assertTrue(made.all { it.mark == MarkStyle() })

        onMain { vm.setMark(false) }
        waitUntil("the mark to come off") { !vm.marking.value && vm.result.value!!.all { it.mark == null } }
        assertEquals(listOf(listOf(0), listOf(0)), marksOn(onMain { vm.result.value!! }))
        assertFalse(vm.saved.value)
        onMain { vm.startOver() }
        runBlocking { vm.awaitFileWork() }
    }
}
