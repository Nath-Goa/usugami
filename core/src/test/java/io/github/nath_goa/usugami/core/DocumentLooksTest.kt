package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The page looks on synthetic photos of a printed page: the page drawn (print in several sizes, a
 * bold heading, a black box, a faint pencil line, a red stamp), then lit as a phone photo lights it
 * (falling off across the page, a hard shadow, a warm lamp) with noise. Each pixel is compared with
 * the page as drawn. Nothing depends on how a font is drawn, which differs between systems.
 */
class DocumentLooksTest {

    /** The page as printed: what each look should get back to; [box] is where the black box is. */
    private class Page(val image: BufferedImage, val box: Rectangle) {
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
        val box = Rectangle((90 * unit).toInt(), (y + 20 * unit).toInt(), (240 * unit).toInt(), (160 * unit).toInt())
        g.fill(box)
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
        return Page(image, box)
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

    /** [photo] with a colour [look], beside it. */
    private fun colour(photo: BufferedImage, look: PageLook, adjustments: LookAdjustments = LookAdjustments.NONE) =
        BufferedImage(photo.width, photo.height, BufferedImage.TYPE_INT_RGB).also { out ->
            DocumentLooks.colour(source(photo), look, adjustments, { y, row -> out.setRGB(0, y, row.size, 1, row, 0, row.size) })
        }

    /** [photo] with any [look], as a picture. */
    private fun made(photo: BufferedImage, look: PageLook, adjustments: LookAdjustments = LookAdjustments.NONE): BufferedImage = when {
        look.isColour -> colour(photo, look, adjustments)
        look == PageLook.GRAYSCALE -> DocumentLooks.grayscale(source(photo), adjustments).toBuffered()
        else -> DocumentLooks.blackAndWhite(source(photo), look, adjustments).toBuffered()
    }

    private fun luma(c: Int) = DocumentLooks.luma(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF)

    /** How widely [image]'s brightness spreads: its standard deviation. */
    private fun spread(image: BufferedImage): Double {
        val mean = meanLuma(image)
        var sum = 0.0
        for (y in 0 until image.height) for (x in 0 until image.width) sum += (luma(image.getRGB(x, y)) - mean).let { it * it }
        return sqrt(sum / (image.width * image.height))
    }

    /** The mean brightness of [image] where [where] holds. */
    private fun meanLuma(image: BufferedImage, where: (Int, Int) -> Boolean = { _, _ -> true }): Double {
        var sum = 0L
        var count = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (where(x, y)) {
                sum += luma(image.getRGB(x, y))
                count++
            }
        }
        return sum.toDouble() / count
    }

    private fun BitImage.blackCount() = (0 until height).sumOf { y -> (0 until width).count { x -> isBlack(x, y) } }

    /** Where the red stamp is printed. */
    private fun Page.isStamp(x: Int, y: Int) = image.getRGB(x, y).let { (it shr 16 and 0xFF) > 180 && (it shr 8 and 0xFF) < 60 }

    /** The hue (in degrees) and saturation (0 to 1) of [image]'s mean colour where [page]'s stamp is. */
    private fun stampColour(page: Page, image: BufferedImage): Pair<Double, Double> {
        val sum = LongArray(3)
        var count = 0
        for (y in 0 until page.height) for (x in 0 until page.width) {
            if (page.isStamp(x, y)) {
                val c = image.getRGB(x, y)
                sum[0] += (c shr 16 and 0xFF).toLong()
                sum[1] += (c shr 8 and 0xFF).toLong()
                sum[2] += (c and 0xFF).toLong()
                count++
            }
        }
        val hsb = Color.RGBtoHSB((sum[0] / count).toInt(), (sum[1] / count).toInt(), (sum[2] / count).toInt(), null)
        return hsb[0] * 360.0 to hsb[1].toDouble()
    }

    /** How far apart two hues are, in degrees. */
    private fun hueDistance(a: Double, b: Double) = abs(a - b).let { min(it, 360 - it) }

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

