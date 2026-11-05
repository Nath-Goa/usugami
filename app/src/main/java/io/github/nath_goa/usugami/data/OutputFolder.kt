package io.github.nath_goa.usugami.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Deletes this folder and all in it, except the files [keep], which stay where they are. The apps a
 * deleted file was shared with or opened in lose their access to its place: a later result made there
 * under the same name is another document, which they weren't given.
 */
internal fun File.deleteAllBut(keep: Collection<File>, context: Context) {
    if (keep.isEmpty()) {
        revokeAccess(context, this)
        deleteRecursively()
        return
    }
    val kept = keep.mapTo(HashSet()) { it.absolutePath }
    listFiles()?.forEach {
        if (it.absolutePath !in kept) {
            revokeAccess(context, it)
            it.deleteRecursively()
        }
    }
}

/** Takes back the access other apps were given to [file] (see PdfSaver) and, for a folder, to all in it. */
private fun revokeAccess(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: Exception) {
        // Not in a folder that is shared, so never given; or the system refused.
    }
}
