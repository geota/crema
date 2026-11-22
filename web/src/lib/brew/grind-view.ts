/**
 * `#lib/brew/grind-view.js` — what Brew shows about the grind (#488): the
 * value with the active grinder's unit, and the unsaved-grind marker
 * (DESIGN.md "Unsaved grind marker"): shown when a bag is selected and Brew's
 * grind differs from the bag's saved grind for the active grinder; "Bag —"
 * when nothing is saved yet; no bag, no marker.
 */
import type { Bean } from '#lib/bean/model.js';
import {
	DEFAULT_GRIND_SCALE,
	formatGrind,
	grindNumber,
	grindUnsaved,
	savedGrindRow,
	type Grinder,
	type GrindScale,
	type LastGrind
} from '#lib/bean/grinder.js';

export interface GrindView {
	/** The active grinder's scale, or the default. */
	readonly scale: GrindScale;
	/** "Grind 480 μm", or "Grind —" with no grind on Brew. */
	readonly label: string;
	/** Brew's grind with its unit ("480 μm"), or "—". */
	readonly value: string;
	/** The marker ("Bag 4.2", "Bag —") while the grind is unsaved; else `null`. */
	readonly marker: string | null;
	/** What the marker means, for its tooltip and accessible name. */
	readonly markerTitle: string;
	/** The bag's saved value for the active grinder, as a number (the ringed chip). */
	readonly savedNumber: number | null;
	/** Save would include the grind (the dot on Save). */
	readonly unsaved: boolean;
}

export function grindView(input: { bag: Bean | null; grinder: Grinder | null; brewGrind: number | null }): GrindView {
	const { bag, grinder, brewGrind } = input;
	const scale = grinder?.scale ?? DEFAULT_GRIND_SCALE;
	const value = formatGrind(brewGrind, scale);
	const row = savedGrindRow(bag, grinder?.id ?? null);
	const saved = row && row.grind.trim() !== '' ? row.grind.trim() : null;
	const unsaved = grindUnsaved(bag, grinder?.id ?? null, brewGrind);
	const who = grinder ? grinder.name : 'no grinder';
	const markerTitle =
		bag && unsaved
			? saved
				? `${bag.name} has ${formatGrind(saved, scale)} saved for ${who}. Brew is at ${value} for the next shot. Save in Quick Controls saves it.`
				: `${bag.name} has no grind saved for ${who}. Brew is at ${value} for the next shot. Save in Quick Controls saves it.`
			: '';
	return {
		scale,
		label: `Grind ${value}`,
		value,
		marker: unsaved ? `Bag ${saved ?? '—'}` : null,
		markerTitle,
		savedNumber: grindNumber(saved),
		unsaved
	};
}

/**
 * The picker row's line for `grinder` on the selected bag: "Saved 480 μm",
 * else "Nothing saved · last shot 32", else "Nothing saved"; with no bag,
 * the grinder's range. The starred grinder reads "This bag's grinder · …".
 */
export function grinderRowLine(grinder: Grinder, bag: Bean | null, lastGrinds: readonly LastGrind[]): string {
	const s = grinder.scale;
	if (!bag) return `${s.min}–${s.max}${s.unit ? ' ' + s.unit : ''}`;
	const row = savedGrindRow(bag, grinder.id);
	const star = row?.starred ? "This bag's grinder · " : '';
	if (row && row.grind.trim() !== '') return `${star}Saved ${formatGrind(row.grind, s)}`;
	const key = grinder.name.trim().toLowerCase();
	const last =
		lastGrinds.find((l) => l.grinderId === grinder.id) ??
		lastGrinds.find((l) => !l.grinderId && l.grinder.trim().toLowerCase() === key);
	return `${star}Nothing saved${last ? ` · last shot ${formatGrind(last.grind, s)}` : ''}`;
}

/**
 * The grind preset chips on `scale`: today's chips on the default scale,
 * else five steps around the middle, or around `around` (Brew's grind)
 * when it's inside the scale.
 */
export function grindChips(scale: GrindScale, around: number | null, count = 5): number[] {
	const { min, max, step } = scale;
	if (min === 0 && max === 20 && Math.abs(step - 0.1) < 1e-9) {
		return count === 4 ? [3.5, 4.0, 4.5, 5.0] : [3.8, 4.0, 4.2, 4.4, 4.6];
	}
	const st = step > 0 ? step : 1;
	// A chip a few steps apart, so the row spans a useful bit of the scale.
	const gap = st * Math.max(1, Math.round((max - min) / st / 50));
	const centre = around !== null && around >= min && around <= max ? around : (min + max) / 2;
	const snapped = min + Math.round((centre - min) / st) * st;
	const half = Math.floor(count / 2);
	const out: number[] = [];
	for (let i = -half; out.length < count; i++) {
		const v = Number((snapped + i * gap).toFixed(4));
		if (v >= min && v <= max) out.push(v);
		if (i > count * 4) break;
	}
	return out;
}
