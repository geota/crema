package coffee.crema.ui

import coffee.crema.beans.BeanLibrary
import coffee.crema.beans.DEFAULT_GRIND_SCALE
import coffee.crema.beans.activeGrinder
import coffee.crema.beans.grindDiffers
import coffee.crema.beans.grindNumber
import coffee.crema.beans.grindText
import coffee.crema.beans.isLive
import coffee.crema.beans.savedGrind
import coffee.crema.core.BagGrind
import coffee.crema.core.Bean
import coffee.crema.core.GrindScale
import coffee.crema.core.Grinder

/*
 * What Brew reads of the grinders and the bag grinds (#488): the active
 * grinder and its scale, the bag's saved grind for it, and the unsaved-grind
 * marker (DESIGN.md "Unsaved grind marker"). Pure reads of the state, so the
 * screens and the Save plan agree.
 */

/** The library file this device keeps, from this state: every row, grinders included, and both pointers. */
internal fun MainUiState.libraryFile(): BeanLibrary = BeanLibrary(beans, roasters, activeBeanId, grinders, activeGrinderId)

/** The grinders the pickers list: owned, not archived. */
val MainUiState.liveGrinders: List<Grinder> get() = grinders.filter { it.isLive }

/** Archived grinders (Devices → Grinder "Archived"), not tombstones. */
val MainUiState.archivedGrinders: List<Grinder> get() = grinders.filter { it.archivedAt != null && it.deletedAt == null }

/**
 * The active grinder as Brew shows it: on a remote the hub's, from its
 * snapshot (a remote's library is its own, review F3), else this device's
 * live record. Null = no grinder: the default scale.
 */
fun MainUiState.activeGrinder(): Grinder? =
    mirroredGrinder?.takeIf { it.id == activeGrinderId } ?: grinders.activeGrinder(activeGrinderId)

/** The scale Brew's grind uses: the active grinder's, else the default 0–20 in 0.1. */
fun MainUiState.activeGrindScale(): GrindScale = activeGrinder()?.scale ?: DEFAULT_GRIND_SCALE

/** The bag on Brew. */
fun MainUiState.activeBag(): Bean? = beans.firstOrNull { it.id == activeBeanId }

/** The bag's saved grind for the active grinder (the "no grinder" slot with none). */
fun MainUiState.savedGrindForBrew(): BagGrind? = activeBag()?.savedGrind(activeGrinder()?.id)

/** Brew's grind as text: the dial when set, else the bag's saved grind; null when neither. */
fun MainUiState.brewGrind(): String? = qcGrind?.let(::grindText) ?: savedGrindForBrew()?.grind

/** Where the stepper starts with no dial yet: the bag's saved grind, else 4.2 inside the scale. */
fun MainUiState.grindSeed(): Double {
    val scale = activeGrindScale()
    // Through the text, so 4.2f reads 4.2 and not 4.199999809.
    savedGrindForBrew()?.grind?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return it }
    val lo = minOf(scale.min, scale.max).toDouble()
    val hi = maxOf(scale.min, scale.max).toDouble()
    return DEFAULT_GRIND_SEED.coerceIn(lo, hi)
}

private const val DEFAULT_GRIND_SEED = 4.2

/** The unsaved-grind marker: Brew's dial differs from the bag's saved grind
 *  for the active grinder. [saved] null = nothing saved yet ("Bag —"). */
data class UnsavedGrind(val saved: String?) {
    /** "Bag 4.2" or "Bag —". */
    val label: String get() = "Bag ${saved ?: "—"}"
}

/** The marker when it shows: a bag is selected, the dial is set, and it
 *  differs from the bag's saved grind (or none is saved). No bag: none. */
fun MainUiState.unsavedGrind(): UnsavedGrind? {
    if (activeBag() == null) return null
    val dial = qcGrind ?: return null
    val saved = savedGrindForBrew()?.grind
    if (saved != null && !grindDiffers(grindText(dial), saved)) return null
    return UnsavedGrind(saved)
}

/** A scale bound or step as Devices and the pickers show it: "999", "0.1". */
fun scaleNumber(v: Float): String = if (v % 1f == 0f) String.format(java.util.Locale.ROOT, "%.0f", v) else v.toString()

/** "0–999 μm", or "0–20" with no unit. */
fun scaleRangeText(scale: GrindScale): String =
    "${scaleNumber(scale.min)}–${scaleNumber(scale.max)}" + scale.unit.trim().takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()

