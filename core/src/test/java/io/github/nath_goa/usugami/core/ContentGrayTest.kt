package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentGrayTest {

    /** A spot colour whose full tint is CMYK 0 0.6 1 0 (an orange). */
    private val spot = GraySpace.Tint(1, GraySpace.Cmyk) { floatArrayOf(0f, 0.6f * it[0], it[0], 0f) }

    private val resources = mapOf(
        "Icc" to GraySpace.Rgb,
        "Icc4" to GraySpace.IccCmyk,
        "Spot" to spot,
        "Cal" to GraySpace.CalGray(1.8f),
        "NoInk" to GraySpace.None(1),
        "Odd" to GraySpace.Unknown(3),
        "P" to GraySpace.Pattern(GraySpace.Rgb),
        "PGray" to GraySpace.Pattern(GraySpace.Gray),
        "POdd" to GraySpace.Pattern(GraySpace.Unknown(3)),
    )

    private var patternsMade = 0

    private fun content(
        fill: GraySpace = GraySpace.Gray,
        stroke: GraySpace = GraySpace.Gray,
        defaults: Map<String, GraySpace> = emptyMap(),
    ) = ContentGray(
        space = { name ->
            defaults[name] ?: when (name) {
                "DeviceGray" -> GraySpace.Gray
                "DeviceRGB" -> GraySpace.Rgb
                "DeviceCMYK" -> GraySpace.Cmyk
                "Pattern" -> GraySpace.Pattern(null)
                else -> resources[name]
            }
        },
        grayPattern = { patternsMade++; "UsugamiGrayPattern" },
        fill = fill,
        stroke = stroke,
    )

    /** One operator with its operands, as written in a content stream: "1 0 0 rg", "/P1 scn", "(x) g". */
    private fun ContentGray.op(text: String): String? {
        val parts = text.split(' ')
        val operands = parts.dropLast(1)
        val name = operands.lastOrNull()?.takeIf { it.startsWith("/") }?.substring(1)
        val rest = if (name != null) operands.dropLast(1) else operands
        val numbers = if (rest.all { it.toFloatOrNull() != null }) FloatArray(rest.size) { rest[it].toFloat() } else null
        return rewrite(parts.last(), numbers, name)
    }

    private fun n(gray: Float) = PdfSyntax.number(gray.toDouble())

    @Test
    fun deviceColoursBecomeGrayAndGraysStay() {
        val gray = content()
        assertEquals("0.3008 g", gray.op("1 0 0 rg"))
        assertEquals("0.1133 G", gray.op("0 0 1 RG"))
        assertEquals("0 g", gray.op("0 0 0 rg"))
        assertEquals("1 g", gray.op("1 1 1 rg"))
        assertEquals("0 g", gray.op("0 0 0 1 k"))
        assertEquals("1 G", gray.op("0 0 0 0 K"))
        assertEquals("0.75 G", gray.op("0 0 0 0.25 K"))
        assertTrue(gray.changed)
        assertFalse(gray.failed)

        val plain = content()
        assertNull(plain.op("0.5 g"))
        assertNull(plain.op("0.25 G"))
        assertNull(plain.op("0.5 sc"))
        assertNull(plain.op("/DeviceGray cs"))
        assertNull(plain.op("0.2 0.4 0.6 0.5 re"))
        assertNull(plain.op("BT"))
        assertFalse(plain.changed)
        assertFalse(plain.failed)
        assertTrue(plain.kept.isEmpty())
    }

    @Test
    fun spacesSetByCsGiveTheirInitialColourAndThenTheirColours() {
        val content = content()
        assertEquals("0 g", content.op("/Icc cs"))
        assertEquals(n(150f / 256), content.op("0 1 0 sc")!!.removeSuffix(" g"))
        assertEquals("0.5859 g", content.op("0 1 0 scn"))
        // A spot colour starts at full tint.
        assertEquals("${n(GrayMath.cmyk(0f, 0.6f, 1f, 0f))} G", content.op("/Spot CS"))
        assertEquals("${n(GrayMath.cmyk(0f, 0.3f, 0.5f, 0f))} G", content.op("0.5 SCN"))
        assertEquals("${n(GrayMath.cmyk(0f, 0.3f, 0.5f, 0f))} G", content.op("0.5 SC"))
        // The fill side kept its own space.
        assertEquals("0.3008 g", content.op("1 0 0 sc"))
        assertSame(GraySpace.Rgb, content.fill)
        assertSame(spot, content.stroke)
        // An ICCBased space of four starts with no ink, DeviceCMYK with black.
        assertEquals("1 g", content.op("/Icc4 cs"))
        assertEquals("0 g", content.op("/DeviceCMYK cs"))
        assertEquals("0 g", content.op("/DeviceRGB cs"))
        assertFalse(content.failed)
    }

    @Test
    fun qAndQPutTheSpacesBack() {
        val content = content()
        assertNull(content.op("Q"))
        assertNull(content.op("0.5 sc"))
        assertEquals("0.3008 g", content.op("1 0 0 rg"))
        assertNull(content.op("q"))
        assertEquals("0 g", content.op("0 0 0 1 k"))
        assertEquals("0 g", content.op("0 0 0 1 sc"))
        assertSame(GraySpace.Cmyk, content.fill)
        assertNull(content.op("Q"))
        assertSame(GraySpace.Rgb, content.fill)
        assertEquals("0.1133 g", content.op("0 0 1 sc"))
        assertNull(content.op("Q"))
        assertSame(GraySpace.Rgb, content.fill)
    }

    @Test
    fun deepNestingIsPutBackLevelByLevel() {
        val content = content()
        content.op("1 0 0 rg")
        repeat(100_000) { content.op("q") }
        content.op("/Cal cs")
        content.op("q")
        content.op("/Icc4 CS")
        repeat(3) { content.op("q") }
        repeat(4) { content.op("Q") }
        assertTrue(content.fill is GraySpace.CalGray)
        assertSame(GraySpace.Gray, content.stroke)
        content.op("Q")
        assertSame(GraySpace.Rgb, content.fill)
        repeat(99_999) { content.op("Q") }
        assertSame(GraySpace.Rgb, content.fill)
    }

    @Test
    fun uncolouredPatternsTurnToTheGrayPatternSpace() {
        val content = content()
        assertEquals("/UsugamiGrayPattern cs", content.op("/P cs"))
        assertEquals("0.3008 /Dots scn", content.op("1 0 0 /Dots scn"))
        assertEquals("/UsugamiGrayPattern CS", content.op("/P CS"))
        assertEquals("0.1133 /Dots SCN", content.op("0 0 1 /Dots SCN"))
        // A coloured pattern in that space takes no numbers: kept, and no failure.
        assertNull(content.op("/Tile scn"))
        // Numbers with no pattern name, or sc, which can't paint a pattern: kept.
        assertNull(content.op("1 0 0 scn"))
        assertNull(content.op("1 0 0 /Dots sc"))
        assertFalse(content.failed)
        assertEquals(1, patternsMade)
        // A name that needs escaping comes out as PDF writes it.
        assertEquals("0.3008 /Dots#20#281#29#C3#A9 scn", content.rewrite("scn", floatArrayOf(1f, 0f, 0f), "Dots (1)é"))

        val coloured = content()
        assertNull(coloured.op("/Pattern cs"))
        assertNull(coloured.op("/P1 scn"))
        assertNull(coloured.op("/PGray cs"))
        assertNull(coloured.op("0.5 /P2 scn"))
        assertFalse(coloured.changed)
        assertEquals(setOf("PGray"), coloured.kept)
    }

    @Test
    fun spacesThatCantBeTurnedGrayAreKeptByName() {
        val content = content()
        assertNull(content.op("/Cal cs"))
        assertNull(content.op("0.5 sc"))
        assertNull(content.op("/NoInk CS"))
        assertNull(content.op("1 SCN"))
        assertFalse(content.failed)
        assertFalse(content.changed)
        assertNull(content.op("/Odd cs"))
        assertNull(content.op("1 0 0 sc"))
        assertNull(content.op("0.5 sc"))
        assertTrue(content.failed)
        assertEquals(setOf("Cal", "NoInk", "Odd"), content.kept)

        val unknownBase = content()
        assertNull(unknownBase.op("/POdd cs"))
        assertNull(unknownBase.op("1 0 0 /Dots scn"))
        assertTrue(unknownBase.failed)
        assertEquals(setOf("POdd"), unknownBase.kept)
    }

    @Test
    fun rgSetsDeviceRgbForWhatFollows() {
        val content = content()
        assertEquals("0.3008 g", content.op("1 0 0 rg"))
        assertEquals("0.1133 g", content.op("0 0 1 sc"))
        assertEquals("0 g", content.op("0 0 0 1 k"))
        assertSame(GraySpace.Cmyk, content.fill)
        // So a form drawn here starts in CMYK.
        val form = content(fill = content.fill, stroke = content.stroke)
        assertEquals("1 g", form.op("0 0 0 0 sc"))
    }

    @Test
    fun aCountThatDoesntFitIsReadByTheCount() {
        val content = content()
        assertNull(content.op("/DeviceGray cs"))
        assertEquals("0.3008 g", content.op("1 0 0 sc"))
        assertEquals("0 g", content.op("0 0 0 1 sc"))
        assertEquals("0 g", content.op("/Icc cs"))
        assertEquals("0.5 g", content.op("0.5 sc"))
        assertFalse(content.failed)
        assertNull(content.op("0.5 0.5 sc"))
        assertTrue(content.failed)

        // A colour space that isn't there: no failure for the cs, colours read by their count.
        val missing = content()
        assertNull(missing.op("/Missing cs"))
        assertFalse(missing.failed)
        assertEquals("0.5 g", missing.op("0.5 scn"))
        assertEquals("0.3008 g", missing.op("1 0 0 scn"))
        assertNull(missing.op("/P1 scn"))
        assertFalse(missing.failed)
        assertTrue(missing.kept.isEmpty())
    }

    @Test
    fun operandsAnOperatorDoesntTakeKeepIt() {
        val content = content()
        assertNull(content.op("1 0 rg"))
        assertNull(content.op("(x) 1 0 0 rg"))
        assertNull(content.op("1 0 0 /X rg"))
        assertNull(content.op("g"))
        assertNull(content.op("cs"))
        assertNull(content.op("(x) sc"))
        assertFalse(content.failed)
        assertFalse(content.changed)
        // Kept operators change no space.
        assertSame(GraySpace.Gray, content.fill)
        // More numbers than it takes: the last ones count, as the viewers read them.
        assertEquals("0.3008 g", content.op("5 1 0 0 rg"))
    }

    @Test
    fun aFormStartsWithTheSpacesOfWhatDrawsIt() {
        val form = content(fill = GraySpace.Rgb)
        assertEquals("0.1133 g", form.op("0 0 1 sc"))
        assertNull(form.op("0.5 SC"))
        val patterned = content(fill = GraySpace.Pattern(GraySpace.Rgb))
        assertEquals("0.3008 /Dots scn", patterned.op("1 0 0 /Dots scn"))
    }

    @Test
    fun deviceColoursAreReadThroughTheirDefaultSpaces() {
        val calibrated = content(defaults = mapOf("DeviceRGB" to GraySpace.CalRgb(floatArrayOf(1f, 1f, 1f))))
        val expected = n(GrayMath.rgb(GrayMath.gamma(0.5f, 1f), GrayMath.gamma(0.5f, 1f), GrayMath.gamma(0.5f, 1f)))
        assertEquals("$expected g", calibrated.op("0.5 0.5 0.5 rg"))
        assertEquals("$expected g", calibrated.op("0.5 0.5 0.5 sc"))
        // A DefaultCMYK of ICC CMYK starts with no ink.
        assertEquals("1 g", content(defaults = mapOf("DeviceCMYK" to GraySpace.IccCmyk)).op("/DeviceCMYK cs"))
        // A spot colour as DefaultGray: g is read through it and written as the gray it gives.
        val spotGray = content(defaults = mapOf("DeviceGray" to spot))
        assertEquals("${n(GrayMath.cmyk(0f, 0.6f, 1f, 0f))} g", spotGray.op("1 g"))
        // A default space that doesn't fit, or can't be read, is passed over.
        assertEquals("0.3008 g", content(defaults = mapOf("DeviceRGB" to GraySpace.Gray)).op("1 0 0 rg"))
        val unreadable = content(defaults = mapOf("DeviceRGB" to GraySpace.Unknown(3)))
        assertEquals("0.3008 g", unreadable.op("1 0 0 rg"))
        assertEquals("0 g", unreadable.op("/DeviceRGB cs"))
        assertFalse(unreadable.failed)
    }

    @Test
    fun valuesOutOfRangeAndDamagedTintsStillWriteNumbersInRange() {
        val content = content()
        assertEquals("1 g", content.op("2 2 2 rg"))
        assertEquals("0 G", content.op("-1 -1 -1 RG"))
        val broken = ContentGray({ GraySpace.Tint(1, GraySpace.Gray) { floatArrayOf(Float.NaN) } }, { "G0" })
        assertEquals("0 g", broken.op("/S cs"))
        assertEquals("0 g", broken.op("0.5 sc"))
    }
}
