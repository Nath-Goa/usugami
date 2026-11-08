package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class XmlTokenGuardTest {

    private fun readAll(bytes: ByteArray) = XmlTokenGuard(ByteArrayInputStream(bytes)).use { it.readBytes().size }

    /**
     * A tag with thousands of attributes keeps Android's parser busy for minutes, in one call that
     * Cancel can't stop: it is refused in UTF-16 as in UTF-8, and so is a declaration that would have
     * the parser read the rest in UTF-16, while an ordinary part in UTF-16 is read.
     */
    @Test
    fun aCraftedPartIsRefusedWhateverItsEncoding() {
        val crafted = "<w:document" + (0..4096).joinToString("") { " xmlns:p$it=\"u\"" } + "/>"
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        assertThrows(IOException::class.java) { readAll(crafted.toByteArray(Charsets.UTF_8)) }
        assertThrows(IOException::class.java) { readAll(bom + crafted.toByteArray(Charsets.UTF_16LE)) }
        assertThrows(IOException::class.java) { readAll("<?xml version=\"1.0\"?>$crafted".toByteArray(Charsets.UTF_16BE)) }
        assertThrows(IOException::class.java) { readAll("<?xml version=\"1.0\" encoding=\"UTF-16\"?><a/>".toByteArray(Charsets.UTF_8)) }

        val ordinary = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><w:document xmlns:w=\"main\"><w:body/></w:document>"
        val utf16 = bom + ordinary.toByteArray(Charsets.UTF_16LE)
        assertEquals(utf16.size, readAll(utf16))
        val utf8 = ordinary.replace("UTF-16", "UTF-8").toByteArray(Charsets.UTF_8)
        assertEquals(utf8.size, readAll(utf8))
    }
}
