package coffee.crema.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.beans.daysOffRoast
import coffee.crema.beans.roastBand
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager
import coffee.crema.core.Bean
import coffee.crema.core.MaintenanceReadout
import coffee.crema.core.Roaster
import coffee.crema.profiles.CremaProfile
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaAnchoredPopup
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaNavigationRail
import coffee.crema.ui.components.CremaValueUnit
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.theme.CremaTheme
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Brew — the hero dashboard (M1, read-only telemetry + machine control foot).
 *
 * A faithful port of the tablet design (prototype/tablet/brew-screen.jsx + the
 * web BrewDashboard): rail | column { header twin-block, body grid[248dp | 1fr],
 * foot(split) }. Every value reads from the live `MainUiState` the core decodes;
 * the four channel cards, timer, ratio, phase, and limits are driven by real
 * telemetry, and the foot's Coffee / Stop / mode chips drive machine control.
 *
 * M1 scope (what is deliberately NOT here yet):
 *  • The live chart is a static placeholder (M2 — pairs with per-frame phase fill).
 *  • Coffee does a DIRECT espresso request, not the gated profile-upload start
 *    sequence (lazy sync → await ProfileUploadCompleted → pre-shot flush → 500ms
 *    guard). That sequence is M2; for now Coffee brews whatever profile the DE1
 *    already has loaded, and the header selection is display-only.
 *  • The bean block is an honest empty state — the bean library is M3.
 *  • Quick Controls opens nothing yet (the bottom sheet is M2).
 */
@Composable
fun BrewScreen(
    vm: MainViewModel,
    onNav: (String) -> Unit,
    onConnect: (String) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val connected = ui.bleState == De1BleManager.State.READY
    val scaleConnected = ui.scaleState == ScaleBleManager.State.READY
    val active = ui.profiles.firstOrNull { it.id == ui.activeProfileId }
    val activeBean = ui.beans.firstOrNull { it.id == ui.activeBeanId }
    val running = ui.shotInProgress
    val espressoActive = ui.machineStateName == "Espresso"
    var quickOpen by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CremaNavigationRail(
            active = "brew",
            onNav = onNav,
            machineConnected = connected,
            scaleConnected = scaleConnected,
            onConnect = onConnect,
        )
        Column(Modifier.weight(1f).fillMaxHeight()) {
            BrewHeader(
                active = active,
                profiles = ui.profiles,
                onSelectProfile = vm::setActiveProfile,
                uploading = ui.profileUploading,
                uploadProgress = ui.profileUploadProgress,
                activeBean = activeBean,
                beans = ui.beans,
                roasters = ui.roasters,
                onSelectBean = vm::setActiveBean,
                onOpenQuick = { quickOpen = true },
                // Footer/empty-state routes — mirror ProfilesScreen/BeansScreen wiring
                // (start a fresh editor in the VM, then route to its screen).
                onOpenProfiles = { onNav("profiles") },
                onNewProfile = { vm.startNewProfile(); onNav("profile-edit") },
                // Edit the active profile — custom edits in place, built-in edits a
                // copy (ProfilesScreen parity); no active profile → start a new one.
                onEditProfile = {
                    when {
                        active == null -> vm.startNewProfile()
                        active.source == "custom" -> vm.startEditProfile(active.id)
                        else -> vm.duplicateProfile(active.id)
                    }
                    onNav("profile-edit")
                },
                onOpenBeans = { onNav("beans") },
                onNewBean = { onNav("beans") },
                // Edit the active bag in the full bean editor (BeansScreen parity);
                // no active bean → route to Beans to add one.
                onEditBean = {
                    if (activeBean != null) { vm.startEditBean(activeBean.id); onNav("bean-edit") }
                    else onNav("beans")
                },
            )
            // Slim amber maintenance banner — modelled on the PWA MachineErrorBanner.
            // Shown when any maintenance counter is past its interval (descale →
            // clean → filter, most-urgent first); hidden when all ok or no readout.
            BrewHeadBanner(readout = ui.maintenanceReadout, onOpenWater = { onNav("settings") })
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Left column (248dp). Fixed cards top + bottom; the Phase card takes
                // the flexible middle and scrolls its phase list internally, so adding
                // the Last-shot card never pushes anything off-screen.
                Column(
                    modifier = Modifier
                        .width(248.dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TimerCard(running = running, elapsedMs = ui.shotElapsedMs, phase = ui.shotPhase)
                    RatioCard(active = active, weightG = ratioWeight(ui, running))
                    PhaseCard(
                        active = active,
                        running = running,
                        frame = ui.shotFrame,
                        phase = ui.shotPhase,
                        modifier = Modifier.weight(1f),
                    )
                    LimitsCard(active = active, ui = ui)
                    if (!running && ui.lastShot != null) {
                        LastShotCard(
                            last = ui.lastShot!!,
                            dose = active?.dose ?: 18f,
                            onClick = { vm.openShotInHistory(ui.lastShot!!.id); onNav("history") },
                        )
                    }
                }
                // Right column (fills remainder).
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChannelsRow(ui = ui, active = active)
                    // The live chart fills the remainder. Hosted in a Surface
                    // (not CremaCard) so the Canvas chart can fillMaxSize.
                    Surface(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        CanvasShotChart(
                            samples = ui.shotTelemetry,
                            enabledChannels = ui.chartChannels,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                        )
                    }
                }
            }
            BrewFoot(
                ui = ui,
                connected = connected,
                scaleConnected = scaleConnected,
                espressoActive = espressoActive,
                onPower = { if (ui.machineStateName == "Sleep") vm.wake() else vm.sleep() },
                onSteam = { if (ui.machineStateName == "Steam") vm.stopShot() else vm.steam() },
                onHotWater = { if (ui.machineStateName == "HotWater") vm.stopShot() else vm.hotWater() },
                onFlush = vm::flush,
                // Gated start: upload the active profile, await completion, guard,
                // then Espresso (vm.startShot). Stop is a direct Idle request.
                onCoffee = { if (espressoActive) vm.stopShot() else vm.startShot() },
            )
            if (quickOpen) {
                QuickControlsSheet(
                    active = active,
                    brewParams = ui.brewParams,
                    pinnedProfiles = ui.profiles.filter { it.pinned },
                    favBeans = ui.beans.filter { it.favourite },
                    activeProfileId = ui.activeProfileId,
                    activeBeanId = ui.activeBeanId,
                    autoTare = ui.autoTare,
                    stopOnWeight = ui.stopOnWeight,
                    steamEco = ui.steamEco,
                    channels = ui.chartChannels,
                    onSelectProfile = vm::setActiveProfile,
                    onSelectBean = vm::setActiveBean,
                    onAdjustBrew = vm::quickAdjustBrew,
                    onResetBrew = vm::resetBrewParams,
                    onSavePreset = vm::saveQuickPreset,
                    onAutoTare = vm::setAutoTare,
                    onStopOnWeight = vm::setStopOnWeight,
                    onSteamEco = vm::setSteamEco,
                    onToggleChannel = vm::toggleChartChannel,
                    onDismiss = { quickOpen = false },
                )
            }
        }
    }
}

