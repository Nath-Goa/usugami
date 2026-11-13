package io.github.nath_goa.usugami

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutManager
import android.content.res.Configuration
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.nath_goa.usugami.core.ShortcutTarget
import io.github.nath_goa.usugami.data.AppLanguage
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.AppShortcuts
import io.github.nath_goa.usugami.ui.MainViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Locale

/**
 * The launcher shortcuts: Usugami publishes its four, in their order, labelled in the language picked
 * in Usugami, each opening this very app's MainActivity with its target; and a shortcut used waits in
 * the view model until the screen takes it, the next one too, also one that starts Usugami before a
 * language is chosen.
 */
class AppShortcutsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(100)
        }
    }

    @After fun tearDown() {
        // The activity may have been recreated in the language picked: whichever instance is left goes.
        onMain {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED).flatMap(monitor::getActivitiesInStage)
                .filterIsInstance<MainActivity>().forEach { it.finish() }
        }
        // The language picked outlives the activity: forgotten, as the other tests do.
        if (AppLanguages.reset(app)) Thread.sleep(1_000)
    }

    @Test fun theFourShortcutsArePublishedInUsugamisLanguageAndOpenThisApp() {
        val manager = app.getSystemService(ShortcutManager::class.java)
        assumeTrue("The phone has launcher shortcuts", manager != null)
        // Those an earlier run left would already be there: the wait below would pass without Usugami
        // publishing anything.
        manager.removeAllDynamicShortcuts()
        val french = app.createConfigurationContext(
            Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(AppLanguage.FRENCH.tag)) },
        ).resources
        val longLabels = listOf(R.string.scan_title, R.string.tool_images, R.string.tool_merge, R.string.tool_compress).map(french::getString)
        val shortLabels = listOf(R.string.shortcut_scan, R.string.shortcut_images, R.string.shortcut_merge, R.string.shortcut_compress)
            .map(french::getString)

        // In French, as the language screen leaves it; in the foreground, which also keeps Android's
        // limit on how often an app may publish out of the way.
        AppLanguages.skipChoice(app, AppLanguage.FRENCH)
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        instrumentation.startActivitySync(intent)
        // Waited for, not read once: from Android 13 the language reaches the activity a moment after
        // it starts, and the activity publishes again once recreated in it.
        fun published() = manager.dynamicShortcuts.sortedBy { it.rank }
        waitUntil("the shortcuts in French") { published().map { it.longLabel?.toString() } == longLabels }

        val shortcuts = published()
        assertEquals(listOf("scan", "images", "merge", "compress"), shortcuts.map { it.id })
        assertEquals(shortLabels, shortcuts.map { it.shortLabel?.toString() })
        for ((shortcut, target) in shortcuts.zip(ShortcutTarget.entries)) {
            val opens = checkNotNull(shortcut.intent) { "${shortcut.id} opens nothing" }
            assertEquals(ComponentName(app.packageName, MainActivity::class.java.name), opens.component)
            assertEquals(AppShortcuts.ACTION, opens.action)
            assertEquals(target.id, opens.getStringExtra(AppShortcuts.EXTRA_TARGET))
            assertEquals(target, AppShortcuts.targetOf(opens))
        }
        // An intent that isn't a shortcut's, or names no shortcut, is none.
        assertNull(AppShortcuts.targetOf(Intent(Intent.ACTION_MAIN).putExtra(AppShortcuts.EXTRA_TARGET, "merge")))
        assertNull(AppShortcuts.targetOf(Intent(AppShortcuts.ACTION).putExtra(AppShortcuts.EXTRA_TARGET, "reader")))
        assertNull(AppShortcuts.targetOf(Intent(AppShortcuts.ACTION).putExtra(AppShortcuts.EXTRA_TARGET, 2)))
    }

    @Test fun aShortcutWaitsForTheScreenAndSoDoesTheNext() {
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        assertNull(vm.shortcut.value)
        assertNull(vm.takeShortcut())
        onMain { vm.openShortcut(ShortcutTarget.MERGE.id) }
        assertEquals("merge", vm.shortcut.value)
        assertEquals(ShortcutTarget.MERGE, onMain { vm.takeShortcut() })
        assertNull("Taken once", vm.shortcut.value)
        assertNull(onMain { vm.takeShortcut() })
        // The same shortcut again reaches the screen: the flow it watches still follows the saved state.
        onMain { vm.openShortcut(ShortcutTarget.MERGE.id) }
        assertEquals("merge", vm.shortcut.value)
        onMain { vm.openShortcut(ShortcutTarget.SCAN.id) }
        assertEquals(ShortcutTarget.SCAN, onMain { vm.takeShortcut() })
        assertNull(vm.shortcut.value)
    }

    @Test fun aShortcutThatStartsUsugamiWaitsForTheLanguageScreen() {
        // The first launch: the language screen shows first, and the shortcut waits for the screens
        // behind it.
        if (AppLanguages.reset(app)) Thread.sleep(1_000)
        val started = AppShortcuts.intent(app, ShortcutTarget.MERGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity = instrumentation.startActivitySync(started) as MainActivity
        val vm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        assertEquals("merge", vm.shortcut.value)
    }
}
