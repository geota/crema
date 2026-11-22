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
    grindNumber(savedGrindForBrew()?.grind)?.let { return it.toDouble() }
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
