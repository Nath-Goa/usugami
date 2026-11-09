package io.github.nath_goa.usugami.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.core.graphics.createBitmap
import io.github.nath_goa.usugami.core.ImageFileType
import io.github.nath_goa.usugami.core.PictureTiles
import io.github.nath_goa.usugami.core.RgbConversion
import io.github.nath_goa.usugami.core.YuvColors
import java.io.File

/**
 * Decodes a HEIC or AVIF picture a tile at a time, straight to a fraction of its size. Phones store
 * a large photo as a grid of tiles, each a small HEVC or AV1 picture. Android's own decoder puts
 * them together at full size first, however small the picture is asked for: for a 200-megapixel
 * photo that is 400 MB and more, in Usugami and again in Android's media server. Here each tile is
 * decoded alone and only the pixels kept are taken from it.
 */
internal object HeifTiles {
    /**
     * The HEIC or AVIF picture at [path] at 1/[sample] of its size, its pixels as stored (not
     * turned), in sRGB, as BitmapFactory gives it. Null when [path] holds another kind of picture,
     * when [sample] is 1 (Android's decoder then makes it no bigger than asked), or when it can't be
     * decoded this way, such as a picture of more than 8 bits a colour: Android's decoder does it
     * then.
     */
    fun decode(path: String, sample: Int): Bitmap? {
        if (sample <= 1 || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val extractor = MediaExtractor()
        try {
            val codecType = when (typeOf(path)) {
                ImageFileType.HEIF -> MediaFormat.MIMETYPE_VIDEO_HEVC
                ImageFileType.AVIF -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaFormat.MIMETYPE_VIDEO_AV1 else return null
                else -> return null
            }
            // The colours the file says it is in, read without decoding it.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val space = bounds.outColorSpace
            val toSrgb = if (space == null || space.isSrgb) null else srgbConversion(space) ?: return null

            extractor.setDataSource(path)
            val track = pictureTrack(extractor) ?: return null
            val format = extractor.getTrackFormat(track)
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val tiles = PictureTiles(
                width,
                height,
                format.intOrNull(MediaFormat.KEY_TILE_WIDTH) ?: width,
                format.intOrNull(MediaFormat.KEY_TILE_HEIGHT) ?: height,
                sample,
            )
            val columns = format.intOrNull(MediaFormat.KEY_GRID_COLUMNS) ?: 1
            val rows = format.intOrNull(MediaFormat.KEY_GRID_ROWS) ?: 1
            if (columns != tiles.columns || rows != tiles.rows) return null

            val codecFormat = MediaFormat.createVideoFormat(codecType, tiles.tileWidth, tiles.tileHeight)
            for (key in CONFIG_KEYS) {
                if (!format.containsKey(key)) continue
                if (key.startsWith("csd-")) codecFormat.setByteBuffer(key, format.getByteBuffer(key))
                else codecFormat.setInteger(key, format.getInteger(key))
            }
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(codecFormat) ?: return null
            codecFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val codec = MediaCodec.createByCodecName(name)
            var unused: Bitmap? = null
            try {
                val bitmap = createBitmap(tiles.outWidth, tiles.outHeight, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.SRGB))
                unused = bitmap
                codec.configure(codecFormat, null, null, 0)
                codec.start()
                extractor.selectTrack(track)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                if (!decodeTiles(codec, extractor, tiles, bitmap, toSrgb)) return null
                unused = null
                return bitmap
            } finally {
                codec.release()
                unused?.recycle()
            }
        } catch (e: Exception) {
            // A codec or a file it can't take; Android's decoder may still read it.
            return null
        } finally {
            extractor.release()
        }
    }

