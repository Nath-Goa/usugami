package io.github.nath_goa.usugami.core

import kotlin.math.pow

/**
 * How Black and white turns a colour into a shade of gray: 0 is black, 1 is white, and every
 * value read is kept in 0 to 1 first (as viewers keep a colour operand in its range).
 *
 * RGB uses Rec. 601 luma on the values as stored (gamma-encoded), with the 8-bit weights 77, 150
 * and 29 of 256 that [DocumentLooks]' Grayscale look uses. It is the PDF standard's own DeviceRGB to
 * DeviceGray conversion, what the Luminosity blend mode and luminosity soft masks use and what a
 * JPEG's Y channel holds, and it is linear: a gradient between two colours becomes exactly the
 * gradient between their grays, and a soft mask keeps its opacity.
 *
 * Every place a colour is read uses these: operators, palettes, tint alternates, shading functions,
 * mesh vertices, a soft mask's /Matte, /Background, annotation colours and picture samples.
 */
object GrayMath {

    /** The gray of an RGB colour. */
    fun rgb(r: Float, g: Float, b: Float): Float =
        unit((77f * unit(r) + 150f * unit(g) + 29f * unit(b)) / 256f)

    /** [rgb] for 8-bit channels (0 to 255), rounded to the nearest level: white stays 255. */
    fun rgb8(r: Int, g: Int, b: Int): Int = (r * 77 + g * 150 + b * 29 + 128) shr 8

    /**
     * The gray of a CMYK colour: the luma of the usual `R = (1 - c)(1 - k)` conversion. This departs
     * on purpose from the standard's `1 - min(1, 0.3c + 0.59m + 0.11y + k)`: that form clamps, so
     * every dark mix of inks would become pure black and dark text on a dark fill would disappear.
     * This one keeps black ink alone exact (`1 - k`, so K = 1 text is `0 g`), equals [rgb] of
     * `1 - c, 1 - m, 1 - y` when k is 0, and keeps different dark inks apart.
     */
    fun cmyk(c: Float, m: Float, y: Float, k: Float): Float =
        unit((1f - unit(k)) * (1f - (77f * unit(c) + 150f * unit(m) + 29f * unit(y)) / 256f))

    /**
     * The gray of a CIE Lab colour, from its lightness [l] (0 to 100) alone: the sRGB gray of that
     * lightness. The a* and b* axes carry no lightness.
     */
    fun lab(l: Float): Float {
        val lightness = if (l.isNaN()) 0.0 else l.toDouble().coerceIn(0.0, 100.0)
        val luminance = if (lightness > 8.0) ((lightness + 16.0) / 116.0).pow(3.0) else lightness / 903.3
        val gray = if (luminance <= 0.0031308) 12.92 * luminance else 1.055 * luminance.pow(1.0 / 2.4) - 0.055
        return unit(gray.toFloat())
    }

    /**
     * A CalGray value, or one component of CalRGB, brought to the 2.2 gamma of the values the other
     * formulas read: `value ^ (gamma / 2.2)`. A gamma that isn't a positive number counts as 1, the
     * standard's default.
     */
    fun gamma(value: Float, gamma: Float): Float {
        val exponent = if (gamma > 0f && gamma.isFinite()) gamma / 2.2f else 1f / 2.2f
        return unit(unit(value).toDouble().pow(exponent.toDouble()).toFloat())
    }

    /** [value] kept in 0 to 1; NaN (a damaged function's result, say) counts as 0. */
    fun unit(value: Float): Float = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)

    /** A gray as one 8-bit level, rounded to the nearest. */
    fun level(gray: Float): Int = (unit(gray) * 255f + 0.5f).toInt()
}
