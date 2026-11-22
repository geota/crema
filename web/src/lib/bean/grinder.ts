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
	CatalogueGrinderInfo,
	Grinder,
	GrinderAdd,
	GrinderArchive,
	GrinderLibraryMigration,
	GrinderMatch,
	GrindSave,
	GrindScale,
	GrinderSwitchInput,
	LastGrind,
	QuickControlBounds
} from '#lib/core/crema-core.js';
import {
	activeGrinderJson,
	addGrinderJson,
	archiveGrinderJson,
	autoSaveShotGrindJson,
	bagGrindsToFixJson,
	coerceGrinder as wasmCoerceGrinder,
	grinderCatalogueJson,
	grindersForNamesJson,
	matchGrindersJson,
	mergeGrinderEditJson,
	migrateGrinderLibraryJson,
	quickControlBoundsForGrinderJson,
	resolveBagSelectionJson,
	resolveGrinderSwitchJson,
	restoreGrinderJson,
	saveBagGrindJson
} from '#lib/wasm/de1_wasm.js';
import type { Bean } from './model';

export type {
	BagGrind,
	BagSelection,
	CatalogueGrinderInfo,
	Grinder,
	GrinderAdd,
	GrinderMatch,
	GrindSave,
	GrindScale,
	LastGrind
};

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

/** The bag's saved row for `grinderId` (`NO_GRINDER_ID` for none), if any. */
export function savedGrindRow(bean: Bean | null, grinderId: string | null): BagGrind | null {
	const id = grinderId ?? NO_GRINDER_ID;
	return bean?.grinds?.find((g) => g.grinderId === id) ?? null;
}

/** A grind as a number, or `null` when it isn't one ("6 + a tooth"). */
export function grindNumber(grind: string | null | undefined): number | null {
	const raw = (grind ?? '').trim().replace(',', '.');
	if (!raw) return null;
	const n = Number(raw);
	return Number.isFinite(n) ? n : null;
}

/** The dial's value as a bag stores it: whole numbers without a decimal. */
export function grindText(dial: number): string {
	if (Number.isInteger(dial)) return String(dial);
	// The dial moves in its scale's steps: at most two decimals, trailing zeros dropped.
	return String(Number(dial.toFixed(2)));
}

/**
 * What the grind dial shows with no grind on Brew: today's 4.2 on the default
 * scale, else the scale's middle on its step. A placeholder, never a grind.
 */
export function grindPlaceholder(scale: GrindScale): number {
	if (scale.min <= 4.2 && 4.2 <= scale.max && scale.max <= 20) return 4.2;
	const mid = (scale.min + scale.max) / 2;
	const step = scale.step > 0 ? scale.step : 1;
	return Number((scale.min + Math.round((mid - scale.min) / step) * step).toFixed(3));
}

/** `grind` with the scale's unit: "480 μm", "14", "—" for none. */
export function formatGrind(grind: string | number | null | undefined, scale: GrindScale | null): string {
	if (grind == null || (typeof grind === 'string' && grind.trim() === '')) return '—';
	const text = typeof grind === 'number' ? grindText(grind) : grind.trim();
	const unit = scale?.unit?.trim() ?? '';
	// A value that isn't a plain number is shown as typed, without a unit.
	return unit && grindNumber(text) !== null ? `${text} ${unit}` : text;
}

/**
 * Whether Brew's grind differs from what the bag saved for the active
 * grinder: the unsaved-grind marker. No bag, or no grind on Brew: no marker.
 */
export function grindUnsaved(bean: Bean | null, grinderId: string | null, brewGrind: number | null): boolean {
	if (!bean || brewGrind === null) return false;
	const row = savedGrindRow(bean, grinderId);
	if (!row || row.grind.trim() === '') return true;
	const saved = grindNumber(row.grind);
	return saved === null ? row.grind.trim() !== grindText(brewGrind) : saved !== brewGrind;
}

/** Whether a typed scale is usable: finite numbers, min below max, a positive step. */
export function validScale(scale: GrindScale): boolean {
	const { min, max, step } = scale;
	return [min, max, step].every((n) => typeof n === 'number' && Number.isFinite(n)) && min < max && step > 0;
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
