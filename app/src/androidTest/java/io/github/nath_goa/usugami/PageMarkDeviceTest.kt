package io.github.nath_goa.usugami

import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.nath_goa.usugami.core.MarkLayout
import io.github.nath_goa.usugami.core.MarkStyle
import io.github.nath_goa.usugami.core.PageFrame
import io.github.nath_goa.usugami.data.PageMark
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Usugami mark on a PDF's pages: in the bottom-right corner of each page as shown, upright, in the
 * size and opacity chosen; changed and taken off again, leaving each page as it was.
 */
class PageMarkDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val dir = File(app.cacheDir, "test-mark").apply { mkdirs() }
    private val marks = PageMark(app)

    @After fun tearDown() {
        dir.deleteRecursively()
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
}
