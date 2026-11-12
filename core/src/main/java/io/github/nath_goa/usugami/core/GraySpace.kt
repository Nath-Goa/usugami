package io.github.nath_goa.usugami.core

import kotlin.coroutines.cancellation.CancellationException

/**
 * A PDF colour space as Black and white sees it: how many components a colour in it has, and the
 * gray of a colour ([GrayMath]). The app's PDF code reads a colour space from the file into one of
 * these (PDFBox can't read most colour spaces itself); everything about colour lives here, where it
 * is tested on the desktop.
 *
 * ICC profiles are not read (there is no colour engine on the phone): an ICCBased space counts by
 * its number of components as gray, RGB or CMYK, so an unusual profile gives the gray of its
 * numbers as if they were plain sRGB or CMYK.
 */
sealed class GraySpace(val components: Int) {

    /**
     * The gray of a colour in this space. [c] has [components] values, as an operator or a decoded
     * picture sample gives them. Throws [IllegalArgumentException] for a space that can't give one
     * ([None], [Unknown], a [Pattern] without a base) or a colour it can't read (a [Tint] whose
     * transform fails for it): callers leave what uses it as it was.
     */
    abstract fun gray(c: FloatArray): Float

    /** The colour `cs` sets: zeros, but black for DeviceCMYK, full tint for a spot colour, L* 0 for Lab. */
    open val initial: FloatArray get() = FloatArray(components)

    /** A picture's /Decode when it has none: 0 1 for each component (Lab and Indexed differ). */
    open fun defaultDecode(bits: Int): FloatArray = FloatArray(components * 2) { if (it % 2 == 0) 0f else 1f }

    /** The gray of a mix of colours is the same mix of their grays: true for RGB alone. */
    open val linear: Boolean get() = false

    /** Gray already: what is drawn in it is left exactly as it is, operators and all. */
    val isGray: Boolean get() = this is Gray || this is CalGray || (this is Indexed && base.isGray)

    /** DeviceGray, and an ICCBased space of one component. */
    object Gray : GraySpace(1) {
        override fun gray(c: FloatArray): Float = GrayMath.unit(c[0])
    }

    /** DeviceRGB, and an ICCBased space of three components. */
    object Rgb : GraySpace(3) {
        override fun gray(c: FloatArray): Float = GrayMath.rgb(c[0], c[1], c[2])
        override val linear: Boolean get() = true
    }

    /** DeviceCMYK: `cs` sets 0 0 0 1, black. */
    object Cmyk : GraySpace(4) {
        override fun gray(c: FloatArray): Float = GrayMath.cmyk(c[0], c[1], c[2], c[3])
        override val initial: FloatArray get() = floatArrayOf(0f, 0f, 0f, 1f)
    }

    /**
     * An ICCBased space of four components: [Cmyk] in everything but [initial], since the standard
     * starts every component of an ICCBased space at 0 (no ink: white) where DeviceCMYK starts black.
     */
    object IccCmyk : GraySpace(4) {
        override fun gray(c: FloatArray): Float = GrayMath.cmyk(c[0], c[1], c[2], c[3])
    }

    /** CalGray: gray already, but read through its gamma when it is the base or alternate of another space. */
    class CalGray(val gamma: Float) : GraySpace(1) {
        override fun gray(c: FloatArray): Float = GrayMath.gamma(c[0], gamma)
    }

    /** CalRGB: each component through its gamma ([GrayMath.gamma]; 1 where [gamma] has none), then as RGB. */
    class CalRgb(val gamma: FloatArray) : GraySpace(3) {
        override fun gray(c: FloatArray): Float = GrayMath.rgb(
            GrayMath.gamma(c[0], gamma.getOrElse(0) { 1f }),
            GrayMath.gamma(c[1], gamma.getOrElse(1) { 1f }),
            GrayMath.gamma(c[2], gamma.getOrElse(2) { 1f }),
        )
    }

