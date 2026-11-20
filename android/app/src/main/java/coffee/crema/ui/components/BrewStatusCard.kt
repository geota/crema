package coffee.crema.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.core.MaintenanceReadout
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.theme.JetBrainsMono

/**
 * A condition that lasts, shown on Brew at rest as the status card (brew
 * messages PRD §1): a machine fault, a refused shot start, or maintenance due.
 * The titles are the web shell's (`BrewDashboard` status snippet).
 */
sealed interface BrewStatus {
    val title: String
    val detail: String

    /** A firmware fault (`Event.MachineErrorChanged`). The firmware clears it,
     *  so there is no ✕. */
    data class MachineError(override val detail: String) : BrewStatus {
        override val title get() = "Machine error"
    }

    /** This device's last shot start was refused, and why. Dismissible. */
    data class StartBlocked(override val detail: String) : BrewStatus {
        override val title get() = "Can't start shot"
    }

    /** Maintenance counters past their interval that weren't dismissed this
     *  session ([categories]: "filter", "descale", "clean"). Dismissible; the
     *  body opens Water & maintenance. */
    data class MaintenanceDue(override val detail: String, val categories: Set<String>) : BrewStatus {
        override val title get() = "Maintenance due"
    }
}

/**
 * The status card to show, by the web's precedence: a machine error, then a
 * shot-start blocker, then maintenance due. Null when none applies.
 */
fun brewStatusOf(
    machineError: String?,
    shotStartError: String?,
    readout: MaintenanceReadout?,
    maintenanceDismissed: Set<String>,
): BrewStatus? {
    machineError?.let { return BrewStatus.MachineError(it) }
    shotStartError?.let { return BrewStatus.StartBlocked(startBlockerDetail(it)) }
    val due = dueMaintenance(readout) - maintenanceDismissed
    if (due.isEmpty()) return null
    // The web's wording and order: "Filter due, Descale due, Clean due".
    val detail = MAINTENANCE_ORDER.filter { it in due }.joinToString(", ") { "${it.replaceFirstChar(Char::uppercase)} due" }
    return BrewStatus.MaintenanceDue(detail, due)
}

/** The maintenance categories past their interval (none before a readout). */
fun dueMaintenance(readout: MaintenanceReadout?): Set<String> {
    if (readout == null) return emptySet()
    return buildSet {
        if (!readout.filterOk) add("filter")
        if (!readout.descaleOk) add("descale")
        if (!readout.cleanOk) add("clean")
    }
}

private val MAINTENANCE_ORDER = listOf("filter", "descale", "clean")

/**
 * A start refusal's reason as the card's detail line: the card's title already
 * says "Can't start shot", so a reason that opens with it drops that opening
 * ("Can’t start shot — no profile selected" → "No profile selected").
 */
fun startBlockerDetail(reason: String): String {
    val m = START_PREFIX.find(reason) ?: return reason
    val rest = reason.substring(m.range.last + 1).trim()
    return if (rest.isEmpty()) reason else rest.replaceFirstChar(Char::uppercase)
}

private val START_PREFIX = Regex("""^Can[’']t start (the )?shot\s*[—–:-]\s*""", RegexOption.IGNORE_CASE)

/**
 * The status card: the banner's warning or danger wash and border, an icon in
 * a tinted circle, a bold title, a mono detail line, an optional
 * "Water & maintenance →" link, and a ✕ when the condition is dismissible.
 * It fills its slot's width; the caller decides where it sits.
 */
@Composable
fun BrewStatusCard(
    status: BrewStatus,
    onOpenMaintenance: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val danger = status !is BrewStatus.MaintenanceDue
    // The theme's danger (error) and the Brew banners' amber, as web's
    // `.is-error` / `.is-warning` use --danger / --warning.
    val tone: Color = if (danger) MaterialTheme.colorScheme.error else CremaTheme.freshness.ok
    val shape = RoundedCornerShape(12.dp)
    val open = (status as? BrewStatus.MaintenanceDue)?.let { onOpenMaintenance }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tone.copy(alpha = 0.12f))
            .border(1.dp, tone.copy(alpha = 0.5f), shape)
            .then(if (open != null) Modifier.clickable(onClick = open, onClickLabel = "Open Water & maintenance") else Modifier)
            // role=alert on web: TalkBack reads it out when it appears.
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 12.dp)
            .testTag("brew-status-card"),
        // The mockup's anatomy: icon, title and ✕ on the top line; the
        // detail and link under the title.
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(tone.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            PhIcon("warning", sizeDp = 15, tint = tone, contentDescription = null)
        }
        Column(
            Modifier.weight(1f).padding(top = 4.dp, end = if (status is BrewStatus.MachineError) 10.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                status.title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                status.detail,
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (open != null) {
                Text(
                    "Water & maintenance →",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (status !is BrewStatus.MachineError) {
            // 40dp target; its own clickable consumes the tap, so it doesn't
            // also open Settings like the card body does.
            Box(
                Modifier
                    .size(40.dp)
                    .offset(y = (-6).dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Dismiss", onClick = onDismiss)
                    .testTag("brew-status-dismiss"),
                contentAlignment = Alignment.Center,
            ) {
                PhIcon("x", sizeDp = 14, tint = MaterialTheme.colorScheme.onSurfaceVariant, contentDescription = "Dismiss")
            }
        }
    }
}
