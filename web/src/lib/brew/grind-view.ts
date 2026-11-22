/**
 * `#lib/brew/grind-view.js` — what Brew shows about the grind (#488), read
 * from the core's `brew_grind_view` (round 2, REVIEW-CORE H2) so the header,
 * the stepper, the shot and Save agree: the value with the active grinder's
 * unit, or blank when not set; the unsaved-grind marker (DESIGN.md
 * "Unsaved grind marker"): shown when a bag is selected and Brew's grind
 * differs from the bag's saved grind for the active grinder; "Bag —" when
 * nothing is saved yet; no bag or no grind, no marker. The shell keeps only
 * the tooltip's wording.
 */
import type { Bean } from '#lib/bean/model.js';
import {
	brewGrindView,
	formatGrind,
	savedGrindRow,
	type Grinder,
	type GrindScale,
	type LastGrind
} from '#lib/bean/grinder.js';

export interface GrindView {
	/** The active grinder's scale, or the default. */
	readonly scale: GrindScale;
	/** "Grind 480 μm", or "Grind —" when not set. */
	readonly label: string;
	/** Brew's grind with its unit ("480 μm", "2.1.3"), or "—". */
	readonly value: string;
	/** Brew's grind as a shot records it, or `null` when not set. */
	readonly recorded: string | null;
	/** The stepper's number; `null` when not set or not a plain number (the stepper shows blank, or the text). */
	readonly dial: number | null;
	/** Where the stepper's first + or − goes when {@link dial} is `null`. */
	readonly startFromBlank: number;
	/** The marker ("Bag 4.2", "Bag —") while the grind is unsaved; else `null`. */
	readonly marker: string | null;
	/** What the marker means, for its tooltip and accessible name. */
	readonly markerTitle: string;
	/** The bag's saved grind for the active grinder, or `null` (the ringed chip). */
	readonly saved: string | null;
	/** Save's grind part: the value Save writes to the bag, or `null` (no dot on Save). */
	readonly saveGrind: string | null;
}

export function grindView(input: { bag: Bean | null; grinder: Grinder | null; brewGrind: string | null }): GrindView {
	const { bag, grinder } = input;
	const v = brewGrindView(input);
	const saved = v.saved ?? null;
	const who = grinder ? grinder.name : 'no grinder';
	const markerTitle =
		bag && v.marker
			? saved
				? `${bag.name} has ${formatGrind(saved, v.scale)} saved for ${who}. Brew is at ${v.display} for the next shot. Save in Quick Controls saves it.`
				: `${bag.name} has no grind saved for ${who}. Brew is at ${v.display} for the next shot. Save in Quick Controls saves it.`
			: '';
	return {
		scale: v.scale,
		label: `Grind ${v.display}`,
		value: v.display,
		recorded: v.value ?? null,
		dial: v.dial ?? null,
		startFromBlank: v.startFromBlank,
		marker: v.marker?.label ?? null,
		markerTitle,
		saved,
		saveGrind: v.saveGrind ?? null
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
