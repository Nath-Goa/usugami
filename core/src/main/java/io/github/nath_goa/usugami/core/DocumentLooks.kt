package io.github.nath_goa.usugami.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** How a page looks in its PDF: as photographed, or cleaned up like a scan. */
enum class PageLook {
    /** The picture as it is. */
    ORIGINAL,

    /** In colour, with the paper made white (the light's colour and shadows taken out) and the ink darker. */
    ENHANCED,

    /** As [ENHANCED], in shades of grey. */
    GRAYSCALE,

    /** Black ink on white paper, one bit a pixel. */
    BLACK_WHITE,
}

/** A grey picture: a byte a pixel, 0 black to 255 white, row after row. */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray = ByteArray(width * height)) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "A $width x $height picture needs ${width * height} bytes" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF
}

/**
 * A black-and-white picture, a bit a pixel: rows of [rowBytes] bytes, the leftmost pixel in the high
 * bit, 1 for white and 0 for black, as a PDF's DeviceGray reads them. Unused bits at a row's end are 1.
 */
class BitImage(val width: Int, val height: Int, val data: ByteArray = ByteArray(((width + 7) / 8) * height)) {
    val rowBytes: Int get() = (width + 7) / 8

    init {
        require(width > 0 && height > 0 && data.size == rowBytes * height) { "A $width x $height picture needs ${rowBytes * height} bytes" }
    }

    fun isBlack(x: Int, y: Int): Boolean = (data[y * rowBytes + (x ushr 3)].toInt() shr (7 - (x and 7))) and 1 == 0
}

/**
 * The pixel work behind a page's [PageLook]: plain Kotlin on rows of pixels, so a large photo never
 * needs more than a byte a pixel besides itself.
 *
 * Every look first finds the paper: what the page would show with no ink on it, shadows and the
 * light's colour included. The picture is cut into blocks (about [BLOCKS] along its long side), and
 * each block's paper is a bright level that most of its pixels reach ([PAPER_SHARE] of them are as
 * bright or brighter), so print on it doesn't count. A morphological closing of those levels then
 * fills blocks covered by a large heading or a dark box from the paper around them, while a shadow,
 * which covers many blocks, keeps its edges. Dividing each pixel by the paper there makes the paper
 * white everywhere: per channel for [PageLook.ENHANCED], which white-balances the page too.
 *
 * Black and white then needs only a threshold, but one that adapts to each part of the page
 * (Sauvola's, with the mean and spread of a window around each pixel kept as running sums): it finds
 * faint print beside dark, and lets paper the division left a little grey (at a shadow's edge) stay
 * white. Pixels darker than [INK] are always black, so the inside of a bold letter is never left white.
 */
object DocumentLooks {
    /** The picture is cut into about this many blocks along its long side to find the paper. */
    const val BLOCKS = 64

    /** The share of a block's pixels at least as bright as the level taken for its paper. */
    const val PAPER_SHARE = 0.2

    /** How many blocks round the closing reaches: dark areas up to about twice as wide are filled. */
    const val CLOSING = 3

    /** Paper is never taken to be darker than this part of the page's brightest paper (or 24). */
    const val DARKEST_PAPER = 0.3f

    /** Where the paper ends up: [WHITE] and brighter turns white. */
    const val WHITE = 235f

    /** The curve that deepens the ink once the paper is white. */
    const val GAMMA = 1.6

    /** A black-and-white pixel this dark (on paper made white) is always black. */
    const val INK = 100

    /** Sauvola's k and R: how far below a window's mean the threshold sits where it is flat. */
    const val SAUVOLA_K = 0.2
    const val SAUVOLA_R = 128.0

    /** The threshold's window is about this many times smaller than the picture's long side. */
    const val WINDOW = 40

