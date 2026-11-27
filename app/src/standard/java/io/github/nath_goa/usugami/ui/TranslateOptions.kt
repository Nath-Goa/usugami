package io.github.nath_goa.usugami.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.data.AppLanguage
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.TranslationEngine
import io.github.nath_goa.usugami.data.TranslationProblem
import io.github.nath_goa.usugami.data.currentTranslationEngine
import io.github.nath_goa.usugami.ui.kit.DividedColumn
import io.github.nath_goa.usugami.ui.kit.GlyphButton
import io.github.nath_goa.usugami.ui.kit.QuietButton
import io.github.nath_goa.usugami.ui.kit.SectionHeading
import io.github.nath_goa.usugami.ui.kit.Sheet
import io.github.nath_goa.usugami.ui.kit.SheetDivider
import io.github.nath_goa.usugami.ui.kit.SheetRow
import io.github.nath_goa.usugami.ui.kit.UsugamiBottomSheet
import io.github.nath_goa.usugami.ui.kit.UsugamiDialog
import java.util.Locale

/**
 * Translate PDF's choices, for [input]; its pages are drawn from [shown] (see
 * PdfToolsEngine.previewView). The language the PDF is in (From), found from its text once per PDF
 * unless the user chooses one, and the language to translate to (To), which starts as the app's;
 * the pages, as in PDF to images or text; the language packs this needs, with a sheet that lists and
 * deletes those on the phone. They tell [action] what the button does; the file name is the screen's.
 *
 * The choices survive rotation, the result screen and Android stopping Usugami; From and the pages
 * start again with another PDF, and everything with the tool opened afresh.
 */