// Which weight feeds the Ratio card: the live scale weight while running, else
// the last shot's yield held until the next shot (web parity).
private fun ratioWeight(ui: coffee.crema.ui.MainUiState, running: Boolean): Float? =
    if (!running) (ui.lastShot?.yieldG ?: ui.scaleWeightG) else ui.scaleWeightG

// ── Header twin-block ───────────────────────────────────────────────────────

@Composable
private fun BrewHeader(
    active: CremaProfile?,
    profiles: List<CremaProfile>,
    onSelectProfile: (String) -> Unit,
    uploading: Boolean,
    uploadProgress: String?,
    activeBean: Bean?,
    beans: List<Bean>,
    roasters: List<Roaster>,
    onSelectBean: (String) -> Unit,
    onOpenQuick: () -> Unit,
    onOpenProfiles: () -> Unit,
    onNewProfile: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenBeans: () -> Unit,
    onNewBean: () -> Unit,
    onEditBean: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProfileBlock(
            active = active,
            profiles = profiles,
            onSelect = onSelectProfile,
            uploading = uploading,
            uploadProgress = uploadProgress,
            onOpenLibrary = onOpenProfiles,
            onNew = onNewProfile,
            onEdit = onEditProfile,
        )
        Spacer(Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(44.dp).background(MaterialTheme.colorScheme.outlineVariant))
        BeanBlock(
            activeBean = activeBean,
            beans = beans,
            roasters = roasters,
            onSelect = onSelectBean,
            onOpenLibrary = onOpenBeans,
            onNew = onNewBean,
            onEdit = onEditBean,
        )
        QuickControlsPill(onClick = onOpenQuick)
    }
}

// ── Maintenance banner (PWA MachineErrorBanner, amber) ──────────────────────
// A slim full-width amber strip shown when any maintenance counter is overdue.
// Picks the most-urgent due item (descale → clean → filter) for the headline;
// tapping it routes to Settings (the Water & maintenance section). Hidden when
// every counter is ok or the readout hasn't been computed yet.
@Composable
private fun BrewHeadBanner(readout: MaintenanceReadout?, onOpenWater: () -> Unit) {
    if (readout == null) return
    val message = when {
        !readout.descaleOk -> "Descale due"
        !readout.cleanOk -> "Clean due"
        !readout.filterOk -> "Filter due"
        else -> return
    }
    val amber = Color(0xFFDBA764)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(amber.copy(alpha = 0.14f))
            .border(1.dp, amber.copy(alpha = 0.40f), RoundedCornerShape(10.dp))
            .clickable(onClick = onOpenWater)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PhIcon("wrench", sizeDp = 16, tint = amber)
        Text(
            message,
            style = MaterialTheme.typography.labelLarge,
            color = amber,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Maintenance",
            style = MaterialTheme.typography.labelSmall,
            color = amber.copy(alpha = 0.85f),
        )
        PhIcon("caret-right", sizeDp = 14, tint = amber.copy(alpha = 0.85f))
    }
}

