package com.ronie.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ronie.app.R

/*
 * Ronie's design language: a soft grey page with white panels and near-black "ink"
 * cards (16 dp corners), light-weight tight headlines, small UPPERCASE monospace labels, a dark pill for the selected
 * tab, and one bright accent (magenta) for what's live, with coral, blue and green from the site for the rest.
 * Every colour on screen comes from here, so both themes keep readable contrast.
 */

/** Status colours Material doesn't have: ok / in progress / problem, each with a container and text on it. */
@Immutable
data class StatusColors(
    val success: Color, val successContainer: Color, val onSuccessContainer: Color,
    val warning: Color, val warningContainer: Color, val onWarningContainer: Color,
    val danger: Color, val dangerContainer: Color, val onDangerContainer: Color,
)

/** The site's dark cards, used for the hero card in both themes, and the live accent. */
@Immutable
data class InkColors(val card: Color, val line: Color, val onCard: Color, val onCard2: Color, val live: Color)

private val Ink = InkColors(
    card = Color(0xFF151515), line = Color(0xFF2A2A2A), onCard = Color(0xFFF3F3F3), onCard2 = Color(0xFF9A9A9A),
    live = Color(0xFFE879F9),
)

private val DarkStatus = StatusColors(
    success = Color(0xFF4CC48F), successContainer = Color(0xFF12301F), onSuccessContainer = Color(0xFFBDEBD3),
    warning = Color(0xFFF2B44D), warningContainer = Color(0xFF33270F), onWarningContainer = Color(0xFFF7DCA8),
    danger = Color(0xFFFF7A5C), dangerContainer = Color(0xFF3A1A12), onDangerContainer = Color(0xFFFFC9BC),
)

private val LightStatus = StatusColors(
    success = Color(0xFF2F9E6B), successContainer = Color(0xFFDCF2E7), onSuccessContainer = Color(0xFF14462F),
    warning = Color(0xFFB7791F), warningContainer = Color(0xFFFBEBD0), onWarningContainer = Color(0xFF5A3A0B),
    danger = Color(0xFFC2412A), dangerContainer = Color(0xFFFBE0DA), onDangerContainer = Color(0xFF5E1A0E),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF111111), onPrimary = Color(0xFFFFFFFF),          // ink buttons, like the site's dark pill
    primaryContainer = Color(0xFFE6E6E6), onPrimaryContainer = Color(0xFF111111),
    secondary = Color(0xFF2B3FA8), onSecondary = Color(0xFFFFFFFF),      // the site's blue
    secondaryContainer = Color(0xFFE3E7FA), onSecondaryContainer = Color(0xFF1B2A78),
    tertiary = Color(0xFFE8553B), onTertiary = Color(0xFFFFFFFF),        // the site's coral
    background = Color(0xFFF2F2F2), onBackground = Color(0xFF111111),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF2F2F2), onSurfaceVariant = Color(0xFF5E5E5E),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFF7F7F7),
    outline = Color(0xFFD8DCE0), outlineVariant = Color(0xFFE6E6E6),
    error = Color(0xFFC2412A), onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF3F3F3), onPrimary = Color(0xFF111111),
    primaryContainer = Color(0xFF2A2A2A), onPrimaryContainer = Color(0xFFF3F3F3),
    secondary = Color(0xFF9AA8FF), onSecondary = Color(0xFF101A55),
    secondaryContainer = Color(0xFF1E2550), onSecondaryContainer = Color(0xFFDCE1FF),
    tertiary = Color(0xFFFF7A5C), onTertiary = Color(0xFF3A1208),
    background = Color(0xFF0C0D0E), onBackground = Color(0xFFF3F3F3),
    surface = Color(0xFF151515), onSurface = Color(0xFFF3F3F3),
    surfaceVariant = Color(0xFF1C1C1C), onSurfaceVariant = Color(0xFF9A9A9A),
    surfaceContainer = Color(0xFF151515), surfaceContainerHigh = Color(0xFF1C1C1C),
    outline = Color(0xFF2A2A2A), outlineVariant = Color(0xFF222222),
    error = Color(0xFFFF7A5C), onError = Color(0xFF3A1208),
)

/** The site's fonts (OFL, from Google Fonts): Inter Tight for text, JetBrains Mono for labels. */
val Sans = FontFamily(
    Font(R.font.inter_tight_300, FontWeight.Light),
    Font(R.font.inter_tight_400, FontWeight.Normal),
    Font(R.font.inter_tight_500, FontWeight.Medium),
    Font(R.font.inter_tight_600, FontWeight.SemiBold),
    Font(R.font.inter_tight_600, FontWeight.Bold),
)
val Mono = FontFamily(
    Font(R.font.jetbrains_mono_400, FontWeight.Normal),
    Font(R.font.jetbrains_mono_500, FontWeight.Medium),
    Font(R.font.jetbrains_mono_500, FontWeight.Bold),
)

/** Light, tight headlines (the site's 300-weight "Hello Stranger"), 15–16 sp body, small mono uppercase labels. */
private val RonieTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = Sans),
        displayMedium = displayMedium.copy(fontFamily = Sans),
        displaySmall = displaySmall.copy(fontFamily = Sans, fontWeight = FontWeight.Light, letterSpacing = (-1).sp),
        headlineLarge = headlineLarge.copy(fontFamily = Sans, fontWeight = FontWeight.Light, letterSpacing = (-1).sp),
        headlineMedium = headlineMedium.copy(fontFamily = Sans, fontWeight = FontWeight.Light, letterSpacing = (-0.8).sp),
        headlineSmall = headlineSmall.copy(fontFamily = Sans, fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp),
        titleLarge = titleLarge.copy(fontFamily = Sans, fontWeight = FontWeight.Normal, letterSpacing = (-0.3).sp),
        titleMedium = titleMedium.copy(fontFamily = Sans, fontWeight = FontWeight.Medium),
        titleSmall = titleSmall.copy(fontFamily = Sans, fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(fontFamily = Sans, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = bodyMedium.copy(fontFamily = Sans, fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall = bodySmall.copy(fontFamily = Sans),
        labelLarge = labelLarge.copy(fontFamily = Sans, fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.copy(fontFamily = Sans),
        labelSmall = labelSmall.copy(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 0.9.sp),
    )
}

private val LocalStatus = staticCompositionLocalOf { LightStatus }

@Composable
fun RonieTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatus provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = RonieTypography, content = content)
    }
}

/** Theme extras: `Ronie.status.success`, `Ronie.ink.card` … */
object Ronie {
    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatus.current
    val ink: InkColors get() = Ink
}

/** A small UPPERCASE monospace label with an optional icon, like the site's "◷ TIME SPENT IN AI / ML". */
@Composable
fun MonoLabel(text: String, icon: ImageVector? = null, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
    }
}
