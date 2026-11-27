package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Grouping a page's lines into paragraphs, on pages drawn here: every glyph is half the type's
 * size wide (a Chinese character a whole size), a line's box is 1.2 sizes tall and lines are 1.3
 * sizes apart, on a page 600 × 800 points big.
 */
class TextBlocksTest {
    private val pageWidth = 600f
    private val pageHeight = 800f

    private fun group(lines: List<SourceLine>) = TextBlocks.group(lines, pageWidth, pageHeight)

    /** A line of [text] from [left], as wide as its letters unless [width] is given. */
    private fun line(text: String, left: Float, top: Float, size: Float = 10f, width: Float = text.length * size / 2, bold: Boolean = false) =
        SourceLine(text, left, top, left + width, top + 1.2f * size, size, bold)

    /** A right-to-left line of [text] ending at [right]. */
    private fun rtlLine(text: String, right: Float, top: Float, size: Float = 10f, width: Float = text.length * size / 2) =
        SourceLine(text, right - width, top, right, top + 1.2f * size, size, rtl = true)

    /**
     * [text] wrapped word by word into a column [width] points wide starting at [start] (its right
     * edge for [rtl] text), its first line indented by [indent], lines [step] apart from [top]. A
     * [justify]d paragraph's lines but the last are as wide as the column; [justifyLast] makes the
     * last one so too.
     */
    private fun paragraph(
        text: String,
        start: Float,
        top: Float,
        width: Float,
        size: Float = 10f,
        step: Float = 1.3f * size,
        indent: Float = 0f,
        justify: Boolean = false,
        justifyLast: Boolean = false,
        rtl: Boolean = false,
    ): List<SourceLine> {
        val glyph = size / 2
        val rows = ArrayList<String>()
        var current = ""
        for (word in text.split(' ')) {
            val room = (if (rows.isEmpty()) width - indent else width) / glyph
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (candidate.length <= room || current.isEmpty()) {
                current = candidate
            } else {
                rows += current
                current = word
            }
        }
        rows += current
        return rows.mapIndexed { i, row ->
            val shift = if (i == 0) indent else 0f
            val full = justify && i < rows.size - 1 || justifyLast
            val wide = if (full) width - shift else row.length * glyph
            val y = top + i * step
            if (rtl) SourceLine(row, start - shift - wide, y, start - shift, y + 1.2f * size, size, rtl = true) else SourceLine(row, start + shift, y, start + shift + wide, y + 1.2f * size, size)
        }
    }

    private fun texts(blocks: List<SourceBlock>) = blocks.map { it.text }

    @Test fun aParagraphOfFiveLinesIsOneBlock() {
        val lines = paragraph(FIVE, 72f, 100f, 451f)
        assertEquals(5, lines.size)
        val blocks = group(lines)
        assertEquals(listOf(FIVE), texts(blocks))
        val block = blocks.single()
        assertEquals(lines, block.lines)
        assertEquals(BlockAlign.START, block.align)
        assertEquals(10f, block.size, 0f)
        assertEquals(72f, block.left, 0f)
        assertEquals(100f, block.top, 0f)
        assertEquals(lines.maxOf { it.right }, block.right, 0f)
        assertEquals(100f + 4 * 13f + 12f, block.bottom, 1e-4f)
    }

    @Test fun aShortLastLineEndsAParagraph() {
        val first = paragraph(NEWS, 50f, 100f, 240f)
        val second = paragraph(MORE, 50f, 100f + first.size * 13f, 240f)
        assertTrue(first.last().right < first[0].right - 100f)
        assertEquals(listOf(NEWS, MORE), texts(group(first + second)))
    }

    @Test fun aHeadingIsABlockOfItsOwn() {
        val body = paragraph(TOOLS, 72f, 100f, 451f)
        // Bigger.
        assertEquals(listOf("Getting started", TOOLS), texts(group(listOf(line("Getting started", 72f, 76f, size = 16f)) + body)))
        // The same size but bold.
        assertEquals(listOf("Getting started", TOOLS), texts(group(listOf(line("Getting started", 72f, 87f, bold = true)) + body)))
        // The same size and weight, but short: it ends where the paragraph that follows doesn't.
        assertEquals(listOf("Getting started", TOOLS), texts(group(listOf(line("Getting started", 72f, 87f)) + body)))
    }

