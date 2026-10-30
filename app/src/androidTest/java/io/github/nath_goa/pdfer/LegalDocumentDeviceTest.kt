package io.github.nath_goa.pdfer

import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.pdfer.data.LegalDocument
import io.github.nath_goa.pdfer.data.loadLegalText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The About screen's legal texts are copied into the APK by the build; check they are there and readable. */
class LegalDocumentDeviceTest {
    private val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets

    @Test fun everyDocumentIsBundled() {
        for (document in LegalDocument.entries) {
            val blocks = document.load(assets)
            assertNotNull("${document.name} is missing from assets/legal/", blocks)
            assertTrue("${document.name} looks empty", blocks!!.size > 3)
        }
    }

    @Test fun licenseShowsPdfersNoticeBeforeTheGpl() {
        val texts = LegalDocument.LICENSE.load(assets)!!.map { it.text }
        val gpl = texts.indexOfFirst { it.startsWith("GNU GENERAL PUBLIC LICENSE") }
        assertTrue("The GPL text is there, after LICENSE-EXCEPTION.md", gpl > 0)
    }

    @Test fun missingFilesAreSkipped() {
        assertNull(loadLegalText(assets, listOf("no-such-file.md")))
        val withMissing = loadLegalText(assets, listOf("no-such-file.md", "PRIVACY.md"))
        assertEquals(LegalDocument.PRIVACY.load(assets), withMissing)
    }
}
