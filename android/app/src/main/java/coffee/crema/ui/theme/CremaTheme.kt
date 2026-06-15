package coffee.crema.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * CremaTheme — the single entry point. Wraps MaterialTheme with the Crema
 * color scheme + typography + shapes, AND provides the brand-extended tokens
 * that don't fit M3's slots (telemetry colors, the 8dp spacing scale, the mono
 * readout text styles, and the standard motion curve) via CompositionLocals.
 *
 * Usage:
 *   CremaTheme { AppNavHost() }
 *
 * Read brand tokens anywhere:
 *   CremaTheme.telemetry.flow
 *   CremaTheme.spacing.s4
 *   CremaTheme.readout.readoutXl
 */

// ── Spacing (4dp base grid; tokens at 4/8/12/16/24/32/48/64) ────────────────
data class CremaSpacing(
    val s1: Dp = 4.dp,
    val s2: Dp = 8.dp,
    val s3: Dp = 12.dp,
    val s4: Dp = 16.dp,
    val s5: Dp = 24.dp,
    val s6: Dp = 32.dp,
    val s7: Dp = 48.dp,
    val s8: Dp = 64.dp,
    // Edge insets: 16dp phone · 24dp tablet · 32dp desktop. Tablet default:
    val edge: Dp = 24.dp,
)

// ── Telemetry + brand colors as a local ─────────────────────────────────────
data class CremaTelemetryColors(
    val pressure: Color = TelemetryPalette.Pressure,
    val pressure2: Color = TelemetryPalette.Pressure2,
    val flow: Color = TelemetryPalette.Flow,
    val flow2: Color = TelemetryPalette.Flow2,
    val temp: Color = TelemetryPalette.Temp,
    val temp2: Color = TelemetryPalette.Temp2,
    val weight: Color = TelemetryPalette.Weight,
    val weight2: Color = TelemetryPalette.Weight2,
    val success: Color = TelemetryPalette.Success,
    val modeSteam: Color = TelemetryPalette.ModeSteam,
    val modeWater: Color = TelemetryPalette.ModeWater,
    val modeFlush: Color = TelemetryPalette.ModeFlush,
)

/**
 * Bean-freshness dot hues as a theme local (issue 46), mirroring
 * [CremaTelemetryColors]. Keyed by the core's roast-freshness verdict; the
 * mapping lives in `Format.freshnessColor`, which reads this off the theme.
 */
data class CremaFreshnessColors(
    val frozen: Color = FreshnessPalette.Frozen,
    val best: Color = FreshnessPalette.Best,
    val ok: Color = FreshnessPalette.Ok,
    val bad: Color = FreshnessPalette.Bad,
    val unknown: Color = FreshnessPalette.Unknown,
)

// ── Motion (single calm ease-out; no springs / bounces / parallax) ──────────
object CremaMotion {
    val standardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val durMicro = 120     // hover / press
    const val durToggle = 200    // toggles, chip selection, tab change
    const val durPanel = 320     // panel open / sheet / modal
    const val durChart = 500     // chart interpolation between steps
    fun <T> tween(durationMs: Int = durToggle) = tween<T>(durationMs, easing = standardEasing)
}

val LocalCremaSpacing = staticCompositionLocalOf { CremaSpacing() }
val LocalCremaTelemetry = staticCompositionLocalOf { CremaTelemetryColors() }
val LocalCremaFreshness = staticCompositionLocalOf { CremaFreshnessColors() }
val LocalCremaReadout = staticCompositionLocalOf { CremaReadoutType() }

@Composable
fun CremaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Crema defaults to DARK regardless of system — the machine app is dark-
    // skinned. Pass darkTheme=false (or wire to Settings → Display) to opt in
    // to the paper scheme.
    forceDark: Boolean = true,
    content: @Composable () -> Unit,
) {
    val useDark = if (forceDark) true else darkTheme
    val colors = if (useDark) CremaDarkColors else CremaLightColors
    CompositionLocalProvider(
        LocalCremaSpacing provides CremaSpacing(),
        LocalCremaTelemetry provides CremaTelemetryColors(),
        LocalCremaFreshness provides CremaFreshnessColors(),
        LocalCremaReadout provides CremaReadoutType(),
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = CremaTypography,
            shapes = CremaShapes,
            content = content,
        )
    }
}

// Convenience accessors — `CremaTheme.telemetry`, `CremaTheme.spacing`, etc.
object CremaTheme {
    val spacing: CremaSpacing
        @Composable get() = LocalCremaSpacing.current
    val telemetry: CremaTelemetryColors
        @Composable get() = LocalCremaTelemetry.current
    val freshness: CremaFreshnessColors
        @Composable get() = LocalCremaFreshness.current
    val readout: CremaReadoutType
        @Composable get() = LocalCremaReadout.current
}
