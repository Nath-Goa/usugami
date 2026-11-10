package io.github.nath_goa.usugami.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import io.github.nath_goa.usugami.core.RecentFile
import io.github.nath_goa.usugami.core.RecentFileList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * A file just saved, for [RecentFiles.add]: where it went, its type, and the access that keeps it
 * reachable (see [RecentFile.grant]). [name] and [size] are what Usugami wrote, for when the place it
 * went doesn't say; a file whose name isn't known either way isn't listed.
 */
class SavedFile(val uri: Uri, val type: String, val grant: Uri?, val name: String?, val size: Long?)

/**
 * The files Usugami has saved, newest first, for Recent files on Home and on its own screen. The list
 * is kept in Usugami's private storage between launches (see [RecentFileList]) and looked over before
 * it shows: a file deleted meanwhile goes quietly, and so does one renamed, except in Downloads, where
 * it shows its new name. One list for the whole app (see [of]).
 *
 * Reaching a file again needs no permission. Usugami can always read what it put into Downloads, and
 * it keeps its access to a document made with the "Save as" screen, or to the folder a copy went into,
 * for as long as such a file is listed. It gives that access back once the file leaves the list, as
 * Android limits how many an app may keep; it keeps no other.
 */
class RecentFiles @VisibleForTesting internal constructor(context: Context) {
    private val resolver = context.contentResolver
    private val store = AtomicFile(File(context.filesDir, "recent_files"))
    private val saver by lazy { PdfSaver(context) }

