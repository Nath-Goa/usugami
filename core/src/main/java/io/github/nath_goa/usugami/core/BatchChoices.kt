package io.github.nath_goa.usugami.core

/**
 * What a tool that works on several files one after another keeps for each of them, by the file's
 * id: the name its PDF gets, or null while it is the tool's own (such as "Report_compressed"), and a
 * choice, such as a compression level, as its number among the tool's choices.
 *
 * [all] is the choice last made for every file at once. It is what files added later start with,
 * and what a file not listed has. A file whose choice is changed on its own afterwards keeps it,
 * until a choice is made for all of them again.
 */
data class BatchChoices(val files: List<BatchFile> = emptyList(), val all: Int = 0) {

    /** One file's name (null for the tool's own) and choice. */
    data class BatchFile(val id: Long, val name: String?, val choice: Int)

    /** The choice file [id] has. */
    fun choiceOf(id: Long): Int = files.firstOrNull { it.id == id }?.choice ?: all

    /** The name file [id]'s PDF gets, or null for the tool's own. */
    fun nameOf(id: Long): String? = files.firstOrNull { it.id == id }?.name

    /** The choice every file has, or null while they differ. With no files, [all]. */
    val common: Int?
        get() = files.map { it.choice }.distinct().let { if (it.isEmpty()) all else it.singleOrNull() }

    /**
     * The choices for the files [ids], in that order: the files kept have theirs, files new to the
     * list start with the tool's name and [all], and files no longer listed are forgotten.
     */
    fun following(ids: List<Long>): BatchChoices {
        val byId = files.associateBy { it.id }
        val list = ids.distinct().map { id -> byId[id] ?: BatchFile(id, null, all) }
        return if (list == files) this else copy(files = list)
    }

    /** Every file, and those added later, get [choice]. */
    fun withAll(choice: Int): BatchChoices = BatchChoices(files.map { it.copy(choice = choice) }, choice)

    /** File [id] alone gets [choice]; nothing changes for a file not listed. */
    fun withChoice(id: Long, choice: Int): BatchChoices = copy(files = files.map { if (it.id == id) it.copy(choice = choice) else it })

    /** File [id]'s PDF is to be named [name] (as typed; null for the tool's own). */
    fun withName(id: Long, name: String?): BatchChoices = copy(files = files.map { if (it.id == id) it.copy(name = name) else it })

    /**
     * The choices in a few bytes per file, for Android's saved state: the ids, names and choices
     * each in an array of their own, and [all].
     */
    fun pack(): Packed = Packed(
        LongArray(files.size) { files[it].id },
        Array(files.size) { files[it].name },
        IntArray(files.size) { files[it].choice },
        all,
    )

    /** [BatchChoices] as [pack] keeps them. */
    class Packed(val ids: LongArray, val names: Array<String?>, val choices: IntArray, val all: Int)

    companion object {
        /** The choices [pack] kept; arrays that don't match (from another version, say) give none. */
        fun unpack(ids: LongArray?, names: Array<String?>?, choices: IntArray?, all: Int): BatchChoices {
            if (ids == null || names == null || choices == null || names.size != ids.size || choices.size != ids.size) return BatchChoices(all = all)
            return BatchChoices(ids.indices.map { BatchFile(ids[it], names[it], choices[it]) }, all)
        }
    }
}
