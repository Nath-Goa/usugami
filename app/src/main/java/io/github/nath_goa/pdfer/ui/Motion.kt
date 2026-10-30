package io.github.nath_goa.pdfer.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Shared motion values so every screen moves the same way. The animations are short and subtle,
 * and Compose already scales them with the system "Animator duration scale" setting.
 */
object Motion {
    /** Small changes: colors, fades of a single control, check marks. */
    const val SHORT = 150

    /** Showing or hiding a section, resizing a card. */
    const val MEDIUM = 250

    /** Moving between screens. */
    const val SCREEN = 300

    /** Material "emphasized decelerate": things arriving on screen. */
    val EnterEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Material "emphasized accelerate": things leaving the screen. */
    val ExitEasing: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun <T> short(): FiniteAnimationSpec<T> = tween(SHORT, easing = FastOutSlowInEasing)
    fun <T> medium(): FiniteAnimationSpec<T> = tween(MEDIUM, easing = FastOutSlowInEasing)

    /**
     * Horizontal "shared axis" screen change: the new screen slides in a short distance while
     * fading in, and the old one fades out quickly. [forward] is true when going deeper.
     */
    fun screenTransition(forward: Boolean): ContentTransform {
        val direction = if (forward) 1 else -1
        val enter = fadeIn(tween(SCREEN - 90, delayMillis = 90, easing = EnterEasing)) +
            slideInHorizontally(tween(SCREEN, easing = EnterEasing)) { width -> direction * width / 10 }
        val exit = fadeOut(tween(90, easing = ExitEasing)) +
            slideOutHorizontally(tween(SCREEN, easing = EnterEasing)) { width -> -direction * width / 10 }
        return enter togetherWith exit
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
