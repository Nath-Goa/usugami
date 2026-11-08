package io.github.nath_goa.usugami.data

import android.content.ClipData
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import io.github.nath_goa.usugami.MainActivity
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/** Saving finished PDFs (and ZIP files of them) to shared storage, where Recent files lists them, and handing them to other apps. */
class PdfSaver(private val context: Context) {
    private val resolver = context.contentResolver

    /** Where each file saved is listed, so it can be found again from Home. */
    private val recent by lazy { RecentFiles.of(context) }

    /**
     * Copies [files] into the public Downloads folder and returns the copies. A file [earlier] says
     * (at its place, or null) was copied there already, and still is, isn't copied again: saving a
     * result a second time (a second tap, say) doesn't add "Name (1).pdf". [onCopied] is told of each
     * copy as soon as it is there, by the file's place: when a later file fails (the storage is full,
     * say), those copied before it stay in Downloads, and saving again mustn't copy them twice. Each
     * copy goes on the Recent files list, also those made before a failure.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToDownloads(
        files: List<File>,
        earlier: List<DownloadsCopy?>? = null,
        onCopied: (Int, DownloadsCopy) -> Unit = { _, _ -> },
    ): List<DownloadsCopy> {
        val saved = ArrayList<SavedFile>()
        try {
            return files.mapIndexed { i, file ->
                (earlier?.getOrNull(i)?.let(::stillInDownloads) ?: saveToDownloads(file)).also {
                    // Usugami can always read what it put into Downloads: there is no access to keep.
                    saved += SavedFile(it.uri, PDF_MIME, grant = null, it.name, file.length())
                    onCopied(i, it)
                }
            }
        } finally {
            recent.add(saved)
        }
    }

    /** Copies [file] into the public Downloads folder. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToDownloads(file: File): DownloadsCopy {
        requireExists(listOf(file))
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, PDF_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
        try {
            copy(file, uri)
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        // MediaStore adds " (1)" etc. when the name is taken; report the real one.
        return stillInDownloads(DownloadsCopy(uri, file.name)) ?: DownloadsCopy(uri, file.name)
    }

    /** [copy] with the name it has now, or null once it has gone from Downloads (the user deleted it, say). */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun stillInDownloads(copy: DownloadsCopy): DownloadsCopy? = try {
        resolver.query(copy.uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) DownloadsCopy(copy.uri, c.getString(0) ?: copy.name) else null
        }
    } catch (_: Exception) {
        null
    }

    /** Writes [file] into a document the user created with the system "Save as" screen. */
    fun saveTo(file: File, target: Uri) {
        // The picker makes a new, empty document; one the user chose to overwrite isn't empty and
        // is never deleted.
        val created = sizeOf(target) == 0L
        if (!file.isFile) {
            // The system cleared the PDF from the cache while the picker was open.
            if (created) delete(target)
            throw FileNotFoundException(file.name)
        }
        try {
            copy(file, target)
        } catch (e: Exception) {
            // Don't leave a half-written PDF behind (the storage may have run full).
            if (created) delete(target)
            throw e
        }
        recent.add(listOf(SavedFile(target, PDF_MIME, grant = target, file.name, file.length())))
    }

    /**
     * Writes into a document the user created with the system "Save as" screen: [write] gets its
     * stream and makes the content, of [type], from [files]. A new document is deleted again if that
     * fails or is cancelled. One the user chose to overwrite is made in the cache first and copied over
     * only once it is complete, so a cancel or a failure while making it leaves the document as it was.
     */
    fun writeTo(target: Uri, files: List<File>, type: String, write: (OutputStream) -> Unit) {
        val created = sizeOf(target) == 0L
        try {
            // Checked first, so a document the user chose to overwrite isn't emptied for nothing.
            requireExists(files)
            if (created) {
                openForWriting(target).use(write)
            } else {
                withTempFile { temp ->
                    temp.outputStream().use(write)
                    copy(temp, target)
                }
            }
        } catch (e: Exception) {
            if (created) delete(target)
            throw e
        }
        recent.add(listOf(SavedFile(target, type, grant = target, name = null, size = null)))
    }

    /**
     * Deletes the temporary files left behind when Android closed Usugami in the middle of a save
     * (a whole ZIP file of results can be large); those of saves going on now stay.
     */
    fun clearLeftovers() {
        synchronized(tempFiles) {
            File(context.cacheDir, "saving").listFiles()?.forEach { if (it.absolutePath !in tempFiles) it.delete() }
        }
    }

    /** Runs [block] with a new, empty file in the cache, which is deleted again afterwards. */
    private inline fun <T> withTempFile(block: (File) -> T): T {
        val temp = synchronized(tempFiles) {
            clearLeftovers()
            val dir = File(context.cacheDir, "saving")
            dir.mkdirs()
            File.createTempFile("save", ".tmp", dir).also { tempFiles += it.absolutePath }
        }
        try {
            return block(temp)
        } finally {
            temp.delete()
            synchronized(tempFiles) { tempFiles -= temp.absolutePath }
        }
    }

    /**
     * Creates a copy of each file inside a folder the user picked with the system folder picker. A
     * file [earlier] says (at its place, or null) was copied into that folder already, and still is,
     * isn't copied again; [onCopied] is told of each copy as soon as it is there, as in
     * [saveToDownloads]: when a later file fails, saving again into that folder mustn't add
     * "Name (1).pdf" for those before it.
     */
    fun saveToFolder(
        files: List<File>,
        tree: Uri,
        earlier: List<Uri?>? = null,
        onCopied: (Int, Uri) -> Unit = { _, _ -> },
    ) {
        requireExists(files)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        // Each copy goes on the Recent files list, reached through the folder, also those made before a failure.
        val saved = ArrayList<SavedFile>()
        try {
            files.forEachIndexed { i, file ->
                val target = earlier?.getOrNull(i)?.takeIf(::stillThere) ?: copyInto(parent, file)
                saved += SavedFile(target, PDF_MIME, grant = tree, file.name, file.length())
                onCopied(i, target)
            }
        } finally {
            recent.add(saved)
        }
    }

    /** A new copy of [file] in the folder [parent]. */
    private fun copyInto(parent: Uri, file: File): Uri {
        val target = DocumentsContract.createDocument(resolver, parent, PDF_MIME, file.name)
            ?: throw IOException("Could not create ${file.name}")
        try {
            copy(file, target)
        } catch (e: Exception) {
            delete(target)
            throw e
        }
        return target
    }

    /** Whether the document [copy] is still there (the user may have deleted it). */
    private fun stillThere(copy: Uri): Boolean = try {
        resolver.query(copy, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() } == true
    } catch (_: Exception) {
        false
    }

    /**
     * Deletes the document the "Save as" screen made if nothing was written to it: used when the PDF
     * to save has gone, for example because the system restarted Usugami while the screen was open.
     */
    fun discardIfEmpty(target: Uri) {
        if (sizeOf(target) == 0L) delete(target)
    }

    /** Hands [files] (PDFs, or one ZIP file with [type] application/zip) to an app the user picks. */
    fun shareIntent(files: List<File>, type: String = PDF_MIME): Intent = shareIntent(files.map(::contentUri), files[0].name, type)

    /** Hands the files at [uris], of [type], to an app the user picks; [label] names the first. */
    fun shareIntent(uris: List<Uri>, label: String, type: String): Intent {
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = type
        intent.clipData = ClipData.newRawUri(label, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Not to Usugami itself, which takes PDFs shared to it: it doesn't read its own files for another app.
        return Intent.createChooser(intent, null)
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
    }

    fun viewIntent(file: File): Intent = viewIntent(contentUri(file), PDF_MIME)

    /** Opens the file at [uri], of [type], in an app the user picks. */
    fun viewIntent(uri: Uri, type: String): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, type)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun contentUri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /** The source is opened first, so a PDF that can't be read doesn't truncate the target. */
    private fun copy(file: File, target: Uri) {
        file.inputStream().use { source -> openForWriting(target).use { source.copyTo(it, 64 * 1024) } }
    }

    /**
     * "wt" truncates, so overwriting a bigger file can't leave its tail behind ("w" doesn't truncate
     * with some providers). A few providers reject "wt"; they get "w".
     */
    private fun openForWriting(target: Uri): OutputStream {
        val out = try {
            resolver.openOutputStream(target, "wt")
        } catch (_: FileNotFoundException) {
            resolver.openOutputStream(target, "w")
        } catch (_: IllegalArgumentException) {
            resolver.openOutputStream(target, "w")
        }
        return out ?: throw IOException("Could not open $target")
    }

    /** The finished PDFs live in the cache, which the system may clear while Usugami is in the background. */
    private fun requireExists(files: List<File>) {
        files.firstOrNull { !it.isFile }?.let { throw FileNotFoundException(it.name) }
    }

    /** The document's size, or null when the provider doesn't say. */
    private fun sizeOf(target: Uri): Long? = try {
        resolver.query(target, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun delete(target: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, target)
        } catch (_: Exception) {
            // Best effort: the provider may not allow deleting.
        }
    }

    companion object {
        const val PDF_MIME = "application/pdf"

        /** The temporary files saves are using now, in every PdfSaver. */
        private val tempFiles = HashSet<String>()
    }
}

/** A PDF saved into the Downloads folder: its entry there, and the name it got. */
class DownloadsCopy(val uri: Uri, val name: String)
