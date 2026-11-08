package io.github.nath_goa.usugami.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
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

    /**
     * In colour and still much as photographed, only better lit: brighter, the paper lighter and more
     * even, shadows and the light's colour softened rather than taken out, and the ink left as it is.
     */
    LIGHTEN,

    /**
     * As [ENHANCED], further: the paper white sooner, the print darker and sharpened, and coloured ink
     * and stamps richer.
     */
    SUPER_ENHANCED,

    /**
     * As [BLACK_WHITE], printing lighter: the print stays as it is, while a large dark area (a black box,
     * a photo, the table left round a page) keeps only its outline.
     */
    INK_SAVER,
    ;

    /** Whether the page is black and white, a bit a pixel. */
    val isBilevel: Boolean get() = this == BLACK_WHITE || this == INK_SAVER

    /** Whether the page stays in colour. */
    val isColour: Boolean get() = this == ORIGINAL || this == LIGHTEN || this == ENHANCED || this == SUPER_ENHANCED

    companion object {
        /**
         * The looks in the order the filter screen shows them, from the photo as it is to the most
         * cleaned up. The entries' own order is how a saved state stores a page's look, so a new look
         * goes at the end of them.
         */
        val shown: List<PageLook> = listOf(ORIGINAL, LIGHTEN, ENHANCED, SUPER_ENHANCED, GRAYSCALE, BLACK_WHITE, INK_SAVER)
    }
}

/**
 * Brightness and contrast set by hand on top of a page's look, each from -[MAX] to [MAX]; 0 leaves the
 * look as it is.
 */
data class LookAdjustments(val brightness: Int = 0, val contrast: Int = 0) {
    init {
        require(brightness in -MAX..MAX && contrast in -MAX..MAX) { "Brightness $brightness and contrast $contrast must be within ±$MAX" }
    }

    /** Whether the look is left as it is. */
    val isNone: Boolean get() = brightness == 0 && contrast == 0

