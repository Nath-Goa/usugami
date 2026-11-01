package io.github.nath_goa.usugami.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.nath_goa.usugami.core.FileNames
import io.github.nath_goa.usugami.core.ZipArchive
import io.github.nath_goa.usugami.core.ZipProgress
import java.io.File

/**
 * A run's PDFs in one ZIP file, for the result screen's "Share or save as ZIP". The ZIP made for
 * sharing lives in [dir], in the cache beside the PDFs, and is cleared with them. Blocking: call
 * from a background thread.
 */
class ResultZip(context: Context, private val dir: File) {
    private val saver = PdfSaver(context)

    /** Makes the ZIP file for sharing, in place of an earlier one, and returns it. */
    fun make(files: List<File>, name: String, check: () -> Unit, onProgress: (ZipProgress) -> Unit): File {
        dir.deleteRecursively()
        dir.mkdirs()
        val zip = File(dir, fileName(name))
        try {
            zip.outputStream().use { ZipArchive.write(files, it, check, onProgress) }
        } catch (t: Throwable) {
            zip.delete()
            throw t
        }
        return zip
    }

    /** Writes the ZIP file straight into a document the user created with the system "Save as" screen. */
    fun saveTo(files: List<File>, target: Uri, check: () -> Unit, onProgress: (ZipProgress) -> Unit) {
        saver.writeTo(target, files) { ZipArchive.write(files, it, check, onProgress) }
    }

    fun shareIntent(zip: File): Intent = saver.shareIntent(listOf(zip), ZipArchive.MIME)

    /** Deletes the ZIP file made for sharing. */
    fun clear() {
        dir.deleteRecursively()
    }

    companion object {
        /** The ZIP file's name: the run's, such as "Report_split.zip". */
        fun fileName(name: String): String = "${FileNames.sanitize(name, "Usugami")}.zip"
    }
}
