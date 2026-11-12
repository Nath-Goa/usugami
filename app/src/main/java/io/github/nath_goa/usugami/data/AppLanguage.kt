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

/**
 * The languages Usugami's screens come in, in the order the pickers list them. Each also picks the
 * default text-recognition language.
 *
 * [tag] is a BCP 47 language tag, which may carry a region: "pt-BR", whose texts (in values-pt) every
 * Portuguese-speaking phone gets, and "id" for Indonesian, which Android's resources and Locale write
 * "in". So a tag is never compared with a Locale's language as it is: see [shownIn].
 */
enum class AppLanguage(val tag: String, val nativeName: String, val ocr: OcrLanguage) {
    ENGLISH("en", "English", OcrLanguage.ENGLISH),
    FRENCH("fr", "Français", OcrLanguage.FRENCH),
    SPANISH("es", "Español", OcrLanguage.SPANISH),
    HINDI("hi", "हिन्दी", OcrLanguage.HINDI),
    GERMAN("de", "Deutsch", OcrLanguage.GERMAN),
    PORTUGUESE("pt-BR", "Português (Brasil)", OcrLanguage.PORTUGUESE),
    INDONESIAN("id", "Bahasa Indonesia", OcrLanguage.INDONESIAN),
    ;

    /** The language code of [tag] as this Android's Locale writes it ("in" for Indonesian on most versions). */
    private val languageCode: String get() = Locale.forLanguageTag(tag).language

    companion object {
        /**
         * The language a screen built from [context] is showing: one of ours, or English. Any region
         * counts (a phone in Portuguese from Portugal or German from Austria gets ours), and the codes
         * are compared as Locale writes them, so Indonesian is found whether this Android says "in" or "id".
         */
        fun shownIn(context: Context): AppLanguage {
            val language = context.resources.configuration.locales[0].language
            return entries.firstOrNull { it.languageCode == language } ?: ENGLISH
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
    private const val KEY_HANDED_OVER = "handed_over"

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

    /**
     * From Android 13, Android keeps the language: one the user picked on an older version, which only
     * Usugami stored, is handed to Android once, the first time Usugami starts on 13 or newer, unless
     * Android already has one for Usugami. The activity is then recreated in it.
     */
    fun handOver(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_HANDED_OVER, false)) return
        // Once: a language taken away later in the phone's settings stays away.
        prefs.edit(commit = true) { putBoolean(KEY_HANDED_OVER, true) }
        val tag = prefs.getString(KEY_TAG, null) ?: return
        val locales = context.getSystemService(LocaleManager::class.java)
        if (locales.applicationLocales.isEmpty) locales.applicationLocales = LocaleList.forLanguageTags(tag)
    }

    /**
     * For tests: as if the user had already picked a language, so Usugami opens on Home. In
     * [language], or in the phone's when it is null.
     */
    @VisibleForTesting
    internal fun skipChoice(context: Context, language: AppLanguage? = null) {
        prefs(context).edit(commit = true) {
            putBoolean(KEY_CHOSEN, true)
            if (language != null) putString(KEY_TAG, language.tag)
        }
        if (language != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
        }
    }

    /**
     * For tests: forgets the choice, so Usugami asks again and shows the phone's language. Returns
     * whether Android had a language for Usugami, which it takes away from Usugami's screens and
     * resources shortly after.
     */
    @VisibleForTesting
    internal fun reset(context: Context): Boolean {
        prefs(context).edit(commit = true) { clear() }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val locales = context.getSystemService(LocaleManager::class.java)
        if (locales.applicationLocales.isEmpty) return false
        locales.applicationLocales = LocaleList.getEmptyLocaleList()
        return true
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
