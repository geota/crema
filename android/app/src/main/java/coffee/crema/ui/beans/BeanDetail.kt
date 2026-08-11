package coffee.crema.ui.beans

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.beans.beanDaysOffRoast
import coffee.crema.beans.daysOffRoast
import coffee.crema.beans.isFrozen
import coffee.crema.beans.roastBand5
import coffee.crema.core.Bean
import coffee.crema.history.StoredShot
import coffee.crema.history.grindLabel
import coffee.crema.ui.components.BeanPhotoBox
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaStarRating
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.components.RoasterMarkAvatar
import coffee.crema.ui.freshnessColor
import coffee.crema.ui.theme.JetBrainsMono
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Bean detail — the read-only view of everything recorded about one bag
 * (issue 61).
 *
 * Before this, the ONLY way to read a bag's origin, process or tasting notes
 * on Android was to open the editor: a form full of live inputs you can dirty
 * by accident, and which on the phone hides the bottom nav. The bag photo was
 * never visible above 44 dp.
 *
 * The sections here are the shared body; the two shells wrap them in their own
 * container — a right-side sheet on the tablet (`BeanDetailSheet`), a pushed
 * full-screen route on the phone (`PhoneBeanDetailScreen`) — because that is
 * where the platforms genuinely differ. Section order and wording match the
 * web `BeanDrawer` field for field, so a user with a tablet and the PWA reads
 * the same bag the same way.
 *
 * Nothing here is editable. Every mutation is an explicit control in the
 * container's header or footer.
 */

/** The one place a missing value is rendered, so an empty bag reads as
 *  "nothing recorded" rather than as a broken layout. */
private const val EMPTY = "—"

/** Grams per shot used for the "~N shots left" estimate — the same 18 g the
 *  web drawer assumes. */
private const val GRAMS_PER_SHOT = 18f

/**
 * The scrolling body of the bean detail. The caller owns the scroll container
 * (a `LazyColumn` item, or a `verticalScroll` Column) and the surrounding
 * chrome; this is pure content.
 *
 * @param shotCount how many stored shots reference this bag.
 * @param recentShots the newest few, as `(date label, profile name, rating)`.
 * @param linkedProfileName resolved name of `Bean.linkedProfileId`, or
 *   `"(missing profile)"` when the id dangles, or null when unlinked.
 * @param onPhotoTap opens the full-size viewer; null when there is no photo.
 */