    /** A centred heading, two columns read side by side, and a paragraph across the page under them. */
    private fun twoColumnPage(): List<SourceLine> {
        val heading = line("Two columns of news", 214.5f, 50f, size = 18f)
        val news = paragraph(NEWS, 50f, 90f, 240f)
        val more = paragraph(MORE, 50f, 90f + news.size * 13f, 240f)
        val about = paragraph(ABOUT, 310f, 90f, 240f)
        val wide = paragraph(WIDE, 50f, 90f + 7 * 13f + 15f, 500f)
        return listOf(heading) + news + more + about + wide
    }

    @Test fun columnsAreReadSideBySideAndNeverJoin() {
        val blocks = group(twoColumnPage())
        assertEquals(listOf("Two columns of news", NEWS, ABOUT, MORE, WIDE), texts(blocks))
        assertEquals(BlockAlign.CENTER, blocks[0].align)
        // The left column's blocks keep to the left column, the right one's to the right.
        for (block in listOf(blocks[1], blocks[3])) assertTrue(block.lines.all { it.left == 50f && it.right <= 290f })
        assertTrue(blocks[2].lines.all { it.left >= 310f })
        assertEquals(listOf(BlockAlign.START, BlockAlign.START, BlockAlign.START, BlockAlign.START), blocks.drop(1).map { it.align })
    }

    @Test fun linesGivenInAnyOrderMakeTheSameBlocks() {
        val page = twoColumnPage()
        val expected = group(page)
        for (seed in 1L..5L) assertEquals(expected, group(page.shuffled(Random(seed))))
    }

    @Test fun aTablesCellsStayApart() {
        val rows = listOf(
            listOf("Item", "Quantity", "Unit price", "Total"),
            listOf("Apples", "12", "0.50", "6.00"),
            listOf("Oranges", "8", "0.75", "6.00"),
        )
        val columns = listOf(50f, 200f, 320f, 450f)
        val lines = rows.flatMapIndexed { r, cells -> cells.mapIndexed { c, cell -> line(cell, columns[c], 100f + r * 13f) } }
        // Each cell is a block of its own, row by row, also down a column: "Apples" doesn't go on from "Item".
        assertEquals(rows.flatten(), texts(group(lines)))
    }

    @Test fun eachItemOfAListIsABlock() {
        val bullets = listOf(
            line("• Scan documents with the camera", 60f, 100f),
            line("• Translate a PDF into another language, keeping the layout of", 60f, 113f),
            // The item's second line, under its text rather than its bullet.
            line("every page as it was", 70f, 126f),
            line("• Sign documents", 60f, 139f),
        )
        assertEquals(
            listOf("• Scan documents with the camera", "• Translate a PDF into another language, keeping the layout of every page as it was", "• Sign documents"),
            texts(group(bullets)),
        )
        val numbered = listOf(line("1. Open the PDF you want to read", 60f, 100f), line("2. Choose the language", 60f, 113f), line("3. Tap Translate PDF", 60f, 126f))
        assertEquals(numbered.map { it.text }, texts(group(numbered)))
        // A bullet drawn apart from its text, as a line of its own: the text beside it starts a block
        // too, even where it would go on from the item above, whose line is about as long.
        val apart = listOf(
            line("•", 60f, 100f), line("Scan documents with the camera, sharp and straight", 75f, 100f),
            line("•", 60f, 113f), line("Translate a PDF into another language, keeping the layout of", 75f, 113f),
            line("every page as it was", 75f, 126f),
            line("•", 60f, 139f), line("Sign documents", 75f, 139f),
        )
        assertEquals(
            listOf(
                "•", "Scan documents with the camera, sharp and straight", "•",
                "Translate a PDF into another language, keeping the layout of every page as it was", "•", "Sign documents",
            ),
            texts(group(apart)),
        )
        // What only looks like a number or a letter counting items doesn't start a block.
        val prose = listOf(
            line("The recipe needs flour, sugar and eggs, and of the flour it needs about", 72f, 100f, width = 451f),
            line("3.5 kilograms, which is more than most kitchens keep, as J. Smith wrote on", 72f, 113f, width = 451f),
            line("p. 12 of the book; anyone who has been ill. or away can still follow it.", 72f, 126f),
        )
        assertEquals(listOf(prose.joinToString(" ") { it.text }), texts(group(prose)))
    }