@Composable
internal fun TranslateOptions(input: PdfInput, shown: PdfInput, vm: PdfToolsViewModel, action: ToolAction) {
    val context = LocalContext.current
    val engine = remember { currentTranslationEngine(context.applicationContext) }
    // Languages are named in the app's language, and listed in its order.
    val locale = LocalConfiguration.current.locales[0]
    val languages = remember(engine, locale) { byName(engine?.languages.orEmpty(), locale) }
    val appTag = AppLanguage.shownIn(context).translationTag()
    var detecting by rememberSaveable(input.id) { mutableStateOf(true) }
    var detected by rememberSaveable(input.id) { mutableStateOf<String?>(null) }
    var chosenSource by rememberSaveable(input.id) { mutableStateOf<String?>(null) }
    var chosenTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var pages by rememberSaveable(input.id) { mutableStateOf("") }
    var showPacks by rememberSaveable { mutableStateOf(false) }
    // Once per PDF: not again after a rotation or the result screen, which keep what was found.
    LaunchedEffect(input.id) {
        if (detecting) {
            detected = vm.detectLanguage(input, engine)
            detecting = false
        }
    }
    // A language the user chose is never replaced by one detected.
    val source = chosenSource ?: detected
    val target = chosenTarget ?: defaultTarget(appTag, source)
    val same = source == target
    val packsChanged by vm.translate.packs.collectAsStateWithLifecycle()
    // The packs on the phone, read again whenever they may have changed; null until known.
    val onPhone by produceState<Set<String>?>(null, engine, packsChanged) { value = engine?.downloadedOrNull() }

    // The sections that come and go carry the gap above them, as in PDF to images or text.
    Column {
        SectionHeading(stringResource(R.string.translate_languages), top = 8.dp, bottom = 0.dp)
        // Drawn round the choices, which are read one by one: no node of its own (R17).
        Sheet(Modifier.padding(top = 16.dp).fillMaxWidth(), group = false) {
            Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 12.dp)) {
                // Side by side while they fit; a long name or a large font size puts To under From.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LanguageChip(
                        label = stringResource(R.string.translate_from),
                        value = when {
                            source != null -> languageName(source, locale)
                            detecting -> stringResource(R.string.translate_detecting)
                            else -> stringResource(R.string.translate_choose)
                        },
                        languages = languages,
                        selected = source,
                        locale = locale,
                        onSelect = { chosenSource = it },
                    )
                    LanguageChip(
                        label = stringResource(R.string.translate_to),
                        value = languageName(target, locale),
                        languages = languages,
                        selected = target,
                        locale = locale,
                        onSelect = { chosenTarget = it },
                    )
                }
                val hint = when {
                    same -> null
                    chosenSource == null && detected != null -> R.string.translate_detected
                    source == null && !detecting -> R.string.translate_not_detected
                    else -> null
                }
                if (hint != null) {
                    Text(
                        stringResource(hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                // Says why the button is off, and a screen reader says it as it shows.
                if (same) {
                    Text(
                        stringResource(R.string.translate_same),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            SheetDivider()
            // One button, which a screen reader reads with what it says of the packs.
            SheetRow(onClick = { showPacks = true }, enabled = engine != null, role = Role.Button) {
                Text(stringResource(R.string.translate_packs), style = MaterialTheme.typography.bodyLarge)
                packsLine(onPhone, source, target, locale)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // A PDF of one page has no pages to choose from. The pages are translated, not converted.
        if (input.pageCount > 1) {
            PagesChoice(
                input,
                shown,
                pages,
                onText = { pages = it },
                onTap = { change -> pages = change(pages) },
                hint = R.string.translate_pages_hint,
                allPages = R.string.translate_pages_all,
                somePages = R.string.translate_pages_count,
            )
        }
        Text(
            stringResource(R.string.translate_scans_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp).padding(horizontal = 8.dp),
        )
    }
    // Once, here: the button is on once both languages are known and differ. Without an engine (never
    // in this edition) it is on, and says so when pressed.
    action.Bind(ready = engine == null || (source != null && !same)) { fileName ->
        vm.runTranslate(input, pages, source, target, fileName)
    }

    if (showPacks && engine != null) LanguagePacksSheet(engine, vm, locale, packsChanged, onDismiss = { showPacks = false })
    val download by vm.translate.download.collectAsStateWithLifecycle()
    download?.let { request ->
        DownloadDialog(request.missing, locale, onConfirm = { vm.confirmTranslateDownload() }, onDismiss = { vm.dismissTranslateDownload() })
    }
    val problem by vm.translate.problem.collectAsStateWithLifecycle()
    problem?.let { TranslationProblemDialog(it, onDismiss = { vm.translate.problem.value = null }) }
    val ocrProblem by vm.translate.ocrProblem.collectAsStateWithLifecycle()
    // No way out through Pages where there is no Pages field (one page, which is the scan), and no
    // Usugami OCR, which has no Translate PDF.
    ocrProblem?.let {
        OcrProblemDialog(
            it,
            retry = if (input.pageCount > 1) R.string.translate_ocr_retry else null,
            help = null,
            onDismiss = { vm.translate.ocrProblem.value = null },
        )
    }
}

/** A chip, "[label]: [value]", whose menu offers [languages] by name, [selected] checked. */
@Composable
private fun LanguageChip(label: String, value: String, languages: List<String>, selected: String?, locale: Locale, onSelect: (String) -> Unit) {
    OptionChip<String?>(
        label = label,
        value = value,
        options = languages,
        selected = selected,
        optionLabel = { tag -> tag?.let { languageName(it, locale) }.orEmpty() },
        onSelect = { tag -> tag?.let(onSelect) },
        enabled = languages.isNotEmpty(),
    )
}

/**
 * What the packs row says: that the packs translating [source] to [target] needs are on the phone
 * ([onPhone]), or which must download first, how big they are and that it is once. Null until the
 * packs on the phone are known, and when the languages need none (English is built in). Without a
 * known [source], only that [target]'s pack must download, when it must.
 */
@Composable
private fun packsLine(onPhone: Set<String>?, source: String?, target: String, locale: Locale): String? {
    if (onPhone == null) return null
    val needed = packsNeeded(source ?: target, target)
    val missing = needed.filterNot { it in onPhone }
    return when {
        missing.isNotEmpty() -> pluralStringResource(R.plurals.translate_packs_needed, missing.size, namesListed(missing, locale))
        // Until From is known, the packs on the phone may not be all the run needs.
        source == null || needed.isEmpty() -> null
        else -> pluralStringResource(R.plurals.translate_packs_ready, needed.size, namesListed(needed, locale))
    }
}

/**
 * The language packs on the phone, by name, each with a button that deletes it once the user says
 * so. English, built into the engine, isn't a pack and isn't listed. [packsChanged] counts the
 * changes, to read them again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePacksSheet(engine: TranslationEngine, vm: PdfToolsViewModel, locale: Locale, packsChanged: Int, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val onPhone by produceState<Set<String>?>(null, engine, packsChanged) { value = engine.downloadedOrNull() }
    // The pack the user asked to delete, while the dialog asks whether to.
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    UsugamiBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // Scrolls when the list doesn't fit (landscape, large text).
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(stringResource(R.string.translate_packs), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.translate_packs_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            SheetDivider()
            // The engine counts English among its packs, always there.
            val packs = onPhone?.let { byName(it - ENGLISH, locale) }
            if (packs != null && packs.isEmpty()) {
                Text(
                    stringResource(R.string.translate_packs_none),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            } else if (packs != null) {
                DividedColumn(inset = 0.dp) {
                    for (tag in packs) key(tag) { PackRow(tag, locale, onDelete = { deleting = tag }) }
                }
            }
        }
    }
    deleting?.let { tag ->
        UsugamiDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.translate_pack_delete_title, languageWord(tag, locale))) },
            text = { Text(stringResource(R.string.translate_pack_delete_body)) },
            confirmButton = {
                QuietButton(
                    onClick = {
                        deleting = null
                        vm.deleteLanguagePack(engine, tag)
                    },
                    danger = true,
                ) { Text(stringResource(R.string.translate_pack_delete_confirm)) }
            },
            dismissButton = { QuietButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** A language pack on the phone, [tag]'s: its name and a delete button. */
@Composable
private fun PackRow(tag: String, locale: Locale, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(languageName(tag, locale), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        // Quiet, as a list of red glyphs would shout: the dialog's Delete is the red one.
        GlyphButton(onClick = onDelete) {
            Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.translate_pack_delete, languageWord(tag, locale)), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Asks whether to download the language packs [missing] before translating: which, how big, and that it is once. */
@Composable
private fun DownloadDialog(missing: List<String>, locale: Locale, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val count = missing.size
    UsugamiDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.translate_download_title, count)) },
        // Scrolls when large text in landscape leaves too little room for all it says.
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(pluralStringResource(R.plurals.translate_download_body, count, namesListed(missing, locale)))
            }
        },
        confirmButton = { QuietButton(onClick = onConfirm) { Text(stringResource(R.string.translate_download_confirm)) } },
        dismissButton = { QuietButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Shown in place of a result when translating couldn't start: why, and what to do. */
@Composable
private fun TranslationProblemDialog(problem: TranslationProblem, onDismiss: () -> Unit) {
    UsugamiDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.translate_problem_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(
                        when (problem) {
                            TranslationProblem.NO_INTERNET -> R.string.translate_problem_no_internet
                            TranslationProblem.DOWNLOAD_FAILED -> R.string.translate_problem_download
                            TranslationProblem.NO_STORAGE -> R.string.translate_problem_storage
                            TranslationProblem.FAILED_TO_START -> R.string.translate_problem_failed
                        },
                    ),
                )
            }
        },
        confirmButton = { QuietButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}
