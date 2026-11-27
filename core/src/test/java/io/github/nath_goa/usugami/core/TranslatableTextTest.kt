package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Translate PDF translates, what it keeps from the translator, and how a long block is cut for the translator and joined again. */
class TranslatableTextTest {
    private fun worth(text: String) = TranslatableText.isWorthTranslating(text)

    @Test fun addressesAreNotTranslated() {
        for (address in listOf(
            "https://example.com/report?id=3", "www.example.org", "(www.example.org)", "example.co.uk/about",
            "jane.doe@example.com", "<jane.doe@example.com>", "mailto:info@example.com", "report_final.pdf",
            "C:\\Program Files\\Usugami", "/home/jane/notes.txt", "~/Documents", "https://a.example www.b.example",
        )) {
            assertFalse(address, worth(address))
        }
        // An address among words is translated with them.
        assertTrue(worth("Write to jane.doe@example.com for a copy."))
        assertTrue(worth("Visit www.example.org"))
    }

    @Test fun numbersDatesAmountsAndCodesAreNotTranslated() {
        for (text in listOf("12.50", "A", "03/04/2025", "$1,200.00", "€ 3,50", "+33 1 23 45 67 89", "12:30", "A-12-B", "Q3", "p. 12", "", "  ", "—", "2025年3月")) {
            assertFalse(text, worth(text))
        }
        // An id: one run of more than 40 characters without a space, with digits.
        assertFalse(worth("INV2025000123ABCDEFGHIJKLMNOPQRSTUVWXYZ0042"))
    }

    @Test fun anItemsCodeIsNotTranslated() {
        // An invoice's references: capitals and digits, which a translator changes ("LB-204" -> "Lb-204").
        for (code in listOf("LB-204", "AMP-12", "SRV-01", "F-2026-0412", "CMCIFR2A", "COVID-19")) {
            assertFalse(code, worth(code))
        }
        // Words with digits in writing without capitals, and a code among words, are still translated.
        for (text in listOf("東京2020", "Réf. LB-204", "Covid-19", "B2B-Kunden")) {
            assertTrue(text, worth(text))
        }
    }

    @Test fun wordsAreTranslated() {
        // "U.S.A." has no word of two letters, but no digit either: an abbreviation, not a code.
        for (text in listOf("Invoice 12", "Total", "No.", "12 kg", "Page 3 of 10", "東京", "नमस्ते", "Étude", "U.S.A.", "e.g.")) {
            assertTrue(text, worth(text))
        }
        // A long word without digits is still a word.
        assertTrue(worth("Donaudampfschifffahrtsgesellschaftskapitänsmütze"))
    }

    @Test fun chineseJapaneseAndThaiParagraphsWithDigitsAreTranslated() {
        // Written without spaces, a paragraph is one run of more than 40 characters, and a date, a
        // price or a number in it doesn't make it an id: each has words, so each is translated.
        for (text in listOf(
            // A date and counts (50 characters).
            "本公司成立于2010年，现有员工500余名，业务覆盖全国30多个省市自治区，是行业内领先的企业之一。",
            // A date in full-width digits (48).
            "本説明会は２０２５年１１月２７日（木）午後２時から、東京本社１２階の大会議室にて開催いたします。",
            // A price (48).
            "本产品售价为人民币1,299元（含税及运费），购买三件及以上可享受九折优惠，详情请咨询客服热线。",
            // A model number (57).
            "型番ABC-1234X-JPの製品につきましては、2024年3月以降に製造されたものに限り、無償で修理いたします。",
            // Thai: an amount and a date (80).
            "กรุณาชำระเงินจำนวน1,500บาทภายในวันที่30พฤศจิกายน2568เพื่อหลีกเลี่ยงค่าปรับล่าช้า",
            // A phone number and opening hours (50).
            "如需帮助，请拨打客服电话400-820-8820，工作时间为每周一至周五上午9:00至下午6:00。",
            // A heading, its number and title apart by an ideographic space.
            "第3章　产品规格与技术参数",
            // A label with a phone number: the label is a word.
            "電話：03-1234-5678",
        )) {
            assertTrue(text, worth(text))
        }
        for (text in listOf(
            // A web address alone, its path in Chinese.
            "https://zh.wikipedia.org/wiki/北京",
            // An e-mail address alone.
            "service@example.co.jp",
            // A file's name alone.
            "会議資料_2025.pdf",
            // A date with no word: its 年, 月 and 日 each stand between digits.
            "２０２５年１１月２７日",
            // A number: fewer than two letters.
            "１２３，４５６",
            // A phone number alone.
            "03-1234-5678",
            // A product code: one run of 47 characters with digits, in writing that has spaces.
            "SKU-2025-JP-000123-ABCDEFGHIJKLMNOPQRSTU-998877",
        )) {
            assertFalse(text, worth(text))
        }
    }