    @Test fun aRaggedLineEndsShortWhereTheNextWordWouldNotFit() {
        // The first line ends 7.5 sizes short of the second, but the second's first word is longer than that.
        val lines = listOf(
            line("Usugami keeps every page as it was, and", 50f, 100f),
            line("internationalization changes none of the pages at all.", 50f, 113f),
        )
        assertEquals(75f, lines[1].right - lines[0].right, 0f)
        assertEquals(listOf(lines.joinToString(" ") { it.text }), texts(group(lines)))
        // Where even that word would have fitted, the line was a paragraph's last.
        val ended = listOf(line("Usugami keeps pages.", 50f, 100f), lines[1])
        assertEquals(ended.map { it.text }, texts(group(ended)))
    }

    @Test fun aCentredTitleOfTwoLinesIsOneCentredBlock() {
        val title = listOf(line("Annual Report", 241.5f, 80f, size = 18f), line("of the Example Company", 201f, 103.4f, size = 18f))
        val block = group(title).single()
        assertEquals("Annual Report of the Example Company", block.text)
        assertEquals(BlockAlign.CENTER, block.align)
    }

    @Test fun aWordBrokenAtALinesEndIsJoinedAgain() {
        val lines = listOf(
            line("In this paragraph the word at the end of the first line is an exam-", 72f, 100f, width = 451f),
            line("ple of a word broken in two, while a name such as Jean-", 72f, 113f, width = 451f),
            line("Pierre keeps its hyphen, and so does COVID-", 72f, 126f, width = 451f),
            line("19, the name of an illness that everyone knows. Keep it strictly confi­", 72f, 139f, width = 451f),
            line("dential. It was late -", 72f, 152f, width = 451f),
            line("too late to go.", 72f, 165f),
        )
        assertEquals(
            "In this paragraph the word at the end of the first line is an example of a word broken in two, while a name such as " +
                "Jean-Pierre keeps its hyphen, and so does COVID-19, the name of an illness that everyone knows. Keep it strictly " +
                "confidential. It was late - too late to go.",
            group(lines).single().text,
        )
    }

    @Test fun aHyphenStandingForAWordsEndKeepsItsSpace() {
        // "Vor- und Nachname": the hyphen stands for "-name", and the space after it stays. A word
        // broken at a line's end is still joined, also before its last two letters ("Kon-" "to"),
        // and a name keeps its hyphen.
        fun joined(vararg rows: String) =
            group(rows.mapIndexed { i, row -> line(row, 72f, 100f + 13f * i, width = if (i < rows.size - 1) 451f else row.length * 5f) }).single().text
        assertEquals(
            "Bitte tragen Sie hier Ihren vollständigen Vor- und Nachnamen sowie die Nummer Ihres Konto ein, wie sie auf der Karte steht.",
            joined("Bitte tragen Sie hier Ihren vollständigen Vor-", "und Nachnamen sowie die Nummer Ihres Kon-", "to ein, wie sie auf der Karte steht."),
        )
        assertEquals(
            "The plan sets out what the city will do in the short- and long-term, with an example of each step, as Jean-Pierre Martin said.",
            joined("The plan sets out what the city will do in the short-", "and long-term, with an exam-", "ple of each step, as Jean-", "Pierre Martin said."),
        )
        assertEquals(
            "Selon votre contrat, les examens pré- et post-opératoires sont pris en charge par l'hôpital, qui les facture à votre caisse.",
            joined("Selon votre contrat, les examens pré-", "et post-opératoires sont pris en charge par l'hôpital, qui les fac-", "ture à votre caisse."),
        )
    }

