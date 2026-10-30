package io.github.nath_goa.pdfer.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import kotlin.random.Random

class RasterEncoderTest {

    private class ArraySource(override val width: Int, override val height: Int, val argb: IntArray) : PixelSource {
        override fun readRow(y: Int, out: IntArray) = System.arraycopy(argb, y * width, out, 0, width)
    }

    @Test
    fun colourRoundTripIsLossless() {
        val random = Random(42)
        val w = 53
        val h = 31
        // Mix of noise, smooth gradients and flat areas so every filter type gets picked.
        val argb = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            when {
                y < 10 -> random.nextInt() or (0xFF shl 24)
                y < 20 -> (0xFF shl 24) or ((x * 4) shl 16) or ((y * 8) shl 8) or (x + y)
                else -> 0xFF336699.toInt()
            }
        }
        val decoded = roundTrip(ArraySource(w, h, argb), gray = false)
        val expected = ByteArray(w * h * 3)
        argb.forEachIndexed { i, p ->
            expected[i * 3] = (p ushr 16).toByte()
            expected[i * 3 + 1] = (p ushr 8).toByte()
            expected[i * 3 + 2] = p.toByte()
        }
        assertArrayEquals(expected, decoded)
    }

    @Test
    fun transparencyIsFlattenedOntoWhite() {
        val pixels = intArrayOf(
            0x00000000,               // fully transparent black -> white
            0x80FF0000.toInt(),       // half red
            0xFF0000FF.toInt(),       // opaque blue
            0x40123456,
        )
        val decoded = roundTrip(ArraySource(4, 1, pixels), gray = false)
        fun mix(c: Int, a: Int) = ((c * a + 255 * (255 - a) + 127) / 255).toByte()
        val expected = byteArrayOf(
            255.toByte(), 255.toByte(), 255.toByte(),
            255.toByte(), mix(0, 0x80), mix(0, 0x80),
            0, 0, 255.toByte(),
            mix(0x12, 0x40), mix(0x34, 0x40), mix(0x56, 0x40),
        )
        assertArrayEquals(expected, decoded)
    }

    @Test
    fun grayDetectionAndEncoding() {
        val grayPixels = IntArray(20 * 10) { i -> val v = (i * 7) and 0xFF; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val source = ArraySource(20, 10, grayPixels)
        assertTrue(RasterEncoder.isGray(source))
        val decoded = roundTrip(source, gray = true)
        assertArrayEquals(ByteArray(grayPixels.size) { grayPixels[it].toByte() }, decoded)

        // Transparent coloured pixel flattens to white, so it is still gray.
        assertTrue(RasterEncoder.isGray(ArraySource(2, 1, intArrayOf(0x00FF0000, 0xFF808080.toInt()))))
        assertFalse(RasterEncoder.isGray(ArraySource(2, 1, intArrayOf(0xFF808081.toInt(), 0xFF808080.toInt()))))
    }

    /** Inflates and reverses the PNG row filters, as a PDF reader does for /Predictor 15. */
    private fun roundTrip(source: PixelSource, gray: Boolean): ByteArray {
        val compressed = ByteArrayOutputStream().also { RasterEncoder.writeFlate(source, gray, it) }.toByteArray()
        val inflater = Inflater()
        inflater.setInput(compressed)
        val bpp = if (gray) 1 else 3
        val rowBytes = source.width * bpp
        val raw = ByteArray((rowBytes + 1) * source.height)
        var n = 0
        while (n < raw.size) {
            val read = inflater.inflate(raw, n, raw.size - n)
            if (read == 0) break
            n += read
        }
        assertEquals(raw.size, n)
        assertTrue(inflater.finished())
        inflater.end()

        val out = ByteArray(rowBytes * source.height)
        for (y in 0 until source.height) {
            val filter = raw[y * (rowBytes + 1)].toInt()
            for (i in 0 until rowBytes) {
                val x = raw[y * (rowBytes + 1) + 1 + i].toInt() and 0xFF
                val a = if (i >= bpp) out[y * rowBytes + i - bpp].toInt() and 0xFF else 0
                val b = if (y > 0) out[(y - 1) * rowBytes + i].toInt() and 0xFF else 0
                val c = if (y > 0 && i >= bpp) out[(y - 1) * rowBytes + i - bpp].toInt() and 0xFF else 0
                val predicted = when (filter) {
                    0 -> 0
                    1 -> a
                    2 -> b
                    3 -> (a + b) / 2
                    4 -> {
                        val p = a + b - c
                        val pa = Math.abs(p - a); val pb = Math.abs(p - b); val pc = Math.abs(p - c)
                        if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                    }
                    else -> error("bad filter $filter")
                }
                out[y * rowBytes + i] = (x + predicted).toByte()
            }
        }
        return out
    }
}
