/**
 * `#lib/bean/grinder.js` — owned grinders and bag grinds (#488), over the
 * Rust core (`de1_domain::grinder`, `de1_domain::bag_grind`) so Android
 * behaves identically. The shell keeps the rows ({@link BeanLibraryStore});
 * every rule lives in the core:
 *
 *  - grinder records sit in the bean library next to the roasters; Remove
 *    archives, and adding a name or catalogue entry that matches an archived
 *    record brings it back with the same id;
 *  - the active grinder is a per-device pointer, not a setting, and an
 *    archived, deleted or unknown id is no grinder;
 *  - a bag's saved grinds change only on an explicit save.
 */

import type {
	AutoSaveInput,
	BagGrind,
	BagSelectInput,
	BagSelection,
	BrewGrindView,
	CatalogueGrinderInfo,
	Grinder,
	GrinderAdd,
	GrinderArchive,
	GrinderLibraryMigration,
	GrinderMatch,
	GrindSave,
	GrindScale,
	GrinderSwitchInput,
	GrindChip,
	LastGrind,
	QuickControlBounds
} from '#lib/core/crema-core.js';
import { ScaleRefusal, ScaleTier } from '#lib/core/crema-core.js';
import {
	activeGrinderJson,
	addGrinderJson,
	archiveGrinderJson,
	autoSaveShotGrindJson,
	bagGrindsToFixJson,
	brewGrindViewJson,
	coerceGrinder as wasmCoerceGrinder,
	countsInThirdsJson,
	formatGrindJson,
	grinderCatalogueJson,
	grindChipsJson,
	grindersForNamesJson,
	grindNumber as wasmGrindNumber,
	grindScaleRefusalJson,
	grindTextJson,
	matchGrindersJson,
	mergeGrinderEditJson,
	migrateGrinderLibraryJson,
	quickControlBoundsForGrinderJson,
	resolveBagSelectionJson,
	resolveGrinderSwitchJson,
	restoreGrinderJson,
	sameGrind as wasmSameGrind,
	saveBagGrindJson
} from '#lib/wasm/de1_wasm.js';
import type { Bean } from './model';

export type {
	BagGrind,
	BagSelection,
	BrewGrindView,
	CatalogueGrinderInfo,
	Grinder,
	GrinderAdd,
	GrinderMatch,
	GrindChip,
	GrindSave,
	GrindScale,
	LastGrind
};
export { ScaleRefusal, ScaleTier };

/** What the type-or-pick box answers: one of its matches, or "+ Create “<typed>”". */
export type GrinderChoice =
	| { kind: 'owned' | 'catalogue' | 'archived'; id: string; name: string }
	| { kind: 'create'; name: string };

/** The "no grinder" slot's id on a bag grind (`de1_domain::NO_GRINDER_ID`). */
export const NO_GRINDER_ID = '';

/** The scale Brew uses with no active grinder: 0–20, step 0.1, no unit. */
export const DEFAULT_GRIND_SCALE: GrindScale = { min: 0, max: 20, step: 0.1, unit: '' };

/** A stored grinder row, normalised, or `null` without a string id. */
export function coerceGrinder(raw: unknown): Grinder | null {
	const out = wasmCoerceGrinder(JSON.stringify(raw) ?? 'null');
	return out == null ? null : (JSON.parse(out) as Grinder);
}

/** Owned: not archived, not a tombstone. */
export function isLiveGrinder(g: Grinder): boolean {
	return g.archivedAt == null && g.deletedAt == null;
}

/** The active grinder for `activeId`, or `null` (archived, deleted, unknown or none). */
export function activeGrinder(grinders: readonly Grinder[], activeId: string | null): Grinder | null {
	const out = activeGrinderJson(JSON.stringify(grinders), activeId ?? '');
	return out === 'null' ? null : (JSON.parse(out) as Grinder);
}

/** The Quick Controls bounds with the grind range from `grinder`'s scale (the default without one). */
export function boundsForGrinder(grinder: Grinder | null): QuickControlBounds {
	return JSON.parse(quickControlBoundsForGrinderJson(grinder ? JSON.stringify(grinder) : 'null')) as QuickControlBounds;
}

