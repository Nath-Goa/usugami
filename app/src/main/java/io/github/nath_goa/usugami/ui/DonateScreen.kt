package io.github.nath_goa.usugami.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.ui.kit.ButtonGlyph
import io.github.nath_goa.usugami.ui.kit.ButtonWithGlyphPadding
import io.github.nath_goa.usugami.ui.kit.GlyphButton
import io.github.nath_goa.usugami.ui.kit.PrimaryButton
import io.github.nath_goa.usugami.ui.kit.Sheet
import io.github.nath_goa.usugami.ui.kit.SheetDivider
import io.github.nath_goa.usugami.ui.kit.Space
import io.github.nath_goa.usugami.ui.kit.Usugami
import io.github.nath_goa.usugami.ui.kit.UsugamiTopBar

/**
 * How to donate, in four steps: through GitHub Sponsors, from the Sponsor button on Usugami's page
 * on GitHub, which [onOpenGitHub] opens in the browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DonateScreen(snackbar: SnackbarHostState, onBack: () -> Unit, onOpenGitHub: () -> Unit) {
    // A hairline shows under the bar once the page scrolls under it.
    val barScroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(barScroll.nestedScrollConnection),
        topBar = {
            UsugamiTopBar(
                scrollBehavior = barScroll,
                title = { BarTitle(stringResource(R.string.settings_donate)) },
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
            Column(Modifier.widthIn(max = Space.content).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.gap)) {
                Text(stringResource(R.string.donate_intro), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 8.dp))
                val textStart = stepTextStart()
                // The steps lay on the ground in 2.0.1: their sheet is drawn, and is no node of its own.
                Sheet(Modifier.fillMaxWidth(), group = false) {
                    DonateStep(1, stringResource(R.string.donate_step_open)) {
                        Text(
                            stringResource(R.string.about_source_detail),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PrimaryButton(onClick = onOpenGitHub, modifier = Modifier.padding(top = 8.dp), contentPadding = ButtonWithGlyphPadding) {
                            ButtonGlyph(Icons.AutoMirrored.Rounded.OpenInNew)
                            Text(stringResource(R.string.donate_open_github))
                        }
                    }
                    SheetDivider(inset = textStart)
                    DonateStep(2, stringResource(R.string.donate_step_sponsor))
                    SheetDivider(inset = textStart)
                    DonateStep(3, stringResource(R.string.donate_step_amount))
                    SheetDivider(inset = textStart)
                    DonateStep(4, stringResource(R.string.donate_step_pay))
                }
                Text(
                    stringResource(R.string.donate_thanks),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, end = 8.dp),
                )
            }
        }
    }
}

/** A step's padding in its sheet, and the gap between its number circle and its text. */
private val StepPadding = 16.dp

/** The padding round the number in its circle. */
private val NumberPadding = 4.dp

/** A circle at least 32 dp across; with large text it grows round its label, still a circle. */
private fun Density.circleSide(labelWidth: Int, labelHeight: Int): Int = maxOf(labelWidth, labelHeight, 32.dp.roundToPx())

/**
 * Where a step's text starts in its sheet, and so the line between two steps: the step's padding, its
 * number circle as [DonateStep] sizes it, and the gap after the circle. At the default text size that is
 * 16 + 32 + 16 = 64 dp; with larger text the circle grows, and the line starts later with it.
 */
@Composable
private fun stepTextStart(): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.titleSmall
    val density = LocalDensity.current
    return remember(measurer, style, density) {
        with(density) {
            // Every step's number is one digit, taller than it is wide, so any of them sizes the circle.
            val number = measurer.measure("4", style).size
            val padding = NumberPadding.roundToPx() * 2
            StepPadding * 2 + circleSide(number.width + padding, number.height + padding).toDp()
        }
    }
}

/**
 * One step: its number in a circle, then what to do, then [more]. A screen reader reads the number
 * and the text as one; a button in [more] stays a stop of its own.
 */
@Composable
private fun DonateStep(number: Int, text: String, more: @Composable ColumnScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(StepPadding)) {
        Box(
            Modifier
                .clip(CircleShape)
                .background(Usugami.colors.lilac)
                .layout { measurable, constraints ->
                    val label = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    val side = circleSide(label.width, label.height)
                    layout(side, side) { label.place((side - label.width) / 2, (side - label.height) / 2) }
                },
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = Usugami.colors.onLilac,
                modifier = Modifier.padding(NumberPadding),
            )
        }
        Spacer(Modifier.width(StepPadding))
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            more()
        }
    }
}