    @Test fun anAddressIsToldFromTheChineseJapaneseOrThaiWordsAroundIt() {
        // No space parts an address, a dotted name or a numbered heading from the words around it.
        for (text in listOf(
            "如有任何问题，请通过电子邮件support@example.com与我们联系。", "お問い合わせはinfo@example.co.jpまでお願いいたします。",
            "邮箱：support@example.com", "www.example.com上有更多信息。", "ติดต่อเราได้ที่support@example.com",
            "基于Node.js的开发", "详情请见附件report.pdf", "1.公司简介", "2.产品与服务", "一.概述",
            // A heading numbered with a full stop and no space, in writing with spaces too.
            "1.Introduction", "2.1.Scope", "1.Введение", "1.مقدمة", "١.مقدمة", "1.소개",
        )) {
            assertTrue(text, worth(text))
        }
        // An address or a file's name standing alone stays as it is, in any script, also in the
        // brackets and with the full stop of Chinese and Japanese.
        for (text in listOf(
            "报告.pdf", "会議資料_2025.pdf", "東京タワー.jpg", "ข้อมูล.pdf", "रिपोर्ट.pdf", "отчёт.pdf", "пример.рф",
            "https://zh.wikipedia.org/wiki/北京", "https://ja.wikipedia.org/wiki/東京", "C:\\用户\\文档",
            "（www.example.org）", "「report.pdf」", "support@example.com。", "163.com", "1.txt", "1.JPG",
        )) {
            assertFalse(text, worth(text))
        }
    }

    private fun kept(text: String) = TranslatableText.protect(text).tokens

    @Test fun codesAddressesPostcodesAndPhoneNumbersAreKeptFromTheTranslator() {
        // Sentences of real letters and invoices, and what in them must come back as it is.
        for ((text, tokens) in listOf(
            "Merci de régler la somme de 742,00 € par virement sur le compte FR76 3000 6000 0112 3456 7890 189 (BIC CMCIFR2A) avant le 15 octobre 2026."
                to listOf("FR76 3000 6000 0112 3456 7890 189", "CMCIFR2A"),
            "Vous pouvez y accéder en écrivant à accueil@cooplait-monts.fr ou en appelant le 04 78 48 52 10." to listOf("accueil@cooplait-monts.fr", "04 78 48 52 10"),
            "Conformément à l'article L. 441-10 du Code de commerce, une indemnité de 40 € est due." to listOf("441-10"),
            "Renseignements sur www.reparons-lyon.fr." to listOf("www.reparons-lyon.fr"),
            "Our ref.: LET-2026-0457" to listOf("LET-2026-0457"),
            "Our office is at 14 Park Row, Bristol BS6 5RT, and opens at 9 am." to listOf("BS6 5RT"),
            "Please pay into account GB33 BUKB 2045 7741 2678 90 (sort code 20-45-77) by 30 November." to listOf("GB33 BUKB 2045 7741 2678 90", "20-45-77"),
            "Hartwell Property Management Ltd – Registered in England No. 04126789 – VAT No. GB 412 6789 03" to listOf("GB 412 6789 03"),
            "Brandt & Partner GmbH – Amtsgericht Köln HRB 81234 – USt-IdNr. DE 412 678 903" to listOf("DE 412 678 903"),
            "Call us on +44 (0)161 496 0732 or write to Brightwork Lighting, 12 Canal Street, Manchester M3 3JE." to listOf("+44 (0)161 496 0732", "M3 3JE"),
            "The report is saved as annual_report_2026.pdf in /home/jane/reports and at https://example.com/r?id=3." to
                listOf("annual_report_2026.pdf", "/home/jane/reports", "https://example.com/r?id=3"),
            "Bitte überweisen Sie den Betrag auf das Konto DE89 3704 0044 0532 0130 00 (BIC: COBADEFFXXX) bis zum 30. November." to
                listOf("DE89 3704 0044 0532 0130 00", "COBADEFFXXX"),
            "Schreiben Sie an info@brandt-verwaltung.de oder rufen Sie 0221 924 51 80 an, Wohnung 3B." to listOf("info@brandt-verwaltung.de", "0221 924 51 80", "3B"),
            "Puede escribirnos a info@taller-luz.es o llamarnos al +34 912 345 678." to listOf("info@taller-luz.es", "+34 912 345 678"),
            "Referencia: FAC-2026-0412, artículos AMP-12 y B-41267890." to listOf("FAC-2026-0412", "AMP-12", "B-41267890"),
            // Chinese and Japanese run into what is kept, without spaces.
            "お問い合わせはinfo@example.co.jpまで、型番ABC-1234X-JPは03-1234-5678へ。" to listOf("info@example.co.jp", "ABC-1234X-JP", "03-1234-5678"),
            "基于Node.js的开发" to listOf("Node.js"),
        )) {
            assertEquals(text, tokens, kept(text))
        }
        val bank = TranslatableText.protect("Payez sur FR76 3000 6000 0112 3456 7890 189 (BIC CMCIFR2A), réf. INV-2026-0412.")
        assertEquals("Payez sur {1} (BIC {2}), réf. {3}.", bank.masked)
    }

