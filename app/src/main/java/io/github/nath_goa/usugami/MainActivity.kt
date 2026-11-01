package io.github.nath_goa.usugami

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.Appearances
import io.github.nath_goa.usugami.ui.MainViewModel
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import io.github.nath_goa.usugami.ui.UsugamiApp
import io.github.nath_goa.usugami.ui.UsugamiTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    // The same instance UsugamiApp gets from viewModel().
    private val pdfToolsViewModel: PdfToolsViewModel by viewModels()

    // Below Android 13 the language the user picked is applied here, and below Android 12 the
    // appearance (light or dark); newer versions do it themselves.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Appearances.applied(AppLanguages.localized(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // On recreation the shared images were already taken in (and are in the saved state).
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            UsugamiTheme(Appearances.forTheme(this)) {
                UsugamiApp(
                    viewModel,
                    onChooseLanguage = { AppLanguages.choose(this, it) },
                    onChooseAppearance = { Appearances.choose(this, it) },
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // The view models are cleared before onDestroy, and also when the system destroys the
        // activity without finishing it (the saved state then still lists the working copies).
        // Tell them here whether the user is leaving for good, so only then are the copies freed.
        if (isFinishing) {
            viewModel.activityFinishing()
            pdfToolsViewModel.activityFinishing()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** Images shared from a gallery or file manager are added to the current list. */
    private fun handleShare(intent: Intent) {
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val uris = try {
            sharedUris(intent) ?: return
        } catch (_: RuntimeException) {
            // A sender can put anything in the extras; one that can't be read (BadParcelableException,
            // for example) is ignored rather than crashing Usugami.
            return
        }
        // Only content:// URIs, and none from Usugami itself: a file:// URI or one from our own
        // FileProvider would make Usugami read its own private files on another app's behalf.
        val accepted = uris.filter { uri ->
            uri.scheme == ContentResolver.SCHEME_CONTENT &&
                // An authority may carry a user ID ("0@authority") that the system strips.
                uri.authority?.substringAfterLast('@') != "$packageName.files"
        }
        viewModel.addImages(accepted, refused = uris.size - accepted.size)
    }

    private fun sharedUris(intent: Intent): List<Uri>? = when (intent.action) {
        // Below API 34 these extras are not type-checked, so a sender can put anything there.
        // Filter by type instead of trusting the generic signature.
        Intent.ACTION_SEND ->
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Parcelable::class.java) as? Uri)
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Parcelable::class.java)
                .orEmpty().filterIsInstance<Uri>()
        else -> null
    }?.ifEmpty {
        // Some apps only put the images in ClipData.
        val clip = intent.clipData ?: return null
        (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }
}
