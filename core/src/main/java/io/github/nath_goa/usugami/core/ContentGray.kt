package io.github.nath_goa.usugami.core

/**
 * The colour state of one content stream (a page's, a form's, a pattern's, a glyph's, an
 * appearance's) while Black and white turns it gray: the app's PDF code reads the stream one
 * operator at a time and asks [rewrite] what to write in its place.
 *
 * It tracks the fill and stroke colour spaces the SOURCE set, read as the PDF means them, saved by
 * `q` and put back by `Q`. What it writes is always `g`/`G` (which set DeviceGray), a gray pattern
 * space, or nothing (the operator is kept, under a space whose `cs` was kept too), so the stream
 * stays valid whatever the source did, and a form or glyph drawn next inherits a space it can paint
 * with. Colours are read with [GrayMath]; a colour that can't be read (a damaged tint transform,
 * which [GraySpace.Tint] reports as an [IllegalArgumentException]) is not caught here: the stream
 * is then left as it was.
 *
 * [space] finds a colour space by the name an operator gives: "DeviceGray", "DeviceRGB",
 * "DeviceCMYK" (each read through the resources' /DefaultGray, /DefaultRGB or /DefaultCMYK when there
 * is one), "Pattern" or a resource name; null when there is none. [grayPattern] gives the name of a
 * `[/Pattern /DeviceGray]` resource, made the first time it is asked for. [fill] and [stroke] are the
 * spaces the content starts with: a form starts with those of the content that draws it.
 */
