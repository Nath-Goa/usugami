package io.github.nath_goa.usugami.ui

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.SignatureLayout
import io.github.nath_goa.usugami.data.Signature
import io.github.nath_goa.usugami.data.SignatureInk
import kotlin.math.hypot

/** The pen, in pad units, as the screen draws it; the PDF's image is drawn with the same width and ends. */
internal val SignaturePen = Stroke(SignatureLayout.PEN_WIDTH, cap = StrokeCap.Round, join = StrokeJoin.Round)

@StringRes
internal fun inkLabel(ink: SignatureInk): Int = when (ink) {
    SignatureInk.BLACK -> R.string.color_black
    SignatureInk.BLUE -> R.string.color_blue
}

/**
 * A full-screen pad to sign on with a finger, starting from [initial] if there is one. "Done" hands
 * the drawing to [onDone]; closing keeps the signature there was.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SignaturePadDialog(initial: Signature?, onDone: (Signature) -> Unit, onDismiss: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf(initial ?: Signature(emptyList(), SignatureInk.BLACK)) }
    // Edge to edge like the screens, so nothing of the screen below shows around it, even beside a
    // camera cutout in landscape.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.sign_draw)) },
                    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel)) }
                    },
                )
            },
            bottomBar = {
                Button(
                    onClick = { onDone(draft) },
                    enabled = !draft.isEmpty,
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) { Text(stringResource(R.string.sign_done)) }
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.sign_pad_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // As wide as the screen; in landscape, as tall as the room left.
                Box(Modifier.weight(1f, fill = false).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    SignaturePad(draft, onStroke = { draft += it }, Modifier.aspectRatio(SignatureLayout.PAD_WIDTH / SignatureLayout.PAD_HEIGHT))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sign_ink), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(8.dp))
                    ColorSwatches(SignatureInk.entries, draft.ink, onSelect = { draft = draft.withInk(it) }, argb = { it.argb }, label = ::inkLabel)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { draft = draft.cleared() }, enabled = !draft.isEmpty) { Text(stringResource(R.string.sign_clear)) }
                }
            }
        }
    }
}

/**
 * White, like paper, with a line to sign on. Strokes are kept in pad units, so a drawing keeps its
 * place when the pad changes size, as it does on rotation.
 */
@Composable
private fun SignaturePad(signature: Signature, onStroke: (FloatArray) -> Unit, modifier: Modifier) {
    // The stroke being drawn, in pad units: x, y, x, y, …
    val stroke = remember { mutableStateListOf<Float>() }
    val latest by rememberUpdatedState(signature)
    val finish by rememberUpdatedState(onStroke)
    val drawn = remember(signature.strokes) { signature.path().asComposePath() }
    val ink by animateColorAsState(Color(signature.ink.argb), Motion.short(), label = "ink")
    val shape = RoundedCornerShape(12.dp)
    val description = stringResource(R.string.sign_pad)
    Canvas(
        modifier
            .clip(shape)
            // A stroke starting at the pad's edge draws, instead of going back.
            .systemGestureExclusion()
            .background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val toPad = SignatureLayout.PAD_WIDTH / size.width
                    var room = Signature.MAX_POINTS - latest.pointCount
                    fun add(position: Offset) {
                        val x = (position.x * toPad).coerceIn(0f, SignatureLayout.PAD_WIDTH)
                        val y = (position.y * toPad).coerceIn(0f, SignatureLayout.PAD_HEIGHT)
                        val count = stroke.size
                        if (room <= 0 || (count >= 2 && hypot(x - stroke[count - 2], y - stroke[count - 1]) < Signature.MIN_STEP)) return
                        stroke.addAll(listOf(x, y))
                        room--
                    }
                    stroke.clear()
                    add(down.position)
                    down.consume()
                    // One finger signs; others are ignored. Points between frames make the curve smoother.
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        change.historical.forEach { add(it.position) }
                        add(change.position)
                        change.consume()
                        if (!change.pressed) break
                    }
                    if (stroke.isNotEmpty()) finish(stroke.toFloatArray())
                    stroke.clear()
                }
            },
    ) {
        val lineY = size.height * 0.72f
        drawLine(Color(0x42000000), Offset(size.width * 0.06f, lineY), Offset(size.width * 0.94f, lineY), strokeWidth = 1.dp.toPx())
        val scale = size.width / SignatureLayout.PAD_WIDTH
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            drawPath(drawn, ink, style = SignaturePen)
            if (stroke.isNotEmpty()) drawPath(Signature.smoothPath(listOf(stroke.toFloatArray())).asComposePath(), ink, style = SignaturePen)
        }
    }
}
