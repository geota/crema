@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package coffee.crema.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coffee.crema.R

/*
 * Crema typography — a 1:1 port of the type scale in tablet/m3-tokens.css.
 *
 * Three families (all Google Fonts — no proprietary face was supplied):
 *   • Newsreader      (serif)  → Display + Headline + Title-Large. Editorial,
 *                                slightly modulated; the "coffee menu" voice.
 *   • Hanken Grotesk  (sans)   → Title M/S, Body, Label — all UI chrome.
 *   • JetBrains Mono  (mono)   → telemetry readouts, doses, weights, time
 *                                codes. TABULAR figures everywhere so numbers
 *                                don't jitter as they update.
 *
 * Set up the GoogleFont provider in your module (see README), or drop the
 * static .ttf files into res/font and swap `FontFamily(Font(R.font.…))`.
 *
 * px in the CSS maps 1:1 to sp here (the mockup used a 1:1 px↔sp convention).
 */

// Brand families are BUNDLED as variable fonts (res/font/*.ttf) rather than
// pulled from the GMS downloadable-font provider — the provider isn't present on
// many emulators / non-GMS devices, which silently fell the whole type scale
// back to the system sans (no serif titles, no tabular mono). Each weight maps
// to the matching `wght` instance of the one variable file (minSdk 31 → variable
// fonts are universally supported).
private fun newsreader(w: Int) = Font(R.font.newsreader, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val Newsreader = FontFamily(newsreader(400), newsreader(500), newsreader(600))

private fun hanken(w: Int) = Font(R.font.hanken_grotesk, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val HankenGrotesk = FontFamily(hanken(400), hanken(500), hanken(600), hanken(700))

private fun jbMono(w: Int) = Font(R.font.jetbrains_mono, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val JetBrainsMono = FontFamily(jbMono(400), jbMono(500), jbMono(700))

/*
 * M3 type scale, Crema families. Display/Headline + Title-Large are serif;
 * everything else is Hanken. letterSpacing in px → em (px / fontSizePx).
 */
val CremaTypography = Typography(
    displayLarge = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Normal, fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.0044).em),
    displayMedium = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Normal, fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Normal, fontSize = 36.sp, lineHeight = 44.sp),

    headlineLarge = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Normal, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 32.sp),

    titleLarge = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp.value.em),
    titleSmall = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp.value.em),

    bodyLarge = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),

    labelLarge = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

/*
 * Telemetry / readout numerics — NOT part of the M3 Typography roles, so they
 * live as an extension object exposed via LocalCremaType (see CremaTheme.kt).
 * Always mono + tabular. Use these for the big weight readout, the brew timer,
 * channel values, dose steppers — anything numeric that updates live.
 *
 * Apply tabular figures with:
 *   style.merge(TextStyle(fontFeatureSettings = "tnum"))
 * or set fontFeatureSettings = "tnum" on the TextStyle directly.
 */
class CremaReadoutType(
    val readoutHero: TextStyle = mono(132, 132, -0.038f), // scale weight hero (signature number; tablet 132sp)
    val readoutXl: TextStyle = mono(96, 96, -0.026f), // huge numerals
    val readoutLg: TextStyle = mono(56, 56, -0.027f), // tare value, big channel
    val readoutMd: TextStyle = mono(36, 40, -0.022f), // channel values
    val readoutSm: TextStyle = mono(22, 28, -0.009f), // inline stats, list values
)

private fun mono(size: Int, line: Int, tracking: Float) = TextStyle(
    fontFamily = JetBrainsMono,
    fontWeight = FontWeight.Medium,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
    fontFeatureSettings = "tnum", // tabular numbers — non-negotiable for live values
)