    /** Writing the list and giving accesses back: one at a time, off the main thread. */
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + io)

    /** Looking files over: one check at a time, beside [io], so a slow place doesn't hold up the list. */
    private val looking = Dispatchers.IO.limitedParallelism(1)

    /** Guards the list, [checked], [round] and [unwritten], and keeps taking and giving back accesses in step with them. */
    private val lock = Any()

    private val _files = MutableStateFlow(read())
    val files: StateFlow<List<RecentFile>> = _files.asStateFlow()

    /** The addresses of the listed files Usugami can no longer read: opening one says so. */
    private val _unreachable = MutableStateFlow<Set<String>>(emptySet())
    val unreachable: StateFlow<Set<String>> = _unreachable.asStateFlow()

    /** The files looked over since Usugami was last in the background. */
    private val checked = HashSet<String>()

    /** Counts [checkAgain]s: a check begun before the last one doesn't count its files as looked over. */
    private var round = 0

    /** Whether the list has changed since it was last written. */
    private var unwritten = false

    init {
        scope.launch { giveBackUnused() }
    }

    /**
     * Lists the files [saved], in the order they were saved, so the last is on top, and keeps
     * Usugami's access to them where there is one to keep. Only the last [RecentFileList.MAX] can stay
     * listed, so only they are looked at. Blocking: call from a background thread.
     */
    fun add(saved: List<SavedFile>) {
        val now = System.currentTimeMillis()
        val files = saved.filter { it.uri.scheme == ContentResolver.SCHEME_CONTENT }.takeLast(RecentFileList.MAX).mapNotNull { file ->
            // Where it went says what it is named there: "Report (1).pdf" when "Report.pdf" was taken.
            val found = look(file.uri) as? Look.Found
            val name = found?.name ?: file.name ?: return@mapNotNull null
            RecentFile(file.uri.toString(), name, file.type, found?.size ?: file.size ?: -1, now, file.grant?.toString())
        }
        if (files.isEmpty()) return
        synchronized(lock) {
            // Kept inside the lock, so that a file leaving the list meanwhile can't give back an access just
            // kept. One a listed file kept already still holds when it can't be kept again.
            val held = _files.value.mapNotNullTo(HashSet()) { it.grant }
            val kept = files.mapNotNullTo(HashSet()) { it.grant }.filterTo(HashSet()) { keep(it) || it in held }
            val listed = files.map { if (it.grant == null || it.grant in kept) it else it.copy(grant = null) }
            change { RecentFileList.add(it, listed) }
            listed.mapTo(checked) { it.uri }
            _unreachable.value -= listed.map { it.uri }.toSet()
        }
    }

    /** Takes [file] off the list. The file itself stays where it is. */
    fun remove(file: RecentFile) {
        scope.launch { synchronized(lock) { change { list -> list.filterNot { it.uri == file.uri } } } }
    }

    /** Empties the list. The files themselves stay where they are. */
    fun clear() {
        scope.launch { synchronized(lock) { change { emptyList() } } }
    }

    /**
     * Looks for each listed file not looked for since Usugami was last in the background (see
     * [checkAgain]). One deleted goes from the list, and one renamed gets its new name or goes too (see
     * [RecentFiles]); one Usugami can no longer read, or whose place is out of reach, is marked
     * [unreachable]. One whose kept access Android has taken back goes as well: the phone's Files app
     * does that to a document, or a folder, it deletes or renames, and nothing brings it back.
     * A query or two each, for at most [RecentFileList.MAX] files.
     */
    suspend fun check() = withContext(looking) {
        val (due, begun) = synchronized(lock) { _files.value.filter { it.uri !in checked } to round }
        if (due.isEmpty()) return@withContext
        val looks = due.associate { it.uri to look(it.uri.toUri()) }
        synchronized(lock) {
            // Usugami went into the background meanwhile, where files may change again: they are looked for once more.
            if (begun == round) checked += looks.keys
            val held = heldGrants()
            change { list ->
                list.mapNotNull { file ->
                    when (val look = looks[file.uri]) {
                        null -> file
                        Look.Lost -> file.takeUnless { held != null && it.grant != null && it.grant !in held }
                        Look.Gone -> null
                        is Look.Found -> file.copy(name = look.name ?: file.name, size = look.size ?: file.size)
                    }
                }
            }
            _unreachable.value = _unreachable.value - looks.keys + looks.filterValues { it == Look.Lost }.keys
        }
    }

    /** Usugami went into the background, where files can be deleted, moved or renamed: they are looked for again. */
    fun checkAgain() {
        synchronized(lock) {
            checked.clear()
            round++
        }
    }

    /** Opens [file] in an app the user picks. */
    fun viewIntent(file: RecentFile): Intent = saver.viewIntent(file.uri.toUri(), file.type)

    /** Hands [file] to an app the user picks, as the result screen's Share does. */
    fun shareIntent(file: RecentFile): Intent = saver.shareIntent(listOf(file.uri.toUri()), file.name, file.type)

    /** For tests: returns once the changes made so far are written. */
    @VisibleForTesting
    internal suspend fun awaitWrites() {
        // Twice: a change waiting its turn on [io], as Clear list's does, starts its write there only once it runs.
        repeat(2) { withContext(io) {} }
    }

    /**
     * Puts [change] into effect, under [lock]: gives back the accesses no listed file needs any more,
     * and writes the list soon.
     */
    private fun change(change: (List<RecentFile>) -> List<RecentFile>) {
        val before = _files.value
        val after = change(before)
        if (after == before) return
        _files.value = after
        RecentFileList.unusedGrants(before, after).forEach(::giveBack)
        unwritten = true
        scope.launch { write() }
    }

    /** The list as it was last written, or none when there is none or it can't be read. */
    private fun read(): List<RecentFile> = try {
        RecentFileList.decode(store.readFully().decodeToString())
    } catch (_: IOException) {
        emptyList()
    }

    /** Writes the list as it is now, all at once: a write cut short leaves the last one whole. */
    private fun write() {
        val list = synchronized(lock) {
            if (!unwritten) return
            unwritten = false
            _files.value
        }
        var out: FileOutputStream? = null
        try {
            out = store.startWrite()
            out.write(RecentFileList.encode(list).encodeToByteArray())
            store.finishWrite(out)
        } catch (_: IOException) {
            // The storage is full, say. The last list written stays, and this one goes with the next change.
            out?.let(store::failWrite)
            synchronized(lock) { unwritten = true }
        }
    }

    /**
     * Gives back the accesses kept for files no longer listed: left when Android closed Usugami after it
     * kept one but before the list was written.
     */
    private fun giveBackUnused() {
        synchronized(lock) {
            val needed = _files.value.mapNotNullTo(HashSet()) { it.grant }
            val held = try {
                resolver.persistedUriPermissions.map { it.uri.toString() }
            } catch (_: Exception) {
                emptyList()
            }
            held.filterNot { it in needed }.forEach(::giveBack)
        }
    }

    /** The accesses Usugami still keeps, or null when Android doesn't say. Read under [lock], as they are taken. */
    private fun heldGrants(): Set<String>? = try {
        resolver.persistedUriPermissions.mapTo(HashSet()) { it.uri.toString() }
    } catch (_: Exception) {
        null
    }

    /** Keeps Usugami's access to [grant] across restarts. False when the place doesn't allow that. */
    private fun keep(grant: String): Boolean = try {
        resolver.takePersistableUriPermission(grant.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (_: Exception) {
        false
    }

    private fun giveBack(grant: String) {
        try {
            resolver.releasePersistableUriPermission(grant.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // It wasn't kept, or isn't any more.
        }
    }

    /**
     * Whether the file at [uri] is still there, with its name and size, has gone, or is out of Usugami's
     * reach. A place says a file has gone in a way of its own: with no row, with no answer at all, with
     * an error for one in a folder Usugami was given (Android's own storage does), or by still listing
     * it, as empty (Downloads does, in a folder of its own). Each is checked against the place, so that
     * a file isn't dropped, and its access given back, while its place is only out of reach.
     */
    private fun look(uri: Uri): Look {
        val found = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return Look.Gone
                val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getString)
                val size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
                Look.Found(name?.takeIf { it.isNotBlank() }, size)
            }
        } catch (_: SecurityException) {
            // Usugami's access has ended.
            return Look.Lost
        } catch (_: IllegalArgumentException) {
            // How Android's own storage answers for a file no longer in the folder Usugami was given.
            if (!DocumentsContract.isTreeUri(uri)) return Look.Lost
            null
        } catch (_: Exception) {
            // The place fails in a way of its own: the file stays listed rather than vanish over what may pass.
            return Look.Lost
        }
        return when {
            found == null -> if (placeAnswers(uri)) Look.Gone else Look.Lost
            // Usugami never saves an empty file.
            found.size == 0L && !opens(uri) -> Look.Gone
            else -> found
        }
    }

    /**
     * Whether the place [uri] is in still answers, so that its having nothing for the file means the
     * file has gone. For a file reached through a folder Usugami was given, the folder must be found:
     * one on an SD card taken out keeps its files listed. Otherwise the app that holds the file must be
     * there to ask, not disabled or being updated.
     */
    private fun placeAnswers(uri: Uri): Boolean = try {
        if (DocumentsContract.isTreeUri(uri)) {
            val folder = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            resolver.query(folder, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
        } else {
            resolver.acquireUnstableContentProviderClient(uri)?.use { true } == true
        }
    } catch (_: Exception) {
        false
    }

    /** Whether the file at [uri], which its place lists as empty, can still be opened. */
    private fun opens(uri: Uri): Boolean = try {
        resolver.openFileDescriptor(uri, "r")?.close()
        true
    } catch (_: FileNotFoundException) {
        false
    } catch (_: Exception) {
        // Something other than the file missing: it stays as it was found.
        true
    }

    /** What looking for a listed file found. */
    private sealed interface Look {
        class Found(val name: String?, val size: Long?) : Look
        data object Gone : Look
        data object Lost : Look
    }

    companion object {
        @Volatile
        private var instance: RecentFiles? = null

        /** The list, one for the whole app. */
        fun of(context: Context): RecentFiles =
            instance ?: synchronized(this) { instance ?: RecentFiles(context.applicationContext).also { instance = it } }
    }
}