    @Test fun amountsDatesWordsInCapitalsAndNamesGoToTheTranslator() {
        // The translator writes amounts and dates its language's way; it knows words in capitals; no rule knows a name.
        for (text in listOf(
            "Le total TTC s'élève à 1 234,56 € au 15/10/2026, soit 2 500 € de plus qu'en 2025.",
            "Les ventes ont atteint 12 345 678 € le 3 mars 2026 (page 3 of 10, ISO 9001).",
            "FACTURE n° 12 – TOTAL HT – TVA 20 %",
            "Claire Delorme, Gestionnaire locative, Agence Immobilière Delorme",
            "e.g. the U.S.A., p.ej. en España, z.B. in Köln, etc.",
            "Ab dem 1. Januar 2027 erhöht sich die Miete von 742,00 € auf 758,30 €, Seite 12-15.",
            "Die Ergebnisse 3.1 und 4.2 vom 15.10.2026 sind in 1.234.567 Fällen gleich.",
        )) {
            assertEquals(text, emptyList<String>(), kept(text))
        }
    }

    @Test fun theTranslationGetsBackWhatWasKeptWhereverItsPlaceholdersAre() {
        val protected = TranslatableText.protect("Écrivez à dpo@mediatheque-quimper.fr ou appelez le 02 98 95 74 10.")
        assertEquals("Écrivez à {1} ou appelez le {2}.", protected.masked)
        assertEquals("Write to dpo@mediatheque-quimper.fr or call 02 98 95 74 10.", protected.restore("Write to {1} or call {2}."))
        // Moved about, written full-width, with spaces inside or in another script's digits.
        assertEquals("Call 02 98 95 74 10 or write to dpo@mediatheque-quimper.fr.", protected.restore("Call {2} or write to {1}."))
        assertEquals("dpo@mediatheque-quimper.frまたは02 98 95 74 10へ。", protected.restore("｛１｝または{ 2 }へ。"))
        assertEquals("اكتب إلى dpo@mediatheque-quimper.fr أو اتصل 02 98 95 74 10", protected.restore("اكتب إلى {١} أو اتصل {٢}"))
        assertEquals("dpo@mediatheque-quimper.fr को लिखें, 02 98 95 74 10", protected.restore("{१} को लिखें, {२}"))
        // Written right to left, what is kept reads left to right between marks, its digits' groups in their order.
        assertEquals("اكتب إلى \u200Edpo@mediatheque-quimper.fr\u200E أو اتصل \u200E02 98 95 74 10\u200E", protected.restore("اكتب إلى {1} أو اتصل {2}", leftToRight = true))
        // Lost, twice, or one it didn't have: the translation can't be put together.
        for (broken in listOf("Write to or call {2}.", "Write to {1} or {1} call {2}.", "Write to {1} or call {2} {3}.", "Write to [1] or call [2].", "Write to {1}1° or call {2}.")) {
            assertNull(broken, protected.restore(broken))
        }
        // The other ways of writing them.
        val brackets = TranslatableText.protect("Écrivez à dpo@mediatheque-quimper.fr.", Placeholder.BRACKETS)
        assertEquals("Écrivez à [1].", brackets.masked)
        assertEquals("Write to dpo@mediatheque-quimper.fr.", brackets.restore("Write to 【1】."))
        assertNull(brackets.restore("Write to {1}."))
        val white = TranslatableText.protect("Écrivez à dpo@mediatheque-quimper.fr.", Placeholder.WHITE_BRACKETS)
        assertEquals("Écrivez à ⟦1⟧.", white.masked)
        assertEquals("dpo@mediatheque-quimper.fr को लिखें।", white.restore("⟦१⟧ को लिखें।"))
    }

