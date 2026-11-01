package com.kukreja.stickersearch.ui.kds.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.kukreja.stickersearch.R

// ---------- type ----------

@OptIn(ExperimentalTextApi::class)
private fun geist(weight: Int) = Font(
    R.font.geist,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Geist = FontFamily(geist(400), geist(500), geist(600), geist(700))

/** Type scale design tokens. */
@Immutable
data class KdsType(
    val titleLg: TextStyle,
    val title: TextStyle,
    val titleSm: TextStyle,
    val heading: TextStyle,
    val bodyLg: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val chip: TextStyle,
    val caption: TextStyle,
    val overline: TextStyle,
    val labelXs: TextStyle,
    val micro: TextStyle,
    val mono: TextStyle,
    val stat: TextStyle,
)

private fun style(size: Int, line: Int, weight: Int, tracking: Double = 0.0, tabular: Boolean = false) = TextStyle(
    fontFamily = Geist,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking.em,
    fontFeatureSettings = if (tabular) "tnum" else null,
)

val KdsTypography = KdsType(
    titleLg = style(24, 32, 600, -0.025),
    title = style(20, 28, 600, -0.025),
    titleSm = style(18, 28, 600),
    heading = style(16, 24, 600),
    bodyLg = style(15, 22, 500),
    body = style(14, 20, 400),
    bodyStrong = style(14, 20, 500),
    chip = style(13, 18, 500),
    caption = style(12, 16, 400),
    overline = style(12, 16, 500, 0.025),
    labelXs = style(11, 16, 500),
    micro = style(10, 18, 600, tabular = true),
    mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp),
    stat = style(24, 32, 600, -0.025, tabular = true),
)

// ---------- space, radius, size, motion ----------

object KdsSpace {
    val s0_5 = 2.dp; val s1 = 4.dp; val s1_5 = 6.dp; val s2 = 8.dp; val s2_5 = 10.dp
    val s3 = 12.dp; val s4 = 16.dp; val s5 = 20.dp; val s6 = 24.dp; val s12 = 48.dp
}

object KdsRadius {
    val xs = 4.dp; val sm = 6.dp; val md = 8.dp; val lg = 14.dp; val xl = 18.dp; val x2l = 24.dp
}

object KdsSize {
    val controlXl = 48.dp; val controlLg = 44.dp; val controlMd = 40.dp; val controlSm = 36.dp
    val controlXs = 32.dp; val control2xs = 28.dp; val rowMin = 56.dp
}

object KdsMotion {
    const val QUICK = 120
    const val STANDARD = 200
    const val EMPHASISED = 300
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Exit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Toast = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
}

// ---------- theme ----------

private val LocalKdsColors = staticCompositionLocalOf { KdsLightColors }
private val LocalKdsDark = staticCompositionLocalOf { false }

object Kds {
    val colors: KdsColors
        @Composable @ReadOnlyComposable get() = LocalKdsColors.current
    val type: KdsType get() = KdsTypography
    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalKdsDark.current
}

@Composable
fun KdsTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) KdsDarkColors else KdsLightColors
    // Mapped onto Material 3 so any stock component still matches the tokens.
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.primary, onPrimary = c.primaryForeground, secondary = c.secondary,
            onSecondary = c.secondaryForeground, background = c.background, onBackground = c.foreground,
            surface = c.background, onSurface = c.foreground, surfaceVariant = c.muted,
            onSurfaceVariant = c.mutedForeground, outline = c.border, error = c.destructive,
            onError = c.destructiveForeground, surfaceContainerLow = c.glassThick,
        )
    } else {
        lightColorScheme(
            primary = c.primary, onPrimary = c.primaryForeground, secondary = c.secondary,
            onSecondary = c.secondaryForeground, background = c.background, onBackground = c.foreground,
            surface = c.background, onSurface = c.foreground, surfaceVariant = c.muted,
            onSurfaceVariant = c.mutedForeground, outline = c.border, error = c.destructive,
            onError = c.destructiveForeground, surfaceContainerLow = c.glassThick,
        )
    }
    CompositionLocalProvider(LocalKdsColors provides c, LocalKdsDark provides dark) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