    /** How colours in [space] are brought to sRGB, as ColorSpace.connect does; null if it isn't RGB. */
    private fun srgbConversion(space: ColorSpace): RgbConversion? {
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB) as ColorSpace.Rgb
        val source = ColorSpace.adapt(space, srgb.whitePoint) as? ColorSpace.Rgb ?: return null
        return RgbConversion(source.eotf::applyAsDouble, source.transform, srgb.inverseTransform, srgb.oetf::applyAsDouble)
    }

    /** The type of picture in the file at [path], by its first bytes. */
    private fun typeOf(path: String): ImageFileType? = File(path).inputStream().use { input ->
        val header = ByteArray(ImageFileType.HEADER_BYTES)
        var read = 0
        while (read < header.size) {
            val count = input.read(header, read, header.size - read)
            if (count < 0) break
            read += count
        }
        ImageFileType.of(header.copyOf(read))
    }

    /** The track of the file's main picture: the one marked as such, or else the first. */
    private fun pictureTrack(extractor: MediaExtractor): Int? {
        val pictures = (0 until extractor.trackCount).filter {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) in PICTURE_TYPES
        }
        return pictures.firstOrNull { extractor.getTrackFormat(it).intOrNull(MediaFormat.KEY_IS_DEFAULT) == 1 } ?: pictures.firstOrNull()
    }

    /** Feeds [codec] every tile and draws each it gives back into [bitmap]; false if it can't. */
    private fun decodeTiles(
        codec: MediaCodec,
        extractor: MediaExtractor,
        tiles: PictureTiles,
        bitmap: Bitmap,
        toSrgb: RgbConversion?,
    ): Boolean {
        val count = tiles.columns * tiles.rows
        // Tiles give a pixel more or less than each other when the sample doesn't divide them.
        val pixels = IntArray((0 until tiles.columns).maxOf { tiles.outColumns(it).count() } * (0 until tiles.rows).maxOf { tiles.outRows(it).count() })
        val info = MediaCodec.BufferInfo()
        var colors: YuvColors? = null
        var queued = 0
        var done = 0
        var inputEnded = false
        var waits = 0
        while (done < count) {
            if (!inputEnded) {
                val index = codec.dequeueInputBuffer(0)
                if (index >= 0) {
                    val size = if (queued < count) extractor.readSampleData(checkNotNull(codec.getInputBuffer(index)), 0) else -1
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        // The tile's number, given back with its picture.
                        codec.queueInputBuffer(index, 0, size, queued.toLong(), 0)
                        queued++
                        extractor.advance()
                    }
                    continue
                }
            }
            val index = codec.dequeueOutputBuffer(info, WAIT_US)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (++waits > MAX_WAITS) return false
                continue
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) colors = null
            if (index < 0) continue
            waits = 0
            val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            if (info.size > 0 || !ended) {
                val tile = info.presentationTimeUs
                if (tile !in 0 until count) return false
                val yuv = colors ?: codec.getOutputFormat(index).let {
                    // As Android's decoder reads the colours the codec reports.
                    YuvColors.of(
                        it.intOrNull(MediaFormat.KEY_COLOR_STANDARD) ?: 0,
                        it.intOrNull(MediaFormat.KEY_COLOR_RANGE) ?: 0,
                        before13 = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU,
                    )
                }.also { colors = it }
                val image = codec.getOutputImage(index) ?: return false
                val drawn = image.use { draw(it, tile.toInt(), tiles, yuv, toSrgb, pixels, bitmap) }
                if (!drawn) return false
                done++
            }
            codec.releaseOutputBuffer(index, false)
            if (ended && done < count) return false
        }
        return true
    }

    /** Draws the pixels kept of tile [tile], decoded in [image], into [bitmap]; false if it can't. */
    private fun draw(
        image: Image,
        tile: Int,
        tiles: PictureTiles,
        colors: YuvColors,
        toSrgb: RgbConversion?,
        pixels: IntArray,
        bitmap: Bitmap,
    ): Boolean {
        val crop = image.cropRect
        if (image.format != ImageFormat.YUV_420_888 || crop.width() < tiles.tileWidth || crop.height() < tiles.tileHeight) return false
        val column = tile % tiles.columns
        val row = tile / tiles.columns
        val xs = tiles.outColumns(column)
        val ys = tiles.outRows(row)
        if (xs.isEmpty() || ys.isEmpty()) return true
        val left = column * tiles.tileWidth - crop.left
        val top = row * tiles.tileHeight - crop.top
        val (lumaPlane, cbPlane, crPlane) = image.planes
        val luma = lumaPlane.buffer
        val cb = cbPlane.buffer
        val cr = crPlane.buffer
        val width = xs.count()
        var at = 0
        for (y in ys) {
            val sourceY = tiles.sourceY(y) - top
            val lumaRow = sourceY * lumaPlane.rowStride
            val cbRow = sourceY / 2 * cbPlane.rowStride
            val crRow = sourceY / 2 * crPlane.rowStride
            for (x in xs) {
                val sourceX = tiles.sourceX(x) - left
                pixels[at++] = colors.argb(
                    luma.get(lumaRow + sourceX * lumaPlane.pixelStride).toInt() and 0xFF,
                    cb.get(cbRow + sourceX / 2 * cbPlane.pixelStride).toInt() and 0xFF,
                    cr.get(crRow + sourceX / 2 * crPlane.pixelStride).toInt() and 0xFF,
                )
            }
        }
        if (toSrgb != null) for (i in 0 until at) pixels[i] = toSrgb.argb(pixels[i])
        bitmap.setPixels(pixels, 0, width, xs.first, ys.first, width, ys.count())
        return true
    }

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

    /** The types MediaExtractor gives a HEIC and an AVIF picture. */
    private val PICTURE_TYPES = setOf("image/vnd.android.heic", "image/avif")

    /** What the codec is told of the tiles, as Android's decoder tells it. */
    private val CONFIG_KEYS = listOf(
        "csd-0", "csd-1", "csd-2",
        MediaFormat.KEY_PROFILE, MediaFormat.KEY_LEVEL, MediaFormat.KEY_MAX_INPUT_SIZE,
        MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_COLOR_TRANSFER,
    )

    /** How long to wait for a tile, and how many waits without one before giving up: 10 s. */
    private const val WAIT_US = 10_000L
    private const val MAX_WAITS = 1000
}