    /**
     * [source] with the [PageLook.ENHANCED] look, handed to [target] a row at a time (opaque ARGB;
     * the row is reused). [source] is read twice.
     */
    fun enhance(source: PixelSource, target: (y: Int, row: IntArray) -> Unit, checkCancelled: () -> Unit = {}) {
        val width = source.width
        val height = source.height
        val red = PaperEstimator(width, height)
        val green = PaperEstimator(width, height)
        val blue = PaperEstimator(width, height)
        val row = IntArray(width)
        val r = IntArray(width)
        val g = IntArray(width)
        val b = IntArray(width)
        for (y in 0 until height) {
            if (y and 63 == 0) checkCancelled()
            source.readRow(y, row)
            for (x in 0 until width) {
                val argb = row[x]
                r[x] = flatten(argb ushr 16, argb)
                g[x] = flatten(argb ushr 8, argb)
                b[x] = flatten(argb, argb)
            }
            red.addRow(r)
            green.addRow(g)
            blue.addRow(b)
        }
        val paperR = red.finish()
        val paperG = green.finish()
        val paperB = blue.finish()
        val lr = FloatArray(width)
        val lg = FloatArray(width)
        val lb = FloatArray(width)
        val dr = FloatArray(width)
        val dg = FloatArray(width)
        val db = FloatArray(width)
        for (y in 0 until height) {
            if (y and 63 == 0) checkCancelled()
            source.readRow(y, row)
            paperR.row(y, lr, dr)
            paperG.row(y, lg, dg)
            paperB.row(y, lb, db)
            for (x in 0 until width) {
                val argb = row[x]
                val vr = flatten(argb ushr 16, argb)
                val vg = flatten(argb ushr 8, argb)
                val vb = flatten(argb, argb)
                // One weight for all three, so the paper beside a shadow doesn't get coloured speckles.
                val t = (paperWeight(vr, lr[x], dr[x]) + paperWeight(vg, lg[x], dg[x]) + paperWeight(vb, lb[x], db[x])) / 3
                val cr = CURVE[normalized(vr, lr[x], dr[x], t)]
                val cg = CURVE[normalized(vg, lg[x], dg[x], t)]
                val cb = CURVE[normalized(vb, lb[x], db[x], t)]
                row[x] = (0xFF shl 24) or (cr shl 16) or (cg shl 8) or cb
            }
            target(y, row)
        }
    }

    /** [source] with the [PageLook.GRAYSCALE] look. */
    fun grayscale(source: PixelSource, checkCancelled: () -> Unit = {}): GrayImage {
        val (image, paper) = lumaAndPaper(source, checkCancelled)
        normalize(image, paper, CURVE, checkCancelled)
        return image
    }

    /** [source] with the [PageLook.BLACK_WHITE] look. */
    fun blackAndWhite(source: PixelSource, checkCancelled: () -> Unit = {}): BitImage {
        val (image, paper) = lumaAndPaper(source, checkCancelled)
        normalize(image, paper, LINEAR, checkCancelled)
        return threshold(image, checkCancelled)
    }

    /** The brightness of each pixel (flattened onto white), and the paper's. */
    private fun lumaAndPaper(source: PixelSource, checkCancelled: () -> Unit): Pair<GrayImage, PaperMap> {
        val width = source.width
        val height = source.height
        val image = GrayImage(width, height)
        val estimator = PaperEstimator(width, height)
        val row = IntArray(width)
        val luma = IntArray(width)
        for (y in 0 until height) {
            if (y and 63 == 0) checkCancelled()
            source.readRow(y, row)
            val offset = y * width
            for (x in 0 until width) {
                val argb = row[x]
                val v = luma(flatten(argb ushr 16, argb), flatten(argb ushr 8, argb), flatten(argb, argb))
                luma[x] = v
                image.pixels[offset + x] = v.toByte()
            }
            estimator.addRow(luma)
        }
        return image to estimator.finish()
    }

    /** Each pixel of [image] divided by the paper there, then through [curve]. */
    private fun normalize(image: GrayImage, paper: PaperMap, curve: IntArray, checkCancelled: () -> Unit) {
        val width = image.width
        val levels = FloatArray(width)
        val darkest = FloatArray(width)
        for (y in 0 until image.height) {
            if (y and 63 == 0) checkCancelled()
            paper.row(y, levels, darkest)
            val offset = y * width
            for (x in 0 until width) {
                val v = image.pixels[offset + x].toInt() and 0xFF
                image.pixels[offset + x] = curve[normalized(v, levels[x], darkest[x], paperWeight(v, levels[x], darkest[x]))].toByte()
            }
        }
    }

