package io.github.nath_goa.usugami.core

import java.io.ByteArrayOutputStream

/**
 * Shrinks a TrueType font that a PDF embeds to the glyphs the PDF draws. Glyph numbers stay as
 * they are, so nothing in the PDF needs to change: the other glyphs' outlines are emptied, and the
 * tables only glyph positioning reads (GPOS, kern and the like) are left out, since a PDF places
 * every glyph itself. Android's PDF writer embeds each font whole: about 140 KB for each style of
 * the phone's serif font, while a book draws a few hundred glyphs of it.
 *
 * The pages must look exactly the same afterwards, also in viewers that render with FreeType's
 * auto-hinter (Android's own among them). It lines each glyph up with heights it measures, for each
 * script, from a few reference letters of the font, so those letters stay for each script drawn.
 * Only fonts for Latin, Greek and Cyrillic text (with digits, punctuation and symbols) are shrunk,
 * the scripts whose reference letters this knows; a font for any other script is left whole.
 */
object TrueTypeSubset {
    /**
     * Tables PDF viewers don't read: positioning (the PDF has placed each glyph), signatures, device
     * metrics. GSUB and GDEF stay, small as they are: an auto-hinter built with HarfBuzz reads them to
     * tell which script a glyph without a character of its own (a ligature) belongs to.
     */
    private val DROPPED = setOf(
        "GPOS", "BASE", "JSTF", "MATH", "kern", "kerx", "morx", "mort", "feat", "DSIG", "hdmx", "LTSH",
        "VDMX", "PCLT", "STAT", "meta", "vhea", "vmtx",
    )

    /** Fonts left as they are: PostScript outlines, variable, colour and bitmap fonts keep glyphs elsewhere. */
    private val UNTOUCHED = setOf(
        "CFF ", "CFF2", "fvar", "gvar", "COLR", "CBDT", "CBLC", "sbix", "SVG ", "EBDT", "EBLC", "bdat", "bloc",
    )

    /**
     * A script FreeType's auto-hinter tells apart: the characters it counts as that script (as in its
     * afranges.c, with a little to spare), and the letters and digits it measures them from (its
     * afblue.dat, again with a little to spare).
     */
    private class Script(val bit: Int, val ranges: List<IntRange>, references: String) {
        val references = references.filter { it != ' ' }.map { it.code }
    }

    private val SUBSCRIPT = Script(
        1,
        listOf(0x1D62..0x1D6A, 0x2080..0x209C, 0x2C7C..0x2C7C),
        "₀₁₂₃₄₅₆₇₈₉ ᵢⱼₕₖₗ ₐₑₒₓₙₛᵥᵤᵣ ᵦᵧᵨᵩₚ",
    )
    private val SUPERSCRIPT = Script(
        2,
        listOf(
            0xAA..0xAA, 0xB2..0xB3, 0xB9..0xBA, 0x2B0..0x2B8, 0x2E0..0x2E4, 0x1D2C..0x1D61, 0x1D78..0x1D78,
            0x1D9B..0x1DBF, 0x2070..0x207F, 0x2C7D..0x2C7D, 0xA770..0xA770, 0xA7F8..0xA7F9, 0xAB5C..0xAB5F,
        ),
        "⁰¹²³⁴⁵⁶⁷⁸⁹ ᵀᴴᴱᴼᴸᵁ ᵇᵈᵏʰʲᶠⁱ ᵉᵒʳˢˣᶜᶻ ᵖʸᵍ",
    )
    private val GREEK = Script(
        4,
        listOf(0x370..0x3FF, 0x1F00..0x1FFF, 0x1D26..0x1D2A, 0x1D5D..0x1D61, 0x1D66..0x1D6A, 0x1DBF..0x1DBF),
        "ΓΒΕΖΘΟΩΔΞ βθδζλξ αειοπστω γημρφχψ",
    )
    private val CYRILLIC = Script(
        8,
        listOf(0x400..0x52F, 0x1C80..0x1C8F, 0x2DE0..0x2DFF, 0xA640..0xA69F, 0x1D2B..0x1D2B, 0x1D78..0x1D78),
        "БВЕПЗОСЭШ хпншезос руф",
    )