@Composable
fun BeanDetailContent(
    bean: Bean,
    roasterName: String?,
    linkedProfileName: String?,
    shotCount: Int,
    recentShots: List<ShotRowSummary>,
    onPhotoTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Open one of this bag's shots in History; null = rows not tappable. */
    onOpenShot: ((String) -> Unit)? = null,
    /** Open History filtered to this bag ("See all N shots"); null = hidden. */
    onSeeAllShots: (() -> Unit)? = null,
) {
    val days = beanDaysOffRoast(bean)
    val frozen = bean.isFrozen
    val openedDays = daysOffRoast(bean.openedOn)
    val bagSize = bean.bagSize ?: 0f
    val remaining = bean.remaining ?: 0f

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BeanDetailHero(bean, roasterName, frozen, onPhotoTap)
        BeanStatusStrip(bean, days, frozen, openedDays, bagSize, remaining, shotCount)

        DetailGroup("Identity") {
            DetailRow("Name", bean.name)
            DetailRow("Roaster", roasterName)
            DetailRow(
                "Roast level",
                bean.roastLevel?.let { "${it.toInt()}/10 · ${roastBand5(it.toInt())}" },
            )
            DetailRow("Roasted for", bean.roastType?.string?.replaceFirstChar { it.uppercase() })
            DetailRow(
                "Mix",
                when (bean.mix?.string) {
                    "blend" -> "Blend"
                    "single" -> "Single origin"
                    else -> null
                },
            )
            DetailRow("Decaf", if (bean.decaf == true) "Yes" else "No")
            // A dangling link reads "(missing profile)" rather than blank: the
            // difference between "no link" and "a link that no longer resolves"
            // is the difference between nothing to fix and something to fix.
            DetailRow("Linked profile", linkedProfileName)
            val tags = bean.tags?.filter { it.isNotBlank() }.orEmpty()
            if (tags.isNotEmpty()) DetailRow("Tags", tags.joinToString(" · "))
        }

        DetailGroup("Dates") {
            DetailRow("Roasted on", bean.roastedOn?.let { dateWithAge(it, days) })
            DetailRow("Opened on", bean.openedOn?.let { dateWithAge(it, openedDays) })
            DetailRow("Frozen on", bean.frozenOn)
            DetailRow("Defrosted on", bean.defrostedOn)
        }

        DetailGroup("Bag & grinder") {
            DetailRow("Bag size", bagSize.takeIf { it > 0f }?.let { "${it.toInt()} g" })
            DetailRow(
                "Remaining",
                remaining.takeIf { it > 0f || bagSize > 0f }?.let { "${it.toInt()} g" },
            )
            DetailRow("Grinder", bean.grinder)
            DetailRow("Setting", bean.grinderSetting)
        }

        DetailGroup("Origin") {
            DetailRow("Country", bean.origin?.country)
            DetailRow("Region", bean.origin?.region)
            DetailRow("Farm", bean.origin?.farm)
            DetailRow("Producer", bean.origin?.farmer)
            DetailRow("Variety", bean.origin?.variety)
            DetailRow("Elevation", bean.origin?.elevation)
            DetailRow("Process", bean.origin?.processing)
            DetailRow("Harvest", bean.origin?.harvestTime)
        }

        DetailGroup("Tasting") {
            DetailRow("Score", bean.qualityScore)
            DetailRowContent("Rating") {
                CremaStarRating(
                    bean.rating?.toInt() ?: 0,
                    starDp = 13,
                    emptyTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                )
            }
            DetailBlock("Tasting notes", bean.tastingNotes)
            // The free-form box, distinct from tasting notes — it had no
            // read-only surface anywhere before issue 61.
            DetailBlock("Notes", bean.notes)
        }

        DetailGroup("Buy again") {
            DetailRow("Place", bean.placeOfPurchase)
            DetailRow("Cost", bean.cost?.let { String.format("%.2f", it) })
            DetailRow("Link", bean.url)
        }

        if (recentShots.isNotEmpty()) {
            DetailGroup("Shots", count = shotCount) {
                recentShots.forEachIndexed { i, s ->
                    if (i > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (onOpenShot != null) Modifier.clickable { onOpenShot(s.id) }
                                else Modifier,
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            s.date,
                            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                s.profileName ?: EMPTY,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // The dial-in facts at a glance: grind · yield · time.
                            if (s.dial != null) {
                                Text(
                                    s.dial,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // The journal line: the forward-looking plan, else notes.
                            if (s.snippet != null) {
                                Text(
                                    s.snippet,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 10.sp,
                                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        CremaStarRating(
                            s.rating,
                            starDp = 11,
                            emptyTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                        )
                    }
                }
                if (onSeeAllShots != null && shotCount > recentShots.size) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Text(
                        "See all $shotCount shots",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSeeAllShots() }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** One row of the "Shots with this bean" list. */
data class ShotRowSummary(
    val id: String,
    val date: String,
    val profileName: String?,
    val rating: Int,
    /** "Grind 4.2 · 36.5 g · 28 s" — the dial-in facts, pieces omitted when unknown. */
    val dial: String?,
    /** One journal line: the forward-looking plan, else the tasting notes. */
    val snippet: String?,
)

/** Project a stored shot down to what the detail's shot list shows. */
fun shotRowSummary(shot: StoredShot): ShotRowSummary = ShotRowSummary(
    id = shot.id,
    date = SHOT_DATE_FORMAT.format(Date(shot.completedAtMs)),
    profileName = shot.profileName,
    rating = shot.rating ?: 0,
    dial = listOfNotNull(
        shot.grindLabel,
        shot.yieldG?.let { String.format(Locale.US, "%.1f g", it) },
        "${(shot.durationMs / 1000.0).toInt()} s",
    ).joinToString(" · ").ifBlank { null },
    snippet = (
        shot.nextPlan?.trim()?.takeIf { it.isNotEmpty() }
            ?: shot.notes?.trim()?.takeIf { it.isNotEmpty() }
        )?.replace(Regex("\\s+"), " "),
)

private val SHOT_DATE_FORMAT = SimpleDateFormat("d MMM", Locale.getDefault())

/**
 * Display name for a bag's linked profile: the profile's own name, or
 * `"(missing profile)"` when the id no longer resolves, or `null` when the bag
 * has no link at all.
 *
 * A dangling id is expected — profiles get deleted, and a cross-device import
 * can carry a link to a device-local custom. Every other consumer tolerates
 * it, so the detail says so out loud instead of rendering an em-dash that
 * would read as "not linked".
 */
fun linkedProfileNameFor(bean: Bean, profiles: List<Pair<String, String>>): String? {
    val id = bean.linkedProfileId ?: return null
    return profiles.firstOrNull { it.first == id }?.second ?: "(missing profile)"
}

// ── Hero ────────────────────────────────────────────────────────────────────

@Composable
private fun BeanDetailHero(
    bean: Bean,
    roasterName: String?,
    frozen: Boolean,
    onPhotoTap: (() -> Unit)?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Box {
            BeanPhotoBox(
                beanId = bean.id,
                imageRef = bean.imageRef,
                updatedAt = bean.updatedAt,
                modifier = Modifier
                    .size(104.dp)
                    .clip(RoundedCornerShape(14.dp))
                    // Only a real photo is tappable — a lightbox over the
                    // deterministic roaster mark would show nothing new.
                    .then(if (onPhotoTap != null) Modifier.clickable(onClick = onPhotoTap) else Modifier),
                fallback = { RoasterMarkAvatar(roasterName ?: bean.name, sizeDp = 104, cornerDp = 14, fontSize = 38.sp) },
            )
            if (onPhotoTap != null) {
                // A small corner affordance rather than a hover state: touch has
                // no hover, so the cue has to be permanent to exist at all.
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    PhIcon("magnifying-glass-plus", sizeDp = 14, tint = MaterialTheme.colorScheme.inverseOnSurface)
                }
            }
            if (frozen) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color(0xFF7DA0CD)),
                    contentAlignment = Alignment.Center,
                ) {
                    PhIcon("snowflake", sizeDp = 13, tint = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Eyebrow(roasterName ?: "No roaster")
            Text(
                bean.name.ifBlank { "Untitled bag" },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val origin = listOfNotNull(
                bean.origin?.country?.takeIf { it.isNotBlank() },
                bean.origin?.region?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            Text(
                origin.ifBlank { "No origin set" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                bean.roastLevel?.let { lvl ->
                    DetailPill("${roastBand5(lvl.toInt())} · ${lvl.toInt()}/10", accent = true)
                }
                if (bean.mix?.string == "blend") DetailPill("Blend")
                if (bean.decaf == true) DetailPill("Decaf")
                CremaStarRating(
                    bean.rating?.toInt() ?: 0,
                    starDp = 13,
                    emptyTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                )
            }
        }
    }
}

// ── Status strip ────────────────────────────────────────────────────────────

@Composable
private fun BeanStatusStrip(
    bean: Bean,
    days: Int?,
    frozen: Boolean,
    openedDays: Int?,
    bagSize: Float,
    remaining: Float,
    shotCount: Int,
) {
    CremaCard(shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusCell(
                    Modifier.weight(1f),
                    label = "Off roast",
                    value = days?.let { "${it}d" } ?: EMPTY,
                    sub = freshnessLabel(frozen, days),
                    dot = freshnessColor(frozen, bean.roastLevel?.toInt(), days),
                )
                StatusCell(
                    Modifier.weight(1f),
                    label = "Opened",
                    value = openedDays?.let { "${it}d" } ?: EMPTY,
                    sub = bean.openedOn ?: EMPTY,
                )
                StatusCell(
                    Modifier.weight(1f),
                    label = "Remaining",
                    value = if (bagSize > 0f || remaining > 0f) "${remaining.toInt()}g" else EMPTY,
                    sub = if (bagSize > 0f) {
                        val shots = (remaining / GRAMS_PER_SHOT).toInt()
                        "of ${bagSize.toInt()}g" + if (shots > 0) " · ~$shots shots" else ""
                    } else {
                        EMPTY
                    },
                )
                StatusCell(
                    Modifier.weight(1f),
                    label = "Shots",
                    value = "$shotCount",
                    sub = bean.qualityScore?.takeIf { it.isNotBlank() }?.let { "$it score" } ?: "in history",
                )
            }
            if (bagSize > 0f) {
                val pct = (remaining / bagSize).coerceIn(0f, 1f)
                Box(
                    Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    if (pct > 0f) {
                        Box(
                            Modifier.fillMaxWidth(pct).height(5.dp).clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCell(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    sub: String,
    dot: androidx.compose.ui.graphics.Color? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.5.sp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (dot != null) Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            Text(
                value,
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            sub,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun freshnessLabel(frozen: Boolean, days: Int?): String = when {
    frozen -> "Paused (frozen)"
    days == null -> "Unknown"
    else -> "Off roast"
}

// ── Section primitives ──────────────────────────────────────────────────────

@Composable
private fun DetailGroup(title: String, count: Int? = null, content: @Composable () -> Unit) {
    CremaCard(shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, letterSpacing = 0.6.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (count != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$count",
                        style = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    val shown = value?.takeIf { it.isNotBlank() } ?: EMPTY
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(104.dp),
        )
        Text(
            shown,
            style = MaterialTheme.typography.bodyMedium,
            color = if (shown == EMPTY) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DetailRowContent(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(104.dp),
        )
        Box(Modifier.weight(1f)) { content() }
    }
}

/** A multi-line block (notes) — stacked rather than in the label column, since
 *  free text needs the full width to stay readable. Omitted entirely when
 *  empty; a blank paragraph is noise, unlike a blank single-value row. */
@Composable
private fun DetailBlock(label: String, value: String?) {
    val text = value?.takeIf { it.isNotBlank() } ?: return
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun DetailPill(text: String, accent: Boolean = false) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (accent) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            )
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** `"2026-07-24 · 11d ago"` — the ISO date the user typed, plus the age the
 *  freshness maths derives from it. */
private fun dateWithAge(iso: String, days: Int?): String =
    if (days == null) iso else "$iso · ${days}d ago"
