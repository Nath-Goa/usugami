package io.github.nath_goa.usugami.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The colours of a block of text on a picture of its page, for Translate PDF, which paints the
 * block over in its background's colour and writes the translation in its ink's. A picture is ARGB
 * pixels, row after row, [width] × [height]; a rectangle on it is [left], [top] (its first pixels)
 * and [right], [bottom] (just past its last), in pixels, and may reach past the picture. A pixel that
 * isn't opaque counts as it shows on white paper.
 */
object PageColors {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()

    /** The least contrast the ink keeps with its background, as WCAG asks of large text. */
    const val MIN_CONTRAST = 3.0

    /** The ring around a block that tells its background is this share of the block's thickness wide… */
    private const val RING_SHARE = 0.15f

    /** …at least this many pixels… */
    private const val MIN_RING = 2

    /** …and at most this many. */
    private const val MAX_RING = 4

    /** The ring's colours are counted with this many bits of each channel, so paper's noise counts as one colour. */
    private const val BITS = 5

    /** A pixel further than this from the background (in RGB, each channel 0 to 255) is ink. */
    private const val INK_DISTANCE = 96

    /** One in this many of the ink's pixels is averaged, the furthest from the background: the strokes' cores, not their soft edges. */
    private const val INK_PARTS = 4

    /** With fewer ink pixels than this a block has no ink of its own. */
    private const val MIN_INK = 3

    /** A background lighter than this (relative luminance) gets black text when the block has no ink of its own, a darker one white. */
    private const val LIGHT = 0.5

    /**
     * The colour the rectangle sits on: the commonest colour (5 bits a channel) of a ring 2 to 4
     * pixels wide (about 15 % of the rectangle's height or width, whichever is less) just outside
     * it, as far as the picture goes, as the average of the ring's pixels of that colour. White when
     * no pixel of the ring is on the picture.
     */
    fun background(pixels: IntArray, width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int): Int {
        if (width <= 0 || height <= 0) return WHITE
        require(pixels.size.toLong() >= width.toLong() * height) { "Fewer pixels than $width × $height" }
        // Far outside the picture only the ring's width matters, so the sides are brought near it first.
        val x0 = min(left, right).coerceIn(-2 * MAX_RING, width + 2 * MAX_RING)
        val x1 = max(left, right).coerceIn(-2 * MAX_RING, width + 2 * MAX_RING)
        val y0 = min(top, bottom).coerceIn(-2 * MAX_RING, height + 2 * MAX_RING)
        val y1 = max(top, bottom).coerceIn(-2 * MAX_RING, height + 2 * MAX_RING)
        val ring = (RING_SHARE * min(max(left, right).toLong() - min(left, right), max(top, bottom).toLong() - min(top, bottom)))
            .roundToInt().coerceIn(MIN_RING, MAX_RING)
        val counts = IntArray(1 shl (3 * BITS))
        forEachInRing(width, height, x0, y0, x1, y1, ring) { counts[bin(opaque(pixels[it]))]++ }
        var best = -1
        for (bin in counts.indices) if (counts[bin] > 0 && (best < 0 || counts[bin] > counts[best])) best = bin
        if (best < 0) return WHITE
        var r = 0L
        var g = 0L
        var b = 0L
        forEachInRing(width, height, x0, y0, x1, y1, ring) {
            val pixel = opaque(pixels[it])
            if (bin(pixel) == best) {
                r += red(pixel)
                g += green(pixel)
                b += blue(pixel)
            }
        }
        val n = counts[best]
        return rgb(((r + n / 2) / n).toInt(), ((g + n / 2) / n).toInt(), ((b + n / 2) / n).toInt())
    }

    /**
     * The colour of the text in the rectangle on [background]: of the pixels in it further than 96
     * from [background] (in RGB), the average of the furthest quarter. With fewer than 3 such
     * pixels, black on a light background (relative luminance above 0.5) and white on a dark one.
     * Whichever it is, it keeps a contrast of at least 3:1 with [background], or is black or white,
     * whichever contrasts more.
     */
    fun ink(pixels: IntArray, width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int, background: Int): Int {
        val paper = opaque(background)
        val found = if (width <= 0 || height <= 0) null else inkOf(pixels, width, height, left, top, right, bottom, paper)
        val ink = found ?: if (luminance(paper) > LIGHT) BLACK else WHITE
        if (contrastRatio(ink, paper) >= MIN_CONTRAST) return ink
        return if (contrastRatio(BLACK, paper) >= contrastRatio(WHITE, paper)) BLACK else WHITE
    }

