package io.github.nath_goa.usugami.ui.kit

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

// Top sheets: dialogs and the bottom sheet lie on the screen as the topmost paper. Menus (Material's
// DropdownMenu, with MenuItem items) and snackbars (Material's SnackbarHost) are not wrapped: the
// theme already gives them the top sheet and the dark ink.

/**
 * A dialog: Material's (its own window), a white top sheet (light) with 24 dp corners and a hairline
 * edge, its title in `onSurface` and its text in `onSurfaceVariant`. Its buttons are QuietButtons (a
 * destructive confirm with danger = true). The window behind dims black, 32 % (55 % in the dark
 * theme). Nothing inside animates on opening.
 */
@Composable
fun UsugamiDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    val colors = Usugami.colors
    val scheme = MaterialTheme.colorScheme
    val dim = if (colors.isDark) 0.55f else 0.32f
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            // A platform dialog's dim is black and cannot be tinted; only how much it darkens is set.
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect { window?.setDimAmount(dim) }
            confirmButton()
        },
        // If this edge is seen not to follow the dialog's on a device, remove it and nothing else.
        modifier = modifier.border(1.dp, colors.hairline, DialogShape),
        dismissButton = dismissButton,
        title = title,
        text = text,
        shape = DialogShape,
        containerColor = colors.sheetTop,
        titleContentColor = scheme.onSurface,
        textContentColor = scheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        properties = properties,
    )
}

/**
 * A modal bottom sheet: Material's, a white top sheet (light) with 28 dp top corners and a short
 * drag handle, over Usugami's scrim. Material's settle animation and drag stay; the screen behind is
 * not scaled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsugamiBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Usugami.colors
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = BottomSheetShape,
        containerColor = colors.sheetTop,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        scrimColor = colors.scrim,
        dragHandle = { BottomSheetDefaults.DragHandle(width = 32.dp, height = 4.dp, color = colors.hairlineStrong) },
        content = content,
    )
}
