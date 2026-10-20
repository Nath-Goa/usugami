package com.nathan.pdfer.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.nathan.pdfer.R
import com.nathan.pdfer.core.Compression
import com.nathan.pdfer.core.LayoutSettings
import com.nathan.pdfer.core.Margin
import com.nathan.pdfer.core.PageSize
import com.nathan.pdfer.data.OcrLanguage

/** The last step before converting: one PDF or one per image, and the file name. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertSheet(
    imageCount: Int,
    layout: LayoutSettings,
    initialMerge: Boolean,
    initialCompression: Compression,
    defaultName: String,
    onDismiss: () -> Unit,
    onCreate: (merge: Boolean, fileName: String, compression: Compression, ocr: OcrLanguage?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var merge by rememberSaveable { mutableStateOf(initialMerge) }
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var compression by rememberSaveable { mutableStateOf(initialCompression) }
    var ocrEnabled by rememberSaveable { mutableStateOf(false) }
    var ocrLanguage by rememberSaveable { mutableStateOf(OcrLanguage.ENGLISH) }
    val oneFile = merge || imageCount == 1
    val create = { onCreate(merge, name, compression, if (ocrEnabled) ocrLanguage else null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(stringResource(R.string.convert_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(summary(imageCount, layout), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (imageCount > 1) {
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = merge, role = Role.Switch, onValueChange = { merge = it })
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.merge_title), style = MaterialTheme.typography.bodyLarge)
                        AnimatedContent(targetState = merge, transitionSpec = { textSwap() }, label = "mergeSummary") { on ->
                            Text(
                                stringResource(if (on) R.string.merge_on_summary else R.string.merge_off_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Switch(checked = merge, onCheckedChange = null)
                }
            }

            // The name field only applies to a single PDF, so it folds away when merging is turned off.
            AnimatedVisibility(
                visible = oneFile,
                enter = fadeIn(Motion.medium()) + expandVertically(Motion.medium()),
                exit = fadeOut(Motion.medium()) + shrinkVertically(Motion.medium()),
                label = "fileName",
            ) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.file_name)) },
                        suffix = { Text(".pdf") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { create() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.compression_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Compression.entries.forEachIndexed { index, level ->
                    SegmentedButton(
                        selected = compression == level,
                        onClick = { compression = level },
                        shape = SegmentedButtonDefaults.itemShape(index, Compression.entries.size),
                        label = { Text(compressionLabel(level), maxLines = 1) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            AnimatedContent(targetState = compression, transitionSpec = { textSwap() }, label = "compressionSummary") { level ->
                Text(
                    stringResource(
                        when (level) {
                            Compression.LESS -> R.string.compression_less_summary
                            Compression.RECOMMENDED -> R.string.compression_recommended_summary
                            Compression.EXTREME -> R.string.compression_extreme_summary
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().toggleable(value = ocrEnabled, role = Role.Switch, onValueChange = { ocrEnabled = it })
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ocr_title), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.ocr_detail), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(16.dp))
                Switch(checked = ocrEnabled, onCheckedChange = null)
            }
            AnimatedVisibility(
                visible = ocrEnabled,
                enter = fadeIn(Motion.medium()) + expandVertically(Motion.medium()),
                exit = fadeOut(Motion.medium()) + shrinkVertically(Motion.medium()),
                label = "ocrLanguage",
            ) {
                Column {
                    Text(stringResource(R.string.ocr_language), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        OcrLanguage.entries.forEachIndexed { index, language ->
                            SegmentedButton(
                                selected = ocrLanguage == language,
                                onClick = { ocrLanguage = language },
                                shape = SegmentedButtonDefaults.itemShape(index, OcrLanguage.entries.size),
                                label = { Text(stringResource(if (language == OcrLanguage.ENGLISH) R.string.ocr_english else R.string.ocr_hindi)) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(onClick = create, modifier = Modifier.fillMaxWidth()) {
                AnimatedContent(
                    targetState = oneFile,
                    transitionSpec = { textSwap() },
                    contentAlignment = Alignment.Center,
                    label = "createLabel",
                ) { single ->
                    Text(
                        if (single) stringResource(R.string.create_pdf)
                        else pluralStringResource(R.plurals.create_pdfs, imageCount, imageCount),
                    )
                }
            }
        }
    }
}

/** A quick cross-fade for text that changes in place; if its size changes, that glides too. */
private fun textSwap(): ContentTransform = ContentTransform(
    targetContentEnter = fadeIn(Motion.short()),
    initialContentExit = fadeOut(Motion.short()),
    sizeTransform = SizeTransform { _, _ -> Motion.medium() },
)

@Composable
private fun compressionLabel(level: Compression): String = stringResource(
    when (level) {
        Compression.LESS -> R.string.compression_less
        Compression.RECOMMENDED -> R.string.compression_recommended
        Compression.EXTREME -> R.string.compression_extreme
    },
)

@Composable
private fun summary(imageCount: Int, layout: LayoutSettings): String {
    val parts = mutableListOf(pluralStringResource(R.plurals.image_count, imageCount, imageCount), label(layout.pageSize))
    if (layout.pageSize != PageSize.FIT) parts += label(layout.orientation)
    parts += stringResource(
        when (layout.margin) {
            Margin.NONE -> R.string.margin_none
            Margin.SMALL -> R.string.margin_small_long
            Margin.BIG -> R.string.margin_big_long
        },
    )
    return parts.joinToString(" · ")
}

@Composable
fun ProgressDialog(progress: Progress, onCancel: () -> Unit) {
    // The bar glides to each new step instead of jumping as every image finishes.
    val fraction by animateFloatAsState(
        targetValue = if (progress.total == 0) 0f else progress.done.toFloat() / progress.total,
        animationSpec = Motion.medium(),
        label = "progress",
    )
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.creating_pdf)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(pluralStringResource(R.plurals.progress_images, progress.total, progress.done, progress.total))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
