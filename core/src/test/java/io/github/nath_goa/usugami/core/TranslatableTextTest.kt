package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Translate PDF translates, and how a long block is cut for the translator and joined again. */
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
