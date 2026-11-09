package io.github.nath_goa.usugami.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.nath_goa.usugami.core.Compression
import io.github.nath_goa.usugami.core.LayoutSettings
import io.github.nath_goa.usugami.core.MarkStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the PDFs Usugami makes carry its small mark ([on], off unless the user turns it on), and
 * the mark's size and opacity ([style]), kept while it is off.
 */
data class MarkSetting(val on: Boolean = false, val style: MarkStyle = MarkStyle()) {
    /** The mark the PDFs are to carry: null while it is off. */
    val wanted: MarkStyle? get() = if (on) style else null
}

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

    /**
     * The mark's setting, one for the whole app: every screen that shows PDFs made sees a change at
     * once, whichever changed it.
     */
    val mark: StateFlow<MarkSetting> get() = markFlow(prefs).asStateFlow()

    fun setMark(setting: MarkSetting) {
        prefs.edit {
            putBoolean(KEY_MARK_ON, setting.on)
            putInt(KEY_MARK_SIZE, setting.style.size)
            putInt(KEY_MARK_OPACITY, setting.style.opacity)
        }
        markFlow(prefs).value = setting
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val KEY_SIZE = "page_size"
        const val KEY_ORIENTATION = "orientation"
        const val KEY_MARGIN = "margin"
        const val KEY_MERGE = "merge"
        const val KEY_COMPRESSION = "compression"
        const val KEY_MARK_ON = "mark_on"
        const val KEY_MARK_SIZE = "mark_size"
        const val KEY_MARK_OPACITY = "mark_opacity"

        /** The mark's setting as the app sees it, read once from the preferences. */
        @Volatile private var mark: MutableStateFlow<MarkSetting>? = null

        @Synchronized
        fun markFlow(prefs: SharedPreferences): MutableStateFlow<MarkSetting> = mark ?: MutableStateFlow(
            MarkSetting(
                on = prefs.getBoolean(KEY_MARK_ON, false),
                style = MarkStyle.of(
                    prefs.getInt(KEY_MARK_SIZE, MarkStyle.DEFAULT_SIZE),
                    prefs.getInt(KEY_MARK_OPACITY, MarkStyle.DEFAULT_OPACITY),
                ),
            ),
        ).also { mark = it }
    }
}