    /** Latin, and every other character of the fonts this shrinks (digits, punctuation, symbols). */
    private val LATIN = Script(16, emptyList(), "0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ abcdefghijklmnopqrstuvwxyz")
    private val SCRIPTS = listOf(SUBSCRIPT, SUPERSCRIPT, GREEK, CYRILLIC, LATIN)
    private val REFERENCES = SCRIPTS.flatMapTo(HashSet()) { it.references }

    /** Latin, Greek and Cyrillic letters, digits, punctuation and symbols: the characters a font to shrink may draw. */
    private val COMMON = listOf(
        0x0000..0x052F, 0x1AB0..0x1AFF, 0x1C80..0x1C8F, 0x1D00..0x1FFF, 0x2000..0x2BFF, 0x2C60..0x2C7F,
        0x2DE0..0x2E7F, 0xA640..0xA69F, 0xA700..0xA7FF, 0xAB30..0xAB6F, 0xE000..0xF8FF, 0xFB00..0xFB06,
        0xFE00..0xFE0F, 0xFE20..0xFE2F, 0xFEFF..0xFEFF, 0xFFF0..0xFFFF, 0x1D400..0x1D7FF, 0x1F100..0x1F1FF,
    )

    /**
     * CJK characters. The auto-hinter measures the glyphs of no script it knows (arrows, ligatures)
     * from the ideographs, so a font that has any is left whole.
     */
    private val CJK = listOf(
        0x1100..0x11FF, 0x2E80..0xA4CF, 0xA960..0xA97F, 0xAC00..0xD7FF, 0xF900..0xFAFF, 0xFE10..0xFE1F,
        0xFE30..0xFE4F, 0xFF00..0xFFEF, 0x1B000..0x1B2FF, 0x1F200..0x1F2FF, 0x20000..0x3FFFF,
    )

    /** The rupee sign, which the auto-hinter counts as Devanagari: drawn, the font mustn't have Devanagari letters. */
    private const val RUPEE = 0x20B9
    private val DEVANAGARI = 0x0900..0x097F

    // What a glyph's characters are, beside the scripts' bits.
    private const val SCRIPT_BITS = 31
    private const val HAS_CHARACTER = 1 shl 8
    private const val OTHER_SCRIPT = 1 shl 9
    private const val RUPEE_SIGN = 1 shl 10

    /**
     * [font] with only the outlines of [glyphs], glyph 0 (shown for missing characters), the glyphs
     * those are built from and the reference letters of the scripts drawn. Null when [font] isn't a
     * TrueType font this can shrink (one for another script, or one that is damaged or unusual), or
     * when nothing would be saved.
     */
    fun keepGlyphs(font: ByteArray, glyphs: Set<Int>): ByteArray? = try {
        subset(font, glyphs)?.takeIf { it.size < font.size }
    } catch (_: RuntimeException) {
        // Damaged beyond what the checks below catch, such as offsets past the end.
        null
    }

