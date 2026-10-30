package io.github.nathgoa.pdfer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import io.github.nathgoa.pdfer.ui.MainViewModel
import io.github.nathgoa.pdfer.ui.PdferApp
import io.github.nathgoa.pdfer.ui.PdferTheme

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
        val uris = when (intent.action) {
            // Below API 34 these extras are not type-checked, so a sender can put anything there.
            // Filter by type instead of trusting the generic signature.
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Parcelable::class.java) as? Uri)
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Parcelable::class.java)
                    .orEmpty().filterIsInstance<Uri>()
            else -> return
        }.ifEmpty {
            // Some apps only put the images in ClipData.
            val clip = intent.clipData ?: return
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }
        viewModel.addImages(uris)
    }
}
