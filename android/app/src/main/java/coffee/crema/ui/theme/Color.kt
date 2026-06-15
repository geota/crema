package coffee.crema.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Crema color tokens — a 1:1 port of tablet/m3-tokens.css.
 *
 * The brand is espresso (warm near-black surfaces pulled toward brown, never
 * blue) + a single copper accent. Surfaces map onto M3's tonal "surface
 * container" ramp; the dark scheme is the default because the machine app is
 * dark-skinned. Outlines are WARM and translucent (ink/paper at low alpha),
 * not the cool grey M3 ships by default — keep the alpha values exact.
 *
 * Pure #000 / #FFF are deliberately avoided everywhere.
 */

// ── Copper accent ramp (brand) ──────────────────────────────────────────────
val Copper300 = Color(0xFFE8A876)
val Copper500 = Color(0xFFC7763B) // brand primary on dark
val Copper600 = Color(0xFFA55F2A) // brand primary on light (reads on paper)
val Copper700 = Color(0xFF82481C)

// ── Telemetry channels (brand-extended; identical in both schemes) ──────────
// These NEVER swap. A user who learns "blue = flow" sees it everywhere.
// NB: pressure + weight are intentionally the sage/amber pair (the shell
// swapped them from the original brief): pressure = sage, weight = amber.
object TelemetryPalette {
    val Pressure = Color(0xFF6B8C5F)   // sage
    val Pressure2 = Color(0xFF88A37A)  // resistance — lighter sage
    val Flow = Color(0xFF4A6FA5)       // blue
    val Flow2 = Color(0xFF6E8FBD)      // dispensed water — lighter blue
    val Temp = Color(0xFFC44E3F)       // coral
    val Temp2 = Color(0xFFD8786A)      // mix temp — lighter coral
    val Weight = Color(0xFFD89030)     // amber
    val Weight2 = Color(0xFFE5A958)    // weight-flow — lighter amber
    val Success = Color(0xFF6B8C5F)    // dark scheme
    val SuccessLight = Color(0xFF5C8A4C)
    // Service-mode tints (Steam / Hot water / Flush)
    val ModeSteam = Color(0xFFC44E3F)
    val ModeWater = Color(0xFF4A6FA5)
    val ModeFlush = Color(0xFF6B8C5F)
}

/**
 * Bean-freshness dot hues, keyed by the core's roast-freshness verdict. Was
 * inlined in `Format.freshnessColor`; lifted here so the palette lives beside
 * [TelemetryPalette] (issue 46) and is themable via [CremaFreshnessColors].
 */
object FreshnessPalette {
    val Frozen = Color(0xFF7FB0E0)   // icy blue — frozen storage
    val Best = Color(0xFF5FB87A)     // green — in the rest window
    val Ok = Color(0xFFDBA764)       // amber — drinkable, past peak
    val Bad = Color(0xFFC58B8B)      // muted red — stale / too fresh
    val Unknown = Color(0xFF8A8175)  // neutral — no roast date
}

// ── Dark scheme (default) ───────────────────────────────────────────────────
val CremaDarkColors = darkColorScheme(
    primary = Copper500,
    onPrimary = Color(0xFF1F140B),
    primaryContainer = Copper700,
    onPrimaryContainer = Color(0xFFFFDDBE),

    secondary = Color(0xFFDCC5AA),
    onSecondary = Color(0xFF3D2E1E),
    secondaryContainer = Color(0xFF43301F), // selected chips / rail pill
    onSecondaryContainer = Color(0xFFF2D2B0),

    tertiary = Color(0xFFB9CDA0),
    onTertiary = Color(0xFF243410),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF15110D),
    onBackground = Color(0xFFF4EDE0),
    surface = Color(0xFF15110D),
    onSurface = Color(0xFFF4EDE0),
    onSurfaceVariant = Color(0xFFC3B299),
    surfaceDim = Color(0xFF15110D),
    surfaceBright = Color(0xFF3C2F23),
    surfaceContainerLowest = Color(0xFF120D09),
    surfaceContainerLow = Color(0xFF1A140F),
    surfaceContainer = Color(0xFF1F1813),       // resting card
    surfaceContainerHigh = Color(0xFF281F18),   // hover / raised
    surfaceContainerHighest = Color(0xFF322820),

    // Warm, translucent outlines — alpha is significant (0x29≈16%, 0x14≈8%).
    outline = Color(0x29F4EDE0),
    outlineVariant = Color(0x14F4EDE0),
    scrim = Color(0x99000000),

    inverseSurface = Color(0xFFF4EDE0),
    inverseOnSurface = Color(0xFF1F1812),
    inversePrimary = Copper600,
)

// ── Light scheme (Crema paper + ink) ────────────────────────────────────────
val CremaLightColors = lightColorScheme(
    primary = Copper600,
    onPrimary = Color(0xFFFFFDF8),
    primaryContainer = Color(0xFFFFDDBE),
    onPrimaryContainer = Color(0xFF5A3210),

    secondary = Color(0xFF6E5C46),
    onSecondary = Color(0xFFFFFDF8),
    secondaryContainer = Color(0xFFF3E3CC),
    onSecondaryContainer = Color(0xFF5A4326),

    tertiary = Color(0xFF4F6A3F),
    onTertiary = Color(0xFFFFFDF8),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFFAF7F1), // paper-50
    onBackground = Color(0xFF1F1812),
    surface = Color(0xFFFAF7F1),
    onSurface = Color(0xFF1F1812),
    onSurfaceVariant = Color(0xFF6E5E49),
    surfaceDim = Color(0xFFEBE2D2),
    surfaceBright = Color(0xFFFFFDF8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBF6EE),
    surfaceContainer = Color(0xFFF4EDE0),       // paper-100
    surfaceContainerHigh = Color(0xFFECE2D1),   // paper-200
    surfaceContainerHighest = Color(0xFFE5D9C3), // paper-300 (crema foam)

    outline = Color(0x3D1F1812),     // ink @ 24%
    outlineVariant = Color(0x1F1F1812), // ink @ 12%
    scrim = Color(0x66151107),       // espresso @ 40%

    inverseSurface = Color(0xFF322820),
    inverseOnSurface = Color(0xFFF4EDE0),
    inversePrimary = Copper300,
)
