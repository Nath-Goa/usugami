package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/** Fitting a translation in its block's place, with a measurer whose letters and spaces are all half the type's size wide. */
class TextFitTest {
    /** Lines are 1.2 times the type's size apart, as Android's default line spacing about gives. */
    private val lineHeight = 1.2f

    /** Wraps [text] at [width] word by word, as StaticLayout does with every glyph half the size wide; a word wider than the line takes as many lines as it needs. */
    private fun lines(text: String, size: Float, width: Float): Int {
        val glyph = size / 2
        var lines = 1
        var used = -1f
        for (word in text.split(' ').filter(String::isNotEmpty)) {
            val wide = word.length * glyph
            used = if (used < 0f) wide else if (used + glyph + wide <= width + 0.001f) used + glyph + wide else wide.also { lines++ }
            if (used > width + 0.001f) {
                val over = ceil(used / width).toInt()
                lines += over - 1
                used -= (over - 1) * width
            }
        }
        return lines
    }

    private val measurer = TextMeasurer { text, size, width -> if (width <= 0f) 1e9f else lines(text, size, width) * lineHeight * size }

    /** A block of [lines] lines with the box given, 10-point type. */
    private fun block(left: Float, top: Float, right: Float, bottom: Float, lines: Int = 1, align: BlockAlign = BlockAlign.START, rtl: Boolean = false) =
        SourceBlock(List(lines) { SourceLine("line", left, top, right, bottom, 10f) }, "text", left, top, right, bottom, 10f, false, rtl, align)

    private fun assertArea(expected: BlockArea, actual: BlockArea, within: Float = 1e-3f) {
        assertEquals("left", expected.left, actual.left, within)
        assertEquals("top", expected.top, actual.top, within)
        assertEquals("right", expected.right, actual.right, within)
        assertEquals("bottom", expected.bottom, actual.bottom, within)
    }

    @Test fun aTranslationThatFitsIsLeftAsItIs() {
        // Two lines' room, "Hello world" takes one.
        val paragraph = BlockArea(50f, 100f, 150f, 124f)
        val fit = TextFit.fit("Hello world", 10f, paragraph, BlockArea(50f, 100f, 150f, 300f), measurer)
        assertEquals(Fit(10f, paragraph, false), fit)
        assertSame(paragraph, fit.area)
        // A single line's box is shorter than a line of the layout (10 points against 12): one line still fits.
        val line = BlockArea(50f, 100f, 150f, 110f)
        assertSame(line, TextFit.fit("Hello world", 10f, line, BlockArea(0f, 100f, 500f, 110f), measurer).area)
    }

    @Test fun aLongerParagraphGrowsDownAsFarAsItNeeds() {
        // Two lines' room, 100 points wide; the translation takes three lines at its size.
        val area = BlockArea(50f, 100f, 150f, 124f)
        val text = "aaaaa bbbbb ccccc ddddd eeeee fffff ggg"
        assertEquals(3, lines(text, 10f, 100f))
        val fit = TextFit.fit(text, 10f, area, BlockArea(50f, 100f, 150f, 200f), measurer)
        assertEquals(10f, fit.size, 0f)
        assertFalse(fit.overflow)
        assertArea(BlockArea(50f, 100f, 150f, 136f), fit.area)
    }

    @Test fun aLongerLineGrowsTowardsItsEnd() {
        val area = BlockArea(50f, 100f, 100f, 112f)
        // 85 points wide at 10 points.
        val fit = TextFit.fit("Hello world again", 10f, area, BlockArea(50f, 100f, 300f, 112f), measurer)
        assertEquals(10f, fit.size, 0f)
        assertFalse(fit.overflow)
        assertEquals(50f, fit.area.left, 0f)
        assertEquals(135f, fit.area.right, 0.25f)
        assertEquals(1, lines("Hello world again", 10f, fit.area.width))
    }

