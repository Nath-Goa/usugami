package io.github.nath_goa.usugami.data

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import java.util.Locale

/** The languages Usugami's screens come in. Each also picks the default text-recognition language. */
enum class AppLanguage(val tag: String, val nativeName: String, val ocr: OcrLanguage) {
    ENGLISH("en", "English", OcrLanguage.ENGLISH),
    FRENCH("fr", "Français", OcrLanguage.FRENCH),
    SPANISH("es", "Español", OcrLanguage.SPANISH),
    HINDI("hi", "हिन्दी", OcrLanguage.HINDI),
    ;

    companion object {
        /** The language a screen built from [context] is showing: one of ours, or English. */
        fun shownIn(context: Context): AppLanguage {
            val language = context.resources.configuration.locales[0].language
            return entries.firstOrNull { it.tag == language } ?: ENGLISH
        }
    }
}

/**
 * Remembers whether the user has picked a language, and applies the one they picked. Android 13
 * and newer keep a language per app themselves (it can also be changed in the phone's settings);
 * on older versions Usugami stores it and applies it to its own screens and messages.
 */
object AppLanguages {
    private const val PREFS = "language"
    private const val KEY_CHOSEN = "chosen"
    private const val KEY_TAG = "tag"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether the user has picked a language yet; until then Usugami asks on launch. */
    fun isChosen(context: Context): Boolean = prefs(context).getBoolean(KEY_CHOSEN, false)

    /** Switches Usugami to [language]. The activity is recreated in it if it isn't showing it already. */
    fun choose(activity: Activity, language: AppLanguage) {
        // Written before the activity is recreated, which reads it.
        prefs(activity).edit(commit = true) {
            putBoolean(KEY_CHOSEN, true)
            putString(KEY_TAG, language.tag)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
        } else if (AppLanguage.shownIn(activity) != language) {
            activity.recreate()
        }
    }

    /** For tests: as if the user had already picked a language, so Usugami opens on Home. */
    @VisibleForTesting
    internal fun skipChoice(context: Context) {
        prefs(context).edit(commit = true) { putBoolean(KEY_CHOSEN, true) }
    }

    /** For tests: forgets the choice, so Usugami asks again and shows the phone's language. */
    @VisibleForTesting
    internal fun reset(context: Context) {
        prefs(context).edit(commit = true) { clear() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        }
    }

    /**
     * Below Android 13: [base] showing the language the user picked. Android 13 and newer apply it
     * to every context of the app themselves, so [base] is returned as it is.
     */
    fun localized(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = prefs(base).getString(KEY_TAG, null) ?: return base
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(config)
    }
}