    @Test fun lightenBrightensThePhotoButKeepsItsLook() {
        val page = page()
        val photo = photo(page)
        val lighten = colour(photo, PageLook.LIGHTEN)
        save("lighten", lighten)
        assertTrue("Brighter: ${meanLuma(lighten)}, the photo ${meanLuma(photo)}", meanLuma(lighten) > meanLuma(photo) + 30)
        // The paper lighter, but not made white as Enhanced makes it.
        val paper = { x: Int, y: Int -> page.isPaper(x, y) }
        val enhancedPaper = meanLuma(colour(photo, PageLook.ENHANCED), paper)
        assertTrue("Paper ${meanLuma(lighten, paper)}, Enhanced's $enhancedPaper", meanLuma(lighten, paper) < enhancedPaper - 8)
        // The shadow over the left of the page's middle softened, not taken out: its paper against the
        // paper just right of it.
        val middle = (page.height * 0.35).toInt()..(page.height * 0.65).toInt()
        fun shade(image: BufferedImage) = meanLuma(image) { x, y -> paper(x, y) && x < page.width * 0.3 && y in middle } /
            meanLuma(image) { x, y -> paper(x, y) && x > page.width * 0.4 && x < page.width * 0.6 && y in middle }
        assertTrue("Shadow ${shade(lighten)}, in the photo ${shade(photo)}", shade(lighten) > shade(photo) + 0.1 && shade(lighten) < 0.95)
        // The ink not deepened, and the stamp still the photo's red.
        val ink = { x: Int, y: Int -> page.luma(x, y) < 60 }
        assertTrue("Ink ${meanLuma(lighten, ink)}, in the photo ${meanLuma(photo, ink)}", meanLuma(lighten, ink) >= meanLuma(photo, ink))
        val (hue, saturation) = stampColour(page, lighten)
        assertTrue("Stamp hue $hue, saturation $saturation", hueDistance(hue, stampColour(page, page.image).first) < 10 && saturation > 0.6)
    }

    @Test fun superEnhancedGoesFurtherThanEnhanced() {
        val page = page()
        val photo = photo(page)
        val enhanced = colour(photo, PageLook.ENHANCED)
        val superEnhanced = colour(photo, PageLook.SUPER_ENHANCED)
        save("super-enhanced", superEnhanced)
        fun whitePaper(image: BufferedImage): Int {
            var white = 0
            for (y in 0 until page.height) for (x in 0 until page.width) {
                val c = image.getRGB(x, y)
                if (page.isPaper(x, y) && min(c shr 16 and 0xFF, min(c shr 8 and 0xFF, c and 0xFF)) >= 250) white++
            }
            return white
        }
        assertTrue("White paper ${whitePaper(superEnhanced)}, Enhanced's ${whitePaper(enhanced)}", whitePaper(superEnhanced) >= whitePaper(enhanced))
        val ink = { x: Int, y: Int -> page.luma(x, y) < 60 }
        assertTrue("Ink ${meanLuma(superEnhanced, ink)}, Enhanced's ${meanLuma(enhanced, ink)}", meanLuma(superEnhanced, ink) <= meanLuma(enhanced, ink))
        val (hue, saturation) = stampColour(page, superEnhanced)
        val (enhancedHue, enhancedSaturation) = stampColour(page, enhanced)
        assertTrue("Stamp saturation $saturation, Enhanced's $enhancedSaturation", saturation > enhancedSaturation + 0.02)
        assertTrue("Stamp hue $hue, Enhanced's $enhancedHue", hueDistance(hue, enhancedHue) < 10)

        // Sharpened: a grey square's edge comes out darker than its middle, which no curve could make,
        // and the step onto it from the paper steeper.
        val square = BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 400) for (x in 0 until 400) square.setRGB(x, y, if (x in 188 until 212 && y in 188 until 212) 0x6E6E6E else 0xEBEBEB)
        val plain = colour(square, PageLook.ENHANCED)
        val sharp = colour(square, PageLook.SUPER_ENHANCED)
        fun at(image: BufferedImage, x: Int) = luma(image.getRGB(x, 200))
        assertEquals("Enhanced", at(plain, 200), at(plain, 188))
        assertTrue("Edge ${at(sharp, 188)}, middle ${at(sharp, 200)}", at(sharp, 188) < at(sharp, 200) - 5)
        assertTrue("Step ${at(sharp, 187) - at(sharp, 188)}, Enhanced's ${at(plain, 187) - at(plain, 188)}", at(sharp, 187) - at(sharp, 188) > at(plain, 187) - at(plain, 188))