    /** CIE Lab, whose a* and b* run over [aMin] to [aMax] and [bMin] to [bMax] (/Range). */
    class Lab(val aMin: Float, val aMax: Float, val bMin: Float, val bMax: Float) : GraySpace(3) {
        override fun gray(c: FloatArray): Float = GrayMath.lab(c[0])
        override val initial: FloatArray get() = floatArrayOf(0f, inRange(0f, aMin, aMax), inRange(0f, bMin, bMax))
        override fun defaultDecode(bits: Int): FloatArray = floatArrayOf(0f, 100f, aMin, aMax, bMin, bMax)

        private fun inRange(value: Float, min: Float, max: Float): Float = if (min <= max) value.coerceIn(min, max) else value
    }

    /**
     * A palette: [grays] holds the gray of each entry (see [of]). The colour is an entry's number,
     * kept within the palette. Over a gray base it is a palette of grays, so [isGray].
     */
    class Indexed(val base: GraySpace, val grays: FloatArray) : GraySpace(1) {
        override fun gray(c: FloatArray): Float {
            if (grays.isEmpty()) return 0f
            val index = c[0].let { if (it.isNaN()) 0 else Math.round(it).coerceIn(0, grays.size - 1) }
            return grays[index]
        }

        override fun defaultDecode(bits: Int): FloatArray = floatArrayOf(0f, ((1 shl bits) - 1).toFloat())

        companion object {
            /**
             * The palette of an `[/Indexed base hival lookup]` space: entry i is the [base]'s
             * `base.components` bytes from `i * base.components` in [lookup], each mapped onto the
             * base's [defaultDecode] range for 8 bits. A table shorter than `hival + 1` entries reads
             * 0 for the bytes it lacks; [hival] is kept in 0 to 255. Throws [IllegalArgumentException]
             * (from [gray]) for a base with no gray, or one whose tint transform fails for an entry:
             * such a palette can't be read.
             */
            fun of(base: GraySpace, hival: Int, lookup: ByteArray): Indexed {
                val n = base.components
                val decode = base.defaultDecode(8)
                val colour = FloatArray(n)
                val grays = FloatArray(hival.coerceIn(0, 255) + 1) { entry ->
                    for (i in 0 until n) {
                        val at = entry * n + i
                        val byte = if (at < lookup.size) lookup[at].toInt() and 0xFF else 0
                        colour[i] = decode[2 * i] + byte * (decode[2 * i + 1] - decode[2 * i]) / 255f
                    }
                    base.gray(colour)
                }
                return Indexed(base, grays)
            }
        }
    }

    /**
     * Separation or DeviceN: [transform] (the tint transform) gives the [alternate] space's colour,
     * whose gray is taken. `cs` sets full tint. A transform that gives fewer values than the
     * alternate takes reads 0 for the rest. A transform that throws (PDFBox's functions throw
     * runtime exceptions on damaged tables and programs, sometimes only for some colours) makes
     * [gray] throw [IllegalArgumentException] with it as the cause; a cancellation goes through
     * as it is.
     */
    class Tint(components: Int, val alternate: GraySpace, val transform: (FloatArray) -> FloatArray) : GraySpace(components) {
        override fun gray(c: FloatArray): Float {
            val out = try {
                transform(c)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                throw IllegalArgumentException("The tint transform failed", e)
            }
            val n = alternate.components
            return alternate.gray(if (out.size >= n) out else out.copyOf(n))
        }

        override val initial: FloatArray get() = FloatArray(components) { 1f }
    }

    /** Separation /None, or DeviceN of /None alone: paints nothing, so it is left alone. */
    class None(components: Int) : GraySpace(components) {
        override fun gray(c: FloatArray): Float = throw IllegalArgumentException("A /None colour paints nothing")
    }

    /** A pattern space; [base] is the space of an uncoloured pattern's colour, null for coloured patterns. */
    class Pattern(val base: GraySpace?) : GraySpace(base?.components ?: 0) {
        override fun gray(c: FloatArray): Float =
            base?.gray(c) ?: throw IllegalArgumentException("A coloured pattern has no colour of its own")
    }

    /** A space that can't be read (or, with no components, one that isn't there): left, and reported. */
    class Unknown(components: Int) : GraySpace(components) {
        override fun gray(c: FloatArray): Float = throw IllegalArgumentException("This colour space can't be read")
    }
}
