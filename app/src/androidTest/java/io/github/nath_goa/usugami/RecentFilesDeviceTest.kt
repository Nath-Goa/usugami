package io.github.nath_goa.usugami

import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.data.PdfPrinting
import io.github.nath_goa.usugami.data.PdfSaver
import io.github.nath_goa.usugami.data.RecentFiles
import io.github.nath_goa.usugami.data.SavedFile
import io.github.nath_goa.usugami.ui.KeptResult
import io.github.nath_goa.usugami.ui.MainViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Recent files: each file saved is listed, once however often it is saved, and Home shows it; a file
 * deleted in another app goes from the list, also one saved into a folder the user picked; a PDF on
 * the list opens in the reader, and the list asks another app to share a file, with access to read it,
 * and removing a file or clearing
 * the list leaves the files where they are; and a document made with the system's "Save as" screen
 * stays reachable once the screen that gave Usugami access to it has gone, as after a restart, or else
 * says it can't be opened and offers to remove it.
 */
class RecentFilesDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val automation = instrumentation.uiAutomation
    private val recent = RecentFiles.of(app)
    private val dir = File(app.cacheDir, "test-recent").apply { mkdirs() }
    private var activity: MainActivity? = null

    /** What the tests put into Downloads, deleted afterwards. */
    private val made = ArrayList<Uri>()

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(100)
        }
    }

    private fun clearList() {
        recent.clear()
        runBlocking { recent.awaitWrites() }
    }

    @Before fun setUp() = clearList()

    @After fun tearDown() {
        activity?.let { onMain { it.finish() } }
        made.forEach { app.contentResolver.delete(it, null, null) }
        clearList()
        dir.deleteRecursively()
    }

    /** Starts Usugami on Home, in the phone's language, as [app]'s strings are. */
    private fun launch(): MainActivity {
        AppLanguages.skipChoice(app)
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return (instrumentation.startActivitySync(intent) as MainActivity).also { activity = it }
    }

    /** Every node in the window that has focus (a dialog's, while one is open; another app's while it shows). */
    private fun nodes(): List<AccessibilityNodeInfo> {
        val all = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            all += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        automation.rootInActiveWindow?.let(::walk)
        return all
    }

    private fun find(what: String, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        waitUntil(what) {
            found = nodes().firstOrNull(match)
            found != null
        }
        return found!!
    }

    private fun AccessibilityNodeInfo.says(text: String) = this.text?.toString() == text || contentDescription?.toString() == text

    /** [node], or the clickable item it is part of, such as the button around a label. */
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return checkNotNull(target) { "Nothing to click at $node" }
    }

    private fun click(node: AccessibilityNodeInfo) = assertTrue(clickable(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))

    /**
     * The row of [name] on the list, which a screen reader reads as one item named by the file, then
     * when it was saved and its size, and which carries the actions.
     */
    private fun row(name: String) = clickable(find("the row of $name") { it.contentDescription?.startsWith("$name, ") == true })

    private fun AccessibilityNodeInfo.act(label: String) =
        assertTrue(performAction(checkNotNull(actionList.firstOrNull { it.label == label }) { "No \"$label\" action in ${actionList.map { it.label }}" }.id))

    /** Catches the next screen of another app that Usugami starts with [action], instead of starting it. */
    private class Intercept(private val action: String) : Instrumentation.ActivityMonitor() {
        @Volatile var intent: Intent? = null

        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != action) return null
            this.intent = intent
            return Instrumentation.ActivityResult(0, null)
        }
    }

    private fun intercepted(action: String, start: () -> Unit): Intent {
        val monitor = Intercept(action)
        instrumentation.addMonitor(monitor)
        try {
            start()
            waitUntil("$action to start") { monitor.intent != null }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        return monitor.intent!!
    }

    private fun exists(uri: Uri) = app.contentResolver.query(uri, null, null, null, null)?.use { it.moveToFirst() } == true

    @Test fun aSavedResultIsListedOnceOpensAndSharesFromHomeAndGoesWhenDeleted() {
        assumeTrue("Android 10 and newer save into Downloads", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        // A real PDF, which the reader shows.
        val pdf = File(app.cacheDir, "output/images/Recent test.pdf").apply {
            parentFile!!.mkdirs()
            writeBytes(instrumentation.context.assets.open("alpha.pdf").use { it.readBytes() })
        }
        val vm = onMain { MainViewModel(app, SavedStateHandle(mapOf(KeptResult.KEY to KeptResult.pack(listOf(PdfFile(pdf, 1, 0)))))) }
        runBlocking { vm.awaitFileWork() }
        // Saved a second time, it is still one file, in Downloads and on the list.
        repeat(2) {
            onMain { vm.saveToDownloads() }
            runBlocking { withTimeout(20_000) { vm.messages.first() } }
        }
        val file = recent.files.value.single()
        val uri = Uri.parse(file.uri)
        made += uri
        assertTrue(file.name, file.name.startsWith("Recent test"))
        assertEquals(PdfSaver.PDF_MIME, file.type)
        assertEquals(pdf.length(), file.size)
        assertNull("Usugami can always read what it put into Downloads", file.grant)
        onMain { vm.startOver() }
        runBlocking { vm.awaitFileWork() }

        launch()
        val heading = find("the list's heading") { it.says(app.getString(R.string.recent_title)) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) assertTrue("A screen reader can jump to the list", heading.isHeading)
        // A tap shows it in the reader, with its name in the bar; Back returns to Home.
        click(row(file.name))
        find("the reader, showing its pages") { it.says(app.getString(R.string.reader_page_of, 1, 3)) }
        find("its name in the bar") { it.says(file.name) }
        onMain { activity!!.onBackPressedDispatcher.onBackPressed() }
        find("Home, with the list") { it.says(app.getString(R.string.recent_title)) }
        // Another app's screen opens once per burst of taps: the next one is a moment later.
        Thread.sleep(1_100)
        val chooser = intercepted(Intent.ACTION_CHOOSER) { row(file.name).act(app.getString(R.string.share)) }
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(uri, IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(PdfSaver.PDF_MIME, send.type)
        assertTrue((send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        val excluded = IntentCompat.getParcelableArrayExtra(chooser, Intent.EXTRA_EXCLUDE_COMPONENTS, ComponentName::class.java)
        assertTrue("Not shared to Usugami itself", excluded!!.contains(ComponentName(app, MainActivity::class.java)))
        if (PdfPrinting.available(app)) assertTrue("A PDF can be printed", row(file.name).actionList.any { it.label == app.getString(R.string.print) })

        // Deleted in another app, it goes from the list when Usugami looks again.
        app.contentResolver.delete(uri, null, null)
        recent.checkAgain()
        runBlocking { recent.check() }
        assertTrue(recent.files.value.isEmpty())
        waitUntil("Home to leave out the list") { nodes().none { it.says(app.getString(R.string.recent_title)) } }
    }

    @Test fun removingAFileAndClearingTheListLeaveTheFilesInDownloads() {
        assumeTrue("Android 10 and newer save into Downloads", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val pdfs = listOf("Recent A.pdf", "Recent B.pdf").map { File(dir, it).apply { writeText("%PDF-1.4 $it") } }
        val copies = PdfSaver(app).saveToDownloads(pdfs)
        made += copies.map { it.uri }
        assertEquals("The last saved first", copies.reversed().map { it.uri.toString() }, recent.files.value.map { it.uri })

        launch()
        row(copies[0].name).act(app.getString(R.string.recent_remove))
        waitUntil("the file to leave the list") { recent.files.value.map { it.uri } == listOf(copies[1].uri.toString()) }
        assertTrue("The file itself stays", exists(copies[0].uri))

        click(find("See all") { it.says(app.getString(R.string.recent_see_all)) })
        click(find("Clear list") { it.says(app.getString(R.string.recent_clear)) })
        find("the question") { it.says(app.getString(R.string.recent_clear_title)) }
        click(find("Clear list in the question") { it.says(app.getString(R.string.recent_clear)) })
        find("the empty list") { it.says(app.getString(R.string.recent_empty)) }
        assertTrue(recent.files.value.isEmpty())
        assertTrue("The file itself stays", exists(copies[1].uri))
    }

    /**
     * Saved with the system's "Save as" screen, a document stays reachable once that screen's access
     * has gone with the activity it was given to: Usugami kept it, also for the list read back as after
     * a restart. Once Android takes that access back, as when the Files app deletes the document, it
     * goes from the list; listed without an access Usugami could keep, it stays, and opening it says it
     * can't be opened and offers to remove it.
     */
    @Test fun aSaveAsDocumentStaysReachableOnceTheScreenThatGaveItHasGone() {
        val name = "Usugami-recent-test.pdf"
        val pdf = File(dir, name).apply { writeText("%PDF-1.4 save as") }
        // Left by a run stopped part way, it would make the screen name the new one "(1)".
        shell("rm -f /sdcard/Download/$name")
        val first = launch()
        val picked = CompletableDeferred<Uri?>()
        // In Downloads: the screen otherwise opens where it was last used, such as the folder another test picked.
        val inDownloads = object : ActivityResultContracts.CreateDocument(PdfSaver.PDF_MIME) {
            override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(DOWNLOADS_DOCUMENTS, "downloads"))
        }
        val createDocument = onMain { first.activityResultRegistry.register("recent-test", inDownloads) { picked.complete(it) } }
        onMain { createDocument.launch(name) }
        // The system's own screen, which only its Save button leaves with a document.
        click(find("the Save button") { node -> node.text?.toString().equals("save", ignoreCase = true) && node.isEnabled })
        val uri = checkNotNull(runBlocking { withTimeout(20_000) { picked.await() } }) { "No document was made" }
        onMain { createDocument.unregister() }
        try {
            PdfSaver(app).saveTo(pdf, uri)
            val listed = recent.files.value.first()
            assertEquals(uri.toString(), listed.uri)
            assertEquals(uri.toString(), listed.grant)

            onMain { first.finish() }
            activity = null
            waitUntil("the activity to be gone") { first.isDestroyed }
            assertTrue(app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
            assertArrayEquals(pdf.readBytes(), app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            runBlocking { recent.awaitWrites() }
            val restarted = RecentFiles(app)
            runBlocking { restarted.check() }
            assertEquals(listed.uri, restarted.files.value.first().uri)
            assertTrue(listed.uri !in restarted.unreachable.value)

            // Android took back the access Usugami kept, as when the Files app deletes or renames
            // the document: nothing brings it back, and the file goes from the list.
            app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            waitUntil("the document to leave the list") {
                recent.checkAgain()
                runBlocking { recent.check() }
                recent.files.value.isEmpty()
            }

            // In a place that didn't let Usugami keep its access, the file stays listed, and opening it
            // says why it can't be.
            recent.add(listOf(SavedFile(uri, PdfSaver.PDF_MIME, grant = null, name, pdf.length())))
            waitUntil("Usugami to find it can't read the document") {
                recent.checkAgain()
                runBlocking { recent.check() }
                listed.uri in recent.unreachable.value
            }
            assertEquals("Still listed", listOf(listed.uri), recent.files.value.map { it.uri })
            launch()
            click(row(listed.name))
            find("the explanation") { it.says(app.getString(R.string.recent_lost_title)) }
            click(find("Remove from list") { it.says(app.getString(R.string.recent_remove)) })
            waitUntil("the file to leave the list") { recent.files.value.isEmpty() }
        } finally {
            // Usugami no longer has access to the document: the shell deletes it from Downloads.
            shell("rm -f /sdcard/Download/$name")
        }
    }

    /**
     * Saved into a folder picked with the system's folder picker, a file deleted in another app goes
     * from the list, though Android's storage answers for it with an error rather than with nothing,
     * and Usugami gives back its access to the folder, which no listed file needs any more. So does a
     * file whose folder the Files app deleted, which takes that access away.
     */
    @Test fun aFileSavedIntoAPickedFolderGoesWhenDeleted() {
        val folder = "Documents/Usugami-recent-test"
        val pdf = File(dir, "Usugami-recent-folder.pdf").apply { writeText("%PDF-1.4 folder") }
        shell("rm -rf /sdcard/$folder")
        shell("mkdir -p /sdcard/$folder")
        val first = launch()
        val picked = CompletableDeferred<Uri?>()
        val pickFolder = onMain {
            first.activityResultRegistry.register("recent-folder-test", ActivityResultContracts.OpenDocumentTree()) { picked.complete(it) }
        }
        onMain { pickFolder.launch(DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_DOCUMENTS, "primary:$folder")) }
        try {
            // The system's own screens, which open in the folder and ask whether to allow access to it.
            click(find("Use this folder") { it.text?.toString().equals("use this folder", ignoreCase = true) && it.isEnabled })
            click(find("Allow") { it.text?.toString().equals("allow", ignoreCase = true) })
            val tree = checkNotNull(runBlocking { withTimeout(20_000) { picked.await() } }) { "No folder was picked" }
            onMain { pickFolder.unregister() }
            PdfSaver(app).saveToFolder(listOf(pdf), tree)
            assertEquals(tree.toString(), recent.files.value.single().grant)
            assertTrue(app.contentResolver.persistedUriPermissions.any { it.uri == tree })

            shell("rm /sdcard/$folder/${pdf.name}")
            recent.checkAgain()
            runBlocking { recent.check() }
            assertTrue(recent.files.value.isEmpty())
            assertTrue(app.contentResolver.persistedUriPermissions.none { it.uri == tree })

            // The folder itself deleted as the phone's Files app does it, which takes Usugami's access to
            // it away: a file saved into it goes too, rather than staying as one that can't be opened.
            PdfSaver(app).saveToFolder(listOf(pdf), tree)
            assertEquals(tree.toString(), recent.files.value.single().grant)
            DocumentsContract.deleteDocument(
                app.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
            )
            recent.checkAgain()
            runBlocking { recent.check() }
            assertTrue(recent.files.value.isEmpty())
        } finally {
            shell("rm -rf /sdcard/$folder")
        }
    }

    /** Runs [command] in the shell, which may delete what Usugami no longer has access to. */
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }

    private companion object {
        /** The system's Downloads, as its file screens reach it. */
        const val DOWNLOADS_DOCUMENTS = "com.android.providers.downloads.documents"

        /** The phone's own storage, as its file screens reach it. */
        const val EXTERNAL_STORAGE_DOCUMENTS = "com.android.externalstorage.documents"
    }
}