    private fun subset(font: ByteArray, glyphs: Set<Int>): ByteArray? {
        if (font.size < 12) return null
        val version = u32(font, 0)
        if (version != 0x00010000L && version != 0x74727565L) return null
        val count = u16(font, 4)
        if (count == 0 || 12 + count * 16 > font.size) return null
        val tables = HashMap<String, ByteArray>()
        for (i in 0 until count) {
            val record = 12 + i * 16
            val tag = String(font, record, 4, Charsets.ISO_8859_1)
            val offset = u32(font, record + 8)
            val length = u32(font, record + 12)
            if (offset + length > font.size) return null
            tables[tag] = font.copyOfRange(offset.toInt(), (offset + length).toInt())
        }
        if (tables.keys.any { it in UNTOUCHED }) return null
        val head = tables["head"]?.takeIf { it.size >= 54 } ?: return null
        val maxp = tables["maxp"]?.takeIf { it.size >= 6 } ?: return null
        val loca = tables["loca"] ?: return null
        val glyf = tables["glyf"] ?: return null
        val longLoca = s16(head, 50) == 1
        val glyphCount = u16(maxp, 4)
        if (loca.size < (glyphCount + 1) * (if (longLoca) 4 else 2)) return null
        val offsets = IntArray(glyphCount + 1) { i -> if (longLoca) u32(loca, i * 4).toInt() else u16(loca, i * 2) * 2 }
        for (i in 0 until glyphCount) if (offsets[i] < 0 || offsets[i] > offsets[i + 1]) return null
        if (offsets[glyphCount] > glyf.size) return null

        // What each glyph's characters are, from the Unicode character map: without one, the font is left whole.
        val map = tables["cmap"]?.let(CharMap::read) ?: return null
        val kinds = IntArray(glyphCount)
        val referenceGlyphs = HashMap<Int, Int>()
        var hasCjk = false
        var hasDevanagari = false
        var hasOther = false
        val readable = map.forEach { c, glyph ->
            val other = COMMON.none { c in it }
            if (other) hasOther = true
            if (CJK.any { c in it }) hasCjk = true
            if (c in DEVANAGARI) hasDevanagari = true
            if (glyph < glyphCount) {
                var kind = HAS_CHARACTER or scriptBits(c)
                if (other) kind = kind or OTHER_SCRIPT
                if (c == RUPEE) kind = kind or RUPEE_SIGN
                kinds[glyph] = kinds[glyph] or kind
                if (c in REFERENCES) referenceGlyphs[c] = glyph
            }
        }
        if (!readable || hasCjk) return null
        // The scripts drawn. A glyph without a character (a ligature, a mark placed on a capital) may
        // count as any script that has substitutions in the font: in a font that has other scripts
        // too, it may be one of those.
        var drawn = 0
        for (glyph in glyphs) {
            if (glyph !in 1 until glyphCount) continue
            val kind = kinds[glyph]
            drawn = drawn or when {
                kind and HAS_CHARACTER == 0 -> if (hasOther) return null else LATIN.bit or GREEK.bit or CYRILLIC.bit
                kind and OTHER_SCRIPT != 0 -> return null
                kind and RUPEE_SIGN != 0 && hasDevanagari -> return null
                else -> kind and SCRIPT_BITS
            }
        }

        // The glyphs drawn, glyph 0, the reference letters of the scripts drawn, and the parts of composite glyphs.
        val keep = BooleanArray(glyphCount)
        val todo = ArrayDeque<Int>()
        fun add(glyph: Int) {
            if (glyph in 0 until glyphCount && !keep[glyph]) {
                keep[glyph] = true
                todo += glyph
            }
        }
        add(0)
        glyphs.forEach(::add)
        for (script in SCRIPTS) {
            if (drawn and script.bit != 0) script.references.forEach { c -> referenceGlyphs[c]?.let(::add) }
        }
        while (todo.isNotEmpty()) {
            val glyph = todo.removeFirst()
            val start = offsets[glyph]
            val end = offsets[glyph + 1]
            if (end - start >= 10 && s16(glyf, start) < 0) components(glyf, start + 10, end, ::add)
        }

        val outlines = ByteArrayOutputStream(glyf.size / 4)
        val newOffsets = IntArray(glyphCount + 1)
        for (glyph in 0 until glyphCount) {
            newOffsets[glyph] = outlines.size()
            if (!keep[glyph]) continue
            val start = offsets[glyph]
            val end = offsets[glyph + 1]
            outlines.write(glyf, start, end - start)
            repeat((4 - (end - start) % 4) % 4) { outlines.write(0) }
        }
        newOffsets[glyphCount] = outlines.size()
        // Short offsets when they fit, as they usually do now.
        val newLong = outlines.size() > 0x1FFFE
        val newLoca = ByteArray((glyphCount + 1) * (if (newLong) 4 else 2))
        for (i in 0..glyphCount) if (newLong) put32(newLoca, i * 4, newOffsets[i].toLong()) else put16(newLoca, i * 2, newOffsets[i] / 2)

        val newHead = head.copyOf()
        put16(newHead, 50, if (newLong) 1 else 0)
        put32(newHead, 8, 0)
        tables["head"] = newHead
        tables["loca"] = newLoca
        tables["glyf"] = outlines.toByteArray()
        // Glyph names aren't needed either: version 3 of the post table has none.
        tables["post"]?.takeIf { it.size >= 32 }?.let { post ->
            tables["post"] = post.copyOf(32).also { put32(it, 0, 0x00030000L) }
        }
        DROPPED.forEach(tables::remove)
        return write(tables)
    }