    /**
     * Sauvola's threshold on [image] (its paper already white): black where a pixel is darker than
     * its window's mean m lowered by k (1 - s / R), s the window's standard deviation, or darker than
     * [INK]. The window's sums come from running column sums, so each pixel costs the same whatever
     * the window's size.
     */
    internal fun threshold(image: GrayImage, checkCancelled: () -> Unit = {}): BitImage {
        val width = image.width
        val height = image.height
        val pixels = image.pixels
        val radius = windowRadius(width, height)
        val out = BitImage(width, height)
        val rowBytes = out.rowBytes
        val columnSum = IntArray(width)
        val columnSquares = LongArray(width)
        var top = 0
        var bottom = -1
        fun addRow(y: Int, sign: Int) {
            val offset = y * width
            for (x in 0 until width) {
                val v = pixels[offset + x].toInt() and 0xFF
                columnSum[x] += sign * v
                columnSquares[x] += (sign * v * v).toLong()
            }
        }
        for (y in 0 until height) {
            if (y and 63 == 0) checkCancelled()
            val newBottom = min(height - 1, y + radius)
            val newTop = max(0, y - radius)
            while (bottom < newBottom) addRow(++bottom, 1)
            while (top < newTop) addRow(top++, -1)
            val rows = bottom - top + 1
            var sum = 0L
            var squares = 0L
            var left = 0
            var right = -1
            val offset = y * width
            val rowStart = y * rowBytes
            var bits = 0
            for (x in 0 until width) {
                val newRight = min(width - 1, x + radius)
                val newLeft = max(0, x - radius)
                while (right < newRight) {
                    right++
                    sum += columnSum[right]
                    squares += columnSquares[right]
                }
                while (left < newLeft) {
                    sum -= columnSum[left]
                    squares -= columnSquares[left]
                    left++
                }
                val v = pixels[offset + x].toInt() and 0xFF
                val black = when {
                    v < INK -> true
                    else -> {
                        val n = ((right - left + 1) * rows).toDouble()
                        val mean = sum / n
                        // At or above the mean, a pixel is white whatever the spread.
                        if (v >= mean) {
                            false
                        } else {
                            val deviation = sqrt(max(0.0, squares / n - mean * mean))
                            v < mean * (1 + SAUVOLA_K * (deviation / SAUVOLA_R - 1))
                        }
                    }
                }
                bits = (bits shl 1) or (if (black) 0 else 1)
                if (x and 7 == 7) {
                    out.data[rowStart + (x ushr 3)] = bits.toByte()
                    bits = 0
                }
            }
            val left8 = width and 7
            if (left8 != 0) {
                // The row's last pixels, then white padding.
                bits = (bits shl (8 - left8)) or ((1 shl (8 - left8)) - 1)
                out.data[rowStart + rowBytes - 1] = bits.toByte()
            }
        }
        return out
    }

    /** Half the width of the threshold's window for a [width] x [height] picture. */
    internal fun windowRadius(width: Int, height: Int): Int = max(4, max(width, height) / (2 * WINDOW))

    /** The side of the blocks the paper is found in. */
    internal fun blockSize(width: Int, height: Int): Int = max(8, ceil(max(width, height) / BLOCKS.toDouble()).toInt())

    /**
     * [v] on paper of brightness [paper], made white: 0 to 255. Between blocks the paper's level goes
     * smoothly from one block's to the next, so beside a shadow's hard edge it is too bright, and the
     * paper there would come out grey: a pixel that is as bright as the paper of the [darkest] of the
     * blocks around it is taken as paper on that block's level instead (gradually, so as not to make
     * speckles).
     */
    private fun normalized(v: Int, paper: Float, darkest: Float, weight: Float): Int {
        val n = v * 255f / paper
        if (n >= WHITE || weight == 0f) return min(255, (n + 0.5f).toInt())
        val m = v * 255f / darkest
        return min(255, (n + weight * (m - n) + 0.5f).toInt())
    }

    /** How much [v] counts as paper on the [darkest] paper around it: 0 not at all, to 1. */
    private fun paperWeight(v: Int, paper: Float, darkest: Float): Float {
        if (v * 255f / paper >= WHITE) return 0f
        val m = v * 255f / darkest
        return if (m <= EDGE) 0f else min(1f, (m - EDGE) / (WHITE - EDGE))
    }

    /** See [normalized]: a pixel this bright on the darkest paper around it starts to count as paper. */
    private const val EDGE = 200f

    /** The brightness of an sRGB colour, as the page finder and most photo apps take it. */
    internal fun luma(r: Int, g: Int, b: Int): Int = (r * 77 + g * 150 + b * 29) shr 8

    /** One 8-bit channel (the low byte of [channel]) composited over white using the alpha of [argb]. */
    private fun flatten(channel: Int, argb: Int): Int {
        val value = channel and 0xFF
        val alpha = argb ushr 24
        if (alpha == 0xFF) return value
        return (value * alpha + 255 * (255 - alpha) + 127) / 255
    }

    /** Paper made white, ink deepened: [WHITE] and up to 255, the rest along a gamma curve. */
    private val CURVE = IntArray(256) { v -> (255 * min(1.0, v / WHITE.toDouble()).pow(GAMMA) + 0.5).toInt() }

    private val LINEAR = IntArray(256) { it }
}

/**
 * Finds the paper's level in each block of a picture, from its rows of one channel given one after
 * another (see [DocumentLooks]): each block's histogram, and the level [DocumentLooks.PAPER_SHARE] of
 * its pixels reach.
 */
