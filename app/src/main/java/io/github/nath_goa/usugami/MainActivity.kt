package io.github.nath_goa.usugami

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.print.PrintManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import io.github.nath_goa.usugami.core.ReadFrom
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.AppShortcuts
import io.github.nath_goa.usugami.data.Appearances
import io.github.nath_goa.usugami.data.PdfPrinting
import io.github.nath_goa.usugami.ui.MainViewModel
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import io.github.nath_goa.usugami.ui.UsugamiApp
import io.github.nath_goa.usugami.ui.UsugamiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    // The same instance UsugamiApp gets from viewModel().
    private val pdfToolsViewModel: PdfToolsViewModel by viewModels()

    // The context Android made for this activity, before the language and appearance picked were put on a copy of it.
    private var systemContext: Context? = null

    // Below Android 13 the language the user picked is applied here, and below Android 12 the
    // appearance (light or dark); newer versions do it themselves.
    override fun attachBaseContext(newBase: Context) {
        systemContext = newBase
        super.attachBaseContext(Appearances.applied(AppLanguages.localized(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The status bar's icons light on a dark screen and dark on a light one, as Usugami is, not as
        // the window's resources say: below Android 12 Usugami may be dark on a light phone or light on
        // a dark one, and on Android 8 the window's resources follow the phone.
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark })
        super.onCreate(savedInstanceState)
        // A language or appearance picked before the phone was updated to an Android version that
        // keeps it for Usugami.
        AppLanguages.handOver(this)
        Appearances.handOver(this)
        // The launcher shortcuts, labelled in the language picked: published at every creation, so
        // after the language changes (which recreates the activity) they follow. One at a time, in the
        // order the activities were created: the labels of the newest win.
        lifecycleScope.launch(shortcutWork) { AppShortcuts.publish(this@MainActivity) }
        // On recreation the shared files were already taken in (and are in the saved state), and so
        // were a file opened and a shortcut used.
        if (savedInstanceState == null) {
            handleShare(intent)
            handleView(intent)
            handleShortcut(intent)
        }
        setContent {
            UsugamiTheme(Appearances.forTheme(this)) {
                UsugamiApp(
                    viewModel,
                    onChooseLanguage = { AppLanguages.choose(this, it) },
                    onChooseAppearance = { Appearances.choose(this, it) },
                    // Back from a file another app opened returns to that app: Usugami steps behind it.
                    onLeaveToBack = { moveTaskToBack(true) },
                    onPrint = ::print,
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
        handleView(intent)
        handleShortcut(intent)
    }

    /**
     * Android's print service as this activity must ask for it. Android prints only for an activity, which
     * it tells by the context the service was asked through: the copy of the context this activity gets
     * below Android 13 (see attachBaseContext) doesn't know the activity, and printing would be refused.
     */
    private fun printManager(): PrintManager? = systemContext?.getSystemService(PrintManager::class.java)

    /** Opens Android's print screen for the PDF [open] gives, named [name]; false when printing couldn't be started (see PdfPrinting.print). */
    internal fun print(name: String, open: () -> InputStream): Boolean =
        PdfPrinting.print(printManager(), name, getString(R.string.print_failed), open)

    /**
     * Files shared from a gallery, a file manager or another app: images are added to the current
     * list, PDFs open Choose a tool, and Word, PowerPoint, Excel and EPUB files their converter (see
     * [MainViewModel.share]).
     */
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
        viewModel.share(accepted, sharedAs = intent.type, refused = uris.size - accepted.size)
    }

    /** A file another app asks Usugami to open ("Open with"): it opens in the reader (see MainViewModel.open). */
    private fun handleView(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        // As for shares: only content:// addresses, and none of Usugami's own files.
        val uri = intent.data?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT && it.authority?.substringAfterLast('@') != "$packageName.files" }
        viewModel.open(uri, type = intent.type, from = ReadFrom.OUTSIDE)
    }

    /** A launcher shortcut used (see AppShortcuts): the screen opens its tool (see MainViewModel.shortcut). */
    private fun handleShortcut(intent: Intent) {
        // Reopened from the recent apps, the intent is the one Usugami was started with: already acted on.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        AppShortcuts.targetOf(intent)?.let { viewModel.openShortcut(it.id) }
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
        // Some apps only put the files in ClipData.
        val clip = intent.clipData ?: return null
        (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }

    private companion object {
        /** Shortcuts are published one at a time (see onCreate). */
        val shortcutWork = Dispatchers.IO.limitedParallelism(1)
    }
}
