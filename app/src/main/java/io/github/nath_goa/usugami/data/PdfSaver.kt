package io.github.nath_goa.usugami.data

import android.content.ClipData
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
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/** Saving finished PDFs (and ZIP files of them) to shared storage and handing them to other apps. */
class PdfSaver(private val context: Context) {
    private val resolver = context.contentResolver

    /** Copies [file] into the public Downloads folder and returns the name it got there. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToDownloads(file: File): String {
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
        resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return file.name
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
    }

    /**
     * Writes into a document the user created with the system "Save as" screen: [write] gets its
     * stream and makes the content from [files]. A new document is deleted again if that fails or is
     * cancelled.
     */
    fun writeTo(target: Uri, files: List<File>, write: (OutputStream) -> Unit) {
        val created = sizeOf(target) == 0L
        try {
            // Checked first, so a document the user chose to overwrite isn't emptied for nothing.
            requireExists(files)
            openForWriting(target).use(write)
        } catch (e: Exception) {
            if (created) delete(target)
            throw e
        }
    }

    /** Creates a copy of each file inside a folder the user picked with the system folder picker. */
    fun saveToFolder(files: List<File>, tree: Uri) {
        requireExists(files)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        for (file in files) {
            val target = DocumentsContract.createDocument(resolver, parent, PDF_MIME, file.name)
                ?: throw IOException("Could not create ${file.name}")
            try {
                copy(file, target)
            } catch (e: Exception) {
                delete(target)
                throw e
            }
        }
    }

    /**
     * Deletes the document the "Save as" screen made if nothing was written to it: used when the PDF
     * to save has gone, for example because the system restarted Usugami while the screen was open.
     */
    fun discardIfEmpty(target: Uri) {
        if (sizeOf(target) == 0L) delete(target)
    }

    /** Hands [files] (PDFs, or one ZIP file with [type] application/zip) to an app the user picks. */
    fun shareIntent(files: List<File>, type: String = PDF_MIME): Intent {
        val uris = files.map(::contentUri)
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = type
        intent.clipData = ClipData.newRawUri(files[0].name, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(intent, null)
    }

    fun viewIntent(file: File): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(contentUri(file), PDF_MIME)
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
    }
}