/** The newest shot grind on [lastGrinds] for [grinder]: stamped with its id, or an older row by its name. */
fun newestShotGrind(lastGrinds: List<coffee.crema.core.LastGrind>, grinder: Grinder): coffee.crema.core.LastGrind? =
    lastGrinds.filter { it.grind.isNotBlank() && (it.grinderId == grinder.id || (it.grinderId == null && it.grinder.trim().equals(grinder.name.trim(), ignoreCase = true))) }
        .maxByOrNull { it.completedAt }

/** One grinder row in the Brew pickers (wide header, phone swap section). */
data class GrinderPickRow(val grinder: Grinder, val starred: Boolean, val meta: String)

/**
 * The pickers' rows for [grinders] (the live ones) with bag [bag] selected:
 * "This bag's grinder · saved 14" on the starred one, "Saved 480 μm", or
 * "Nothing saved · last shot 32"; with no bag, the grinder's range.
 */
fun grinderPickRows(grinders: List<Grinder>, bag: Bean?, lastGrinds: List<coffee.crema.core.LastGrind>): List<GrinderPickRow> =
    grinders.map { g ->
        val saved = bag?.savedGrind(g.id)
        val starred = saved?.starred == true
        val meta = when {
            bag == null -> scaleRangeText(g.scale)
            saved != null && starred -> "This bag's grinder · saved ${coffee.crema.beans.grindLabel(saved.grind, g.scale)}"
            saved != null -> "Saved ${coffee.crema.beans.grindLabel(saved.grind, g.scale)}"
            else -> newestShotGrind(lastGrinds, g)?.let { "Nothing saved · last shot ${coffee.crema.beans.grindLabel(it.grind, g.scale)}" } ?: "Nothing saved"
        }
        GrinderPickRow(g, starred, meta)
    }

/** Five quick-pick values around [center] on [scale] (Quick Controls' grind chips), inside the scale. */
fun grindChips(center: Double, scale: GrindScale): List<Double> {
    val step = if (scale.step.isFinite() && scale.step > 0f) scale.step.toDouble() else 0.1
    val lo = minOf(scale.min, scale.max).toDouble()
    val hi = maxOf(scale.min, scale.max).toDouble()
    val snapped = (kotlin.math.round((center - lo) / step) * step + lo).coerceIn(lo, hi)
    val start = (snapped - 2 * step).coerceIn(lo, (hi - 4 * step).coerceAtLeast(lo))
    return (0 until 5).map { i -> kotlin.math.round((start + i * step) * 100) / 100 }.filter { it in lo..hi }.distinct()
}

/** The explanation behind the unsaved-grind marker (hover / long-press). */
fun MainUiState.unsavedGrindExplanation(): String? {
    val marker = unsavedGrind() ?: return null
    val bag = activeBag() ?: return null
    val grinder = activeGrinder()
    val scale = activeGrindScale()
    val now = brewGrind()?.let { coffee.crema.beans.grindLabel(it, scale) } ?: "—"
    val forGrinder = grinder?.name?.let { " for $it" }.orEmpty()
    val has = marker.saved?.let { "${bag.name} has ${coffee.crema.beans.grindLabel(it, scale)} saved$forGrinder." } ?: "${bag.name} has no grind saved$forGrinder yet."
    return "$has Brew is at $now for the next shot. Save in Quick Controls saves it."
}

/** What the type-or-pick box says under catalogue entry [id] (review 499): one
 *  without a verified range gets the default scale, set on Devices. */
fun catalogueOfferLine(id: String): String =
    if (coffee.crema.beans.grinderCatalogue.firstOrNull { it.id == id }?.scale != null) "Adds it to your grinders, with its scale"
    else "Adds it to your grinders · set its range on Devices"

/** What the type-or-pick box picked (#488, "Add a grinder: type or pick"). */
sealed interface GrinderPick {
    /** One of the user's grinders. */
    data class Owned(val id: String) : GrinderPick

    /** A catalogue entry: added to the user's grinders with its scale. */
    data class Catalogue(val id: String, val name: String) : GrinderPick

    /** An archived grinder: restored, same id, its bag grinds come back. */
    data class Archived(val id: String) : GrinderPick

    /** "+ Create “text”": a new grinder with the default scale. */
    data class Create(val name: String) : GrinderPick
}
