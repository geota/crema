/**
 * `grinder-devices` — the Devices → Grinder tab's logic (#488), kept out of
 * the component so it is testable on its own: reading the typed scale,
 * the preset a grinder came from, "Range not set", and the bags that saved a
 * grind for it. The rules about grinder records live in the core
 * (`#lib/bean/grinder.js`); this only shapes them for the tab.
 */
import {
	DEFAULT_GRIND_SCALE,
	grinderCatalogue,
	validScale,
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

/** A grinder's scale as the fields show it. */
export function draftOf(scale: GrindScale): ScaleDraft {
	return { min: String(scale.min), max: String(scale.max), step: String(scale.step), unit: scale.unit };
}

/** A typed number ("0,5" reads as 0.5); NaN when blank or not a number. */
function numberOf(text: string): number {
	const raw = text.trim().replace(',', '.');
	return raw === '' ? Number.NaN : Number(raw);
}

/**
 * The typed scale, and why it is refused (`null` when it is usable). Gated
 * on {@link validScale}: finite numbers, Lowest below Highest, Step above 0.
 */
export function parseScaleDraft(draft: ScaleDraft): { scale: GrindScale; error: string | null } {
	const scale: GrindScale = {
		min: numberOf(draft.min),
		max: numberOf(draft.max),
		step: numberOf(draft.step),
		unit: draft.unit.trim()
	};
	if (validScale(scale)) return { scale, error: null };
	let error: string;
	if (![scale.min, scale.max, scale.step].every(Number.isFinite)) {
		error = 'Enter a number in Lowest, Highest and Step.';
	} else if (scale.min >= scale.max) {
		error = 'Lowest must be below Highest.';
	} else {
		error = 'Step must be above 0.';
	}
	return { scale, error };
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
