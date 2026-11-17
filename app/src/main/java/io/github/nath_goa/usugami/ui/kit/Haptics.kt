package io.github.nath_goa.usugami.ui.kit

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

// Nothing else in the app vibrates: no haptic on ordinary taps, switches or navigation.

/** The few moments Usugami is felt as well as seen. Get one with [rememberHaptics]. */
@Stable
class Haptics internal constructor(private val feedback: HapticFeedback) {
    /** The seal lands on the result. */
    fun confirm() {
        if (Build.VERSION.SDK_INT >= 30) feedback.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    /** A wrong password; "Keep at least one page." */
    fun reject() {
        if (Build.VERSION.SDK_INT >= 30) feedback.performHapticFeedback(HapticFeedbackType.Reject)
    }

    /** A page tile lifted (the editor, Reorganize, Merge's page list). */
    fun lift() = feedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)

    /** Each swap while dragging a tile. */
    fun swap() = feedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)

    /** The tile dropped. */
    fun drop() = feedback.performHapticFeedback(HapticFeedbackType.GestureEnd)

    /** Brightness or Contrast snaps to 0. */
    fun snap() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)

    /** A word selected in the reader. */
    fun select() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)
}

/** The [Haptics] of this place in the app, through Compose's [LocalHapticFeedback]. */
@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics(feedback) }
}