    @Test fun chineseLinesAreJoinedWithoutASpace() {
        val lines = listOf(
            line("这是一个关于翻译的段落，它的第一行在这里结束", 72f, 100f, width = 451f),
            line("第二行接着写，没有空格。", 72f, 113f, width = 120f),
        )
        assertEquals("这是一个关于翻译的段落，它的第一行在这里结束第二行接着写，没有空格。", group(lines).single().text)
    }

    @Test fun rightToLeftParagraphsAreReadFromTheRight() {
        val first = paragraph(HEBREW, 550f, 100f, 240f, rtl = true)
        val second = paragraph(HEBREW_MORE, 550f, 100f + first.size * 13f, 240f, rtl = true)
        assertEquals(3, first.size)
        val blocks = group(first + second)
        assertEquals(listOf(HEBREW, HEBREW_MORE), texts(blocks))
        // Lined up on the right: their start.
        for (block in blocks) {
            assertTrue(block.rtl)
            assertEquals(BlockAlign.START, block.align)
        }
        // Two columns of right-to-left text with the same tops: the right one first.
        val left = paragraph(HEBREW_MORE, 290f, 100f, 240f, rtl = true)
        assertEquals(listOf(HEBREW, HEBREW_MORE), texts(group(left + first)))
    }

    @Test fun aRunningHeadAndAFooterAreBlocksOfTheirOwn() {
        val head = line("Usugami Quarterly Report, Spring 2026", 72f, 30f, size = 8f)
        val body = paragraph(FIVE, 72f, 72f, 451f)
        val note = line("Confidential", 72f, 780f, size = 8f)
        val number = line("Page 3 of 12", 276f, 780f, size = 8f)
        val blocks = group(listOf(number, head, note) + body)
        assertEquals(listOf(head.text, FIVE, note.text, number.text), texts(blocks))
        assertEquals(listOf(BlockAlign.START, BlockAlign.START, BlockAlign.START, BlockAlign.CENTER), blocks.map { it.align })
    }

    @Test fun anIndentStartsTheNextParagraphOfJustifiedText() {
        // The first paragraph's last line is short.
        val five = paragraph(FIVE, 72f, 100f, 451f, justify = true)
        val tools = paragraph(TOOLS, 72f, 165f, 451f, indent = 15f, justify = true)
        assertEquals(listOf(FIVE, TOOLS), texts(group(five + tools)))
        // Its last line is as long as the others: only the indent of the next one tells them apart.
        val full = paragraph(FIVE, 72f, 100f, 451f, justifyLast = true)
        val blocks = group(full + tools)
        assertEquals(listOf(FIVE, TOOLS), texts(blocks))
        // The indented first line belongs to its paragraph, which is left-aligned.
        assertEquals(2, blocks[1].lines.size)
        assertEquals(BlockAlign.START, blocks[1].align)
    }

    @Test fun aHangingIndentKeepsAnEntryTogether() {
        // A list of references: each entry's lines after the first are indented.
        fun entry(text: String, top: Float) = listOf(
            line(text.substringBefore('|'), 72f, top, width = 451f),
            line(text.substringAfter('|').substringBefore('#'), 108f, top + 13f, width = 415f),
            line(text.substringAfter('#'), 108f, top + 26f, width = 415f),
        )
        val first = "Smith, J. (2020). A very long title of a book about paper|that goes on over three lines of the list#of references, all of them full."
        val second = "Jones, A. (2021). Another book with a title just as long|which also needs more than one line in the#list, all of them full as well."
        val blocks = group(entry(first, 100f) + entry(second, 139f))
        assertEquals(listOf(first.replace('|', ' ').replace('#', ' '), second.replace('|', ' ').replace('#', ' ')), texts(blocks))
        assertEquals(listOf(BlockAlign.START, BlockAlign.START), blocks.map { it.align })
    }

