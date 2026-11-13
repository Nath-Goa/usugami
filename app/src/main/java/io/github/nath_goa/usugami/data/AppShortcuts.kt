package io.github.nath_goa.usugami.data

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import io.github.nath_goa.usugami.MainActivity
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.ShortcutTarget

/**
 * The shortcuts a long-press on Usugami's icon shows: Scan document, Images to PDF, Merge PDF and
 * Compress PDF. They are published by the app (dynamic shortcuts), not declared in the manifest:
 * Android starts a shortcut declared there by clearing the task, which would destroy Usugami's screen
 * before it could ask about unsaved work, while a published one reaches the running MainActivity in
 * onNewIntent, as a share does. Published, they also open the edition that published them, and their
 * labels are in the language picked in Usugami rather than the phone's.
 */
object AppShortcuts {
    /** The action of a shortcut's intent. The same in every edition: the intent names its own app's activity. */
    const val ACTION = "io.github.nath_goa.usugami.action.SHORTCUT"
    /** The extra holding the shortcut's [ShortcutTarget.id]. */
    const val EXTRA_TARGET = "target"

    /** The intent [target]'s shortcut starts: MainActivity of this very app (so Usugami Lite's opens Usugami Lite). */
    fun intent(context: Context, target: ShortcutTarget): Intent =
        Intent(context, MainActivity::class.java).setAction(ACTION).putExtra(EXTRA_TARGET, target.id)

    /** The target of a shortcut's [intent], or null when it isn't one. Never throws on a malformed intent. */
    fun targetOf(intent: Intent): ShortcutTarget? {
        if (intent.action != ACTION) return null
        // Any app can send this intent, with anything in its extras: one that can't be read
        // (BadParcelableException, for example) is no shortcut.
        val id = try {
            intent.getStringExtra(EXTRA_TARGET)
        } catch (_: RuntimeException) {
            null
        }
        return ShortcutTarget.of(id)
    }

    /**
     * Publishes the four shortcuts with labels from [context]'s resources, unless those published
     * already match. [context] is the activity, whose resources are in the language picked in Usugami
     * on every Android version (see MainActivity.attachBaseContext). Never throws. Call off the main
     * thread: it asks Android's shortcut service.
     */
    fun publish(context: Context) {
        try {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return
            val wanted = ShortcutTarget.entries.map { target ->
                val (icon, short, long) = when (target) {
                    ShortcutTarget.SCAN -> Triple(R.drawable.ic_shortcut_scan, R.string.shortcut_scan, R.string.scan_title)
                    ShortcutTarget.IMAGES -> Triple(R.drawable.ic_shortcut_images, R.string.shortcut_images, R.string.tool_images)
                    ShortcutTarget.MERGE -> Triple(R.drawable.ic_shortcut_merge, R.string.shortcut_merge, R.string.tool_merge)
                    ShortcutTarget.COMPRESS -> Triple(R.drawable.ic_shortcut_compress, R.string.shortcut_compress, R.string.tool_compress)
                }
                ShortcutInfo.Builder(context, target.id)
                    .setShortLabel(context.getString(short))
                    .setLongLabel(context.getString(long))
                    .setIcon(Icon.createWithResource(context, icon))
                    .setIntent(intent(context, target))
                    .setRank(target.ordinal)
                    .build()
            }.take(manager.maxShortcutCountPerActivity)
            // Android limits how often an app in the background may publish: nothing is asked when
            // the shortcuts are already as they should be.
            fun ShortcutInfo.shown() = Triple(id, shortLabel?.toString(), longLabel?.toString())
            if (manager.dynamicShortcuts.sortedBy { it.rank }.map { it.shown() } == wanted.map { it.shown() }) return
            manager.dynamicShortcuts = wanted
        } catch (_: RuntimeException) {
            // A locked user profile, a launcher without shortcuts and the rate limit throw or refuse:
            // the shortcuts are tried again the next time Usugami opens.
        }
    }
}