    @Test fun aTranslationOfTheTextItselfIsTakenWhenItKeptEachToken() {
        val protected = TranslatableText.protect("Écrivez à dpo@mediatheque-quimper.fr ou appelez le 02 98 95 74 10, réf. AMP-12 et AMP-12.")
        val good = "Write to dpo@mediatheque-quimper.fr or call 02 98 95 74 10, ref. AMP-12 and AMP-12."
        assertSame(good, protected.unchangedIn(good))
        // One changed, run into a word, or there once where the text has it twice: the translator changed it.
        for (changed in listOf(
            "Write to dpo@mediathheque-quimper.fr or call 02 98 95 74 10, ref. AMP-12 and AMP-12.",
            "Write to dpo@mediatheque-quimper.fr or call 0298957410, ref. AMP-12 and AMP-12.",
            "Write to dpo@mediatheque-quimper.fr or call 02 98 95 74 10, ref. AMP-123 and AMP-12.",
            "Write to dpo@mediatheque-quimper.fr or call 02 98 95 74 10, ref. AMP-12 and Amp-12.",
        )) {
            assertNull(changed, protected.unchangedIn(changed))
        }
        // Written right to left, each between left-to-right marks.
        assertEquals(
            "اكتب إلى \u200Edpo@mediatheque-quimper.fr\u200E أو اتصل \u200E02 98 95 74 10\u200E، \u200EAMP-12\u200E و\u200EAMP-12\u200E",
            protected.unchangedIn("اكتب إلى dpo@mediatheque-quimper.fr أو اتصل 02 98 95 74 10، AMP-12 وAMP-12", leftToRight = true),
        )
    }

    @Test fun whatIsKeptAtTheEndsTwiceInBracketsOrAloneComesBackAsItWas() {
        fun same(text: String, translation: (String) -> String = { it }) = TranslatableText.protect(text).let { it.restore(translation(it.masked)) }
        // At the start and the end.
        val ends = TranslatableText.protect("LET-2026-0457 est notre référence, écrivez à contact@delorme-immo.fr")
        assertEquals("{1} est notre référence, écrivez à {2}", ends.masked)
        // The same twice: each its own placeholder.
        val twice = TranslatableText.protect("Le code AMP-12 remplace AMP-12.")
        assertEquals("Le code {1} remplace {2}.", twice.masked)
        assertEquals("The code AMP-12 replaces AMP-12.", twice.restore("The code {1} replaces {2}."))
        // Beside punctuation and in brackets and quotes.
        val around = TranslatableText.protect("(www.example.org), «LB-204», [M3 3JE]: contact@x-y.fr.")
        assertEquals("({1}), «{2}», [{3}]: {4}.", around.masked)
        assertEquals("(www.example.org), «LB-204», [M3 3JE]: contact@x-y.fr.", same("(www.example.org), «LB-204», [M3 3JE]: contact@x-y.fr."))
        // Placeholders written in the text already are kept as they are.
        val written = TranslatableText.protect("Voir [1] et {2} pour AMP-12.")
        assertEquals(listOf("[1]", "{2}", "AMP-12"), written.tokens)
        assertEquals("See [1] and {2} for AMP-12.", written.restore("See {1} and {2} for {3}."))
        // Nothing to keep: the text as it is, its translation too.
        val plain = "Le loyer passera de 742,00 € à 758,30 € au 1er janvier 2027."
        val nothing = TranslatableText.protect(plain)
        assertSame(plain, nothing.masked)
        assertEquals(emptyList<String>(), nothing.tokens)
        assertEquals("The rent will rise.", nothing.restore("The rent will rise."))
        // Only what is kept.
        val only = TranslatableText.protect("LET-2026-0457 contact@delorme-immo.fr")
        assertEquals("{1} {2}", only.masked)
        assertEquals("LET-2026-0457 contact@delorme-immo.fr", only.restore(only.masked))
    }

    @Test fun aLongTextIsKeptPieceByPieceAsItIsCut() {
        // Each piece TextChunks cuts is protected on its own, numbered from 1, and put together again.
        val text = (1..60).joinToString(" ") { "La pièce LB-$it est livrée à contact$it@atelier-lumiere.fr avant le $it mars." }
        val pieces = TextChunks.split(text)
        assertTrue(pieces.size >= 3)
        val back = pieces.map { piece ->
            val protected = TranslatableText.protect(piece)
            assertTrue(protected.masked, protected.masked.startsWith("La pièce {1} est livrée à {2} "))
            assertEquals(protected.tokens.size, Regex("\\{\\d+\\}").findAll(protected.masked).count())
            protected.restore(protected.masked)!!
        }
        assertEquals(text, TextChunks.join(back))
    }

