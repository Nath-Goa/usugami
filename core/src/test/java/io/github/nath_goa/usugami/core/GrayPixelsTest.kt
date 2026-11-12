package io.github.nath_goa.usugami.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

class GrayPixelsTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun levels(pixels: GrayPixels, width: Int, vararg packed: Int): List<Int> {
        val gray = ByteArray(width)
        pixels.row(bytes(*packed), width, gray)
        return gray.map { it.toInt() and 0xFF }
    }

    @Test
    fun eightBitRgbAndCmykTakeTheFormulas() {
        val samples = listOf(0, 1, 37, 127, 128, 200, 254, 255)
        val rgb = ArrayList<Int>()
        for (r in samples) for (g in samples) for (b in samples) rgb += listOf(r, g, b)
        val width = rgb.size / 3
        val gray = ByteArray(width)
        GrayPixels(GraySpace.Rgb, 8, null).row(bytes(*rgb.toIntArray()), width, gray)
        // Through the general path (CalRGB of gamma 2.2 is RGB), the same within a level.
        val general = ByteArray(width)
        GrayPixels(GraySpace.CalRgb(floatArrayOf(2.2f, 2.2f, 2.2f)), 8, null).row(bytes(*rgb.toIntArray()), width, general)
        for (x in 0 until width) {
            assertEquals(GrayMath.rgb8(rgb[3 * x], rgb[3 * x + 1], rgb[3 * x + 2]), gray[x].toInt() and 0xFF)
            assertTrue(abs((gray[x].toInt() and 0xFF) - (general[x].toInt() and 0xFF)) <= 1)
        }

        val cmyk = ArrayList<Int>()
        for (c in samples) for (m in samples) for (y in samples) for (k in listOf(0, 77, 255)) cmyk += listOf(c, m, y, k)
        val cmykWidth = cmyk.size / 4
        for (space in listOf(GraySpace.Cmyk, GraySpace.IccCmyk)) {
            val out = ByteArray(cmykWidth)
            GrayPixels(space, 8, floatArrayOf(0f, 1f, 0f, 1f, 0f, 1f, 0f, 1f)).row(bytes(*cmyk.toIntArray()), cmykWidth, out)
            for (x in 0 until cmykWidth) {
                val c = cmyk.subList(4 * x, 4 * x + 4)
                assertEquals(GrayMath.level(GrayMath.cmyk(c[0] / 255f, c[1] / 255f, c[2] / 255f, c[3] / 255f)), out[x].toInt() and 0xFF)
            }
        }
    }

    @Test
    fun samplesOfEverySizeUnpackWithRowsStartingOnAByte() {
        assertEquals(listOf(255, 0, 255, 0, 0, 0, 0, 0, 0, 255), levels(GrayPixels(GraySpace.Gray, 1, null), 10, 0b10100000, 0b01000000))
        assertEquals(listOf(0, 85, 170, 255, 255), levels(GrayPixels(GraySpace.Gray, 2, null), 5, 0b00011011, 0b11000000))
        assertEquals(listOf(17, 34, 51), levels(GrayPixels(GraySpace.Gray, 4, null), 3, 0x12, 0x30))
        assertEquals(listOf(128, 255), levels(GrayPixels(GraySpace.Gray, 16, null), 2, 0x80, 0x00, 0xFF, 0xFF))
        // RGB of 4 bits: red, then blue.
        assertEquals(listOf(77, 29), levels(GrayPixels(GraySpace.Rgb, 4, null), 2, 0xF0, 0x00, 0x0F))
        // RGB of 16 bits: red, then a mid gray.
        assertEquals(listOf(77, 128), levels(GrayPixels(GraySpace.Rgb, 16, null), 2, 0xFF, 0xFF, 0, 0, 0, 0, 0x80, 0, 0x80, 0, 0x80, 0))
        assertEquals(2, GrayPixels(GraySpace.Rgb, 1, null).rowBytes(3))
        assertEquals(2, GrayPixels(GraySpace.Gray, 4, null).rowBytes(3))
        assertEquals(12, GrayPixels(GraySpace.Rgb, 16, null).rowBytes(2))
        assertEquals(1, GrayPixels(GraySpace.Cmyk, 2, null).rowBytes(1))
        // Rows are read one at a time, each from its first byte: a 1-bit row of 3 pixels takes a byte.
        val pixels = GrayPixels(GraySpace.Gray, 1, null)
        val image = bytes(0b10100000, 0b01000000)
        val second = ByteArray(3)
        pixels.row(image.copyOfRange(pixels.rowBytes(3), 2), 3, second)
        assertArrayEquals(bytes(0, 255, 0), second)
    }

    @Test
    fun aDecodeArrayIsApplied() {
        assertEquals(listOf(255, 0, 127), levels(GrayPixels(GraySpace.Gray, 8, floatArrayOf(1f, 0f)), 3, 0, 255, 128))
        // Inverted RGB: red's samples read as cyan.
        assertEquals(listOf(GrayMath.level(179f / 256)), levels(GrayPixels(GraySpace.Rgb, 8, floatArrayOf(1f, 0f, 1f, 0f, 1f, 0f)), 1, 255, 0, 0))
        // Lab takes its own default: L* 0 to 100 for the first sample.
        val lab = GraySpace.Lab(-128f, 127f, -128f, 127f)
        assertEquals(listOf(255, 0, GrayMath.level(GrayMath.lab(128 * 100f / 255))), levels(GrayPixels(lab, 8, null), 3, 255, 128, 128, 0, 0, 255, 128, 0, 0))
    }

    @Test
    fun palettesAndSpotColoursGoThroughTheirTables() {
        val palette = GraySpace.Indexed.of(GraySpace.Rgb, 1, bytes(255, 0, 0, 0, 0, 255))
        assertEquals(listOf(77, 29, 29, 77), levels(GrayPixels(palette, 8, null), 4, 0, 1, 1, 0))
        assertEquals(listOf(77, 29, 29, 77), levels(GrayPixels(palette, 1, null), 4, 0b01100000))
        // An index past the palette is the last entry.
        assertEquals(listOf(29), levels(GrayPixels(palette, 8, null), 1, 200))
        val red = GraySpace.Tint(1, GraySpace.Rgb) { floatArrayOf(1f, 1 - it[0], 1 - it[0]) }
        assertEquals(listOf(255, 77), levels(GrayPixels(red, 8, null), 2, 0, 255))

        var calls = 0
        val twoInks = GraySpace.Tint(2, GraySpace.Gray) { calls++; floatArrayOf(1 - maxOf(it[0], it[1])) }
        val pixels = GrayPixels(twoInks, 8, null)
        val row = IntArray(2000) { if (it % 2 == 0) (it / 2) % 5 * 60 else 0 }
        val gray = ByteArray(1000)
        pixels.row(bytes(*row), 1000, gray)
        for (x in 0 until 1000) assertEquals(255 - x % 5 * 60, gray[x].toInt() and 0xFF)
        // Five colours, each worked out once.
        assertEquals(5, calls)
    }

    @Test
    fun aColourKeyMarksExactlyThePixelsInsideEveryRange() {
        val pixels = GrayPixels(GraySpace.Rgb, 8, null, intArrayOf(0, 10, 0, 10, 200, 255))
        val row = intArrayOf(
            5, 5, 250, 5, 5, 100, 11, 5, 250, 0, 0, 200, 10, 10, 255,
            1, 1, 1, 0, 0, 0, 3, 3, 199, 9, 9, 201,
        )
        val masked = bytes(0xFF, 0xFF, 0xFF)
        val gray = ByteArray(9)
        pixels.row(bytes(*row), 9, gray, masked)
        assertArrayEquals(bytes(0b10011000, 0b10000000, 0xFF), masked)
        assertEquals(GrayMath.rgb8(5, 5, 250), gray[0].toInt() and 0xFF)
        // Through the general path and with samples of 4 bits too.
        val general = GrayPixels(GraySpace.Indexed.of(GraySpace.Rgb, 15, ByteArray(48)), 4, null, intArrayOf(3, 5))
        val generalMask = ByteArray(1)
        general.row(bytes(0x23, 0x45, 0x60), 6, ByteArray(6), generalMask)
        assertArrayEquals(bytes(0b01110000), generalMask)
    }

    @Test
    fun whatCantBeReadIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { GrayPixels(GraySpace.Rgb, 3, null) }
        assertThrows(IllegalArgumentException::class.java) { GrayPixels(GraySpace.Unknown(3), 8, null) }
        assertThrows(IllegalArgumentException::class.java) { GrayPixels(GraySpace.Pattern(GraySpace.Rgb), 8, null) }
        assertThrows(IllegalArgumentException::class.java) { GrayPixels(GraySpace.None(1), 8, null) }
        assertThrows(IllegalArgumentException::class.java) { GrayPixels(GraySpace.Rgb, 8, floatArrayOf(0f, 1f)) }
        assertThrows(IllegalArgumentException::class.java) { GrayPixels(GraySpace.Rgb, 8, null, intArrayOf(0, 1)) }
        assertThrows(IllegalArgumentException::class.java) { GrayPixels(GraySpace.Rgb, 8, null).row(ByteArray(5), 2, ByteArray(2)) }
        // A tint transform that fails (a damaged function's table) is reported as a colour that
        // can't be read: in the constructor for one component, in row() for a memo of two.
        val shortTable = GraySpace.Tint(1, GraySpace.Gray) { floatArrayOf(floatArrayOf(1f, 0f)[(it[0] * 4).toInt()]) }
        assertThrows(IllegalArgumentException::class.java) { GrayPixels(shortTable, 8, null) }
        val twoInks = GraySpace.Tint(2, GraySpace.Gray) { if (it[1] > 0.5f) throw IndexOutOfBoundsException("1") else floatArrayOf(it[0]) }
        val pixels = GrayPixels(twoInks, 8, null)
        assertThrows(IllegalArgumentException::class.java) { pixels.row(bytes(0, 255), 1, ByteArray(1)) }
        val cancelled = GraySpace.Tint(1, GraySpace.Gray) { throw CancellationException() }
        assertThrows(CancellationException::class.java) { cancelled.gray(floatArrayOf(1f)) }
    }
}
