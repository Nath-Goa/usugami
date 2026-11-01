package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubFormatsTest {
    @Test fun knowsHtmlsNamedCharacters() {
        val e = HtmlEntities.all
        assertEquals("\u00A0", e["nbsp"])
        assertEquals("é", e["eacute"])
        // The Latin-1 run ends exactly at ÿ, so none of its names is missing or out of place.
        assertEquals("ÿ", e["yuml"])
        assertEquals("×", e["times"])
        assertEquals("Ω", e["Omega"])
        assertEquals("ς", e["sigmaf"])
        assertEquals("ω", e["omega"])
        assertEquals("—", e["mdash"])
        assertEquals("…", e["hellip"])
        assertEquals("€", e["euro"])
        assertEquals("♦", e["diams"])
        assertEquals(253, e.size)
    }

    @Test fun resolvesLinksInsideTheBook() {
        assertEquals("OEBPS/text/ch2.xhtml" to null, EpubPaths.resolve("OEBPS/text/ch1.xhtml", "ch2.xhtml"))
        assertEquals("OEBPS/images/cover.jpg" to null, EpubPaths.resolve("OEBPS/text/ch1.xhtml", "../images/cover.jpg"))
        assertEquals("OEBPS/text/ch2.xhtml" to "sec 1", EpubPaths.resolve("OEBPS/text/ch1.xhtml", "ch2.xhtml#sec%201"))
        assertEquals("OEBPS/text/ch1.xhtml" to "note3", EpubPaths.resolve("OEBPS/text/ch1.xhtml", "#note3"))
        assertEquals("text/My Chapter.xhtml" to null, EpubPaths.resolve("content.opf", "text/My%20Chapter.xhtml"))
        assertEquals("images/a.png" to null, EpubPaths.resolve("OEBPS/x.xhtml", "/images/a.png"))
        assertEquals("C++.xhtml" to null, EpubPaths.resolve("a.opf", "C++.xhtml"))
        assertEquals("é.xhtml" to null, EpubPaths.resolve("a.opf", "%C3%A9.xhtml"))
        assertNull(EpubPaths.resolve("a.xhtml", "https://example.com/"))
        assertNull(EpubPaths.resolve("a.xhtml", "mailto:someone@example.com"))
        assertNull(EpubPaths.resolve("a.xhtml", "  "))
    }

    @Test fun fontObfuscationIsNotDrm() {
        assertFalse(EpubProtection.isProtected(false, emptyList()))
        assertFalse(EpubProtection.isProtected(false, listOf("http://www.idpf.org/2008/embedding", " http://ns.adobe.com/pdf/enc#RC ")))
        assertTrue(EpubProtection.isProtected(false, listOf("http://www.idpf.org/2008/embedding", "http://www.w3.org/2001/04/xmlenc#aes128-cbc")))
        assertTrue(EpubProtection.isProtected(true, emptyList()))
    }

    @Test fun findsTheCharacterSet() {
        fun charset(text: String) = text.toByteArray(Charsets.ISO_8859_1).let { XmlPrefix.charset(it, it.size) }
        assertEquals("UTF-8", charset("<html/>"))
        assertEquals("ISO-8859-1", charset("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><html/>"))
        assertEquals("UTF-8", charset("<?xml version='1.0' encoding='no-such-charset'?><html/>"))
        assertEquals("UTF-16LE", XmlPrefix.charset(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), '<'.code.toByte(), 0), 4))
        assertEquals("UTF-16BE", XmlPrefix.charset(byteArrayOf(0, '<'.code.toByte(), 0, '?'.code.toByte()), 4))
    }

    @Test fun dropsTheDoctypeAndWhatItDeclares() {
        val xhtml = """<?xml version="1.0"?>
            <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
            <html><body/></html>"""
        val stripped = XmlPrefix.stripDoctype(xhtml)!!
        assertFalse("DOCTYPE" in stripped)
        assertTrue(stripped.trimEnd().endsWith("<html><body/></html>"))
        val entities = """<!DOCTYPE html [<!ENTITY a "aaaa"><!ENTITY b "&a;&a;&a;">]><html>&b;</html>"""
        assertEquals("<html>&b;</html>", XmlPrefix.stripDoctype(entities))
        // A quoted ">" doesn't end it.
        assertEquals("<html/>", XmlPrefix.stripDoctype("""<!doctype html SYSTEM "a>b"><html/>"""))
        assertEquals("<!-- note --><html/>", XmlPrefix.stripDoctype("<!-- note --><html/>"))
        // Unfinished, or the root element doesn't start in the text given: refused.
        assertNull(XmlPrefix.stripDoctype("""<!DOCTYPE html [<!ENTITY a "x"> """))
        assertNull(XmlPrefix.stripDoctype("<?xml version=\"1.0\"?><!-- a long comment"))
    }

    @Test fun collapsesWhiteSpaceAsHtmlDoes() {
        assertEquals("one two three ", HtmlText.collapse("one \n two\t\tthree  ", afterSpace = false))
        assertEquals("word", HtmlText.collapse("   word", afterSpace = true))
        assertEquals(" word", HtmlText.collapse("   word", afterSpace = false))
        assertEquals("a\u00A0\u00A0b", HtmlText.collapse("a\u00A0\u00A0b", afterSpace = false))
        assertEquals("", HtmlText.collapse(" \n ", afterSpace = true))
    }

    @Test fun readsViewportsAndListStyles() {
        assertEquals(1200 to 800, HtmlText.viewport("width=1200, height=800"))
        assertEquals(600 to 900, HtmlText.viewport("height=900;width=600.5"))
        assertNull(HtmlText.viewport("width=device-width, initial-scale=1"))
        assertEquals(ListNumbering.Format.LOWER_ROMAN, HtmlText.listFormat("lower-roman", ordered = true))
        assertEquals(ListNumbering.Format.UPPER_LETTER, HtmlText.listFormat("A", ordered = true))
        assertEquals(ListNumbering.Format.DECIMAL, HtmlText.listFormat(null, ordered = true))
        assertEquals(ListNumbering.Format.BULLET, HtmlText.listFormat(null, ordered = false))
        assertNull(HtmlText.listFormat("none", ordered = true))
    }

    @Test fun columnsShareTheWidthByTheirText() {
        val widths = HtmlText.columnWidths(listOf(10f, 30f), 400f)
        assertEquals(400f, widths.sum(), 0.01f)
        assertTrue(widths[1] > widths[0])
        // A column with a huge cell doesn't squeeze the others to nothing.
        val skewed = HtmlText.columnWidths(listOf(1f, 1f, 5000f), 300f)
        assertTrue(skewed[0] >= 300f / 3 * 0.5f * 0.9f)
        assertEquals(300f, skewed.sum(), 0.01f)
    }

    private fun node(tag: String, classes: String = "", id: String? = null, parent: CssNode? = null) =
        CssNode(tag, id, classes.split(' ').filter { it.isNotEmpty() }.toSet(), parent)

    @Test fun matchesSimpleSelectors() {
        val body = node("body")
        val chapter = node("div", "chapter", parent = body)
        val heading = node("h1", "title", "c1", parent = chapter)
        val para = node("p", "first", parent = node("blockquote", parent = chapter))
        assertTrue(SimpleCss.selector("h1")!!.matches(heading))
        assertTrue(SimpleCss.selector("h1.title")!!.matches(heading))
        assertTrue(SimpleCss.selector("#c1")!!.matches(heading))
        assertTrue(SimpleCss.selector(".chapter h1")!!.matches(heading))
        assertTrue(SimpleCss.selector("div > h1")!!.matches(heading))
        assertTrue(SimpleCss.selector("div p.first")!!.matches(para))
        assertFalse(SimpleCss.selector("div > p")!!.matches(para))
        assertFalse(SimpleCss.selector("h2")!!.matches(heading))
        assertFalse(SimpleCss.selector("h1.other")!!.matches(heading))
        assertTrue(SimpleCss.selector("*")!!.matches(para))
        // Left out rather than misapplied.
        assertNull(SimpleCss.selector("p:first-child"))
        assertNull(SimpleCss.selector("a[href]"))
        assertNull(SimpleCss.selector("h1 + p"))
        assertEquals(111, SimpleCss.selector("div.chapter #c1")!!.specificity)
    }

    @Test fun cascadesStyleSheetsAndStyleAttributes() {
        val sheet = SimpleCss.parse(
            """
            /* comment { p { color: red } */
            @import url(other.css);
            @font-face { font-family: X; src: url(x.ttf) }
            @media print { p { text-align: right } }
            p { text-align: justify; text-indent: 1.5em; margin: 0 }
            p.center, .title { text-align: center !important }
            p:first-child { text-indent: 0 }
            #intro { font-style: italic }
            """.trimIndent(),
        )
        val p = node("p", "center", "intro", node("body"))
        val result = CssSheet.cascade(p, listOf(sheet), "font-weight: bold")
        assertEquals("center", result["text-align"])
        assertEquals("1.5em", result["text-indent"])
        assertEquals("italic", result["font-style"])
        assertEquals("bold", result["font-weight"])
        assertEquals("0", result["margin"])
        val plain = CssSheet.cascade(node("p"), listOf(sheet), null)
        assertEquals("justify", plain["text-align"])
        // A later sheet wins over an earlier one at the same specificity.
        val later = SimpleCss.parse("p { text-align: left }")
        assertEquals("left", CssSheet.cascade(node("p"), listOf(sheet, later), null)["text-align"])
        assertTrue(SimpleCss.parse("garbage { without end").isEmpty)
    }

    @Test fun measuresCssLengths() {
        val base = 11f
        assertEquals(22f, CssValues.length("2em", 11f, base)!!, 0.001f)
        assertEquals(11f, CssValues.length("16px", 11f, base)!!, 0.001f)
        assertEquals(11f, CssValues.length("12pt", 11f, base)!!, 0.001f)
        assertEquals(30f, CssValues.length("10%", 11f, base, 300f)!!, 0.001f)
        assertNull(CssValues.length("10%", 11f, base))
        assertEquals(0f, CssValues.length("0", 11f, base)!!, 0.001f)
        assertNull(CssValues.length("auto", 11f, base))
        assertEquals(22f, CssValues.fontSize("200%", 11f, base)!!, 0.001f)
        assertEquals(base * 1.5f, CssValues.fontSize("x-large", 20f, base)!!, 0.001f)
        assertEquals(base * 3f, CssValues.fontSize("100px", 11f, base)!!, 0.001f)
        assertEquals(true, CssValues.bold("700"))
        assertEquals(false, CssValues.bold("normal"))
        assertNull(CssValues.bold("inherit"))
        assertEquals("1em" to "2em", CssValues.verticalMargins("1em 0 2em"))
        assertEquals("3em" to "1em", CssValues.horizontalMargins("0 1em 0 3em"))
        assertEquals("serif", CssValues.fontFamily("\"Libre Baskerville\", serif"))
        assertEquals("sans-serif", CssValues.fontFamily("MyFont, sans-serif"))
        assertEquals("monospace", CssValues.fontFamily("'Courier New'"))
        assertNull(CssValues.fontFamily("MyFont"))
        assertTrue(CssValues.pageBreak("always"))
        assertTrue(CssValues.pageBreak("page"))
        assertFalse(CssValues.pageBreak("avoid"))
    }
}
