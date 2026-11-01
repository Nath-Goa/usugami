package io.github.nath_goa.usugami.data

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit

/** Whether Usugami's screens are light or dark: as the phone is set (the default), or always one. */
enum class Appearance { SYSTEM, LIGHT, DARK }

/**
 * Remembers and applies the appearance picked in Settings. Android 12 and newer keep a night mode
 * for each app and apply it before the app's first frame. On older versions Usugami applies it to
 * its own screens: the theme, and the configuration its resources and system bars are styled from.
 */
object Appearances {
    private const val PREFS = "appearance"
    private const val KEY = "appearance"

    /**
     * Whether Usugami applies the choice itself rather than the system: below Android 12. Tests
     * turn it on to try that way on newer phones too.
     */
    @VisibleForTesting
    internal var appliedByUsugami = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The appearance picked; the system's, until one is. */
    fun current(context: Context): Appearance {
        val name = prefs(context).getString(KEY, null)
        return Appearance.entries.firstOrNull { it.name == name } ?: Appearance.SYSTEM
    }

    /**
     * Switches Usugami to [appearance]. The activity is recreated in it if that changes whether it
     * is dark (by the system from Android 12 on).
     */
    fun choose(activity: Activity, appearance: Appearance) {
        // Written before the activity is recreated, which reads it.
        prefs(activity).edit(commit = true) { putString(KEY, appearance.name) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !appliedByUsugami) {
            // The system remembers it for Usugami, also across restarts.
            activity.getSystemService(UiModeManager::class.java).setApplicationNightMode(
                when (appearance) {
                    Appearance.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                    Appearance.LIGHT -> UiModeManager.MODE_NIGHT_NO
                    Appearance.DARK -> UiModeManager.MODE_NIGHT_YES
                },
            )
        } else {
            val phoneDark = isDark(activity.applicationContext.resources.configuration)
            val dark = when (appearance) {
                Appearance.SYSTEM -> phoneDark
                Appearance.LIGHT -> false
                Appearance.DARK -> true
            }
            if (dark != isDark(activity.resources.configuration)) activity.recreate()
        }
    }

    /**
     * Below Android 12: [base] in the night mode picked, so the theme's resources (the window's
     * background) and the system bars' style follow it. Newer versions get it from the system, so
     * [base] is returned as it is.
     */
    fun applied(base: Context): Context {
        if (!appliedByUsugami) return base
        val night = when (current(base)) {
            Appearance.SYSTEM -> return base
            Appearance.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            Appearance.DARK -> Configuration.UI_MODE_NIGHT_YES
        }
        val config = Configuration(base.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        }
        return base.createConfigurationContext(config)
    }

    /**
     * What the Compose theme follows. Below Android 12 the choice itself, as the configuration
     * Compose reads may lose it; newer versions put it in the configuration, which the theme follows
     * as it does the phone's setting.
     */
    fun forTheme(context: Context): Appearance = if (appliedByUsugami) current(context) else Appearance.SYSTEM

    /** For tests: back to following the phone, as on a fresh install. */
    @VisibleForTesting
    internal fun reset(context: Context) {
        prefs(context).edit(commit = true) { clear() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(UiModeManager::class.java).setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
        }
    }

    private fun isDark(config: Configuration) = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}
