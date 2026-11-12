package io.github.nath_goa.usugami.data

import android.content.Context
import android.net.Uri
import android.os.Parcelable
import android.provider.OpenableColumns
import io.github.nath_goa.usugami.core.SharedKind
import kotlinx.parcelize.Parcelize
import java.io.File
import java.io.IOException

/** A PDF, Word, PowerPoint, Excel or EPUB file shared to Usugami, copied in and waiting for the tool it opens in. */
@Parcelize
data class SharedFile(val id: Long, val path: String, val name: String, val kind: SharedKind) : Parcelable {
    val file: File get() = File(path)
}

/**
 * Copies of the PDFs and documents shared to Usugami, from the moment they arrive until the tool they
 * open in takes them (see PdfToolsViewModel.addShared): another app's share lets Usugami read a file
 * for a while only, and the user may be asked first. They live in the cache, like the other copies.
 */
class ShareInbox(private val context: Context) {
    private val dir = File(context.cacheDir, "shared")

    /** The type the app that has the file at [uri] gives it, and the file's name; either may be null. */
    fun describe(uri: Uri): Pair<String?, String?> {
        val type = try {
            context.contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        }
        val name = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0).substringAfterLast('/').takeIf { it.isNotBlank() } else null
            }
        } catch (_: Exception) {
            null
        }
        return type to name
    }

    /** A name for a shared or picked file whose app gives it none, to say which it is. */
    fun fallbackName(uri: Uri, kind: SharedKind): String = when (kind) {
        SharedKind.PDF -> "Document.pdf"
        SharedKind.WORD -> "Document.docx"
        SharedKind.POWERPOINT -> "Presentation.pptx"
        SharedKind.EPUB -> "Book.epub"
        SharedKind.EXCEL -> "Spreadsheet.xlsx"
        else -> uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "File"
    }

    /**
     * Copies the file at [uri] in, as file [id] named [name]. [check] is called between chunks and
     * throws to stop: a large file from a cloud provider can take a while.
     */
    fun copy(uri: Uri, id: Long, name: String, kind: SharedKind, check: () -> Unit = {}): SharedFile {
        dir.mkdirs()
        val file = File(dir, "$id")
        try {
            val source = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open the file")
            source.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        check()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            return SharedFile(id, file.path, name, kind)
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
    }

    fun delete(shared: SharedFile) {
        shared.file.delete()
    }

    /** Deletes the copies but [keep]'s: leftovers from an earlier session, or half-copied files. */
    fun deleteAllExcept(keep: Collection<SharedFile>) {
        val names = keep.mapTo(HashSet()) { it.file.name }
        dir.listFiles()?.forEach { if (it.name !in names) it.delete() }
    }
}

/**
 * Moves [from], a copy made already, to [to] in the cache, replacing what is there. A rename, so a
 * large file isn't copied again; a copy when that can't be done.
 */
internal fun moveCopy(from: File, to: File) {
    to.parentFile?.mkdirs()
    to.delete()
    if (from.renameTo(to)) return
    try {
        from.copyTo(to, overwrite = true)
    } catch (t: Throwable) {
        to.delete()
        throw t
    }
    from.delete()
}
