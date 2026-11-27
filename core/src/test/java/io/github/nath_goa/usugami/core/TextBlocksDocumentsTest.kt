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

    @Test fun anAddressAndLabelsAreALineEach() {
        // A letter's addressee, left-aligned, each line starting with a capital or a digit.
        val address = listOf("Madame Sophie Durand" to 107.5f, "Service des achats" to 80.6f, "Hôtel du Parc" to 60.5f, "8 avenue Foch" to 64.2f, "75016 Paris" to 52.3f)
            .mapIndexed { i, (text, width) -> line(text, 330f, 129f + 14f * i, 330f + width) }
        assertEquals(address.map { it.text }, texts(group(address)))
        // An invoice's labels in its top right corner, right-aligned.
        val labels = listOf("Facture n° 2026-0412" to 448.2f, "Date : 15 octobre 2026" to 443.3f, "Échéance : 14 novembre 2026" to 408.8f)
            .mapIndexed { i, (text, left) -> line(text, left, 42f + 14f * i, 545f, size = 10f) }
        val blocks = group(labels)
        assertEquals(labels.map { it.text }, texts(blocks))
        // A narrow table cell's text that runs on in small letters is still one block.
        val cell = listOf(line("Abat-jour en lin naturel", 56f, 217f, 176f, size = 10f), line("et coton tissé à la main", 56f, 229.5f, 170f, size = 10f))
        assertEquals(listOf("Abat-jour en lin naturel et coton tissé à la main"), texts(group(cell)))
    }

    @Test fun textThatOnlyLooksCentredIsLeftAligned() {
        // Under a heading at the left margin, a long line and a paragraph of lines all as wide,
        // from 60 to 530 points: their middles are the page's, as the margins are nearly alike.
        val heading = line("Before you go hiking", 60f, 62f, 310f, size = 18f)
        val intro = line("Good preparation makes walking more pleasant and safer, so here is what to carry.", 60f, 99f, 530f)
        val paragraph = paragraph(
            listOf("Children play until nightfall, while the old people chat on the benches" to 470f, "in the shade of the plane trees, and from far away comes the music of" to 470f, "a dance that will go on until morning in the village square." to 470f),
            60f, 560f, size = 15f,
        )
        val blocks = group(listOf(heading, intro) + paragraph)
        assertEquals(listOf(BlockAlign.START, BlockAlign.START, BlockAlign.START), blocks.map { it.align })
        // A subtitle centred under a centred title starts where nothing else does: centred.
        val title = line("Bees return to the city", 170f, 50f, 425f, size = 24f)
        val subtitle = line("How the roofs of Paris became a refuge for pollinating insects", 82f, 88f, 513f, size = 12f)
        val column = paragraph(listOf("For about ten years, bees have found an unexpected refuge" to 238f, "in large cities, far from the fields." to 160f), 50f, 152f, size = 10f)
        assertEquals(listOf(BlockAlign.CENTER, BlockAlign.CENTER, BlockAlign.START), group(listOf(title, subtitle) + column).map { it.align })
    }

    @Test fun labelsRightAlignedTogetherInsideThePageAreRightAligned() {
        // An invoice's totals: labels right-aligned at 450 points, each beside its amount at 539,
        // under a table whose rows run from margin to margin.
        val table = paragraph(listOf("Lampe de bureau en laiton, abat-jour en lin naturel et ampoules" to 489f, "basse consommation, installation et réglage compris dans le prix" to 470f), 56f, 300f, size = 10f)
        val totals = listOf("Total hors taxes" to 374f, "TVA (20 %)" to 398f, "Total à payer" to 388f).flatMapIndexed { i, (label, left) ->
            listOf(line(label, left, 400f + 16f * i, 450f, size = 10f), line("418,80 €", 500f, 400f + 16f * i, 539f, size = 10f))
        }
        val blocks = group(table + totals)
        assertEquals(listOf("Total hors taxes", "TVA (20 %)", "Total à payer"), texts(blocks).filter { it.startsWith("T") })
        assertEquals(List(3) { BlockAlign.END }, blocks.filter { it.text.startsWith("T") }.map { it.align })
        // Left-aligned labels that only end together by chance start together too: left-aligned.
        val address = listOf(line("Café des Arts", 320f, 200f, 380f, size = 10f), line("Rue du Port", 320f, 214f, 380.5f, size = 10f))
        assertEquals(listOf(BlockAlign.START, BlockAlign.START), group(table + address).filter { it.left == 320f }.map { it.align })
    }

    @Test fun eachFootnoteStartsAtItsNumber() {
        // Two footnotes in 9-point type, each after its number in 6-point type.
        val notes = listOf(
            line("1", 60f, 755f, 63f, size = 6f), line("Venetian travellers already spoke of it in their own accounts of the East.", 66f, 754f, 330f, size = 9f),
            line("2", 60f, 769f, 63f, size = 6f), line("Voltaire, it is said, drank more than forty cups of it on every single day.", 66f, 768f, 332f, size = 9f),
        )
        assertEquals(listOf("1", notes[1].text, "2", notes[3].text), texts(group(notes)))
        // A number of the same size as the text beside it is no note's: a table's first column, say.
        val table = listOf(line("1", 60f, 754f, 65f, size = 9f), notes[1], line("2", 60f, 768f, 65f, size = 9f), notes[3])
        assertEquals(listOf("1", "${notes[1].text} ${notes[3].text}", "2"), texts(group(table)))
    }
}
