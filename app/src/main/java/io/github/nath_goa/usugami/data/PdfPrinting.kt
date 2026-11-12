package io.github.nath_goa.usugami.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.annotation.VisibleForTesting
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

/** Printing a PDF with Android's print system, which needs no permission: the file is handed over as it is. */
object PdfPrinting {
    /** Whether this device has printing at all. */
    fun available(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_PRINTING)

    /**
     * Opens Android's print screen for the PDF [open] gives, named [name] (the job's name and the
     * name "Save as PDF" suggests). [failed] is what that screen says when the PDF can't be read.
     * [manager] is the activity's own (see MainActivity.print). Call on the main thread.
     * False when printing couldn't be started.
     */
    fun print(manager: PrintManager?, name: String, failed: String, open: () -> InputStream): Boolean = try {
        // print() gives null when it couldn't start, and throws when the print service can't be reached
        // or the manager wasn't asked for through an activity.
        manager?.print(name, PdfPrintAdapter(name, failed, open), null) != null
    } catch (_: RuntimeException) {
        false
    }

    /**
     * Copies what [open] gives into [to], [BUFFER_BYTES] at a time, so a very large PDF is never held
     * in memory. False when [cancelled] said to stop, which is asked before anything is read and
     * between pieces. Throws when it can't be read or written.
     */
    @VisibleForTesting
    internal fun copy(open: () -> InputStream, to: OutputStream, cancelled: () -> Boolean): Boolean {
        if (cancelled()) return false
        open().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                if (cancelled()) return false
                val read = input.read(buffer)
                if (read < 0) break
                to.write(buffer, 0, read)
            }
        }
        to.flush()
        return true
    }

    private const val BUFFER_BYTES = 64 * 1024
}

/**
 * Hands Android's print system the PDF [open] gives, unchanged, as Android's own adapter for a PDF
 * file does: the same document whatever the paper (Android scales its pages itself), its pages
 * counted by Android. It holds no screen and keeps nothing between writes: Android drops it without
 * calling onFinish when the activity under the print screen is destroyed.
 */
private class PdfPrintAdapter(
    private val name: String,
    private val failed: String,
    private val open: () -> InputStream,
) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()
        // The PDF is the same whatever the paper.
        callback.onLayoutFinished(info, false)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        // A thread for each write, which ends with it: off the main thread, where Android calls this, and
        // nothing left to shut down. Android asks for one write at a time and closes [destination] itself
        // after any answer.
        thread(name = "print") {
            val done = try {
                FileOutputStream(destination.fileDescriptor).use { out ->
                    PdfPrinting.copy(open, out) { cancellationSignal.isCanceled }
                }
            } catch (_: Exception) {
                // Android closes the file under a write it gives up: that is a cancel, not a failure to tell of.
                if (cancellationSignal.isCanceled) false else null
            }
            // Answered once, outside the copy's own failures.
            try {
                when (done) {
                    true -> callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    false -> callback.onWriteCancelled()
                    null -> callback.onWriteFailed(failed)
                }
            } catch (_: RuntimeException) {
                // The print screen has gone meanwhile: there is no one left to tell.
            }
        }
    }
}
