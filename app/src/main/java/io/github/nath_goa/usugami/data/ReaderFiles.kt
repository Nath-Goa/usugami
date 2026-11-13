package io.github.nath_goa.usugami.data

import android.content.Context
import io.github.nath_goa.usugami.core.FileNames
import java.io.File
import java.io.IOException

/**
 * A copy of [input] among the results, named [fileName]: what the reader's "Save as PDF" saves (the PDF
 * made of a Word, Excel, PowerPoint or EPUB file opened in the reader). [check] is called between
 * chunks and throws to stop; a copy cut short is not left behind.
 */
internal fun PdfToolsEngine.saveCopy(input: PdfInput, fileName: String, check: () -> Unit = {}): PdfFile = newOutput {
    val target = outputFile(FileNames.sanitize(fileName, "Document"))
    ReaderFiles.copy(input.file, target, check)
    PdfFile(target, input.pageCount, 0)
}

/**
 * The reader's file under its own name where other apps can be handed it (Share, Open with another
 * app): cache/output/ is what the FileProvider shares, and it shares a file under the name it has there.
 */
internal object ReaderFiles {
    private fun folder(context: Context) = File(context.cacheDir, "output/reader")

    /**
     * A copy of [input] under its own name in a folder of its own, cache/output/reader/<its number>/.
     * The name ends in ".pdf", which is what other apps are told it is ("Report.pdf"; a PDF opened
     * as "notes.txt" is handed over as "notes.txt.pdf"). The copy made before for the same PDF is
     * used again; the copies of other PDFs go first, and the access other apps were given to them
     * with them. It is written under another name and renamed once whole, so a copy cut short is
     * never handed over. [check] is called between chunks and throws to stop. Blocking.
     */
    fun shareCopy(context: Context, input: PdfInput, check: () -> Unit = {}): File {
        val root = folder(context)
        val own = File(root, input.id.toString())
        root.deleteAllBut(listOf(own), context)
        val target = File(own, "${FileNames.sanitize(input.name, "Document")}.pdf")
        if (target.isFile) return target
        own.mkdirs()
        val partial = File(own, "copy.tmp")
        try {
            copy(input.file, partial, check)
            if (!partial.renameTo(target)) throw IOException("Couldn't keep the copy")
        } finally {
            partial.delete()
        }
        return target
    }

    /** Deletes the copies made before, and takes back the access other apps were given to them. */
    fun clear(context: Context) {
        folder(context).deleteAllBut(emptyList(), context)
    }

    /** Copies [source] to [target], 64 KB at a time, [check] between; a copy cut short is deleted. */
    fun copy(source: File, target: File, check: () -> Unit) {
        try {
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        check()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
    }
}
