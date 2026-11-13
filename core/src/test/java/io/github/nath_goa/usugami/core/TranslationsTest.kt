package io.github.nath_goa.usugami.core

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Checks the app's translations against its English strings, and the store texts of both apps
 * (Usugami and Usugami Lite). Lint checks the strings of the languages it knows of, but not a
 * strings file it never looks at, nor the store texts; this does both, on the desktop JVM, from
 * the files themselves: `app/src/<set>/res/values*` and the two `fastlane/metadata/android` trees.
 *
 * Each rule is a test of its own that fails with every offender, as `language/key` (with the
 * source set after it when it isn't `main`).
 */
class TranslationsTest {
    private val languages = setOf("fr", "es", "hi", "de", "pt", "in")

    /** The plural forms each language's files give, as Android's plural rules ask for them. */
    private val pluralForms = mapOf(
        "fr" to setOf("one", "many", "other"),
        "es" to setOf("one", "many", "other"),
        "pt" to setOf("one", "many", "other"),
        "hi" to setOf("one", "other"),
        "de" to setOf("one", "other"),
        "in" to setOf("other"),
    )

    private val storeLocales = setOf("en-US", "fr-FR", "es-ES", "hi-IN", "de-DE", "pt-BR", "id")
    private val placeholder = Regex("""%(\d+\$)?[sd]|%%""")
    private val unescapedQuote = Regex("""(?<!\\)['"]""")
    private val ignoresMissingTranslation = Regex("""tools:ignore="[^"]*\bMissingTranslation\b""")

    /** The repository: Gradle runs the tests in the core project's folder. */
    private val repo: File by lazy {
        val root = File("..").canonicalFile
        check(File(root, "app/src/main/res/values/strings.xml").isFile) {
            "app/src/main/res/values/strings.xml not found from ${File(".").canonicalPath}: run this test with Gradle (:core:test)"
        }
        root
    }

    private sealed interface Entry
    private data class Text(val text: String) : Entry
    private data class Plural(val items: Map<String, String>) : Entry

    /** One strings.xml: its entries by name, and the names marked translatable="false". */
    private class Strings(val entries: Map<String, Entry>, val untranslatable: Set<String>)

    /** A source set's English strings and its translations by language. */
    private class SourceSet(val name: String, val english: Strings, val translations: Map<String, Strings>)

    private fun read(file: File): Strings {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val entries = LinkedHashMap<String, Entry>()
        val untranslatable = mutableSetOf<String>()
        val children = document.documentElement.childNodes
        for (i in 0 until children.length) {
            val element = children.item(i) as? Element ?: continue
            val name = element.getAttribute("name")
            if (element.getAttribute("translatable") == "false") untranslatable += name
            entries[name] = when (element.tagName) {
                "string" -> Text(element.textContent)
                "plurals" -> {
                    val items = LinkedHashMap<String, String>()
                    val nodes = element.getElementsByTagName("item")
                    for (j in 0 until nodes.length) {
                        val item = nodes.item(j) as Element
                        items[item.getAttribute("quantity")] = item.textContent
                    }
                    Plural(items)
                }
                else -> error("${file.path}: unexpected <${element.tagName}> $name")
            }
        }
        return Strings(entries, untranslatable)
    }

    private val resFolders: List<File> by lazy {
        File(repo, "app/src").listFiles().orEmpty().mapNotNull { File(it, "res").takeIf(File::isDirectory) }
            .flatMap { res -> res.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("values") } }
    }

    private val sourceSets: List<SourceSet> by lazy {
        File(repo, "app/src").listFiles().orEmpty().sortedBy { it.name }
            .filter { File(it, "res/values/strings.xml").isFile }
            .map { set ->
                val res = File(set, "res")
                val translations = res.listFiles().orEmpty()
                    .filter { it.name.startsWith("values-") && File(it, "strings.xml").isFile }
                    .associate { it.name.removePrefix("values-") to read(File(it, "strings.xml")) }
                SourceSet(set.name, read(File(res, "values/strings.xml")), translations)
            }
    }

    private fun where(set: SourceSet, language: String, key: String) =
        if (set.name == "main") "$language/$key" else "$language/$key (${set.name})"

    private fun placeholders(text: String) = placeholder.findAll(text).map { it.value }.sorted().toList()

    private fun newlines(text: String) = Regex("""\\n""").findAll(text).count()

    private fun assertNone(what: String, offenders: List<String>) =
        assertTrue("$what (${offenders.size}):\n" + offenders.joinToString("\n"), offenders.isEmpty())

    /** Calls [check] with each translated entry beside the English entry of the same name. */
    private fun eachPair(check: (where: String, language: String, english: Entry, translated: Entry) -> Unit) {
        for (set in sourceSets) for ((language, strings) in set.translations) {
            for ((key, entry) in strings.entries) {
                val english = set.english.entries[key] ?: continue
                check(where(set, language, key), language, english, entry)
            }
        }
    }

    @Test fun everyLanguageHasItsFoldersAndNoStringsFileIsLeftUnchecked() {
        val offenders = mutableListOf<String>()
        val main = sourceSets.single { it.name == "main" }
        for (set in sourceSets) {
            val found = set.translations.keys
            (languages - found).forEach { offenders += "${set.name}: no values-$it/strings.xml" }
            (found - languages).forEach { offenders += "${set.name}: values-$it/strings.xml is not one of the app's languages" }
            if (set !== main && found != main.translations.keys) {
                offenders += "${set.name}: languages ${found.sorted()} differ from main's ${main.translations.keys.sorted()}"
            }
        }
        for (folder in resFolders) {
            val files = folder.listFiles().orEmpty().filter { it.name.endsWith(".xml") }
            files.filter { it.name.startsWith("strings_") }.forEach {
                offenders += "${it.relativeTo(repo).invariantSeparatorsPath}: fold it into strings.xml, which lint checks"
            }
            files.filter { ignoresMissingTranslation.containsMatchIn(it.readText()) }.forEach {
                offenders += "${it.relativeTo(repo).invariantSeparatorsPath}: tools:ignore=\"MissingTranslation\" hides strings left untranslated"
            }
        }
        assertNone("Languages and strings files", offenders)
    }

    @Test fun everyTranslationHasExactlyTheEnglishKeys() {
        val offenders = mutableListOf<String>()
        for (set in sourceSets) for ((language, strings) in set.translations) {
            for ((key, english) in set.english.entries) {
                if (key in set.english.untranslatable) continue
                val translated = strings.entries[key]
                when {
                    translated == null -> offenders += "${where(set, language, key)}: missing"
                    translated::class != english::class ->
                        offenders += "${where(set, language, key)}: should be a <${if (english is Plural) "plurals" else "string"}>"
                }
            }
            for (key in strings.entries.keys - set.english.entries.keys) {
                offenders += "${where(set, language, key)}: not in English"
            }
        }
        assertNone("Missing and extra keys", offenders)
    }

    @Test fun placeholdersMatchTheEnglish() {
        val offenders = mutableListOf<String>()
        eachPair { where, _, english, translated ->
            if (english is Text && translated is Text && placeholders(english.text) != placeholders(translated.text)) {
                offenders += "$where: ${placeholders(translated.text)}, English has ${placeholders(english.text)}"
            }
        }
        assertNone("Placeholders that differ from the English", offenders)
    }

    @Test fun pluralsHaveTheirLanguagesFormsWithTheEnglishPlaceholders() {
        val offenders = mutableListOf<String>()
        eachPair { where, language, english, translated ->
            if (english !is Plural || translated !is Plural) return@eachPair
            val forms = pluralForms[language] ?: return@eachPair
            if (translated.items.keys != forms) offenders += "$where: forms ${translated.items.keys}, should be $forms"
            for ((quantity, text) in translated.items) {
                val model = english.items[quantity] ?: english.items.getValue("other")
                if (placeholders(text) != placeholders(model)) {
                    offenders += "$where [$quantity]: ${placeholders(text)}, English has ${placeholders(model)}"
                }
            }
        }
        assertNone("Plural forms and their placeholders", offenders)
    }

    @Test fun quotesAreEscapedLineBreaksKeptAndNoStringEmpty() {
        val offenders = mutableListOf<String>()
        fun check(where: String, text: String, english: String) {
            if (unescapedQuote.containsMatchIn(text)) offenders += "$where: an apostrophe or quote without a backslash"
            if (newlines(text) != newlines(english)) offenders += "$where: ${newlines(text)} \\n, English has ${newlines(english)}"
            if (text.isBlank()) offenders += "$where: empty"
        }
        eachPair { where, _, english, translated ->
            when {
                english is Text && translated is Text -> check(where, translated.text, english.text)
                english is Plural && translated is Plural -> for ((quantity, text) in translated.items) {
                    check("$where [$quantity]", text, english.items[quantity] ?: english.items.getValue("other"))
                }
            }
        }
        assertNone("Quotes, line breaks and empty strings", offenders)
    }

    @Test fun nothingUntranslatableIsTranslated() {
        val offenders = mutableListOf<String>()
        for (set in sourceSets) for ((language, strings) in set.translations) {
            for (key in strings.entries.keys intersect set.english.untranslatable) {
                offenders += "${where(set, language, key)}: translatable=\"false\" in English"
            }
        }
        assertNone("Untranslatable strings in translations", offenders)
    }

    /** The text of a store file as the stores count it: in characters, without the final newline. */
    private fun storeText(file: File) = file.readText().trimEnd('\n', '\r')

    private fun length(text: String) = text.codePointCount(0, text.length)

    @Test fun storeTextsAreInEveryLanguageWithinTheStoresLimits() {
        val offenders = mutableListOf<String>()
        for (tree in listOf("fastlane/metadata/android", "src/lite/fastlane/metadata/android")) {
            val root = File(repo, tree)
            check(root.isDirectory) { "${root.path} not found" }
            val locales = root.listFiles().orEmpty().filter { it.isDirectory }.associateBy { it.name }
            (storeLocales - locales.keys).forEach { offenders += "$tree/$it: missing" }
            (locales.keys - storeLocales).forEach { offenders += "$tree/$it: not one of the app's languages" }
            val english = locales["en-US"] ?: continue
            val title = storeText(File(english, "title.txt"))
            val latest = File(english, "changelogs").listFiles().orEmpty().mapNotNull { it.name.removeSuffix(".txt").toIntOrNull() }.maxOrNull()
            for ((name, locale) in locales) {
                val at = "$tree/$name"
                val titleFile = File(locale, "title.txt")
                if (!titleFile.isFile) offenders += "$at/title.txt: missing"
                else if (storeText(titleFile) != title) offenders += "$at/title.txt: differs from en-US's \"$title\""
                for ((file, limit) in listOf("short_description.txt" to 80, "full_description.txt" to 4000)) {
                    val f = File(locale, file)
                    if (!f.isFile) offenders += "$at/$file: missing"
                    else if (length(storeText(f)) > limit) offenders += "$at/$file: ${length(storeText(f))} characters, at most $limit"
                }
                if (latest != null && !File(locale, "changelogs/$latest.txt").isFile) offenders += "$at/changelogs/$latest.txt: missing"
                File(locale, "changelogs").listFiles().orEmpty().filter { it.name.endsWith(".txt") }.forEach {
                    val n = length(storeText(it))
                    if (n > 500) offenders += "$at/changelogs/${it.name}: $n characters, at most 500"
                }
            }
        }
        assertNone("Store texts", offenders)
    }
}
