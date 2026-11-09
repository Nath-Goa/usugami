package io.github.nath_goa.usugami.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.MarkLayout
import io.github.nath_goa.usugami.core.MarkStyle
import io.github.nath_goa.usugami.data.MarkSetting
import kotlin.math.roundToInt

/**
 * The Usugami mark on the result screen: a switch that puts it on the PDFs, or takes it off, and on
 * the same row an arrow that opens its size and opacity, with the first page showing the mark as they
 * move. The PDFs change once a slider is let go. [working] while they are being changed; [enabled] is
 * false while they are being saved, when nothing may change them.
 */
@Composable
internal fun MarkCard(
    setting: MarkSetting,
    working: Boolean,
    enabled: Boolean,
    preview: MarkPreview?,
    onOn: (Boolean) -> Unit,
    onStyle: (MarkStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    // The sliders' values while they move: the setting changes when one is let go.
    var size by remember(setting.style) { mutableFloatStateOf(setting.style.size.toFloat()) }
    var opacity by remember(setting.style) { mutableFloatStateOf(setting.style.opacity.toFloat()) }
    val moving = MarkStyle.of(size.roundToInt(), opacity.roundToInt())
    // Read when let go, after a screen reader's step has just moved it.
    fun letGo() {
        val chosen = MarkStyle.of(size.roundToInt(), opacity.roundToInt())
        if (chosen != setting.style) onStyle(chosen)
    }
    OutlinedCard(modifier.fillMaxWidth()) {
        // The whole row is the switch, for a screen reader and for a finger; the arrow is a button of its own.
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = setting.on, enabled = enabled, role = Role.Switch, onValueChange = onOn)
                .padding(start = 16.dp, top = 8.dp, bottom = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(stringResource(R.string.mark_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.mark_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val turn by animateFloatAsState(if (open) 180f else 0f, Motion.medium(), label = "markArrow")
            IconButton(
                onClick = { open = !open },
                modifier = Modifier.semantics {
                    if (open) collapse { open = false; true } else expand { open = true; true }
                },
            ) {
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.mark_adjust),
                    modifier = Modifier.graphicsLayer { rotationZ = turn },
                )
            }
            Switch(checked = setting.on, onCheckedChange = null, enabled = enabled)
        }
        // While the PDFs change: a bar under the row, in room of its own, so nothing moves meanwhile
        // (a slider being moved, say). A screen reader hears what it is.
        val shown by animateFloatAsState(if (working) 1f else 0f, Motion.medium(), label = "markWorking")
        val workingText = stringResource(R.string.mark_working)
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(4.dp).graphicsLayer { alpha = shown }) {
            if (working || shown > 0f) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().semantics {
                        contentDescription = workingText
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(Motion.medium()) + expandVertically(Motion.medium()),
            exit = fadeOut(Motion.medium()) + shrinkVertically(Motion.medium()),
            label = "markPanel",
        ) {
            MarkPanel(
                size = size,
                opacity = opacity,
                style = moving,
                on = setting.on,
                preview = preview,
                enabled = enabled && setting.on,
                onSize = { size = it },
                onOpacity = { opacity = it },
                onDone = ::letGo,
            )
        }
    }
}

/**
 * The mark's sliders and the page showing it. Beside each other where there is room (a phone on its
 * side, whose screen is too low for both one above the other; a tablet), otherwise the page above
 * the sliders, where a finger on a slider doesn't hide it.
 */
@Composable
private fun MarkPanel(
    size: Float,
    opacity: Float,
    style: MarkStyle,
    on: Boolean,
    preview: MarkPreview?,
    enabled: Boolean,
    onSize: (Float) -> Unit,
    onOpacity: (Float) -> Unit,
    onDone: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        // The sliders keep over 200 dp beside the page, room for their names in a large font size too.
        val beside = maxWidth >= 400.dp
        val sliders: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier) {
                MarkSlider(R.string.mark_size, size, 0f..100f, enabled, onSize, onDone)
                MarkSlider(R.string.mark_opacity, opacity, MarkStyle.MIN_OPACITY.toFloat()..100f, enabled, onOpacity, onDone)
            }
        }
        if (beside) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                MarkPage(preview, style, on)
                sliders(Modifier.weight(1f))
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MarkPage(preview, style, on)
                sliders(Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * A slider from [range] with its [label] and value, as a percentage, over it. [onDone] when it is let
 * go, and after each step a screen reader takes.
 */
@Composable
private fun MarkSlider(
    @StringRes label: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    val name = stringResource(label)
    val shown = stringResource(R.string.mark_percent, value.roundToInt())
    Column {
        // The slider says both to a screen reader.
        Row(Modifier.padding(top = 4.dp).clearAndSetSemantics {}) {
            Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(shown, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            enabled = enabled,
            // The slider's own item for a screen reader lies inside, out of reach of a name given here:
            // this one, named, takes its place.
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = name
                stateDescription = shown
                progressBarRangeInfo = ProgressBarRangeInfo(value, range)
                if (enabled) {
                    setProgress { target ->
                        val set = target.coerceIn(range).roundToInt().toFloat()
                        onChange(set)
                        onDone()
                        true
                    }
                } else {
                    disabled()
                }
            },
        )
    }
}

/**
 * The result's first page ([preview]; a white A4 page while there is none), with the mark drawn on it
 * in [style] as the PDFs will have it [on]; faint while it is off, as the PDFs haven't it.
 */
@Composable
private fun MarkPage(preview: MarkPreview?, style: MarkStyle, on: Boolean) {
    val width = preview?.width ?: A4_WIDTH
    val height = preview?.height ?: A4_HEIGHT
    val description = if (on) stringResource(R.string.mark_preview_description) else stringResource(R.string.pdf_page_number, 1)
    val shape = MaterialTheme.shapes.extraSmall
    Box(
        Modifier
            .widthIn(max = 150.dp)
            .heightIn(max = 200.dp)
            .aspectRatio(width / height)
            .clip(shape)
            .background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .semantics {
                contentDescription = description
                role = Role.Image
            },
    ) {
        preview?.page?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val scale = size.width / width
                val place = MarkLayout.place(style, width, height)
                // The same outlines as in the PDF, made at their size there: before Android 9, a path
                // drawn scaled up is a picture of it at its own size, stretched.
                val word = wordPath(place.fontSize * scale)
                val color = Color(MarkLayout.GRAY, MarkLayout.GRAY, MarkLayout.GRAY)
                val alpha = if (on) style.alpha else style.alpha * OFF_ALPHA
                onDrawBehind {
                    translate(place.x * scale, size.height - place.y * scale) { drawPath(word, color, alpha = alpha) }
                }
            },
        )
    }
}

/** The word as the PDFs draw it, [fontSize] pixels: its outlines, the baseline starting at the origin, y down. */
private fun wordPath(fontSize: Float) = Path().also { path ->
    MarkLayout.drawWord(
        object : MarkLayout.Pen {
            override fun moveTo(x: Float, y: Float) = path.moveTo(x, -y)
            override fun lineTo(x: Float, y: Float) = path.lineTo(x, -y)
            override fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = path.quadraticTo(x1, -y1, x, -y)
            override fun close() = path.close()
        },
        fontSize,
    )
}

/** How much of its opacity the word keeps on the page while the mark is off. */
private const val OFF_ALPHA = 0.25f

/** A4's size in points, for a page that couldn't be drawn. */
private const val A4_WIDTH = 595.28f
private const val A4_HEIGHT = 841.89f
