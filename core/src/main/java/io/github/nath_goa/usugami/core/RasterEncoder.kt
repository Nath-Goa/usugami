package io.github.nath_goa.usugami.core

import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs

/** Decoded pixels, read one row at a time as non-premultiplied ARGB (sRGB). */
interface PixelSource {
    val width: Int
    val height: Int
    fun readRow(y: Int, out: IntArray)
}

/**
 * Lossless encoding of decoded pixels for a PDF image XObject: transparency is flattened onto
 * white (the page colour), rows go through the same adaptive PNG filters a PNG encoder uses and
 * are then deflated, so the result is about the size of the original PNG.
 */
object RasterEncoder {

    /** Whether every pixel, once flattened onto white, is a shade of grey. */
    fun isGray(source: PixelSource, checkCancelled: () -> Unit = {}): Boolean {
        val row = IntArray(source.width)
        for (y in 0 until source.height) {
            if (y and 63 == 0) checkCancelled()
            source.readRow(y, row)
            for (argb in row) {
                val r = flatten(argb ushr 16, argb)
                if (r != flatten(argb ushr 8, argb) || r != flatten(argb, argb)) return false
            }
        }
        return true
    }

    /**
     * Writes the FlateDecode stream body for [source] to [out] (which is left open). The matching
     * image dictionary needs /DecodeParms << /Predictor 15 /Colors (1|3) /BitsPerComponent 8
     * /Columns width >>.
     */
    fun writeFlate(source: PixelSource, gray: Boolean, out: OutputStream, checkCancelled: () -> Unit = {}) {
        val width = source.width
        val bpp = if (gray) 1 else 3
        val rowBytes = width * bpp
        val pixels = IntArray(width)
        var previous = ByteArray(rowBytes)
        var current = ByteArray(rowBytes)
        val candidates = Array(5) { ByteArray(rowBytes) }

        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            val stream = DeflaterOutputStream(NonClosingOutputStream(out), deflater, 64 * 1024)
            for (y in 0 until source.height) {
                if (y and 63 == 0) checkCancelled()
                source.readRow(y, pixels)
                if (gray) {
                    for (x in 0 until width) current[x] = flatten(pixels[x], pixels[x]).toByte()
                } else {
                    var i = 0
                    for (x in 0 until width) {
                        val argb = pixels[x]
                        current[i++] = flatten(argb ushr 16, argb).toByte()
                        current[i++] = flatten(argb ushr 8, argb).toByte()
                        current[i++] = flatten(argb, argb).toByte()
                    }
                }
                val filter = filterRow(current, previous, bpp, candidates)
                stream.write(filter)
                stream.write(candidates[filter], 0, rowBytes)
                val swap = previous
                previous = current
                current = swap
            }
            stream.finish()
            stream.flush()
        } finally {
            deflater.end()
        }
    }

    /**
     * Fills [candidates] with the row under each PNG filter (None, Sub, Up, Average, Paeth) and
     * returns the one with the smallest sum of absolute values (the libpng heuristic).
     */
    internal fun filterRow(row: ByteArray, prior: ByteArray, bpp: Int, candidates: Array<ByteArray>): Int {
        val none = candidates[0]
        val sub = candidates[1]
        val up = candidates[2]
        val avg = candidates[3]
        val paeth = candidates[4]
        var sumNone = 0L
        var sumSub = 0L
        var sumUp = 0L
        var sumAvg = 0L
        var sumPaeth = 0L
        for (i in row.indices) {
            val x = row[i].toInt() and 0xFF
            val a = if (i >= bpp) row[i - bpp].toInt() and 0xFF else 0
            val b = prior[i].toInt() and 0xFF
            val c = if (i >= bpp) prior[i - bpp].toInt() and 0xFF else 0

            val vNone = x.toByte()
            val vSub = (x - a).toByte()
            val vUp = (x - b).toByte()
            val vAvg = (x - ((a + b) ushr 1)).toByte()
            val vPaeth = (x - paethPredictor(a, b, c)).toByte()
            none[i] = vNone
            sub[i] = vSub
            up[i] = vUp
            avg[i] = vAvg
            paeth[i] = vPaeth
            sumNone += abs(vNone.toInt())
            sumSub += abs(vSub.toInt())
            sumUp += abs(vUp.toInt())
            sumAvg += abs(vAvg.toInt())
            sumPaeth += abs(vPaeth.toInt())
        }
        var best = 0
        var bestSum = sumNone
        if (sumSub < bestSum) { best = 1; bestSum = sumSub }
        if (sumUp < bestSum) { best = 2; bestSum = sumUp }
        if (sumAvg < bestSum) { best = 3; bestSum = sumAvg }
        if (sumPaeth < bestSum) { best = 4 }
        return best
    }

    private fun paethPredictor(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    /** One 8-bit channel (the low byte of [channel]) composited over white using the alpha of [argb]. */
    private fun flatten(channel: Int, argb: Int): Int {
        val value = channel and 0xFF
        val alpha = argb ushr 24
        if (alpha == 0xFF) return value
        return (value * alpha + 255 * (255 - alpha) + 127) / 255
    }
}

private class NonClosingOutputStream(private val target: OutputStream) : OutputStream() {
    override fun write(b: Int) = target.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = target.write(b, off, len)
    override fun flush() = target.flush()
    override fun close() = flush()
}
