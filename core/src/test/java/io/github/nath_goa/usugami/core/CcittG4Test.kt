package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * CCITT Group 4 compression, checked by decoding it with Java's own TIFF reader (which shares no
 * code with it), and the 1-bit Flate stream beside it.
 */
class CcittG4Test {

    private fun bits(width: Int, height: Int, black: (Int, Int) -> Boolean): BitImage {
        val image = BitImage(width, height)
        image.data.fill(0xFF.toByte())
        for (y in 0 until height) for (x in 0 until width) {
            if (black(x, y)) {
                val i = y * image.rowBytes + (x ushr 3)
                image.data[i] = (image.data[i].toInt() and (0x80 ushr (x and 7)).inv()).toByte()
            }
        }
        return image
    }

    /** [data] as a one-strip TIFF file, compressed with T.6 (WhiteIsZero). */
    private fun tiff(width: Int, height: Int, data: ByteArray): ByteArray {
        val entries = listOf(
            intArrayOf(256, 4, width), intArrayOf(257, 4, height), intArrayOf(258, 3, 1), intArrayOf(259, 3, 4),
            intArrayOf(262, 3, 0), intArrayOf(273, 4, 0), intArrayOf(277, 3, 1), intArrayOf(278, 4, height),
            intArrayOf(279, 4, data.size), intArrayOf(293, 4, 0),
        )
        val dataOffset = 8 + 2 + entries.size * 12 + 4
        val out = ByteArrayOutputStream()
        fun short(v: Int) { out.write(v and 0xFF); out.write(v ushr 8 and 0xFF) }
        fun int(v: Int) { short(v and 0xFFFF); short(v ushr 16) }
        out.write('I'.code); out.write('I'.code); short(42); int(8)
        short(entries.size)
        for ((tag, type, value) in entries) {
            short(tag); short(type); int(1)
            int(if (tag == 273) dataOffset else value)
        }
        int(0)
        out.write(data)
        return out.toByteArray()
    }

