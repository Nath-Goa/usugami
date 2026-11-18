package io.github.nath_goa.usugami.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.data.AppLanguage
import io.github.nath_goa.usugami.ui.kit.DividedColumn
import io.github.nath_goa.usugami.ui.kit.Sheet
import io.github.nath_goa.usugami.ui.kit.SheetRow
import io.github.nath_goa.usugami.ui.kit.Space

/**
 * Shown the first time Usugami opens: the language for its screens, which is also the default
 * language for text recognition. It can be changed later in Settings.
 */
@Composable
fun LanguageScreen(onChoose: (AppLanguage) -> Unit) {
    // A second tap before the screen goes away must not switch again.
    var chosen by remember { mutableStateOf(false) }
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = Space.content).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.gap)) {
                Spacer(Modifier.height(16.dp))
                // Lined up with the text on the ground below it.
                Box(Modifier.padding(start = Space.ground)) { AppIcon() }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.language_title),
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.padding(horizontal = Space.ground).semantics { heading() },
                )
                Text(
                    stringResource(R.string.language_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Space.ground, end = Space.ground, bottom = 12.dp),
                )
                // Each language was a card of its own on the ground in 2.0.1: the one sheet round them
                // is drawn, and is no node of its own.
                Sheet(Modifier.fillMaxWidth(), group = false) {
                    DividedColumn(inset = 16.dp) {
                        for (language in AppLanguage.entries) {
                            SheetRow(
                                onClick = {
                                    if (!chosen) {
                                        chosen = true
                                        onChoose(language)
                                    }
                                },
                                minHeight = 56.dp,
                            ) {
                                Text(nativeName(language), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}
