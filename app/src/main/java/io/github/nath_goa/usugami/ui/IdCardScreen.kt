package io.github.nath_goa.usugami.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.nath_goa.usugami.R

/**
 * The ID card flow's prompt for [side]: what to do, and buttons that take a photo of that side with
 * the phone's camera app ([onTakePhoto]) or choose an image of it ([onChooseImage]). The cross ends
 * the flow ([onEnd]): on the front's prompt it cancels, on the back's it keeps the card with its front
 * alone, as No back side does. While the side's photo or image is being added and its page looked for
 * ([busy]), the two buttons wait and a line says so; ending the flow stays possible. Back is handled
 * by UsugamiApp, as for the editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdCardScreen(
    side: CardSide,
    busy: Boolean,
    snackbar: SnackbarHostState,
    onTakePhoto: () -> Unit,
    onChooseImage: () -> Unit,
    onEnd: () -> Unit,
) {
    val front = side == CardSide.FRONT
    val title = stringResource(R.string.card_title)
    val heading = stringResource(if (front) R.string.card_front else R.string.card_back)
    val pane = stringResource(R.string.scan_pane_title, title, heading)
    Scaffold(
        modifier = Modifier.semantics { paneTitle = pane },
        topBar = {
            TopAppBar(
                title = { BarTitle(title) },
                navigationIcon = {
                    IconButton(onClick = onEnd) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(if (front) R.string.cancel else R.string.card_no_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // As the empty editor is laid out: it scrolls when it doesn't fit, as in landscape.
        Column(
            Modifier
                .padding(padding)
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
                Icon(if (front) Icons.Rounded.Badge else Icons.Rounded.CreditCard, contentDescription = null, modifier = Modifier.padding(24.dp).size(56.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(
                heading,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(if (front) R.string.card_front_hint else R.string.card_back_hint),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            if (front) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.card_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(32.dp))
            Button(onClick = onTakePhoto, enabled = !busy, modifier = Modifier.widthIn(min = 220.dp)) {
                Icon(Icons.Rounded.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_from_camera))
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onChooseImage, enabled = !busy, modifier = Modifier.widthIn(min = 220.dp)) {
                Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.card_choose_image))
            }
            if (!front) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onEnd, modifier = Modifier.widthIn(min = 220.dp)) {
                    Text(stringResource(R.string.card_no_back))
                }
            }
            // Room kept for the line, so the buttons stay where they are when it shows.
            Box(Modifier.heightIn(min = 56.dp).padding(top = 16.dp), contentAlignment = Alignment.Center) {
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.card_adding),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
        }
    }
}
