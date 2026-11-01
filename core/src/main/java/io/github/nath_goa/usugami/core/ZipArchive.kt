package io.github.nath_goa.usugami.core

import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** How far [ZipArchive.write] has got: [files] of [totalFiles] files in, [bytes] of [totalBytes] read. */
data class ZipProgress(val files: Int, val totalFiles: Int, val bytes: Long, val totalBytes: Long) {
    val fraction: Float get() = if (totalBytes <= 0) 0f else (bytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

/**
 * One ZIP file holding several finished files, to share or save them together. Each file goes in as
 * it is ("stored"): PDFs are compressed already, so compressing them again would take time and save
 * almost nothing.
 */
object ZipArchive {
    const val MIME = "application/zip"
    private const val BUFFER = 64 * 1024

    /**
     * The names the files get inside the archive: their own, with folder separators replaced, made
     * unique ignoring case, as Windows and phones' shared storage need ("a.pdf", then "A (2).pdf").
     */
    fun entryNames(names: List<String>): List<String> {
        val taken = HashSet<String>()
        return names.map { raw ->
            val name = raw.replace('/', '_').replace('\\', '_').trim().ifEmpty { "file" }
            var candidate = name
            if (candidate.lowercase() in taken) {
                val base = FileNames.baseName(name)
                val extension = name.substring(base.length)
                var n = 2
                while ("$base ($n)$extension".lowercase() in taken) n++
                candidate = "$base ($n)$extension"
            }
            taken += candidate.lowercase()
            candidate
        }
    }

    /**
     * Writes [files] into a ZIP archive on [out], in their order, and closes [out]. [check] is called
     * before each chunk and throws to stop (when cancelled). [onProgress] follows the bytes read:
     * each file is read twice, once for its checksum (a stored entry needs it before its data) and
     * once to copy it.
     */
    fun write(files: List<File>, out: OutputStream, check: () -> Unit = {}, onProgress: (ZipProgress) -> Unit = {}) {
        val totalBytes = files.sumOf { it.length() } * 2
        var bytes = 0L
        val buffer = ByteArray(BUFFER)
        val names = entryNames(files.map { it.name })

        /** Reads all of [file] in chunks, giving each to [use]; returns its size. */
        fun read(file: File, done: Int, use: (Int) -> Unit): Long {
            var size = 0L
            file.inputStream().use { input ->
                while (true) {
                    check()
                    val n = input.read(buffer)
                    if (n < 0) break
                    use(n)
                    size += n
                    bytes += n
                    onProgress(ZipProgress(done, files.size, bytes, totalBytes))
                }
            }
            return size
        }

        val zip = ZipOutputStream(out)
        var finished = false
        try {
            files.forEachIndexed { index, file ->
                val crc = CRC32()
                val size = read(file, index) { n -> crc.update(buffer, 0, n) }
                zip.putNextEntry(
                    ZipEntry(names[index]).apply {
                        method = ZipEntry.STORED
                        this.size = size
                        compressedSize = size
                        this.crc = crc.value
                        time = file.lastModified()
                    },
                )
                read(file, index) { n -> zip.write(buffer, 0, n) }
                zip.closeEntry()
                onProgress(ZipProgress(index + 1, files.size, bytes, totalBytes))
            }
            // Writes the archive's table of contents and closes [out].
            zip.close()
            finished = true
        } finally {
            if (!finished) {
                // The archive is incomplete and will be thrown away. Closing the ZIP stream fails on
                // the unfinished entry before it closes [out], so that is closed here.
                runCatching { zip.close() }
                runCatching { out.close() }
            }
        }
    }
}
