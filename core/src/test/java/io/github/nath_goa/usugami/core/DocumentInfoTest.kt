package io.github.nath_goa.usugami.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.Charset
import java.time.Instant
import java.time.ZoneId

class DocumentInfoTest {

    // 1. InfoText

    @Test
    fun infoTextIsCleanedCutAndWrittenAsAPdfStringHasIt() {
        // Line breaks, tabs and other control characters: each run one space; U+0000 dropped; trimmed.
        assertEquals("Annual report 2026", InfoText.clean("  Annual\r\n\treport\u0085\u20282026\u0000 "))
        // A language escape (U+001B, a language, perhaps a country, U+001B) is dropped with its code.
        assertEquals("Rapport annuel", InfoText.clean("\u001BfrFR\u001BRapport annuel"))
        assertEquals("Hello world", InfoText.clean("\u001Ben\u001BHello\u0000 world"))
        // In a UTF-16 string, as ISO 32000 writes it, the codes are two bytes of ASCII each: "en" is one
        // UTF-16 character, "frFR" two. Read through the bytes a PDF has (big- and little-endian).
        val esc = bytesOf(0x00, 0x1B)
        val rapport = "Rapport annuel".toByteArray(Charsets.UTF_16BE)
        val frFR = bytesOf(0xFE, 0xFF) + esc + bytesOf(0x66, 0x72, 0x46, 0x52) + esc + rapport
        assertEquals("Rapport annuel", InfoText.clean(InfoText.unicode(frFR)!!))
        val en = bytesOf(0xFE, 0xFF) + esc + bytesOf(0x65, 0x6E) + esc + rapport
        assertEquals("Rapport annuel", InfoText.clean(InfoText.unicode(en)!!))
        val enLe = bytesOf(0xFF, 0xFE, 0x1B, 0x00, 0x6E, 0x65, 0x1B, 0x00) + "Hi".toByteArray(Charsets.UTF_16LE)
        assertEquals("Hi", InfoText.clean(InfoText.unicode(enLe)!!))
        // Two stray escapes are not one: the text between them stays, each escape a space.
        assertEquals("a long title b", InfoText.clean("a\u001Blong title\u001Bb"))

        // Cut at the limit, never between the two halves of an emoji.
        val emoji = "\uD83D\uDE00"
        assertEquals("a".repeat(1999), InfoText.cut("a".repeat(1999) + emoji))
        assertEquals("a".repeat(1998) + emoji, InfoText.cut("a".repeat(1998) + emoji + "b"))
        assertEquals("short", InfoText.cut("short"))
        assertEquals("ab", InfoText.cut("abc", 2))

        // Hindi, Japanese, an emoji and "é": UTF-16BE after FE FF, and read back the same.
        for (text in listOf("हिन्दी रिपोर्ट", "年次報告書", "Party \uD83C\uDF89", "Café")) {
            val bytes = InfoText.bytes(text)
            assertEquals(text, 0xFE, bytes[0].toInt() and 0xFF)
            assertEquals(text, 0xFF, bytes[1].toInt() and 0xFF)
            assertEquals(text, InfoText.unicode(bytes))
        }
        // Plain ASCII stays plain bytes, which PDFBox reads itself: unicode() has nothing to say of them.
        val plain = "Q3 (draft) ~ #1"
        assertArrayEquals(plain.toByteArray(Charsets.US_ASCII), InfoText.bytes(plain))
        assertNull(InfoText.unicode(InfoText.bytes(plain)))
        assertNull(InfoText.unicode(ByteArray(0)))
        // PDF 2.0's UTF-8 mark and a UTF-16LE mark are read.
        assertEquals("Café ü", InfoText.unicode(bytesOf(0xEF, 0xBB, 0xBF) + "Café ü".toByteArray(Charsets.UTF_8)))
        assertEquals("日本語", InfoText.unicode(bytesOf(0xFF, 0xFE) + "日本語".toByteArray(Charsets.UTF_16LE)))

        // Keywords for dc:subject: split at commas and semicolons.
        assertEquals(listOf("a", "b", "c"), InfoText.keywordList("a, b;; a ,c"))
        assertEquals(emptyList<String>(), InfoText.keywordList(" , ;"))
    }

    // 2. PdfDates

    /** Neither the phone this was written on (India) nor UTC, so a date read in the wrong zone shows. */
    private val zone: ZoneId = ZoneId.of("-03:00")

    private fun at(instant: String) = Instant.parse(instant).toEpochMilli()