@Composable
private fun ProfileBlock(
    active: CremaProfile?,
    profiles: List<CremaProfile>,
    onSelect: (String) -> Unit,
    uploading: Boolean,
    uploadProgress: String?,
    onOpenLibrary: () -> Unit,
    onNew: () -> Unit,
    onEdit: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // Box wraps the anchor block + the popup so the Popup positions against the
    // block's bounds (proto: .bh-pop anchored to .bh-anchor at top:100%+8px).
    Box {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                // Open-state tint so the anchor reads as active while the menu is up
                // (proto .bh-block.is-open = primary @13%).
                .background(if (open) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f) else Color.Transparent)
                .clickable { open = !open }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("Profile")
                if (uploading) {
                    Text(
                        uploadProgress?.let { "Uploading… $it" } ?: "Uploading…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    active?.name ?: "No profile selected",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 24.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Caret is always shown — the picker (or library route) is always reachable.
                PhIcon("caret-down", sizeDp = 16, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (active != null) {
                Text(
                    "Pre-inf ${active.preinfuseSeconds}s · 1:%.2f · %.1f g · %.1f °C".format(
                        active.ratio, active.yieldOut, active.brewTemp,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // .bh-spec — "{beverage} · {roast} · {author}".
                val spec = listOfNotNull(
                    active.beverageType?.replaceFirstChar { it.uppercase() },
                    active.roast?.replaceFirstChar { it.uppercase() },
                    active.author.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (spec.isNotBlank()) {
                    Text(
                        spec,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // .bh-tags — custom tags joined " · " (the synthesised "Built-in"
                // import tag is dropped; rendered only when something remains).
                val tags = active.tags
                    .filter { it.isNotBlank() && it != "Built-in" }
                    .joinToString(" · ")
                if (tags.isNotBlank()) {
                    Text(
                        tags,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        CremaAnchoredPopup(expanded = open, onDismiss = { open = false }) {
            PopCard {
                PickerLead("Switch profile · pinned")
                // Active first, then pinned, then store order (web HeaderPicker rank).
                val sorted = profiles.sortedByDescending {
                    (if (it.id == active?.id) 2 else 0) + (if (it.pinned) 1 else 0)
                }
                PopList {
                    sorted.forEach { p ->
                        val isActive = p.id == active?.id
                        PickRow(
                            active = isActive,
                            onClick = { open = false; onSelect(p.id) },
                            leading = {
                                // 48×32 mini-curve thumbnail (proto .bh-pick-spark).
                                Box(
                                    Modifier
                                        .size(width = 48.dp, height = 32.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                                ) {
                                    CanvasProfilePreview(
                                        segments = p.segments,
                                        modifier = Modifier.fillMaxSize().padding(2.dp),
                                        compact = true,
                                    )
                                }
                            },
                            name = p.name,
                            // 2nd line = author; blank → Built-in / Custom (proto .bh-pick-meta).
                            meta = p.author.takeIf { it.isNotBlank() }
                                ?: if (p.source == "builtin") "Built-in" else "Custom",
                        )
                    }
                }
                PickerFootRow(
                    onOpenLibrary = { open = false; onOpenLibrary() },
                    onNew = { open = false; onNew() },
                    onEdit = { open = false; onEdit() },
                )
            }
        }
    }
}

@Composable
private fun BeanBlock(
    activeBean: Bean?,
    beans: List<Bean>,
    roasters: List<Roaster>,
    onSelect: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onNew: () -> Unit,
    onEdit: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val roasterNameOf: (Bean) -> String? = { b ->
        b.roasterId?.let { rid -> roasters.firstOrNull { it.id == rid }?.name }
    }
    // With an empty library there is nothing to pick — tapping the block routes
    // straight to Beans (proto/PWA never present a dead end).
    val hasBeans = beans.isNotEmpty()
    Box {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (open && hasBeans) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f) else Color.Transparent)
                .clickable { if (hasBeans) open = !open else onOpenLibrary() }
                // Bounded width so the freshness chip + caret right-justify WITHIN
                // the block (not across the whole header — that ate the QC pill).
                .width(264.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            // Bean info is LEFT-justified (like the profile block); only the
            // green-dot freshness chip + the caret are pushed to the right edge.
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // .bh-eyebrow-row — "Bean" + the freshness chip on the same row. The
            // chip ("Nd off roast" / "Frozen") is coloured by freshness band and
            // shown only when a roast date (or freeze) is known.
            val frozen = activeBean?.frozenOn != null
            val daysOff = activeBean?.let { daysOffRoast(it.roastedOn) }
            val freshLabel = when {
                activeBean == null -> null
                frozen -> "Frozen"
                else -> daysOff?.let { "${it}d off roast" }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Eyebrow("Bean")
                if (freshLabel != null) {
                    val freshColor = beanFreshnessColor(frozen, daysOff)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(freshColor))
                        Text(freshLabel, style = MaterialTheme.typography.labelSmall, color = freshColor)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    activeBean?.let { listOfNotNull(roasterNameOf(it), it.name).joinToString(" · ") } ?: "No bean selected",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 24.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Caret on the right — empty taps route to the Beans library.
                PhIcon("caret-down", sizeDp = 16, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (activeBean != null) {
                // .bh-meta — "{country} · {variety} · {process}".
                val meta = listOfNotNull(
                    activeBean.origin.country,
                    activeBean.origin.variety,
                    activeBean.origin.processing,
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // .bh-spec — "{roastBand} · {roastType} · {mix} · Grind {grind}".
                val spec = listOfNotNull(
                    roastBand(activeBean.roastLevel?.toInt()),
                    activeBean.roastType?.string?.replaceFirstChar { it.uppercase() },
                    activeBean.mix?.string?.replaceFirstChar { it.uppercase() },
                    activeBean.grinderSetting.takeIf { it.isNotBlank() }?.let { "Grind $it" },
                ).joinToString(" · ")
                if (spec.isNotBlank()) {
                    Text(
                        spec,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // .bh-tags — custom tags joined " · ".
                val tags = activeBean.tags?.filter { it.isNotBlank() }.orEmpty().joinToString(" · ")
                if (tags.isNotBlank()) {
                    Text(
                        tags,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    if (hasBeans) "Tap to choose a bag" else "Tap to add your first bag",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }
        CremaAnchoredPopup(expanded = open && hasBeans, onDismiss = { open = false }) {
            PopCard {
                PickerLead("Switch bean · pinned")
                // Active first, then favourite, then store order (mirrors the profile rank).
                val sorted = beans.sortedByDescending {
                    (if (it.id == activeBean?.id) 2 else 0) + (if (it.favourite) 1 else 0)
                }
                PopList {
                    sorted.forEach { b ->
                        val isActive = b.id == activeBean?.id
                        val rName = roasterNameOf(b)
                        PickRow(
                            active = isActive,
                            onClick = { open = false; onSelect(b.id) },
                            leading = { RoasterAvatar(seed = rName ?: b.name) },
                            // "roaster · name"; meta = origin (+ roast band if present).
                            name = listOfNotNull(rName, b.name).joinToString(" · "),
                            meta = listOfNotNull(
                                b.origin.country,
                                b.origin.processing,
                                roastBand(b.roastLevel?.toInt()),
                            ).joinToString(" · "),
                        )
                    }
                }
                PickerFootRow(
                    onOpenLibrary = { open = false; onOpenLibrary() },
                    onNew = { open = false; onNew() },
                    onEdit = { open = false; onEdit() },
                )
            }
        }
    }
}

// ── Header-picker shared bits (proto .bh-pop) ───────────────────────────────

// The bordered popover card (proto .bh-pop): surfaceContainerHigh fill, 1dp
// outline-variant border, 16dp radius, 12dp padding, level-3 shadow, width
// clamped 320–420dp. Sets LocalContentColor to onSurface so the popup never
// inherits a tint from the warm Brew page root.
@Composable
private fun PopCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.widthIn(min = 320.dp, max = 420.dp),
    ) {
        Column(Modifier.padding(12.dp), content = content)
    }
}

// The scrolling row list (proto .bh-pop-list): column, 2dp gap, 10dp top margin,
// height-bounded so the LEAD + FOOTER stay fixed while only the rows scroll.
@Composable
private fun PopList(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .padding(top = 10.dp)
            .heightIn(max = 300.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

// Uppercase section header (proto .bh-pop-lead): 11sp/500, 0.6px tracking.
@Composable
private fun PickerLead(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.6.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// One picker row (proto .bh-pick): leading 48×32 spark / 32×32 avatar, a name +
// meta text column, and a trailing check only when active. The 10dp-radius
// rounded row sits inside the card's 12dp padding, so the active highlight reads
// as a small inset card (secondaryContainer / onSecondaryContainer) — NOT a
// full-bleed bar. Hover → surfaceContainerHighest (proto .bh-pick:hover).
@Composable
private fun PickRow(
    active: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    name: String,
    meta: String,
) {
    // Active row = the shared copper "selected" wash (the same primary@12% used by
    // the sort/overflow menus + the web .hpick-item.is-active), not a solid neutral
    // card. Name stays onSurface for readability; the wash + copper check mark active.
    val rowColor = MaterialTheme.colorScheme.onSurface
    val metaColor = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
        contentColor = rowColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading()
            // bh-pick-text: name (14sp/500) + meta (12sp/400), both ellipsised.
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                    color = rowColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 15.sp),
                        color = metaColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Trailing check only on the active row (proto .bh-pick-check).
            if (active) {
                PhIcon("check", sizeDp = 16, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// 32×32 rounded roaster mark with the initial (proto .bh-pick-av). Tone is a
// deterministic theme colour keyed off the seed (no per-roaster colour field yet).
@Composable
private fun RoasterAvatar(seed: String) {
    val palette = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
    )
    val color = palette[(seed.hashCode() and Int.MAX_VALUE) % palette.size]
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            seed.take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

// Footer action row (proto .bh-pop-footrow): a flexible ghost "Open library"
// (transparent, primary text, outline-variant border) + two 42dp icon buttons
// ("+" new, "✏" edit) on surfaceContainerHighest. All three are 10dp-radius,
// 42dp tall (9dp vertical padding on the 13sp ghost label).
@Composable
private fun PickerFootRow(onOpenLibrary: () -> Unit, onNew: () -> Unit, onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Ghost "Open library" — flex 1, transparent, primary text, 1dp border.
        Row(
            modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                .clickable(onClick = onOpenLibrary)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Open library",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PopFootIcon(icon = "plus", onClick = onNew)
        PopFootIcon(icon = "pencil-simple", onClick = onEdit)
    }
}

// 42dp-wide square icon button on the footer row (proto .bh-pop-icon).
@Composable
private fun PopFootIcon(icon: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(42.dp)
            .height(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PhIcon(icon, sizeDp = 16, tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun QuickControlsPill(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhIcon("sliders-horizontal", sizeDp = 18, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(
            "Quick Controls",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

// ── Left column cards ───────────────────────────────────────────────────────

@Composable
private fun TimerCard(running: Boolean, elapsedMs: Long, phase: String?) {
    val totalSec = elapsedMs / 1000.0
    val mm = (totalSec / 60).toInt()
    val ss = (totalSec % 60).toInt()
    val tenth = ((totalSec % 1) * 10).toInt()
    val step = if (!running) "Ready" else phaseStepLabel(phase)
    CremaCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().height(86.dp).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Eyebrow(step)
            // Proto .brew-timer-val: main mono value + the orange ".N" frac share
            // ONE text baseline (display:flex; align-items:baseline). alignByBaseline()
            // on both children is the only reliable Row baseline-align in Compose, so
            // the frac no longer sits low/misaligned against the main readout.
            Row {
                Text(
                    "%02d:%02d".format(mm, ss),
                    style = CremaTheme.readout.readoutMd.copy(fontSize = 44.sp, lineHeight = 48.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.alignByBaseline(),
                )
                Text(
                    ".$tenth",
                    style = CremaTheme.readout.readoutSm.copy(fontSize = 20.sp, lineHeight = 24.sp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
    }
}

@Composable
private fun RatioCard(active: CremaProfile?, weightG: Float?) {
    val dose = active?.dose ?: 18f
    val live = if (weightG != null && dose > 0f) "1:%.2f".format(weightG / dose) else "1:0.00"
    CremaCard(Modifier.fillMaxWidth()) {
        // proto .brew-ratio padding: 5px vertical / 16px horizontal (compact).
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
            Eyebrow("Ratio")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    live,
                    style = CremaTheme.readout.readoutSm.copy(fontSize = 24.sp, lineHeight = 28.sp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.alignByBaseline(),
                )
                if (active != null && active.ratio > 0f) {
                    Text(
                        "· target 1:%.2f".format(active.ratio),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PhaseCard(
    active: CremaProfile?,
    running: Boolean,
    frame: Int,
    phase: String?,
    modifier: Modifier = Modifier,
) {
    val segments = active?.segments ?: emptyList()
    val activeIdx = if (running) frame.coerceIn(0, max(0, segments.lastIndex)) else -1
    val listState = rememberLazyListState()
    // Auto-advance: while a shot runs, keep the active frame centred in the list
    // (web PhaseIndicatorCard's scrollTo-centre $effect).
    LaunchedEffect(activeIdx, running) {
        if (running && activeIdx >= 0) {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo.firstOrNull { it.index == activeIdx }
            if (visible != null) {
                val vpCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                val itemCenter = visible.offset + visible.size / 2f
                listState.animateScrollBy(itemCenter - vpCenter)
            } else {
                listState.animateScrollToItem(activeIdx)
            }
        }
    }
    CremaCard(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Eyebrow("Phase")
                Text(
                    if (running) phaseStepLabel(phase) else "Idle",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (segments.isEmpty()) {
                Text(
                    "No profile",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Proportional segment track (fill ∝ duration).
                Row(
                    Modifier.fillMaxWidth().height(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    segments.forEachIndexed { i, seg ->
                        val fillFrac = if (i <= activeIdx) 1f else 0f
                        val fillColor = if (i == activeIdx) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        Box(
                            Modifier
                                .weight(max(0.01f, seg.time))
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(fillFrac)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(fillColor),
                            )
                        }
                    }
                }
                // Per-segment rows — internally scrollable with a top/bottom fade
                // (web .crema-phase-list mask-image) so a long profile never pushes
                // the rest of the column off-screen, plus auto-advance above.
                val density = LocalDensity.current
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val h = size.height
                            if (h <= 0f) return@drawWithContent
                            val fade = (with(density) { 16.dp.toPx() } / h).coerceIn(0f, 0.45f)
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to (if (listState.canScrollBackward) Color.Transparent else Color.Black),
                                    fade to Color.Black,
                                    1f - fade to Color.Black,
                                    1f to (if (listState.canScrollForward) Color.Transparent else Color.Black),
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    itemsIndexed(segments) { i, seg ->
                        PhaseRow(seg = seg, isActive = i == activeIdx, isPast = i < activeIdx)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhaseRow(seg: coffee.crema.profiles.ProfileSegment, isActive: Boolean, isPast: Boolean) {
    val tel = CremaTheme.telemetry
    val border = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    else MaterialTheme.colorScheme.outlineVariant
    val bg = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isPast) 0.78f else 1f)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            seg.name.ifBlank { "Frame" },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Early-exit metric icon (channel-coloured), when the frame can exit early.
        seg.exit?.metric?.let { metric ->
            val (icon, color) = when (metric) {
                "pressure" -> "gauge" to tel.pressure
                "flow", "volume" -> "drop" to tel.flow
                "weight" -> "scales" to tel.weight
                else -> "timer" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            PhIcon(icon, sizeDp = 14, tint = color)
        }
        Text(
            phaseTime(seg.time),
            style = CremaTheme.readout.readoutSm.copy(fontSize = 12.sp, lineHeight = 15.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun LimitsCard(active: CremaProfile?, ui: coffee.crema.ui.MainUiState) {
    val tel = CremaTheme.telemetry
    val weightG = ui.scaleWeightG
    val timeColor = MaterialTheme.colorScheme.primary
    val rows = buildList {
        if (active != null && active.yieldOut > 0f) {
            add(LimitRow("Yield", "scales", tel.weight, weightG, active.yieldOut, "g"))
        }
        if (active != null && active.maxTotalVolumeMl > 0) {
            add(LimitRow("Volume", "drop-half", tel.flow, ui.dispensedVolume, active.maxTotalVolumeMl.toFloat(), "ml"))
        }
        // Time cap from the persisted setting (ui.maxShotDurationS); live = elapsed
        // shot seconds. Always shown — it's a global hard cap, not profile-scoped.
        if (ui.maxShotDurationS > 0f) {
            add(LimitRow("Time", "timer", timeColor, ui.shotElapsedMs / 1000f, ui.maxShotDurationS, "s"))
        }
    }
    if (rows.isEmpty()) return
    CremaCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Eyebrow("Max · stop conditions")
            rows.forEach { LimitRowView(it) }
        }
    }
}

private data class LimitRow(
    val label: String,
    val icon: String,
    val color: Color,
    val live: Float?,
    val target: Float,
    val unit: String,
)

@Composable
private fun LimitRowView(row: LimitRow) {
    val frac = if (row.target > 0f && row.live != null) (row.live / row.target).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                PhIcon(row.icon, sizeDp = 16, tint = row.color)
                Text(row.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "%.1f / %.1f%s".format(row.live ?: 0f, row.target, row.unit),
                style = CremaTheme.readout.readoutSm.copy(fontSize = 15.sp, lineHeight = 19.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatBar(fraction = frac, color = row.color)
    }
}

// Last shot summary (web LastShotCard.svelte: a 3-col grid of 5 stats). Tapping
// opens the matching shot in History. Shown after a shot finishes until the next
// one starts.
@Composable
private fun LastShotCard(last: coffee.crema.ui.LastShot, dose: Float, onClick: () -> Unit) {
    val yieldG = last.yieldG
    val ratio = if (yieldG != null && dose > 0f) "1:%.2f".format(yieldG / dose) else "—"
    CremaCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // "Last shot · N min ago" eyebrow + a chevron hinting the tap-through.
            val ago = last.completedAtMs.takeIf { it > 0L }?.let {
                android.text.format.DateUtils.getRelativeTimeSpanString(
                    it,
                    System.currentTimeMillis(),
                    android.text.format.DateUtils.MINUTE_IN_MILLIS,
                ).toString()
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Eyebrow(if (ago != null) "Last shot · $ago" else "Last shot")
                PhIcon("caret-right", sizeDp = 13, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 5 stats in a 3-col grid (Time · Yield · Ratio / Peak · Peak temp), the
            // web .ls-grid order. Float? guarded before %f (never pass an Int).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LastShotStat("Time", "%.1f".format(last.durationMs / 1000.0), "s", Modifier.weight(1f))
                LastShotStat("Yield", if (yieldG != null) "%.1f".format(yieldG) else "—", "g", Modifier.weight(1f))
                LastShotStat("Ratio", ratio, null, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LastShotStat("Peak", last.peakPressure?.let { "%.1f".format(it) } ?: "—", "bar", Modifier.weight(1f))
                LastShotStat("Peak temp", last.peakTemp?.let { "%.0f".format(it) } ?: "—", "°C", Modifier.weight(1f))
                Spacer(Modifier.weight(1f)) // hold the 3-col grid alignment
            }
        }
    }
}

@Composable
private fun LastShotStat(label: String, value: String, unit: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            maxLines = 1,
        )
        CremaValueUnit(value, unit, valueSize = 15.sp, unitSize = 10.sp)
    }
}

// ── Right column ────────────────────────────────────────────────────────────

@Composable
private fun ChannelsRow(ui: coffee.crema.ui.MainUiState, active: CremaProfile?) {
    val tel = CremaTheme.telemetry
    val resist = ui.resistanceWeight ?: ui.resistance
    val resistUnit = if (ui.resistanceWeight != null) "bar·s²/g²" else "bar·s²/ml²"
    // Proto .brew-channels: grid repeat(4,1fr) gap 9px — all four cards EQUAL
    // width AND height (grid stretch). The Coffee/Weight cards carry a "target …"
    // line the Pressure/Flow cards don't, so without an explicit height they'd be
    // taller. IntrinsicSize.Max measures the tallest card; fillMaxHeight() then
    // stretches the shorter two to match (the missing-line cards just gain height).
    Row(
        // Fixed 86dp = the timer card's height, so the top band is uniform and
        // the cards read compact (proto .brew-channel is a slim row, not a stack).
        Modifier.fillMaxWidth().height(86.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        ChannelCard(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            primLabel = "Pressure", primIcon = "gauge", primColor = tel.pressure,
            primValue = fmt(ui.pressure), primUnit = "bar",
            secLabel = "Resistance", secColor = tel.pressure2,
            secValue = fmt(resist, 2), secUnit = resistUnit,
        )
        ChannelCard(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            primLabel = "Flow", primIcon = "drop", primColor = tel.flow,
            primValue = fmt(ui.flow), primUnit = "ml/s",
            secLabel = "Water", secColor = tel.flow2,
            secValue = fmt(ui.dispensedVolume), secUnit = "ml",
        )
        ChannelCard(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            primLabel = "Coffee", primIcon = "thermometer", primColor = tel.temp,
            primValue = fmt(ui.headTemp), primUnit = "°C",
            secLabel = "Water", secColor = tel.temp2,
            secValue = fmt(ui.mixTemp), secUnit = "°C",
            target = active?.let { "target %.1f °C".format(it.brewTemp) },
        )
        ChannelCard(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            primLabel = "Weight", primIcon = "scales", primColor = tel.weight,
            primValue = fmt(ui.scaleWeightG), primUnit = "g",
            secLabel = "Flow", secColor = tel.weight2,
            secValue = fmt(ui.scaleFlowGPerS), secUnit = "g/s",
            target = active?.let { "target %.1f g".format(it.yieldOut) },
        )
    }
}

@Composable
private fun ChannelCard(
    modifier: Modifier,
    primLabel: String, primIcon: String, primColor: Color, primValue: String, primUnit: String,
    secLabel: String, secColor: Color, secValue: String, secUnit: String,
    target: String? = null,
) {
    CremaCard(modifier) {
        // proto v2 .brew-channel padding: 9px vertical / 13px horizontal (compact).
        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Primary (left).
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhIcon(primIcon, sizeDp = 14, tint = primColor)
                    Eyebrow(primLabel, color = primColor)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(primValue, style = CremaTheme.readout.readoutSm.copy(fontSize = 24.sp, lineHeight = 26.sp), color = primColor)
                    Text(" $primUnit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (target != null) {
                    Text(target, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // Secondary (right).
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Eyebrow(secLabel, color = secColor)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(secValue, style = CremaTheme.readout.readoutSm.copy(fontSize = 24.sp, lineHeight = 26.sp), color = secColor)
                    Text(" $secUnit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ── Foot (split) ────────────────────────────────────────────────────────────

@Composable
private fun BrewFoot(
    ui: coffee.crema.ui.MainUiState,
    connected: Boolean,
    scaleConnected: Boolean,
    espressoActive: Boolean,
    onPower: () -> Unit,
    onSteam: () -> Unit,
    onHotWater: () -> Unit,
    onFlush: () -> Unit,
    onCoffee: () -> Unit,
) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Left meta cluster.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PowerButton(connected = connected, asleep = ui.machineStateName == "Sleep", onClick = onPower)
                FootMeta("Machine", ui.machineState ?: "—")
                FootDivider()
                FootMeta("Scale", if (scaleConnected) (ui.scaleName ?: "Scale") else "—")
                FootDivider()
                FootMeta("Steam", ui.steamTemp?.let { "%.0f".format(it) } ?: "—", ui.steamTemp?.let { "°C" })
                FootMeta("Tank", ui.waterLevelMm?.let { "%.0f".format(it) } ?: "—", ui.waterLevelMm?.let { "mm" })
            }
            // Right actions.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("Steam", "148 °C · 90 s", "cloud", CremaTheme.telemetry.modeSteam, active = ui.machineStateName == "Steam", enabled = connected, onTap = onSteam)
                ModeChip("Hot water", "90 °C · 250 ml", "drop", CremaTheme.telemetry.modeWater, active = ui.machineStateName == "HotWater", enabled = connected, onTap = onHotWater)
                ModeChip("Flush", "91 °C · 4 s", "sparkle", CremaTheme.telemetry.modeFlush, active = false, enabled = connected, onTap = onFlush)
                CoffeeButton(running = espressoActive, uploading = ui.profileUploading, enabled = connected, onClick = onCoffee)
            }
        }
    }
}

@Composable
private fun RowScope.FootMeta(label: String, value: String, unit: String? = null) {
    Row(
        Modifier.padding(end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Eyebrow(label)
        if (unit != null) {
            CremaValueUnit(value, unit, valueSize = 13.sp)
        } else {
            Text(
                value,
                style = CremaTheme.readout.readoutSm.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FootDivider() {
    Box(Modifier.width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun PowerButton(connected: Boolean, asleep: Boolean, onClick: () -> Unit) {
    val tint = when {
        !connected -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        asleep -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> CremaTheme.telemetry.success
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = connected, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PhIcon("power", sizeDp = 18, tint = tint)
    }
}

@Composable
private fun ModeChip(
    label: String,
    sub: String,
    icon: String,
    modeColor: Color,
    active: Boolean,
    enabled: Boolean,
    onTap: () -> Unit,
) {
    val border = if (active) modeColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val bg = modeColor.copy(alpha = if (active) 0.20f else 0.14f)
    Row(
        modifier = Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.5.dp, border, RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onTap)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhIcon(icon, sizeDp = 19, tint = modeColor)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(sub, style = CremaTheme.readout.readoutSm.copy(fontSize = 10.5f.sp, lineHeight = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (active) {
            Box(
                Modifier.size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { PhIcon("x", sizeDp = 10, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun CoffeeButton(running: Boolean, uploading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val stopColor = Color(0xFFD26456)
    val bg = if (running) stopColor else MaterialTheme.colorScheme.primary
    val fg = if (running) Color(0xFF2A0B07) else MaterialTheme.colorScheme.onPrimary
    // Tappable to start (Coffee) or stop (Stop); inert while a profile uploads.
    val clickable = enabled && !uploading
    val label = when { running -> "Stop"; uploading -> "Uploading…"; else -> "Coffee" }
    val icon = when { running -> "stop"; uploading -> "arrows-clockwise"; else -> "coffee" }
    Row(
        modifier = Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (clickable || running) bg else bg.copy(alpha = 0.4f))
            .clickable(enabled = clickable, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PhIcon(icon, sizeDp = 20, tint = fg)
        Text(
            label,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            color = fg,
        )
    }
}

// ── Shared bits ─────────────────────────────────────────────────────────────

@Composable
private fun StatBar(fraction: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(999.dp))
                .background(color),
        )
    }
}

private fun fmt(v: Float?, digits: Int = 1): String =
    if (v == null) "—" else "%.${digits}f".format(v)

// Freshness-band colour for the bean eyebrow chip (proto .bh-fresh / web
// freshColor). Mirrors BeansScreen.freshnessColor — kept local because that
// one is private; frozen → blue, then the days-off-roast bands.
private fun beanFreshnessColor(frozen: Boolean, days: Int?): Color = when {
    frozen -> Color(0xFF7FB0E0)
    days == null -> Color(0xFF8A8175)
    days < 4 -> Color(0xFFDBA764)
    days <= 21 -> Color(0xFF5FB87A)
    days <= 40 -> Color(0xFFDBA764)
    else -> Color(0xFFC58B8B)
}

private fun phaseStepLabel(phase: String?): String = when (phase) {
    "Heating" -> "Heating"
    "Preinfusion" -> "Pre-infusion"
    "Pouring" -> "Extraction"
    "Ending" -> "Ending"
    else -> "Extraction"
}

private fun phaseTime(t: Float): String = when {
    t <= 0f -> "—"
    t < 1f -> "%.1fs".format(t)
    else -> "${t.roundToInt()}s"
}