    /** The bits of the scripts [c] counts as: Latin when none of the others. */
    private fun scriptBits(c: Int): Int {
        var bits = 0
        for (script in SCRIPTS) if (script.ranges.any { c in it }) bits = bits or script.bit
        return if (bits == 0) LATIN.bit else bits
    }

    /** Adds the glyphs a composite glyph's description (from [start] to [end] in glyf) is made of. */
    private fun components(glyf: ByteArray, start: Int, end: Int, add: (Int) -> Unit) {
        var p = start
        while (p + 4 <= end) {
            val flags = u16(glyf, p)
            add(u16(glyf, p + 2))
            p += 4 + (if (flags and ARGS_ARE_WORDS != 0) 4 else 2)
            p += when {
                flags and HAS_SCALE != 0 -> 2
                flags and HAS_XY_SCALE != 0 -> 4
                flags and HAS_2X2 != 0 -> 8
                else -> 0
            }
            if (flags and MORE_COMPONENTS == 0) break
        }
    }

    /** A font's character map (cmap), by its Unicode subtable. */
    private class CharMap(private val cmap: ByteArray, private val at: Int, private val format: Int) {
        /** Calls [action] with each character mapped and its glyph; false when the map is damaged or implausibly big. */
        fun forEach(action: (Int, Int) -> Unit): Boolean {
            when (format) {
                4 -> {
                    val segments = u16(cmap, at + 6) / 2
                    val ends = at + 14
                    val starts = ends + segments * 2 + 2
                    val deltas = starts + segments * 2
                    val rangeOffsets = deltas + segments * 2
                    var previousEnd = -1
                    for (i in 0 until segments) {
                        val start = u16(cmap, starts + i * 2)
                        val end = u16(cmap, ends + i * 2)
                        // In order and apart, as they must be: then at most 65,535 characters in all.
                        if (start > end || start <= previousEnd) return false
                        previousEnd = end
                        val delta = u16(cmap, deltas + i * 2)
                        val rangeOffset = u16(cmap, rangeOffsets + i * 2)
                        for (c in start..minOf(end, 0xFFFE)) {
                            val glyph = if (rangeOffset == 0) {
                                (c + delta) and 0xFFFF
                            } else {
                                u16(cmap, rangeOffsets + i * 2 + rangeOffset + (c - start) * 2).let { if (it == 0) 0 else (it + delta) and 0xFFFF }
                            }
                            if (glyph != 0) action(c, glyph)
                        }
                    }
                }
                12 -> {
                    var total = 0L
                    for (i in 0 until u32(cmap, at + 12)) {
                        val group = (at + 16 + i * 12).toInt()
                        val first = u32(cmap, group)
                        val last = u32(cmap, group + 4)
                        val glyph = u32(cmap, group + 8)
                        if (first > last || last > 0x10FFFF) return false
                        total += last - first + 1
                        if (total > 0x110000) return false
                        for (c in first..last) {
                            val g = glyph + (c - first)
                            if (g in 1..0xFFFF) action(c.toInt(), g.toInt())
                        }
                    }
                }
                else -> return false
            }
            return true
        }

