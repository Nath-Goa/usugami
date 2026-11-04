package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The page looks on synthetic photos of a printed page: the page drawn (print in several sizes, a
 * bold heading, a black box, a faint pencil line, a red stamp), then lit as a phone photo lights it
 * (falling off across the page, a hard shadow, a warm lamp) with noise. Each pixel is compared with
 * the page as drawn.
 */
class DocumentLooksTest {

    /** The page as printed: what each look should get back to. */
    private class Page(val image: BufferedImage) {
        val width get() = image.width
        val height get() = image.height
        private val luma = IntArray(width * height).also { out ->
            for (y in 0 until height) for (x in 0 until width) {
                val c = image.getRGB(x, y)
                out[y * width + x] = DocumentLooks.luma(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF)
            }
        }

        fun luma(x: Int, y: Int) = luma[y * width + x]

        /** Ink: clearly dark on the page. */
        fun isInk(x: Int, y: Int) = luma(x, y) < 110

        /** Paper: bright, and nothing darker within [margin] pixels (the edges of print are left out). */
        fun isPaper(x: Int, y: Int, margin: Int = 2): Boolean {
            for (dy in -margin..margin) for (dx in -margin..margin) {
                val xx = (x + dx).coerceIn(0, width - 1)
                val yy = (y + dy).coerceIn(0, height - 1)
                if (luma(xx, yy) < 225) return false
            }
            return true
        }
    }

