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
	MigrationGate,
	GrinderMatch,
	GrinderPickRow,
	LastGrindRow,
	SelectionChange,
	SelectionState,
	StagedGrinderPick,
	StagedGrindersSave,
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
	addAnotherGrinderJson,
	addGrinderJson,
	archiveGrinderJson,
	autoSaveShotGrindJson,
	bagEditorGrindersJson,
	bagGrindsToFixJson,
	bagSelectionUndoJson,
	brewGrindViewJson,
	cleanGrinderName as wasmCleanGrinderName,
	coerceGrinder as wasmCoerceGrinder,
	countsInThirdsJson,
	formatGrindJson,
	grinderCatalogueJson,
	grinderHasVariableSpeed,
	grinderNamedJson,
	grindChipsJson,
	grinderPickRowsJson,
	grindersForNamesJson,
	grindNumber as wasmGrindNumber,
	grindScaleRefusalJson,
	grindTextJson,
	lastGrindsFromRowsJson,
	matchGrindersJson,
	mergeGrinderEditJson,
	migrateGrinderLibraryJson,
	newBagGrindRowJson,
	newestShotGrindJson,
	quickControlBoundsForGrinderJson,
	resolveBagSelectionJson,
	resolveGrinderSwitchJson,
	restoreGrinderJson,
	sameGrind as wasmSameGrind,
	saveBagGrindJson,
	splitVisualizerGrindJson,
	stageGrinderPickJson,
	stagedGrindersOnSaveJson
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
	GrinderPickRow,
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

/**
 * A grinder name as stored (`de1_domain::clean_grinder_name`, REVIEW-513
 * follow-up 1): control characters, newlines included, become one space, and
 * the ends are trimmed. Empty for a name with nothing visible.
 */
export function cleanGrinderName(name: string): string {
	return wasmCleanGrinderName(name);
}

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

/**
 * Add **another** grinder of a model already owned (#488 follow-up 18: two
 * Niche Zeros), for when {@link addGrinder} answers `existing`: an archived
 * record of that model comes back, else a new one named "Niche Zero 2" with
 * its own id. `null` as for {@link addGrinder}.
 */
