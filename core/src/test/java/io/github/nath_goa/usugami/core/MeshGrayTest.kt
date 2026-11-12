package io.github.nath_goa.usugami.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class MeshGrayTest {

    private val rgbDecode = floatArrayOf(0f, 1f, 0f, 1f, 0f, 1f)
    private val rgbGray: (FloatArray) -> Float = { GrayMath.rgb(it[0], it[1], it[2]) }

    /** [value]'s low [bits] bits, high byte first. */
    private fun ByteArrayOutputStream.number(value: Long, bits: Int) {
        for (i in bits / 8 - 1 downTo 0) write((value ushr (8 * i)).toInt() and 0xFF)
    }

    private fun max(bits: Int) = (1L shl bits) - 1

    /** The gray an RGB colour of [bits]-bit samples is written as. */
    private fun grayOf(r: Long, g: Long, b: Long, bits: Int): Long {
        val m = max(bits).toFloat()
        return (GrayMath.rgb(r / m, g / m, b / m).toDouble() * max(bits) + 0.5).toLong()
    }

    private fun convert(
        type: Int, coordinate: Int, component: Int, data: ByteArray,
        flag: Int = 8, components: Int = 3, decode: FloatArray = rgbDecode, gray: (FloatArray) -> Float = rgbGray,
        input: (ByteArray) -> InputStream = { ByteArrayInputStream(it) },
    ): ByteArray? {
        val out = ByteArrayOutputStream()
        val done = MeshGray.convert(type, flag, coordinate, component, components, decode, gray, input(data), out)
        return if (done) out.toByteArray() else null.also { assertEquals(0, out.size()) }
    }

    /**
     * A mesh of [type] with [patches] records, each starting with the flag given (none for type 5),
     * its points' coordinates numbered from [seed] and its colours cycling through red, green, blue
     * and a mix; and the data expected once each colour is gray.
     */
    private fun mesh(type: Int, coordinate: Int, component: Int, flags: List<Int>, seed: Long = 0x7F3A_91C2L): Pair<ByteArray, ByteArray> {
        val input = ByteArrayOutputStream()
        val expected = ByteArrayOutputStream()
        val colours = listOf(longArrayOf(max(component), 0, 0), longArrayOf(0, max(component), 0), longArrayOf(0, 0, max(component)), longArrayOf(max(component) / 3, max(component) / 2, 7))
        var coordinateValue = seed
        var colour = 0
        for (flag in flags) {
            if (type != 5) {
                input.write(flag)
                expected.write(flag)
            }
            val first = flag and 3 == 0
            val (points, count) = when (type) {
                4, 5 -> 1 to 1
                6 -> if (first) 12 to 4 else 8 to 2
                else -> if (first) 16 to 4 else 12 to 2
            }
            repeat(points * 2) {
                coordinateValue = (coordinateValue * 31 + 17) and max(coordinate)
                input.number(coordinateValue, coordinate)
                expected.number(coordinateValue, coordinate)
            }
            repeat(count) {
                val c = colours[colour++ % colours.size]
                for (v in c) input.number(v, component)
                expected.number(grayOf(c[0], c[1], c[2], component), component)
            }
        }
        return input.toByteArray() to expected.toByteArray()
    }

    @Test
    fun everyTypeKeepsFlagsAndCoordinatesAndTurnsEachColourGray() {
        for (type in 4..7) for (coordinate in listOf(8, 16, 32)) for (component in listOf(8, 16)) {
            val flags = when (type) {
                4 -> listOf(0, 0, 0, 1, 2, 2)
                5 -> List(6) { 0 }
                else -> listOf(0, 1, 2, 3, 0)
            }
            val (data, expected) = mesh(type, coordinate, component, flags)
            assertArrayEquals("type $type, $coordinate/$component bits", expected, convert(type, coordinate, component, data))
        }
    }

    @Test
    fun patchesTakeTheirLengthFromTheFlag() {
        // Type 6: flag 0 is 1 + 12 points of 2 bytes + 4 colours of 3 bytes; flag 1 is 1 + 8 * 2 + 2 * 3.
        val six = mesh(6, 8, 8, listOf(0, 1))
        assertEquals(1 + 24 + 12 + 1 + 16 + 6, six.first.size)
        assertEquals(1 + 24 + 4 + 1 + 16 + 2, six.second.size)
        assertArrayEquals(six.second, convert(6, 8, 8, six.first))
        // Type 7: 16 points and 4 colours, or 12 points and 2.
        val seven = mesh(7, 16, 16, listOf(0, 2))
        assertEquals(1 + 64 + 24 + 1 + 48 + 12, seven.first.size)
        assertEquals(1 + 64 + 8 + 1 + 48 + 4, seven.second.size)
        assertArrayEquals(seven.second, convert(7, 16, 16, seven.first))
        // Only the low two bits of a flag count: 4 is a first patch.
        val four = mesh(6, 8, 8, listOf(4))
        assertEquals(1 + 24 + 12, four.first.size)
        assertArrayEquals(four.second, convert(6, 8, 8, four.first))
    }

    @Test
    fun aRedVertexIsItsGray() {
        val data = byteArrayOf(0, 10, 20, 0xFF.toByte(), 0, 0)
        assertArrayEquals(byteArrayOf(0, 10, 20, 77), convert(4, 8, 8, data))
        val wide = byteArrayOf(1, 2, 3, 4, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0)
        // 65535 * 77 / 256 = 19712.3, written in 16 bits.
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, (19712 ushr 8).toByte(), (19712 and 0xFF).toByte()), convert(5, 16, 16, wide))
    }

    @Test
    fun decodeRangesAreHonoured() {
        // One component read over 0.2 to 0.6, its gray the value itself.
        val data = byteArrayOf(0, 1, 2, 0, 0, 3, 4, 0xFF.toByte(), 0, 5, 6, 0x80.toByte())
        val out = convert(4, 8, 8, data, components = 1, decode = floatArrayOf(0.2f, 0.6f), gray = { it[0] })
        assertArrayEquals(byteArrayOf(0, 1, 2, 51, 0, 3, 4, 153.toByte(), 0, 5, 6, GrayMath.level(0.2f + 128 * 0.4f / 255).toByte()), out)
        // Inverted RGB: black samples read as white.
        assertArrayEquals(byteArrayOf(9, 9, 0xFF.toByte()), convert(5, 8, 8, byteArrayOf(9, 9, 0, 0, 0), decode = floatArrayOf(1f, 0f, 1f, 0f, 1f, 0f)))
    }

    @Test
    fun aRecordCutShortIsDropped() {
        val (data, expected) = mesh(4, 16, 8, listOf(0, 0, 0))
        val record = data.size / 3
        val gray = expected.size / 3
        for (cut in 1 until record) {
            assertArrayEquals(expected.copyOf(2 * gray), convert(4, 16, 8, data.copyOf(2 * record + cut)))
        }
        val (patches, grayPatches) = mesh(6, 8, 8, listOf(0, 1))
        // A flag with nothing after it, and a patch missing its last byte.
        assertArrayEquals(grayPatches, convert(6, 8, 8, patches + byteArrayOf(0)))
        assertArrayEquals(grayPatches.copyOf(1 + 24 + 4), convert(6, 8, 8, patches.copyOf(patches.size - 1)))
        assertArrayEquals(ByteArray(0), convert(6, 8, 8, ByteArray(0)))
    }

    @Test
    fun aStreamThatGivesOneByteAtATimeReadsTheSame() {
        val (data, expected) = mesh(7, 32, 16, listOf(0, 1, 0))
        val trickle: (ByteArray) -> InputStream = { bytes ->
            object : InputStream() {
                val source = ByteArrayInputStream(bytes)
                override fun read(): Int = source.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = if (len == 0) 0 else source.read(b, off, 1)
            }
        }
        assertArrayEquals(expected, convert(7, 32, 16, data, input = trickle))
    }

    @Test
    fun numbersThatArentWholeBytesAreRefusedWithNothingWritten() {
        val (data, _) = mesh(4, 8, 8, listOf(0, 0, 0))
        assertEquals(null, convert(4, 8, 8, data, flag = 2))
        assertEquals(null, convert(4, 8, 12, data))
        assertEquals(null, convert(4, 12, 8, data))
        assertEquals(null, convert(6, 8, 8, data, flag = 4))
        assertEquals(null, convert(3, 8, 8, data))
        assertEquals(null, convert(4, 8, 8, data, decode = floatArrayOf(0f, 1f)))
        // Type 5 has no flags, so their size doesn't matter.
        val (lattice, expected) = mesh(5, 8, 8, List(4) { 0 })
        assertArrayEquals(expected, convert(5, 8, 8, lattice, flag = 2))
    }

    @Test
    fun cancellingIsAskedAsItGoes() {
        val (data, _) = mesh(4, 8, 8, List(5000) { 0 })
        var asked = 0
        val done = MeshGray.convert(4, 8, 8, 8, 3, rgbDecode, rgbGray, ByteArrayInputStream(data), ByteArrayOutputStream(), check = { asked++ })
        assertTrue(done)
        assertTrue(asked >= 4)
        assertFalse(MeshGray.convert(4, 2, 8, 8, 3, rgbDecode, rgbGray, ByteArrayInputStream(data), ByteArrayOutputStream()))
    }
}
