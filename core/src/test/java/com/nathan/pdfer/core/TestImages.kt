package com.nathan.pdfer.core

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

object TestImages {

    fun pattern(width: Int, height: Int, type: Int = BufferedImage.TYPE_INT_RGB): BufferedImage {
        val image = BufferedImage(width, height, type)
        for (y in 0 until height) for (x in 0 until width) {
            val r = x * 255 / maxOf(1, width - 1)
            val g = y * 255 / maxOf(1, height - 1)
            val b = if ((x / 8 + y / 8) % 2 == 0) 40 else 220
            image.setRGB(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
        }
        return image
    }

    fun jpeg(image: BufferedImage, progressive: Boolean = false): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam
        param.compressionMode = ImageWriteParam.MODE_EXPLICIT
        param.compressionQuality = 0.9f
        if (progressive) param.progressiveMode = ImageWriteParam.MODE_DEFAULT
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(image, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(bytes))

    /** Builds a marker segment: FF marker, 2-byte length, payload. */
    fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length ushr 8).toByte(), length.toByte()) + payload
    }

    /** Inserts [segments] right after SOI. */
    fun withSegments(jpeg: ByteArray, vararg segments: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(jpeg, 0, 2)
        segments.forEach { out.write(it) }
        out.write(jpeg, 2, jpeg.size - 2)
        return out.toByteArray()
    }

    fun pixelsOf(image: BufferedImage): IntArray = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

    class BufferedImageSource(private val image: BufferedImage) : PixelSource {
        override val width get() = image.width
        override val height get() = image.height
        override fun readRow(y: Int, out: IntArray) {
            val gray = image.type == BufferedImage.TYPE_BYTE_GRAY || image.type == BufferedImage.TYPE_USHORT_GRAY
            if (!gray) {
                image.getRGB(0, y, image.width, 1, out, 0, image.width)
                return
            }
            // getRGB treats Java's gray images as linear and brightens them; PNG gray is not linear.
            val shift = if (image.type == BufferedImage.TYPE_USHORT_GRAY) 8 else 0
            for (x in 0 until image.width) {
                val v = image.raster.getSample(x, y, 0) ushr shift
                out[x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
    }
}

fun ByteArray.indexOf(pattern: ByteArray, from: Int = 0): Int {
    outer@ for (i in from..size - pattern.size) {
        for (j in pattern.indices) if (this[i + j] != pattern[j]) continue@outer
        return i
    }
    return -1
}
