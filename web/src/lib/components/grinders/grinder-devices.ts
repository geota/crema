/**
 * `grinder-devices` — the Devices → Grinder tab's logic (#488), kept out of
 * the component so it is testable on its own: reading the typed scale,
 * the preset a grinder came from, "Range not set", and the bags that saved a
 * grind for it. The rules about grinder records live in the core
 * (`#lib/bean/grinder.js`); this only shapes them for the tab.
 */
import {
	countsInThirds,
	DEFAULT_GRIND_SCALE,
	grinderCatalogue,
	grindNumber,
	grindScaleRefusal,
	SCALE_REFUSAL_TEXT,
	ScaleTier,
	type CatalogueGrinderInfo,
	type Grinder,
	type GrindScale
} from '#lib/bean/grinder.js';
import type { Bean } from '#lib/bean/model.js';

/** The four scale fields as typed. */
export interface ScaleDraft {
	min: string;
	max: string;
	step: string;
	unit: string;
}

/** A grinder's scale as the fields show it: a thirds step as "⅓" (the Fellow Ode), not 0.33333334. */
export function draftOf(scale: GrindScale): ScaleDraft {
	return { min: String(scale.min), max: String(scale.max), step: stepFieldText(scale), unit: scale.unit };
}

/** The Step field's text: "⅓" on a thirds scale (`de1_domain::counts_in_thirds`), else the stored step. */
export function stepFieldText(scale: GrindScale): string {
	return countsInThirds(scale) ? '⅓' : String(scale.step);
}

/**
 * A typed number ("0,5" reads as 0.5, "⅓" and "1/3" as a third through the
 * core's `grind_number`); NaN when blank or not a number.
 */
function numberOf(text: string): number {
	const typed = grindNumber(text);
	if (typed !== null) return typed;
	const raw = text.trim().replace(',', '.');
	return raw === '' ? Number.NaN : Number(raw);
}

/**
 * The typed scale, and why it is refused (`null` when it is usable): the
 * core's `grind_scale_refusal` (finite numbers, Lowest below Highest, Step
 * above 0 and no more than the range), so Devices refuses what Android does.
 */
export function parseScaleDraft(draft: ScaleDraft): { scale: GrindScale; error: string | null } {
	const scale: GrindScale = {
		min: numberOf(draft.min),
		max: numberOf(draft.max),
		step: numberOf(draft.step),
		unit: draft.unit.trim()
	};
	const refusal = grindScaleRefusal(scale);
	return { scale, error: refusal === null ? null : `${SCALE_REFUSAL_TEXT[refusal]}.` };
}

/** Whether two scales are the same. */
export function sameScale(a: GrindScale, b: GrindScale): boolean {
	return a.min === b.min && a.max === b.max && a.step === b.step && a.unit === b.unit;
}

/** The catalogue entry `grinder` came from, or `null`. */
export function presetOf(grinder: Grinder): CatalogueGrinderInfo | null {
	if (!grinder.presetId) return null;
	return grinderCatalogue().find((c) => c.id === grinder.presetId) ?? null;
}

/**
 * What the grinder header says while the scale is its preset's: "From the X
 * preset" for a verified range, "Typical range — check your dial" for a
 * typical one (`ScaleTier::Typical`, #501).
 */
export function presetLabel(preset: CatalogueGrinderInfo): string {
	return preset.tier === ScaleTier.Typical ? 'Typical range — check your dial' : `From the ${preset.name} preset`;
}

/** What the type-or-pick box says under a catalogue entry: its scale comes with it, typical or not, or is set on Devices. */
export function catalogueOfferLine(entry: CatalogueGrinderInfo | undefined): string {
	if (!entry?.scale) return 'Adds it to your grinders; set its range on Devices';
	return entry.tier === ScaleTier.Typical
		? 'Adds it to your grinders · Typical range — check your dial'
		: 'Adds it to your grinders, with its scale';
}

/** The verified scale of the preset `grinder` came from: what "Reset to preset" restores. */
export function presetScaleOf(grinder: Grinder): GrindScale | null {
	return presetOf(grinder)?.scale ?? null;
}

/**
 * "Range not set: check your grinder's dial": the grinder came from no
 * verified scale (a typed name, or a catalogue entry without one) and still
 * has the default scale it was given.
 */
export function rangeNotSet(grinder: Grinder): boolean {
	return presetScaleOf(grinder) === null && sameScale(grinder.scale, DEFAULT_GRIND_SCALE);
}

/** "0–999 μm", "0–20". */
export function scaleSummary(scale: GrindScale): string {
	return `${scale.min}–${scale.max}${scale.unit ? ` ${scale.unit}` : ''}`;
}

/** One bag that saved a grind for a grinder. */
export interface SavedOnBag {
	beanId: string;
	bagName: string;
	grind: string;
	starred: boolean;
}

/** The bags (tombstones left out) whose saved grinds have a row for `grinderId`. */
export function savedOnBags(beans: readonly Bean[], grinderId: string): SavedOnBag[] {
	const out: SavedOnBag[] = [];
	for (const b of beans) {
		if (b.deletedAt != null) continue;
		const row = b.grinds?.find((g) => g.grinderId === grinderId);
		if (!row) continue;
		out.push({ beanId: b.id, bagName: b.name.trim() || 'Untitled bag', grind: row.grind, starred: row.starred });
	}
	return out.sort((a, b) => a.bagName.localeCompare(b.bagName));
}

/** The catalogue for "Change preset", by name. */
export function presetChoices(): CatalogueGrinderInfo[] {
	return [...grinderCatalogue()].sort((a, b) => a.name.localeCompare(b.name));
}
