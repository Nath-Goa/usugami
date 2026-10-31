package io.github.nath_goa.usugami.data

import android.content.res.AssetManager
import androidx.annotation.StringRes
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.SimpleMarkdown
import io.github.nath_goa.usugami.core.TextBlock
import java.io.IOException

/** The privacy policy, notices and license that the build copies from the repository into assets/legal/. */
enum class LegalDocument(@param:StringRes val title: Int, private val files: List<String>) {
    PRIVACY(R.string.about_privacy_policy, listOf("PRIVACY.md")),
    // The Google notices come from the libraries themselves; the offline file is only in the offline edition.
    NOTICES(R.string.about_licenses, listOf("THIRD_PARTY_NOTICES.md", "google-notices.txt", "google-notices-offline.txt")),

    // Usugami's copyright notice and its additional permission (GPL section 7) come before the GPL itself.
    LICENSE(R.string.about_license, listOf("LICENSE-EXCEPTION.md", "LICENSE")),
    ;

    /**
     * Reads and lays out the document. Returns null when none of its files are in this build. It
     * reads from storage, so call it off the main thread.
     */
    fun load(assets: AssetManager): List<TextBlock>? = loadLegalText(assets, files)
}

/** Reads the given files from assets/legal/ (Markdown when named .md, plain text otherwise), skipping missing ones. */
internal fun loadLegalText(assets: AssetManager, files: List<String>): List<TextBlock>? {
    val parts = files.mapNotNull { name ->
        val text = try {
            assets.open("legal/$name").bufferedReader().use { it.readText() }
        } catch (_: IOException) {
            return@mapNotNull null
        }
        if (name.endsWith(".md")) SimpleMarkdown.parse(text) else SimpleMarkdown.parsePlain(text)
    }
    return if (parts.isEmpty()) null else parts.flatten()
}
