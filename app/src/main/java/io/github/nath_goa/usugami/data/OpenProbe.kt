package io.github.nath_goa.usugami.data

import android.content.Context
import android.net.Uri
import io.github.nath_goa.usugami.core.DocumentPicks
import io.github.nath_goa.usugami.core.SharedKind
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** How much of a file [probeKind] reads at most. */
private const val PROBE_BYTES = 256 * 1024

/** How many of a ZIP file's entries [probeKind] looks at at most. */
private const val PROBE_ENTRIES = 64

/** How much of a ZIP file's "mimetype" entry [probeKind] reads at most. */
private const val PROBE_MIMETYPE_BYTES = 64

/**
 * What the file at [uri] is by its content, for a file whose type and name say nothing: its first
 * entries read as a ZIP file (see [DocumentPicks.kindOfEntries]). Null when that doesn't tell: the
 * file isn't a ZIP file, can't be read, or doesn't say within its first 256 KB and 64 entries.
 * Nothing is copied. Blocking.
 */
internal fun probeKind(context: Context, uri: Uri): SharedKind? = try {
    context.contentResolver.openInputStream(uri)?.use { input ->
        ZipInputStream(Limited(input, PROBE_BYTES.toLong())).use(::kindOfZip)
    }
} catch (_: Exception) {
    null
}

/** What the ZIP file [zip] is by its first entries, as soon as they tell (see [DocumentPicks.kindOfEntries]). */
private fun kindOfZip(zip: ZipInputStream): SharedKind? {
    val names = ArrayList<String>()
    var mimetype: String? = null
    repeat(PROBE_ENTRIES) {
        val entry = zip.nextEntry ?: return null
        names += entry.name
        if (entry.name == "mimetype" && mimetype == null) mimetype = readText(zip, PROBE_MIMETYPE_BYTES)
        DocumentPicks.kindOfEntries(names, mimetype)?.let { return it }
    }
    return null
}

/** At most [max] bytes of the entry [zip] is at, as text, trimmed. */
private fun readText(zip: InputStream, max: Int): String {
    val bytes = ByteArray(max)
    var count = 0
    // A stream may give fewer bytes than asked for at a time.
    while (count < max) {
        val read = zip.read(bytes, count, max - count)
        if (read < 0) break
        count += read
    }
    return String(bytes, 0, count, Charsets.UTF_8).trim()
}

/** [input], ending after [limit] bytes: a file is looked into, never read through. */
private class Limited(input: InputStream, private var limit: Long) : FilterInputStream(input) {
    override fun read(): Int {
        if (limit <= 0) return -1
        return super.read().also { if (it >= 0) limit-- }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (limit <= 0) return -1
        return super.read(b, off, minOf(len.toLong(), limit).toInt()).also { if (it > 0) limit -= it }
    }

    override fun skip(n: Long): Long {
        if (limit <= 0) return 0
        return super.skip(minOf(n, limit)).also { if (it > 0) limit -= it }
    }

    override fun available(): Int = minOf(super.available().toLong(), limit).toInt()

    override fun markSupported(): Boolean = false
}