    companion object {
        const val MAX = 100
        val NONE = LookAdjustments()
    }
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
 * Every look but [PageLook.ORIGINAL] first finds the paper: what the page would show with no ink on
 * it, shadows and the light's colour included. The picture is cut into blocks (about [BLOCKS] along
 * its long side), and each block's paper is a bright level that most of its pixels reach
 * ([PAPER_SHARE] of them are as bright or brighter), so print on it doesn't count. A morphological
 * closing of those levels then fills blocks covered by a large heading or a dark box from the paper
 * around them, while a shadow, which covers many blocks, keeps its edges. Dividing each pixel by the
 * paper there makes the paper white everywhere: per channel for the colour looks, which white-balances
 * the page too.
 *
 * Then each look does its own:
 * - [PageLook.LIGHTEN] puts part of the light back: the paper ends up as bright as the photo's own
 *   paper to the power [LIGHT_SHADE], at most [LIGHT_PAPER] but never darker than it was, and keeps
 *   part of its colour, so a shadow half as bright as the rest of the page comes out about three
 *   quarters as bright. The ink is not deepened, so the page still looks like the photo, only better
 *   lit.
 * - [PageLook.ENHANCED] makes [WHITE] and brighter white and deepens the ink along a gamma curve.
 * - [PageLook.SUPER_ENHANCED] does so along a steeper curve, white from [SUPER_WHITE], though a pale
 *   colour (a stain) gets no more colour than [PageLook.ENHANCED] gives it; then makes clearly
 *   coloured pixels richer (see [superEnhanced]), so paper and grey print stay neutral; then sharpens
 *   with an unsharp mask 3 pixels across (see [Sharpener]), which needs only the rows either side.
 * - [PageLook.GRAYSCALE] is [PageLook.ENHANCED]'s brightness alone.
 * - [PageLook.BLACK_WHITE] and [PageLook.INK_SAVER] need only a threshold, but one that adapts to
 *   each part of the page (Sauvola's, with the mean and spread of a window around each pixel kept as
 *   running sums): it finds faint print beside dark, and lets paper the division left a little grey
 *   (at a shadow's edge) stay white. Pixels darker than [INK] are always black, so the inside of a
 *   bold letter is never left white. Ink saver then leaves large dark areas hollow (see [hollow]).
 *
 * Brightness and contrast ([LookAdjustments]) come last. On a colour or grey look they are a tone
 * curve on each channel, a lookup table that keeps white white (see [tones]). A black-and-white look
 * has no levels left to bend, and a curve before its threshold would hardly change it, as the
 * threshold follows the paper round each pixel: they move the threshold instead (see [threshold]).
 * With neither set, every look is exactly as it was without them.
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

    /** [PageLook.LIGHTEN]: pixels this bright on paper made white count as the paper itself, which evens it out. */
    const val LIGHT_WHITE = 245f

    /** [PageLook.LIGHTEN]: the level the page's brightest paper ends up at, unless it is brighter already. */
    const val LIGHT_PAPER = 250f

    /** [PageLook.LIGHTEN]: how much of the shadows and the light's fall-off is kept, as a power of the paper's brightness (0 none, 1 all). */
    const val LIGHT_SHADE = 0.4f

    /** [PageLook.LIGHTEN]: how much of the light's colour is kept, likewise. */
    const val LIGHT_TINT = 0.6f

    /** [PageLook.SUPER_ENHANCED]: where the paper ends up, sooner white than [WHITE]. */
    const val SUPER_WHITE = 228f

    /** [PageLook.SUPER_ENHANCED]: a steeper curve than [GAMMA], for darker print. */
    const val SUPER_GAMMA = 2.4

    /** [PageLook.SUPER_ENHANCED]: colour is made richer by up to this much more of it (see [richer]). */
    const val RICHER = 0.4f

    /** [PageLook.SUPER_ENHANCED]: a pixel whose channels differ by this or less is grey, and kept so. */
    const val GREY_CHROMA = 32

    /** [PageLook.SUPER_ENHANCED]: from this difference between its channels a pixel is made fully [RICHER]. */
    const val FULL_CHROMA = 96

    /**
     * [PageLook.SUPER_ENHANCED]: a colour this bright or brighter on paper made white is pale (a stain,
     * the paper's own tint, a highlighter): its colour isn't stretched by the curve or made richer.
     */
    const val PALE = 200

    /** [PageLook.SUPER_ENHANCED]: a colour this much darker than [PALE] gets the curve's colour and is made fully [RICHER]. */
    const val PALE_FADE = 40

    /** [PageLook.SUPER_ENHANCED]: how strongly the unsharp mask adds back each pixel's difference from its neighbours. */
    const val SHARPEN = 1.0f

    /** [PageLook.SUPER_ENHANCED]: differences from the neighbours this small are left alone, so noise isn't sharpened. */
    const val SHARPEN_FLOOR = 4

    /** A black-and-white pixel this dark (on paper made white) is always black. */
    const val INK = 100

    /** Sauvola's k and R: how far below a window's mean the threshold sits where it is flat. */
    const val SAUVOLA_K = 0.2
    const val SAUVOLA_R = 128.0

    /** The threshold's window is about this many times smaller than the picture's long side. */
    const val WINDOW = 40

    /** Black and white at brightness ±[LookAdjustments.MAX]: how far below its window's mean the threshold sits is multiplied or divided by this. */
    const val DARKNESS_SCALE = 1.6

    /** Black and white at brightness or contrast ±[LookAdjustments.MAX]: [INK] is divided or multiplied by this. */
    const val INK_SCALE = 1.4

    /** Black and white with contrast: windows' spreads are stretched away from this one, between a faint line's alone on paper and faded print's. */
    const val SPREAD_PIVOT = 32.0

    /** Black and white at contrast ±[LookAdjustments.MAX]: how many times the spreads are stretched, or squeezed. */
    const val SPREAD_STRETCH = 2.0

    /** Black and white adjusted: a threshold is never raised above this part of its window's mean. */
    const val HIGHEST_THRESHOLD = 0.92

    /**
     * [PageLook.INK_SAVER]: a dark area is left hollow where a square this part of the picture's long
     * side fits in it, much wider than the strokes of bold print.
     */
    const val HOLLOW = 0.015

    /** [PageLook.INK_SAVER]: the outline a hollow area keeps, as a part of the picture's long side (a few pixels). */
    const val OUTLINE = 0.002

    /** Brightness at ±[LookAdjustments.MAX]: the power of its curve. */
    const val BRIGHTNESS_POWER = 2.2

    /** Contrast at [LookAdjustments.MAX]: the power of its S-shaped curve, which is also its steepness at mid-grey. */
    const val CONTRAST_POWER = 2.5

    /** Contrast at -[LookAdjustments.MAX]: black is lifted to this (of white). */
    const val FADE = 0.35

    /**
     * [source] with the [PageLook.ENHANCED] look, handed to [target] a row at a time (opaque ARGB;
     * the row is reused). [source] is read twice, and row y the second time before [target] gets row
     * y, so [target] may write the rows back into [source].
     */
    fun enhance(source: PixelSource, target: (y: Int, row: IntArray) -> Unit, checkCancelled: () -> Unit = {}) =
        colour(source, PageLook.ENHANCED, LookAdjustments.NONE, target, checkCancelled)

    /**
     * [source] with a colour [look] ([PageLook.isColour]) and [adjustments], handed to [target] a row
     * at a time as [enhance] hands them, with the same promise: row y is read for the last time
     * before [target] gets it.
     */
    fun colour(
        source: PixelSource,
        look: PageLook,
        adjustments: LookAdjustments,
        target: (y: Int, row: IntArray) -> Unit,
        checkCancelled: () -> Unit = {},
    ) {
        require(look.isColour) { "$look isn't a colour look" }
        val tones = if (adjustments.isNone) LINEAR else tones(adjustments)
        if (look == PageLook.ORIGINAL) {
            val row = IntArray(source.width)
            for (y in 0 until source.height) {
                if (y and 63 == 0) checkCancelled()
                source.readRow(y, row)
                for (x in row.indices) {
                    val argb = row[x]
                    row[x] = argb(tones[flatten(argb ushr 16, argb)], tones[flatten(argb ushr 8, argb)], tones[flatten(argb, argb)])
                }
                target(y, row)
            }
        } else {
            cleaned(source, look, tones, target, checkCancelled)
        }
    }

    /**
     * The colour looks that find the paper: [source] is read once to find it per channel, then again
     * a row at a time, each pixel divided by the paper there and given [look], then [tones].
     */
    private fun cleaned(
        source: PixelSource,
        look: PageLook,
        tones: IntArray,
        target: (y: Int, row: IntArray) -> Unit,
        checkCancelled: () -> Unit,
    ) {
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
        val paper = Paper(red.finish(), green.finish(), blue.finish())
        val lit = if (look == PageLook.LIGHTEN) paper.lit() else null
        val curve = IntArray(256) { tones[CURVE[it]] }
        val sharpener = if (look == PageLook.SUPER_ENHANCED) Sharpener(width, height, tones, target) else null
        // Each row's work is in functions of its own, which a just-in-time compiler takes up sooner
        // than a loop over a whole picture.
        for (y in 0 until height) {
            if (y and 63 == 0) checkCancelled()
            source.readRow(y, row)
            paper.whitened(y, row, r, g, b)
            when {
                lit != null -> lit.lightened(y, r, g, b, tones, row)
                sharpener != null -> superEnhanced(r, g, b, row)
                else -> curved(r, g, b, curve, row)
            }
            if (sharpener != null) sharpener.add(y, row) else target(y, row)
        }
    }

    /** Channels [r], [g] and [b] through [curve], into [row]. */
    private fun curved(r: IntArray, g: IntArray, b: IntArray, curve: IntArray, row: IntArray) {
        for (x in row.indices) row[x] = argb(curve[r[x]], curve[g[x]], curve[b[x]])
    }

    /** [PageLook.SUPER_ENHANCED] before it is sharpened: channels [r], [g] and [b] (see [superEnhanced]), into [row]. */
    private fun superEnhanced(r: IntArray, g: IntArray, b: IntArray, row: IntArray) {
        for (x in row.indices) row[x] = superEnhanced(r[x], g[x], b[x])
    }

    /**
     * A pixel of [PageLook.SUPER_ENHANCED] before it is sharpened, from its channels on paper made
     * white: each along [SUPER_CURVE], then [richer]. The curve's steep middle stretches the small
     * differences between a pale colour's channels, which would turn a faint stain into a vivid
     * blotch, so a colour as bright as [PALE] is given no more colour than [PageLook.ENHANCED] gives
     * it (its channels drawn back towards its brightest, which keeps its hue) and isn't made richer;
     * one [PALE_FADE] darker or more is left to the curve and made fully richer, in between in
     * proportion.
     */
    private fun superEnhanced(r: Int, g: Int, b: Int): Int {
        val pale = ((luma(r, g, b) - (PALE - PALE_FADE)) / PALE_FADE.toFloat()).coerceIn(0f, 1f)
        val cr = SUPER_CURVE[r]
        val cg = SUPER_CURVE[g]
        val cb = SUPER_CURVE[b]
        val high = max(cr, max(cg, cb))
        val chroma = high - min(cr, min(cg, cb))
        // The colour Enhanced gives it: that curve never turns back, so it keeps which channels are brightest and darkest.
        val enhanced = CURVE[max(r, max(g, b))] - CURVE[min(r, min(g, b))]
        if (pale == 0f || chroma <= enhanced) return richer(cr, cg, cb, 1 - pale)
        val k = 1 - pale * (1 - enhanced / chroma.toFloat())
        return richer(
            (high - (high - cr) * k + 0.5f).toInt(),
            (high - (high - cg) * k + 0.5f).toInt(),
            (high - (high - cb) * k + 0.5f).toInt(),
            1 - pale,
        )
    }

    /** The paper under a picture, per channel, read back a row at a time. */
    private class Paper(private val red: PaperMap, private val green: PaperMap, private val blue: PaperMap) {
        private val width = red.width
        private val levelR = FloatArray(width)
        private val levelG = FloatArray(width)
        private val levelB = FloatArray(width)
        private val darkestR = FloatArray(width)
        private val darkestG = FloatArray(width)
        private val darkestB = FloatArray(width)

        /** Row [y] of the picture, [row], divided by the paper, into [r], [g] and [b]: 0 to 255, the paper 255. */
        fun whitened(y: Int, row: IntArray, r: IntArray, g: IntArray, b: IntArray) {
            red.row(y, levelR, darkestR)
            green.row(y, levelG, darkestG)
            blue.row(y, levelB, darkestB)
            for (x in 0 until width) {
                val argb = row[x]
                val vr = flatten(argb ushr 16, argb)
                val vg = flatten(argb ushr 8, argb)
                val vb = flatten(argb, argb)
                // One weight for all three, so the paper beside a shadow doesn't get coloured speckles.
                val t = (paperWeight(vr, levelR[x], darkestR[x]) + paperWeight(vg, levelG[x], darkestG[x]) + paperWeight(vb, levelB[x], darkestB[x])) / 3
                r[x] = normalized(vr, levelR[x], darkestR[x], t)
                g[x] = normalized(vg, levelG[x], darkestG[x], t)
                b[x] = normalized(vb, levelB[x], darkestB[x], t)
            }
        }

        /**
         * The paper [PageLook.LIGHTEN] leaves on each block, per channel, from the paper found there:
         * as bright as that paper to the power [LIGHT_SHADE] (against the page's brightest, which ends
         * up at [LIGHT_PAPER]), and its colour, against its brightness, to the power [LIGHT_TINT]; but
         * never darker than that paper, so a page already about white (a clean scan, a screenshot)
         * stays as it is rather than turning grey.
         */
        fun lit(): Paper {
            val count = red.levels.size
            val brightness = FloatArray(count) { i -> (red.levels[i] * 77 + green.levels[i] * 150 + blue.levels[i] * 29) / 256 }
            val brightest = brightness.sorted()[(count * 9) / 10]
            fun lit(paper: PaperMap) = paper.withLevels(
                FloatArray(count) { i ->
                    val light = (brightness[i] / brightest).pow(LIGHT_SHADE)
                    val tint = (paper.levels[i] / brightness[i]).pow(LIGHT_TINT)
                    min(255f, max(paper.levels[i], LIGHT_PAPER * light * tint))
                },
            )
            return Paper(lit(red), lit(green), lit(blue))
        }

        /**
         * [PageLook.LIGHTEN] on row [y], this being its paper (see [lit]), from the row's channels on
         * paper made white ([r], [g] and [b]), into [row] through [tones]: each made white from
         * [LIGHT_WHITE], then put on this paper.
         */
        fun lightened(y: Int, r: IntArray, g: IntArray, b: IntArray, tones: IntArray, row: IntArray) {
            red.row(y, levelR)
            green.row(y, levelG)
            blue.row(y, levelB)
            for (x in 0 until width) {
                row[x] = argb(tones[lit(r[x], levelR[x])], tones[lit(g[x], levelG[x])], tones[lit(b[x], levelB[x])])
            }
        }

        private fun lit(normalized: Int, paper: Float): Int = (LIGHT_CURVE[normalized] * paper + 0.5f).toInt()
    }

    /**
     * A [PageLook.SUPER_ENHANCED] colour made richer: a pixel whose channels differ by more than
     * [GREY_CHROMA] (so not paper, nor grey or black print with a trace of colour left) has them drawn
     * further from its brightest channel, by up to [RICHER] times [strength] more (0 for a pale
     * colour, a stain or the paper's own tint, see [superEnhanced]), and never so far that one passes
     * 0, which keeps its hue. Deeper, not brighter, so nothing is made paler.
     */
    private fun richer(r: Int, g: Int, b: Int, strength: Float): Int {
        val high = max(r, max(g, b))
        val chroma = high - min(r, min(g, b))
        if (chroma <= GREY_CHROMA || strength <= 0f) return argb(r, g, b)
        val more = min(1f, (chroma - GREY_CHROMA) / (FULL_CHROMA - GREY_CHROMA).toFloat()) * strength
        val k = min(1 + RICHER * more, high / chroma.toFloat())
        return argb(
            (high - (high - r) * k + 0.5f).toInt(),
            (high - (high - g) * k + 0.5f).toInt(),
            (high - (high - b) * k + 0.5f).toInt(),
        )
    }

    /** [source] with the [PageLook.GRAYSCALE] look and [adjustments]. */
    fun grayscale(source: PixelSource, adjustments: LookAdjustments = LookAdjustments.NONE, checkCancelled: () -> Unit = {}): GrayImage {
        val (image, paper) = lumaAndPaper(source, checkCancelled)
        val curve = if (adjustments.isNone) CURVE else tones(adjustments).let { tones -> IntArray(256) { tones[CURVE[it]] } }
        normalize(image, paper, curve, checkCancelled)
        return image
    }

    /** [source] with a black-and-white [look] ([PageLook.isBilevel]) and [adjustments]. */
    fun blackAndWhite(
        source: PixelSource,
        look: PageLook = PageLook.BLACK_WHITE,
        adjustments: LookAdjustments = LookAdjustments.NONE,
        checkCancelled: () -> Unit = {},
    ): BitImage {
        require(look.isBilevel) { "$look isn't a black-and-white look" }
        val bits = thresholded(source, adjustments, checkCancelled)
        if (look == PageLook.INK_SAVER) hollow(bits, checkCancelled)
        return bits
    }

    /** [PageLook.BLACK_WHITE], in a function of its own so that the grey picture it is made from is let go before ink saver's work. */
    private fun thresholded(source: PixelSource, adjustments: LookAdjustments, checkCancelled: () -> Unit): BitImage {
        val (image, paper) = lumaAndPaper(source, checkCancelled)
        normalize(image, paper, LINEAR, checkCancelled)
        return threshold(image, adjustments, checkCancelled)
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
     *
     * [adjustments] change the threshold itself, as a curve on the levels would hardly change it
     * (the window's mean follows the pixel). Brightness scales how far below the mean it sits, by up
     * to [DARKNESS_SCALE] either way, and lowers or raises [INK] by up to [INK_SCALE]: lighter needs a
     * pixel to be further below the paper round it to be black, so faint marks go first, and print,
     * whose window puts its threshold near the mean, only thins. Contrast pushes dark and faint apart:
     * more raises [INK], so dark print is black more surely, and stretches the windows' spreads away
     * from [SPREAD_PIVOT], by up to [SPREAD_STRETCH] times, which counts a window with print in it as
     * busier (its threshold nearer its mean, the print bolder) and a quiet one, a faint mark alone on
     * the paper, as quieter (the mark white unless it is clearly darker); less does the opposite.
     * Neither puts a threshold above [HIGHEST_THRESHOLD] of its mean that wasn't there already, so the
     * paper's grain stays white.
     */
    internal fun threshold(image: GrayImage, adjustments: LookAdjustments = LookAdjustments.NONE, checkCancelled: () -> Unit = {}): BitImage {
        val width = image.width
        val height = image.height
        val pixels = image.pixels
        val plain = adjustments.isNone
        val lighter = adjustments.brightness / LookAdjustments.MAX.toDouble()
        val contrast = adjustments.contrast / LookAdjustments.MAX.toDouble()
        val darkness = SAUVOLA_K * DARKNESS_SCALE.pow(lighter)
        val ink = INK * INK_SCALE.pow(contrast - lighter)
        val steepness = SPREAD_STRETCH.pow(contrast)
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
                    v < ink -> true
                    else -> {
                        val n = ((right - left + 1) * rows).toDouble()
                        val mean = sum / n
                        // At or above the mean, a pixel is white whatever the spread.
                        if (v >= mean) {
                            false
                        } else {
                            val deviation = sqrt(max(0.0, squares / n - mean * mean))
                            val threshold = mean * (1 + SAUVOLA_K * (deviation / SAUVOLA_R - 1))
                            if (plain) {
                                v < threshold
                            } else {
                                val spread = min(SAUVOLA_R, SPREAD_PIVOT + steepness * (deviation - SPREAD_PIVOT))
                                val adjusted = mean * (1 - darkness * (1 - spread / SAUVOLA_R))
                                v < min(adjusted, max(threshold, mean * HIGHEST_THRESHOLD))
                            }
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

    /**
     * [PageLook.INK_SAVER]: [bits] with each large dark area made white inside, but for an outline
     * about [OUTLINE] of the long side wide. An area is large where a square [HOLLOW] of the long side
     * across (2 reach + 1 pixels) is black in it, but for at most 2 reach white pixels (fewer, in
     * proportion, where the picture's edge cuts the square): so a few white specks in a dark photo
     * don't stop it, while a white line across the square, or a letter in a dark band, does. Those
     * squares' centres, grown by the reach less the outline, are made white, but only where no open
     * white is within the outline: white that isn't a centre and fills over a quarter of the pixels
     * within the outline round it, so the paper or a white letter rather than a speck. The second
     * test is what keeps the outline on a slanted or curved edge: there, the white a square may hold
     * lets it stick out over the edge, and a square grown reaches further across the edge than along
     * it, which together would eat the outline, and the whole of a thick marker stroke. A speck in a
     * dark area isn't open white, so it is made white with the area rather than left in a black
     * ring of its own, even near the area's edge, where its square holds the paper beyond and it is
     * no centre. Beyond the picture's edge counts as black, so the dark border a crop leaves keeps
     * only its inner edge, not a frame round the page too.
     *
     * Four passes over the rows, with running counts a column each and a bit a pixel for the
     * centres, for the open white and for where none is within the outline.
     */
    private fun hollow(bits: BitImage, checkCancelled: () -> Unit) {
        val width = bits.width
        val height = bits.height
        val long = max(width, height)
        val reach = max(2, (long * HOLLOW / 2).roundToInt())
        val outline = (long * OUTLINE).roundToInt().coerceIn(1, reach - 1)
        val square = (2 * reach + 1) * (2 * reach + 1)
        val rowBytes = bits.rowBytes
        val data = bits.data
        // The centres of the squares, a bit each, 1 for a centre.
        val centres = ByteArray(rowBytes * height)
        var any = false
        squareCounts(width, height, reach, checkCancelled, { y, x -> !bits.isBlack(x, y) }) { y, x, whites, area ->
            // At most 2 reach white pixels in a whole square, fewer in one the picture's edge cuts.
            if (whites.toLong() * square <= 2L * reach * area) {
                setBit(centres, y * rowBytes, x)
                any = true
            }
        }
        if (!any) return
        // The open white, a bit each: not a centre, and white over a quarter of the pixels within the
        // outline round it, so not a speck.
        val open = ByteArray(rowBytes * height)
        squareCounts(width, height, outline, checkCancelled, { y, x -> !bits.isBlack(x, y) }) { y, x, whites, area ->
            if (whites * 4 > area && !bits.isBlack(x, y) && !isBit(centres, y * rowBytes, x)) setBit(open, y * rowBytes, x)
        }
        // Where no open white is within the outline, a bit each.
        val inner = ByteArray(rowBytes * height)
        squareCounts(width, height, outline, checkCancelled, { y, x -> isBit(open, y * rowBytes, x) }) { y, x, near, _ ->
            if (near == 0) setBit(inner, y * rowBytes, x)
        }
        // The centres grown into squares: across a row, then down the columns through the last row
        // each column was reached in, so a row is made white once the rows below that reach it are seen.
        val grow = reach - outline
        val reached = IntArray(width) { Int.MIN_VALUE / 2 }
        for (seen in 0 until height + grow) {
            if (seen and 63 == 0) checkCancelled()
            if (seen < height) {
                val start = seen * rowBytes
                var x = 0
                while (x < width) {
                    if (!isBit(centres, start, x)) {
                        x++
                        continue
                    }
                    var end = x
                    while (end + 1 < width && isBit(centres, start, end + 1)) end++
                    for (c in max(0, x - grow)..min(width - 1, end + grow)) reached[c] = seen
                    x = end + 1
                }
            }
            val y = seen - grow
            if (y < 0) continue
            val start = y * rowBytes
            for (x in 0 until width) {
                if (reached[x] >= y - grow && isBit(inner, start, x)) setBit(data, start, x)
            }
        }
    }

    /**
     * Calls [each] with every pixel of a [width] x [height] picture (row y, column x), how many of the
     * pixels within [radius] of it (a square) are [counted], and how many pixels that square holds,
     * fewer where the picture's edge cuts it. The counts run a column at a time, so a pixel costs the
     * same whatever the radius.
     */
    private inline fun squareCounts(
        width: Int,
        height: Int,
        radius: Int,
        checkCancelled: () -> Unit,
        counted: (y: Int, x: Int) -> Boolean,
        each: (y: Int, x: Int, count: Int, area: Int) -> Unit,
    ) {
        val columns = IntArray(width)
        var top = 0
        var bottom = -1
        for (y in 0 until height) {
            if (y and 63 == 0) checkCancelled()
            val newBottom = min(height - 1, y + radius)
            val newTop = max(0, y - radius)
            while (bottom < newBottom) {
                bottom++
                for (x in 0 until width) if (counted(bottom, x)) columns[x]++
            }
            while (top < newTop) {
                for (x in 0 until width) if (counted(top, x)) columns[x]--
                top++
            }
            val rows = bottom - top + 1
            var sum = 0
            var left = 0
            var right = -1
            for (x in 0 until width) {
                val newRight = min(width - 1, x + radius)
                val newLeft = max(0, x - radius)
                while (right < newRight) sum += columns[++right]
                while (left < newLeft) sum -= columns[left++]
                each(y, x, sum, (right - left + 1) * rows)
            }
        }
    }

    /** Whether bit [x] of the row starting at byte [start] of [bits] (a bit a pixel, the leftmost in the high bit) is 1. */
    private fun isBit(bits: ByteArray, start: Int, x: Int): Boolean = (bits[start + (x ushr 3)].toInt() shr (7 - (x and 7))) and 1 == 1

    /** Sets bit [x] of the row starting at byte [start] of [bits] to 1. */
    private fun setBit(bits: ByteArray, start: Int, x: Int) {
        bits[start + (x ushr 3)] = (bits[start + (x ushr 3)].toInt() or (0x80 ushr (x and 7))).toByte()
    }

    /**
     * The tone curve of [adjustments] for the colour and grey looks, a level for each of 0 to 255; it
     * never turns back, and keeps white white. Brightness first: up, 1 - (1 - v)^p, down, v^p, p up to
     * [BRIGHTNESS_POWER], which keep black black too, and are never steeper than p, so noise isn't
     * blown up. Then contrast: up, the S-shaped v^p / (v^p + (1 - v)^p), flat at both ends, so
     * near-white paper turns white and near-black ink black, and steeper at mid-grey; down, straight,
     * with black lifted towards [FADE], as faded print: a page is mostly white paper and black ink, so
     * only lifting black makes it look flatter.
     */
    internal fun tones(adjustments: LookAdjustments): IntArray {
        val brightness = adjustments.brightness / LookAdjustments.MAX.toDouble()
        val contrast = adjustments.contrast / LookAdjustments.MAX.toDouble()
        return IntArray(256) { level ->
            var v = level / 255.0
            if (brightness > 0) {
                v = 1 - (1 - v).pow(1 + brightness * (BRIGHTNESS_POWER - 1))
            } else if (brightness < 0) {
                v = v.pow(1 - brightness * (BRIGHTNESS_POWER - 1))
            }
            if (contrast > 0) {
                val p = 1 + contrast * (CONTRAST_POWER - 1)
                val a = v.pow(p)
                v = a / (a + (1 - v).pow(p))
            } else if (contrast < 0) {
                v += -contrast * FADE * (1 - v)
            }
            (v * 255 + 0.5).toInt().coerceIn(0, 255)
        }
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

    /** An opaque colour from its channels, each 0 to 255. */
    internal fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** One 8-bit channel (the low byte of [channel]) composited over white using the alpha of [argb]. */
    private fun flatten(channel: Int, argb: Int): Int {
        val value = channel and 0xFF
        val alpha = argb ushr 24
        if (alpha == 0xFF) return value
        return (value * alpha + 255 * (255 - alpha) + 127) / 255
    }

    /** Paper made white, ink deepened: [WHITE] and up to 255, the rest along a gamma curve. */
    private val CURVE = IntArray(256) { v -> (255 * min(1.0, v / WHITE.toDouble()).pow(GAMMA) + 0.5).toInt() }

    /** As [CURVE], white sooner and steeper. */
    private val SUPER_CURVE = IntArray(256) { v -> (255 * min(1.0, v / SUPER_WHITE.toDouble()).pow(SUPER_GAMMA) + 0.5).toInt() }

    /** [PageLook.LIGHTEN]'s: straight, [LIGHT_WHITE] and up to 1, as a part of the paper to put a pixel on. */
    private val LIGHT_CURVE = FloatArray(256) { v -> min(255, (v * 255f / LIGHT_WHITE + 0.5f).toInt()) / 255f }

    private val LINEAR = IntArray(256) { it }
}

/**
 * [PageLook.SUPER_ENHANCED]'s unsharp mask, on rows handed to it one after another: each pixel's
 * brightness less the mean of the 3 x 3 pixels round it (weighted 1 2 1 each way) is the detail
 * the photo's softness took away; less [DocumentLooks.SHARPEN_FLOOR], so the paper's grain is left
 * alone, it is added back [DocumentLooks.SHARPEN] times to each channel. A row goes to [target]
 * (through [tones]) once the row below it has come, so only three rows are kept, and a row is still
 * read for the last time before [target] gets it.
 */
private class Sharpener(
    private val width: Int,
    private val height: Int,
    private val tones: IntArray,
    private val target: (y: Int, row: IntArray) -> Unit,
) {
    private val rows = Array(3) { IntArray(width) }
    private val lumas = Array(3) { IntArray(width) }
    private val out = IntArray(width)

    /** Row [y] (ARGB) made; rows come in order, from 0. */
    fun add(y: Int, row: IntArray) {
        row.copyInto(rows[y % 3])
        val luma = lumas[y % 3]
        for (x in 0 until width) {
            val c = row[x]
            luma[x] = DocumentLooks.luma(c ushr 16 and 0xFF, c ushr 8 and 0xFF, c and 0xFF)
        }
        if (y > 0) send(y - 1)
        if (y == height - 1) send(y)
    }

    private fun send(y: Int) {
        val above = lumas[(if (y > 0) y - 1 else y) % 3]
        val here = lumas[y % 3]
        val below = lumas[(if (y < height - 1) y + 1 else y) % 3]
        val pixels = rows[y % 3]
        val last = width - 1
        val floor = DocumentLooks.SHARPEN_FLOOR * 16
        for (x in 0..last) {
            val l = if (x > 0) x - 1 else x
            val r = if (x < last) x + 1 else x
            val mean = above[l] + 2 * above[x] + above[r] + 2 * (here[l] + 2 * here[x] + here[r]) + below[l] + 2 * below[x] + below[r]
            // In sixteenths of a level.
            val detail = here[x] * 16 - mean
            val c = pixels[x]
            val add = when {
                detail > floor -> ((detail - floor) * DocumentLooks.SHARPEN / 16 + 0.5f).toInt()
                detail < -floor -> -(((-detail - floor) * DocumentLooks.SHARPEN / 16 + 0.5f).toInt())
                else -> 0
            }
            out[x] = DocumentLooks.argb(
                tones[((c ushr 16 and 0xFF) + add).coerceIn(0, 255)],
                tones[((c ushr 8 and 0xFF) + add).coerceIn(0, 255)],
                tones[((c and 0xFF) + add).coerceIn(0, 255)],
            )
        }
        target(y, out)
    }
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

/**
 * The paper's level across a picture, a value per block ([levels], row after row), read back a row of
 * pixels at a time between the blocks' centres.
 */
internal class PaperMap(
    val width: Int,
    private val height: Int,
    private val block: Int,
    private val columns: Int,
    private val rows: Int,
    val levels: FloatArray,
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

    /** The same blocks with other [levels], one for each of these. */
    fun withLevels(levels: FloatArray): PaperMap {
        require(levels.size == this.levels.size) { "${levels.size} levels for ${this.levels.size} blocks" }
        return PaperMap(width, height, block, columns, rows, levels)
    }

    /** The paper's level at each pixel of row [y], into [out], and the darkest of the four blocks around each pixel into [darkest] if given. */
    fun row(y: Int, out: FloatArray, darkest: FloatArray? = null) {
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
            if (darkest != null) darkest[x] = min(min(levels[r * columns + c], levels[r * columns + c2]), min(levels[r2 * columns + c], levels[r2 * columns + c2]))
        }
    }

    /** The level found for block ([column], [row]), for tests. */
    fun block(column: Int, row: Int): Float = levels[row * columns + column]
}
