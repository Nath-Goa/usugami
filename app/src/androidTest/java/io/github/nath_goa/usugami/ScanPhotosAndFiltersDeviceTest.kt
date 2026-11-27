package io.github.nath_goa.usugami

import android.app.Application
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.core.PageLook
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.ImageFormat
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.OriginalImage
import io.github.nath_goa.usugami.data.Thumbnails
import io.github.nath_goa.usugami.ui.ScanLooksActions
import io.github.nath_goa.usugami.ui.ScanLooksScreen
import io.github.nath_goa.usugami.ui.ScanPhotosActions
import io.github.nath_goa.usugami.ui.ScanPhotosScreen
import io.github.nath_goa.usugami.ui.UsugamiTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * A scan's Photos and Filters screens on their own, over real photos and pages, their state held here
 * as the view model would hold it: what each button, page, look, slider and switch hands on, and what
 * is offered when. How a scan goes from one to the next is the view model's, tested with it.
 */
class ScanPhotosAndFiltersDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val dir = File(app.cacheDir, "test-scan-screens")
    private var scenario: ActivityScenario<MainActivity>? = null
    private var activity: MainActivity? = null

    /** What the screens asked for, in order. */
    private val calls = ArrayList<String>()

    private var photos by mutableStateOf(listOf<ImageItem>())
    private var adding by mutableIntStateOf(0)
    private var pages by mutableStateOf(listOf<ImageItem>())
    private var selected by mutableLongStateOf(0L)
    private var same by mutableStateOf(false)

    @Before fun setUp() {
        // The screens are in the phone's language, as [app]'s strings are.
        if (AppLanguages.reset(app)) Thread.sleep(1_000)
        dir.mkdirs()
    }

    @After fun tearDown() {
        scenario?.close()
        dir.deleteRecursively()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun text(res: Int, vararg args: Any): String = app.getString(res, *args)

    /** [count] photos of a page on a table, as the camera app hands them over, and the pages cropped from them. */
    private fun makeScan(count: Int) {
        val made = (1..count).map { i ->
            val photo = PagePhotos.jpeg(PagePhotos.photo(), File(dir, "photo-$i.jpg"))
            val page = PagePhotos.jpeg(PagePhotos.page(), File(dir, "page-$i.jpg"))
            val taken = ImageItem(100L + i, photo.path, "Scan_$i.jpg", ImageFormat.JPEG, PagePhotos.PHOTO_WIDTH, PagePhotos.PHOTO_HEIGHT, 1)
            val original = OriginalImage(photo.path, ImageFormat.JPEG, PagePhotos.PHOTO_WIDTH, PagePhotos.PHOTO_HEIGHT, 1, PagePhotos.QUAD)
            taken to taken.copy(path = page.path, width = PagePhotos.PAGE_WIDTH, height = PagePhotos.PAGE_HEIGHT, original = original, look = PageLook.ENHANCED)
        }
        onMain {
            photos = made.map { it.first }
            pages = made.map { it.second }
            selected = pages.first().id
        }
    }

    private fun showPhotos() = show { snackbar ->
        ScanPhotosScreen(
            photos,
            adding,
            Thumbnails(),
            active = true,
            snackbar = snackbar,
            actions = ScanPhotosActions(
                takeAnother = { calls += "takeAnother" },
                done = { calls += "done" },
                remove = { id ->
                    calls += "remove $id"
                    photos = photos.filter { it.id != id }
                },
                discard = { calls += "discard" },
            ),
        )
    }

    private fun showFilters() = show { snackbar ->
        val thumbnails = remember { Thumbnails() }
        ScanLooksScreen(
            pages,
            selected,
            same,
            emptySet(),
            thumbnails,
            active = true,
            snackbar = snackbar,
            actions = ScanLooksActions(
                select = { id ->
                    calls += "select $id"
                    selected = id
                },
                look = { look ->
                    calls += "look $look"
                    pages = pages.map { if (same || it.id == selected) it.copy(look = look) else it }
                },
                adjust = { adjustments ->
                    calls += "adjust ${adjustments.brightness} ${adjustments.contrast}"
                    pages = pages.map { if (same || it.id == selected) it.copy(adjustments = adjustments) else it }
                },
                sameForAll = { on ->
                    calls += "same $on"
                    same = on
                },
                save = { calls += "save" },
                back = { calls += "back" },
            ),
        )
    }

    private fun show(screen: @Composable (SnackbarHostState) -> Unit) {
        AppLanguages.skipChoice(app)
        val launched = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java))
        scenario = launched
        launched.onActivity { activity = it }
        onMain {
            activity!!.setContent {
                val snackbar = remember { SnackbarHostState() }
                UsugamiTheme { screen(snackbar) }
            }
        }
        compose.waitForIdle()
    }

    private fun button(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun back() {
        onMain { activity!!.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test fun aPhotoIsTakenOutAndTheScanGoesOnWithTakeAnotherOrDone() {
        makeScan(2)
        showPhotos()
        compose.onNode(hasContentDescription(text(R.string.remove_page, 2)) and hasClickAction()).performClick()
        compose.waitForIdle()
        button(text(R.string.crop_take_another)).performClick()
        button(text(R.string.crop_done)).performClick()
        assertEquals(listOf("remove 102", "takeAnother", "done"), calls)
        // The photo left is page 1, and nothing offers to take out a page 2.
        assertEquals(0, compose.onAllNodes(hasContentDescription(text(R.string.remove_page, 2))).fetchSemanticsNodes().size)
    }

    @Test fun doneWaitsForAPhotoAndForThoseStillBeingAdded() {
        showPhotos()
        // No photo yet: the first is taken with Take photo, and there is nothing to crop.
        button(text(R.string.crop_done)).assertIsNotEnabled()
        button(text(R.string.add_from_camera)).assertIsEnabled()
        makeScan(1)
        onMain { adding = 1 }
        compose.waitForIdle()
        button(text(R.string.crop_done)).assertIsNotEnabled()
        onMain { adding = 0 }
        compose.waitForIdle()
        button(text(R.string.crop_done)).assertIsEnabled().performClick()
        assertEquals(listOf("done"), calls)
    }

    @Test fun leavingWithPhotosAsksFirstAndWithoutLeavesAtOnce() {
        makeScan(2)
        showPhotos()
        back()
        button(text(R.string.keep)).performClick()
        compose.waitForIdle()
        assertTrue("Discarded on Keep: $calls", calls.isEmpty())
        compose.onNode(hasContentDescription(text(R.string.back)) and hasClickAction()).performClick()
        button(text(R.string.discard)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("discard"), calls)

        calls.clear()
        onMain { photos = emptyList() }
        compose.waitForIdle()
        back()
        assertEquals(0, compose.onAllNodes(hasText(text(R.string.scan_discard_title))).fetchSemanticsNodes().size)
        assertEquals(listOf("discard"), calls)
    }

    @Test fun aPageALookTheSwitchSaveAndBackAreHandedOn() {
        makeScan(3)
        showFilters()
        val page2 = compose.onNode(hasContentDescription(text(R.string.pdf_page_number, 2)) and isSelectable())
        page2.performClick()
        compose.waitForIdle()
        page2.assertIsSelected()
        compose.onNode(hasText(text(R.string.look_grayscale)) and isSelectable()).performScrollTo().performClick()
        compose.onNode(hasText(text(R.string.scan_same_look)) and isToggleable()).performClick()
        button(text(R.string.save)).performClick()
        compose.onNode(hasContentDescription(text(R.string.back)) and hasClickAction()).performClick()
        back()
        assertEquals(listOf("select 102", "look GRAYSCALE", "same true", "save", "back", "back"), calls)
    }

    @Test fun aSliderIsHandedOnWhenLetGoNotAtEachStep() {
        makeScan(2)
        showFilters()
        val brightness = compose.onNode(hasContentDescription(text(R.string.filter_brightness)))
        brightness.performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(width / 40f, 0f)) }
        }
        compose.waitForIdle()
        assertTrue("Handed on while held: $calls", calls.isEmpty())
        brightness.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(1, calls.size)
        val (b, c) = calls.single().removePrefix("adjust ").split(" ").map { it.toInt() }
        assertTrue("Brightness $b", b > 0)
        assertEquals(0, c)
        // A screen reader's change is let go at once.
        calls.clear()
        compose.onNode(hasContentDescription(text(R.string.filter_contrast))).performSemanticsAction(SemanticsActions.SetProgress) { it(-40f) }
        compose.waitForIdle()
        assertEquals(listOf("adjust $b -40"), calls)
    }

    @Test fun onePageHasNoRowOfPagesAndNoSwitch() {
        makeScan(1)
        showFilters()
        compose.onNode(hasText(text(R.string.look_original)) and isSelectable()).assertExists()
        assertEquals(0, compose.onAllNodes(hasText(text(R.string.scan_same_look))).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasContentDescription(text(R.string.pdf_page_number, 1)) and isSelectable()).fetchSemanticsNodes().size)
    }
}
