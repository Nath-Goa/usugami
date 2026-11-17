package io.github.nath_goa.usugami.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * A text field as a well cut into the paper: no line under it, an outline in `primary` while focused
 * and in `error` with [isError]. Built as Material builds its own filled field, so the label's
 * animation, the paddings and what a screen reader hears (label, placeholder and supporting text
 * inside the editable node) are Material's; with [isError] it is heard as [errorMessage], the message
 * its supporting text shows. 56 dp tall at least, 44 when [compact] (the reader's Find).
 */
@Composable
fun WellField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    errorMessage: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    compact: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
) {
    // The same source for the text field and its decoration, so the decoration sees the focus.
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val well = Usugami.colors.well
    val wellEdge = Usugami.colors.wellEdge
    val focused by source.collectIsFocusedAsState()
    val muted = scheme.onSurfaceVariant
    val disabled = scheme.onSurface.copy(alpha = 0.38f)
    val textColor = textStyle.color.takeOrElse { if (enabled) scheme.onSurface else disabled }
    val style = textStyle.merge(TextStyle(color = textColor))
    val colors = TextFieldDefaults.colors(
        focusedTextColor = scheme.onSurface,
        unfocusedTextColor = scheme.onSurface,
        disabledTextColor = disabled,
        errorTextColor = scheme.onSurface,
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        errorContainerColor = Color.Transparent,
        cursorColor = scheme.primary,
        errorCursorColor = scheme.error,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        errorIndicatorColor = Color.Transparent,
        focusedLabelColor = scheme.primary,
        unfocusedLabelColor = muted,
        disabledLabelColor = disabled,
        errorLabelColor = scheme.error,
        focusedPlaceholderColor = muted,
        unfocusedPlaceholderColor = muted,
        disabledPlaceholderColor = disabled,
        errorPlaceholderColor = muted,
        focusedSupportingTextColor = muted,
        unfocusedSupportingTextColor = muted,
        disabledSupportingTextColor = disabled,
        errorSupportingTextColor = scheme.error,
        focusedPrefixColor = muted,
        unfocusedPrefixColor = muted,
        disabledPrefixColor = disabled,
        errorPrefixColor = muted,
        focusedSuffixColor = muted,
        unfocusedSuffixColor = muted,
        disabledSuffixColor = disabled,
        errorSuffixColor = muted,
    )
    val contentPadding = when {
        compact -> TextFieldDefaults.contentPaddingWithoutLabel(top = 10.dp, bottom = 10.dp)
        label != null -> TextFieldDefaults.contentPaddingWithLabel()
        else -> TextFieldDefaults.contentPaddingWithoutLabel()
    }
    val edgeWidth = if (isError || focused) 1.5.dp else 1.dp
    val edgeColor = when {
        isError -> scheme.error
        focused -> scheme.primary
        else -> wellEdge
    }
    val fill = if (enabled) well else well.copy(alpha = 0.6f)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            // Heard as the message the field shows, not Material's generic "Invalid input".
            .semantics { if (isError) error(errorMessage ?: "") }
            .defaultMinSize(minWidth = TextFieldDefaults.MinWidth, minHeight = if (compact) 44.dp else 56.dp),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = style,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        visualTransformation = visualTransformation,
        interactionSource = source,
        cursorBrush = SolidColor(if (isError) scheme.error else scheme.primary),
        decorationBox = { innerTextField ->
            TextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = innerTextField,
                enabled = enabled,
                singleLine = singleLine,
                visualTransformation = visualTransformation,
                interactionSource = source,
                isError = isError,
                label = label,
                placeholder = placeholder,
                leadingIcon = leadingIcon,
                trailingIcon = trailingIcon,
                prefix = prefix,
                suffix = suffix,
                supportingText = supportingText,
                shape = WellShape,
                colors = colors,
                contentPadding = contentPadding,
                container = {
                    Box(Modifier.background(fill, WellShape).border(edgeWidth, edgeColor, WellShape))
                },
            )
        },
    )
}
