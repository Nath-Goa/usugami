package io.github.nath_goa.usugami.data

import android.content.Context

/**
 * Standard edition: translating with Google ML Kit, on the phone, after each language's pack has
 * been downloaded once (see [MlKitTranslationEngine]). Cheap: nothing of ML Kit is touched until a
 * method of the engine needs it.
 */
fun translationEngine(context: Context): TranslationEngine? = MlKitTranslationEngine(context.applicationContext)
