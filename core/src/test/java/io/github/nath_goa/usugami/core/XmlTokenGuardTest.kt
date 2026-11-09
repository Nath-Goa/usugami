package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class XmlTokenGuardTest {

    private fun readAll(bytes: ByteArray) = XmlTokenGuard(ByteArrayInputStream(bytes)).use { it.readBytes().size }

    private val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

    /**
     * A tag with thousands of attributes keeps Android's parser busy for minutes, in one call that
     * Cancel can't stop: it is refused in UTF-16 as in UTF-8, and so is a declaration that would have
     * the parser read the rest in UTF-16, while an ordinary part in UTF-16 is read. The parser reads
     * an XML declaration's attributes as it reads a tag's, all of them before it fails on more than
     * three, so one with thousands is refused too, however it is written.
     */
    @Test
    fun aCraftedPartIsRefusedWhateverItsEncoding() {
        val crafted = "<w:document" + (0..4096).joinToString("") { " xmlns:p$it=\"u\"" } + "/>"
        assertThrows(IOException::class.java) { readAll(crafted.toByteArray(Charsets.UTF_8)) }
        assertThrows(IOException::class.java) { readAll(bom + crafted.toByteArray(Charsets.UTF_16LE)) }
        assertThrows(IOException::class.java) { readAll("<?xml version=\"1.0\"?>$crafted".toByteArray(Charsets.UTF_16BE)) }
        assertThrows(IOException::class.java) { readAll("<?xml version=\"1.0\" encoding=\"UTF-16\"?><a/>".toByteArray(Charsets.UTF_8)) }

        val declaration = "<?xml version=\"1.0\"" + (0..1000).joinToString("") { " a$it=''" } + "?><a/>"
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        for (bytes in listOf(
            bom + declaration.toByteArray(Charsets.UTF_16LE),
            declaration.replace("<?xml", "<?XML").toByteArray(Charsets.UTF_8),
            utf8Bom + declaration.toByteArray(Charsets.UTF_8),
            declaration.replace("version", "x=\">\" version").toByteArray(Charsets.UTF_8),
        )) {
            assertThrows(IOException::class.java) { readAll(bytes) }
        }

        val ordinary = "<?xml version=\"1.0\" encoding=\"UTF-16\" standalone=\"yes\"?><w:document xmlns:w=\"main\"><w:body/></w:document>"
        val utf16 = bom + ordinary.toByteArray(Charsets.UTF_16LE)
        assertEquals(utf16.size, readAll(utf16))
        val utf8 = ordinary.replace("UTF-16", "UTF-8").toByteArray(Charsets.UTF_8)
        assertEquals(utf8.size, readAll(utf8))
    }

    /**
     * Namespaces declared on nested elements, each within the limit for one tag, pile up in the parser,
     * which goes through them all for each name it looks up: thousands on the elements open at once are
     * refused, while as many on elements one after another, each closed before the next, are read.
     */
    @Test
    fun attributesPilingUpOnOpenElementsAreRefused() {
        val level = (0 until 1000).joinToString("") { " xmlns:a$it=\"u\"" }
        val nested = "<w:document xmlns:w=\"m\">" + "<w:p$level>".repeat(5) + "</w:p>".repeat(5) + "</w:document>"
        assertThrows(IOException::class.java) { readAll(nested.toByteArray()) }
        val apart = "<w:document xmlns:w=\"m\">" + "<w:p$level></w:p>".repeat(5) + "<w:p$level/>".repeat(5) + "</w:document>"
        assertEquals(apart.length, readAll(apart.toByteArray()))
    }

    /**
     * A comment, CDATA or instruction ends where the parser ends it, so a quote in one can't hide the
     * next tag's attributes. Text is counted on across them, as the parser joins it, copying what it has
     * so far at each: neither ">" in text nor many comments breaking it up keep it from the limits.
     */
    @Test
    fun commentsAndTextCantHideMarkupFromTheCount() {
        val many = (0..4096).joinToString("") { " a$it=\"\"" }
        for (hiding in listOf("<!-- > <a ' -->", "<![CDATA[ > <a ' ]]>", "<?pi > <a ' ?>")) {
            assertThrows(IOException::class.java) { readAll("<w:document>$hiding<w:p$many/><!-- ' --></w:document>".toByteArray()) }
        }
        assertThrows(IOException::class.java) { readAll(("<a>" + "x>".repeat(5 * 1024 * 1024) + "</a>").toByteArray()) }
        assertThrows(IOException::class.java) { readAll(("<a>" + ("x".repeat(1000) + "<!---->").repeat(1000) + "</a>").toByteArray()) }

        val ordinary = "<?xml version=\"1.0\"?><?mso-application progid=\"Word.Document\"?><w:document xmlns:w=\"m\">" +
            "<!-- a \"quote\" and > --><w:t a=\"1>2\">x &gt; y > z<![CDATA[<b c='>']]></w:t><w:p/></w:document>"
        assertEquals(ordinary.length, readAll(ordinary.toByteArray()))
    }
}
