package io.github.nath_goa.usugami.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A picture stored as a grid of tiles, as phones store a large HEIC or AVIF photo, read at
 * 1/[sample] of its size a tile at a time. A pixel of the result is the middle pixel of its
 * [sample] × [sample] square, as Android's decoders sample a picture, so it comes from one tile.
 */
class PictureTiles(
    val width: Int,
    val height: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val sample: Int,
) {
    init {
        require(width > 0 && height > 0 && tileWidth > 0 && tileHeight > 0 && sample > 0)
    }

    val columns: Int = ((width.toLong() + tileWidth - 1) / tileWidth).toInt()
    val rows: Int = ((height.toLong() + tileHeight - 1) / tileHeight).toInt()

    /** The result's size, as Android's decoders make it: at least a pixel. */
    val outWidth: Int = max(1, width / sample)
    val outHeight: Int = max(1, height / sample)

    /** The picture's column that the result's column [x] is taken from. */
    fun sourceX(x: Int): Int = source(x, width)

    /** The picture's row that the result's row [y] is taken from. */
    fun sourceY(y: Int): Int = source(y, height)

    /** The result's columns taken from the tiles in column [column]; empty when none are. */
    fun outColumns(column: Int): IntRange = span(column, tileWidth, width, outWidth)

    /** The result's rows taken from the tiles in row [row]; empty when none are. */
    fun outRows(row: Int): IntRange = span(row, tileHeight, height, outHeight)

    private fun source(index: Int, size: Int): Int = min(size - 1, index * sample + sample / 2)

    private fun span(tile: Int, tileSize: Int, size: Int, outSize: Int): IntRange {
        val start = tile.toLong() * tileSize
        val end = start + tileSize
        val first = firstAtOrAfter(start, outSize)
        // Past the picture's end, its last pixel stands in for the middle of a square.
        val last = if (end >= size) outSize else firstAtOrAfter(end, outSize)
        return first until last
    }

    /** The first result index whose source is at or after [at]. */
    private fun firstAtOrAfter(at: Long, outSize: Int): Int {
        val offset = at - sample / 2
        val index = if (offset <= 0) 0 else (offset + sample - 1) / sample
        return min(index, outSize.toLong()).toInt()
    }
}

/**
 * Turns the YUV pixels a video codec gives into RGB the way Android's own decoder does for HEIC and
 * AVIF photos, so a photo read a tile at a time looks as it does read whole: with the matrix of the
 * colour standard the codec reports, BT.601 when it reports none, and full or limited range as it
 * says, limited when it doesn't. Android 12 and older know fewer of them (see [of]).
 */
class YuvColors private constructor(red: Double, blue: Double, fullRange: Boolean) {
    // Fixed point, 16 bits after the point, one entry per byte value.
    private val luma = IntArray(256)
    private val crToRed = IntArray(256)
    private val cbToGreen = IntArray(256)
    private val crToGreen = IntArray(256)
    private val cbToBlue = IntArray(256)

    init {
        val green = 1 - red - blue
        val lumaScale = if (fullRange) 1.0 else 255.0 / 219
        val chromaScale = if (fullRange) 1.0 else 255.0 / 224
        for (i in 0..255) {
            val y = (if (fullRange) i else i - 16) * lumaScale
            val c = (i - 128) * chromaScale
            luma[i] = fixed(y)
            crToRed[i] = fixed(2 * (1 - red) * c)
            cbToGreen[i] = fixed(2 * blue * (1 - blue) / green * c)
            crToGreen[i] = fixed(2 * red * (1 - red) / green * c)
            cbToBlue[i] = fixed(2 * (1 - blue) * c)
        }
    }