    /** A page [width] wide (A4's shape), with print and marks everywhere. */
    private fun page(width: Int = 1240): Page {
        val height = width * 297 / 210
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(246, 245, 240)
        g.fillRect(0, 0, width, height)
        val unit = width / 1240f
        g.color = Color(20, 20, 26)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, (90 * unit).toInt())
        g.drawString("QUARTERLY", 90 * unit, 190 * unit)
        val words = "The quick brown fox jumps over the lazy dog while invoice 4821 waits for Friday."
        var y = 290 * unit
        for ((i, size) in listOf(26, 30, 34, 40).withIndex()) {
            g.font = Font(if (i % 2 == 0) Font.SERIF else Font.SANS_SERIF, Font.PLAIN, (size * unit).toInt())
            repeat(5) {
                g.drawString(words, 90 * unit, y)
                y += size * unit * 1.5f
            }
        }
        // A solid box, as a photo or a filled table cell would be.
        g.fillRect((90 * unit).toInt(), (y + 20 * unit).toInt(), (240 * unit).toInt(), (160 * unit).toInt())
        // A faint pencil line.
        g.color = Color(165, 165, 160)
        g.stroke = BasicStroke(3 * unit)
        g.drawLine((420 * unit).toInt(), (y + 60 * unit).toInt(), (1100 * unit).toInt(), (y + 90 * unit).toInt())
        // A red stamp.
        g.color = Color(200, 30, 40)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, (60 * unit).toInt())
        g.drawString("PAID", 520 * unit, y + 190 * unit)
        y += 260 * unit
        g.color = Color(20, 20, 26)
        g.font = Font(Font.SERIF, Font.PLAIN, (30 * unit).toInt())
        while (y < height - 80 * unit) {
            g.drawString(words, 90 * unit, y)
            y += 45 * unit
        }
        g.dispose()
        return Page(image)
    }

    /**
     * [page] as a phone photographs it: the light falls off from the top-left to under half at the
     * bottom-right, a hard shadow (a hand, a phone) covers part of the left, a warm lamp tints it,
     * and there is noise. [shadow] and [lamp] can be left out.
     */
    private fun photo(page: Page, shadow: Boolean = true, lamp: Boolean = true, seed: Int = 1): BufferedImage {
        val w = page.width
        val h = page.height
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val random = Random(seed)
        val tint = if (lamp) floatArrayOf(1f, 0.92f, 0.76f) else floatArrayOf(1f, 1f, 1f)
        for (y in 0 until h) for (x in 0 until w) {
            var light = 1f - 0.55f * (x.toFloat() / w + y.toFloat() / h) / 2f
            if (shadow) {
                // A hard shadow, its edge a few millimetres wide, over the left of the page's middle.
                val soft = w * 0.004f
                val inside = (1 / (1 + exp((x - w * 0.35f) / soft))) * (1 / (1 + exp((h * 0.3f - y) / soft))) *
                    (1 / (1 + exp((y - h * 0.7f) / soft)))
                light *= 1 - 0.45f * inside
            }
            val c = page.image.getRGB(x, y)
            val noise = random.nextInt(-5, 6)
            fun channel(v: Int, i: Int) = (v * light * tint[i] + noise).toInt().coerceIn(0, 255)
            out.setRGB(x, y, (channel(c shr 16 and 0xFF, 0) shl 16) or (channel(c shr 8 and 0xFF, 1) shl 8) or channel(c and 0xFF, 2))
        }
        return out
    }

    private fun source(image: BufferedImage) = TestImages.BufferedImageSource(image)

    /** Saves [image] for a look by eye, when the environment variable USUGAMI_LOOKS names a folder. */
    private fun save(name: String, image: BufferedImage) {
        val dir = System.getenv("USUGAMI_LOOKS")?.takeIf { it.isNotEmpty() } ?: return
        ImageIO.write(image, "png", File(dir, "$name.png").apply { parentFile.mkdirs() })
    }

    private fun BitImage.toBuffered() = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { out ->
        for (y in 0 until height) for (x in 0 until width) out.setRGB(x, y, if (isBlack(x, y)) 0 else 0xFFFFFF)
    }

    private fun GrayImage.toBuffered() = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { out ->
        for (y in 0 until height) for (x in 0 until width) out.setRGB(x, y, this[x, y] * 0x010101)
    }

    /** How much of the paper came out white and of the ink black. */
    private fun score(page: Page, black: (Int, Int) -> Boolean): Pair<Double, Double> {
        var paper = 0
        var white = 0
        var ink = 0
        var dark = 0
        for (y in 0 until page.height) for (x in 0 until page.width) {
            if (page.isInk(x, y)) {
                ink++
                if (black(x, y)) dark++
            } else if (page.isPaper(x, y)) {
                paper++
                if (!black(x, y)) white++
            }
        }
        return white.toDouble() / paper to dark.toDouble() / ink
    }

    @Test fun blackAndWhiteKeepsThePrintAndWhitensThePaperInAShadow() {
        val page = page()
        val photo = photo(page)
        save("photo", photo)
        val bits = DocumentLooks.blackAndWhite(source(photo))
        save("bw", bits.toBuffered())
        assertEquals(page.width, bits.width)
        assertEquals(page.height, bits.height)
        val (paper, ink) = score(page) { x, y -> bits.isBlack(x, y) }
        assertTrue("Paper white: $paper", paper > 0.998)
        assertTrue("Ink black: $ink", ink > 0.97)

        // The pencil line, fainter than any print, is kept where it is: black along most of it.
        // One global threshold, however well chosen, can't do this page: the shadow turns black, or
        // the print in it white.
        val luma = IntArray(photo.width * photo.height)
        for (y in 0 until photo.height) for (x in 0 until photo.width) {
            val c = photo.getRGB(x, y)
            luma[y * photo.width + x] = DocumentLooks.luma(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF)
        }
        val global = PageDetection.otsu(luma)!!
        val (globalPaper, globalInk) = score(page) { x, y -> luma[y * photo.width + x] <= global }
        assertTrue("One threshold: paper $globalPaper, ink $globalInk", globalPaper < 0.95 || globalInk < 0.9)
    }

    @Test fun blackAndWhiteFillsBoldPrintAndKeepsFaintLines() {
        val page = page()
        val bits = DocumentLooks.blackAndWhite(source(photo(page, shadow = false)))
        // Every pixel well inside the box and the bold heading's strokes is black.
        val unit = page.width / 1240f
        var boxMissed = 0
        var box = 0
        for (y in 0 until page.height) for (x in 0 until page.width) {
            if (page.luma(x, y) < 40 && page.isInk(max(0, x - 3), y) && page.isInk(min(page.width - 1, x + 3), y)) {
                box++
                if (!bits.isBlack(x, y)) boxMissed++
            }
        }
        assertTrue("Inside bold print and the box: $boxMissed of $box white", boxMissed < box / 500)
        // The pencil line (from (420, ·) to (1100, ·) in page units) is black along most of its length.
        var found = 0
        val xs = (450..1070 step 10).map { (it * unit).toInt() }
        for (x in xs) {
            val column = (0 until page.height).filter { y -> page.luma(x, y) in 150..200 }
            if (column.any { y -> bits.isBlack(x, y) }) found++
        }
        assertTrue("Pencil line found at $found of ${xs.size} places", found > xs.size * 0.9)
    }

    @Test fun grayscaleAndEnhancedMakeThePaperWhiteAndTheInkDark() {
        val page = page()
        val photo = photo(page)
        val gray = DocumentLooks.grayscale(source(photo))
        save("gray", gray.toBuffered())
        val enhanced = BufferedImage(photo.width, photo.height, BufferedImage.TYPE_INT_RGB)
        DocumentLooks.enhance(source(photo), { y, row -> enhanced.setRGB(0, y, row.size, 1, row, 0, row.size) })
        save("enhanced", enhanced)

        var paper = 0
        var whiteGray = 0
        var whiteColour = 0
        // The paper's colour in each tenth of the page across and down: red, green, blue and count.
        val regions = Array(100) { LongArray(4) }
        var ink = 0
        var darkGray = 0
        for (y in 0 until page.height) for (x in 0 until page.width) {
            val c = enhanced.getRGB(x, y)
            val r = c shr 16 and 0xFF
            val g = c shr 8 and 0xFF
            val b = c and 0xFF
            if (page.isInk(x, y) && page.luma(x, y) < 60) {
                ink++
                if (gray[x, y] < 90) darkGray++
            } else if (page.isPaper(x, y)) {
                paper++
                if (gray[x, y] >= 245) whiteGray++
                if (min(r, min(g, b)) >= 240) whiteColour++
                regions[y * 10 / page.height * 10 + x * 10 / page.width].let {
                    it[0] += r.toLong()
                    it[1] += g.toLong()
                    it[2] += b.toLong()
                    it[3]++
                }
            }
        }
        assertTrue("Grey paper white: ${whiteGray.toDouble() / paper}", whiteGray > paper * 0.99)
        assertTrue("Colour paper white: ${whiteColour.toDouble() / paper}", whiteColour > paper * 0.99)
        // The lamp's warm tint is gone everywhere, in the shadow too: the paper is white, not yellow.
        for (region in regions.filter { it[3] > 100 }) {
            val (r, g, b) = List(3) { region[it].toDouble() / region[3] }
            assertTrue("Paper colour ${listOf(r, g, b)}", max(r, max(g, b)) - min(r, min(g, b)) < 4)
        }
        assertTrue("Ink dark: ${darkGray.toDouble() / ink}", darkGray > ink * 0.97)

        // The red stamp stays red.
        var stamp = 0
        var red = 0
        for (y in 0 until page.height) for (x in 0 until page.width) {
            val c = page.image.getRGB(x, y)
            if ((c shr 16 and 0xFF) > 180 && (c shr 8 and 0xFF) < 60) {
                stamp++
                val e = enhanced.getRGB(x, y)
                if ((e shr 16 and 0xFF) > 150 && (e shr 8 and 0xFF) < 70 && (e and 0xFF) < 80) red++
            }
        }
        assertTrue("Stamp red: $red of $stamp", red > stamp * 0.9)
    }

    @Test fun anEvenlyLitPageKeepsItsPaperAndPrint() {
        // No shadow, no lamp: nothing to undo, and the looks still agree with the page.
        val page = page(620)
        val photo = photo(page, shadow = false, lamp = false)
        val bits = DocumentLooks.blackAndWhite(source(photo))
        val (paper, ink) = score(page) { x, y -> bits.isBlack(x, y) }
        assertTrue("Paper $paper, ink $ink", paper > 0.998 && ink > 0.95)
        val gray = DocumentLooks.grayscale(source(photo))
        for (y in 0 until page.height step 7) for (x in 0 until page.width step 7) {
            if (page.isPaper(x, y)) assertTrue("Paper at $x, $y: ${gray[x, y]}", gray[x, y] >= 235)
        }
    }

    @Test fun aBlankPageAndTinyPicturesWork() {
        val blank = BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB).apply {
            val g = createGraphics()
            g.color = Color(180, 170, 150)
            g.fillRect(0, 0, 300, 200)
            g.dispose()
        }
        val bits = DocumentLooks.blackAndWhite(source(blank))
        for (y in 0 until 200) for (x in 0 until 300) assertTrue(!bits.isBlack(x, y))
        assertTrue(DocumentLooks.grayscale(source(blank)).pixels.all { it == 255.toByte() })

        // A single pixel (which can't tell print from paper), lines, and widths that aren't whole bytes.
        assertEquals(1, DocumentLooks.blackAndWhite(source(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB))).data.size)
        for ((w, h) in listOf(1 to 9, 9 to 1, 13 to 7, 17 to 33)) {
            val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until h) for (x in 0 until w) image.setRGB(x, y, if ((x + y) % 5 == 0) 0x101010 else 0xF0F0F0)
            val b = DocumentLooks.blackAndWhite(source(image))
            assertEquals((w + 7) / 8 * h, b.data.size)
            for (y in 0 until h) for (x in 0 until w) assertEquals("$w x $h at $x, $y", (x + y) % 5 == 0, b.isBlack(x, y))
            // The bits past a row's end are white.
            if (w % 8 != 0) for (y in 0 until h) assertEquals(0, (b.data[y * b.rowBytes + b.rowBytes - 1].toInt().inv() and ((1 shl (8 - w % 8)) - 1)))
            assertEquals(w * h, DocumentLooks.grayscale(source(image)).pixels.size)
            var rows = 0
            DocumentLooks.enhance(source(image), { _, row -> assertEquals(w, row.size); rows++ })
            assertEquals(h, rows)
        }
    }

    @Test fun transparencyIsOnWhite() {
        // A PNG with a transparent background and black print: the background is paper.
        val image = BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = Color.BLACK
        g.fillRect(40, 40, 120, 20)
        g.dispose()
        val bits = DocumentLooks.blackAndWhite(source(image))
        assertTrue(bits.isBlack(100, 50))
        assertTrue(!bits.isBlack(10, 10))
        assertEquals(255, DocumentLooks.grayscale(source(image))[10, 10])
    }

    @Test fun thePaperIsFoundUnderPrintAndBoxes() {
        // Paper at 200, with a box of ink at 30 two blocks wide: the closing fills it, so the box is
        // read as ink on paper at 200, not as paper of its own.
        val size = 800
        val block = DocumentLooks.blockSize(size, size)
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until size) for (x in 0 until size) {
            val inBox = x in 5 * block until 7 * block && y in 5 * block until 7 * block
            image.setRGB(x, y, if (inBox) 0x1E1E1E else 0xC8C8C8)
        }
        val estimator = PaperEstimator(size, size)
        val row = IntArray(size)
        for (y in 0 until size) {
            for (x in 0 until size) row[x] = image.getRGB(x, y) and 0xFF
            estimator.addRow(row)
        }
        val paper = estimator.finish()
        assertEquals(200f, paper.block(5, 5), 0.5f)
        assertEquals(200f, paper.block(6, 6), 0.5f)
        val levels = FloatArray(size)
        paper.row(6 * block, levels, FloatArray(size))
        assertTrue(levels.all { abs(it - 200f) < 1f })
    }

    @Test fun aTwelveMegapixelPhotoIsQuick() {
        // 4000 x 3000: timed, and checked to finish well within a few seconds on a computer.
        val image = BufferedImage(4000, 3000, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(210, 200, 180)
        g.fillRect(0, 0, 4000, 3000)
        g.color = Color.DARK_GRAY
        g.font = Font(Font.SERIF, Font.PLAIN, 60)
        for (line in 0 until 40) g.drawString("A line of print on a large photo, number $line", 100, 100 + line * 70)
        g.dispose()
        val source = source(image)
        repeat(2) { DocumentLooks.blackAndWhite(source) } // warm-up
        val times = PageLook.entries.drop(1).associateWith { look ->
            val start = System.nanoTime()
            when (look) {
                PageLook.ENHANCED -> DocumentLooks.enhance(source, { _, _ -> })
                PageLook.GRAYSCALE -> DocumentLooks.grayscale(source)
                else -> DocumentLooks.blackAndWhite(source)
            }
            (System.nanoTime() - start) / 1_000_000
        }
        println("12 MP looks, ms: $times")
        assertTrue("$times", times.values.all { it < 5000 })
    }
}
