package io.github.nath_goa.usugami.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import io.github.nath_goa.usugami.core.GrayImage
import io.github.nath_goa.usugami.core.GrayJpeg
import io.github.nath_goa.usugami.core.GrayMath
import io.github.nath_goa.usugami.core.GrayPixels
import io.github.nath_goa.usugami.core.GraySpace
import io.github.nath_goa.usugami.core.PdfSyntax
import io.github.nath_goa.usugami.core.PixelSource
import io.github.nath_goa.usugami.core.RasterEncoder
import io.github.nath_goa.usugami.core.isOutOfSpace
import java.io.File
import java.io.IOException
import java.util.BitSet
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
 * - F: any other JPEG (four components, Lab, a spot colour of several, /ColorTransform) and JPEG
 *   2000 in colour or without /ColorSpace, which neither PDFBox nor BitmapFactory reads as the PDF
 *   means it: drawn alone by Android's PDF renderer ([rendered]), to a gray JPEG. A JPEG 2000
 *   whose transparency is in its data (/SMaskInData) is left.
 * - Anything else (a filter nothing here reads, a colour space that can't be read) is left as it is,
 *   and reported.
 *
 * A plain picture coming out of route E or F is sized and compressed as the Compress level says, as
 * Compress's own pass would; one with a mask or soft mask keeps its size (Compress alone never
 * touches those) at quality 85, halved while it has more than 8 million pixels unless its soft mask
 * has /Matte (they must then match). A soft mask's /Matte, a colour in the picture's space, becomes
 * its gray.
 *
 * Memory: route D holds one row; route E a bitmap of at most 8 million pixels (one whose soft mask
 * has /Matte is tried whole), route F one band of at most a million; then the gray picture (a byte
 * a pixel) and the JPEG encoder's coefficients (two bytes a pixel). What fails (a damaged stream, a
 * picture too big for memory, a tint transform that throws, a picture the renderer can't draw)
 * leaves the picture as it was, reported; storage running full and cancelling go up as they are.
 * Route F's temporary PDF is written in [scratchDir], the engine's scratch folder.
 */
