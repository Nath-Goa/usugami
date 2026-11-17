package io.github.nath_goa.usugami.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Shared motion values so every screen moves the same way. The animations are short and subtle,
 * and Compose already scales them with the system "Animator duration scale" setting.
 */
object Motion {
    /** Small changes: colors, fades of a single control, check marks. */
    const val SHORT = 150

    /** Showing or hiding a section, resizing a card. */
    const val MEDIUM = 250

    /** The longest a screen change takes to settle; tests wait twice this. */
    const val SCREEN = 600

    /** Material "emphasized decelerate": things arriving on screen. */
    val EnterEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Material "emphasized accelerate": things leaving the screen. */
    val ExitEasing: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun <T> short(): FiniteAnimationSpec<T> = tween(SHORT, easing = FastOutSlowInEasing)
    fun <T> medium(): FiniteAnimationSpec<T> = tween(MEDIUM, easing = FastOutSlowInEasing)

    // Springs, all without a bounce but the one after a flick. A spring that moves pixels (a size, an
    // offset) is given IntSize.VisibilityThreshold or IntOffset.VisibilityThreshold: without it the
    // animation runs on to a hundredth of a pixel, and a section leaving stays three times as long.

    /** Scale on touch-down, the seal landing, a slider thumb growing (0.14 s). */
    fun <T> press(visibilityThreshold: T? = null): SpringSpec<T> = spring(1f, 2000f, visibilityThreshold)

    /** The segmented thumb, a section's size, a chip's width, turning an arrow or a page, letting go of a press (0.24 s). */
    fun <T> ui(visibilityThreshold: T? = null): SpringSpec<T> = spring(1f, 700f, visibilityThreshold)

    /** Screen changes, the action bar arriving, the result's headline (0.36 s). */
    fun <T> move(visibilityThreshold: T? = null): SpringSpec<T> = spring(1f, 300f, visibilityThreshold)

    /** Only after a gesture with speed, where an animation after a gesture exists (0.32 s). */
    fun <T> flick(visibilityThreshold: T? = null): SpringSpec<T> = spring(0.8f, 380f, visibilityThreshold)

    /** The result's stack, nowhere else (0.44 s). */
    fun <T> settle(visibilityThreshold: T? = null): SpringSpec<T> = spring(1f, 200f, visibilityThreshold)

    /** A colour or an alpha appearing. */
    fun <T> appear(): FiniteAnimationSpec<T> = tween(120)

    /** A colour or an alpha going. */
    fun <T> vanish(): FiniteAnimationSpec<T> = tween(90)

    /** How a section appears: fading in while opening downwards. */
    fun sectionEnter(): EnterTransition =
        fadeIn(tween(120)) + expandVertically(ui(IntSize.VisibilityThreshold), expandFrom = Alignment.Top)

    /** How a section disappears: fading out while closing upwards. */
    fun sectionExit(): ExitTransition =
        fadeOut(ui()) + shrinkVertically(ui(IntSize.VisibilityThreshold), shrinkTowards = Alignment.Top)

    /**
     * A screen change: going deeper ([forward]), the new screen slides in from the end edge over the
     * whole width while the old one stays and dims 12 %; going back, the reverse on the same path.
     * Only the slide is a spring: the screen underneath always runs a [SCREEN] tween, so it settles
     * after the slide has ended, and the one left behind is kept until then. [depth] is the z-order:
     * a deeper screen is always above a shallower one, whichever is entering. [rtl] mirrors the slide.
     */
    fun screenTransition(forward: Boolean, depth: Int = 0, rtl: Boolean = false): ContentTransform {
        val slide = move<IntOffset>(IntOffset.VisibilityThreshold)
        val edge = if (rtl) -1 else 1
        val transform = if (forward) {
            slideInHorizontally(slide) { width -> edge * width } togetherWith
                fadeOut(tween(SCREEN, easing = EnterEasing), targetAlpha = 0.88f)
        } else {
            fadeIn(tween(SCREEN, easing = EnterEasing), initialAlpha = 0.88f) togetherWith
                slideOutHorizontally(slide) { width -> edge * width }
        }
        transform.targetContentZIndex = depth.toFloat()
        return transform
    }
}

private class Holder<T>(var value: T)

/**
 * Returns [value] while [frozen] is false, and keeps returning the last such value once it turns
 * true. Screens that are animating away use this so they keep showing what they showed, instead
 * of flashing an empty state when the data behind them is cleared.
 */
@Composable
fun <T> freezeWhen(frozen: Boolean, value: T): T {
    val holder = remember { Holder(value) }
    if (!frozen) holder.value = value
    return holder.value
}