    @Test fun withoutRoomItShrinksInStepsOfSevenPercent() {
        val area = BlockArea(50f, 100f, 150f, 124f)
        // Nine words: three lines at 10 points down to 6.96, two from 6.47 (10 × 0.93⁶) on.
        val text = "aaaaa bbbbb ccccc ddddd eeeee fffff ggggg hhhhh iiiii"
        val fit = TextFit.fit(text, 10f, area, area, measurer)
        var expected = 10f
        repeat(6) { expected *= TextFit.SHRINK_STEP }
        assertEquals(expected, fit.size, 1e-5f)
        assertFalse(fit.overflow)
        assertArea(area, fit.area)
        // The step before it didn't fit.
        assertTrue(lines(text, expected / TextFit.SHRINK_STEP, 100f) * lineHeight * expected / TextFit.SHRINK_STEP > area.height)
    }

    @Test fun itGrowsAllItMayBeforeShrinking() {
        val area = BlockArea(50f, 100f, 150f, 124f)
        // Room for a third line, but the text needs four at 10 points.
        val text = "aaaaa bbbbb ccccc ddddd eeeee fffff ggggg hhhhh iiiii jjjjj"
        assertEquals(4, lines(text, 10f, 100f))
        val fit = TextFit.fit(text, 10f, area, BlockArea(50f, 100f, 150f, 136f), measurer)
        assertFalse(fit.overflow)
        // At 9.3 points it still takes four lines; at 8.65 (10 × 0.93²) three, 31.1 points of the 36 it may have.
        assertEquals(10f * TextFit.SHRINK_STEP * TextFit.SHRINK_STEP, fit.size, 1e-5f)
        assertEquals(3, lines(text, fit.size, 100f))
        assertArea(BlockArea(50f, 100f, 150f, 100f + 3 * lineHeight * fit.size), fit.area)
    }

    @Test fun neverSmallerThanTheMinimumAndThenItOverflows() {
        val area = BlockArea(50f, 100f, 150f, 112f)
        val long = "word ".repeat(200)
        val fit = TextFit.fit(long, 10f, area, BlockArea(50f, 100f, 150f, 150f), measurer)
        assertTrue(fit.overflow)
        assertEquals(5.5f, fit.size, 1e-5f)
        // With all the room it had.
        assertArea(BlockArea(50f, 100f, 150f, 150f), fit.area)
        // 8 × 0.55 would be 4.4: never below 5 points, and never above the block's own size.
        assertEquals(5f, TextFit.fit(long, 8f, area, area, measurer).size, 0f)
        assertEquals(4f, TextFit.fit(long, 4f, area, area, measurer).size, 0f)
        assertEquals(7f, TextFit.fit(long, 10f, area, area, measurer, minScale = 0.7f).size, 1e-5f)
    }

    @Test fun limitsStopShortOfNeighboursAndTheMargins() {
        val page = 600f to 800f
        val line = block(100f, 100f, 200f, 112f)
        val right = block(300f, 95f, 400f, 115f)
        val lower = block(300f, 200f, 400f, 212f)
        // To the right, 6 points short of the block beside it; the one lower down doesn't count, nor the line itself.
        assertArea(BlockArea(100f, 100f, 294f, 112f), TextFit.limits(line, listOf(line, right, lower), page.first, page.second))
        // Alone, up to the margin: 4 % of 600 points.
        assertArea(BlockArea(100f, 100f, 576f, 112f), TextFit.limits(line, emptyList(), page.first, page.second))
        // Right-aligned: to the left.
        val left = block(20f, 98f, 60f, 110f)
        assertArea(BlockArea(66f, 100f, 200f, 112f), TextFit.limits(line.copy(align = BlockAlign.END), listOf(left, right), page.first, page.second))
        // A paragraph keeps its width and grows down to 6 points above the block under it, not one beside.
        val paragraph = block(100f, 100f, 300f, 160f, lines = 4)
        val under = block(150f, 200f, 250f, 220f)
        val beside = block(350f, 170f, 450f, 190f)
        assertArea(BlockArea(100f, 100f, 300f, 194f), TextFit.limits(paragraph, listOf(beside, under), page.first, page.second))
        // Alone, down to the bottom margin: 4 % of 800 points.
        assertArea(BlockArea(100f, 100f, 300f, 768f), TextFit.limits(paragraph, emptyList(), page.first, page.second))
        // A block right under it leaves no room, and a small page's margin is 12 points.
        assertArea(BlockArea(100f, 100f, 300f, 160f), TextFit.limits(paragraph, listOf(block(100f, 162f, 300f, 174f)), page.first, page.second))
        assertArea(BlockArea(10f, 10f, 88f, 20f), TextFit.limits(block(10f, 10f, 40f, 20f), emptyList(), 100f, 100f))
    }

