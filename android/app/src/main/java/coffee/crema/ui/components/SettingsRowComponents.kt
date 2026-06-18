package coffee.crema.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.ui.theme.JetBrainsMono

// ════════════════════════════════════════════════════════════════════════════
// SHARED SETTINGS ROWS  (one set for both shells — folds the former phone
// PRow/PPill/PSelect/PMono and tablet SetRow/SetPill/SetSelect/MonoReadout,
// issue 26). The only phone↔tablet difference is layout density, carried by the
// LocalSettingsRowDense composition-local so the ~150 call sites stay a plain
// rename rather than a per-site flag.
// ════════════════════════════════════════════════════════════════════════════

/**
 * Whether settings rows render in the phone's dense layout (tighter paddings,
 * smaller select). The phone settings root provides `true`; the tablet leaves
 * the default `false` (roomier). One [CremaSettingsRow] / [CremaSettingsSelect]
 * then serves both shells without threading a flag through every call site.
 */
val LocalSettingsRowDense = staticCompositionLocalOf { false }

/**
 * One settings row for both shells: title + optional [sub] + a status pill on
 * the left, a [trailing] control, and a bottom hairline unless [last].
 * [notImplemented] shows a neutral "Soon" pill and dims the control;
 * [needsConnection] shows a copper "Connect DE1" pill. Paddings/gap follow
 * [LocalSettingsRowDense]. Set [dot] for a 0-sentinel setting: a leading enable
 * dot ([dotOn]/[onDot]) toggles the feature and greys the [trailing] control
 * when off, instead of making the user dial the value down to 0.
 */
@Composable
fun CremaSettingsRow(
    title: String,
    sub: String? = null,
    last: Boolean = false,
    notImplemented: Boolean = false,
    needsConnection: Boolean = false,
    stacked: Boolean = false,
    dot: Boolean = false,
    dotOn: Boolean = false,
    onDot: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val dense = LocalSettingsRowDense.current
    // FlowRow so a too-narrow row drops the pill to its own line WHOLE
    // (a plain Row squeezed it into letter-per-line wrapping on the phone);
    // on the roomy tablet the pill stays inline, matching the old SetRow.
    val titleBlock: @Composable () -> Unit = {
        FlowRow(
            verticalArrangement = Arrangement.Center,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Optional leading enable dot — flips a 0-sentinel setting on/off
            // (the trailing control greys out below) instead of forcing the user
            // to dial the value to 0. Mirrors the PWA's StStepper dot.
            if (dot) CremaDotToggle(dotOn, { onDot?.invoke() })
            if (title.isNotEmpty()) Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (notImplemented) CremaSettingsPill("Soon")
            else if (needsConnection) CremaSettingsPill("Connect DE1", copper = true)
        }
        if (sub != null) Text(
            sub,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    val hPad = if (dense) 16.dp else 20.dp
    val vPad = if (dense) 13.dp else 16.dp
    if (stacked) {
        // Title above a full-width control — for controls too wide to sit beside
        // the title (e.g. the 3-way Water source pill, which otherwise squeezes
        // the title into wrapping on the narrow phone).
        Column(
            Modifier.fillMaxWidth().padding(horizontal = hPad, vertical = vPad),
            verticalArrangement = Arrangement.spacedBy(if (dense) 10.dp else 12.dp),
        ) {
            Column { titleBlock() }
            Box(Modifier.fillMaxWidth().alpha(if (notImplemented) 0.5f else if (dot && !dotOn) 0.4f else 1f)) { trailing() }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = hPad, vertical = vPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (dense) 12.dp else 16.dp),
        ) {
            Column(Modifier.weight(1f)) { titleBlock() }
            Box(Modifier.alpha(if (notImplemented) 0.5f else if (dot && !dotOn) 0.4f else 1f)) { trailing() }
        }
    }
    if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * Status pill on a settings row — neutral "Soon" by default, copper "Connect
 * DE1" when [copper]. Folds the byte-identical former `PPill` / `SetPill`.
 */
@Composable
fun CremaSettingsPill(text: String, copper: Boolean = false) {
    val fg = if (copper) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val bg = if (copper) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val ring = if (copper) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.5.sp),
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, ring, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * Flat select pill (value + caret) that opens a menu (static today). Folds the
 * former phone `PSelect` + tablet `SetSelect`; sizing follows [LocalSettingsRowDense].
 */
@Composable
fun CremaSettingsSelect(value: String, onClick: () -> Unit = {}) {
    val dense = LocalSettingsRowDense.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.height(if (dense) 38.dp else 40.dp),
    ) {
        Row(
            Modifier.padding(horizontal = if (dense) 12.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            PhIcon("caret-down", sizeDp = if (dense) 14 else 16, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Tiny mono readout (diagnostics / versions / values). Folds the former phone
 * `PMono` + tablet `MonoReadout`: [color] wins, else [strong] picks onSurface
 * over the muted onSurfaceVariant default.
 */
@Composable
fun CremaMonoReadout(text: String, strong: Boolean = false, color: Color? = null) {
    Text(
        text,
        style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, fontFeatureSettings = "tnum"),
        color = color ?: if (strong) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
