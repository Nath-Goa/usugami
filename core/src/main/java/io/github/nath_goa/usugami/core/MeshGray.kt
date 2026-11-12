package io.github.nath_goa.usugami.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Turns the colours of a mesh shading (types 4 to 7: triangles, a lattice of triangles, Coons and
 * tensor patches) gray in its stream data, for one without a /Function, whose every vertex or patch
 * corner carries its own colour. Flags and coordinates are copied byte for byte; each colour becomes
 * one gray value of the same number of bits.
 *
 * It only converts data whose every number is a whole number of bytes (coordinates of 8 to 32 bits,
 * components of 8 or 16, flags of 8), which is how the PDF makers found in the wild write them: then
 * every record is whole bytes and viewers agree on where it ends (for other sizes they disagree on
 * padding). Anything else is left in colour, and reported, by the caller.
 */
object MeshGray {

    /**
     * Copies a mesh shading's data from [input] to [output] with one gray value in place of each colour.
     * [decode] is the colour part of /Decode (two numbers per component). Returns false, having written
     * nothing to keep, when a number in the data is not a whole number of bytes.
     *
     * Records: type 4 = flag, x, y, colour; type 5 = x, y, colour; type 6 = flag, then 12 points and
     * 4 colours when the flag's low two bits are 0, else 8 points and 2 colours; type 7 the same with
     * 16 and 12 points. A record cut short at the end is dropped. [gray] gets each colour's decoded
     * components in an array it must not keep (it is reused). Also false, with nothing written, for
     * a type other than 4 to 7, no components, or a [decode] with too few numbers. Neither stream is
     * closed.
     */
    fun convert(
        type: Int, bitsPerFlag: Int, bitsPerCoordinate: Int, bitsPerComponent: Int, components: Int,
        decode: FloatArray, gray: (FloatArray) -> Float, input: InputStream, output: OutputStream, check: () -> Unit = {},
    ): Boolean {
        if (type !in 4..7) return false
        if (type != 5 && bitsPerFlag != 8) return false
        if (bitsPerCoordinate != 8 && bitsPerCoordinate != 16 && bitsPerCoordinate != 24 && bitsPerCoordinate != 32) return false
        if (bitsPerComponent != 8 && bitsPerComponent != 16) return false
        if (components < 1 || decode.size < 2 * components) return false

        val point = 2 * (bitsPerCoordinate / 8)
        val component = bitsPerComponent / 8
        val colourIn = components * component
        val maxRaw = (1 shl bitsPerComponent) - 1
        val flag = if (type == 5) 0 else 1
        // The longest record: a tensor patch's 16 points and 4 colours.
        val record = ByteArray(1 + 16 * point + 4 * colourIn)
        val converted = ByteArray(1 + 16 * point + 4 * component)
        val values = FloatArray(components)
        val source = BufferedInputStream(input, BUFFER)
        val target = BufferedOutputStream(output, BUFFER)
        var count = 0
        while (true) {
            if (++count and 0x3FF == 0) check()
            if (flag == 1 && readFully(source, record, 0, 1) < 1) break
            val first = flag == 0 || record[0].toInt() and 3 == 0
            val points = when (type) {
                4, 5 -> 1
                6 -> if (first) 12 else 8
                else -> if (first) 16 else 12
            }
            val colours = when (type) {
                4, 5 -> 1
                else -> if (first) 4 else 2
            }
            val length = flag + points * point + colours * colourIn
            if (readFully(source, record, flag, length - flag) < length - flag) break
            val coordinates = flag + points * point
            System.arraycopy(record, 0, converted, 0, coordinates)
            var at = coordinates
            for (c in 0 until colours) {
                val start = coordinates + c * colourIn
                for (i in 0 until components) {
                    val offset = start + i * component
                    var raw = record[offset].toInt() and 0xFF
                    if (component == 2) raw = (raw shl 8) or (record[offset + 1].toInt() and 0xFF)
                    val min = decode[2 * i]
                    values[i] = min + raw * (decode[2 * i + 1] - min) / maxRaw
                }
                val level = (GrayMath.unit(gray(values)).toDouble() * maxRaw + 0.5).toInt()
                if (component == 2) converted[at++] = (level ushr 8).toByte()
                converted[at++] = level.toByte()
            }
            target.write(converted, 0, at)
        }
        target.flush()
        return true
    }

    /** Reads until [length] bytes are in [buffer] from [offset] or the data ends; the count read. */
    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        var read = 0
        while (read < length) {
            val n = input.read(buffer, offset + read, length - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    private const val BUFFER = 64 * 1024
}
