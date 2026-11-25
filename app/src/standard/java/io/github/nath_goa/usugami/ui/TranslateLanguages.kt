package io.github.nath_goa.usugami.ui

import android.icu.text.ListFormatter
import io.github.nath_goa.usugami.data.AppLanguage
import java.text.Collator
import java.util.Locale

// Languages as Translate PDF shows them: tags as the translation engine writes them ("fr", "zh"),
// named in the app's language.

/** English: built into the engine, so it has no pack to download or delete (see TranslationEngine.ensureReady). */
internal const val ENGLISH = "en"

/** The language packs a translation from [source] to [target] needs: those of its languages other than English. */
internal fun packsNeeded(source: String, target: String): List<String> = listOf(source, target).distinct().filter { it != ENGLISH }

/**
 * [tag]'s language named in [locale]'s language as a sentence writes it: "French", "Französisch",
 * but "français", "francés"; the tag itself when Android has no name for it.
 */
internal fun languageWord(tag: String, locale: Locale): String {
    val name = Locale.forLanguageTag(tag).getDisplayLanguage(locale)
    return if (name.isBlank() || name.equals(tag, ignoreCase = true)) tag else name
}

/** [tag]'s language named in [locale]'s language with a capital first letter, as a menu, a chip or a list shows it ("Français"). */
internal fun languageName(tag: String, locale: Locale): String = languageWord(tag, locale).replaceFirstChar { it.titlecase(locale) }

/** [tags] in the order of their names in [locale]'s language, as its speakers sort them. */
internal fun byName(tags: Collection<String>, locale: Locale): List<String> {
    val collator = Collator.getInstance(locale)
    return tags.map { it to languageName(it, locale) }.sortedWith { a, b -> collator.compare(a.second, b.second) }.map { it.first }
}

/** The names of [tags], in that order, as a sentence lists them in [locale]'s language ("French and English", "francés e inglés"). */
internal fun namesListed(tags: List<String>, locale: Locale): String =
    ListFormatter.getInstance(locale).format(tags.map { languageWord(it, locale) })

/**
 * The language a PDF in [source] (null while it isn't known) is translated to unless the user
 * chooses: the app's ([app]), or English when the PDF is in that, or Spanish when it is in both.
 */
internal fun defaultTarget(app: String, source: String?): String = when {
    app != source -> app
    source != ENGLISH -> ENGLISH
    else -> "es"
}

/** The app's language among the engine's: its language alone, "pt" for Brazil's Portuguese. */
internal fun AppLanguage.translationTag(): String = tag.substringBefore('-')

/** [tag] as the engine writes it: Java's old codes for Hebrew, Indonesian and Yiddish become today's. */
internal fun engineTag(tag: String): String = when (tag) {
    "iw" -> "he"
    "in" -> "id"
    "ji" -> "yi"
    else -> tag
}