    /** A paragraph of [count] sentences of different lengths, each ending with a full stop. */
    private fun sentences(count: Int): String = (1..count).joinToString(" ") { i ->
        "Sentence $i of the report says " + "something more ".repeat(i % 7) + "and ends here."
    }

    @Test fun aLongParagraphIsCutAtTheEndsOfItsSentences() {
        val text = sentences(35)
        assertEquals(3175, text.length)
        val pieces = TextChunks.split(text)
        assertTrue(pieces.size >= 3)
        for (piece in pieces) {
            assertTrue(piece.length <= TextChunks.MAX_CHARS)
            assertTrue(piece, piece.endsWith("and ends here."))
            assertTrue(piece, piece.startsWith("Sentence "))
        }
        assertEquals(text, TextChunks.join(pieces))
        // Each piece takes as many whole sentences as fit: the next one wouldn't have.
        for (i in 0 until pieces.size - 1) {
            val next = pieces[i + 1].indexOf(". ").let { if (it < 0) pieces[i + 1].length else it + 1 }
            assertTrue(pieces[i].length + 1 + next > TextChunks.MAX_CHARS)
        }
    }

    @Test fun aSentenceLongerThanAPieceIsCutBetweenWords() {
        val words = (1..700).joinToString(" ") { "word$it" }
        val pieces = TextChunks.split(words, max = 1200)
        for (piece in pieces) {
            assertTrue(piece.length <= 1200)
            assertTrue(piece, piece.split(' ').all { it.matches(Regex("word\\d+")) })
        }
        assertEquals(words, TextChunks.join(pieces))
        // A single "word" longer than a piece (an address, say) is cut where it must be.
        val long = "x".repeat(30)
        assertEquals(listOf("x".repeat(12), "x".repeat(12), "x".repeat(6)), TextChunks.split(long, max = 12))
    }

    @Test fun chineseIsCutAfterItsFullStopsAndJoinedWithoutSpaces() {
        val sentence = "这是一个关于翻译的很长的句子，它没有空格。"
        val text = sentence.repeat(150)
        assertEquals(3150, text.length)
        val pieces = TextChunks.split(text)
        assertTrue(pieces.size >= 3)
        for (piece in pieces) {
            assertTrue(piece.length <= TextChunks.MAX_CHARS)
            assertTrue(piece.endsWith("。"))
        }
        assertEquals(text, TextChunks.join(pieces))
        // Without any full stop, between any two characters.
        val unbroken = "翻译".repeat(1300)
        val cut = TextChunks.split(unbroken)
        assertEquals(listOf(1200, 1200, 200), cut.map { it.length })
        assertEquals(unbroken, TextChunks.join(cut))
    }

    @Test fun aLongRunOfClosingBracketsIsCutLikeAnyLongWord() {
        // A title of a million closing brackets, as a hostile PDF can hold: cut where it must be, at once.
        val text = "ab" + ")".repeat(1_000_000)
        val pieces = TextChunks.split(text)
        assertTrue(pieces.all { it.length <= TextChunks.MAX_CHARS })
        assertEquals(text, pieces.joinToString(""))
        // A sentence's end is still found behind the quotes and brackets that close it.
        assertEquals(listOf("“It is (late.)”", "Go home."), TextChunks.split("“It is (late.)” Go home.", max = 16))
    }

    @Test fun piecesNeverSplitACharacter() {
        // An emoji is two chars; a cut at 3 would fall between its halves.
        val text = "ab\uD83D\uDE00cd"
        val pieces = TextChunks.split(text, max = 3)
        assertEquals(listOf("ab", "\uD83D\uDE00c", "d"), pieces)
        // é written as e and its accent stays together.
        assertEquals(listOf("ab", "e\u0301c"), TextChunks.split("abe\u0301c", max = 3))
    }

    @Test fun joiningPutsSpacesOnlyBetweenSpacedWriting() {
        assertEquals("One. Two.", TextChunks.join(listOf("One.", " Two. ")))
        assertEquals("東京。大阪。", TextChunks.join(listOf("東京。", "大阪。")))
        assertEquals("ภาษาไทยภาษาไทย", TextChunks.join(listOf("ภาษาไทย", "ภาษาไทย")))
        assertEquals("東京。 Osaka.", TextChunks.join(listOf("東京。", "Osaka.")))
        assertEquals("한국어 문장", TextChunks.join(listOf("한국어", "문장")))
        assertEquals("", TextChunks.join(listOf(" ", "")))
        assertEquals(emptyList<String>(), TextChunks.split("   "))
        assertEquals(listOf("Short."), TextChunks.split("  Short.  "))
    }
}
