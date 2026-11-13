package io.github.nath_goa.usugami.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import io.github.nath_goa.usugami.core.GrayImage
import io.github.nath_goa.usugami.core.GrayJpeg
import io.github.nath_goa.usugami.core.GrayMath
import io.github.nath_goa.usugami.core.GrayPixels
import io.github.nath_goa.usugami.core.GraySpace
import io.github.nath_goa.usugami.core.PdfSyntax
import io.github.nath_goa.usugami.core.PixelSource
import io.github.nath_goa.usugami.core.RasterEncoder
import io.github.nath_goa.usugami.core.isOutOfSpace
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/** What Black and white made of a picture or a shading. */
internal enum class Grayed {
    /** Nothing to do: gray already, a stencil, or a colour that paints nothing. */
    AS_IT_WAS,

    /** Turned gray. */
    CHANGED,

    /** Left in colour, as it was: its pages are reported. */
    LEFT,
}

/**
 * Black and white's second pass over pictures (image XObjects): every picture still in colour after
 * Compress's own pass is turned gray in place, its stream's data and dictionary changed, so every
 * reference to it, its /SMask and its /Mask stay attached. The first route that fits is taken:
 *
 * - A: a stencil (/ImageMask), or a colour space that is gray already or paints nothing: as it is.
 * - B: a palette over colours (any filter, the data unread): a palette of grays.
 * - C: one component of a spot colour, at most 8 bits, not a JPEG: a palette of grays with an entry
 *   for every possible sample, /Decode worked in; the data unread.
 * - D: lossless filters only: a row at a time through [GrayPixels] to 8-bit DeviceGray, deflated
 *   with the PNG predictors as [RasterEncoder] writes them, the same size; a colour key becomes a
 *   one-bit stencil /Mask.
 * - E: an RGB JPEG (or one in a spot colour of one component): decoded by Android's BitmapFactory,
 *   sampled down while decoding, to a one-component gray JPEG ([GrayJpeg]).
 * - Anything else (a four-component JPEG, JPEG 2000 in colour, a filter nothing here reads, a colour
 *   space that can't be read) is left as it is, and reported.
 *
 * A plain picture coming out of route E is sized and compressed as the Compress level says, as Compress's
 * own pass would; one with a mask or soft mask keeps its size (Compress alone never touches those)
 * at quality 85, halved while it has more than 8 million pixels unless its soft mask has /Matte
 * (they must then match). A soft mask's /Matte, a colour in the picture's space, becomes its gray.
 *
 * Memory: route D holds one row; route E a bitmap of at most 8 million pixels (one whose soft mask
 * has /Matte is tried whole), the gray picture (a byte a pixel) and the JPEG encoder's coefficients
 * (two bytes a pixel). What fails (a damaged stream, a picture too big for memory, a tint transform
 * that throws) leaves the picture as it was, reported; storage running full and cancelling go up as
 * they are.
 */
