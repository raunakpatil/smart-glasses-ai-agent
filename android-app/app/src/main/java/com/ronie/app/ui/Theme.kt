package com.ronie.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
 * Ronie's design language: a black page, near-black cards with a soft coloured glow bleeding in from one side,
 * big light-weight headlines, dot-matrix numbers, round icon buttons, and pill buttons that end in a white circle.
 * One glow per card (magenta, violet, coral, mint), and a lime tick for "this one / now". The app is always dark,
 * like the glasses' own world; every colour on screen comes from here.
 */

/** Status colours Material doesn't have: ok / in progress / problem, each with a container and text on it. */
@Immutable
data class StatusColors(
    val success: Color, val successContainer: Color, val onSuccessContainer: Color,
    val warning: Color, val warningContainer: Color, val onWarningContainer: Color,
    val danger: Color, val dangerContainer: Color, val onDangerContainer: Color,
)

/** The page, card and text colours, plus the live accent. */
@Immutable
data class InkColors(
    val page: Color, val card: Color, val raised: Color, val line: Color,
    val onCard: Color, val onCard2: Color, val onCard3: Color,
    val live: Color, val tick: Color,
)

/** A card's glow: the bright core and the deep edge it fades through before reaching the card colour. */
@Immutable
data class Glow(val core: Color, val edge: Color)

object Glows {
    val Magenta = Glow(Color(0xFFF062C8), Color(0xFF6E1A5C))
    val Violet = Glow(Color(0xFF6B6BFF), Color(0xFF231C6E))
    val Coral = Glow(Color(0xFFFF6A3D), Color(0xFF6E2414))
    val Mint = Glow(Color(0xFF8FD8BE), Color(0xFF1D4F45))
    val Rose = Glow(Color(0xFFE88AA8), Color(0xFF5A2236))
    val Ice = Glow(Color(0xFF9CC9FF), Color(0xFF1C3756))
    val all = listOf(Magenta, Violet, Coral, Mint, Rose, Ice)
}

private val Ink = InkColors(
    page = Color(0xFF000000), card = Color(0xFF111113), raised = Color(0xFF1C1C1F), line = Color(0xFF26262A),
    onCard = Color(0xFFF5F5F7), onCard2 = Color(0xFFA1A1A8), onCard3 = Color(0xFF6B6B72),
    live = Color(0xFFE879F9), tick = Color(0xFFD9F55B),
)

private val Status = StatusColors(
    success = Color(0xFF6EE0A8), successContainer = Color(0xFF10291D), onSuccessContainer = Color(0xFFC4F2DA),
    warning = Color(0xFFF5C25B), warningContainer = Color(0xFF2E2410), onWarningContainer = Color(0xFFF8E1AE),
    danger = Color(0xFFFF7A5C), dangerContainer = Color(0xFF34160F), onDangerContainer = Color(0xFFFFCCBF),
)

private val Colors = darkColorScheme(
    primary = Color(0xFFF5F5F7), onPrimary = Color(0xFF0A0A0B),           // white pills with black text
    primaryContainer = Ink.raised, onPrimaryContainer = Ink.onCard,
    secondary = Glows.Violet.core, onSecondary = Color(0xFF0A0A0B),
    secondaryContainer = Ink.raised, onSecondaryContainer = Ink.onCard,
    tertiary = Ink.live, onTertiary = Color(0xFF0A0A0B),
    background = Ink.page, onBackground = Ink.onCard,
    surface = Ink.card, onSurface = Ink.onCard,
    surfaceVariant = Ink.raised, onSurfaceVariant = Ink.onCard2,
    surfaceContainer = Ink.card, surfaceContainerHigh = Ink.raised, surfaceContainerHighest = Ink.raised,
    surfaceContainerLow = Ink.card, surfaceContainerLowest = Ink.page,
    outline = Ink.line, outlineVariant = Color(0xFF1E1E21),
    error = Status.danger, onError = Color(0xFF0A0A0B),
)

/** Inter Tight for text, JetBrains Mono for small labels (both OFL, bundled). */
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

/** Big light headlines with tight tracking, calm 15–16 sp body, small mono uppercase labels. */
private val RonieTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = Sans, fontWeight = FontWeight.Light, letterSpacing = (-2.5).sp),
        displayMedium = displayMedium.copy(fontFamily = Sans, fontWeight = FontWeight.Light, letterSpacing = (-2).sp),
        displaySmall = displaySmall.copy(fontFamily = Sans, fontWeight = FontWeight.Light, letterSpacing = (-1.4).sp),
        headlineLarge = headlineLarge.copy(fontFamily = Sans, fontWeight = FontWeight.Light, letterSpacing = (-1.2).sp),
        headlineMedium = headlineMedium.copy(fontFamily = Sans, fontWeight = FontWeight.Light, letterSpacing = (-0.9).sp),
        headlineSmall = headlineSmall.copy(fontFamily = Sans, fontWeight = FontWeight.Light, letterSpacing = (-0.6).sp),
        titleLarge = titleLarge.copy(fontFamily = Sans, fontWeight = FontWeight.Normal, letterSpacing = (-0.4).sp),
        titleMedium = titleMedium.copy(fontFamily = Sans, fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp),
        titleSmall = titleSmall.copy(fontFamily = Sans, fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(fontFamily = Sans, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = bodyMedium.copy(fontFamily = Sans, fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall = bodySmall.copy(fontFamily = Sans, fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = labelLarge.copy(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp),
        labelMedium = labelMedium.copy(fontFamily = Sans),
        labelSmall = labelSmall.copy(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.sp),
    )
}

@Composable
fun RonieTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = RonieTypography, content = content)
}

/** Theme extras: `Ronie.status.success`, `Ronie.ink.card` … */
object Ronie {
    val status: StatusColors get() = Status
    val ink: InkColors get() = Ink
}

/** A small UPPERCASE monospace label with an optional icon. */
@Composable
fun MonoLabel(text: String, icon: ImageVector? = null, color: Color = Ink.onCard2, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
    }
}
