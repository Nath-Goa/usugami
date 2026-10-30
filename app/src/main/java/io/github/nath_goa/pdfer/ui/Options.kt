package io.github.nath_goa.pdfer.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nath_goa.pdfer.R
import io.github.nath_goa.pdfer.core.LayoutSettings
import io.github.nath_goa.pdfer.core.Margin
import io.github.nath_goa.pdfer.core.PageOrientation
import io.github.nath_goa.pdfer.core.PageSize

/** Page size, orientation and margin pickers, like the options panel of the web tools. */
@Composable
fun OptionsRow(layout: LayoutSettings, onChange: (LayoutSettings) -> Unit, modifier: Modifier = Modifier) {
    val fit = layout.pageSize == PageSize.FIT
    Row(
        modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OptionChip(
            label = stringResource(R.string.option_page_size),
            value = shortLabel(layout.pageSize),
            options = PageSize.entries,
            selected = layout.pageSize,
            optionLabel = { label(it) },
            onSelect = { onChange(layout.copy(pageSize = it)) },
        )
        OptionChip(
            label = stringResource(R.string.option_orientation),
            value = if (fit) stringResource(R.string.orientation_fit_note) else label(layout.orientation),
            options = PageOrientation.entries,
            selected = layout.orientation,
            optionLabel = { label(it) },
            onSelect = { onChange(layout.copy(orientation = it)) },
            enabled = !fit,
        )
        OptionChip(
            label = stringResource(R.string.option_margin),
            value = label(layout.margin),
            options = Margin.entries,
            selected = layout.margin,
            optionLabel = { label(it) },
            onSelect = { onChange(layout.copy(margin = it)) },
        )
    }
}

@Composable
private fun <T> OptionChip(
    label: String,
    value: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            enabled = enabled,
            // When the chosen value changes length, the chip (and the chips after it) resize smoothly.
            label = { Text("$label: $value", modifier = Modifier.animateContentSize(Motion.medium())) },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    trailingIcon = if (option == selected) {
                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
fun label(size: PageSize): String = stringResource(
    when (size) {
        PageSize.FIT -> R.string.size_fit
        PageSize.A4 -> R.string.size_a4
        PageSize.LETTER -> R.string.size_letter
        PageSize.LEGAL -> R.string.size_legal
        PageSize.A3 -> R.string.size_a3
        PageSize.A5 -> R.string.size_a5
    },
)

@Composable
fun shortLabel(size: PageSize): String =
    if (size == PageSize.FIT) stringResource(R.string.size_fit_short) else label(size)

@Composable
fun label(orientation: PageOrientation): String = stringResource(
    when (orientation) {
        PageOrientation.AUTO -> R.string.orientation_auto
        PageOrientation.PORTRAIT -> R.string.orientation_portrait
        PageOrientation.LANDSCAPE -> R.string.orientation_landscape
    },
)

@Composable
fun label(margin: Margin): String = stringResource(
    when (margin) {
        Margin.NONE -> R.string.margin_none
        Margin.SMALL -> R.string.margin_small
        Margin.BIG -> R.string.margin_big
    },
)