    @Test fun rightToLeftLinesGrowToTheLeft() {
        val line = block(330f, 100f, 400f, 112f, rtl = true)
        val limits = TextFit.limits(line, emptyList(), 600f, 800f)
        assertArea(BlockArea(24f, 100f, 400f, 112f), limits)
        // 85 points wide, in a box of 70.
        val fit = TextFit.fit("Hello world again", 10f, BlockArea(330f, 100f, 400f, 112f), limits, measurer)
        assertEquals(400f, fit.area.right, 0f)
        assertEquals(315f, fit.area.left, 0.25f)
        // An end-aligned right-to-left line grows to the right.
        assertArea(BlockArea(330f, 100f, 576f, 112f), TextFit.limits(line.copy(align = BlockAlign.END), emptyList(), 600f, 800f))
    }

    @Test fun centredLinesGrowBothWaysAlike() {
        val line = block(250f, 100f, 350f, 112f, align = BlockAlign.CENTER)
        val limits = TextFit.limits(line, emptyList(), 600f, 800f)
        assertArea(BlockArea(24f, 100f, 576f, 112f), limits)
        // 160 points wide: 30 more on each side.
        val text = "aaaaa bbbbb ccccc ddddd eeeee ff"
        val fit = TextFit.fit(text, 10f, BlockArea(250f, 100f, 350f, 112f), limits, measurer)
        assertEquals(10f, fit.size, 0f)
        assertEquals(220f, fit.area.left, 0.25f)
        assertEquals(380f, fit.area.right, 0.25f)
        assertEquals(250f - fit.area.left, fit.area.right - 350f, 1e-3f)
        // With a neighbour 20 points to its left, what is left over goes right.
        val near = TextFit.limits(line, listOf(block(200f, 100f, 230f, 112f)), 600f, 800f)
        val lopsided = TextFit.fit(text, 10f, BlockArea(250f, 100f, 350f, 112f), near, measurer)
        assertEquals(236f, lopsided.area.left, 0f)
        assertEquals(396f, lopsided.area.right, 0.25f)
    }

    @Test fun oddNumbersNeverThrowOrRunOn() {
        val area = BlockArea(50f, 100f, 150f, 112f)
        assertTrue(TextFit.fit("text", Float.NaN, area, area, measurer).overflow)
        assertTrue(TextFit.fit("text", -3f, area, area, measurer).overflow)
        val nan = BlockArea(Float.NaN, 100f, 150f, 112f)
        assertSame(nan, TextFit.fit("text", 10f, nan, area, measurer).area)
        // A box of no size with no room: the smallest size, overflowing.
        val point = BlockArea(50f, 100f, 50f, 100f)
        val none = TextFit.fit("text", 10f, point, point, measurer)
        assertTrue(none.overflow)
        assertEquals(5.5f, none.size, 1e-5f)
        assertArea(point, none.area)
        // Limits that aren't numbers give no room; a scale that isn't one is the usual 0.55.
        val nanLimits = BlockArea(Float.NaN, Float.NaN, Float.POSITIVE_INFINITY, Float.NaN)
        val cramped = TextFit.fit("word ".repeat(50), 10f, area, nanLimits, measurer, minScale = Float.NaN)
        assertTrue(cramped.overflow)
        assertEquals(5.5f, cramped.size, 1e-5f)
        assertArea(area, cramped.area)
        // A measurer that answers NaN: nothing fits.
        assertTrue(TextFit.fit("text", 10f, area, area, { _, _, _ -> Float.NaN }).overflow)
        // A block whose box isn't numbers grows nowhere; a page without a size gives no margin to grow to.
        assertTrue(TextFit.limits(block(Float.NaN, 0f, 10f, 10f), emptyList(), 600f, 800f).left.isNaN())
        assertArea(BlockArea(100f, 100f, 200f, 112f), TextFit.limits(block(100f, 100f, 200f, 112f), emptyList(), Float.NaN, 0f))
        assertArea(BlockArea(100f, 100f, 200f, 160f), TextFit.limits(block(100f, 100f, 200f, 160f, lines = 3), emptyList(), 600f, -1f))
    }
}
