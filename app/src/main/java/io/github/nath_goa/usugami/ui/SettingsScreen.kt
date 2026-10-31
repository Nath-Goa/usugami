package io.github.nath_goa.usugami.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.data.AppLanguage

/** Settings: the language Usugami is shown in, its source code on GitHub, and About. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onChooseLanguage: (AppLanguage) -> Unit,
    onSourceCode: () -> Unit,
    onAbout: () -> Unit,
) {
    val current = AppLanguage.shownIn(LocalContext.current)
    var choosing by rememberSaveable { mutableStateOf(false) }
    // Applied once the dialog has closed, so its window is gone before the activity is recreated
    // in the new language.
    var switchTo by remember { mutableStateOf<AppLanguage?>(null) }
    LaunchedEffect(switchTo) { switchTo?.let(onChooseLanguage) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(vertical = 8.dp)) {
            ListRow(Icons.Rounded.Language, stringResource(R.string.settings_language), current.nativeName) { choosing = true }
            ListRow(Icons.Rounded.Code, stringResource(R.string.settings_github), stringResource(R.string.about_source_detail), external = true, onClick = onSourceCode)
            ListRow(Icons.Outlined.Info, stringResource(R.string.about), stringResource(R.string.settings_about_detail), onClick = onAbout)
        }
    }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    for (language in AppLanguage.entries) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = language == current, role = Role.RadioButton) {
                                    choosing = false
                                    if (language != current) switchTo = language
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = language == current, onClick = null)
                            Spacer(Modifier.width(16.dp))
                            Text(nativeName(language), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * A language's own name, marked as being in that language, so a screen reader reads "Français"
 * with a French voice.
 */
internal fun nativeName(language: AppLanguage): AnnotatedString =
    AnnotatedString(language.nativeName, SpanStyle(localeList = LocaleList(language.tag)))
