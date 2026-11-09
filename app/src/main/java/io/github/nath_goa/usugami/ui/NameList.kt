package io.github.nath_goa.usugami.ui

import android.content.res.Resources
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.FileNames

/**
 * [names] as a message lists them: all of them, or when there are many the first few and how many
 * more, "a.pdf, b.pdf, c.pdf and 247 more" (see [FileNames.listed]), so that a message about
 * hundreds of files stays short. The message counts them all itself.
 */
internal fun Resources.listOfNames(names: List<String>): String {
    val (listed, more) = FileNames.listed(names)
    val list = listed.joinToString(", ")
    return if (more == 0) list else getQuantityString(R.plurals.names_and_more, more, list, more)
}
