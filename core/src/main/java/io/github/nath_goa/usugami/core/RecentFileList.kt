package io.github.nath_goa.usugami.core

/**
 * A file Usugami saved, as its Recent files list keeps it: where the saved copy is ([uri], a
 * content:// address), its [name] and [type], its [size] in bytes (-1 when unknown) and when it was
 * saved ([savedAt], in milliseconds since 1970). [grant] is the address whose access Usugami keeps to
 * reach the file again: the document itself for one made with the "Save as" screen, the folder picked
 * for a copy saved into one, or null for a copy in Downloads, which Usugami can always read.
 */
data class RecentFile(
    val uri: String,
    val name: String,
    val type: String,
    val size: Long,
    val savedAt: Long,
    val grant: String? = null,
)

/**
 * The bookkeeping of the Recent files list: the newest file first, each file once, at most [MAX]
 * files, and the list as text to keep between launches. Text that isn't such a list, or a line of it
 * that isn't a file, is left out rather than trusted.
 */
object RecentFileList {
    /** The most files the list keeps: plenty to find a file again, and far below the accesses Android lets an app keep. */
    const val MAX = 50

    /** Starts the text, so that anything else (an old or damaged file) reads as an empty list. */
    private const val HEADER = "usugami-recent-files 1"

    /**
     * [list] with the files [saved] on top, in the order they were saved, so the last is first. A file
     * listed already (saved again) moves up rather than showing twice; the oldest go past [MAX].
     */
    fun add(list: List<RecentFile>, saved: List<RecentFile>): List<RecentFile> =
        (saved.asReversed() + list).distinctBy { it.uri }.take(MAX)

    /** The accesses the files [before] needed that those [after] no longer do: Usugami gives them back. */
    fun unusedGrants(before: List<RecentFile>, after: List<RecentFile>): Set<String> =
        before.mapNotNullTo(HashSet()) { it.grant } - after.mapNotNullTo(HashSet()) { it.grant }

    /** [list] as text: a line per file, its fields separated by tabs. */
    fun encode(list: List<RecentFile>): String = buildString {
        append(HEADER)
        for (file in list) {
            append('\n')
            listOf(file.uri, file.name, file.type, file.size.toString(), file.savedAt.toString(), file.grant.orEmpty())
                .joinTo(this, "\t") { escape(it) }
        }
    }

    /** The list [encode] wrote into [text]; what can't be read is left out. */
    fun decode(text: String): List<RecentFile> {
        val lines = text.split('\n')
        if (lines.first() != HEADER) return emptyList()
        return lines.drop(1).mapNotNull(::file).distinctBy { it.uri }.take(MAX)
    }

    /** The file on [line], or null when it isn't one. */
    private fun file(line: String): RecentFile? {
        val fields = line.split('\t').map { unescape(it) ?: return null }
        if (fields.size != 6) return null
        val (uri, name, type) = fields
        // Only a content:// address is ever listed: Usugami must not be led to open its own files.
        if (!uri.startsWith("content://") || name.isBlank() || type.isBlank()) return null
        val size = fields[3].toLongOrNull() ?: return null
        val savedAt = fields[4].toLongOrNull() ?: return null
        return RecentFile(uri, name, type, size, savedAt, fields[5].ifEmpty { null })
    }

    /** A name can hold any character: the tab and line break that separate fields and files are written as \t and \n. */
    private fun escape(field: String): String = buildString {
        for (c in field) {
            when (c) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
    }

    /** The field [escape] wrote, or null when it is damaged (a backslash with nothing it stands for). */
    private fun unescape(field: String): String? {
        if ('\\' !in field) return field
        val out = StringBuilder(field.length)
        var i = 0
        while (i < field.length) {
            val c = field[i++]
            if (c != '\\') {
                out.append(c)
                continue
            }
            if (i == field.length) return null
            out.append(
                when (field[i++]) {
                    '\\' -> '\\'
                    't' -> '\t'
                    'n' -> '\n'
                    'r' -> '\r'
                    else -> return null
                },
            )
        }
        return out.toString()
    }
}