    /** The opaque ARGB colour of luma [y] and chroma [u] (Cb) and [v] (Cr), each 0 to 255. */
    fun argb(y: Int, u: Int, v: Int): Int {
        val l = luma[y] + HALF
        val r = channel((l + crToRed[v]) shr 16)
        val g = channel((l - cbToGreen[u] - crToGreen[v]) shr 16)
        val b = channel((l + cbToBlue[u]) shr 16)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun channel(value: Int): Int = if (value < 0) 0 else if (value > 255) 255 else value

    companion object {
        private const val HALF = 1 shl 15

        // MediaFormat's colour standards and ranges.
        const val STANDARD_BT709 = 1
        const val STANDARD_BT601_625 = 2
        private const val STANDARD_BT601_625_UNADJUSTED = 3
        private const val STANDARD_BT601_525 = 4
        private const val STANDARD_BT601_525_UNADJUSTED = 5
        const val STANDARD_BT2020 = 6
        private const val STANDARD_BT2020_CONSTANT = 7
        private const val STANDARD_FILM = 9
        const val RANGE_FULL = 1
        const val RANGE_LIMITED = 2

        /**
         * A pair of primaries and matrix with no standard of its own is reported as this plus
         * primaries + matrix × 7, the matrix numbered as in ColorAspects (see [matrixOf]).
         */
        private const val EXTENDED_STANDARDS = 64

        private enum class Matrix(val red: Double, val blue: Double) {
            BT601(0.299, 0.114),
            BT709(0.2126, 0.0722),
            BT2020(0.2627, 0.0593),
        }

        /**
         * The colours of a picture the codec reports as colour [standard] and [range]. Android 12
         * and older ([before13]) read BT.709 by its own standard only, full range with a BT.601
         * standard only, and the rest as BT.601 at limited range.
         */
        fun of(standard: Int, range: Int, before13: Boolean = false): YuvColors {
            if (before13) {
                val bt601 = standard == STANDARD_BT601_625 || standard == STANDARD_BT601_525
                val matrix = if (standard == STANDARD_BT709) Matrix.BT709 else Matrix.BT601
                return YuvColors(matrix.red, matrix.blue, bt601 && range == RANGE_FULL)
            }
            val matrix = matrixOf(standard)
            return YuvColors(matrix.red, matrix.blue, range == RANGE_FULL)
        }

        /** The matrix Android's colour converter uses for [standard], the closest one where it has none. */
        private fun matrixOf(standard: Int): Matrix = when (standard) {
            STANDARD_BT709, STANDARD_BT601_625_UNADJUSTED, STANDARD_BT601_525_UNADJUSTED -> Matrix.BT709
            STANDARD_BT2020, STANDARD_BT2020_CONSTANT, STANDARD_FILM -> Matrix.BT2020
            in EXTENDED_STANDARDS until EXTENDED_STANDARDS + 7 * 7 -> when ((standard - EXTENDED_STANDARDS) / 7) {
                // ColorAspects' matrices: BT.709, BT.470 M, BT.601, SMPTE 240M, BT.2020, BT.2020 constant.
                1, 4 -> Matrix.BT709
                5, 6 -> Matrix.BT2020
                else -> Matrix.BT601
            }
            else -> Matrix.BT601
        }

        private fun fixed(value: Double): Int = (value * 65536).roundToInt()
    }
}

/**
 * Brings opaque 8-bit RGB colours from one colour space to another, as Android's
 * ColorSpace.Connector does, but by tables, fast enough for the millions of pixels of a photo:
 * each channel to linear light ([toLinear]), through XYZ ([toXyz], then [fromXyz]) and back with
 * [fromLinear], held to the target's range. The matrices are 3 × 3 by columns, as ColorSpace gives
 * them, for the same white point.
 */
class RgbConversion(
    toLinear: (Double) -> Double,
    toXyz: FloatArray,
    fromXyz: FloatArray,
    fromLinear: (Double) -> Double,
) {
    private val linear = FloatArray(256) { toLinear(it / 255.0).toFloat() }
    private val matrix = FloatArray(9) { i -> (0..2).sumOf { k -> fromXyz[k * 3 + i % 3].toDouble() * toXyz[i / 3 * 3 + k] }.toFloat() }

    // The target's byte for each linear value from 0 to 1, in steps of 1/STEPS.
    private val encoded = ByteArray(STEPS + 1) { ((fromLinear(it.toDouble() / STEPS) * 255 + 0.5).toInt().coerceIn(0, 255)).toByte() }

    /** The opaque ARGB colour [argb] is in the target. */
    fun argb(argb: Int): Int {
        val r = linear[argb shr 16 and 0xFF]
        val g = linear[argb shr 8 and 0xFF]
        val b = linear[argb and 0xFF]
        val m = matrix
        return (0xFF shl 24) or
            (encode(m[0] * r + m[3] * g + m[6] * b) shl 16) or
            (encode(m[1] * r + m[4] * g + m[7] * b) shl 8) or
            encode(m[2] * r + m[5] * g + m[8] * b)
    }

    private fun encode(value: Float): Int = encoded[(value * STEPS + 0.5f).toInt().coerceIn(0, STEPS)].toInt() and 0xFF

    private companion object {
        const val STEPS = 1 shl 16
    }
}
