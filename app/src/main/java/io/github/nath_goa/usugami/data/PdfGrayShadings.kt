package io.github.nath_goa.usugami.data

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.function.PDFunction
import io.github.nath_goa.usugami.core.GrayMath
import io.github.nath_goa.usugami.core.GraySpace
import io.github.nath_goa.usugami.core.MeshGray
import io.github.nath_goa.usugami.core.PdfSyntax
import io.github.nath_goa.usugami.core.isOutOfSpace
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Black and white for shadings (gradients), changed in place, so every `sh` and shading pattern that
 * draws one sees the change.
 *
 * With a /Function (types 1 to 3 always, the meshes 4 to 7 sometimes) the function is replaced by one
 * that gives gray, /ColorSpace becomes /DeviceGray and /Background its gray; a mesh's data stays (each
 * vertex still carries the one parameter). The gray function is new, never the old function objects
 * changed, since they may be shared:
 *
 * - RGB (whose gray is linear) and an exponential function (type 2): a type 2 between the grays of its
 *   two ends, exact for any exponent.
 * - RGB and a stitching function (type 3): a type 3 with the same bounds and each part converted by
 *   these rules, so hard colour stops stay sharp.
 * - Anything else (sampled or PostScript functions, an array of functions, CMYK, Lab, spot colours,
 *   CalRGB): a sampled function of 1024 grays (64 by 64 for a type 1 shading's two inputs), each the
 *   gray of the old function at that point.
 *
 * A mesh without a function, whose every vertex carries its colour, has each colour turned into one
 * gray in its data by [MeshGray], when its numbers are whole bytes (as the PDF makers found in the wild
 * write them; viewers disagree on the padding of other sizes). Anything that can't be read (such a
 * mesh, a colour space or function that can't be read, a function that throws for some input) is left
 * in colour, whole, and reported: a shading changes only once its gray function, or its new data, is
 * whole. Nothing is drawn as a picture.
 */
internal class GrayShadings(private val doc: PDDocument, private val check: () -> Unit) {

    /** Turns [shading] gray, read in [space] (its /ColorSpace as the content drawing it resolves it). */
    fun convert(shading: COSDictionary, space: GraySpace): Grayed {
        check()
        if (space.isGray || space is GraySpace.None) return Grayed.AS_IT_WAS
        if (space is GraySpace.Unknown || space is GraySpace.Pattern) return Grayed.LEFT
        val type = shading.getInt(COSName.SHADING_TYPE, -1)
        return try {
            val background = (shading.getDictionaryObject(COSName.BACKGROUND) as? COSArray)?.let { array ->
                val values = floats(array) ?: return Grayed.LEFT
                gray(GrayMath.unit(space.gray(if (values.size >= space.components) values else values.copyOf(space.components))))
            }
            val function = shading.getDictionaryObject(COSName.FUNCTION)
            when {
                function != null && type in 1..7 -> {
                    val gray = grayFunction(function, space, if (type == 1) 2 else 1, shading, 0)
                    shading.setItem(COSName.FUNCTION, gray)
                    finish(shading, background)
                    Grayed.CHANGED
                }
                type in 4..7 && shading is COSStream -> mesh(shading, space, type, background)
                else -> Grayed.LEFT
            }
        } catch (e: IOException) {
            if (e.isOutOfSpace()) throw e
            Grayed.LEFT
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // PDFBox's functions throw these on damaged tables and programs, some only for some inputs;
            // a tint transform that fails throws IllegalArgumentException.
            if (e.isOutOfSpace()) throw e
            Grayed.LEFT
        }
    }

    /** /ColorSpace /DeviceGray and the gray /Background: the last of a shading's changes. */
    private fun finish(shading: COSDictionary, background: COSBase?) {
        shading.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY)
        if (background != null) shading.setItem(COSName.BACKGROUND, COSArray().apply { add(background) })
    }

    /**
     * A new function giving the gray of [function] (a function, or an array of one-output functions,
     * one per component of [space]) for [inputs] inputs. Exact for RGB with an exponential or a
     * stitching function, sampled otherwise. Throws when the old function can't be read or evaluated.
     */
    private fun grayFunction(function: COSBase, space: GraySpace, inputs: Int, shading: COSDictionary, depth: Int): COSBase {
        val dict = (if (function is COSObject) function.`object` else function) as? COSDictionary
        if (dict != null && space.linear && inputs == 1 && depth < MAX_DEPTH) {
            when (dict.getInt(COSName.FUNCTION_TYPE, -1)) {
                2 -> exponential(dict, space)?.let { return it }
                3 -> stitching(dict, space, shading, depth)?.let { return it }
            }
        }
        return sampled(function, space, inputs, shading)
    }

    /** An exponential function (type 2) between the grays of its ends: same /Domain and /N; null when its ends don't fit [space]. */
    private fun exponential(dict: COSDictionary, space: GraySpace): COSDictionary? {
        val n = space.components
        val c0 = (dict.getDictionaryObject(COSName.C0) as? COSArray)?.let { floats(it) ?: return null } ?: FloatArray(n)
        val c1 = (dict.getDictionaryObject(COSName.C1) as? COSArray)?.let { floats(it) ?: return null } ?: FloatArray(n) { 1f }
        if (c0.size != n || c1.size != n) return null
        val domain = dict.getDictionaryObject(COSName.DOMAIN) as? COSArray ?: return null
        val exponent = dict.getDictionaryObject(COSName.N) as? COSNumber ?: return null
        return COSDictionary().apply {
            setInt(COSName.FUNCTION_TYPE, 2)
            setItem(COSName.DOMAIN, domain)
            setItem(COSName.N, exponent)
            setItem(COSName.C0, COSArray().apply { add(gray(space.gray(c0))) })
            setItem(COSName.C1, COSArray().apply { add(gray(space.gray(c1))) })
        }
    }

    /** A stitching function (type 3) with the same /Domain, /Bounds and /Encode, each part converted; null when it isn't whole. */
    private fun stitching(dict: COSDictionary, space: GraySpace, shading: COSDictionary, depth: Int): COSDictionary? {
        val parts = dict.getDictionaryObject(COSName.FUNCTIONS) as? COSArray ?: return null
        val domain = dict.getDictionaryObject(COSName.DOMAIN) as? COSArray ?: return null
        val bounds = dict.getDictionaryObject(COSName.BOUNDS) as? COSArray ?: return null
        val encode = dict.getDictionaryObject(COSName.ENCODE) as? COSArray ?: return null
        if (parts.size() == 0) return null
        val grays = COSArray()
        for (i in 0 until parts.size()) {
            val part = parts.getObject(i) ?: return null
            grays.add(grayFunction(part, space, 1, shading, depth + 1))
        }
        return COSDictionary().apply {
            setInt(COSName.FUNCTION_TYPE, 3)
            setItem(COSName.DOMAIN, domain)
            setItem(COSName.FUNCTIONS, grays)
            setItem(COSName.BOUNDS, bounds)
            setItem(COSName.ENCODE, encode)
        }
    }

    /**
     * A sampled function (type 0, a Flate stream) of the gray of [function] at 1024 points over its
     * domain, or 64 by 64 for two inputs (the first varying fastest), each sample
     * `round(255 * gray)`. /Domain is the function's (the first one's, for an array), else the
     * shading's, else 0 to 1.
     */
    private fun sampled(function: COSBase, space: GraySpace, inputs: Int, shading: COSDictionary): COSStream {
        val value = if (function is COSObject) function.`object` else function
        val functions = if (value is COSArray) {
            List(value.size()) { PDFunction.create(value.getObject(it)) }
        } else {
            listOf(PDFunction.create(value))
        }
        if (functions.isEmpty()) throw IOException("A shading with no functions")
        val first = (if (value is COSArray) value.getObject(0) else value) as? COSDictionary
        val domain = domain(first, inputs) ?: domain(shading, inputs) ?: FloatArray(2 * inputs) { if (it % 2 == 0) 0f else 1f }
        val sizes = if (inputs == 2) intArrayOf(TWO_INPUT_SIZE, TWO_INPUT_SIZE) else intArrayOf(ONE_INPUT_SIZE)
        val count = sizes.fold(1) { a, b -> a * b }
        val samples = ByteArray(count)
        val input = FloatArray(inputs)
        val colour = FloatArray(space.components)
        for (index in 0 until count) {
            if (index and 0xFF == 0) check()
            var rest = index
            for (i in 0 until inputs) {
                val at = rest % sizes[i]
                rest /= sizes[i]
                input[i] = domain[2 * i] + at * (domain[2 * i + 1] - domain[2 * i]) / (sizes[i] - 1)
            }
            if (functions.size == 1) {
                val out = functions[0].eval(input)
                for (c in colour.indices) colour[c] = if (c < out.size) out[c] else 0f
            } else {
                for (c in colour.indices) colour[c] = if (c < functions.size) functions[c].eval(input).firstOrNull() ?: 0f else 0f
            }
            samples[index] = GrayMath.level(space.gray(colour)).toByte()
        }
        return doc.document.createCOSStream().apply {
            setInt(COSName.FUNCTION_TYPE, 0)
            setItem(COSName.DOMAIN, numbers(domain))
            setItem(COSName.RANGE, numbers(floatArrayOf(0f, 1f)))
            setInt(COSName.BITS_PER_SAMPLE, 8)
            setItem(COSName.SIZE, COSArray().apply { sizes.forEach { add(COSInteger.get(it.toLong())) } })
            try {
                createOutputStream(COSName.FLATE_DECODE).use { it.write(samples) }
            } catch (t: Throwable) {
                close()
                throw t
            }
        }
    }

    /**
     * A mesh without a function: each vertex's colour turned into one gray of the same bits in new
     * data ([MeshGray]), /Decode's colour part 0 to 1. Left in colour when its numbers aren't whole
     * bytes or its /Decode is short.
     */
    private fun mesh(stream: COSStream, space: GraySpace, type: Int, background: COSBase?): Grayed {
        val components = space.components
        val decode = (stream.getDictionaryObject(COSName.DECODE) as? COSArray)?.let { floats(it) } ?: return Grayed.LEFT
        if (decode.size < 4 + 2 * components) return Grayed.LEFT
        val data = doc.document.createCOSStream()
        var kept = false
        try {
            val whole = stream.createInputStream().use { input ->
                data.createOutputStream(COSName.FLATE_DECODE).use { output ->
                    MeshGray.convert(
                        type, stream.getInt(COSName.BITS_PER_FLAG, -1), stream.getInt(COSName.BITS_PER_COORDINATE, -1),
                        stream.getInt(COSName.BITS_PER_COMPONENT, -1), components, decode.copyOfRange(4, 4 + 2 * components),
                        { space.gray(it) }, input, output, check,
                    )
                }
            }
            if (!whole) return Grayed.LEFT
            replaceData(stream, data, COSName.FLATE_DECODE)
            kept = true
            stream.setItem(COSName.DECODE, numbers(floatArrayOf(decode[0], decode[1], decode[2], decode[3], 0f, 1f)))
            finish(stream, background)
            return Grayed.CHANGED
        } finally {
            if (!kept) data.close()
        }
    }

    /** [dict]'s /Domain when it has two numbers for each of [inputs]. */
    private fun domain(dict: COSDictionary?, inputs: Int): FloatArray? =
        (dict?.getDictionaryObject(COSName.DOMAIN) as? COSArray)?.let { floats(it) }?.takeIf { it.size >= 2 * inputs }?.copyOf(2 * inputs)

    private fun gray(value: Float): COSFloat = COSFloat(PdfSyntax.number(GrayMath.unit(value).toDouble()))

    private fun numbers(values: FloatArray): COSArray = COSArray().apply { values.forEach { add(COSFloat(PdfSyntax.number(it.toDouble()))) } }

    private companion object {
        const val ONE_INPUT_SIZE = 1024
        const val TWO_INPUT_SIZE = 64
        const val MAX_DEPTH = 8
    }
}
