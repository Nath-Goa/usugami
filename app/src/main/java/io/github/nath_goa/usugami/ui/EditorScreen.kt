package io.github.nath_goa.usugami.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Rotate90DegreesCcw
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.LayoutSettings
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.Thumbnails
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

class EditorActions(
    val addFromPhotos: () -> Unit,
    val addFromFiles: () -> Unit,
    val changeLayout: (LayoutSettings) -> Unit,
    val rotate: (id: Long, degrees: Int) -> Unit,
    val remove: (id: Long) -> Unit,
    val move: (fromId: Long, toId: Long) -> Unit,
    val moveBy: (id: Long, offset: Int) -> Unit,
    val sort: (SortMode) -> Unit,
    val clearAll: () -> Unit,
    val convert: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    items: List<ImageItem>,
    layout: LayoutSettings,
    pendingImports: Int,
    thumbnails: Thumbnails,
    snackbar: SnackbarHostState,
    actions: EditorActions,
    onBack: () -> Unit,
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.editor_title))
                        // Keeps the last count while the line animates away after the last image goes.
                        val shownCount = freezeWhen(items.isEmpty(), items.size)
                        AnimatedVisibility(
                            visible = items.isNotEmpty(),
                            enter = fadeIn(Motion.medium()) + expandVertically(Motion.medium()),
                            exit = fadeOut(Motion.medium()) + shrinkVertically(Motion.medium()),
                            label = "image count",
                        ) {
                            Text(
                                pluralStringResource(R.plurals.image_count, shownCount, shownCount),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    AnimatedVisibility(
                        visible = items.size > 1,
                        enter = fadeIn(Motion.short()),
                        exit = fadeOut(Motion.short()),
                        label = "sort action",
                    ) {
                        SortMenu(actions.sort)
                    }
                    AnimatedVisibility(
                        visible = items.isNotEmpty(),
                        enter = fadeIn(Motion.short()),
                        exit = fadeOut(Motion.short()),
                        label = "clear action",
                    ) {
                        IconButton(onClick = { if (items.isNotEmpty()) confirmClear = true }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.clear_all))
                        }
                    }
                },
            )
        },
        bottomBar = {
            // Slides up when the first image arrives. It leaves at once: a bar that slid or shrank
            // away would hold the content padding to the end and then make the page jump.
            AnimatedVisibility(
                visible = items.isNotEmpty(),
                enter = slideInVertically(Motion.medium()) { fullHeight -> fullHeight },
                exit = ExitTransition.None,
                label = "bottom actions",
            ) {
                BottomActions(
                    convertEnabled = pendingImports == 0,
                    onAddPhotos = actions.addFromPhotos,
                    onAddFiles = actions.addFromFiles,
                    onConvert = actions.convert,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        // The bottom padding is applied per branch below, so a branch that is fading out can keep
        // the padding it had instead of jumping when the bottom bar appears or disappears.
        Column(
            Modifier
                .padding(
                    start = padding.calculateStartPadding(layoutDirection),
                    top = padding.calculateTopPadding(),
                    end = padding.calculateEndPadding(layoutDirection),
                )
                .fillMaxSize(),
        ) {
            AnimatedVisibility(
                visible = pendingImports > 0,
                enter = fadeIn(Motion.medium()) + expandVertically(Motion.medium()),
                exit = fadeOut(Motion.medium()) + shrinkVertically(Motion.medium()),
                label = "import progress",
            ) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Crossfade(targetState = items.isEmpty(), animationSpec = Motion.medium(), label = "editor content") { empty ->
                if (empty) {
                    val bottom = freezeWhen(items.isNotEmpty(), padding.calculateBottomPadding())
                    Box(Modifier.fillMaxSize().padding(bottom = bottom)) {
                        EmptyState(pendingImports > 0, actions.addFromPhotos, actions.addFromFiles)
                    }
                } else {
                    // Keeps showing the last pages while they fade out after the last image goes.
                    val shownItems = freezeWhen(items.isEmpty(), items)
                    val bottom = freezeWhen(items.isEmpty(), padding.calculateBottomPadding())
                    Column(Modifier.fillMaxSize().padding(bottom = bottom)) {
                        OptionsRow(layout, actions.changeLayout, Modifier.padding(top = 4.dp, bottom = 4.dp))
                        PageGrid(shownItems, layout, thumbnails, actions)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_title)) },
            text = { Text(stringResource(R.string.discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    actions.clearAll()
                }) { Text(stringResource(R.string.clear_all)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.keep)) } },
        )
    }
}

