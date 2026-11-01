package io.github.nath_goa.usugami

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.PointF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.core.os.BundleCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.nath_goa.usugami.core.EditLayout
import io.github.nath_goa.usugami.core.InkItem
import io.github.nath_goa.usugami.core.PageFrame
import io.github.nath_goa.usugami.core.PageItem
import io.github.nath_goa.usugami.core.PictureItem
import io.github.nath_goa.usugami.core.ShapeItem
import io.github.nath_goa.usugami.core.ShapeKind
import io.github.nath_goa.usugami.core.TextItem
import io.github.nath_goa.usugami.core.fromTopLeft
import io.github.nath_goa.usugami.data.PagePictures
import io.github.nath_goa.usugami.data.PageText
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.ui.EditHistory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Edit PDF: text, strokes, shapes and pictures land where the preview put them on pages as shown
 * (turned and cropped too), look as they should, leave other pages untouched, and survive Undo,
 * Redo and Android saving the screen's state.
 */
class PageEditsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = PdfToolsEngine(context)
    private val pictures = PagePictures(context)
    private val results = File(context.getExternalFilesDir(null), "test-results").apply { mkdirs() }
    private var nextId = 4001L

    private fun import(file: File): PdfInput = engine.import(Uri.fromFile(file), nextId++)

    private fun save(file: File, name: String): File = file.copyTo(File(results, name), overwrite = true)

    /**
     * Four pages, each with a black caption across the middle of its top half:
     * 1. A4, upright
     * 2. A4, turned clockwise (/Rotate 90)
     * 3. A4 with a crop box that leaves a 100-point border off on every side
     * 4. landscape A4, turned anticlockwise (/Rotate 270)
     */
    private fun mixedPages(name: String): File = File(context.cacheDir, name).also { file ->
        PDDocument().use { doc ->
            val landscape = PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)
            for ((index, box) in listOf(PDRectangle.A4, PDRectangle.A4, PDRectangle.A4, landscape).withIndex()) {
                val page = PDPage(box)
                when (index) {
                    1 -> page.rotation = 90
                    2 -> page.cropBox = PDRectangle(100f, 100f, box.width - 200f, box.height - 200f)
                    3 -> page.rotation = 270
                }
                doc.addPage(page)
                PDPageContentStream(doc, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA_BOLD, 18f)
                    content.newLineAtOffset(box.width / 2 - 60, box.height / 2)
                    content.showText("CAPTION ${listOf("A", "B", "C", "D")[index]}")
                    content.endText()
                }
            }
            doc.save(file)
        }
    }

    private fun frameOf(page: PDPage) = page.cropBox.let { PageFrame(it.lowerLeftX, it.lowerLeftY, it.width, it.height, page.rotation) }

    /** Page [index] of [file] as shown, [scale] pixels per point, on white. */
    private fun render(file: File, index: Int, scale: Float = 2f): Bitmap =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(index).use { page ->
                    Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }

    /** Pixels of [bitmap] that [match], as (x, y) with y down. */
    private fun pixels(bitmap: Bitmap, match: (Int) -> Boolean): List<Pair<Int, Int>> {
        val all = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(all, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return all.indices.filter { match(all[it]) }.map { it % bitmap.width to it / bitmap.width }
    }

    /** Where [found] pixels are, in points on the page as shown: left, top, right, bottom. */
    private fun extent(found: List<Pair<Int, Int>>, scale: Float) = floatArrayOf(
        found.minOf { it.first } / scale, found.minOf { it.second } / scale,
        (found.maxOf { it.first } + 1) / scale, (found.maxOf { it.second } + 1) / scale,
    )

    private fun assertExtent(message: String, expected: FloatArray, actual: FloatArray, tolerance: Float) {
        for ((i, side) in listOf("left", "top", "right", "bottom").withIndex()) {
            assertEquals("$message, $side", expected[i], actual[i], tolerance)
        }
    }

    private fun red(pixel: Int) = Color.red(pixel) > 160 && Color.green(pixel) < 90 && Color.blue(pixel) < 90
    private fun blue(pixel: Int) = Color.blue(pixel) > 120 && Color.red(pixel) < 80 && Color.green(pixel) < 140
    private fun green(pixel: Int) = Color.green(pixel) > 90 && Color.red(pixel) < 80 && Color.blue(pixel) < 90
    private fun magenta(pixel: Int) = Color.red(pixel) > 180 && Color.blue(pixel) > 180 && Color.green(pixel) < 80

    /** A path the page draws: its points in the page's own coordinates, whether it was filled, and its line width. */
    private class DrawnPath(val points: List<PointF>, val filled: Boolean, val lineWidth: Float, val color: FloatArray)

    /** Every path [page] strokes or fills, forms included, in drawing order. */
    private fun pathsOn(page: PDPage): List<DrawnPath> {
        val found = ArrayList<DrawnPath>()
        val engine = object : PDFGraphicsStreamEngine(page) {
            val current = ArrayList<PointF>()
            var at = PointF()
            override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) { current += listOf(p0, p1, p2, p3) }
            override fun drawImage(pdImage: PDImage) = Unit
            override fun clip(windingRule: Path.FillType) = Unit
            override fun moveTo(x: Float, y: Float) { at = PointF(x, y); current += at }
            override fun lineTo(x: Float, y: Float) { at = PointF(x, y); current += at }
            override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { at = PointF(x3, y3); current += at }
            override fun getCurrentPoint(): PointF = at
            override fun closePath() = Unit
            override fun endPath() { current.clear() }
            override fun strokePath() = done(false)
            override fun fillPath(windingRule: Path.FillType) = done(true)
            override fun fillAndStrokePath(windingRule: Path.FillType) = done(true)
            override fun shadingFill(shadingName: COSName) = Unit
            private fun done(filled: Boolean) {
                val state = graphicsState
                val color = (if (filled) state.nonStrokingColor else state.strokingColor).components
                found += DrawnPath(current.toList(), filled, state.lineWidth, color)
                current.clear()
            }
        }
        engine.processPage(page)
        return found
    }

    private fun assertPoint(message: String, expected: Pair<Float, Float>, actual: PointF, tolerance: Float = 0.05f) {
        assertEquals("$message x", expected.first, actual.x, tolerance)
        assertEquals("$message y", expected.second, actual.y, tolerance)
    }

    /** Every character on each page, with its place on the page as shown (y down). */
    private fun glyphsByPage(file: File): List<List<TextPosition>> = PDDocument.load(file).use { doc ->
        (1..doc.numberOfPages).map { number ->
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
    }

    /** A made-up picture: a magenta square on a transparent ground, as a PNG. */
    private fun squarePng(name: String): File = File(context.cacheDir, name).also { file ->
        val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(20f, 20f, 100f, 100f, android.graphics.Paint().apply { color = Color.MAGENTA })
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The shown page's size, in points, of each page of [input]. */
    private fun sizes(input: PdfInput) = engine.shownPageSizes(input)

    /** On each shown page: a filled red box, a blue stroke, a green arrow, black text and a picture. */
    private fun everyKind(input: PdfInput, picture: File): List<PageItem> {
        val sizes = sizes(input)
        var id = 1L
        val (unitWidth, unitHeight) = PageText.unitSize("Edited here")
        return sizes.flatMapIndexed { page, (w, h) ->
            listOf(
                ShapeItem(id++, page, ShapeKind.RECTANGLE, 0.1f * w, 0.1f * h, 0.35f * w, 0.2f * h, 2f, 0xFFD32F2F.toInt(), filled = true),
                InkItem(id++, page, floatArrayOf(0.6f * w, 0.1f * h, 0.7f * w, 0.25f * h, 0.8f * w, 0.12f * h, 0.9f * w, 0.24f * h), 3f, 0xFF1565C0.toInt(), false),
                ShapeItem(id++, page, ShapeKind.ARROW, 0.1f * w, 0.75f * h, 0.5f * w, 0.85f * h, 4f, 0xFF2E7D32.toInt()),
                TextItem(id++, page, 0.55f * w, 0.7f * h, "Edited here", 16f, 0xFF000000.toInt(), unitWidth, unitHeight),
                PictureItem(id++, page, picture.path, 0.6f * w, 0.85f * h, 0.12f * w, 0.12f * w, 1, false),
            )
        }
    }

    @Test fun everyKindLandsWhereThePreviewPutsIt() {
        val source = import(mixedPages("edit_mixed.pdf"))
        val picture = squarePng("edit_square.png")
        val items = everyKind(source, picture)
        val result = engine.editPages(source, items, "edited").file
        assertEquals("edited.pdf", result.name)
        val edited = save(result, "edited_every_kind.pdf")
        val glyphs = glyphsByPage(edited)
        PDDocument.load(edited).use { doc ->
            for ((index, page) in doc.pages.withIndex()) {
                val frame = frameOf(page)
                val (box, ink, arrow, text, square) = items.filter { it.page == index }
                box as ShapeItem
                ink as InkItem
                arrow as ShapeItem
                text as TextItem
                square as PictureItem
                // Paths, from the page's content: each starts where the item does, mapped onto the page.
                // (The page itself draws none; the text after them may draw its letters as paths.)
                val paths = pathsOn(page).take(3)
                assertTrue("Page ${index + 1}: the box is filled", paths[0].filled)
                assertPoint("Page ${index + 1}, box corner", frame.fromTopLeft(box.x1, box.y1), paths[0].points.first())
                assertPoint("Page ${index + 1}, opposite corner", frame.fromTopLeft(box.x2, box.y2), paths[0].points[2])
                assertFalse(paths[1].filled)
                assertEquals(3f, paths[1].lineWidth, 0.001f)
                assertPoint("Page ${index + 1}, stroke start", frame.fromTopLeft(ink.points[0], ink.points[1]), paths[1].points.first())
                assertPoint("Page ${index + 1}, stroke end", frame.fromTopLeft(ink.points[6], ink.points[7]), paths[1].points.last())
                assertPoint("Page ${index + 1}, arrow start", frame.fromTopLeft(arrow.x1, arrow.y1), paths[2].points.first())
                assertPoint("Page ${index + 1}, arrow tip", frame.fromTopLeft(arrow.x2, arrow.y2), paths[2].points[1])
                assertArrayEquals(floatArrayOf(0x2E / 255f, 0x7D / 255f, 0x32 / 255f), paths[2].color, 0.002f)
                // The picture: its corners where the item's are, the right way up on the page as shown.
                val placed = imagesOn(page).single()
                assertPoint("Page ${index + 1}, picture's bottom-left", frame.fromTopLeft(square.x, square.y + square.height), placed.corner(0f, 0f), 0.01f)
                assertPoint("Page ${index + 1}, picture's top-right", frame.fromTopLeft(square.x + square.width, square.y), placed.corner(1f, 1f), 0.01f)
                assertNotNull("Page ${index + 1}: the picture keeps its transparency", placed.image.softMask)
                // The text: real text, starting at the box's left edge, its baseline the ascent below its top.
                val words = glyphs[index].filter { it.unicode.isNotBlank() && it.unicode.single().isLowerCase() || it.unicode == "E" }
                assertEquals("Editedhere", words.joinToString("") { it.unicode })
                val layout = PageText.layout(text.text)
                assertEquals("Page ${index + 1}, text x", text.x, words.first().x, 0.5f)
                assertEquals("Page ${index + 1}, text baseline", text.y + layout.ascent * text.size / PageText.REFERENCE, words.first().y, 0.5f)
                // The letters are as big as the size chosen, once the form's scaling is counted.
                assertEquals("Page ${index + 1}, text size", text.size, words.first().xScale, 0.5f)
            }
        }
        // Rendered as the page shows (y down): each item's colour where the item is, nowhere else.
        val scale = 2f
        for (index in 0..3) {
            val bitmap = render(edited, index, scale)
            val (box, ink, arrow, _, square) = items.filter { it.page == index }
            assertExtent("Page ${index + 1}, red box", box.bounds.let { floatArrayOf(it.x, it.y, it.x + it.width, it.y + it.height) }, extent(pixels(bitmap, ::red), scale), 1f)
            assertExtent("Page ${index + 1}, blue stroke", ink.bounds.let { floatArrayOf(it.x, it.y, it.x + it.width, it.y + it.height) }, extent(pixels(bitmap, ::blue), scale), 1.5f)
            assertExtent("Page ${index + 1}, green arrow", arrow.bounds.let { floatArrayOf(it.x, it.y, it.x + it.width, it.y + it.height) }, extent(pixels(bitmap, ::green), scale), 2f)
            // The square fills the middle two thirds of the picture.
            square as PictureItem
            val inner = floatArrayOf(square.x + square.width / 6, square.y + square.height / 6, square.x + square.width * 5 / 6, square.y + square.height * 5 / 6)
            assertExtent("Page ${index + 1}, picture", inner, extent(pixels(bitmap, ::magenta), scale), 1.5f)
        }
    }

    @Test fun pagesWithoutItemsAreLeftAsTheyWere() {
        val source = import(mixedPages("edit_untouched.pdf"))
        val (w, h) = sizes(source)[2]
        val items = listOf(
            ShapeItem(1, 2, ShapeKind.ELLIPSE, 0.2f * w, 0.2f * h, 0.5f * h, 0.4f * h, 2f, 0xFFD32F2F.toInt()),
            ShapeItem(2, 0, ShapeKind.LINE, 10f, 10f, 100f, 100f, 2f, 0xFF000000.toInt()),
        )
        fun raw(page: PDPage) = (page.cosObject.getDictionaryObject(COSName.CONTENTS) as COSStream).createRawInputStream().use { it.readBytes() }
        val before = PDDocument.load(source.file).use { doc -> doc.pages.map { raw(it) to it.resources.cosObject.keySet().toSet() } }
        val edited = save(engine.editPages(source, items, "untouched").file, "edited_untouched.pdf")
        PDDocument.load(edited).use { doc ->
            for (index in listOf(1, 3)) {
                val page = doc.getPage(index)
                assertTrue("Page ${index + 1} has one content stream still", page.cosObject.getDictionaryObject(COSName.CONTENTS) is COSStream)
                assertArrayEquals("Page ${index + 1}'s content is byte for byte the same", before[index].first, raw(page))
                assertEquals("Page ${index + 1}'s resources are the same", before[index].second, page.resources.cosObject.keySet().toSet())
                assertTrue(pathsOn(page).isEmpty())
            }
            assertEquals(1, pathsOn(doc.getPage(0)).size)
            assertEquals(1, pathsOn(doc.getPage(2)).size)
            assertEquals(listOf(0, 90, 0, 270), doc.pages.map { it.rotation })
        }
    }

    @Test fun highlighterIsSeeThroughAndKeepsTextDark() {
        val source = import(mixedPages("edit_highlight.pdf"))
        val (w, h) = sizes(source)[0]
        // Across the first page's caption, which is in the middle of the page.
        val stroke = InkItem(1, 0, floatArrayOf(0.25f * w, 0.5f * h - 6f, 0.75f * w, 0.5f * h - 6f), 24f, 0xFFFFE600.toInt(), highlighter = true)
        val edited = save(engine.editPages(source, listOf(stroke), "highlighted").file, "edited_highlight.pdf")
        PDDocument.load(edited).use { doc ->
            val page = doc.getPage(0)
            val states = page.resources.extGStateNames.map { page.resources.getExtGState(it) }
            assertTrue("A see-through, multiplying state", states.any { it.strokingAlphaConstant == EditLayout.HIGHLIGHTER_ALPHA && it.blendMode == BlendMode.MULTIPLY })
        }
        val scale = 2f
        val bitmap = render(edited, 0, scale)
        val band = stroke.bounds
        fun inBand(x: Int, y: Int) = x / scale in band.x + 14..band.x + band.width - 14 && y / scale in band.y + 2..band.y + band.height - 2
        val all = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        val inside = all.indices.filter { inBand(it % bitmap.width, it / bitmap.width) }.map { all[it] }
        // Paper under it turns pale yellow, not solid: the page shows through.
        val paper = inside.filter { Color.red(it) > 200 }
        assertTrue(paper.size > inside.size / 2)
        assertTrue("Yellow", paper.count { Color.blue(it) in 60..200 && Color.green(it) > 200 } > paper.size * 9 / 10)
        // The caption's letters stay dark under it.
        assertTrue("Text stays dark", inside.count { Color.red(it) < 60 && Color.green(it) < 60 } > 200)
    }

    @Test fun hindiTextIsShaped() {
        val source = import(mixedPages("edit_hindi.pdf"))
        val words = "हिन्दी में लिखा गया"
        val (unitWidth, unitHeight) = PageText.unitSize(words)
        val text = TextItem(1, 0, 60f, 120f, words, 28f, 0xFF000000.toInt(), unitWidth, unitHeight)
        val edited = save(engine.editPages(source, listOf(text), "hindi").file, "edited_hindi.pdf")
        // The same words drawn straight by Android, which shapes them, where the preview draws them.
        val scale = 2f
        val actual = render(edited, 0, scale)
        val expected = Bitmap.createBitmap(actual.width, actual.height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.WHITE)
            val paint = PageText.paint(Color.BLACK)
            Canvas(bitmap).apply {
                scale(scale, scale)
                translate(text.x, text.y)
                scale(text.size / PageText.REFERENCE, text.size / PageText.REFERENCE)
                PageText.draw(this, PageText.layout(words, paint), paint)
            }
        }
        fun ink(bitmap: Bitmap) = pixels(bitmap) { Color.red(it) < 100 }.filter { (_, y) -> y < actual.height / 3 }.toSet()
        val drawn = ink(actual)
        val wanted = ink(expected)
        assertTrue("Nothing drawn", wanted.size > 1000)
        val overlap = drawn.intersect(wanted).size.toFloat() / drawn.union(wanted).size
        assertTrue("The PDF's text matches Android's shaping and place only $overlap", overlap > 0.7f)
    }

    @Test fun photosStayPhotosTheRightWayUp() {
        // A photo stored sideways: red on its left, blue on its right, with EXIF saying "turn it right".
        val file = File(context.cacheDir, "edit_sideways.jpg")
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.BLUE)
            drawRect(0f, 0f, 100f, 100f, android.graphics.Paint().apply { color = Color.RED })
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val picked = checkNotNull(pictures.import(Uri.fromFile(file), 9001))
        assertTrue(picked.jpeg)
        // Upright, it is tall: red on top, blue below.
        assertEquals(100, picked.width)
        assertEquals(200, picked.height)
        val source = import(mixedPages("edit_photo.pdf"))
        val item = EditLayout.newPicture(1, 0, picked.path, picked.width.toFloat() / picked.height, picked.orientation, picked.jpeg, 595.28f, 841.89f)
        val edited = save(engine.editPages(source, listOf(item), "photo").file, "edited_photo.pdf")
        PDDocument.load(edited).use { doc ->
            val image = imagesOn(doc.getPage(0)).single().image
            assertEquals("jpg", image.suffix)
            assertNull(image.softMask)
            assertTrue(image.height > image.width)
        }
        val scale = 1f
        val rendered = render(edited, 0, scale)
        val redTop = extent(pixels(rendered, ::red), scale)[1]
        val blueTop = extent(pixels(rendered) { Color.blue(it) > 160 && Color.red(it) < 90 }, scale)[1]
        assertEquals(item.y, redTop, 2f)
        // JPEG blurs colours a little where they meet.
        assertEquals(item.y + item.height / 2, blueTop, 5f)
        pictures.deleteAllExcept(emptyList())
    }

    @Test fun textNoFontCanDrawIsRefused() {
        assertTrue(PageText.canDraw("Hello é ñ\nगोपनीय"))
        assertFalse(PageText.canDraw("͸"))
    }

    @Test fun cancellingLeavesNoOutput() {
        class Stop : RuntimeException()
        val source = import(mixedPages("edit_cancel.pdf"))
        val items = everyKind(source, squarePng("edit_cancel.png"))
        var calls = 0
        try {
            engine.editPages(source, items, "cancelled") { if (++calls == 12) throw Stop() }
            fail("Editing should have been stopped")
        } catch (_: Stop) {
        }
        assertTrue(File(context.cacheDir, "output/tools").listFiles().isNullOrEmpty())
    }

    @Test fun undoRedoAndSavedStateKeepTheItems() {
        val handle = SavedStateHandle()
        val history = EditHistory(handle)
        val box = ShapeItem(history.newId(), 1, ShapeKind.RECTANGLE, 10f, 10f, 60f, 40f, 2f, 0xFFD32F2F.toInt())
        val stroke = InkItem(history.newId(), 0, floatArrayOf(5f, 5f, 50f, 60f), 2.5f, 0xFF1565C0.toInt(), false)
        history.change(listOf(box))
        history.select(box.id)
        history.change(listOf(box, stroke))
        // A drag is many steps, but one change for Undo.
        history.begin()
        for (dx in 1..10) history.step(listOf(box.moved(dx.toFloat(), 0f), stroke))
        history.end()
        assertEquals(box.moved(10f, 0f), history.items.value.first())
        // A drag that moved nothing leaves nothing to undo.
        history.begin()
        history.end()

        assertEquals("The drag is undone, on page 2", 1, history.undo())
        assertEquals(listOf(box, stroke), history.items.value)
        assertEquals("Then the stroke, on page 1", 0, history.undo())
        assertEquals(listOf<PageItem>(box), history.items.value)
        assertTrue(history.canRedo.value)
        assertEquals(0, history.redo())
        assertEquals(listOf(box, stroke), history.items.value)
        // A new change can't be redone over.
        history.change(listOf(stroke))
        assertFalse(history.canRedo.value)
        assertNull(history.redo())
        // The chosen box has gone: nothing is chosen.
        history.undo()
        history.redo()
        assertNull(history.selected.value)

        // Saved and restored as Android does when it closes Usugami in the background: through a Parcel.
        val bundle = Bundle().apply { for (key in handle.keys()) putSerializable(key, handle.get<Any>(key) as java.io.Serializable?) }
        val parcel = Parcel.obtain()
        val restored = try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            parcel.readBundle(javaClass.classLoader)!!
        } finally {
            parcel.recycle()
        }
        val again = EditHistory(SavedStateHandle(restored.keySet().associateWith { key -> BundleCompat.getSerializable(restored, key, java.io.Serializable::class.java) }))
        assertEquals(listOf<PageItem>(stroke), again.items.value)
        // New items don't reuse an id.
        assertTrue(again.newId() > stroke.id)
    }
}