class ContentGray(
    private val space: (name: String) -> GraySpace?,
    private val grayPattern: () -> String,
    fill: GraySpace = GraySpace.Gray,
    stroke: GraySpace = GraySpace.Gray,
) {
    /** The fill colour space now: what a form drawn here starts with. */
    var fill: GraySpace = fill
        private set

    /** The stroke colour space now. */
    var stroke: GraySpace = stroke
        private set

    /** Something was written in place of an operator: a colour that wasn't gray was turned gray. */
    var changed: Boolean = false

    /** Something had to be left in colour as it was. */
    var failed: Boolean = false

    private val keptNames = LinkedHashSet<String>()

    /** The colour space resource names still used by what was left as it was. */
    val kept: Set<String> get() = keptNames

    // What q saved, as runs of equal states, so a stream that nests q deeply holds one entry per
    // change of colour space rather than one per q.
    private var savedFill = arrayOfNulls<GraySpace>(8)
    private var savedStroke = arrayOfNulls<GraySpace>(8)
    private var savedRuns = IntArray(8)
    private var saved = 0

    private val devices = arrayOfNulls<GraySpace>(DEVICE_NAMES.size)
    private var patternName: String? = null

    /**
     * What to write in place of [operator] and its operands, or null to keep them as they are.
     * [numbers]: the operands that are numbers, in order; null when an operand is neither a number
     * nor a trailing name. [name]: the last operand when it is a name.
     *
     * Operands that are not what the operator takes (too few numbers, a string) keep it, and that is
     * no failure: viewers ignore such operators.
     */
    fun rewrite(operator: String, numbers: FloatArray?, name: String?): String? {
        val text = when (operator) {
            "q" -> { save(); null }
            "Q" -> { restore(); null }
            "g" -> deviceColour(true, GRAY, numbers, name)
            "G" -> deviceColour(false, GRAY, numbers, name)
            "rg" -> deviceColour(true, RGB, numbers, name)
            "RG" -> deviceColour(false, RGB, numbers, name)
            "k" -> deviceColour(true, CMYK, numbers, name)
            "K" -> deviceColour(false, CMYK, numbers, name)
            "cs" -> setSpace(true, name)
            "CS" -> setSpace(false, name)
            "sc" -> setColour(true, false, numbers, name)
            "scn" -> setColour(true, true, numbers, name)
            "SC" -> setColour(false, false, numbers, name)
            "SCN" -> setColour(false, true, numbers, name)
            else -> null
        }
        if (text != null) changed = true
        return text
    }

    /** `g`, `rg`, `k` and their stroke forms: the colour is read through the device space [which]. */
    private fun deviceColour(fill: Boolean, which: Int, numbers: FloatArray?, name: String?): String? {
        val count = DEVICE_SPACES[which].components
        if (numbers == null || numbers.size < count || name != null) return null
        val space = device(which)
        track(fill, space)
        if (space.isGray) return null
        // With more numbers than it takes, the last ones count, as Android's and the browsers' viewers read them.
        val colour = if (numbers.size == count) numbers else numbers.copyOfRange(numbers.size - count, numbers.size)
        return gray(fill, space.gray(colour))
    }

    /** `cs` and `CS`. */
    private fun setSpace(fill: Boolean, name: String?): String? {
        if (name == null) return null
        val device = DEVICE_NAMES.indexOf(name)
        val space = if (device >= 0) device(device) else space(name)
        if (space == null) {
            // No such resource: the content is damaged, and its colours are read by their count.
            track(fill, GraySpace.Unknown(0))
            return null
        }
        track(fill, space)
        if (space is GraySpace.Pattern) {
            val base = space.base
            if (base == null || base.isGray || base is GraySpace.None) return keep(name)
            if (!readable(base)) return keep(name).also { failed = true }
            val pattern = patternName ?: grayPattern().also { patternName = it }
            return "/" + pdfName(pattern) + if (fill) " cs" else " CS"
        }
        if (space.isGray || space is GraySpace.None) return keep(name)
        if (!readable(space)) return keep(name).also { failed = true }
        // `cs` alone sets the space's initial colour: full tint for a spot colour, no ink for an
        // ICCBased space of four, black otherwise.
        return gray(fill, space.gray(space.initial))
    }

    /** `sc`, `scn` and their stroke forms, by the tracked space. */
    private fun setColour(fill: Boolean, scn: Boolean, numbers: FloatArray?, name: String?): String? {
        if (numbers == null) return null
        val space = if (fill) this.fill else stroke
        if (space is GraySpace.Pattern) {
            val base = space.base
            if (base == null || base.isGray || !readable(base)) return null
            // A colour base: `cs` was written as the gray pattern space, which takes one number and
            // the pattern's name. With no numbers it is a coloured pattern (or damaged): kept.
            if (!scn || name == null || numbers.isEmpty()) return null
            val gray = (if (numbers.size == base.components) base.gray(numbers) else byCount(numbers))
                ?: return null.also { failed = true }
            return number(gray) + " /" + pdfName(name) + if (fill) " scn" else " SCN"
        }
        // A real space that can't be read: its `cs` was kept (and counted), so this stays with it.
        if (space is GraySpace.Unknown && space.components > 0) return null
        if (space !is GraySpace.Unknown && !readable(space)) return null
        if (numbers.isEmpty()) return null
        if (space !is GraySpace.Unknown && numbers.size == space.components) {
            return if (space.isGray) null else gray(fill, space.gray(numbers))
        }
        // A count that doesn't fit (a damaged stream, a resource that isn't there): read by the
        // count, and never kept when it can be read, since kept it would be read as something else.
        val gray = byCount(numbers) ?: return null.also { failed = true }
        return gray(fill, gray)
    }

    /** One number is a gray, three are RGB, four are CMYK; null for any other count. */
    private fun byCount(numbers: FloatArray): Float? = when (numbers.size) {
        1 -> GrayMath.unit(numbers[0])
        3 -> GrayMath.rgb(numbers[0], numbers[1], numbers[2])
        4 -> GrayMath.cmyk(numbers[0], numbers[1], numbers[2], numbers[3])
        else -> null
    }

    private fun keep(name: String): String? {
        if (name !in DEVICE_NAMES && name != "Pattern") keptNames += name
        return null
    }

    private fun track(fill: Boolean, space: GraySpace) {
        if (fill) this.fill = space else stroke = space
    }

    private fun gray(fill: Boolean, value: Float): String = number(value) + if (fill) " g" else " G"

    /** The device space [which], read through its default space when that is one that fits. */
    private fun device(which: Int): GraySpace = devices[which] ?: run {
        val plain = DEVICE_SPACES[which]
        val found = space(DEVICE_NAMES[which])
        val fits = found != null && found.components == plain.components && found !is GraySpace.Pattern && readable(found)
        val chosen = if (fits) found!! else plain
        devices[which] = chosen
        chosen
    }

    private fun save() {
        val top = saved - 1
        if (top >= 0 && savedFill[top] === fill && savedStroke[top] === stroke && savedRuns[top] < Int.MAX_VALUE) {
            savedRuns[top]++
            return
        }
        if (saved == savedRuns.size) {
            savedFill = savedFill.copyOf(saved * 2)
            savedStroke = savedStroke.copyOf(saved * 2)
            savedRuns = savedRuns.copyOf(saved * 2)
        }
        savedFill[saved] = fill
        savedStroke[saved] = stroke
        savedRuns[saved] = 1
        saved++
    }

    /** A `Q` with nothing saved does nothing. */
    private fun restore() {
        if (saved == 0) return
        val top = saved - 1
        fill = savedFill[top]!!
        stroke = savedStroke[top]!!
        if (--savedRuns[top] == 0) {
            savedFill[top] = null
            savedStroke[top] = null
            saved--
        }
    }

    private companion object {
        const val GRAY = 0
        const val RGB = 1
        const val CMYK = 2
        val DEVICE_NAMES = listOf("DeviceGray", "DeviceRGB", "DeviceCMYK")
        val DEVICE_SPACES = listOf(GraySpace.Gray, GraySpace.Rgb, GraySpace.Cmyk)
        const val NAME_CHARACTERS = "+-_@*$;."
        const val HEX = "0123456789ABCDEF"

        /** Whether a colour in [space] has a gray: not for /None, an unreadable space, or a spot colour whose alternate is one. */
        fun readable(space: GraySpace): Boolean = when (space) {
            is GraySpace.Unknown, is GraySpace.None -> false
            is GraySpace.Pattern -> space.base?.let { readable(it) } ?: false
            is GraySpace.Tint -> space.alternate !is GraySpace.Pattern && readable(space.alternate)
            else -> true
        }

        fun number(gray: Float): String = PdfSyntax.number(GrayMath.unit(gray).toDouble())

        /** A name as PDF syntax writes it, without its slash: bytes outside a safe set as #xx, as PDFBox writes names. */
        fun pdfName(name: String): String {
            val sb = StringBuilder()
            for (b in name.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                val safe = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                    NAME_CHARACTERS.indexOf(c.toChar()) >= 0
                if (safe) sb.append(c.toChar()) else sb.append('#').append(HEX[c ushr 4]).append(HEX[c and 0x0F])
            }
            return sb.toString()
        }
    }
}
