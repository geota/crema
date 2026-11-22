package coffee.crema.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.beans.matchGrinders
import coffee.crema.core.GrinderMatchKind
import coffee.crema.core.Grinder
import coffee.crema.ui.GrinderPick
import coffee.crema.ui.GrinderPickRow
import coffee.crema.ui.UnsavedGrind

/*
 * The grinder pieces Brew, Devices and the bag editor share (#488): the
 * unsaved-grind marker, the grinder picker rows, and the type-or-pick box
 * ("Add a grinder: type or pick"), which matches the user's grinders first,
 * then the well-known catalogue (typos and missing brands don't matter: the
 * core's matcher), and always ends with "+ Create “…”".
 */

/**
 * "● Bag 4.2": Brew's grind differs from the bag's saved grind for this
 * grinder ("Bag —" when nothing is saved). A long press explains it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UnsavedGrindMarker(marker: UnsavedGrind, explanation: String?, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .combinedClickable(onClick = { open = explanation != null }, onLongClick = { open = explanation != null })
                .semantics { contentDescription = "Unsaved grind: the bag has ${marker.saved ?: "nothing"} saved" }
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            Text(
                marker.label,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = coffee.crema.ui.theme.JetBrainsMono, fontSize = 11.sp),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
        CremaAnchoredPopup(expanded = open && explanation != null, onDismiss = { open = false }) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(explanation.orEmpty(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

/** The copper dot on Save: Save includes the grind. */
@Composable
fun SaveGrindDot(modifier: Modifier = Modifier) {
    Box(modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).semantics { contentDescription = "includes the grind" })
}

/** One grinder in a picker: name (+ ★ for the bag's grinder), its meta line, a check when active. */
@Composable
fun GrinderPickerRow(row: GrinderPickRow, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        row.grinder.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.starred) PhIcon("star-fill", sizeDp = 13, tint = MaterialTheme.colorScheme.primary, contentDescription = "this bag's grinder")
                }
                Text(row.meta, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (active) PhIcon("check", sizeDp = 16, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * The type-or-pick box. [grinders] are all the records (the matcher lists
 * owned, then catalogue, then archived); [excludeIds] are left out (the
 * grinders already on a bag). Enter takes the top row; the last row is
 * always "+ Create “text”", the top one when nothing matches.
 */
@Composable
fun GrinderAddBox(
    grinders: List<Grinder>,
    onPick: (GrinderPick) -> Unit,
    modifier: Modifier = Modifier,
    excludeIds: List<String> = emptyList(),
    placeholder: String = "Add a grinder: type or pick",
    /** Show the list before anything is typed (Devices' Add, the Brew picker). */
    listWhenBlank: Boolean = false,
    /** Offer "+ Create “…”" (not for "Change preset", which picks a catalogue entry). */
    allowCreate: Boolean = true,
) {
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val matches = remember(query, grinders, excludeIds) {
        if (query.isBlank() && !listWhenBlank) emptyList() else matchGrinders(query, grinders, excludeIds).take(8)
    }
    val typed = query.trim()
    val picks: List<Pair<GrinderPick, Pair<String, String>>> = buildList {
        matches.forEach { m ->
            val pick = when (m.kind) {
                GrinderMatchKind.Owned -> GrinderPick.Owned(m.id)
                GrinderMatchKind.Catalogue -> GrinderPick.Catalogue(m.id, m.name)
                GrinderMatchKind.Archived -> GrinderPick.Archived(m.id)
            }
            val sub = when (m.kind) {
                GrinderMatchKind.Owned -> grinders.firstOrNull { it.id == m.id }?.let { coffee.crema.ui.scaleRangeText(it.scale) + if (excludeIds.isNotEmpty()) " · not on this bag yet" else "" }.orEmpty()
                GrinderMatchKind.Catalogue -> coffee.crema.ui.catalogueOfferLine(m.id)
                GrinderMatchKind.Archived -> "Removed earlier · brings it back with its saved grinds"
            }
            add(pick to (m.name to sub))
        }
        if (allowCreate && typed.isNotEmpty()) add(GrinderPick.Create(typed) to ("+ Create “$typed”" to "A new grinder with the default scale; adjust it on Devices"))
    }
    fun take(pick: GrinderPick) {
        onPick(pick)
        query = ""
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PhIcon("magnifying-glass", sizeDp = 16, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { picks.firstOrNull()?.let { take(it.first) } }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder }
                        .onFocusChanged { focused = it.isFocused },
                )
            }
        }
        if (picks.isNotEmpty() && (focused || typed.isNotEmpty() || listWhenBlank)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                var lastKind: String? = null
                picks.forEachIndexed { i, (pick, text) ->
                    val kind = when (pick) {
                        is GrinderPick.Owned -> "YOUR GRINDERS"
                        is GrinderPick.Catalogue -> "WELL-KNOWN GRINDERS"
                        is GrinderPick.Archived -> "ARCHIVED"
                        is GrinderPick.Create -> null
                    }
                    if (kind != null && kind != lastKind) {
                        Text(kind, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp))
                    }
                    if (pick is GrinderPick.Create && matches.isEmpty()) {
                        Text("No match in your grinders or the well-known list.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
                    }
                    lastKind = kind
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .background(if (i == 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable { take(pick) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text.first,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (pick is GrinderPick.Create) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (text.second.isNotEmpty()) Text(text.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (i == 0) Text("↵", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