/** The built-in grinder catalogue. Parsed once. */
let catalogue: CatalogueGrinderInfo[] | null = null;
export function grinderCatalogue(): CatalogueGrinderInfo[] {
	return (catalogue ??= JSON.parse(grinderCatalogueJson()) as CatalogueGrinderInfo[]);
}

/** The type-or-pick box's matches: owned (not in `excludeIds`), catalogue, archived. */
export function matchGrinders(query: string, grinders: readonly Grinder[], excludeIds: readonly string[] = []): GrinderMatch[] {
	return JSON.parse(matchGrindersJson(query, JSON.stringify(grinders), JSON.stringify(excludeIds))) as GrinderMatch[];
}

/** Add a grinder by catalogue id or typed name (reviving an archived one), or `null` when both are blank. */
export function addGrinder(grinders: readonly Grinder[], name: string, catalogueId: string, nowMs = Date.now()): GrinderAdd | null {
	const out = addGrinderJson(JSON.stringify(grinders), name, catalogueId, nowMs);
	return out === 'null' ? null : (JSON.parse(out) as GrinderAdd);
}

/** Archive `grinder`; the pointer afterwards is cleared when it was the active one. */
export function archiveGrinder(grinder: Grinder, activeId: string | null, nowMs = Date.now()): GrinderArchive {
	return JSON.parse(archiveGrinderJson(JSON.stringify(grinder), activeId ?? '', nowMs)) as GrinderArchive;
}

/** An archived grinder brought back, same id. */
export function restoreGrinder(grinder: Grinder, nowMs = Date.now()): Grinder {
	return JSON.parse(restoreGrinderJson(JSON.stringify(grinder), nowMs)) as Grinder;
}

/** A Devices edit's Save: the fields changed (`edited` vs `base`) onto `current`. */
export function mergeGrinderEdit(base: Grinder, edited: Grinder, current: Grinder, nowMs = Date.now()): Grinder {
	return JSON.parse(
		mergeGrinderEditJson(JSON.stringify(base), JSON.stringify(edited), JSON.stringify(current), nowMs)
	) as Grinder;
}

/** New grinder records for the names no grinder has yet (imports). */
export function grindersForNames(grinders: readonly Grinder[], names: readonly string[], nowMs = Date.now()): Grinder[] {
	return JSON.parse(grindersForNamesJson(JSON.stringify(grinders), JSON.stringify(names), nowMs)) as Grinder[];
}

/** The library migration: grinder records from the old names, and bags' old grind as a saved grind. */
export function migrateGrinderLibrary(
	grinders: readonly Grinder[],
	beans: readonly Bean[],
	grinderModel: string,
	nowMs = Date.now()
): GrinderLibraryMigration {
	return JSON.parse(
		migrateGrinderLibraryJson(JSON.stringify({ grinders, beans, grinderModel }), nowMs)
	) as GrinderLibraryMigration;
}

/** Brew's Save of `grind` to the (bag, grinder) row; `grinder` `null` = the "no grinder" slot. */
export function saveBagGrind(bean: Bean, grinder: Grinder | null, grind: string, nowMs = Date.now()): GrindSave & { bean: Bean } {
	return JSON.parse(
		saveBagGrindJson(JSON.stringify(bean), grinder ? JSON.stringify(grinder) : 'null', grind, nowMs)
	) as GrindSave & { bean: Bean };
}

/** The grinder ids of the rows the bag editor's Save refuses (an empty grind). */
export function bagGrindsToFix(bean: Bean): string[] {
	return JSON.parse(bagGrindsToFixJson(JSON.stringify(bean))) as string[];
}

/** The core's inputs with the shell's {@link Bean} (`null` where the core reads absent). */
type WithBag<T extends { bag?: unknown }> = Omit<T, 'bag'> & { bag?: Bean };

/** A bag selection as one change, with its Undo. */
export function resolveBagSelection(input: WithBag<BagSelectInput>): BagSelection {
	return JSON.parse(resolveBagSelectionJson(JSON.stringify(input))) as BagSelection;
}

/** The grind Brew takes on switching grinder, or `null` (it stays). */
export function resolveGrinderSwitch(input: WithBag<GrinderSwitchInput>): string | null {
	return JSON.parse(resolveGrinderSwitchJson(JSON.stringify(input))) as string | null;
}