    @Test fun aSuperscriptDoesNotBreakItsParagraph() {
        val body = paragraph(ABOUT, 72f, 100f, 451f, justify = true).toMutableList()
        // The second line's box reaches higher, for a superscript inside it.
        body[1] = body[1].copy(top = body[1].top - 2f)
        // A footnote's number set apart, small and raised, in the first line.
        val mark = SourceLine("1", 300f, 98f, 303f, 105.2f, 6f)
        val blocks = group(body + mark)
        assertEquals(listOf(ABOUT, "1"), texts(blocks))
        assertEquals(4, blocks[0].lines.size)
    }

    @Test fun aBlockDoesNotGoOnPastALineUnderIt() {
        // A note in small print under a line of the body, in the way of the next line, which is close enough to go on from it.
        val above = line("A line of the body text that is long enough to go on.", 72f, 100f, width = 451f)
        val small = line("(a note in small print)", 150f, 110f, size = 6f)
        val below = line("Another line of body text, set further down.", 72f, 117f, width = 451f)
        assertEquals(listOf(above.text, small.text, below.text), texts(group(listOf(above, small, below))))
        // A subscript a little lower than its line isn't under it.
        val subscript = SourceLine("2", 200f, 106f, 203f, 113.2f, 6f)
        assertEquals(listOf("${above.text} ${below.text}", "2"), texts(group(listOf(above, subscript, below))))
    }

    @Test fun doubleSpacedParagraphsKeepTheirLines() {
        // Lines 2.3 sizes apart (more than 1.8), paragraphs told apart by their short last lines and indents.
        var top = 100f
        val page = ArrayList<SourceLine>()
        for (text in listOf(ESSAY1, ESSAY2, ESSAY3)) {
            val lines = paragraph(text, 72f, top, 300f, step = 23f, indent = 20f)
            page += lines
            top += lines.size * 23f
        }
        assertEquals(listOf(ESSAY1, ESSAY2, ESSAY3), texts(group(page)))
        // On a page spaced as usual, a line that far below the one before it starts a new block.
        val five = paragraph(FIVE, 72f, 100f, 451f, justifyLast = true)
        val below = line("This line is further down the page than the next line of a paragraph.", 72f, five.last().top + 23f)
        assertEquals(listOf(FIVE, below.text), texts(group(five + below)))
    }

    @Test fun alignmentComesFromTheLinesAndThePage() {
        val date = line("25 November 2026", 443f, 60f)
        val address = listOf(
            line("Jane Smith", 473f, 90f),
            line("12 Long Street, Springfield", 388f, 103f),
            line("Springfield, IL 62701", 418f, 116f),
        )
        val body = paragraph(FIVE, 72f, 160f, 451f, justify = true)
        val blocks = group(listOf(date) + address + body)
        // An address's lines are each an entry of their own (see TextBlocksDocumentsTest).
        assertEquals(listOf(date.text) + address.map { it.text } + FIVE, texts(blocks))
        // A single line flush with the right margin, and right-aligned lines.
        assertEquals(listOf(BlockAlign.END, BlockAlign.END, BlockAlign.END, BlockAlign.END, BlockAlign.START), blocks.map { it.align })
        // In the right column of a two-column page, a full line starts where its column does; a short one at the column's end is right-aligned.
        val columns = paragraph(NEWS, 50f, 90f, 240f) + paragraph(MORE, 310f, 90f, 240f)
        val full = line("A single line as wide as its column, set apart.", 310f, 200f, width = 240f)
        val page = line("Page 2", 520f, 230f)
        val aligned = group(columns + full + page).associate { it.text to it.align }
        assertEquals(BlockAlign.START, aligned[full.text])
        assertEquals(BlockAlign.END, aligned[page.text])
    }

