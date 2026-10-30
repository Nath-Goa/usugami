package io.github.nathgoa.pdfer.data

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/** Saving finished PDFs to shared storage and handing them to other apps. */
class PdfSaver(private val context: Context) {
    private val resolver = context.contentResolver

    /** Copies [file] into the public Downloads folder and returns the name it got there. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToDownloads(file: File): String {
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
    fun saveTo(file: File, target: Uri) = copy(file, target)

    /** Creates a copy of each file inside a folder the user picked with the system folder picker. */
    fun saveToFolder(files: List<File>, tree: Uri) {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        for (file in files) {
            val target = DocumentsContract.createDocument(resolver, parent, PDF_MIME, file.name)
                ?: throw IOException("Could not create ${file.name}")
            copy(file, target)
        }
    }

    fun shareIntent(files: List<File>): Intent {
        val uris = files.map(::contentUri)
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = PDF_MIME
        intent.clipData = ClipData.newRawUri(files[0].name, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(intent, null)
    }

    fun viewIntent(file: File): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(contentUri(file), PDF_MIME)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun contentUri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    private fun copy(file: File, target: Uri) {
        val out = resolver.openOutputStream(target, "w") ?: throw IOException("Could not open $target")
        out.use { stream -> file.inputStream().use { it.copyTo(stream, 64 * 1024) } }
    }

    companion object {
        const val PDF_MIME = "application/pdf"
    }
}
