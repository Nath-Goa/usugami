package io.github.nath_goa.usugami.data

import androidx.annotation.StringRes
import io.github.nath_goa.usugami.BuildConfig
import io.github.nath_goa.usugami.R

/**
 * Usugami's three editions, the product flavors in app/build.gradle.kts, all signed with the same key.
 * Usugami and Usugami OCR share the app ID, so either one installs over the other; Usugami Lite has
 * its own (io.github.nath_goa.usugami.lite) and installs next to them.
 */
enum class Edition(@param:StringRes val title: Int, @param:StringRes val description: Int) {
    /** "Usugami": text recognition with Google ML Kit through Google Play services. */
    STANDARD(R.string.app_name, R.string.about_edition_standard),

    /** "Usugami Lite": no text recognition and no Google libraries. */
    LITE(R.string.about_edition_name_lite, R.string.about_edition_lite),

    /** "Usugami OCR": open-source text recognition built in. */
    OCR(R.string.about_edition_name_ocr, R.string.about_edition_ocr),
    ;

    companion object {
        /** The edition of this build. */
        val current: Edition = when (BuildConfig.EDITION) {
            "lite" -> LITE
            "ocr" -> OCR
            else -> STANDARD
        }
    }
}