@Composable
private fun PageGrid(items: List<ImageItem>, layout: LayoutSettings, thumbnails: Thumbnails, actions: EditorActions) {
    val haptic = LocalHapticFeedback.current
    val gridState = rememberLazyGridState()
    // The reorder hint is inserted above the first page when the second image arrives. The grid
    // keeps the first page in place, which would leave the hint scrolled out of view, so stay at
    // the top when the grid was already there.
    val showHint = items.size > 1
    val hintWasShown = remember { booleanArrayOf(showHint) }
    if (showHint && !hintWasShown[0]) {
        val atTop = Snapshot.withoutReadObservation {
            gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
        }
        if (atTop) gridState.requestScrollToItem(0)
    }
    hintWasShown[0] = showHint
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        val fromId = from.key as? Long ?: return@rememberReorderableLazyGridState
        val toId = to.key as? Long ?: return@rememberReorderableLazyGridState
        actions.move(fromId, toId)
        haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        state = gridState,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (showHint) {
            item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(R.string.reorder_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.animateItem().padding(horizontal = 4.dp),
                )
            }
        }
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            ReorderableItem(reorderState, key = item.id) { isDragging ->
                val elevation by animateDpAsState(if (isDragging) 10.dp else 1.dp, label = "drag elevation")
                val moveEarlier = stringResource(R.string.move_page_earlier, index + 1)
                val moveLater = stringResource(R.string.move_page_later, index + 1)
                ElevatedCard(
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = elevation),
                    modifier = Modifier
                        .longPressDraggableHandle(
                            onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                            onDragStopped = { haptic.performHapticFeedback(HapticFeedbackType.GestureEnd) },
                        )
                        // Screen reader and switch users can't drag: the card offers the same moves as
                        // actions. Merged, so it is one focusable item that carries them.
                        .semantics(mergeDescendants = true) {
                            customActions = buildList {
                                if (index > 0) add(CustomAccessibilityAction(moveEarlier) { actions.moveBy(item.id, -1); true })
                                if (index < items.lastIndex) add(CustomAccessibilityAction(moveLater) { actions.moveBy(item.id, 1); true })
                            }
                        },
                ) {
                    PageCardContent(index + 1, item, layout, thumbnails, actions)
                }
            }
        }
        item(key = "add") {
            AddMoreCard(actions.addFromPhotos, actions.addFromFiles, Modifier.animateItem())
        }
    }
}

@Composable
private fun PageCardContent(pageNumber: Int, item: ImageItem, layout: LayoutSettings, thumbnails: Thumbnails, actions: EditorActions) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.85f)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(12.dp),
        ) {
            PagePreview(item, layout, thumbnails, Modifier.fillMaxSize())
            val pageLabel = stringResource(R.string.pdf_page_number, pageNumber)
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(50),
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                // A screen reader says "Page 3" rather than a bare "3".
                Text(
                    pageNumber.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .semantics { contentDescription = pageLabel }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            item.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            // Each card has these buttons, so their labels name the page they act on.
            IconButton(onClick = { actions.rotate(item.id, -90) }) {
                Icon(Icons.Rounded.Rotate90DegreesCcw, contentDescription = stringResource(R.string.rotate_page_left, pageNumber))
            }
            IconButton(onClick = { actions.rotate(item.id, 90) }) {
                Icon(Icons.Rounded.Rotate90DegreesCw, contentDescription = stringResource(R.string.rotate_page_right, pageNumber))
            }
            IconButton(onClick = { actions.remove(item.id) }) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.remove_page, pageNumber))
            }
        }
    }
}

@Composable
private fun AddMoreCard(onAddPhotos: () -> Unit, onAddFiles: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedCard(
            onClick = { menu = true },
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().aspectRatio(0.85f),
        ) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.add_more), style = MaterialTheme.typography.labelLarge)
            }
        }
        AddMenu(menu, onDismiss = { menu = false }, onAddPhotos, onAddFiles)
    }
}

@Composable
private fun AddMenu(expanded: Boolean, onDismiss: () -> Unit, onAddPhotos: () -> Unit, onAddFiles: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.add_from_photos)) },
            leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, contentDescription = null) },
            onClick = {
                onDismiss()
                onAddPhotos()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.add_from_files)) },
            leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
            onClick = {
                onDismiss()
                onAddFiles()
            },
        )
    }
}

@Composable
private fun SortMenu(onSort: (SortMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = stringResource(R.string.sort))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                SortMode.NAME_ASC to R.string.sort_name_az,
                SortMode.NAME_DESC to R.string.sort_name_za,
                SortMode.REVERSE to R.string.sort_reverse,
            ).forEach { (mode, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        expanded = false
                        onSort(mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun BottomActions(
    convertEnabled: Boolean,
    onAddPhotos: () -> Unit,
    onAddFiles: () -> Unit,
    onConvert: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var menu by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { menu = true }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.add_images), maxLines = 1)
                }
                AddMenu(menu, onDismiss = { menu = false }, onAddPhotos, onAddFiles)
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onConvert,
                enabled = convertEnabled,
                modifier = Modifier.weight(1f),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                // Shrinks a little on narrow phones, large text or a long translation, rather than being cut off.
                Text(
                    stringResource(R.string.convert),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize, stepSize = 0.5.sp),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(importing: Boolean, onAddPhotos: () -> Unit, onAddFiles: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, modifier = Modifier.padding(24.dp).size(56.dp))
        }
        Spacer(Modifier.height(24.dp))
        AnimatedContent(
            targetState = importing,
            transitionSpec = {
                val resize = SizeTransform { _, _ -> Motion.medium() }
                (fadeIn(Motion.short()) togetherWith fadeOut(Motion.short())).using(resize)
            },
            contentAlignment = Alignment.Center,
            label = "empty title",
        ) { adding ->
            Text(
                stringResource(if (adding) R.string.adding_images else R.string.empty_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onAddPhotos, enabled = !importing, modifier = Modifier.widthIn(min = 220.dp)) {
            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_from_photos))
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = onAddFiles, enabled = !importing, modifier = Modifier.widthIn(min = 220.dp)) {
            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_from_files))
        }
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.empty_share_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
