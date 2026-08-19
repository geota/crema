package coffee.crema.beans

import coffee.crema.core.Bean
import coffee.crema.core.BeanMix
import coffee.crema.core.BeanOrigin
import coffee.crema.core.BeanRoastType

/** Bag-size quick-pick presets (grams), shared by both bean editors (issue 31). */
val BAG_PRESETS = listOf(113, 227, 250, 340, 454, 1000)

/**
 * The editable bean fields the editor forms collect (tablet `BeanEditScreen` +
 * phone `PhoneBeanEditScreen`) — the raw control state before it is applied onto
 * a [Bean]. Kept as plain values so [applyBeanEdits] stays a pure mapping with
 * the trim / ifBlank / freeze-window semantics in one place.
 */
data class BeanDraft(
    val name: String,
    val roast: Int,
    val mixSel: String,
    val roastTypeSel: String,
    val roasted: String,
    val opened: String,
    val frozen: Boolean,
    val archived: Boolean,
    val decaf: Boolean,
    val pinned: Boolean,
    val bagSize: Double,
    val remaining: Double,
    val country: String,
    val region: String,
    val farm: String,
    val farmer: String,
    val variety: String,
    val elevation: String,
    val processing: String,
    val harvestTime: String,
    val grinder: String,
    val grind: String,
    val linkedProfileId: String?,
    val rating: Int,
    val qualityScore: String,
    val tastingNotes: String,
    val placeOfPurchase: String,
    /** Raw text from the cost field — parsed by [applyBeanEdits] via [parseCost]. */
    val cost: String,
    val url: String,
    val notes: String,
    val tags: List<String>,
)

/**
 * Apply the editor [draft] onto [b], returning the updated [Bean] — the
 * field-for-field save mapping both bean editors used to inline verbatim. Blank
 * text fields collapse to null (name/mix keep the prior value instead).
 *
 * The freeze/defrost transition preserves the freeze-window history (web
 * semantics — see `MainViewModel.defrostBean`): unchecking the switch on a
 * frozen bag DEFROSTS it (stamps `defrostedOn`, keeps `frozenOn`); re-freezing
 * stamps a fresh `frozenOn`. (`isFrozen` is the same-package Bean extension.)
 */
fun applyBeanEdits(b: Bean, draft: BeanDraft): Bean = b.copy(
    name = draft.name.trim().ifBlank { b.name },
    roastLevel = draft.roast.toUByte(),
    mix = BeanMix.entries.firstOrNull { it.string == draft.mixSel } ?: b.mix,
    roastType = draft.roastTypeSel.ifBlank { null }?.let { v -> BeanRoastType.entries.firstOrNull { it.string == v } },
    roastedOn = draft.roasted.ifBlank { null },
    openedOn = draft.opened.ifBlank { null },
    frozenOn = when {
        draft.frozen && b.isFrozen -> b.frozenOn
        draft.frozen -> java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
        else -> b.frozenOn
    },
    defrostedOn = when {
        draft.frozen -> null
        b.isFrozen -> java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
        else -> b.defrostedOn
    },
    archivedAt = if (draft.archived) (b.archivedAt ?: System.currentTimeMillis()) else null,
    decaf = draft.decaf,
    favourite = draft.pinned,
    bagSize = draft.bagSize.toFloat(),
    remaining = draft.remaining.toFloat(),
    origin = (b.origin ?: BeanOrigin()).copy(
        country = draft.country.ifBlank { null },
        region = draft.region.ifBlank { null },
        farm = draft.farm.ifBlank { null },
        farmer = draft.farmer.ifBlank { null },
        variety = draft.variety.ifBlank { null },
        elevation = draft.elevation.ifBlank { null },
        processing = draft.processing.ifBlank { null },
        harvestTime = draft.harvestTime.ifBlank { null },
    ),
    grinder = draft.grinder.trim(),
    grinderSetting = draft.grind.trim(),
    linkedProfileId = draft.linkedProfileId,
    rating = draft.rating.coerceIn(0, 5).toUByte(),
    qualityScore = draft.qualityScore.trim(),
    tastingNotes = draft.tastingNotes,
    placeOfPurchase = draft.placeOfPurchase.ifBlank { null },
    cost = parseCost(draft.cost),
    url = draft.url.ifBlank { null },
    notes = draft.notes,
    tags = draft.tags.toList().ifEmpty { null },
)

/**
 * Parse the cost field's raw text into the stored currency-less number.
 * Tolerates a comma decimal separator (`"12,50"`); blank, non-numeric, or
 * negative input clears the field to null (web editor parity).
 */
fun parseCost(raw: String): Float? =
    raw.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f }

/**
 * The cost field's initial editor text for a stored value — `""` when
 * unrecorded, whole numbers without the trailing `.0`. Always `.`-decimal
 * (Float.toString), so the text round-trips through [parseCost] unchanged.
 */
fun costText(cost: Float?): String = when {
    cost == null -> ""
    cost % 1f == 0f && kotlin.math.abs(cost) < 1e7f -> cost.toLong().toString()
    else -> cost.toString()
}
