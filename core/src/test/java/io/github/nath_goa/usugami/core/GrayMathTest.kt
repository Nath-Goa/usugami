package io.github.nath_goa.usugami.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GrayMathTest {

    @Test
    fun rgbIsRec601LumaOnTheStoredValues() {
        assertEquals(0f, GrayMath.rgb(0f, 0f, 0f))
        assertEquals(1f, GrayMath.rgb(1f, 1f, 1f))
        assertEquals(77f / 256, GrayMath.rgb(1f, 0f, 0f))
        assertEquals(150f / 256, GrayMath.rgb(0f, 1f, 0f))
        assertEquals(29f / 256, GrayMath.rgb(0f, 0f, 1f))
        // Out of range is kept in range, as viewers keep an operand in its range; NaN counts as 0.
        assertEquals(77f / 256, GrayMath.rgb(2f, -1f, Float.NaN))
        assertEquals(255, GrayMath.rgb8(255, 255, 255))
        assertEquals(0, GrayMath.rgb8(0, 0, 0))
        for (r in 0..255 step 15) for (g in 0..255 step 17) for (b in 0..255 step 51) {
            val exact = 255 * GrayMath.rgb(r / 255f, g / 255f, b / 255f)
            assertTrue("$r $g $b", abs(GrayMath.rgb8(r, g, b) - exact) <= 0.5f + 1e-4f)
        }
    }

    @Test
    fun cmykKeepsBlackExactAndDarkInksApart() {
        assertEquals(0f, GrayMath.cmyk(0f, 0f, 0f, 1f))
        assertEquals(0.75f, GrayMath.cmyk(0f, 0f, 0f, 0.25f), 1e-6f)
        assertEquals(1f, GrayMath.cmyk(0f, 0f, 0f, 0f))
        for ((c, m, y) in listOf(Triple(1f, 0f, 0f), Triple(0.2f, 0.5f, 0.9f), Triple(0.7f, 0.1f, 0.3f))) {
            assertEquals(GrayMath.rgb(1 - c, 1 - m, 1 - y), GrayMath.cmyk(c, m, y, 0f), 1e-6f)
        }
        val darkBlue = GrayMath.cmyk(1f, 0.8f, 0f, 0.3f)
        assertTrue(darkBlue > GrayMath.cmyk(0f, 0f, 0f, 1f))
        assertTrue(darkBlue < GrayMath.cmyk(1f, 0.8f, 0f, 0f))
        assertEquals(0f, GrayMath.cmyk(1f, 1f, 1f, 1f))
    }

    @Test
    fun labIsTheSrgbGrayOfItsLightness() {
        assertEquals(0f, GrayMath.lab(0f), 1e-6f)
        assertEquals(0.466f, GrayMath.lab(50f), 0.001f)
        assertEquals(1f, GrayMath.lab(100f), 1e-5f)
        var last = -1f
        for (l in 0..100) {
            val gray = GrayMath.lab(l.toFloat())
            assertTrue("L $l", gray > last)
            last = gray
        }
    }

    @Test
    fun gammaOf22LeavesAValueAndOf1BrightensIt() {
        for (v in listOf(0f, 0.2f, 0.5f, 0.9f, 1f)) assertEquals(v, GrayMath.gamma(v, 2.2f), 1e-6f)
        assertTrue(GrayMath.gamma(0.5f, 1f) > 0.5f)
        assertEquals(0f, GrayMath.gamma(0f, 1f))
        assertEquals(1f, GrayMath.gamma(1f, 1f))
        // A gamma that isn't a positive number counts as 1, the default.
        assertEquals(GrayMath.gamma(0.5f, 1f), GrayMath.gamma(0.5f, 0f))
    }

    @Test
    fun spacesHaveTheirComponentsInitialColoursAndDecodes() {
        assertEquals(1, GraySpace.Gray.components)
        assertEquals(3, GraySpace.Rgb.components)
        assertEquals(4, GraySpace.Cmyk.components)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), GraySpace.Rgb.initial, 0f)
        assertEquals(0f, GraySpace.Rgb.gray(GraySpace.Rgb.initial))
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f), GraySpace.Cmyk.initial, 0f)
        assertEquals(0f, GraySpace.Cmyk.gray(GraySpace.Cmyk.initial))
        // An ICCBased space of four starts with no ink: white.
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f), GraySpace.IccCmyk.initial, 0f)
        assertEquals(1f, GraySpace.IccCmyk.gray(GraySpace.IccCmyk.initial))
        assertEquals(GraySpace.Cmyk.gray(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)), GraySpace.IccCmyk.gray(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)))
        val spot = GraySpace.Tint(1, GraySpace.Cmyk) { floatArrayOf(0f, it[0] * 0.6f, it[0], 0f) }
        assertArrayEquals(floatArrayOf(1f), spot.initial, 0f)
        assertEquals(GrayMath.cmyk(0f, 0.6f, 1f, 0f), spot.gray(spot.initial))
        val lab = GraySpace.Lab(-128f, 127f, -50f, 50f)
        assertArrayEquals(floatArrayOf(0f, 100f, -128f, 127f, -50f, 50f), lab.defaultDecode(8), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), lab.initial, 0f)
        assertArrayEquals(floatArrayOf(0f, 255f), GraySpace.Indexed(GraySpace.Rgb, FloatArray(256)).defaultDecode(8), 0f)
        assertArrayEquals(floatArrayOf(0f, 15f), GraySpace.Indexed(GraySpace.Rgb, FloatArray(16)).defaultDecode(4), 0f)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f, 1f, 0f, 1f), GraySpace.Rgb.defaultDecode(8), 0f)
    }

    @Test
    fun aPaletteHoldsTheGrayOfEachEntryAndKeepsItsIndexInRange() {
        val rgb = GraySpace.Indexed.of(GraySpace.Rgb, 2, byteArrayOf(0, 0, 0, -1, 0, 0, 0, 0, -1))
        assertEquals(3, rgb.grays.size)
        assertEquals(0f, rgb.gray(floatArrayOf(0f)))
        assertEquals(77f / 256, rgb.gray(floatArrayOf(1f)), 1e-6f)
        assertEquals(29f / 256, rgb.gray(floatArrayOf(2f)), 1e-6f)
        assertEquals(29f / 256, rgb.gray(floatArrayOf(7f)), 1e-6f)
        assertEquals(0f, rgb.gray(floatArrayOf(-3f)))
        val cmyk = GraySpace.Indexed.of(GraySpace.Cmyk, 1, byteArrayOf(0, 0, 0, -1, 0, 0, 0, 0))
        assertEquals(0f, cmyk.gray(floatArrayOf(0f)))
        assertEquals(1f, cmyk.gray(floatArrayOf(1f)))
        // A table cut short reads 0 for what it lacks: no ink in CMYK.
        assertEquals(1f, GraySpace.Indexed.of(GraySpace.Cmyk, 3, byteArrayOf(0, 0, 0, -1)).gray(floatArrayOf(3f)))
        // Lab entries are bytes mapped onto L* 0 to 100.
        val lab = GraySpace.Indexed.of(GraySpace.Lab(-100f, 100f, -100f, 100f), 0, byteArrayOf(-1, 0, 0))
        assertEquals(1f, lab.gray(floatArrayOf(0f)), 1e-5f)
    }

    @Test
    fun aTintGoesThroughItsFunctionToTheAlternate() {
        val red = GraySpace.Tint(1, GraySpace.Rgb) { floatArrayOf(1f, 1 - it[0], 1 - it[0]) }
        assertEquals(1f, red.gray(floatArrayOf(0f)))
        assertEquals(77f / 256, red.gray(floatArrayOf(1f)), 1e-6f)
        // Fewer values than the alternate takes: the rest read 0.
        val short = GraySpace.Tint(2, GraySpace.Rgb) { floatArrayOf(it[0]) }
        assertEquals(77f / 256, short.gray(floatArrayOf(1f, 0f)), 1e-6f)
        // NaN from a damaged function is a value in range, not a crash.
        val broken = GraySpace.Tint(1, GraySpace.Gray) { floatArrayOf(Float.NaN) }
        assertEquals(0f, broken.gray(floatArrayOf(1f)))
    }

    @Test
    fun grayAndLinearSpaces() {
        assertTrue(GraySpace.Gray.isGray)
        assertTrue(GraySpace.CalGray(1.8f).isGray)
        assertTrue(GraySpace.Indexed.of(GraySpace.Gray, 3, byteArrayOf(0, 85, -86, -1)).isGray)
        assertFalse(GraySpace.Indexed.of(GraySpace.Rgb, 0, byteArrayOf(0, 0, 0)).isGray)
        assertFalse(GraySpace.Rgb.isGray)
        assertFalse(GraySpace.Tint(1, GraySpace.Gray) { it }.isGray)
        assertTrue(GraySpace.Rgb.linear)
        for (space in listOf(GraySpace.Gray, GraySpace.Cmyk, GraySpace.IccCmyk, GraySpace.CalRgb(floatArrayOf(1f, 1f, 1f)), GraySpace.Lab(-100f, 100f, -100f, 100f))) {
            assertFalse(space.toString(), space.linear)
        }
        // CalRGB with a gamma of 2.2 is RGB.
        assertEquals(GrayMath.rgb(0.2f, 0.4f, 0.6f), GraySpace.CalRgb(floatArrayOf(2.2f, 2.2f, 2.2f)).gray(floatArrayOf(0.2f, 0.4f, 0.6f)), 1e-6f)
    }
}
