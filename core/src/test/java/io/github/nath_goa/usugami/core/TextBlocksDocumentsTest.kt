package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Grouping lines into paragraphs on pages laid out as letters, invoices and articles are, measured
 * as Translate PDF reads them: Times-like type 11 points tall, glyph boxes 1.1 sizes tall, lines
 * 1.25 sizes apart, on an A4 page.
 */
class TextBlocksDocumentsTest {
    private val pageWidth = 595f
    private val pageHeight = 842f

    private fun group(lines: List<SourceLine>) = TextBlocks.group(lines, pageWidth, pageHeight)

    private fun texts(blocks: List<SourceBlock>) = blocks.map { it.text }

    /** A line of [text] from [left] to [right], its box's top at [top]. */
    private fun line(text: String, left: Float, top: Float, right: Float, size: Float = 11f) =
        SourceLine(text, left, top, right, top + 1.1f * size, size)

    /** [rows] as the lines of a paragraph from [left], 1.25 sizes apart from [top], each as wide as given (its last shorter). */
    private fun paragraph(rows: List<Pair<String, Float>>, left: Float, top: Float, size: Float = 11f) =
        rows.mapIndexed { i, (text, width) -> line(text, left, top + i * 1.25f * size, left + width, size) }

    @Test fun shortParagraphsWithRoomBetweenThemStayApart() {
        // A letter: its subject, then paragraphs of two lines, the last nearly full, 8 points more
        // apart than their lines (1.98 sizes, bottom to bottom), and its signature far below. As
        // many steps between paragraphs and to what is further below as within them: the usual step
        // is still the one within a paragraph, and the paragraphs stay apart.
        val subject = line("Subject: your bread order for the month of December", 56f, 259f, 335f)
        val greeting = line("Dear Madam,", 56f, 289f, 112f)
        var top = 311f
        val paragraphs = listOf(
            listOf("Thank you for your order of 28 October and for the trust you have placed" to 483f, "in our bakery for more than ten years now, which we value greatly." to 466f),
            listOf("Deliveries will take place every day before half past six, including on" to 483f, "Sundays and public holidays, at the service entrance as usual." to 352f),
            listOf("Please find enclosed our December catalogue with the Yule logs and the" to 483f, "kings' cakes, which can be ordered two days in advance." to 451f),
        ).map { rows -> paragraph(rows, 56f, top).also { top = it.last().top + 1.25f * 11f + 8f } }
        val closing = line("Yours faithfully, with our best wishes for the season.", 56f, top + 6f, 408f)
        val signature = line("Jean Martin", 360f, 629f, 418f)
        val blocks = group(listOf(subject, greeting) + paragraphs.flatten() + closing + signature)
        assertEquals(
            (listOf(listOf(subject), listOf(greeting)) + paragraphs + listOf(listOf(closing), listOf(signature))).map { lines -> lines.joinToString(" ") { it.text } },
            texts(blocks),
        )
    }
}