internal class GrayPictures(
    private val doc: PDDocument,
    private val maxDimension: Int,
    private val level: PdfCompression,
    private val scratchDir: File,
    private val check: () -> Unit,
) {

    /** Turns [image] gray, read in [space] (its /ColorSpace as the content drawing it resolves it). */
    fun convert(image: COSStream, space: GraySpace): Grayed {
        check()
        if (image.getBoolean(COSName.IMAGE_MASK, false)) return Grayed.AS_IT_WAS
        if (space.isGray || space is GraySpace.None) return Grayed.AS_IT_WAS
        val names = filterNames(image.getDictionaryObject(COSName.FILTER))
        // A JPEG 2000 without /ColorSpace has its colours in its own data, which Android's renderer
        // reads (route F); one whose /ColorSpace can't be read is left, as other pictures are.
        val bare = names?.lastOrNull() in JPX && image.getDictionaryObject(COSName.COLORSPACE) == null
        if ((space is GraySpace.Unknown && !bare) || space is GraySpace.Pattern) return Grayed.LEFT
        if (space is GraySpace.Indexed) {
            image.setItem(COSName.COLORSPACE, grayIndexed(space))
            return Grayed.CHANGED
        }
        val filters = names ?: return Grayed.LEFT
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
                // A JPEG 2000 whose transparency is in its data: a renderer that ignores /SMaskInData
                // draws it opaque, so route F would pass it and lose the transparency.
                last in JPX && image.getInt(SMASK_IN_DATA, 0) != 0 -> Grayed.LEFT
                last in DCT || last in JPX -> rendered(image, width, height, matte, bare)
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
        val plain = isPlain(image)
        var (targetWidth, targetHeight) = storedSize(width, height, plain, matte != null)
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
        storeJpeg(image, pixels, plain, matte)
        return Grayed.CHANGED
    }

    /** No /SMask and no /Mask: the Compress level decides its size and quality. */
    private fun isPlain(image: COSStream): Boolean =
        image.getDictionaryObject(COSName.SMASK) == null && image.getDictionaryObject(COSName.MASK) == null

    /**
     * The size routes E and F store a [width] by [height] picture at: a [plain] one the level's
     * size, never more than the 8 million pixels route E's decoded bitmap may have (scaling that up
     * again would only cost memory) and never enlarged; one with a mask its own, halved while it has
     * more than 8 million pixels, unless its soft mask has /Matte ([matte]): a mask needn't match its
     * picture's size, a soft mask with /Matte must, so that one is tried whole.
     */
    private fun storedSize(width: Int, height: Int, plain: Boolean, matte: Boolean): Pair<Int, Int> {
        if (plain) {
            val fit = minOf(1.0, maxDimension.toDouble() / maxOf(width, height), Math.sqrt(MAX_PIXELS.toDouble() / (width.toDouble() * height)))
            return maxOf(1, (width * fit).toInt()) to maxOf(1, (height * fit).toInt())
        }
        var targetWidth = width
        var targetHeight = height
        if (!matte) {
            while (targetWidth.toLong() * targetHeight > MAX_PIXELS && targetWidth > 1 && targetHeight > 1) {
                targetWidth = maxOf(1, targetWidth / 2)
                targetHeight = maxOf(1, targetHeight / 2)
            }
        }
        return targetWidth to targetHeight
    }

    /** [pixels] as a gray JPEG in place of [image]'s data, at the level's quality when it is [plain], else 85. */
    private fun storeJpeg(image: COSStream, pixels: GrayImage, plain: Boolean, matte: Float?) {
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
    }

    /**
     * Route F: a picture neither PDFBox nor BitmapFactory reads as the PDF means it (JPEG 2000, a
     * four-component JPEG and the like) drawn alone by Android's PDF renderer, which reads it as the
     * phone's viewers show it, at the size [storedSize] gives, to a gray JPEG; [bare], a JPEG 2000
     * without /ColorSpace whose every pixel comes back gray, is gray already and stays as it is.
     *
     * The renderer draws nothing, and says nothing, for a picture it can't decode, so the picture
     * must come back opaque (see [drawn]); if not, it is left as it was. A JPEG 2000 transparent in
     * itself (/SMaskInData other than 0) never comes here: [convert] leaves it, as a renderer that
     * ignores /SMaskInData would draw it opaque. A colour key, which needs the exact samples, and a
     * picture of more than 40 million pixels (the renderer decodes a JPEG 2000 whole, in memory Java
     * can't guard) are left too. CMYK pictures take the renderer's own colours, while CMYK fills use
     * the formula, so a picture beside a fill of the same inks can show a faint edge.
     */
    private fun rendered(image: COSStream, width: Int, height: Int, matte: Float?, bare: Boolean): Grayed {
        if (image.getDictionaryObject(COSName.MASK) is COSArray) return Grayed.LEFT
        if (width.toLong() * height > MAX_RENDERED_PIXELS) return Grayed.LEFT
        val plain = isPlain(image)
        val (targetWidth, targetHeight) = storedSize(width, height, plain, matte != null)
        scratchDir.mkdirs()
        val file = File.createTempFile("gray", ".pdf", scratchDir)
        try {
            alone(image, targetWidth, targetHeight, file)
            check()
            val pixels = GrayImage(targetWidth, targetHeight)
            val colour = try {
                drawn(file, pixels)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (e.isOutOfSpace()) throw e
                null
            } catch (_: Exception) {
                // What Android's renderer throws differs between its versions (SecurityException,
                // IllegalStateException and more), as renderShownPage in PdfThumbnail.kt has it.
                null
            } ?: return Grayed.LEFT
            if (bare && !colour) return Grayed.AS_IT_WAS
            storeJpeg(image, pixels, plain, matte)
            return Grayed.CHANGED
        } finally {
            file.delete()
        }
    }

    /**
     * Writes to [file] a PDF of one page, [width] by [height] points, that [image] fills: a copy of
     * it, its raw data and its dictionary cloned into the new PDF (the source document goes on being
     * used), but for its masks and what concerns only the document it is in. The page is whole
     * points, one point a pixel when drawn, so both generations of the renderer (see
     * [PageRendering]) draw it the same.
     */
    private fun alone(image: COSStream, width: Int, height: Int, file: File) {
        PDDocument(pdfScratch(scratchDir)).use { temp ->
            val cloner = PDFCloneUtility(temp)
            val picture = temp.document.createCOSStream()
            for (key in image.keySet()) {
                if (key in NOT_CLONED) continue
                val value = image.getDictionaryObject(key) ?: continue
                picture.setItem(key, cloner.cloneForNewDocument(value))
            }
            image.createRawInputStream().use { input -> picture.createRawOutputStream().use { input.copyTo(it) } }
            val page = PDPage(PDRectangle(width.toFloat(), height.toFloat()))
            page.cosObject.setItem(COSName.RESOURCES, COSDictionary().apply {
                setItem(COSName.XOBJECT, COSDictionary().apply { setItem(PICTURE, picture) })
            })
            val content = temp.document.createCOSStream()
            content.createOutputStream().use { it.write("q $width 0 0 $height 0 0 cm /${PICTURE.name} Do Q".toByteArray(Charsets.US_ASCII)) }
            page.cosObject.setItem(COSName.CONTENTS, content)
            temp.addPage(page)
            temp.save(file)
        }
    }

    /**
     * Draws [file]'s page into [gray], a band of at most a million pixels at a time on a transparent
     * bitmap, each pixel's gray by [GrayMath.rgb8]. Returns null when the picture wasn't drawn: a
     * pixel not fully opaque, but on the picture's outer edge, which a renderer may leave partly
     * uncovered and which then takes the gray of the pixel inside it (a picture with no inside, 2
     * pixels or less across, must be opaque everywhere). Else whether a pixel came back in colour
     * (channels more than 2 apart).
     */
    private fun drawn(file: File, gray: GrayImage): Boolean? {
        val width = gray.width
        val height = gray.height
        val inside = width > 2 && height > 2
        val bandHeight = (BAND_PIXELS / width).toInt().coerceIn(1, height)
        val uncovered = BitSet()
        var colour = false
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    val band = createBitmap(width, bandHeight)
                    try {
                        val row = IntArray(width * bandHeight)
                        var top = 0
                        while (top < height) {
                            check()
                            val rows = minOf(bandHeight, height - top)
                            band.eraseColor(Color.TRANSPARENT)
                            page.render(band, null, Matrix().apply { setTranslate(0f, -top.toFloat()) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            band.getPixels(row, 0, width, 0, 0, width, rows)
                            for (dy in 0 until rows) {
                                val y = top + dy
                                val edgeRow = y == 0 || y == height - 1
                                for (x in 0 until width) {
                                    val p = row[dy * width + x]
                                    if (p ushr 24 != 0xFF) {
                                        if (inside && (edgeRow || x == 0 || x == width - 1)) {
                                            uncovered.set(y * width + x)
                                            continue
                                        }
                                        return null
                                    }
                                    val r = (p shr 16) and 0xFF
                                    val g = (p shr 8) and 0xFF
                                    val b = p and 0xFF
                                    if (maxOf(r, g, b) - minOf(r, g, b) > GRAY_SPREAD) colour = true
                                    gray.pixels[y * width + x] = GrayMath.rgb8(r, g, b).toByte()
                                }
                            }
                            top += rows
                        }
                    } finally {
                        band.recycle()
                    }
                }
            }
        }
        var i = uncovered.nextSetBit(0)
        while (i >= 0) {
            val x = (i % width).coerceIn(1, width - 2)
            val y = (i / width).coerceIn(1, height - 2)
            gray.pixels[i] = gray.pixels[y * width + x]
            i = uncovered.nextSetBit(i + 1)
        }
        return colour
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

        /** Route F: the largest picture sent to the renderer, the most pixels drawn at a time, and how far apart a gray pixel's channels may come back. */
        const val MAX_RENDERED_PIXELS = 40_000_000L
        const val BAND_PIXELS = 1_000_000L
        const val GRAY_SPREAD = 2
        val PICTURE: COSName = COSName.getPDFName("Im")

        /** What route F's copy of a picture leaves out: its masks, and what concerns only the document it is in. */
        val NOT_CLONED = setOf(
            COSName.LENGTH, COSName.SMASK, COSName.MASK, COSName.METADATA, COSName.OC, COSName.STRUCT_PARENT,
            COSName.getPDFName("Alternates"), COSName.getPDFName("OPI"),
        )
        val TABLE_BITS = setOf(1, 2, 4, 8)
        val ROW_BITS = setOf(1, 2, 4, 8, 16)
        val ALTERNATES: COSName = COSName.getPDFName("Alternates")
        val COLOR_TRANSFORM: COSName = COSName.getPDFName("ColorTransform")
        val SMASK_IN_DATA: COSName = COSName.getPDFName("SMaskInData")
        val DCT = setOf("DCTDecode", "DCT")
        val JPX = setOf("JPXDecode")
        val JPEG_FILTERS = setOf("DCTDecode", "DCT", "JPXDecode")
        val LOSSLESS = setOf(
            "FlateDecode", "Fl", "LZWDecode", "LZW", "RunLengthDecode", "RL", "ASCIIHexDecode", "AHx", "ASCII85Decode", "A85",
        )
    }
}