export function addAnotherGrinder(grinders: readonly Grinder[], name: string, catalogueId: string, nowMs = Date.now()): GrinderAdd | null {
	const out = addAnotherGrinderJson(JSON.stringify(grinders), name, catalogueId, nowMs);
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
	gate: MigrationGate,
	nowMs = Date.now()
): GrinderLibraryMigration {
	return JSON.parse(
		migrateGrinderLibraryJson(JSON.stringify({ grinders, beans, grinderModel, gate }), nowMs)
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

/**
 * A bag selection's Undo (REVIEW-CORE H4, FINAL S9): only the parts that
 * still hold what the selection set, so a dial, grinder or profile change
 * made since stands; with a shot in progress the profile stays.
 */
export function bagSelectionUndo(selection: BagSelection, now: SelectionState): SelectionChange {
	return JSON.parse(bagSelectionUndoJson(JSON.stringify({ selection, now }))) as SelectionChange;
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
 * The grinder a typed name names, through the core's `grinder_named`
 * (REVIEW-FINAL-2 N6): one with exactly that name first (trimmed, any case;
 * live, then archived), then through the catalogue, so "Niche" names an owned
 * "Niche Zero"; tombstones never. `null` when none does. The record returned
 * is the caller's own (same object), found by the core's answer's id.
 */
export function grinderNamed(grinders: readonly Grinder[], name: string | null | undefined): Grinder | null {
	if (!(name ?? '').trim()) return null;
	const named = JSON.parse(grinderNamedJson(JSON.stringify(grinders), name ?? '')) as Grinder | null;
	return named ? (grinders.find((g) => g.id === named.id) ?? named) : null;
}

/**
 * The grinder record a typed shot grinder name names (#488, review M2;
 * {@link grinderNamed}). `null` when none does: the shot then has no
 * grinder id.
 */
export function grinderIdForName(grinders: readonly Grinder[], name: string | null | undefined): string | null {
	return grinderNamed(grinders, name)?.id ?? null;
}

/**
 * The scale a shot's grind is on (history's grind stepper, as Android's
 * `shotGrindScale`): its grinder record's, by id, else, for a shot from
 * before grinder ids, the grinder its typed name names ({@link grinderNamed}:
 * an old "Niche" shot is on the Niche Zero's scale); the default scale when
 * none matches.
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
		const byName = grinderNamed(grinders, shot.grinderModel);
		if (byName) return byName.scale;
	}
	return DEFAULT_GRIND_SCALE;
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

/**
 * The keyboard a grind field opens on `scale`: the full keyboard on a thirds
 * scale, where "4 1/3" needs a space and a slash; the decimal number pad on
 * every other scale.
 */
export function grindInputMode(scale: GrindScale | null): 'text' | 'decimal' {
	return scale && countsInThirds(scale) ? 'text' : 'decimal';
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

/**
 * Whether a grinder's catalogue entry has a variable speed
 * (`de1_domain::grinder_catalogue::has_variable_speed`): Brew shows the speed
 * beside a followed grind, and the shot editor has an RPM field.
 */
export function hasVariableSpeed(grinder: Pick<Grinder, 'presetId'> | null | undefined): boolean {
	return grinderHasVariableSpeed(grinder?.presetId || undefined);
}

/**
 * A grind text with its trailing speed set to `rpm` (REVIEW-554: editing a
 * shot's RPM): "15 1200rpm" with 1100 is "15 1100rpm", never "15 1200rpm
 * 1100rpm"; with no speed, "15 1200rpm" is "15". A text that names no speed
 * ("480", "18 clicks") is returned as it is: the speed rides beside it
 * (`grindRpm`) and Visualizer gets "480 1100rpm" (owner decision 6). The split
 * is the core's (`split_visualizer_grind`).
 */
export function withGrindRpm(text: string | null | undefined, rpm: number | null): string | null {
	if (text == null) return null;
	const { setting, grindRpm } = JSON.parse(splitVisualizerGrindJson(text)) as {
		setting: string;
		grindRpm: number | null;
	};
	if (grindRpm == null) return text;
	const next = [setting, rpm != null ? `${rpm}rpm` : ''].filter(Boolean).join(' ');
	return next === '' ? null : next;
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

/** A shot row as the shell holds it, for {@link lastGrindsFromRows}. */
export interface ShotGrindRow {
	completedAt: number;
	id: string;
	grinderId?: string | null;
	grinderModel?: string | null;
	metadata?: { grinderSetting?: string | number | null } | null;
}

/**
 * The newest grind per grinder on a bag, newest first, from its rows
 * (`de1_domain::last_grinds_from_rows`, REVIEW-CORE M1: the grouping the
 * history's `last_grinds_for_bag` applies): by grinder id, and by name
 * (trimmed, any case) for rows from before grinder ids. An old numeric grind
 * reads as its text.
 */
export function lastGrindsFromRows(rows: readonly ShotGrindRow[]): LastGrind[] {
	const core: LastGrindRow[] = rows.map((s) => {
		const grind = s.metadata?.grinderSetting;
		return {
			completedAt: s.completedAt,
			id: s.id,
			grinderId: typeof s.grinderId === 'string' ? s.grinderId : undefined,
			grinderModel: typeof s.grinderModel === 'string' ? s.grinderModel : undefined,
			// The core reads a stored number as its text.
			grind: (typeof grind === 'string' || typeof grind === 'number' ? grind : undefined) as string | undefined
		};
	});
	return JSON.parse(lastGrindsFromRowsJson(JSON.stringify(core))) as LastGrind[];
}

/**
 * The newest shot grind on a bag for `grinder` among its `lastGrinds`
 * (`de1_domain::newest_shot_grind`): a row stamped with its id, or an older
 * row by name through the catalogue's aliases ("Niche" finds the Niche
 * Zero). `null` when none.
 */
export function newestShotGrind(lastGrinds: readonly LastGrind[], grinder: Grinder): LastGrind | null {
	return JSON.parse(newestShotGrindJson(JSON.stringify({ lastGrinds, grinder }))) as LastGrind | null;
}

/**
 * Brew's grinder picker rows for `grinders` with `bag` selected (`null` = no
 * bag), from the bag's `lastGrinds` (`de1_domain::grinder_pick_rows`): the
 * same lookups selecting the bag uses, so a row never says "Nothing saved"
 * where selecting the bag loads a grind.
 */
export function grinderPickRows(grinders: readonly Grinder[], bag: Bean | null, lastGrinds: readonly LastGrind[]): GrinderPickRow[] {
	return JSON.parse(grinderPickRowsJson(JSON.stringify({ grinders, bag, lastGrinds }))) as GrinderPickRow[];
}

/**
 * The bag editor's new row for `grinder` (`de1_domain::new_bag_grind_row`):
 * its grind starts as the newest shot grind on the bag with that grinder,
 * else empty. Not starred.
 */
export function newBagGrindRow(grinder: Grinder, lastGrinds: readonly LastGrind[]): BagGrind {
	return JSON.parse(newBagGrindRowJson(JSON.stringify({ grinder, lastGrinds }))) as BagGrind;
}

// ── The bag editor's Add box (FINAL S7): staged in the draft until Save ──

/** The grinders as the bag editor sees them: the stored records with its staged ones over them. */
export function bagEditorGrinders(stored: readonly Grinder[], staged: readonly Grinder[]): Grinder[] {
	return JSON.parse(bagEditorGrindersJson(JSON.stringify(stored), JSON.stringify(staged))) as Grinder[];
}

/**
 * A type-or-pick choice in the bag editor, staged: the grinder for the new
 * row and the draft's staged records afterwards. Nothing is stored and the
 * active grinder stays. `null` when the choice names nothing.
 */
export function stageGrinderPick(
	grinders: readonly Grinder[],
	staged: readonly Grinder[],
	choice: GrinderChoice,
	nowMs = Date.now()
): StagedGrinderPick | null {
	const input = {
		grinders,
		staged,
		kind: choice.kind === 'create' ? undefined : choice.kind,
		id: choice.kind === 'create' ? '' : choice.id,
		name: choice.name
	};
	return JSON.parse(stageGrinderPickJson(JSON.stringify(input), nowMs)) as StagedGrinderPick | null;
}

/**
 * What the bag editor's Save writes for its staged grinders: the records to
 * upsert (only those the saved bag has a row for) and, when no live grinder
 * is active, the one to make active.
 */
export function stagedGrindersOnSave(
	grinders: readonly Grinder[],
	staged: readonly Grinder[],
	bag: Bean,
	activeGrinderId: string | null,
	nowMs = Date.now()
): StagedGrindersSave {
	return JSON.parse(
		stagedGrindersOnSaveJson(
			JSON.stringify({ grinders, staged, bag, activeGrinderId: activeGrinderId ?? undefined }),
			nowMs
		)
	) as StagedGrindersSave;
}