internal class GrayPictures(
    private val doc: PDDocument,
    private val maxDimension: Int,
    private val level: PdfCompression,
    private val check: () -> Unit,
) {

    /** Turns [image] gray, read in [space] (its /ColorSpace as the content drawing it resolves it). */
    fun convert(image: COSStream, space: GraySpace): Grayed {
        check()
        if (image.getBoolean(COSName.IMAGE_MASK, false)) return Grayed.AS_IT_WAS
        if (space.isGray || space is GraySpace.None) return Grayed.AS_IT_WAS
        if (space is GraySpace.Unknown || space is GraySpace.Pattern) return Grayed.LEFT
        if (space is GraySpace.Indexed) {
            image.setItem(COSName.COLORSPACE, grayIndexed(space))
            return Grayed.CHANGED
        }
        val filters = filterNames(image.getDictionaryObject(COSName.FILTER)) ?: return Grayed.LEFT
        val last = filters.lastOrNull()
        val bits = image.getInt(COSName.BITS_PER_COMPONENT, -1)
        val mask = image.getDictionaryObject(COSName.SMASK) as? COSStream
        val matteColor = (mask?.getDictionaryObject(COSName.MATTE) as? COSArray)?.let { floats(it) ?: return Grayed.LEFT }
        if (space is GraySpace.Tint && space.components == 1 && bits in TABLE_BITS && last !in JPEG_FILTERS && matteColor == null) {
            return table(image, space, bits)
        }
        val width = image.getInt(COSName.WIDTH, -1)
        val height = image.getInt(COSName.HEIGHT, -1)
        if (width <= 0 || height <= 0) return Grayed.LEFT
        return try {
            // A soft mask's /Matte is a colour in the picture's space: its gray, worked out before anything changes.
            val matte = matteColor?.let { GrayMath.unit(space.gray(if (it.size >= space.components) it else it.copyOf(space.components))) }
            when {
                filters.all { it in LOSSLESS } && bits in ROW_BITS -> rows(image, space, bits, width, height, matte)
                last in DCT && bits == 8 && (space is GraySpace.Rgb || space is GraySpace.CalRgb || (space is GraySpace.Tint && space.components == 1)) &&
                    !colorTransform(image, filters.size) -> jpeg(image, space, width, height, matte)
                // JPEG 2000, four-component JPEGs and the rest: Android's renderer is the next step's.
                else -> Grayed.LEFT
            }
        } catch (e: IOException) {
            if (e.isOutOfSpace()) throw e
            Grayed.LEFT
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // IllegalArgumentException from GrayPixels (bits or numbers it can't read, a tint transform
            // that throws), and what PDFBox's filters throw on damaged data.
            if (e.isOutOfSpace()) throw e
            Grayed.LEFT
        } catch (_: OutOfMemoryError) {
            // A picture too big for memory: its buffers are already unreachable.
            Grayed.LEFT
        }
    }

    /**
     * Route C: a palette of grays with an entry for each possible sample, the picture's /Decode worked
     * in, so its data (and a colour key, in sample values) stays as it is.
     */
    private fun table(image: COSStream, space: GraySpace, bits: Int): Grayed {
        val max = (1 shl bits) - 1
        val decode = decodeOf(image, 1) ?: space.defaultDecode(bits)
        val levels = try {
            ByteArray(max + 1) { s -> GrayMath.level(space.gray(floatArrayOf(decode[0] + s * (decode[1] - decode[0]) / max))).toByte() }
        } catch (_: IllegalArgumentException) {
            return Grayed.LEFT
        }
        image.setItem(COSName.COLORSPACE, COSArray().apply {
            add(COSName.INDEXED)
            add(COSName.DEVICEGRAY)
            add(COSInteger.get(max.toLong()))
            add(COSString(levels))
        })
        image.removeItem(COSName.DECODE)
        return Grayed.CHANGED
    }

    /**
     * Route D: the samples a row at a time, through [GrayPixels], to 8-bit gray rows deflated with the
     * PNG predictors; a colour key's masked pixels go, as the rows are read, into a one-bit stencil
     * that takes the key's place as /Mask. Nothing of the picture changes until both are whole.
     */
    private fun rows(image: COSStream, space: GraySpace, bits: Int, width: Int, height: Int, matte: Float?): Grayed {
        val key = (image.getDictionaryObject(COSName.MASK) as? COSArray)?.let { array -> floats(array)?.let { f -> IntArray(f.size) { f[it].roundToInt() } } ?: return Grayed.LEFT }
        val pixels = GrayPixels(space, bits, decodeOf(image, space.components), key)
        val packed = ByteArray(pixels.rowBytes(width))
        val gray = ByteArray(width)
        val maskBytes = (width + 7) / 8
        val masked = if (key != null) ByteArray(maskBytes) else null
        val data = doc.document.createCOSStream()
        val stencil = if (key != null) doc.document.createCOSStream() else null
        var kept = false
        try {
            image.createInputStream().use { input ->
                (stencil?.createOutputStream(COSName.FLATE_DECODE)).use { stencilOut ->
                    val source = object : PixelSource {
                        override val width get() = width
                        override val height get() = height
                        override fun readRow(y: Int, out: IntArray) {
                            // Rows the data lacks are read as zeros.
                            val got = readFully(input, packed)
                            if (got < packed.size) packed.fill(0, got, packed.size)
                            pixels.row(packed, width, gray, masked)
                            for (x in 0 until width) out[x] = OPAQUE or ((gray[x].toInt() and 0xFF) * 0x010101)
                            if (masked != null) stencilOut?.write(masked, 0, maskBytes)
                        }
                    }
                    data.createRawOutputStream().use { RasterEncoder.writeFlate(source, gray = true, it, check) }
                }
            }
            replaceData(image, data, COSName.FLATE_DECODE)
            kept = true
            image.setItem(COSName.DECODE_PARMS, COSDictionary().apply {
                setInt(COSName.PREDICTOR, 15)
                setInt(COSName.COLORS, 1)
                setInt(COSName.BITS_PER_COMPONENT, 8)
                setInt(COSName.COLUMNS, width)
            })
            stored(image, width, height, matte)
            if (stencil != null) {
                stencil.setItem(COSName.TYPE, COSName.XOBJECT)
                stencil.setItem(COSName.SUBTYPE, COSName.IMAGE)
                stencil.setInt(COSName.WIDTH, width)
                stencil.setInt(COSName.HEIGHT, height)
                stencil.setInt(COSName.BITS_PER_COMPONENT, 1)
                stencil.setBoolean(COSName.IMAGE_MASK, true)
                image.setItem(COSName.MASK, stencil)
            }
            return Grayed.CHANGED
        } finally {
            if (!kept) {
                data.close()
                stencil?.close()
            }
        }
    }

    /**
     * Route E: an RGB JPEG (or one in a spot colour of one component) decoded by BitmapFactory, never
     * more than 8 million pixels, /Decode and a CalRGB gamma applied per channel, to a gray JPEG.
     */
    private fun jpeg(image: COSStream, space: GraySpace, width: Int, height: Int, matte: Float?): Grayed {
        // A colour key needs the exact samples, which a JPEG doesn't keep.
        if (image.getDictionaryObject(COSName.MASK) is COSArray) return Grayed.LEFT
        val plain = image.getDictionaryObject(COSName.SMASK) == null && image.getDictionaryObject(COSName.MASK) == null
        var targetWidth = width
        var targetHeight = height
        if (plain) {
            // The level's size, but never more than the 8 million pixels the decoded bitmap may have:
            // scaling that up again would only cost memory.
            val fit = minOf(1.0, maxDimension.toDouble() / maxOf(width, height), Math.sqrt(MAX_PIXELS.toDouble() / (width.toDouble() * height)))
            targetWidth = maxOf(1, (width * fit).toInt())
            targetHeight = maxOf(1, (height * fit).toInt())
        } else if (matte == null) {
            // A mask needn't match its picture's size; a soft mask with /Matte must, so that one is tried whole.
            while (targetWidth.toLong() * targetHeight > MAX_PIXELS && targetWidth > 1 && targetHeight > 1) {
                targetWidth = maxOf(1, targetWidth / 2)
                targetHeight = maxOf(1, targetHeight / 2)
            }
        }
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2
        if (matte == null) while ((width / sample).toLong() * (height / sample) > MAX_PIXELS) sample *= 2
        var bitmap: Bitmap = image.createInputStream().use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return Grayed.LEFT
        if (plain && (bitmap.width < targetWidth || bitmap.height < targetHeight)) {
            // The 8-million-pixel cap sampled it below the level's size: a plain picture is never
            // enlarged, so it keeps the size it was decoded at, which holds all the detail there is.
            targetWidth = bitmap.width
            targetHeight = bitmap.height
        }
        val pixels = try {
            if (bitmap.width != targetWidth || bitmap.height != targetHeight) {
                val sized = bitmap.scale(targetWidth, targetHeight)
                if (sized !== bitmap) bitmap.recycle()
                bitmap = sized
            }
            check()
            grayOf(bitmap, space, decodeOf(image, space.components))
        } finally {
            bitmap.recycle()
        }
        val quality = if (plain) (level.quality * 100).roundToInt().coerceIn(1, 100) else MASKED_QUALITY
        val bytes = GrayJpeg.encode(pixels, quality, check)
        val data = doc.document.createCOSStream()
        try {
            data.createRawOutputStream().use { it.write(bytes) }
        } catch (t: Throwable) {
            data.close()
            throw t
        }
        replaceData(image, data, COSName.DCT_DECODE)
        stored(image, pixels.width, pixels.height, matte)
        return Grayed.CHANGED
    }

    /**
     * The gray of each pixel of [bitmap]: each channel through [decode] and, for CalRGB, its gamma (a
     * table of 256 per channel), then [GrayMath.rgb8]; for a spot colour of one component, the sample
     * (the JPEG's own gray, or the luma of its three channels) through the tint's table.
     */
    private fun grayOf(bitmap: Bitmap, space: GraySpace, decode: FloatArray?): GrayImage {
        val width = bitmap.width
        val height = bitmap.height
        val image = GrayImage(width, height)
        val row = IntArray(width)
        val ranges = decode ?: space.defaultDecode(8)
        fun decoded(channel: Int, s: Int): Float = ranges[2 * channel] + s * (ranges[2 * channel + 1] - ranges[2 * channel]) / 255f
        if (space.components == 1) {
            val table = IntArray(256) { s -> GrayMath.level(space.gray(floatArrayOf(decoded(0, s)))) }
            for (y in 0 until height) {
                if (y and 63 == 0) check()
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    val p = row[x]
                    image.pixels[y * width + x] = table[GrayMath.rgb8((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)].toByte()
                }
            }
            return image
        }
        val gamma = (space as? GraySpace.CalRgb)?.gamma
        val tables = Array(3) { c ->
            IntArray(256) { s ->
                val value = decoded(c, s)
                GrayMath.level(if (gamma != null) GrayMath.gamma(value, gamma.getOrElse(c) { 1f }) else value)
            }
        }
        val (red, green, blue) = tables
        for (y in 0 until height) {
            if (y and 63 == 0) check()
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val p = row[x]
                image.pixels[y * width + x] = GrayMath.rgb8(red[(p shr 16) and 0xFF], green[(p shr 8) and 0xFF], blue[p and 0xFF]).toByte()
            }
        }
        return image
    }

    /**
     * What every route that stores new data sets: its size, 8 bits, DeviceGray, no /Decode and no
     * /Alternates (pictures in colour); a soft mask's /Matte becomes its gray. /SMask, a /Mask
     * stream, /Interpolate, /OC, /StructParent and /Metadata stay.
     */
    private fun stored(image: COSStream, width: Int, height: Int, matte: Float?) {
        image.setInt(COSName.WIDTH, width)
        image.setInt(COSName.HEIGHT, height)
        image.setInt(COSName.BITS_PER_COMPONENT, 8)
        image.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY)
        image.removeItem(COSName.DECODE)
        image.removeItem(ALTERNATES)
        if (matte != null) {
            (image.getDictionaryObject(COSName.SMASK) as? COSStream)?.setItem(
                COSName.MATTE,
                COSArray().apply { add(COSFloat(PdfSyntax.number(matte.toDouble()))) },
            )
        }
    }

    /** /DecodeParms of the last filter says /ColorTransform: the JPEG's colours are stored otherwise than BitmapFactory assumes. */
    private fun colorTransform(image: COSStream, filterCount: Int): Boolean {
        val parms = when (val value = image.getDictionaryObject(COSName.DECODE_PARMS)) {
            is COSDictionary -> value
            is COSArray -> if (filterCount > 0 && value.size() >= filterCount) value.getObject(filterCount - 1) as? COSDictionary else null
            else -> null
        }
        return parms?.containsKey(COLOR_TRANSFORM) == true
    }

    /** [image]'s /Decode when it has two numbers for each of [components]; null to use the space's own. */
    private fun decodeOf(image: COSStream, components: Int): FloatArray? =
        (image.getDictionaryObject(COSName.DECODE) as? COSArray)?.let { floats(it) }?.takeIf { it.size >= 2 * components }

    private companion object {
        const val OPAQUE = 0xFF shl 24
        const val MAX_PIXELS = 8_000_000L
        const val MASKED_QUALITY = 85
        val TABLE_BITS = setOf(1, 2, 4, 8)
        val ROW_BITS = setOf(1, 2, 4, 8, 16)
        val ALTERNATES: COSName = COSName.getPDFName("Alternates")
        val COLOR_TRANSFORM: COSName = COSName.getPDFName("ColorTransform")
        val DCT = setOf("DCTDecode", "DCT")
        val JPEG_FILTERS = setOf("DCTDecode", "DCT", "JPXDecode")
        val LOSSLESS = setOf(
            "FlateDecode", "Fl", "LZWDecode", "LZW", "RunLengthDecode", "RL", "ASCIIHexDecode", "AHx", "ASCII85Decode", "A85",
        )
    }
}