    private fun inkOf(pixels: IntArray, width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int, paper: Int): Int? {
        require(pixels.size.toLong() >= width.toLong() * height) { "Fewer pixels than $width × $height" }
        val x0 = min(left, right).coerceIn(0, width)
        val x1 = max(left, right).coerceIn(0, width)
        val y0 = min(top, bottom).coerceIn(0, height)
        val y1 = max(top, bottom).coerceIn(0, height)
        // Pixels by their distance from the paper, whole numbers 0 to 441, to find the furthest quarter.
        val byDistance = IntArray(MAX_DISTANCE + 1)
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val d = distance(opaque(pixels[y * width + x]), paper)
            if (d > INK_DISTANCE * INK_DISTANCE) {
                byDistance[sqrt(d.toDouble()).toInt()]++
                n++
            }
        }
        if (n < MIN_INK) return null
        // A quarter, rounded up.
        val wanted = (n + INK_PARTS - 1) / INK_PARTS
        // The nearest distance taken, and how many pixels at it are taken (the first in the rectangle).
        var threshold = MAX_DISTANCE
        var taken = 0
        while (threshold > 0 && taken + byDistance[threshold] < wanted) taken += byDistance[threshold--]
        var atThreshold = wanted - taken
        var r = 0L
        var g = 0L
        var b = 0L
        for (y in y0 until y1) for (x in x0 until x1) {
            val pixel = opaque(pixels[y * width + x])
            val d = distance(pixel, paper)
            if (d <= INK_DISTANCE * INK_DISTANCE) continue
            val step = sqrt(d.toDouble()).toInt()
            if (step < threshold || step == threshold && atThreshold-- <= 0) continue
            r += red(pixel)
            g += green(pixel)
            b += blue(pixel)
        }
        return rgb(((r + wanted / 2) / wanted).toInt(), ((g + wanted / 2) / wanted).toInt(), ((b + wanted / 2) / wanted).toInt())
    }

    /** The contrast ratio of two colours, 1 to 21, from their relative luminance as WCAG gives it. */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = luminance(opaque(a))
        val lb = luminance(opaque(b))
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** The relative luminance of [color] as WCAG gives it, 0 (black) to 1 (white); its alpha is ignored. */
    fun luminance(color: Int): Double = 0.2126 * linear(red(color)) + 0.7152 * linear(green(color)) + 0.0722 * linear(blue(color))

    private fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** The pixels of the ring [ring] pixels wide around x0..x1 × y0..y1 that are on the picture, by their index. */
    private inline fun forEachInRing(width: Int, height: Int, x0: Int, y0: Int, x1: Int, y1: Int, ring: Int, action: (Int) -> Unit) {
        val outerLeft = max(0, x0 - ring)
        val outerRight = min(width, x1 + ring)
        for (y in max(0, y0 - ring) until min(height, y1 + ring)) {
            val row = y * width
            if (y < y0 || y >= y1) {
                for (x in outerLeft until outerRight) action(row + x)
            } else {
                for (x in outerLeft until min(width, x0)) action(row + x)
                for (x in max(0, x1) until outerRight) action(row + x)
            }
        }
    }

    /** The largest distance two colours have, rounded down: black to white. */
    private const val MAX_DISTANCE = 441

    /** The square of the distance between two colours in RGB. */
    private fun distance(a: Int, b: Int): Int {
        val dr = red(a) - red(b)
        val dg = green(a) - green(b)
        val db = blue(a) - blue(b)
        return dr * dr + dg * dg + db * db
    }

    private fun bin(color: Int): Int = (red(color) shr (8 - BITS) shl (2 * BITS)) or (green(color) shr (8 - BITS) shl BITS) or (blue(color) shr (8 - BITS))

    /** [color] as it shows on white paper. */
    private fun opaque(color: Int): Int {
        val alpha = color ushr 24
        if (alpha == 255) return color
        fun over(channel: Int) = (channel * alpha + 255 * (255 - alpha) + 127) / 255
        return rgb(over(red(color)), over(green(color)), over(blue(color)))
    }

    private fun red(color: Int) = color shr 16 and 0xFF
    private fun green(color: Int) = color shr 8 and 0xFF
    private fun blue(color: Int) = color and 0xFF
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