        companion object {
            /** The Unicode subtable, full (format 12) or basic (format 4), or null when there is none. */
            fun read(cmap: ByteArray): CharMap? {
                var best: CharMap? = null
                var bestScore = 0
                for (i in 0 until u16(cmap, 2)) {
                    val record = 4 + i * 8
                    if (record + 8 > cmap.size) break
                    val platform = u16(cmap, record)
                    val encoding = u16(cmap, record + 2)
                    val offset = u32(cmap, record + 4).toInt()
                    if (offset < 0 || offset + 16 > cmap.size) continue
                    val format = u16(cmap, offset)
                    val unicode = platform == 0 || (platform == 3 && (encoding == 1 || encoding == 10))
                    val score = if (!unicode) 0 else if (format == 12) 2 else if (format == 4) 1 else 0
                    if (score > bestScore) {
                        bestScore = score
                        best = CharMap(cmap, offset, format)
                    }
                }
                return best
            }
        }
    }

    /** A font file with [tables], in tag order, each padded to four bytes and with its checksum. */
    private fun write(tables: Map<String, ByteArray>): ByteArray {
        val tags = tables.keys.sorted()
        val count = tags.size
        var power = 1
        var log = 0
        while (power * 2 <= count) {
            power *= 2
            log++
        }
        val header = 12 + 16 * count
        val size = header + tags.sumOf { (tables.getValue(it).size + 3) / 4 * 4 }
        val out = ByteArray(size)
        put32(out, 0, 0x00010000L)
        put16(out, 4, count)
        put16(out, 6, power * 16)
        put16(out, 8, log)
        put16(out, 10, count * 16 - power * 16)
        var offset = header
        var headAt = -1
        tags.forEachIndexed { i, tag ->
            val data = tables.getValue(tag)
            val record = 12 + i * 16
            for (k in 0 until 4) out[record + k] = tag[k].code.toByte()
            put32(out, record + 4, checksum(data, 0, data.size))
            put32(out, record + 8, offset.toLong())
            put32(out, record + 12, data.size.toLong())
            data.copyInto(out, offset)
            if (tag == "head") headAt = offset
            offset += (data.size + 3) / 4 * 4
        }
        if (headAt >= 0) put32(out, headAt + 8, (0xB1B0AFBAL - checksum(out, 0, out.size)) and 0xFFFFFFFFL)
        return out
    }

    /** The sum of a table's (or a whole font's) big-endian 32-bit words, as TrueType checksums are. */
    fun checksum(data: ByteArray, from: Int, length: Int): Long {
        var sum = 0L
        var i = 0
        while (i < length) {
            var word = 0L
            for (k in 0 until 4) word = (word shl 8) or (if (i + k < length) (data[from + i + k].toLong() and 0xFF) else 0L)
            sum = (sum + word) and 0xFFFFFFFFL
            i += 4
        }
        return sum
    }

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    private fun s16(b: ByteArray, at: Int) = u16(b, at).toShort().toInt()
    private fun u32(b: ByteArray, at: Int): Long = (u16(b, at).toLong() shl 16) or u16(b, at + 2).toLong()

    private fun put16(b: ByteArray, at: Int, value: Int) {
        b[at] = (value shr 8).toByte()
        b[at + 1] = value.toByte()
    }

    private fun put32(b: ByteArray, at: Int, value: Long) {
        put16(b, at, (value shr 16).toInt() and 0xFFFF)
        put16(b, at + 2, value.toInt() and 0xFFFF)
    }

    private const val ARGS_ARE_WORDS = 0x0001
    private const val HAS_SCALE = 0x0008
    private const val MORE_COMPONENTS = 0x0020
    private const val HAS_XY_SCALE = 0x0040
    private const val HAS_2X2 = 0x0080
}
