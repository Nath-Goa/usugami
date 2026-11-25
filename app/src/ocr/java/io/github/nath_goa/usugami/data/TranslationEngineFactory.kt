package io.github.nath_goa.usugami.data

import android.content.Context

/** Usugami OCR has no translating: it has no Google libraries, and translating needs Google ML Kit. */
fun translationEngine(@Suppress("unused") context: Context): TranslationEngine? = null
