package io.github.nath_goa.usugami.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class TrueTypeSubsetTest {
    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, at: Int) = (u16(b, at).toLong() shl 16) or u16(b, at + 2).toLong()

    private fun bytes(size: Int, fill: Int) = ByteArray(size) { fill.toByte() }

    /** A simple glyph: one contour, then [size] - 10 bytes of made-up outline marked with [mark]. */
    private fun simple(size: Int, mark: Int) = ByteArray(size).also {
        it[1] = 1
        for (i in 10 until size) it[i] = mark.toByte()
    }

    /** A composite glyph made of [parts], with 16-bit offsets. */
    private fun composite(vararg parts: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        out.write(ByteArray(8))
        parts.forEachIndexed { i, part ->
            val flags = 0x0001 or (if (i < parts.lastIndex) 0x0020 else 0)
            out.write(byteArrayOf((flags shr 8).toByte(), flags.toByte(), (part shr 8).toByte(), part.toByte(), 0, 5, 0, 7))
        }
        return out.toByteArray()
    }

    /** A font file with [tables], written plainly (no checksums: the subsetter doesn't need them). */
    private fun sfnt(tables: Map<String, ByteArray>, version: Long = 0x00010000L): ByteArray {
        val tags = tables.keys.sorted()
        val out = ByteArray(12 + 16 * tags.size + tags.sumOf { (tables.getValue(it).size + 3) / 4 * 4 })
        fun put32(at: Int, v: Long) {
            out[at] = (v shr 24).toByte()
            out[at + 1] = (v shr 16).toByte()
            out[at + 2] = (v shr 8).toByte()
            out[at + 3] = v.toByte()
        }
        put32(0, version)
        out[5] = tags.size.toByte()
        var offset = 12 + 16 * tags.size
        tags.forEachIndexed { i, tag ->
            val data = tables.getValue(tag)
            for (k in 0 until 4) out[12 + i * 16 + k] = tag[k].code.toByte()
            put32(12 + i * 16 + 8, offset.toLong())
            put32(12 + i * 16 + 12, data.size.toLong())
            data.copyInto(out, offset)
            offset += (data.size + 3) / 4 * 4
        }
        return out
    }

    /** A format 4 Unicode character map with one segment for each of [map]'s characters (to glyphs). */
    private fun cmap(map: Map<Int, Int>): ByteArray {
        val segments = map.entries.map { it.key to it.value }.sortedBy { it.first } + (0xFFFF to 0)
        val out = ByteArrayOutputStream()
        fun w(v: Int) {
            out.write(v shr 8)
            out.write(v)
        }
        // The table header, one subtable record (Windows Unicode), then the format 4 subtable.
        listOf(0, 1, 3, 1, 0, 12).forEach(::w)
        listOf(4, 16 + segments.size * 8, 0, segments.size * 2, 0, 0, 0).forEach(::w)
        segments.forEach { w(it.first) }
        w(0)
        segments.forEach { w(it.first) }
        segments.forEach { (c, g) -> w(if (c == 0xFFFF) 1 else (g - c) and 0xFFFF) }
        segments.forEach { w(0) }
        return out.toByteArray()
    }

    /** A font of [glyphs], with [chars] (character to glyph) as its character map, layout tables and glyph names. */
    private fun font(glyphs: List<ByteArray>, chars: Map<Int, Int>, longLoca: Boolean = false, cmap: ByteArray? = cmap(chars)): ByteArray {
        val glyf = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        for (g in glyphs) {
            offsets += glyf.size()
            glyf.write(g)
            if (g.size % 2 == 1) glyf.write(0)
        }
        offsets += glyf.size()
        val loca = ByteArray(offsets.size * (if (longLoca) 4 else 2))
        offsets.forEachIndexed { i, o ->
            if (longLoca) {
                loca[i * 4 + 2] = (o shr 8).toByte()
                loca[i * 4 + 3] = o.toByte()
            } else {
                loca[i * 2] = ((o / 2) shr 8).toByte()
                loca[i * 2 + 1] = (o / 2).toByte()
            }
        }
        val head = ByteArray(54).also { it[51] = if (longLoca) 1 else 0 }
        val maxp = ByteArray(6).also { it[1] = 0x50; it[4] = (glyphs.size shr 8).toByte(); it[5] = glyphs.size.toByte() }
        val post = ByteArray(60).also { it[1] = 2; for (i in 32 until 60) it[i] = 'n'.code.toByte() }
        val tables = mutableMapOf(
            "head" to head, "maxp" to maxp, "loca" to loca, "glyf" to glyf.toByteArray(), "post" to post,
            "GPOS" to bytes(400, 9), "GSUB" to bytes(40, 7), "GDEF" to bytes(20, 6),
        )
        if (cmap != null) tables["cmap"] = cmap
        return sfnt(tables)
    }

    /** The font's tables by tag, checking each one's checksum on the way. */
    private fun tables(font: ByteArray): Map<String, ByteArray> {
        val count = u16(font, 4)
        return (0 until count).associate { i ->
            val record = 12 + i * 16
            val tag = String(font, record, 4, Charsets.ISO_8859_1)
            val offset = u32(font, record + 8).toInt()
            val length = u32(font, record + 12).toInt()
            val data = font.copyOfRange(offset, offset + length)
            if (tag != "head") assertEquals("$tag checksum", u32(font, record + 4), TrueTypeSubset.checksum(data, 0, data.size))
            assertEquals("$tag is four-byte aligned", 0, offset % 4)
            tag to data
        }
    }

    /** Each glyph's outline in [font], by its loca table. */
    private fun outlines(font: ByteArray): List<ByteArray> {
        val t = tables(font)
        val long = u16(t.getValue("head"), 50) == 1
        val loca = t.getValue("loca")
        val glyf = t.getValue("glyf")
        val count = u16(t.getValue("maxp"), 4)
        val offsets = (0..count).map { if (long) u32(loca, it * 4).toInt() else u16(loca, it * 2) * 2 }
        return (0 until count).map { glyf.copyOfRange(offsets[it], offsets[it + 1]) }
    }

    // Glyph 3 is made of glyph 2. None of the characters is a letter the auto-hinter measures.
    private val parts = listOf(simple(16, 0x00), simple(24, 0x11), simple(20, 0x22), composite(2), simple(28, 0x44))
    private val symbols = mapOf('!'.code to 1, '#'.code to 2, '$'.code to 3, '%'.code to 4)

    @Test fun keepsTheGlyphsDrawnAndWhatTheyAreMadeOf() {
        val original = font(parts, symbols)
        val subset = TrueTypeSubset.keepGlyphs(original, setOf(3))!!
        assertTrue(subset.size < original.size)
        val kept = outlines(subset)
        // Same glyph numbers: still five glyphs.
        assertEquals(5, kept.size)
        assertArrayEquals(parts[0], kept[0].copyOf(parts[0].size))
        assertEquals("Not drawn: emptied", 0, kept[1].size)
        assertArrayEquals("Part of glyph 3", parts[2], kept[2].copyOf(parts[2].size))
        assertArrayEquals(parts[3], kept[3].copyOf(parts[3].size))
        assertEquals(0, kept[4].size)
    }

    @Test fun dropsPositioningAndGlyphNamesAndKeepsChecksumsRight() {
        val subset = TrueTypeSubset.keepGlyphs(font(parts, symbols), setOf(1))!!
        val t = tables(subset)
        // GPOS goes; GSUB and GDEF stay, for auto-hinters that read them.
        assertEquals(listOf("GDEF", "GSUB", "cmap", "glyf", "head", "loca", "maxp", "post"), t.keys.sorted())
        assertEquals(32, t.getValue("post").size)
        assertEquals(0x00030000L, u32(t.getValue("post"), 0))
        // The whole file sums to TrueType's magic number, as the head table's adjustment makes it.
        assertEquals(0xB1B0AFBAL, TrueTypeSubset.checksum(subset, 0, subset.size))
        val sorted = t.keys.toList()
        assertEquals(sorted.sorted(), (0 until u16(subset, 4)).map { String(subset, 12 + it * 16, 4, Charsets.ISO_8859_1) })
    }

    @Test fun shortensOffsetsAndIgnoresGlyphsTheFontDoesntHave() {
        val subset = TrueTypeSubset.keepGlyphs(font(parts, symbols, longLoca = true), setOf(4, 999, -1))!!
        // The glyphs left fit short offsets, half the size.
        assertEquals(0, u16(tables(subset).getValue("head"), 50))
        assertEquals(parts.size + 1, tables(subset).getValue("loca").size / 2)
        val kept = outlines(subset)
        assertArrayEquals(parts[4], kept[4].copyOf(parts[4].size))
        assertEquals(0, kept[1].size)
        assertEquals(0, kept[3].size)
    }

    // One glyph for each character, 1 onwards, and glyph 12 without one (as ligatures are).
    private val letters = listOf(
        'A'.code, 'o'.code, 'x'.code, '0'.code, 0x3B1, 0x3BF, 0x43E, 0xB2, 0x2070, 0x2080, 0x2192,
    ).withIndex().associate { (i, c) -> c to i + 1 }
    private val letterFont = font(List(13) { simple(12 + it * 2, it) }, letters)

    /** The glyphs [letterFont] keeps when [drawn] are drawn. */
    private fun kept(drawn: Int) = outlines(TrueTypeSubset.keepGlyphs(letterFont, setOf(drawn))!!)
        .withIndex().filter { it.value.isNotEmpty() }.map { it.index }.toSet()

    @Test fun keepsTheLettersTheAutoHinterMeasuresForEachScriptDrawn() {
        // An arrow, or digits alone, are hinted as Latin: measured by "A", "o", "x" and "0" here.
        assertEquals(setOf(0, 11, 1, 2, 3, 4), kept(11))
        assertEquals(setOf(0, 4, 1, 2, 3), kept(4))
        // Greek by α and ο, Cyrillic by о, superscripts by ² and ⁰, subscripts by ₀.
        assertEquals(setOf(0, 5, 6), kept(5))
        assertEquals(setOf(0, 7), kept(7))
        assertEquals(setOf(0, 8, 9), kept(8))
        assertEquals(setOf(0, 10), kept(10))
        // A glyph without a character may count as any script with substitutions: Latin, Greek or Cyrillic.
        assertEquals(setOf(0, 12, 1, 2, 3, 4, 5, 6, 7), kept(12))
    }

    @Test fun leavesFontsForOtherScriptsWhole() {
        val glyphs = List(4) { simple(40, it) }
        // A Hebrew letter drawn: the auto-hinter measures Hebrew by letters this doesn't know.
        val hebrew = font(glyphs, mapOf('A'.code to 1, 0x5D0 to 2))
        assertNull(TrueTypeSubset.keepGlyphs(hebrew, setOf(2)))
        assertNotNull(TrueTypeSubset.keepGlyphs(hebrew, setOf(1)))
        // A glyph without a character, in a font with Hebrew: it may be Hebrew.
        assertNull(TrueTypeSubset.keepGlyphs(hebrew, setOf(3)))
        // A font with CJK ideographs: glyphs of no script (symbols, ligatures) are measured by those.
        assertNull(TrueTypeSubset.keepGlyphs(font(glyphs, mapOf('A'.code to 1, 0x4E00 to 2)), setOf(1)))
        // The rupee sign counts as Devanagari: fine unless the font has Devanagari letters.
        assertNotNull(TrueTypeSubset.keepGlyphs(font(glyphs, mapOf('A'.code to 1, 0x20B9 to 2)), setOf(2)))
        val devanagari = font(glyphs, mapOf('A'.code to 1, 0x20B9 to 2, 0x915 to 3))
        assertNull(TrueTypeSubset.keepGlyphs(devanagari, setOf(2)))
        assertNull(TrueTypeSubset.keepGlyphs(devanagari, setOf(3)))
        assertNotNull(TrueTypeSubset.keepGlyphs(devanagari, setOf(1)))
    }

    @Test fun leavesOtherFontsAlone() {
        val font = font(parts, symbols)
        // PostScript outlines, a variable font, a collection, damage and noise: nothing to do.
        assertNull(TrueTypeSubset.keepGlyphs(sfnt(mapOf("CFF " to bytes(100, 1), "head" to ByteArray(54)), version = 0x4F54544FL), setOf(1)))
        val withVariations = font.copyOf().also { "gvar".forEachIndexed { k, c -> it[12 + k] = c.code.toByte() } }
        assertNull(TrueTypeSubset.keepGlyphs(withVariations, setOf(1)))
        assertNull(TrueTypeSubset.keepGlyphs("ttcf".toByteArray() + ByteArray(40), setOf(1)))
        assertNull(TrueTypeSubset.keepGlyphs(font.copyOf(font.size / 3), setOf(1)))
        assertNull(TrueTypeSubset.keepGlyphs(ByteArray(5), setOf(1)))
        // Without a readable Unicode character map, there is no telling which scripts are drawn.
        assertNull(TrueTypeSubset.keepGlyphs(font(parts, symbols, cmap = null), setOf(1)))
        assertNull(TrueTypeSubset.keepGlyphs(font(parts, symbols, cmap = bytes(12, 3)), setOf(1)))
        val random = Random(7)
        repeat(300) {
            val noise = font.copyOf().also { b -> repeat(20) { b[random.nextInt(b.size)] = random.nextInt(256).toByte() } }
            TrueTypeSubset.keepGlyphs(noise, setOf(1, 3))
        }
    }
}
