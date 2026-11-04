package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random

/** Grey JPEGs, checked by decoding them with Java's own JPEG reader. */
class GrayJpegTest {

    private fun gray(width: Int, height: Int, value: (Int, Int) -> Int) =
        GrayImage(width, height, ByteArray(width * height) { value(it % width, it / width).coerceIn(0, 255).toByte() })

    private fun decode(bytes: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(bytes))

    /** Peak signal to noise ratio of [decoded] against [image], in dB. */
    private fun psnr(image: GrayImage, decoded: BufferedImage): Double {
        var squares = 0.0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val d = image[x, y] - decoded.raster.getSample(x, y, 0)
            squares += d * d
        }
        val mse = squares / (image.width * image.height)
        return if (mse == 0.0) 99.0 else 10 * log10(255.0 * 255.0 / mse)
    }

    @Test fun aGreyJpegDecodesAsTheImage() {
        val smooth = gray(301, 203) { x, y -> (128 + 90 * sin(x / 23.0) * sin(y / 17.0)).toInt() }
        for (quality in listOf(55, 75, 85, 95)) {
            val bytes = GrayJpeg.encode(smooth, quality)
            val decoded = decode(bytes)
            assertEquals(BufferedImage.TYPE_BYTE_GRAY, decoded.type)
            assertEquals(301, decoded.width)
            assertEquals(203, decoded.height)
            val psnr = psnr(smooth, decoded)
            assertTrue("Quality $quality: $psnr dB", psnr > 36)
            val info = Jpeg.parse(bytes)
            assertEquals(1, info.components)
            assertEquals(0xC0, info.frameMarker)
            assertTrue(info.canEmbedDirectly)
        }
    }

    @Test fun qualityTradesSizeForFidelity() {
        val random = Random(5)
        val page = gray(640, 480) { x, y -> if ((x / 9 + y / 13) % 3 == 0) 30 else 235 + random.nextInt(-6, 7) }
        val sizes = listOf(30, 55, 75, 85, 100).map { q -> GrayJpeg.encode(page, q).size }
        assertEquals(sizes.sorted(), sizes)
        assertTrue(psnr(page, decode(GrayJpeg.encode(page, 100))) > psnr(page, decode(GrayJpeg.encode(page, 55))))
        // Noise at quality 100 still comes back within a level or two.
        val noise = gray(64, 64) { _, _ -> random.nextInt(256) }
        assertTrue(psnr(noise, decode(GrayJpeg.encode(noise, 100))) > 40)
        // The same size as Java's own grey JPEG at the same quality, give or take.
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 0.75f
        }
        val buffered = BufferedImage(640, 480, BufferedImage.TYPE_BYTE_GRAY)
        for (y in 0 until 480) for (x in 0 until 640) buffered.raster.setSample(x, y, 0, page[x, y])
        val java = java.io.ByteArrayOutputStream().also { out ->
            ImageIO.createImageOutputStream(out).use { ios ->
                writer.output = ios
                writer.write(null, javax.imageio.IIOImage(buffered, null, null), param)
            }
        }.size()
        val ours = GrayJpeg.encode(page, 75).size
        // Java uses the standard Huffman tables; ours are made for the picture, so smaller.
        assertTrue("Ours $ours, Java's $java", ours < java)
    }

    @Test fun huffmanTablesFitTheirSymbols() {
        // No code longer than 16 bits even for very uneven counts, none all ones, and the codes prefix-free.
        val counts = IntArray(257) { if (it < 200) 1 shl (it % 24) else 0 }
        val table = HuffmanTable.optimal(counts)
        assertEquals(200, table.symbols.size)
        assertEquals(200, table.counts.sum())
        val codes = table.symbols.map { table.codes[it].toString(2).padStart(table.lengths[it], '0') }
        assertTrue(codes.all { it.length in 1..16 && !it.all { c -> c == '1' } })
        for (a in codes) for (b in codes) if (a != b) assertTrue("$a starts $b", !b.startsWith(a))
        // A single symbol gets a one-bit code.
        val one = HuffmanTable.optimal(IntArray(257).also { it[0] = 5 })
        assertEquals(listOf(0), one.symbols.toList())
        assertEquals(1, one.lengths[0])
        // The more frequent of two symbols gets the shorter code.
        val two = HuffmanTable.optimal(IntArray(257).also { it[1] = 100; it[2] = 1; it[3] = 1 })
        assertTrue(two.lengths[1] < two.lengths[2])
    }

    @Test fun anySizeWorks() {
        for ((w, h) in listOf(1 to 1, 1 to 17, 17 to 1, 7 to 9, 8 to 8, 9 to 16, 33 to 65)) {
            val image = gray(w, h) { x, y -> (x * 31 + y * 17) % 256 }
            val decoded = decode(GrayJpeg.encode(image, 90))
            assertEquals(w, decoded.width)
            assertEquals(h, decoded.height)
            assertTrue("$w x $h", psnr(image, decoded) > 25)
        }
        // A flat picture, white and black, with long runs of zero coefficients (and 0xFF bytes stuffed).
        for (v in listOf(0, 255, 128)) {
            val flat = gray(100, 50) { _, _ -> v }
            val decoded = decode(GrayJpeg.encode(flat, 75))
            assertTrue(psnr(flat, decoded) > 45)
        }
    }

    @Test fun quantizationScalesAsLibjpeg() {
        assertEquals(16, GrayJpeg.quantization(50)[0])
        assertEquals(8, GrayJpeg.quantization(75)[0])
        assertEquals(1, GrayJpeg.quantization(100)[0])
        assertEquals(255, GrayJpeg.quantization(1)[63])
    }
}
