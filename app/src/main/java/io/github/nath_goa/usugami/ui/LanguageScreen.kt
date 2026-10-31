package io.github.nath_goa.usugami.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.data.AppLanguage

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
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            AppIcon()
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.language_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(
                stringResource(R.string.language_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            for (language in AppLanguage.entries) {
                OutlinedCard(
                    onClick = {
                        if (!chosen) {
                            chosen = true
                            onChoose(language)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(nativeName(language), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp))
                }
            }
        }
    }
}
