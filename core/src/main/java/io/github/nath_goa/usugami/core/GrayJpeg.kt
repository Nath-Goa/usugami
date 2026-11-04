package io.github.nath_goa.usugami.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Baseline JPEG compression of a grey picture: one 8-bit component, so a PDF stores it as
 * DeviceGray. (Android's own encoder always writes three colour components.) The standard
 * luminance quantization table, scaled for the quality as libjpeg scales it, so [encode]'s quality
 * means what it does for Android's colour JPEGs, and Huffman tables made for the picture (as
 * libjpeg's optimize_coding makes them); no metadata.
 */
object GrayJpeg {

    fun encode(image: GrayImage, quality: Int, checkCancelled: () -> Unit = {}): ByteArray {
        require(quality in 1..100) { "Quality $quality" }
        require(image.width <= 65535 && image.height <= 65535) { "A JPEG can't be ${image.width} x ${image.height}" }
        val table = quantization(quality)
        // What each DCT coefficient is divided by, in natural order, with the DCT's own scale in it.
        val divisors = DoubleArray(64) { table[it] * 4.0 }
        val blocksAcross = (image.width + 7) / 8
        val blocksDown = (image.height + 7) / 8
        // Every block's coefficients, in zigzag order: the Huffman tables are made from all of them
        // before any is written.
        val coefficients = ShortArray(blocksAcross * blocksDown * 64)
        val block = DoubleArray(64)
        for (by in 0 until blocksDown) {
            if (by and 7 == 0) checkCancelled()
            for (bx in 0 until blocksAcross) {
                // The block's pixels, centred on 0; the edge's pixels repeat past the picture.
                for (v in 0 until 8) {
                    val y = minOf(by * 8 + v, image.height - 1) * image.width
                    for (u in 0 until 8) {
                        val x = minOf(bx * 8 + u, image.width - 1)
                        block[v * 8 + u] = (image.pixels[y + x].toInt() and 0xFF) - 128.0
                    }
                }
                forwardDct(block)
                val start = (by * blocksAcross + bx) * 64
                for (k in 0 until 64) {
                    val natural = ZIGZAG[k]
                    coefficients[start + k] = (block[natural] / divisors[natural]).roundToInt().toShort()
                }
            }
        }
        val dcCounts = IntArray(257)
        val acCounts = IntArray(257)
        forEachSymbol(coefficients, { dcCounts[it]++ }, { acCounts[it]++ })
        val dc = HuffmanTable.optimal(dcCounts)
        val ac = HuffmanTable.optimal(acCounts)
        checkCancelled()
        val out = BitWriter(maxOf(4096, image.width * image.height / 8))
        forEachSymbol(
            coefficients,
            { symbol -> out.write(dc.codes[symbol], dc.lengths[symbol]) },
            { symbol -> out.write(ac.codes[symbol], ac.lengths[symbol]) },
            { bits, size -> out.write(bits, size) },
        )
        val data = stuffed(out.finish(padWithOnes = true))
        return header(image.width, image.height, table, dc, ac) + data + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    /**
     * Goes through the Huffman symbols the blocks are coded with, in order: each block's DC
     * difference's size ([dc]), then its AC coefficients' run and size, 16 zeros (0xF0) and the end
     * of the block (0x00) ([ac]), and the bits that follow a symbol ([extra], when given).
     */
    private inline fun forEachSymbol(
        coefficients: ShortArray,
        dc: (Int) -> Unit,
        ac: (Int) -> Unit,
        extra: (bits: Int, size: Int) -> Unit = { _, _ -> },
    ) {
        var previousDc = 0
        for (start in coefficients.indices step 64) {
            val value = coefficients[start].toInt()
            val difference = value - previousDc
            previousDc = value
            val size = magnitude(difference)
            dc(size)
            if (size > 0) extra(amplitude(difference, size), size)
            var zeros = 0
            for (k in 1 until 64) {
                val c = coefficients[start + k].toInt()
                if (c == 0) {
                    zeros++
                    continue
                }
                while (zeros >= 16) {
                    ac(0xF0)
                    zeros -= 16
                }
                val s = magnitude(c)
                ac((zeros shl 4) or s)
                extra(amplitude(c, s), s)
                zeros = 0
            }
            if (zeros > 0) ac(0x00)
        }
    }

    /** The standard luminance table scaled for [quality] as libjpeg does, in natural order. */
    internal fun quantization(quality: Int): IntArray {
        val scale = if (quality < 50) 5000 / quality else 200 - 2 * quality
        return IntArray(64) { ((LUMINANCE[it] * scale + 50) / 100).coerceIn(1, 255) }
    }

    /** SOI, DQT, SOF0, DHT (DC and AC) and SOS for one component. */
    private fun header(width: Int, height: Int, table: IntArray, dc: HuffmanTable, ac: HuffmanTable): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun marker(type: Int, body: ByteArray) {
            out.write(0xFF)
            out.write(type)
            out.write((body.size + 2) ushr 8)
            out.write((body.size + 2) and 0xFF)
            out.write(body)
        }
        out.write(0xFF)
        out.write(0xD8)
        marker(0xDB, ByteArray(65) { if (it == 0) 0 else table[ZIGZAG[it - 1]].toByte() })
        marker(
            0xC0,
            byteArrayOf(8, (height ushr 8).toByte(), height.toByte(), (width ushr 8).toByte(), width.toByte(), 1, 1, 0x11, 0),
        )
        marker(0xC4, byteArrayOf(0x00) + dc.definition() + byteArrayOf(0x10) + ac.definition())
        marker(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0))
        return out.toByteArray()
    }

    /** The number of bits [value]'s magnitude needs (its JPEG category). */
    private fun magnitude(value: Int): Int = 32 - Integer.numberOfLeadingZeros(if (value < 0) -value else value)

    /** [value]'s [size] bits as JPEG writes them: negative values as their one's complement. */
    private fun amplitude(value: Int, size: Int): Int = if (value >= 0) value else (value - 1) and ((1 shl size) - 1)

    /** A 0xFF byte in the coded data is followed by 0x00, so it can't be taken for a marker. */
    private fun stuffed(data: ByteArray): ByteArray {
        val extra = data.count { it == 0xFF.toByte() }
        if (extra == 0) return data
        val out = ByteArray(data.size + extra)
        var i = 0
        for (b in data) {
            out[i++] = b
            if (b == 0xFF.toByte()) out[i++] = 0
        }
        return out
    }

    /** The 8 x 8 DCT of [block] (row after row), in place: C(u) C(v) Σ f(x, y) cos … cos …, 4 times the JPEG coefficient. */
    private fun forwardDct(block: DoubleArray) {
        val temp = DoubleArray(64)
        for (y in 0 until 8) for (u in 0 until 8) {
            var sum = 0.0
            for (x in 0 until 8) sum += block[y * 8 + x] * COS[u * 8 + x]
            temp[y * 8 + u] = sum
        }
        for (x in 0 until 8) for (v in 0 until 8) {
            var sum = 0.0
            for (y in 0 until 8) sum += temp[y * 8 + x] * COS[v * 8 + y]
            block[v * 8 + x] = sum
        }
    }

    /** C(u) cos((2x + 1) u π / 16), C(0) = 1 / √2 and 1 otherwise. */
    private val COS = DoubleArray(64) { i ->
        val u = i / 8
        val x = i % 8
        val c = if (u == 0) 1 / sqrt(2.0) else 1.0
        c * cos((2 * x + 1) * u * PI / 16)
    }

    /** The natural position of each coefficient in zigzag order. */
    private val ZIGZAG = intArrayOf(
        0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
        12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
    )

    /** The JPEG standard's luminance quantization table (Annex K.1), natural order. */
    private val LUMINANCE = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61,
        12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56,
        14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77,
        24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101,
        72, 92, 95, 98, 112, 100, 103, 99,
    )
}

