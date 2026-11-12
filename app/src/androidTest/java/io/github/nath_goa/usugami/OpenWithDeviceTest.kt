package io.github.nath_goa.usugami

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Usugami in Android's "Open with" list: offered once, under its own name, for PDFs and the documents
 * it converts, also when the app that has them gives no telling type but their address ends as
 * theirs does; and not for other files, old Office files or file: addresses.
 */
class OpenWithDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    /**
     * The entries of Usugami's own package offered to open [path] of [type] (none: no type). The
     * address is at a provider that doesn't exist, so Android finds no type for it but the one given.
     */
    private fun offered(path: String, type: String?, scheme: String = "content"): List<ResolveInfo> {
        val uri = Uri.parse("$scheme://${if (scheme == "content") NOWHERE else ""}$path")
        val intent = Intent(Intent.ACTION_VIEW).apply { if (type != null) setDataAndType(uri, type) else data = uri }
        return app.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).filter { it.activityInfo.packageName == app.packageName }
    }

    private fun assertOfferedOnce(path: String, type: String?) {
        val entries = offered(path, type)
        assertEquals("Offered once for $path as $type", 1, entries.size)
        val entry = entries.single()
        assertEquals(MainActivity::class.java.name, entry.activityInfo.name)
        assertEquals("Under Usugami's own name", app.packageManager.getApplicationLabel(app.applicationInfo).toString(), entry.loadLabel(app.packageManager).toString())
    }

    @Test fun usugamiIsOfferedOnceForTheFilesItOpens() {
        for (type in listOf(
            "application/pdf",
            "application/x-pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/epub+zip",
        )) {
            assertOfferedOnce("/document/1234", type)
        }
        // A type that tells nothing, or none: the address's ending tells, in any case and after any number of dots.
        for (type in listOf("application/octet-stream", "application/zip", null)) {
            for (path in listOf("/Report.pdf", "/a.b/c.d.PDF", "/x.docx", "/x.xlsx", "/x.pptx", "/x.epub")) assertOfferedOnce(path, type)
        }

        assertTrue("Not for any file", offered("/document/1234", "application/octet-stream").isEmpty())
        assertTrue("Not for a text file", offered("/a.txt", "text/plain").isEmpty())
        assertTrue("Not for an old Word file", offered("/old.doc", "application/msword").isEmpty())
        assertTrue("Not for a file: address", offered("/sdcard/Download/Report.pdf", "application/pdf", scheme = "file").isEmpty())
        assertTrue("Not for a file: address without a type", offered("/sdcard/Download/Report.pdf", null, scheme = "file").isEmpty())
    }

    private companion object {
        /** An authority no provider on the phone has. */
        const val NOWHERE = "io.github.nath_goa.usugami.test.nowhere"
    }
}
