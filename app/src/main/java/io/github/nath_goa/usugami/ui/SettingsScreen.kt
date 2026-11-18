package io.github.nath_goa.usugami.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.data.AppLanguage
import io.github.nath_goa.usugami.data.Appearance
import io.github.nath_goa.usugami.data.Appearances
import io.github.nath_goa.usugami.ui.kit.DividedColumn
import io.github.nath_goa.usugami.ui.kit.GlyphButton
import io.github.nath_goa.usugami.ui.kit.QuietButton
import io.github.nath_goa.usugami.ui.kit.Sheet
import io.github.nath_goa.usugami.ui.kit.Space
import io.github.nath_goa.usugami.ui.kit.UsugamiDialog
import io.github.nath_goa.usugami.ui.kit.UsugamiTopBar

/**
 * Settings: the language Usugami is shown in, its appearance (light, dark or as the phone is set),
 * its source code on GitHub, how to donate, and About.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onChooseLanguage: (AppLanguage) -> Unit,
    onChooseAppearance: (Appearance) -> Unit,
    onSourceCode: () -> Unit,
    onDonate: () -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    // The language chosen last, until the activity is recreated in it. From Android 13 that happens a
    // moment later, and choosing again meanwhile is measured against this choice, not the old language.
    var chosen by remember { mutableStateOf<AppLanguage?>(null) }
    val current = chosen ?: AppLanguage.shownIn(context)
    // Choosing one recreates the activity only when that turns it light or dark, so the row is
    // updated here too.
    var appearance by remember { mutableStateOf(Appearances.current(context)) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var choosingAppearance by rememberSaveable { mutableStateOf(false) }
    // Applied once the dialog has closed, so its window is gone before the activity is recreated
    // in the new language or appearance.
    var switchTo by remember { mutableStateOf<AppLanguage?>(null) }
    LaunchedEffect(switchTo) { switchTo?.let(onChooseLanguage) }
    var appearanceTo by remember { mutableStateOf<Appearance?>(null) }
    LaunchedEffect(appearanceTo) { appearanceTo?.let(onChooseAppearance) }
    // A hairline shows under the bar once the page scrolls under it.
    val barScroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(barScroll.nestedScrollConnection),
        topBar = {
            UsugamiTopBar(
                scrollBehavior = barScroll,
                title = { BarTitle(stringResource(R.string.settings)) },
                navigationIcon = {
                    GlyphButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // The padding goes inside the scrolling area, so the page scrolls behind the navigation bar.
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The rows lay on the ground in 2.0.1: their sheet is drawn, and is no node of its own.
            Sheet(Modifier.widthIn(max = Space.content).fillMaxWidth(), group = false) {
                DividedColumn {
                    ListRow(Icons.Rounded.Language, stringResource(R.string.settings_language), current.nativeName) { choosing = true }
                    ListRow(Icons.Rounded.BrightnessMedium, stringResource(R.string.settings_appearance), stringResource(appearance.label)) { choosingAppearance = true }
                    ListRow(Icons.Rounded.Code, stringResource(R.string.settings_github), stringResource(R.string.about_source_detail), external = true, onClick = onSourceCode)
                    ListRow(Icons.Rounded.VolunteerActivism, stringResource(R.string.settings_donate), stringResource(R.string.settings_donate_detail), onClick = onDonate)
                    ListRow(Icons.Outlined.Info, stringResource(R.string.about), stringResource(R.string.settings_about_detail), onClick = onAbout)
                }
            }
        }
    }
    if (choosing) {
        UsugamiDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                // Scrolls when large text in landscape leaves too little room for every choice.
                Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                    for (language in AppLanguage.entries) {
                        RadioRow(nativeName(language), selected = language == current) {
                            choosing = false
                            if (language != current) {
                                chosen = language
                                switchTo = language
                            }
                        }
                    }
                }
            },
            confirmButton = { QuietButton(onClick = { choosing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (choosingAppearance) {
        UsugamiDialog(
            onDismissRequest = { choosingAppearance = false },
            title = { Text(stringResource(R.string.settings_appearance)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                    for (option in Appearance.entries) {
                        RadioRow(AnnotatedString(stringResource(option.label)), selected = option == appearance) {
                            choosingAppearance = false
                            if (option != appearance) {
                                appearance = option
                                appearanceTo = option
                            }
                        }
                    }
                }
            },
            confirmButton = { QuietButton(onClick = { choosingAppearance = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** A choice in a dialog: the whole row is one radio button for a screen reader. */
@Composable
private fun RadioRow(text: AnnotatedString, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** How an appearance is named in Settings. */
private val Appearance.label: Int
    @StringRes get() = when (this) {
        Appearance.SYSTEM -> R.string.appearance_system
        Appearance.LIGHT -> R.string.appearance_light
        Appearance.DARK -> R.string.appearance_dark
    }

/**
 * A language's own name, marked as being in that language, so a screen reader reads "Français"
 * with a French voice.
 */
internal fun nativeName(language: AppLanguage): AnnotatedString =
    AnnotatedString(language.nativeName, SpanStyle(localeList = LocaleList(language.tag)))