        // A faint stain on yellowed paper (tea, foxing) gets no more colour than Enhanced gives it,
        // rather than turning into an orange blotch.
        for (stain in listOf(Color(205, 178, 135), Color(215, 192, 155))) {
            val stained = BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB)
            val g = stained.createGraphics()
            g.color = Color(232, 228, 218)
            g.fillRect(0, 0, 400, 400)
            g.color = stain
            g.fillRect(170, 170, 60, 60)
            g.dispose()
            fun chroma(image: BufferedImage) = image.getRGB(200, 200).let { max(it shr 16 and 0xFF, max(it shr 8 and 0xFF, it and 0xFF)) - min(it shr 16 and 0xFF, min(it shr 8 and 0xFF, it and 0xFF)) }
            val superChroma = chroma(colour(stained, PageLook.SUPER_ENHANCED))
            val enhancedChroma = chroma(colour(stained, PageLook.ENHANCED))
            assertTrue("$stain: Super's colour $superChroma, Enhanced's $enhancedChroma", superChroma <= enhancedChroma)
        }
    }

    @Test fun inkSaverLeavesLargeDarkAreasHollow() {
        val page = page()
        val photo = photo(page)
        val plain = DocumentLooks.blackAndWhite(source(photo))
        val saver = DocumentLooks.blackAndWhite(source(photo), PageLook.INK_SAVER)
        save("ink-saver", saver.toBuffered())
        // Everything but the box is as black and white has it: the print, the bold heading, the pencil.
        val box = page.box
        val around = Rectangle(box.x - 2, box.y - 2, box.width + 4, box.height + 4)
        var differ = 0
        for (y in 0 until page.height) for (x in 0 until page.width) {
            if (!around.contains(x, y) && plain.isBlack(x, y) != saver.isBlack(x, y)) differ++
        }
        assertEquals("Pixels that differ outside the box", 0, differ)
        // The box keeps an outline a few pixels wide, and is white inside.
        var outline = 0
        var inside = 0
        for (y in box.y until box.y + box.height) for (x in box.x until box.x + box.width) {
            val depth = min(min(x - box.x, box.x + box.width - 1 - x), min(y - box.y, box.y + box.height - 1 - y))
            if (depth in 1..2 && !saver.isBlack(x, y)) outline++
            if (depth >= 8 && saver.isBlack(x, y)) inside++
        }
        assertEquals("White in the outline", 0, outline)
        assertEquals("Black inside", 0, inside)
        val hollowed = (box.width - 16) * (box.height - 16)
        assertTrue("Black ${saver.blackCount()}, without ink saving ${plain.blackCount()}", saver.blackCount() < plain.blackCount() - hollowed)
    }

    @Test fun inkSaverKeepsTheOutlineOfASlantedStroke() {
        // A marker stroke 30 pixels wide (2.5% of the picture, as in a close photo of a note) at 45
        // degrees: wide enough to be left hollow, and its outline must hold along the whole slant.
        val image = BufferedImage(1200, 1200, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(240, 236, 220)
        g.fillRect(0, 0, 1200, 1200)
        g.color = Color(25, 25, 30)
        g.stroke = BasicStroke(30f)
        g.drawLine(200, 200, 1000, 1000)
        g.dispose()
        val saver = DocumentLooks.blackAndWhite(source(image), PageLook.INK_SAVER)
        var hollow = 0
        val along = 300..900 step 20
        for (t in along) {
            // Across the stroke: (t + d, t - d) is d times the square root of 2 from its middle.
            fun black(d: IntRange) = d.any { saver.isBlack(t + it, t - it) } && d.any { saver.isBlack(t - it, t + it) }
            assertTrue("Outline at $t", black(5..11))
            if (!saver.isBlack(t, t)) hollow++
        }
        assertEquals("Hollow along the stroke", along.count(), hollow)
    }

    @Test fun inkSaverLeavesNoRingsRoundSpecksInADarkArea() {
        // A black box with white specks a few pixels inside its edges, as noise in a dark photo
        // leaves: the specks are made white with the box, not each kept inside a black ring.
        val image = BufferedImage(1200, 1200, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(240, 238, 230)
        g.fillRect(0, 0, 1200, 1200)
        g.color = Color(20, 20, 24)
        g.fillRect(300, 300, 400, 300)
        g.color = Color(240, 238, 230)
        for (k in 0 until 10) {
            g.fillRect(340 + k * 35, 306, 2, 2)
            g.fillRect(340 + k * 35, 592, 2, 2)
            g.fillRect(306, 330 + k * 25, 2, 2)
        }
        g.dispose()
        val saver = DocumentLooks.blackAndWhite(source(image), PageLook.INK_SAVER)
        var black = 0
        for (y in 304 until 596) for (x in 304 until 696) if (saver.isBlack(x, y)) black++
        assertEquals("Black inside the outline", 0, black)
        assertTrue("Outline", (300 until 700).all { saver.isBlack(it, 300) && saver.isBlack(it, 599) })
    }

    @Test fun theToneCurvesNeverTurnBackAndKeepWhite() {
        assertTrue(DocumentLooks.tones(LookAdjustments(0, 0)).withIndex().all { (i, v) -> i == v })
        for (brightness in -100..100 step 20) for (contrast in -100..100 step 20) {
            val tones = DocumentLooks.tones(LookAdjustments(brightness, contrast))
            assertTrue("$brightness, $contrast", (1..255).all { tones[it] >= tones[it - 1] })
            assertEquals("$brightness, $contrast", 255, tones[255])
            if (contrast >= 0) assertEquals("$brightness, $contrast", 0, tones[0])
        }
    }

    @Test fun brightnessAndContrastChangeEveryLook() {
        val page = page(620)
        val photo = photo(page)
        // With neither, every look is exactly as it is made without them.
        for (look in PageLook.entries) {
            assertTrue("$look", TestImages.pixelsOf(made(photo, look, LookAdjustments(0, 0))).contentEquals(TestImages.pixelsOf(made(photo, look))))
        }
        // Brightness lightens or darkens the print and what is round it; contrast spreads the levels
        // further apart or closer together.
        val print = { x: Int, y: Int -> page.luma(x, y) < 200 }
        for (look in PageLook.entries.filter { !it.isBilevel }) {
            val darker = meanLuma(made(photo, look, LookAdjustments(brightness = -60)), print)
            val plain = meanLuma(made(photo, look), print)
            val lighter = meanLuma(made(photo, look, LookAdjustments(brightness = 60)), print)
            assertTrue("$look: $darker, $plain, $lighter", darker < plain - 5 && plain + 5 < lighter)
            val flatter = spread(made(photo, look, LookAdjustments(contrast = -60)))
            val spread = spread(made(photo, look))
            val punchier = spread(made(photo, look, LookAdjustments(contrast = 60)))
            assertTrue("$look: $flatter, $spread, $punchier", flatter < spread - 2 && spread < punchier)
        }
        // Black and white: lighter leaves fewer pixels black, darker more; more contrast makes the
        // print bolder.
        for (look in PageLook.entries.filter { it.isBilevel }) {
            val counts = listOf(-60, 0, 60).map { DocumentLooks.blackAndWhite(source(photo), look, LookAdjustments(brightness = it)).blackCount() }
            assertTrue("$look: $counts", counts[0] > counts[1] && counts[1] > counts[2])
            val less = DocumentLooks.blackAndWhite(source(photo), look, LookAdjustments(contrast = -60)).blackCount()
            val more = DocumentLooks.blackAndWhite(source(photo), look, LookAdjustments(contrast = 60)).blackCount()
            assertTrue("$look: $less, ${counts[1]}, $more", less < counts[1] && counts[1] < more)
        }
    }

    @Test fun originalIsThePhotoOnWhite() {
        val image = TestImages.pattern(37, 23, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 23) for (x in 0 until 37) image.setRGB(x, y, (image.getRGB(x, y) and 0xFFFFFF) or ((x * 7) shl 24))
        val made = colour(image, PageLook.ORIGINAL)
        for (y in 0 until 23) for (x in 0 until 37) {
            val c = image.getRGB(x, y)
            val alpha = c ushr 24
            fun onWhite(v: Int) = (v * alpha + 255 * (255 - alpha) + 127) / 255
            val expected = (onWhite(c shr 16 and 0xFF) shl 16) or (onWhite(c shr 8 and 0xFF) shl 8) or onWhite(c and 0xFF)
            assertEquals("$x, $y", expected, made.getRGB(x, y) and 0xFFFFFF)
        }
    }

    @Test fun eachColourLookCanBeWrittenBackIntoThePhoto() {
        // As a PDF's page is made, without a second picture as large: the same as made beside it.
        for (look in PageLook.entries.filter { it.isColour }) for (adjustments in listOf(LookAdjustments.NONE, LookAdjustments(30, 40))) {
            val photo = photo(page(620))
            val beside = colour(photo, look, adjustments)
            DocumentLooks.colour(source(photo), look, adjustments, { y, row -> photo.setRGB(0, y, row.size, 1, row, 0, row.size) })
            val differ = (0 until photo.height).sumOf { y -> (0 until photo.width).count { x -> beside.getRGB(x, y) != photo.getRGB(x, y) } }
            assertEquals("$look, $adjustments: pixels that differ", 0, differ)
        }
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
        for (look in PageLook.entries) {
            val made = TestImages.pixelsOf(made(blank, look))
            assertTrue("$look", made.all { it == made[0] })
            if (look != PageLook.ORIGINAL && look != PageLook.LIGHTEN) assertEquals("$look", 0xFFFFFFFF.toInt(), made[0])
        }

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
            assertTrue(b.data.contentEquals(DocumentLooks.blackAndWhite(source(image), PageLook.INK_SAVER).data))
            assertEquals(w * h, DocumentLooks.grayscale(source(image), LookAdjustments(20, 20)).pixels.size)
            for (look in PageLook.entries.filter { it.isColour }) {
                var rows = 0
                DocumentLooks.colour(source(image), look, LookAdjustments(20, 20), { y, row -> assertEquals(rows, y); assertEquals(w, row.size); rows++ })
                assertEquals("$look", h, rows)
            }
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
        // Ink saver keeps the bar's edge; the colour looks keep the white round it white, Lighten too.
        val saver = DocumentLooks.blackAndWhite(source(image), PageLook.INK_SAVER)
        assertTrue(saver.isBlack(100, 40) && !saver.isBlack(10, 10))
        for (look in PageLook.entries.filter { it.isColour }) {
            val made = colour(image, look)
            assertTrue("$look", made.getRGB(10, 10) and 0xFFFFFF == 0xFFFFFF && luma(made.getRGB(100, 50)) < 40)
        }
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
        // A dark box, for ink saver to leave hollow.
        g.fillRect(2600, 400, 1000, 800)
        g.dispose()
        val source = source(image)
        repeat(2) { DocumentLooks.blackAndWhite(source) } // warm-up
        // Each look with brightness and contrast, which cost a little more.
        val adjustments = LookAdjustments(10, 10)
        val times = PageLook.entries.associateWith { look ->
            val start = System.nanoTime()
            when {
                look.isColour -> DocumentLooks.colour(source, look, adjustments, { _, _ -> })
                look == PageLook.GRAYSCALE -> DocumentLooks.grayscale(source, adjustments)
                else -> DocumentLooks.blackAndWhite(source, look, adjustments)
            }
            (System.nanoTime() - start) / 1_000_000
        }
        println("12 MP looks, ms: $times")
        assertTrue("$times", times.values.all { it < 5000 })
    }
}
