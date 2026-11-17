package io.github.nath_goa.usugami.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTonalElevationEnabled
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import io.github.nath_goa.usugami.data.Appearance
import io.github.nath_goa.usugami.ui.kit.DarkUsugamiColors
import io.github.nath_goa.usugami.ui.kit.LightUsugamiColors
import io.github.nath_goa.usugami.ui.kit.LocalUsugamiColors
import io.github.nath_goa.usugami.ui.kit.LocalUsugamiType
import io.github.nath_goa.usugami.ui.kit.PressIndication
import io.github.nath_goa.usugami.ui.kit.UsugamiShapes
import io.github.nath_goa.usugami.ui.kit.usugamiType
import io.github.nath_goa.usugami.ui.kit.usugamiTypography

// `surface` is a sheet and `background` the ground, so an unwrapped Card or Surface is already a
// sheet. The container roles are slots: Low, Container and High are all the top sheet (bottom sheet,
// menu, dialog), Highest is the well.
private val LightColors = lightColorScheme(
    primary = Color(0xFF4A3F6B), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8DEF8), onPrimaryContainer = Color(0xFF2A2243),
    inversePrimary = Color(0xFFD5C8F2),
    secondary = Color(0xFF615A78), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDED3F3), onSecondaryContainer = Color(0xFF2F2749),
    tertiary = Color(0xFF7B4E7F), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3DDF4), onTertiaryContainer = Color(0xFF32143A),
    background = Color(0xFFF1ECFA), onBackground = Color(0xFF1B1924),
    surface = Color(0xFFFBFAFE), onSurface = Color(0xFF1B1924),
    surfaceVariant = Color(0xFFE4DEEF), onSurfaceVariant = Color(0xFF575268),
    surfaceTint = Color(0xFF4A3F6B),
    inverseSurface = Color(0xFF302C3D), inverseOnSurface = Color(0xFFF4EFFC),
    error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF777190), outlineVariant = Color(0xFFDAD4E6),
    scrim = Color(0xFF1B1924),
    surfaceBright = Color(0xFFFFFFFF), surfaceDim = Color(0xFFDDD6EA),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFE4DEEF),
    primaryFixed = Color(0xFFE8DEF8), primaryFixedDim = Color(0xFFD5C8F2),
    onPrimaryFixed = Color(0xFF2A2243), onPrimaryFixedVariant = Color(0xFF4A3F6B),
    secondaryFixed = Color(0xFFDED3F3), secondaryFixedDim = Color(0xFFC9C2DD),
    onSecondaryFixed = Color(0xFF2F2749), onSecondaryFixedVariant = Color(0xFF615A78),
    tertiaryFixed = Color(0xFFF3DDF4), tertiaryFixedDim = Color(0xFFE0BBE2),
    onTertiaryFixed = Color(0xFF32143A), onTertiaryFixedVariant = Color(0xFF7B4E7F),
)

// Near-black neutrals with a violet cast of at most 5 (blue ≥ red ≥ green, so none turns brown); lilac and ink purple swap roles.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFE8DEF8), onPrimary = Color(0xFF2A2243),
    primaryContainer = Color(0xFF4A3F6B), onPrimaryContainer = Color(0xFFEFE8FC),
    inversePrimary = Color(0xFF4A3F6B),
    secondary = Color(0xFFC9C2DD), onSecondary = Color(0xFF322C47),
    secondaryContainer = Color(0xFF322F40), onSecondaryContainer = Color(0xFFE6E1F2),
    tertiary = Color(0xFFE0BBE2), onTertiary = Color(0xFF432447),
    tertiaryContainer = Color(0xFF5B3C5F), onTertiaryContainer = Color(0xFFF9D8FA),
    background = Color(0xFF141318), onBackground = Color(0xFFECEAF3),
    surface = Color(0xFF232227), onSurface = Color(0xFFECEAF3),
    surfaceVariant = Color(0xFF2E2D31), onSurfaceVariant = Color(0xFFB9B5C8),
    surfaceTint = Color(0xFFECEAF3),
    inverseSurface = Color(0xFFECEAF3), inverseOnSurface = Color(0xFF1B1924),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF918DA3), outlineVariant = Color(0xFF37363A),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF39383E), surfaceDim = Color(0xFF0C0B0E),
    surfaceContainerLowest = Color(0xFF0C0B0E), surfaceContainerLow = Color(0xFF2E2D31),
    surfaceContainer = Color(0xFF2E2D31), surfaceContainerHigh = Color(0xFF2E2D31),
    surfaceContainerHighest = Color(0xFF0C0B0E),
    primaryFixed = Color(0xFFE8DEF8), primaryFixedDim = Color(0xFFD5C8F2),
    onPrimaryFixed = Color(0xFF2A2243), onPrimaryFixedVariant = Color(0xFF4A3F6B),
    secondaryFixed = Color(0xFFDED3F3), secondaryFixedDim = Color(0xFFC9C2DD),
    onSecondaryFixed = Color(0xFF2F2749), onSecondaryFixedVariant = Color(0xFF615A78),
    tertiaryFixed = Color(0xFFF3DDF4), tertiaryFixedDim = Color(0xFFE0BBE2),
    onTertiaryFixed = Color(0xFF32143A), onTertiaryFixedVariant = Color(0xFF7B4E7F),
)

/** Usugami's colors, light or dark as [appearance] says (see [io.github.nath_goa.usugami.data.Appearances.forTheme]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsugamiTheme(appearance: Appearance = Appearance.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }
    val devanagari = LocalConfiguration.current.locales[0].language == "hi"
    val typography = remember(devanagari) { usugamiTypography(devanagari) }
    val type = remember(devanagari) { usugamiType(devanagari) }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, shapes = UsugamiShapes, typography = typography) {
        // Inside MaterialTheme's content: MaterialTheme provides an indication of its own, which
        // would overwrite one given outside it.
        CompositionLocalProvider(
            LocalUsugamiColors provides if (dark) DarkUsugamiColors else LightUsugamiColors,
            LocalUsugamiType provides type,
            LocalIndication provides PressIndication.Row,
            LocalTonalElevationEnabled provides false,
            content = content,
        )
    }
}
