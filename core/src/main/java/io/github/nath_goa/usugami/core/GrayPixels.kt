package io.github.nath_goa.usugami.core

/**
 * Turns rows of a PDF picture's samples, as its stream stores them once its lossless filters are
 * undone, into one byte of gray a pixel: Black and white's way to store a picture in colour as an
 * 8-bit DeviceGray picture of the same size, a row at a time.
 *
 * Samples of 1, 2, 4, 8 or 16 bits are unpacked (rows start on a byte), mapped through [decode]
 * (the picture's /Decode, or the space's default when null) and given to the [space] for their
 * gray. 8-bit RGB and CMYK with the default decode take a direct path; a space of one component of
 * at most 8 bits is worked out once for every possible sample; a spot colour's space (its tint
 * transform may be a slow function) remembers up to 65 536 colours already worked out.
 *
 * [colorKey] is a picture's colour-key /Mask, a min and max for each component in raw sample
 * values: a pixel whose every component is within its range is not painted.
 *
 * Throws [IllegalArgumentException] for bits it can't read, a /Decode or colour key with too few
 * numbers, or a space with no gray (/None, a pattern, one that can't be read): such a picture is
 * left as it was. A spot colour whose tint transform fails also throws [IllegalArgumentException]
 * ([GraySpace.Tint]): from the constructor for one component of at most 8 bits (every sample is
 * worked out there), else from [row].
 */
class GrayPixels(space: GraySpace, bits: Int, decode: FloatArray?, colorKey: IntArray? = null) {

    private val space = space
    private val bits = bits
    private val components = space.components
    private val maxRaw = (1 shl bits) - 1
    private val decode: FloatArray
    private val colorKey: IntArray?
    private val mode: Int
    private val table: ByteArray?
    private val memo: LevelMemo?

    // One pixel's raw samples and their decoded values, reused for every pixel.
    private val raw = IntArray(components)
    private val values = FloatArray(components)

    init {
        require(bits == 1 || bits == 2 || bits == 4 || bits == 8 || bits == 16) { "$bits bits per component" }
        require(components > 0 && space !is GraySpace.Unknown && space !is GraySpace.Pattern && space !is GraySpace.None) {
            "No gray for a picture in $space"
        }
        require(decode == null || decode.size >= 2 * components) { "A /Decode of ${decode?.size} numbers for $components components" }
        require(colorKey == null || colorKey.size >= 2 * components) { "A colour key of ${colorKey?.size} numbers for $components components" }
        val default = space.defaultDecode(bits)
        this.decode = decode?.copyOf(2 * components) ?: default
        this.colorKey = colorKey?.copyOf(2 * components)
        val isDefault = this.decode.contentEquals(default)
        mode = when {
            bits == 8 && isDefault && space === GraySpace.Rgb -> RGB
            bits == 8 && isDefault && (space === GraySpace.Cmyk || space === GraySpace.IccCmyk) -> CMYK
            components == 1 && bits <= 8 -> TABLE
            space is GraySpace.Tint && components * bits <= 64 -> MEMO
            else -> GENERIC
        }
        table = if (mode == TABLE) ByteArray(maxRaw + 1) { sample -> generic(intArrayOf(sample)).toByte() } else null
        memo = if (mode == MEMO) LevelMemo() else null
    }

    /** The bytes of one row of [width] pixels: rows start on a byte. */
    fun rowBytes(width: Int): Int {
        val bitCount = width.toLong() * components * bits
        require(width >= 0 && bitCount <= Int.MAX_VALUE.toLong()) { "A row of $width pixels" }
        return ((bitCount + 7) / 8).toInt()
    }

    /**
     * One row of [packed] samples to a byte of gray per pixel in [gray]; with a colour key, [masked]
     * gets a bit per pixel (first pixel in the high bit), 1 where the key leaves the pixel unpainted.
     */
    fun row(packed: ByteArray, width: Int, gray: ByteArray, masked: ByteArray? = null) {
        require(packed.size >= rowBytes(width) && gray.size >= width) { "Too little room for a row of $width pixels" }
        val maskBytes = (width + 7) / 8
        if (masked != null) {
            require(masked.size >= maskBytes) { "Too little room for a mask row of $width pixels" }
            masked.fill(0, 0, maskBytes)
        }
        val key = colorKey
        for (x in 0 until width) {
            var inKey = key != null
            for (i in 0 until components) {
                val sample = sample(packed, x * components + i)
                raw[i] = sample
                if (inKey && (sample < key!![2 * i] || sample > key[2 * i + 1])) inKey = false
            }
            if (inKey && masked != null) masked[x ushr 3] = (masked[x ushr 3].toInt() or (0x80 ushr (x and 7))).toByte()
            val level = when (mode) {
                RGB -> GrayMath.rgb8(raw[0], raw[1], raw[2])
                CMYK -> GrayMath.level(GrayMath.cmyk(raw[0] / 255f, raw[1] / 255f, raw[2] / 255f, raw[3] / 255f))
                TABLE -> table!![raw[0]].toInt() and 0xFF
                MEMO -> memoized()
                else -> generic(raw)
            }
            gray[x] = level.toByte()
        }
    }

    /** Sample number [index] of a row. */
    private fun sample(packed: ByteArray, index: Int): Int = when (bits) {
        8 -> packed[index].toInt() and 0xFF
        16 -> ((packed[2 * index].toInt() and 0xFF) shl 8) or (packed[2 * index + 1].toInt() and 0xFF)
        else -> {
            val bit = index * bits
            ((packed[bit ushr 3].toInt() and 0xFF) ushr (8 - bits - (bit and 7))) and maxRaw
        }
    }

    /** The gray level of the raw samples [samples], through the decode and the space. */
    private fun generic(samples: IntArray): Int {
        for (i in 0 until components) {
            val min = decode[2 * i]
            values[i] = min + samples[i] * (decode[2 * i + 1] - min) / maxRaw
        }
        return GrayMath.level(space.gray(values))
    }

    private fun memoized(): Int {
        var key = 0L
        for (i in 0 until components) key = (key shl bits) or raw[i].toLong()
        val memo = memo!!
        val known = memo.get(key)
        if (known >= 0) return known
        return generic(raw).also { memo.put(key, it) }
    }

    /** Levels by the exact samples of a colour packed into a Long; at most [LIMIT] of them. */
    private class LevelMemo {
        private val keys = LongArray(SLOTS)
        private val levels = ShortArray(SLOTS) { -1 }
        private var size = 0

        fun get(key: Long): Int {
            var slot = slot(key)
            while (true) {
                val level = levels[slot].toInt()
                if (level < 0) return -1
                if (keys[slot] == key) return level
                slot = (slot + 1) and (SLOTS - 1)
            }
        }

        fun put(key: Long, level: Int) {
            if (size >= LIMIT) return
            var slot = slot(key)
            while (levels[slot] >= 0) {
                if (keys[slot] == key) return
                slot = (slot + 1) and (SLOTS - 1)
            }
            keys[slot] = key
            levels[slot] = level.toShort()
            size++
        }

        private fun slot(key: Long): Int = ((key * -0x61c8864680b583ebL) ushr (64 - SLOT_BITS)).toInt()

        companion object {
            const val LIMIT = 65_536
            const val SLOT_BITS = 17
            const val SLOTS = 1 shl SLOT_BITS
        }
    }

    private companion object {
        const val RGB = 0
        const val CMYK = 1
        const val TABLE = 2
        const val MEMO = 3
        const val GENERIC = 4
    }
}