    @Test
    fun pdfAndXmpDatesAreReadAsTheMomentsTheyName() {
        val noon = at("2026-11-12T10:00:12Z") // 15:30:12 in India
        assertEquals(noon, PdfDates.parse("D:20261112153012+05'30'", zone))
        assertEquals(noon, PdfDates.parse("D:20261112153012+0530", zone))
        assertEquals(noon, PdfDates.parse("D:20261112153012+05'30", zone))
        assertEquals(at("2026-11-12T23:30:12Z"), PdfDates.parse("D:20261112153012-08'00'", zone))
        assertEquals(at("2026-11-12T15:30:12Z"), PdfDates.parse("D:20261112153012Z", zone))
        assertEquals(at("2026-11-12T15:30:12Z"), PdfDates.parse("D:20261112153012Z00'00'", zone))
        // Without an offset: in the zone given.
        assertEquals(at("2026-11-12T18:30:12Z"), PdfDates.parse("D:20261112153012", zone))
        assertEquals(at("2026-01-01T03:00:00Z"), PdfDates.parse("D:2026", zone))
        assertEquals(at("2026-11-12T03:00:00Z"), PdfDates.parse("20261112", zone))
        // An offset needs at least the hour before it (seconds, though the standard asks for them, may be missing).
        assertEquals(at("2026-11-12T09:30:00Z"), PdfDates.parse("D:2026111215+05'30'", zone))
        // XMP's ISO 8601.
        assertEquals(noon, PdfDates.parse("2026-11-12T15:30:12+05:30", zone))
        assertEquals(at("2026-11-12T10:00:00Z"), PdfDates.parse("2026-11-12T10:00Z", zone))
        assertEquals(at("2026-11-12T18:30:12.250Z"), PdfDates.parse("2026-11-12T15:30:12.25-03:00", zone))
        assertEquals(at("2026-11-01T03:00:00Z"), PdfDates.parse("2026-11", zone))
        assertEquals(at("2026-11-12T03:00:00Z"), PdfDates.parse(" 2026-11-12 ", zone))

        // Neither form, or no real date: null, and the form shows the text as the PDF has it.
        for (text in listOf(
            "", "yesterday", "D:", "D:20261312", "D:20261132", "D:2026111225", "D:20260230", "D:0000",
            "2026-13", "2026-02-30", "2026-11-12T24:00Z", "D:202611121", "D:20261112153012+05'30'x",
            "D:20261112153012+19'00'", "D:2026-08'00'", "D:20261112Z", "Thu Nov 12 15:30:12 2026", "D:20261112153012" + "0".repeat(60),
        )) {
            assertNull(text, PdfDates.parse(text, zone))
        }
    }

    // 3. XmpPacket.read

