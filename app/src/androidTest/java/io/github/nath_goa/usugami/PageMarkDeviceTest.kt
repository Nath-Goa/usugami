package io.github.nath_goa.usugami

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
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
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
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
 * size and opacity chosen; changed and taken off again, leaving each page as it was. PDFs made from
 * images get it as they are made, and lose it again when it is turned off. The tools make their PDFs
 * of a marked PDF's pages without it.
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

    /** Each page's characters, with the page they are on. */
    private fun glyphsByPage(doc: PDDocument): List<List<TextPosition>> = (1..doc.numberOfPages).map { number ->
        ArrayList<TextPosition>().also { glyphs ->
            object : PDFTextStripper() {
                override fun processTextPosition(text: TextPosition) {
                    glyphs += text
                }
            }.apply {
                startPage = number
                endPage = number
            }.getText(doc)
        }
    }

    /**
     * Checks that each page of [file] has the mark once, in [style], where it goes on the page as shown,
     * written upright.
     */
    private fun checkMarked(file: File, style: MarkStyle) = PDDocument.load(file).use { doc ->
        for ((index, glyphs) in glyphsByPage(doc).withIndex()) {
            val page = doc.getPage(index)
            val text = glyphs.joinToString("") { it.unicode }
            assertEquals("Page ${index + 1} has the mark once: $text", 1, Regex(MarkLayout.TEXT).findAll(text).count())
            val word = glyphs.drop(text.indexOf(MarkLayout.TEXT)).take(MarkLayout.TEXT.length)
            val box = page.cropBox
            val frame = PageFrame(box.lowerLeftX, box.lowerLeftY, box.width, box.height, page.rotation)
            val place = MarkLayout.place(style, frame.shownWidth, frame.shownHeight)
            // The glyphs' places are on the page as it is, from its crop box's corner.
            fun shown(glyph: TextPosition, dx: Float = 0f, dy: Float = 0f): Pair<Float, Float> {
                val m = glyph.textMatrix
                return frame.toShown(box.lowerLeftX + m.translateX + dx, box.lowerLeftY + m.translateY + dy)
            }
            val (x, y) = shown(word.first())
            assertEquals("Page ${index + 1}: where the word starts", place.x, x, 0.5f)
            assertEquals("Page ${index + 1}: its baseline", place.y, y, 0.5f)
            // Upright as shown: it runs to the right, and its letters stand up.
            val m = word.first().textMatrix
            val (rightX, rightY) = shown(word.first(), m.scaleX, m.shearY)
            assertEquals(x + place.fontSize, rightX, 0.05f)
            assertEquals(y, rightY, 0.05f)
            val (upX, upY) = shown(word.first(), m.shearX, m.scaleY)
            assertEquals(x, upX, 0.05f)
            assertEquals(y + place.fontSize, upY, 0.05f)
            assertTrue("Page ${index + 1}: the word ends before the page's right edge", shown(word.last()).first < frame.shownWidth)

            val xObjects = page.resources.xObjectNames.filter { page.resources.getXObject(it).cosObject.containsKey(PageMark.MARKER) }
            val form = page.resources.getXObject(xObjects.single()) as PDFormXObject
            val state = form.resources.extGStateNames.single().let { form.resources.getExtGState(it) }
            assertEquals(style.alpha, state.nonStrokingAlphaConstant!!, 0.0001f)
        }
    }

    @Test fun marksEachPageAsShownChangesItAndTakesItOffAgain() {
        // The word's width the layout counts with is Helvetica Bold's.
        PDFBoxResourceLoader.init(app)
        assertEquals(MarkLayout.TEXT_WIDTH, PDType1Font.HELVETICA_BOLD.getStringWidth(MarkLayout.TEXT) / 1000, 0.0001f)
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
            assertFalse(PDFTextStripper().getText(doc).contains(MarkLayout.TEXT))
        }
        assertFalse(marks.apply(file, null))
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

    /** How many times each PDF of [files] says "Usugami", page by page. */
    private fun marksOn(files: List<PdfFile>): List<List<Int>> = files.map { pdf ->
        PDDocument.load(pdf.file).use { doc ->
            glyphsByPage(doc).map { page -> Regex(MarkLayout.TEXT).findAll(page.joinToString("") { it.unicode }).count() }
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
        assertTrue(made.all { it.mark == MarkStyle() })

        onMain { vm.setMark(false) }
        waitUntil("the mark to come off") { !vm.marking.value && vm.result.value!!.all { it.mark == null } }
        assertEquals(listOf(listOf(0), listOf(0)), marksOn(onMain { vm.result.value!! }))
        assertFalse(vm.saved.value)
        onMain { vm.startOver() }
        runBlocking { vm.awaitFileWork() }
    }
}