/**
 * A JPEG Huffman table: how many codes there are of each length 1 to 16 ([counts]), the symbols in
 * code order ([symbols]), and each symbol's code and its length.
 */
internal class HuffmanTable(val counts: IntArray, val symbols: IntArray) {
    val codes = IntArray(256)
    val lengths = IntArray(256)

    init {
        // The codes the lengths give, in order (JPEG Annex C).
        var code = 0
        var k = 0
        for (length in 1..16) {
            repeat(counts[length - 1]) {
                codes[symbols[k]] = code
                lengths[symbols[k]] = length
                code++
                k++
            }
            code = code shl 1
        }
    }

    /** The table as a DHT segment gives it, after its class and number. */
    fun definition(): ByteArray = ByteArray(16 + symbols.size) { if (it < 16) counts[it].toByte() else symbols[it - 16].toByte() }

    companion object {
        /**
         * The best table for symbols used [frequency] times each (indexes 0 to 255; index 256 must be
         * 0), with no code longer than 16 bits and none all ones: libjpeg's jpeg_gen_optimal_table
         * (JPEG Annex K.2).
         */
        fun optimal(frequency: IntArray): HuffmanTable {
            val freq = frequency.copyOf(257)
            // A symbol no one uses keeps every real code from being all ones.
            freq[256] = 1
            val size = IntArray(257)
            val others = IntArray(257) { -1 }
            while (true) {
                // The two least frequent symbols left (the later of equals first).
                var c1 = -1
                var v = Long.MAX_VALUE
                for (i in 0..256) if (freq[i] != 0 && freq[i] <= v) { v = freq[i].toLong(); c1 = i }
                var c2 = -1
                v = Long.MAX_VALUE
                for (i in 0..256) if (freq[i] != 0 && freq[i] <= v && i != c1) { v = freq[i].toLong(); c2 = i }
                if (c2 < 0) break
                freq[c1] += freq[c2]
                freq[c2] = 0
                size[c1]++
                while (others[c1] >= 0) {
                    c1 = others[c1]
                    size[c1]++
                }
                others[c1] = c2
                size[c2]++
                while (others[c2] >= 0) {
                    c2 = others[c2]
                    size[c2]++
                }
            }
            val bits = IntArray(33)
            for (i in 0..256) if (size[i] > 0) bits[size[i]]++
            // Codes longer than 16 bits moved up the tree.
            for (i in 32 downTo 17) {
                while (bits[i] > 0) {
                    var j = i - 2
                    while (bits[j] == 0) j--
                    bits[i] -= 2
                    bits[i - 1]++
                    bits[j + 1] += 2
                    bits[j]--
                }
            }
            // The made-up symbol's code goes.
            var longest = 16
            while (bits[longest] == 0) longest--
            bits[longest]--
            val symbols = ArrayList<Int>()
            for (length in 1..32) for (symbol in 0..255) if (size[symbol] == length) symbols += symbol
            return HuffmanTable(IntArray(16) { bits[it + 1] }, symbols.toIntArray())
        }
    }
}