/** Auto-save after a kept shot, or `null` when it doesn't apply. */
export function autoSaveShotGrind(input: WithBag<AutoSaveInput>, nowMs = Date.now()): (GrindSave & { bean: Bean }) | null {
	const out = autoSaveShotGrindJson(JSON.stringify(input), nowMs);
	return out === 'null' ? null : (JSON.parse(out) as GrindSave & { bean: Bean });
}

/**
 * The grinder record a typed shot grinder name names (#488, review M2): an
 * owned grinder of that name (trimmed, any case), else an archived one;
 * tombstones never. `null` when none does: the shot then has no grinder id.
 */
export function grinderIdForName(grinders: readonly Grinder[], name: string | null | undefined): string | null {
	const key = (name ?? '').trim().toLowerCase();
	if (!key) return null;
	const named = grinders.filter((g) => g.deletedAt == null && g.name.trim().toLowerCase() === key);
	return (named.find(isLiveGrinder) ?? named[0])?.id ?? null;
}

/**
 * The scale a shot's grind is on (history's grind stepper, as Android's
 * `shotGrindScale`): its grinder record's, by id, else by the typed name for a
 * shot from before grinder ids; the default scale when none matches.
 */
export function shotGrindScale(
	shot: { grinderId?: string | null; grinderModel?: string | null },
	grinders: readonly Grinder[]
): GrindScale {
	const id = shot.grinderId ?? '';
	if (id !== '') {
		const byId = grinders.find((g) => g.id === id);
		if (byId) return byId.scale;
	} else {
		const name = (shot.grinderModel ?? '').trim().toLowerCase();
		const byName = name ? grinders.find((g) => g.name.trim().toLowerCase() === name) : undefined;
		if (byName) return byName.scale;
	}
	return DEFAULT_GRIND_SCALE;
}

/** The bag's saved row for `grinderId` (`NO_GRINDER_ID` for none), if any. */
export function savedGrindRow(bean: Bean | null, grinderId: string | null): BagGrind | null {
	const id = grinderId ?? NO_GRINDER_ID;
	return bean?.grinds?.find((g) => g.grinderId === id) ?? null;
}

/** The largest grind read as a number; past it a grind stays text (`de1_domain::MAX_GRIND_NUMBER`). */
export const MAX_GRIND_NUMBER = 1e6;

/**
 * A typed or stored grind as a number, or `null` when it isn't a plain
 * number ("6 + a tooth", "2.1.3") (`de1_domain::grind_number`, REVIEW-CORE
 * H1): plain decimals ("0,5" reads as 0.5) and thirds ("4⅓", "4 1/3"); no
 * hex, no `Infinity`, no exponent, at most 32 characters and no more than
 * {@link MAX_GRIND_NUMBER}.
 */
export function grindNumber(grind: string | null | undefined): number | null {
	return wasmGrindNumber(grind ?? '') ?? null;
}

/** Whether `scale` counts in thirds (the Fellow Ode, `de1_domain::counts_in_thirds`): 4, 4⅓, 4⅔. */
export function countsInThirds(scale: GrindScale): boolean {
	return countsInThirdsJson(JSON.stringify(scale));
}

/** A scale's step as Devices shows it: "⅓" on a thirds scale, "0.05", "1" (`grind_text` of the step). */
export function stepText(scale: GrindScale): string {
	return grindText(scale.step, scale);
}

/**
 * A dial number as a bag stores it and a shot records it, on `scale`
 * (`de1_domain::grind_text`): whole numbers without a decimal, otherwise the
 * step's decimals, thirds as 4⅓ on a thirds scale; never rounded to one
 * decimal (REVIEW-FINAL M2).
 */
export function grindText(dial: number, scale: GrindScale | null = null): string {
	return grindTextJson(dial, JSON.stringify(scale ?? DEFAULT_GRIND_SCALE));
}

/** Whether two grinds are the same (`de1_domain::same_grind`): as numbers when both parse, else trimmed text. */
export function sameGrind(a: string, b: string): boolean {
	return wasmSameGrind(a, b);
}