    /** Encodes [image], decodes it with Java's TIFF reader and checks every pixel. */
    private fun assertRoundTrip(image: BitImage): ByteArray {
        val encoded = CcittG4.encode(image)
        val decoded = ImageIO.read(ByteArrayInputStream(tiff(image.width, image.height, encoded)))
        assertEquals(image.width, decoded.width)
        assertEquals(image.height, decoded.height)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val black = decoded.getRGB(x, y) and 0xFFFFFF == 0
            if (black != image.isBlack(x, y)) throw AssertionError("${image.width} x ${image.height}: pixel $x, $y")
        }
        return encoded
    }

    /** The size of a TIFF file Java writes [image] in with CCITT T.6 compression (its strip and a little more). */
    private fun javaG4(image: BitImage): Int {
        val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_BINARY)
        for (y in 0 until image.height) for (x in 0 until image.width) buffered.setRGB(x, y, if (image.isBlack(x, y)) 0 else 0xFFFFFF)
        val writer = ImageIO.getImageWritersByFormatName("tiff").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
            compressionType = "CCITT T.6"
        }
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, javax.imageio.IIOImage(buffered, null, null), param)
        }
        return out.size()
    }

    @Test fun everyRunLengthOfEitherColourDecodes() {
        // Odd rows: a white run of every length, then a black run, under a blank row, which the
        // horizontal mode codes as two runs: every terminating and make-up code, and runs past 2560.
        val width = 3100
        val image = bits(width, 2 * (width - 4)) { x, y ->
            if (y % 2 == 0) false else {
                val white = y / 2
                val black = (white * 37 + 1) % (width - white) + 1
                x >= white && x < white + black
            }
        }
        assertRoundTrip(image)
        // Black runs of every length from the left edge.
        assertRoundTrip(bits(width, 2 * width) { x, y -> y % 2 == 1 && x < y / 2 })
    }

    @Test fun pagesAndPatternsDecode() {
        // Print, rendered and thresholded.
        val text = BufferedImage(1240, 400, BufferedImage.TYPE_INT_RGB).apply {
            val g = createGraphics()
            g.color = Color.WHITE
            g.fillRect(0, 0, width, height)
            g.color = Color.BLACK
            g.font = Font(Font.SERIF, Font.PLAIN, 28)
            for (line in 0 until 9) g.drawString("Usugami turns photos of pages into PDFs, line ${line + 1}: 0123456789.", 40, 40 + line * 40)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 70)
            g.drawString("BOLD", 900, 380)
            g.dispose()
        }
        val page = bits(text.width, text.height) { x, y -> text.getRGB(x, y) and 0xFF < 128 }
        val encoded = assertRoundTrip(page)
        // Far smaller than the raw bits. (The exact size depends on the fonts Java draws with, which
        // differ between operating systems, so the real check is the next one.)
        assertTrue("${encoded.size} bytes for ${page.data.size}", encoded.size < page.data.size / 5)
        // As small as Java's own T.6 coder makes it, give or take a few bytes.
        assertTrue("Ours ${encoded.size}, Java's ${javaG4(page)}", encoded.size <= javaG4(page))

        // Noise, all white, all black, stripes and checks, at widths that aren't whole bytes.
        val random = Random(7)
        for ((w, h) in listOf(1 to 1, 1 to 40, 40 to 1, 7 to 13, 9 to 9, 64 to 64, 333 to 77)) {
            assertRoundTrip(bits(w, h) { _, _ -> random.nextInt(3) == 0 })
            assertRoundTrip(bits(w, h) { _, _ -> false })
            assertRoundTrip(bits(w, h) { _, _ -> true })
            assertRoundTrip(bits(w, h) { x, _ -> x % 3 == 0 })
            assertRoundTrip(bits(w, h) { x, y -> (x + y) % 2 == 0 })
            assertRoundTrip(bits(w, h) { x, y -> (x / 5 + y / 4) % 2 == 0 })
        }
    }

    @Test fun aBlankRowIsOneBitThenTheEndOfTheBlock() {
        // V0 ("1"), then EOFB (two EOL codes, 000000000001 each), padded with zeros.
        assertTrue(CcittG4.encode(bits(8, 1) { _, _ -> false }).contentEquals(byteArrayOf(0x80.toByte(), 0x08, 0x00, 0x80.toByte())))
    }

    @Test fun aBlankPageIsAlmostNothing() {
        val blank = bits(2480, 3508) { _, _ -> false }
        assertTrue(CcittG4.encode(blank).size < 3508 / 4 + 10)
    }

    @Test fun eachColoursCodesArePrefixFree() {
        val eol = CcittG4.Code(1, 12)
        for (codes in listOf(
            CcittG4.WHITE_TERMINATING + CcittG4.WHITE_MAKE_UP + CcittG4.EXTENDED + eol,
            CcittG4.BLACK_TERMINATING + CcittG4.BLACK_MAKE_UP + CcittG4.EXTENDED + eol,
        )) {
            val strings = codes.map { it.code.toString(2).padStart(it.length, '0') }
            assertEquals(strings.size, strings.toSet().size)
            for (a in strings) for (b in strings) if (a != b) assertTrue("$a starts $b", !b.startsWith(a))
        }
        assertEquals(64, CcittG4.WHITE_TERMINATING.size)
        assertEquals(64, CcittG4.BLACK_TERMINATING.size)
        assertEquals(27, CcittG4.WHITE_MAKE_UP.size)
        assertEquals(27, CcittG4.BLACK_MAKE_UP.size)
        assertEquals(13, CcittG4.EXTENDED.size)
    }

    @Test fun theChangingElementsOfARow() {
        val image = bits(20, 1) { x, _ -> x in 0..2 || x in 9..16 }
        val out = IntArray(24)
        val count = CcittG4.changes(image, 0, out)
        assertEquals(listOf(0, 3, 9, 17), out.take(count))
        assertEquals(listOf(20, 20, 20), out.drop(count).take(3))
    }

    @Test fun flateBitsInflateToTheRowsThroughPngFilters() {
        val random = Random(3)
        val image = bits(77, 31) { _, _ -> random.nextInt(4) == 0 }
        val inflated = InflaterInputStream(ByteArrayInputStream(RasterEncoder.flateBits(image))).readBytes()
        assertEquals((image.rowBytes + 1) * image.height, inflated.size)
        // Undo the filters (bpp 1) and compare with the rows.
        var previous = ByteArray(image.rowBytes)
        for (y in 0 until image.height) {
            val filter = inflated[y * (image.rowBytes + 1)].toInt()
            val row = ByteArray(image.rowBytes)
            for (i in 0 until image.rowBytes) {
                val x = inflated[y * (image.rowBytes + 1) + 1 + i].toInt() and 0xFF
                val a = if (i > 0) row[i - 1].toInt() and 0xFF else 0
                val b = previous[i].toInt() and 0xFF
                val c = if (i > 0) previous[i - 1].toInt() and 0xFF else 0
                val p = a + b - c
                val paeth = if (kotlin.math.abs(p - a) <= kotlin.math.abs(p - b) && kotlin.math.abs(p - a) <= kotlin.math.abs(p - c)) a
                else if (kotlin.math.abs(p - b) <= kotlin.math.abs(p - c)) b else c
                row[i] = (x + when (filter) { 0 -> 0; 1 -> a; 2 -> b; 3 -> (a + b) / 2; else -> paeth }).toByte()
            }
            assertTrue("Row $y", row.contentEquals(image.data.copyOfRange(y * image.rowBytes, (y + 1) * image.rowBytes)))
            previous = row
        }
    }
}
