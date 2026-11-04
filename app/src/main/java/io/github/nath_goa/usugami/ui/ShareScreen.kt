package io.github.nath_goa.usugami.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.data.PdfInput

/**
 * Whether the tool takes [count] PDFs shared to Usugami: any PDF tool takes one, and Merge PDF and
 * the tools that work on several PDFs at once take several.
 */
internal fun PdfTool.takesShared(count: Int): Boolean = officeKind == null && (count == 1 || this == PdfTool.MERGE || isBatch)

/**
 * Choose a tool: the PDFs shared to Usugami ([PdfToolsViewModel.inputs]), and under Home's
 * headings the tools that take them (see [takesShared]). [onTool] opens one with the PDFs in it.
 * The tools show once every PDF is in ([receiving] says more are still being copied in), and
 * those with a password are asked for in turn, as in a tool.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    vm: PdfToolsViewModel,
    receiving: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onTool: (PdfTool) -> Unit,
) {
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val unlocking by vm.unlocking.collectAsStateWithLifecycle()
    val wrongPassword by vm.wrongPassword.collectAsStateWithLifecycle()
    val views by vm.views.collectAsStateWithLifecycle()
    val busy = importing || receiving
    // The bar takes a tint of its own once the page scrolls under it.
    val barScroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(barScroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { BarTitle(stringResource(R.string.share_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) } },
                scrollBehavior = barScroll,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            // The bar carries its own gap, so the gap opens and closes with it.
            AnimatedVisibility(visible = busy, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "adding") {
                LinearProgressIndicator(Modifier.padding(bottom = 16.dp).fillMaxWidth())
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (input in inputs) {
                    key(input.id) { SharedPdfCard(input, thumbnail = views[input.id] ?: input, onRemove = { vm.removeInput(input.id) }) }
                }
            }
            // Which tools fit depends on how many PDFs there are, so they show once all are in.
            val count = inputs.size
            AnimatedVisibility(visible = !busy && locked.isEmpty() && count > 0, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "tools") {
                Column {
                    if (count > 1) {
                        Text(
                            stringResource(R.string.share_several_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    for ((heading, tools) in PdfToolSections) {
                        val fitting = tools.filter { it.takesShared(count) }
                        if (fitting.isNotEmpty()) {
                            ToolSection(heading) {
                                for (tool in fitting) ToolCard(tool.icon, tool.title, tool.detail) { onTool(tool) }
                            }
                        }
                    }
                }
            }
        }
    }
    // Several locked PDFs shared at once are asked for one at a time.
    locked.firstOrNull()?.let { pending ->
        PasswordDialog(
            input = pending,
            wrongPassword = wrongPassword,
            unlocking = unlocking,
            onUnlock = { vm.unlock(pending, it) },
            onEdit = vm::clearWrongPassword,
            onDismiss = { vm.dismissPassword(pending) },
        )
    }
}

/** A PDF shared to Usugami: its first page (drawn from [thumbnail]), name, pages and size, and a button that removes it. */
@Composable
private fun SharedPdfCard(input: PdfInput, thumbnail: PdfInput, onRemove: () -> Unit) {
    val entry = pdfBatchEntry(input)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PdfThumbnail(thumbnail, 0, Modifier.size(width = 44.dp, height = 58.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(entry.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.remove_file, input.name)) }
        }
    }
}
