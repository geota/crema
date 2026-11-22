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
	grinderPickRows,
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

/** One grinder row in Brew's picker: its line and whether the bag stars it. */
export interface GrinderRowView {
	readonly id: string;
	readonly name: string;
	/** "Saved 480 μm", "Nothing saved · last shot 32", "Nothing saved"; the range with no bag. */
	readonly line: string;
	/** The selected bag's starred grinder ("This bag's grinder · …"). */
	readonly starred: boolean;
}

/**
 * The picker rows for `grinders` on the selected bag, from the core's
 * `grinder_pick_rows` (REVIEW-CORE M1: the lookups selecting the bag uses,
 * so an old shot stamped "Niche" counts for the Niche Zero). The shell only
 * words them.
 */
export function grinderRowViews(
	grinders: readonly Grinder[],
	bag: Bean | null,
	lastGrinds: readonly LastGrind[]
): GrinderRowView[] {
	const rows = grinderPickRows(grinders, bag, lastGrinds);
	return grinders.map((g, i) => {
		const r = rows[i];
		const s = g.scale;
		let line: string;
		if (!bag) line = `${s.min}–${s.max}${s.unit ? ' ' + s.unit : ''}`;
		else {
			const star = r?.starred ? "This bag's grinder · " : '';
			if (r?.saved != null) line = `${star}Saved ${formatGrind(r.saved, s)}`;
			else line = `${star}Nothing saved${r?.lastShot != null ? ` · last shot ${formatGrind(r.lastShot, s)}` : ''}`;
		}
		return { id: g.id, name: g.name, line, starred: !!r?.starred };
	});
}
