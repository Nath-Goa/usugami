package io.github.nath_goa.pdfer

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import io.github.nath_goa.pdfer.ui.MainViewModel
import io.github.nath_goa.pdfer.ui.PdferApp
import io.github.nath_goa.pdfer.ui.PdferTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // On recreation the shared images were already taken in (and are in the saved state).
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            PdferTheme {
                PdferApp(viewModel)
            }
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
            // for example) is ignored rather than crashing PDFer.
            return
        }
        // Only content:// URIs, and none from PDFer itself: a file:// URI or one from our own
        // FileProvider would make PDFer read its own private files on another app's behalf.
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