    @Test
    fun anAcrobatStylePacketIsRead() {
        val info = XmpPacket.read(ACROBAT.toByteArray(Charsets.UTF_8))
        assertEquals(
            XmpInfo(
                title = "Annual report",
                author = "Asha Rao, Jean Dupont",
                subject = "Figures for 2026",
                keywords = "finance, report",
                creatorTool = "Adobe InDesign 19.0 (Windows)",
                producer = "Adobe PDF Library 17.0",
                created = "2026-11-12T15:30:12+05:30",
                modified = "2026-11-12T16:00:00+05:30",
            ),
            info,
        )
        // With UTF-8's byte order mark before it, too.
        assertEquals(info, XmpPacket.read(bytesOf(0xEF, 0xBB, 0xBF) + ACROBAT.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun attributeFormsAndOtherPrefixesForTheSameNamespacesAreRead() {
        val packet = """
            <?xpacket begin='' id='W5M0MpCehiHzreSzNTczkc9d'?>
            <r:RDF xmlns:r='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>
             <r:Description r:about='uuid:6a1f' xmlns:p='http://ns.adobe.com/pdf/1.3/'
               xmlns:t='http://ns.adobe.com/xap/1.0/' xmlns:dc='http://example.com/not-dublin-core/'
               p:Keywords='contract; 2026' t:CreatorTool='Writer' p:Producer='LibreOffice 7.6' dc:title='Not the title'>
              <e:title xmlns:e='http://purl.org/dc/elements/1.1/'><r:Alt><r:li xml:lang='x-default'>  </r:li></r:Alt></e:title>
             </r:Description>
             <r:Description r:about='uuid:6a1f' xmlns:e='http://purl.org/dc/elements/1.1/'>
              <e:title><r:Alt><r:li xml:lang='de'>Mietvertrag</r:li><r:li xml:lang='x-default'>Lease</r:li></r:Alt></e:title>
              <e:subject><r:Bag><r:li>ignored</r:li></r:Bag></e:subject>
              <e:creator>Sam Lee</e:creator>
             </r:Description>
            </r:RDF>
            <?xpacket end='w'?>
        """.trimIndent()
        val info = XmpPacket.read(packet.toByteArray(Charsets.UTF_8))!!
        // The empty title of the first description counts as none, so the second one's is taken.
        assertEquals("Lease", info.title)
        assertEquals("Sam Lee", info.author)
        assertNull(info.subject)
        // pdf:Keywords is taken before dc:subject.
        assertEquals("contract; 2026", info.keywords)
        assertEquals("Writer", info.creatorTool)
        assertEquals("LibreOffice 7.6", info.producer)
        assertNull(info.created)
        assertNull(info.modified)
    }

    @Test
    fun utf16PacketsEntitiesAndCdataAreRead() {
        val packet = """
            <?xpacket begin="${'\uFEFF'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/"
              xmlns:pdf="http://ns.adobe.com/pdf/1.3/" xmlns:xmp="http://ns.adobe.com/xap/1.0/"
              xmp:CreatorTool="Q&quot;R &apos;S&apos; &#x3C;T&#62;">
            <dc:title><rdf:Alt><rdf:li xml:lang="x-default">Tom &amp; Jerry &lt;3 &#x263A; &#233; 年次報告書</rdf:li></rdf:Alt></dc:title>
            <pdf:Producer><![CDATA[A <b> & B]]></pdf:Producer>
            </rdf:Description></rdf:RDF></x:xmpmeta>
            <?xpacket end="r"?>
        """.trimIndent()
        val expected = XmpInfo(
            title = "Tom & Jerry <3 ☺ é 年次報告書", author = null, subject = null, keywords = null,
            creatorTool = "Q\"R 'S' <T>", producer = "A <b> & B", created = null, modified = null,
        )
        assertEquals(expected, XmpPacket.read(packet.toByteArray(Charsets.UTF_8)))
        // UTF-16: told by the byte order mark, or by the first "<" without one.
        assertEquals(expected, XmpPacket.read(bytesOf(0xFF, 0xFE) + packet.toByteArray(Charsets.UTF_16LE)))
        assertEquals(expected, XmpPacket.read(bytesOf(0xFE, 0xFF) + packet.toByteArray(Charsets.UTF_16BE)))
        assertEquals(expected, XmpPacket.read(packet.toByteArray(Charsets.UTF_16BE)))
        assertEquals(expected, XmpPacket.read(packet.toByteArray(Charsets.UTF_16LE)))
    }

    // 5. Unreadable packets

    @Test
    fun packetsTheStrictReaderRefusesGiveNull() {
        fun read(packet: String, charset: Charset = Charsets.UTF_8) = XmpPacket.read(packet.toByteArray(charset))
        // Each refused packet is this one, which reads, with one thing wrong.
        val base = ACROBAT
        assertEquals("Annual report", read(base)?.title)

        // A DOCTYPE, before the root or inside it.
        assertNull(read(base.replace("<x:xmpmeta", "<!DOCTYPE x [<!ENTITY e \"boom\">]>\n<x:xmpmeta")))
        assertNull(read(base.replace("<dc:format>", "<!ENTITY e \"boom\"><dc:format>")))
        // An entity XML doesn't define, one with no end and a character XML doesn't allow.
        assertNull(read(base.replace("Annual report", "Annual&nbsp;report")))
        assertNull(read(base.replace("Annual report", "Annual & report")))
        assertNull(read(base.replace("Annual report", "Annual&#1;report")))
        // Tags that don't match, a prefix nothing declares, the same attribute twice.
        assertNull(read(base.replace("</dc:format>", "</dc:formats>")))
        assertNull(read(base.replace("<dc:format>application/pdf</dc:format>", "<q:format>application/pdf</q:format>")))
        assertNull(read(base.replace("<dc:format>", "<dc:format q:kind=\"1\">")))
        assertNull(read(base.replace("<rdf:Description rdf:about=\"\"", "<rdf:Description rdf:about=\"\" rdf:about=\"\"")))
        // Text or a second element after the root.
        assertNull(read("$base\nmore text"))
        assertNull(read("$base\n<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>"))

        // Elements 64 deep read; 65 deep don't (rdf:Description is the third level).
        fun nested(levels: Int) = base.replace("<dc:format>", "<e>".repeat(levels) + "</e>".repeat(levels) + "<dc:format>")
        assertEquals("Annual report", read(nested(61))?.title)
        assertNull(read(nested(62)))

        // Over XmpPacket.MAX_BYTES, made so by padding after the root, where padding is allowed.
        fun padded(size: Int) = base.toByteArray(Charsets.UTF_8).let { it + ByteArray(size - it.size) { ' '.code.toByte() } }
        assertEquals("Annual report", XmpPacket.read(padded(XmpPacket.MAX_BYTES))?.title)
        assertNull(XmpPacket.read(padded(XmpPacket.MAX_BYTES + 1)))

        // No rdf:RDF, or one more than three levels below the root.
        assertNull(read(base.replace("rdf:RDF", "rdf:Rdf")))
        fun wrapped(names: List<String>) = base
            .replace("<x:xmpmeta ", names.joinToString("") { "<$it>" } + "<x:xmpmeta ")
            .replace("</x:xmpmeta>", "</x:xmpmeta>" + names.reversed().joinToString("") { "</$it>" })
        assertNotNull(read(wrapped(listOf("b", "c"))))
        assertNull(read(wrapped(listOf("a", "b", "c"))))

        // UTF-32, either way round, with or without a byte order mark; Latin-1 bytes where UTF-8 should be.
        assertNull(read(base, Charset.forName("UTF-32BE")))
        assertNull(read(base, Charset.forName("UTF-32LE")))
        assertNull(XmpPacket.read(bytesOf(0x00, 0x00, 0xFE, 0xFF) + base.toByteArray(Charset.forName("UTF-32BE"))))
        assertNull(XmpPacket.read(bytesOf(0xFF, 0xFE, 0x00, 0x00) + base.toByteArray(Charset.forName("UTF-32LE"))))
        assertNull(read(base.replace("Annual report", "Café").replace('\uFEFF', ' '), Charsets.ISO_8859_1))

        // Cut off in a tag, in an attribute's value, in a comment, or with elements still open.
        assertNull(read(base.substring(0, base.indexOf("<dc:title") + 6)))
        assertNull(read(base.substring(0, base.indexOf("rdf:about=\"") + 11)))
        assertNull(read(base.substring(0, base.indexOf("<!-- padding") + 8)))
        assertNull(read(base.substring(0, base.indexOf("</rdf:RDF>"))))
        assertNull(XmpPacket.read(ByteArray(0)))
    }

    private fun bytesOf(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private companion object {
        /** A packet as Acrobat and InDesign write one, padding included. */
        val ACROBAT = """
            <?xpacket begin="${'\uFEFF'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 9.1-c001 79.675d0f7, 2023/06/11-19:21:16">
               <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                  <rdf:Description rdf:about=""
                        xmlns:xmp="http://ns.adobe.com/xap/1.0/"
                        xmlns:dc="http://purl.org/dc/elements/1.1/"
                        xmlns:pdf="http://ns.adobe.com/pdf/1.3/"
                        xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/">
                     <xmp:CreateDate>2026-11-12T15:30:12+05:30</xmp:CreateDate>
                     <xmp:ModifyDate>2026-11-12T16:00:00+05:30</xmp:ModifyDate>
                     <xmp:CreatorTool>Adobe InDesign 19.0 (Windows)</xmp:CreatorTool>
                     <dc:format>application/pdf</dc:format>
                     <dc:title>
                        <rdf:Alt>
                           <rdf:li xml:lang="fr-FR">Rapport annuel</rdf:li>
                           <rdf:li xml:lang="x-default">Annual report</rdf:li>
                        </rdf:Alt>
                     </dc:title>
                     <dc:creator>
                        <rdf:Seq>
                           <rdf:li>Asha Rao</rdf:li>
                           <rdf:li>Jean Dupont</rdf:li>
                        </rdf:Seq>
                     </dc:creator>
                     <dc:description>
                        <rdf:Alt>
                           <rdf:li xml:lang="x-default">Figures for 2026</rdf:li>
                        </rdf:Alt>
                     </dc:description>
                     <dc:subject>
                        <rdf:Bag>
                           <rdf:li>finance</rdf:li>
                           <rdf:li>report</rdf:li>
                        </rdf:Bag>
                     </dc:subject>
                     <pdf:Producer>Adobe PDF Library 17.0</pdf:Producer>
                     <pdfaid:part>2</pdfaid:part>
                     <pdfaid:conformance>B</pdfaid:conformance>
                  </rdf:Description>
               </rdf:RDF>
            </x:xmpmeta>
            <!-- padding -->
        """.trimIndent() + "\n" + (" ".repeat(99) + "\n").repeat(20) + "<?xpacket end=\"w\"?>"
    }
}
