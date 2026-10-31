package io.github.nath_goa.usugami.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.BrandingWatermark
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Splitscreen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nath_goa.usugami.R

/** The tools under "Edit PDF" on Home, in order. */
private val EditTools = listOf(PdfTool.SPLIT, PdfTool.MERGE, PdfTool.COMPRESS, PdfTool.ROTATE, PdfTool.PAGE_NUMBERS, PdfTool.WATERMARK)

/** A tool's name: on its card on Home and at the top of its screen. */
@get:StringRes
internal val PdfTool.title: Int
    get() = when (this) {
        PdfTool.SPLIT -> R.string.tool_split
        PdfTool.MERGE -> R.string.tool_merge
        PdfTool.COMPRESS -> R.string.tool_compress
        PdfTool.ROTATE -> R.string.tool_rotate
        PdfTool.PAGE_NUMBERS -> R.string.tool_page_numbers
        PdfTool.WATERMARK -> R.string.tool_watermark
    }

/** What a tool does, in a short line under its name on Home. */
@get:StringRes
private val PdfTool.detail: Int
    get() = when (this) {
        PdfTool.SPLIT -> R.string.tool_split_detail
        PdfTool.MERGE -> R.string.tool_merge_detail
        PdfTool.COMPRESS -> R.string.tool_compress_detail
        PdfTool.ROTATE -> R.string.tool_rotate_detail
        PdfTool.PAGE_NUMBERS -> R.string.tool_page_numbers_detail
        PdfTool.WATERMARK -> R.string.tool_watermark_detail
    }

private val PdfTool.icon: ImageVector
    get() = when (this) {
        PdfTool.SPLIT -> Icons.Rounded.Splitscreen
        PdfTool.MERGE -> Icons.Rounded.Collections
        PdfTool.COMPRESS -> Icons.Rounded.Compress
        PdfTool.ROTATE -> Icons.AutoMirrored.Rounded.RotateRight
        PdfTool.PAGE_NUMBERS -> Icons.Rounded.FormatListNumbered
        PdfTool.WATERMARK -> Icons.AutoMirrored.Rounded.BrandingWatermark
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(snackbar: SnackbarHostState, onImages: () -> Unit, onTool: (PdfTool) -> Unit, onSettings: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        // Messages can arrive on any screen, such as about shared images Usugami can't use. One with
        // nowhere to show would wait, and show up later on another screen.
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // To add a tool, add its card to its section.
            ToolSection(R.string.home_section_create) {
                ToolCard(Icons.Rounded.PictureAsPdf, R.string.tool_images, R.string.tool_images_detail, onImages)
            }
            ToolSection(R.string.home_section_edit) {
                for (tool in EditTools) ToolCard(tool.icon, tool.title, tool.detail) { onTool(tool) }
            }
        }
    }
}

/** A small heading, which a screen reader can jump to, over a group of tool cards. */
@Composable
private fun ToolSection(@StringRes heading: Int, cards: @Composable ColumnScope.() -> Unit) {
    Text(
        stringResource(heading),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = cards)
}

@Composable
private fun ToolCard(icon: ImageVector, @StringRes title: Int, @StringRes detail: Int, onClick: () -> Unit) {
    // The card sinks back slightly while it is held down, so the tap feels physical.
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = Motion.short(),
        label = "toolCardScale",
    )
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        interactionSource = interactionSource,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(detail), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
