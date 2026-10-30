package io.github.nathgoa.pdfer.data

import android.content.Context
import io.github.nathgoa.pdfer.core.Compression
import io.github.nathgoa.pdfer.core.LayoutSettings

/** Remembers the last used PDF options between launches. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var layout: LayoutSettings
        get() {
            val default = LayoutSettings()
            return LayoutSettings(
                pageSize = enumOr(prefs.getString(KEY_SIZE, null), default.pageSize),
                orientation = enumOr(prefs.getString(KEY_ORIENTATION, null), default.orientation),
                margin = enumOr(prefs.getString(KEY_MARGIN, null), default.margin),
            )
        }
        set(value) {
            prefs.edit()
                .putString(KEY_SIZE, value.pageSize.name)
                .putString(KEY_ORIENTATION, value.orientation.name)
                .putString(KEY_MARGIN, value.margin.name)
                .apply()
        }

    var merge: Boolean
        get() = prefs.getBoolean(KEY_MERGE, true)
        set(value) = prefs.edit().putBoolean(KEY_MERGE, value).apply()

    var compression: Compression
        get() = enumOr(prefs.getString(KEY_COMPRESSION, null), Compression.RECOMMENDED)
        set(value) = prefs.edit().putString(KEY_COMPRESSION, value.name).apply()

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val KEY_SIZE = "page_size"
        const val KEY_ORIENTATION = "orientation"
        const val KEY_MARGIN = "margin"
        const val KEY_MERGE = "merge"
        const val KEY_COMPRESSION = "compression"
    }
}
