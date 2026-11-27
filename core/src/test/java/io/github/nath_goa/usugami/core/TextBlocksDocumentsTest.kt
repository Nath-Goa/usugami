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

    @Test fun aGermanParagraphInANarrowColumnIsOneBlock() {
        // A CV's sidebar 12 sizes wide, in 8.5-point Arial: a paragraph whose lines are full, two of
        // them going on to a line starting with a noun ("Häuser", "Kinder"), and one ending short of
        // the others by less than its next line's first word ("die" "Hausaufgabenhilfe"), it is one block.
        val rows = listOf(
            "In meiner Freizeit laufe ich" to 99.67f, "gern am Fluss entlang," to 86.45f, "fotografiere Straßen und" to 92.13f,
            "Häuser der Altstadt und" to 89.29f, "betreue als Ehrenamtliche" to 98.74f, "die Hausaufgabenhilfe für" to 96.86f,
            "Kinder aus dem Stadtteil." to 94.96f,
        )
        val paragraph = paragraph(rows, 23f, 450f, size = 8.5f)
        assertEquals(listOf(rows.joinToString(" ") { it.first }), texts(group(paragraph)))
        // Labels in the same column, as long as its lines and as close: they end no sentence, each is a block.
        val labels = paragraph(listOf("Agiles Projektmanagement" to 101.52f, "Datenanalyse (SQL, Python)" to 108.12f, "Moderation von Workshops" to 103.27f), 23f, 300f, size = 8.5f)
        assertEquals(labels.map { it.text }, texts(group(labels)))
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

    @Test fun eachEntryOfAContentsPageAPriceListOrAFormIsABlock() {
        // A contents page as a word processor sets it: entries from 72 points (chapters) or 83
        // (sections) to their page numbers at 523, after leader dots. One section's title runs on to
        // a second line, which has the dots: it is one entry.
        val dots = " " + ".".repeat(60) + " "
        val rows = listOf(
            Triple("1 Introduction${dots}1", 72f, 523f), Triple("1.1 Scope of this report${dots}2", 83f, 523f), Triple("1.2 Method${dots}3", 83f, 523f),
            Triple("1.3 Results of the survey of the members of the association, in", 83f, 505f), Triple("the spring and the autumn of 2026${dots}4", 83f, 523f),
            Triple("2 Results${dots}5", 72f, 523f), Triple("2.1 Sales in Europe${dots}6", 83f, 523f), Triple("2.2 Sales in Asia${dots}8", 83f, 523f),
            Triple("3 Outlook${dots}11", 72f, 523f), Triple("Appendix${dots}14", 72f, 523f),
        )
        val contents = listOf(line("Contents", 72f, 80f, 150f, size = 16f)) +
            rows.mapIndexed { i, (text, left, right) -> line(text, left, 120f + 1.25f * 11f * i, right) }
        val titles = rows.map { it.first }
        assertEquals(listOf("Contents") + titles.take(3) + "${titles[3]} ${titles[4]}" + titles.drop(5), texts(group(contents)))
        // A price list, its leaders spaced out.
        val menu = listOf("Café crème" to "3,50 €", "Thé à la menthe" to "3,00 €", "Croissant au beurre" to "1,80 €", "Tarte aux pommes" to "4,20 €")
            .mapIndexed { i, (dish, price) -> line("$dish ${". ".repeat(20)}$price", 150f, 200f + 15f * i, 445f) }
        assertEquals(menu.map { it.text }, texts(group(menu)))
        // A form's fields, each with a blank to fill in.
        val form = listOf("Name:", "Address:", "Date of birth:").mapIndexed { i, label -> line("$label ${"_".repeat(50)}", 72f, 300f + 1.6f * 11f * i, 523f) }
        assertEquals(form.map { it.text }, texts(group(form)))
    }

    @Test fun eachRowOfATableIsABlock() {
        // An invoice's table in 9-point type, its rows 24 points apart (2.67 sizes): each row's
        // description is as wide as running text and has its quantity, unit price and amount after it.
        // Below it, three lines of small print. The rows' step is no line spacing of the page: each
        // description is a block of its own, and the small print one block.
        val descriptions = listOf(
            "Lampe de bureau en laiton, abat-jour vert" to 172f, "Abat-jour en lin naturel, diamètre 40 cm" to 166f,
            "Ampoules LED à filament, lumière chaude" to 164f, "Installation et réglage sur place" to 140f,
        )
        val rows = descriptions.flatMapIndexed { i, (text, width) ->
            val top = 274f + 24f * i
            listOf(
                line(listOf("LB-204", "AJ-110", "AMP-12", "SRV-01")[i], 55f, top, 85f, size = 9f),
                line(text, 107f, top, 107f + width, size = 9f),
                line(listOf("2", "4", "12", "1")[i], 360f, top, 366f, size = 9f),
                line("85,00 €", 410f, top, 450f, size = 9f),
                line("170,00 €", 495f, top, 540f, size = 9f),
            )
        }
        val note = paragraph(
            listOf(
                "Paiement par virement bancaire à 30 jours à compter de la date de facture. Tout retard de paiement" to 490f,
                "entraîne l'application de pénalités au taux de trois fois le taux d'intérêt légal, ainsi qu'une" to 485f,
                "indemnité forfaitaire de 40 € pour frais de recouvrement." to 260f,
            ),
            50f, 640f, size = 8f,
        )
        val blocks = texts(group(rows + note))
        for ((text, _) in descriptions) assertEquals(text, 1, blocks.count { it == text })
        assertEquals(1, blocks.count { it.startsWith("Paiement") && it.endsWith("recouvrement.") })
    }

    @Test fun anAddressOnALineOfItsOwnIsABlock() {
        // A letterhead's address, right-aligned in 8.5-point type, its last lines an e-mail and a web
        // address starting with small letters.
        val head = listOf("14, place des Carmes" to 459f, "31000 Toulouse" to 482f, "Tél. 05 61 22 48 90" to 466f, "contact@delorme-immo.fr" to 444f, "www.delorme-immo.fr" to 460f)
            .mapIndexed { i, (text, left) -> line(text, left, 40f + 11f * i, 539f, size = 8.5f) }
        assertEquals(head.map { it.text }, texts(group(head)))
        // A web address on its own line under a line of opening hours.
        val shop = listOf(line("Ouverts du mardi au samedi, de 9 h à 19 h", 56f, 300f, 262f, size = 10f), line("www.cooplait-monts.fr/magasins", 56f, 314f, 205f, size = 10f))
        assertEquals(shop.map { it.text }, texts(group(shop)))
        // An address broken at its hyphen at a line's end runs on with the sentence it ends.
        val note = listOf(
            line("Prochain atelier : samedi 5 décembre, de 10 h à 13 h,", 315f, 590f, 537f, size = 8.5f),
            line("12 rue d'Austerlitz. Renseignements sur www.reparons-", 315f, 601f, 533f, size = 8.5f), line("lyon.fr.", 315f, 612f, 345f, size = 8.5f),
        )
        assertEquals(listOf("${note[0].text} ${note[1].text}${note[2].text}"), texts(group(note)))
    }

    @Test fun aContentsEntryIsLinedUpAtItsStart() {
        // Entries of two levels, from 70 and 92 points, all ending at 525 with their page numbers.
        val dots = " " + ".".repeat(50) + " "
        val entries = listOf("Avant-propos${dots}3" to 70f, "1. Présentation de l'entreprise${dots}5" to 70f, "1.1 Notre histoire${dots}6" to 92f, "1.2 Nos valeurs et notre organisation${dots}8" to 92f, "2. Les résultats de l'année${dots}11" to 70f)
            .mapIndexed { i, (text, left) -> line(text, left, 160f + 22f * i, 525f) }
        assertEquals(List(entries.size) { BlockAlign.START }, group(entries).map { it.align })
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
