package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureTilesTest {
    /** Each result pixel comes from the tile that holds its source pixel, and from no other. */
    private fun assertCovered(tiles: PictureTiles) {
        val columns = IntArray(tiles.outWidth)
        for (c in 0 until tiles.columns) for (x in tiles.outColumns(c)) {
            columns[x]++
            assertEquals("column $x", c, tiles.sourceX(x) / tiles.tileWidth)
        }
        val rows = IntArray(tiles.outHeight)
        for (r in 0 until tiles.rows) for (y in tiles.outRows(r)) {
            rows[y]++
            assertEquals("row $y", r, tiles.sourceY(y) / tiles.tileHeight)
        }
        assertTrue(columns.all { it == 1 } && rows.all { it == 1 })
    }

    @Test
    fun everyPixelOfTheResultComesFromOneTile() {
        // A 200-megapixel photo in 512-pixel tiles, as phones save it.
        val photo = PictureTiles(16320, 12240, 512, 512, 4)
        assertEquals(32 to 24, photo.columns to photo.rows)
        assertEquals(4080 to 3060, photo.outWidth to photo.outHeight)
        assertEquals(2, photo.sourceX(0))
        assertCovered(photo)
        for (sample in 1..9) {
            assertCovered(PictureTiles(1000, 777, 256, 100, sample))
            assertCovered(PictureTiles(513, 512, 512, 512, sample))
        }
        // A picture narrower than a sample is one pixel wide, from its last column.
        val thin = PictureTiles(3, 4000, 64, 512, 8)
        assertEquals(1 to 500, thin.outWidth to thin.outHeight)
        assertEquals(2, thin.sourceX(0))
        assertCovered(thin)
    }

    private fun rgb(argb: Int) = Triple(argb shr 16 and 255, argb shr 8 and 255, argb and 255)

    private fun assertNear(expected: Triple<Int, Int, Int>, actual: Int) {
        val (r, g, b) = rgb(actual)
        assertTrue("$expected, was ${rgb(actual)}", maxOf(Math.abs(expected.first - r), Math.abs(expected.second - g), Math.abs(expected.third - b)) <= 1)
        assertEquals(0xFF, actual ushr 24)
    }

    @Test
    fun yuvTurnsIntoRgbAsAndroidDoes() {
        // libheif's photos: BT.709 primaries with the BT.601 matrix, which has no standard of its
        // own, at full range. Blue (40, 40, 200) is stored as Y 58, U 208, V 115.
        val libheif = YuvColors.of(64 + 1 + 3 * 7, YuvColors.RANGE_FULL)
        assertNear(Triple(40, 40, 200), libheif.argb(58, 208, 115))
        // None said: BT.601, limited range, where 16 is black and 235 white.
        val unsaid = YuvColors.of(0, 0)
        assertNear(Triple(0, 0, 0), unsaid.argb(16, 128, 128))
        assertNear(Triple(255, 255, 255), unsaid.argb(235, 128, 128))
        assertNear(Triple(255, 0, 0), unsaid.argb(81, 90, 240))
        assertNear(Triple(255, 0, 0), YuvColors.of(YuvColors.STANDARD_BT709, YuvColors.RANGE_LIMITED).argb(63, 102, 240))
        assertNear(Triple(255, 0, 0), YuvColors.of(YuvColors.STANDARD_BT2020, YuvColors.RANGE_FULL).argb(67, 92, 255))
        // Android 12 reads libheif's blue at limited range, and BT.709 at full range too.
        assertNear(Triple(28, 28, 210), YuvColors.of(64 + 1 + 3 * 7, YuvColors.RANGE_FULL, before13 = true).argb(58, 208, 115))
        assertNear(Triple(255, 0, 0), YuvColors.of(YuvColors.STANDARD_BT709, YuvColors.RANGE_FULL, before13 = true).argb(63, 102, 240))
        assertNear(Triple(40, 40, 200), YuvColors.of(YuvColors.STANDARD_BT601_625, YuvColors.RANGE_FULL, before13 = true).argb(58, 208, 115))
        // Out of range values are held to it.
        assertNear(Triple(0, 0, 0), unsaid.argb(0, 128, 128))
        assertNear(Triple(255, 255, 255), unsaid.argb(255, 128, 128))
    }

    @Test
    fun displayP3TurnsIntoSrgbAsWorkedOut() {
        // sRGB's curve, which Display P3 shares, and the matrices to and from XYZ (D65), by columns.
        fun toLinear(v: Double) = if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        fun fromLinear(v: Double) = if (v <= 0.0031308) v * 12.92 else 1.055 * Math.pow(v, 1 / 2.4) - 0.055
        val p3ToXyz = floatArrayOf(0.4865709f, 0.2289746f, 0f, 0.2656677f, 0.6917385f, 0.0451134f, 0.1982173f, 0.0792869f, 1.0439444f)
        val xyzToSrgb = floatArrayOf(3.2404542f, -0.969266f, 0.0556434f, -1.5371385f, 1.8760108f, -0.2040259f, -0.4985314f, 0.041556f, 1.0572252f)
        val conversion = RgbConversion(::toLinear, p3ToXyz, xyzToSrgb, ::fromLinear)
        fun times(m: FloatArray, v: List<Double>) = (0..2).map { i -> (0..2).sumOf { j -> m[j * 3 + i] * v[j] } }
        for (r in 0..255 step 5) for (g in 0..255 step 5) for (b in 0..255 step 5) {
            val linear = times(xyzToSrgb, times(p3ToXyz, listOf(r, g, b).map { toLinear(it / 255.0) }))
            val (er, eg, eb) = linear.map { (fromLinear(it.coerceIn(0.0, 1.0)) * 255 + 0.5).toInt() }
            assertNear(Triple(er, eg, eb), conversion.argb((0xFF shl 24) or (r shl 16) or (g shl 8) or b))
        }
        // sRGB's red is (234, 51, 35) in Display P3; P3's own red is beyond sRGB, held to its red.
        assertNear(Triple(255, 0, 0), conversion.argb(0xFFEA3323.toInt()))
        assertNear(Triple(255, 0, 0), conversion.argb(0xFFFF0000.toInt()))
        assertNear(Triple(128, 128, 128), conversion.argb(0xFF808080.toInt()))
    }
}