/** `grind` with the scale's unit (`de1_domain::format_grind`): "480 μm", "2.1.3" as typed, "—" for none. */
export function formatGrind(grind: string | null | undefined, scale: GrindScale | null): string {
	return formatGrindJson(grind ?? '', JSON.stringify(scale ?? DEFAULT_GRIND_SCALE));
}

/** Why a typed scale can't be stored (`de1_domain::grind_scale_refusal`), or `null` when it is usable. */
export function grindScaleRefusal(scale: GrindScale): ScaleRefusal | null {
	return JSON.parse(grindScaleRefusalJson(JSON.stringify(scale))) as ScaleRefusal | null;
}

/** What each {@link ScaleRefusal} tells the user (Android's wording). */
export const SCALE_REFUSAL_TEXT: Record<ScaleRefusal, string> = {
	[ScaleRefusal.NotANumber]: 'Enter a number in each field',
	[ScaleRefusal.LowestNotBelowHighest]: 'Lowest must be below Highest',
	[ScaleRefusal.StepNotPositive]: 'Step must be more than 0',
	[ScaleRefusal.StepTooLarge]: 'Step must fit between Lowest and Highest'
};

/**
 * What Brew's grind is (`de1_domain::brew_grind_view`, DESIGN "No grind set
 * is its own state"): the header's value, what a shot records, the stepper's
 * number or blank, where the first + or − goes from blank, the marker and
 * Save's grind part. `brewGrind` `null` = not set.
 */
export function brewGrindView(input: { bag: Bean | null; grinder: Grinder | null; brewGrind: string | null }): BrewGrindView {
	const core = {
		bag: input.bag ?? undefined,
		grinder: input.grinder ?? undefined,
		brewGrind: input.brewGrind ?? undefined
	};
	return JSON.parse(brewGrindViewJson(JSON.stringify(core))) as BrewGrindView;
}

/** Where a stepper's first + or − goes from a blank grind on `scale`: its middle, on its step. */
export function grindStartFromBlank(scale: GrindScale | null): number {
	return brewGrindView({ bag: null, grinder: scale ? ({ scale } as Grinder) : null, brewGrind: null }).startFromBlank;
}

/**
 * Up to `count` Quick Controls chips on `scale`, single steps around
 * `around` (Brew's grind) (`de1_domain::grind_chips`, REVIEW-CORE L1). None
 * when the grind is unset or isn't a plain number.
 */
export function grindChips(scale: GrindScale, around: string | null, count: number): GrindChip[] {
	return JSON.parse(grindChipsJson(JSON.stringify(scale), around ?? undefined, count)) as GrindChip[];
}

/**
 * The newest grind per grinder on a bag, newest first, from its rows (the
 * history's `last_grinds_for_bag`, for the in-memory rows): by grinder id,
 * and by name (trimmed, any case) for rows from before grinder ids.
 */
export function lastGrindsOf(
	rows: readonly {
		completedAt: number;
		id: string;
		grinderId?: string | null;
		grinderModel?: string | null;
		metadata?: { grinderSetting?: string | null } | null;
	}[]
): LastGrind[] {
	const key = (n: string) => n.trim().toLowerCase();
	const sorted = [...rows].sort((a, b) => b.completedAt - a.completedAt || (a.id < b.id ? 1 : a.id > b.id ? -1 : 0));
	const out: LastGrind[] = [];
	for (const s of sorted) {
		const id = typeof s.grinderId === 'string' && s.grinderId.trim() !== '' ? s.grinderId : undefined;
		const name = typeof s.grinderModel === 'string' ? s.grinderModel : '';
		if (id === undefined && typeof s.grinderModel !== 'string') continue;
		const raw = s.metadata?.grinderSetting;
		const grind = typeof raw === 'number' ? String(raw) : typeof raw === 'string' && raw.trim() !== '' ? raw : null;
		if (grind === null) continue;
		const seen =
			id !== undefined
				? out.some((l) => l.grinderId === id)
				: key(name) === '' || out.some((l) => l.grinderId === undefined && key(l.grinder) === key(name));
		if (seen) continue;
		out.push({ grinderId: id, grinder: name, grind, completedAt: s.completedAt });
	}
	return out;
}