internal class PaperEstimator(private val width: Int, private val height: Int) {
    private val block = DocumentLooks.blockSize(width, height)
    private val columns = (width + block - 1) / block
    private val rows = (height + block - 1) / block
    private val levels = FloatArray(columns * rows)
    private val histograms = IntArray(columns * 256)
    private val histogramOf = IntArray(width) { (it / block) * 256 }
    private var y = 0

    fun addRow(values: IntArray) {
        check(y < height) { "More rows than the picture has" }
        for (x in 0 until width) histograms[histogramOf[x] + values[x]]++
        y++
        if (y % block == 0 || y == height) {
            val blockRow = (y - 1) / block
            for (column in 0 until columns) {
                val start = column * 256
                var total = 0
                for (v in 0..255) total += histograms[start + v]
                val wanted = max(1, (total * DocumentLooks.PAPER_SHARE).toInt())
                var seen = 0
                var level = 255
                while (level > 0) {
                    seen += histograms[start + level]
                    if (seen >= wanted) break
                    level--
                }
                levels[blockRow * columns + column] = level.toFloat()
                histograms.fill(0, start, start + 256)
            }
        }
    }

    /** The paper across the picture, once every row has been added. */
    fun finish(): PaperMap {
        check(y == height) { "Only $y of $height rows were added" }
        val closed = erode(dilate(levels))
        val brightest = closed.sorted()[(closed.size * 9) / 10]
        val floor = max(24f, brightest * DocumentLooks.DARKEST_PAPER)
        for (i in closed.indices) closed[i] = max(closed[i], floor)
        return PaperMap(width, height, block, columns, rows, closed)
    }

    private fun dilate(grid: FloatArray) = filter(grid) { a, b -> max(a, b) }

    private fun erode(grid: FloatArray) = filter(grid) { a, b -> min(a, b) }

    /** A separable max or min over the blocks within [DocumentLooks.CLOSING] of each. */
    private inline fun filter(grid: FloatArray, pick: (Float, Float) -> Float): FloatArray {
        val reach = DocumentLooks.CLOSING
        val across = FloatArray(grid.size)
        for (r in 0 until rows) for (c in 0 until columns) {
            var v = grid[r * columns + c]
            for (k in max(0, c - reach)..min(columns - 1, c + reach)) v = pick(v, grid[r * columns + k])
            across[r * columns + c] = v
        }
        val out = FloatArray(grid.size)
        for (r in 0 until rows) for (c in 0 until columns) {
            var v = across[r * columns + c]
            for (k in max(0, r - reach)..min(rows - 1, r + reach)) v = pick(v, across[k * columns + c])
            out[r * columns + c] = v
        }
        return out
    }
}

/** The paper's level across a picture, a value per block, read back a row of pixels at a time between the blocks' centres. */
internal class PaperMap(
    private val width: Int,
    private val height: Int,
    private val block: Int,
    private val columns: Int,
    private val rows: Int,
    private val levels: FloatArray,
) {
    private val firstColumn = IntArray(width)
    private val columnWeight = FloatArray(width)
    private val blockRow = FloatArray(columns)

    init {
        for (x in 0 until width) {
            val at = ((x + 0.5f) / block - 0.5f).coerceIn(0f, (columns - 1).toFloat())
            val c = min(at.toInt(), max(0, columns - 2))
            firstColumn[x] = c
            columnWeight[x] = if (columns == 1) 0f else at - c
        }
    }

    /** The paper's level at each pixel of row [y], into [out], and the darkest of the four blocks around each pixel into [darkest]. */
    fun row(y: Int, out: FloatArray, darkest: FloatArray) {
        val at = ((y + 0.5f) / block - 0.5f).coerceIn(0f, (rows - 1).toFloat())
        val r = min(at.toInt(), max(0, rows - 2))
        val t = if (rows == 1) 0f else at - r
        val r2 = min(r + 1, rows - 1)
        for (c in 0 until columns) blockRow[c] = levels[r * columns + c] * (1 - t) + levels[r2 * columns + c] * t
        for (x in 0 until width) {
            val c = firstColumn[x]
            val w = columnWeight[x]
            val c2 = min(c + 1, columns - 1)
            out[x] = blockRow[c] * (1 - w) + blockRow[c2] * w
            darkest[x] = min(min(levels[r * columns + c], levels[r * columns + c2]), min(levels[r2 * columns + c], levels[r2 * columns + c2]))
        }
    }

    /** The level found for block ([column], [row]), for tests. */
    fun block(column: Int, row: Int): Float = levels[row * columns + column]
}
