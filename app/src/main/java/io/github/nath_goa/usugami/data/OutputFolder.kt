package io.github.nath_goa.usugami.data

import java.io.File

/** Deletes this folder and all in it, except the files [keep], which stay where they are. */
internal fun File.deleteAllBut(keep: Collection<File>) {
    if (keep.isEmpty()) {
        deleteRecursively()
        return
    }
    val kept = keep.mapTo(HashSet()) { it.absolutePath }
    listFiles()?.forEach { if (it.absolutePath !in kept) it.deleteRecursively() }
}