    @Test fun nothingAndOneLine() {
        assertEquals(emptyList<SourceBlock>(), group(emptyList()))
        assertEquals(emptyList<SourceBlock>(), group(listOf(line("   ", 72f, 100f))))
        val one = SourceLine("  Hello  ", 72f, 100f, 102f, 112f, 10f)
        assertEquals(listOf(SourceBlock(listOf(one), "Hello", 72f, 100f, 102f, 112f, 10f, false, false, BlockAlign.START)), group(listOf(one)))
    }

    @Test fun oddBoxesNeverThrowOrGiveNaN() {
        val lines = listOf(
            // No size at all, and a box of none.
            SourceLine("Zero", 100f, 100f, 100f, 100f, 0f),
            SourceLine("Zero again", 100f, 100f, 100f, 100f, 0f),
            // Sides that aren't numbers: left out.
            SourceLine("Lost", Float.NaN, 120f, 200f, 132f, 10f),
            SourceLine("Lost too", 100f, 120f, Float.POSITIVE_INFINITY, 132f, 10f),
            // A size that isn't one: the box's height.
            SourceLine("Odd size", 100f, 140f, 200f, 152f, Float.NaN),
            // Given right to left and bottom to top; and boxes overlapping, the gap between them negative.
            SourceLine("Upside down", 300f, 212f, 200f, 200f, 10f),
            SourceLine("Overlapping", 200f, 206f, 300f, 218f, 10f),
            // Off the page.
            SourceLine("Gone", 700f, 100f, 800f, 112f, 10f),
        )
        for (width in listOf(pageWidth, 0f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val blocks = TextBlocks.group(lines + lines.reversed(), width, pageHeight)
            assertTrue(blocks.isNotEmpty())
            for (block in blocks) {
                for (value in listOf(block.left, block.top, block.right, block.bottom, block.size)) assertTrue(block.text, value.isFinite())
                assertTrue(block.size > 0f)
            }
            assertTrue(blocks.none { "Lost" in it.text })
        }
        assertEquals(12f, group(lines).single { it.text == "Odd size" }.size, 0f)
        assertTrue(group(lines).none { it.text == "Gone" })
        // A thousand lines on one spot.
        assertEquals(1000, group(List(1000) { line("Same", 72f, 100f) }).size)
    }

    private companion object {
        const val ABOUT = "Usugami makes PDF files on the phone without sending anything anywhere. It reads pictures, documents " +
            "and other PDFs, and it keeps every page as sharp as the original allows. Nothing leaves the phone unless " +
            "you share it yourself, and nothing is kept once you are done with it. Every tool works without internet."
        const val FIVE = "$ABOUT Files are named after the ones they were made from, so they are easy to find again later."
        const val TOOLS = "Each tool does one thing well. Images to PDF turns photos into pages, Merge PDF puts several files " +
            "together, and Compress PDF makes a large file small enough to send by mail."
        const val NEWS = "The library opens its new reading room on Monday, with longer hours on weekdays and a quiet corner for " +
            "children who come after school with their parents."
        const val MORE = "Readers can borrow up to ten books at a time and keep them for three weeks, or longer when nobody else " +
            "has asked for them in the meantime."
        const val WIDE = "Below both columns the page goes on across its whole width again, with a paragraph that sums up what the " +
            "two columns said."
        const val ESSAY1 = "The first paragraph of the essay sets out the question and why it matters to anyone who reads it with " +
            "care, and it ends here."
        const val ESSAY2 = "The second paragraph gives the evidence, one source after another, and weighs each of them against the " +
            "others before it ends."
        const val ESSAY3 = "The third paragraph draws the threads together and says what follows from them for the reader."
        const val HEBREW = "השורה הראשונה של הפסקה הזאת ארוכה מספיק כדי למלא את רוחב הטור כולו, והפסקה ממשיכה בשורה הבאה עד " +
            "שהיא נגמרת כאן."
        const val HEBREW_MORE = "פסקה שנייה מתחילה בשורה חדשה וממשיכה עוד קצת עד הסוף שלה."
    }
}
