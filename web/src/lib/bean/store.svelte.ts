/**
 * `#lib/bean/store` — the bean library store.
 *
 * Backs `/beans` (the library + roaster directory) and the brew-page
 * bean picker. Two reactive lists — beans and roasters — plus an
 * `activeBeanId` pointer for the brew-page selection. All four live in
 * `localStorage` because Crema's web shell is a static, client-only PWA.
 *
 * Migration: on first load with no `crema.beans.v1` payload, the store
 * reads the legacy `crema.bean.current.v1` single-bean record (the
 * pre-library shape from the brew-page card) and promotes it into the
 * library as one favourited bean + (optional) roaster row. After the
 * migration the legacy key is left in place so a roll-back to the old
 * shell keeps reading the old shape — a deliberately defensive choice
 * for the static PWA.
 *
 * The store is a Svelte 5 `$state` class; obtain the singleton with
 * {@link getBeanLibraryStore} or the legacy alias {@link getBeanStore}.
 */

import { getSettingsStore } from '#lib/settings/store.svelte.js';
import {
	followOtherTabs,
	storeHealth,
	isIdOrNull,
	isJsonObject,
	keepSkippedRows,
	readJson,
	readStore,
	writeJson,
	writeJsonChecked
} from '#lib/utils/storage.js';
import {
	credit_shot_debit,
	debit_remaining,
	debit_taken,
	resettle_remaining,
	resettled_bag_debit
} from '#lib/wasm/de1_wasm.js';
import {
	type Grinder,
	type GrindSave,
	addGrinder as coreAddGrinder,
	archiveGrinder as coreArchiveGrinder,
	activeGrinder as coreActiveGrinder,
	coerceGrinder,
	grindersForNames,
	isLiveGrinder,
	mergeGrinderEdit,
	migrateGrinderLibrary,
	restoreGrinder as coreRestoreGrinder,
	saveBagGrind as coreSaveBagGrind
} from './grinder';
import {
	type Bean,
	type Roaster,
	blankBean,
	blankRoaster,
	coerceBean,
	coerceRoaster,
	migrateLegacyCurrentBean
} from './model';
import { getBeanImageStore } from './image-storage';

// ── localStorage keys (versioned envelope) ─────────────────────────────

/** Library envelope key — beans + roasters + schemaVersion. */
const LIBRARY_KEY = 'crema.beans.v1';
/** The library's name in a storage notice. */
const LIBRARY_WHAT = 'bean library';
/** Active bean id — the brew-page picker's pointer. */
const ACTIVE_KEY = 'crema.beans.activeBeanId.v1';
/**
 * The active grinder — a per-device pointer like the active bag (#488): not a
 * setting, not in backups.
 */
export const ACTIVE_GRINDER_KEY = 'crema.grinders.activeId.v1';
/** The pre-library single-bean key — read once for migration, then untouched. */
const LEGACY_KEY = 'crema.bean.current.v1';

/** Current schema version stamped in the envelope. */
const LIBRARY_SCHEMA = 1;

/**
 * Self-heal: drop any rows whose `id` already appeared earlier in the
 * list. Last-write-wins semantics would mean keeping the latest, but
 * the persisted order matches insertion (newest-first), so first-wins
 * here preserves the most-recently-imported version. The pre-fix
 * `bulkAdd` path could produce `[A, A, …]` after a double-click —
 * this strips the duplicates on next page load without user action.
 */
function dedupById<T extends { id: string }>(rows: T[]): T[] {
	const seen = new Set<string>();
	const out: T[] = [];
	for (const r of rows) {
		if (seen.has(r.id)) continue;
		seen.add(r.id);
		out.push(r);
	}
	return out;
}

interface LibraryEnvelope {
	schemaVersion: number;
	beans: Bean[];
	roasters: Roaster[];
	/**
	 * The owned grinders (#488), archived and tombstones included: library
	 * records next to the roasters, in the same blob as the bags whose saved
	 * grinds name them. Absent in an envelope written before #488.
	 */
	grinders?: Grinder[];
}

/** How the library's stored value read at load: only a readable (or absent) one may be migrated. */
type LibraryRead = 'loaded' | 'absent' | 'unavailable' | 'corrupt';

function isLibraryEnvelope(raw: unknown): raw is LibraryEnvelope {
	return (
		isJsonObject(raw) &&
		'schemaVersion' in raw &&
		Array.isArray(raw.beans) &&
		Array.isArray(raw.roasters)
	);
}

function readLibrary(): { env: LibraryEnvelope; read: LibraryRead } {
	// A stored value that isn't an envelope is kept aside (not silently read
	// as an empty library and overwritten by the next save).
	const read = readStore<LibraryEnvelope>(LIBRARY_KEY, {
		what: LIBRARY_WHAT,
		valid: isLibraryEnvelope
	});
	if (read.kind === 'unavailable') return { env: emptyLibrary(), read: 'unavailable' };
	if (read.kind === 'loaded') return { env: libraryOf(read.value), read: 'loaded' };
	if (read.kind === 'corrupt') return { env: firstLibrary(), read: 'corrupt' };
	return { env: firstLibrary(), read: 'absent' };
}

function emptyLibrary(): LibraryEnvelope {
	return { schemaVersion: LIBRARY_SCHEMA, beans: [], roasters: [], grinders: [] };
}

/** First load: the legacy single-bean migration, else an empty library. */
function firstLibrary(): LibraryEnvelope {
	// First load — try the legacy single-bean migration.
	const migrated = migrateLegacyCurrentBean(readJson<unknown>(LEGACY_KEY, null));
	if (migrated) {
		return {
			schemaVersion: LIBRARY_SCHEMA,
			beans: [migrated.bean],
			roasters: migrated.roaster ? [migrated.roaster] : [],
			grinders: []
		};
	}
	return emptyLibrary();
}

/** A stored envelope, its rows coerced; a row that can't be is kept aside. */
function libraryOf(env: LibraryEnvelope): LibraryEnvelope {
	const skipped: unknown[] = [];
	const coerced = <T,>(rows: unknown[], coerce: (row: unknown) => T | null): T[] =>
		rows.flatMap((row) => {
			const out = coerce(row);
			if (out === null) skipped.push(row);
			return out === null ? [] : [out];
		});
	const beans = dedupById(coerced(env.beans, coerceBean));
	const roasters = dedupById(coerced(env.roasters, coerceRoaster));
	const grinders = dedupById(coerced(Array.isArray(env.grinders) ? env.grinders : [], coerceGrinder));
	keepSkippedRows(LIBRARY_KEY, skipped, LIBRARY_WHAT);
	return { schemaVersion: LIBRARY_SCHEMA, beans, roasters, grinders };
}

// ── BeanLibraryStore ───────────────────────────────────────────────────

/**
 * The reactive bean library. CRUD on `beans` and `roasters`, plus the
 * `activeBeanId` pointer that drives the brew page's bean strip.
 */
/** What one debit did to a bag. */
export interface BagDebit {
	/** Grams actually taken: the dose, what was left, or 0. */
	taken: number;
	/** This debit took the bag from above 0 to 0. */
	emptied: boolean;
}

export class BeanLibraryStore {
	private readonly initial = readLibrary();
	private envelope = $state.raw<LibraryEnvelope>(this.initial.env);
	/** How the stored library read at load: the migration runs only over one that loaded. */
	readonly libraryRead: LibraryRead = this.initial.read;
	private activeId = $state.raw<string | null>(
		readJson<string | null>(ACTIVE_KEY, null, { valid: isIdOrNull })
	);
	private activeGrinderPtr = $state.raw<string | null>(
		readJson<string | null>(ACTIVE_GRINDER_KEY, null, { valid: isIdOrNull })
	);

	constructor() {
		// Another tab's debit or edit is taken, not written back over (#333).
		followOtherTabs(LIBRARY_KEY, isLibraryEnvelope, (v) => {
			this.envelope = libraryOf(v as LibraryEnvelope);
		});
		followOtherTabs(ACTIVE_KEY, isIdOrNull, (v) => {
			this.activeId = v as string | null;
		});
		followOtherTabs(ACTIVE_GRINDER_KEY, isIdOrNull, (v) => {
			this.activeGrinderPtr = v as string | null;
		});
	}

	// ── Reads ────────────────────────────────────────────────────────

	/** The full bean list, ordered by `updatedAt` desc by default in the UI. */
	get beans(): Bean[] {
		return this.envelope.beans;
	}

	/** The full roaster directory. */
	get roasters(): Roaster[] {
		return this.envelope.roasters;
	}

	/** Every grinder record: owned, archived and tombstones (#488). */
	get allGrinders(): Grinder[] {
		return this.envelope.grinders ?? [];
	}

	/** The owned grinders: what the pickers and Devices list. */
	get grinders(): Grinder[] {
		return this.allGrinders.filter(isLiveGrinder);
	}

	/** The archived grinders (Devices' "Archived" section), tombstones left out. */
	get archivedGrinders(): Grinder[] {
		return this.allGrinders.filter((g) => g.archivedAt != null && g.deletedAt == null);
	}

	/** Look up a grinder record by id, archived included. */
	getGrinder(id: string): Grinder | null {
		return this.allGrinders.find((g) => g.id === id) ?? null;
	}

	/** The active-grinder pointer as stored (may name an archived or unknown grinder). */
	get activeGrinderId(): string | null {
		return this.activeGrinderPtr;
	}

	/** The active grinder, or `null`: an archived, deleted or unknown id is no grinder. */
	get activeGrinder(): Grinder | null {
		const id = this.activeGrinderPtr;
		if (!id) return null;
		return coreActiveGrinder(this.allGrinders, id);
	}

	/** The id of the currently-selected bean (brew picker), or `null`. */
	get activeBeanId(): string | null {
		return this.activeId;
	}

	/** The currently-selected bean record, or `null`. */
	get activeBean(): Bean | null {
		const id = this.activeId;
		if (!id) return null;
		return this.envelope.beans.find((b) => b.id === id) ?? null;
	}

	/** Look up a bean by id. */
	getBean(id: string): Bean | null {
		return this.envelope.beans.find((b) => b.id === id) ?? null;
	}

	/** Look up a roaster by id. */
	getRoaster(id: string): Roaster | null {
		return this.envelope.roasters.find((r) => r.id === id) ?? null;
	}

	/**
	 * Find a roaster by case-insensitive name match. Used by import +
	 * inline-create to dedup `"Onyx Coffee Lab"` vs `"onyx coffee lab"`.
	 */
	findRoasterByName(name: string): Roaster | null {
		const needle = name.trim().toLowerCase();
		if (!needle) return null;
		return (
			this.envelope.roasters.find((r) => r.name.trim().toLowerCase() === needle) ?? null
		);
	}

	// ── Persistence ──────────────────────────────────────────────────

	private persist(): void {
		writeJson(LIBRARY_KEY, this.envelope);
	}
	private persistActive(): void {
		writeJson(ACTIVE_KEY, this.activeId);
	}
	private persistActiveGrinder(): void {
		writeJson(ACTIVE_GRINDER_KEY, this.activeGrinderPtr);
	}

	/** Wipe the entire library (beans + roasters + active pointer) — for a
	 *  "replace from backup" restore, which writes the bundle fresh after. */
	clearAll(): void {
		this.envelope = emptyLibrary();
		this.activeId = null;
		this.activeGrinderPtr = null;
		this.persist();
		this.persistActive();
		this.persistActiveGrinder();
	}

	/**
	 * A restore's library: beans and roasters in one write, after memory, so an
	 * edit made while it lands stacks on the restored state. `clearActive` for a
	 * Replace whose backup lacks the active bean. Whether it was stored.
	 */
	restoreLibrary(
		beans: Bean[],
		roasters: Roaster[],
		clearActive: boolean,
		grinders: Grinder[] = this.allGrinders,
		clearActiveGrinder = false
	): boolean {
		this.envelope = { ...this.envelope, beans, roasters, grinders };
		let stored = writeJsonChecked(LIBRARY_KEY, this.envelope);
		if (clearActive && this.activeId !== null) {
			this.activeId = null;
			stored = writeJsonChecked(ACTIVE_KEY, null) && stored;
		}
		if (clearActiveGrinder && this.activeGrinderPtr !== null) {
			this.activeGrinderPtr = null;
			stored = writeJsonChecked(ACTIVE_GRINDER_KEY, null) && stored;
		}
		return stored;
	}

	// ── Bean CRUD ────────────────────────────────────────────────────

	/** Insert a new bean (must have a unique id; replaces if id collides). */
	upsertBean(bean: Bean): void {
		const now = Date.now();
		const next = { ...bean, updatedAt: now };
		const idx = this.envelope.beans.findIndex((b) => b.id === bean.id);
		const beans =
			idx >= 0
				? [
						...this.envelope.beans.slice(0, idx),
						next,
						...this.envelope.beans.slice(idx + 1)
					]
				: [next, ...this.envelope.beans];
		this.envelope = { ...this.envelope, beans };
		this.persist();
	}

	/** Patch one or more fields on a bean. No-op if the id is unknown. */
	updateBean(id: string, patch: Partial<Bean>): void {
		const idx = this.envelope.beans.findIndex((b) => b.id === id);
		if (idx < 0) return;
		const updated: Bean = {
			...this.envelope.beans[idx],
			...patch,
			id, // never let the patch swap the id
			updatedAt: Date.now()
		};
		this.envelope = {
			...this.envelope,
			beans: [
				...this.envelope.beans.slice(0, idx),
				updated,
				...this.envelope.beans.slice(idx + 1)
			]
		};
		this.persist();
	}

	/**
	 * Hard-delete a bean locally (and clear the active pointer if it was active).
	 *
	 * Local-only by design: the Visualizer-side DELETE is owned by the caller
	 * (the delete-split component), which runs `BeanSync.deleteBean` on the app
	 * runtime when the user picks "delete on Visualizer too" (Option 3, T-16).
	 * Keeping the store pure-local means it never needs to reach the runtime —
	 * which a module-scope store can't do under Option 3.
	 */
	deleteBean(id: string): void {
		const doomed = this.envelope.beans.filter((b) => b.id === id);
		if (doomed.length === 0) return;
		const beans = this.envelope.beans.filter((b) => b.id !== id);
		this.envelope = { ...this.envelope, beans };
		this.persist();
		if (this.activeId === id) {
			this.activeId = null;
			this.persistActive();
		}
		this.dropPhotos(doomed);
	}

	/**
	 * Delete the photo blobs of bags just removed (save-boundaries review F9,
	 * as Android does), unless a remaining bag still points at the same blob.
	 * Best-effort: a failed delete leaves an orphan, never blocks the delete.
	 */
	private dropPhotos(removed: readonly Bean[]): void {
		const inUse = new Set(this.envelope.beans.map((b) => b.imageRef).filter((r) => r != null));
		for (const ref of new Set(removed.map((b) => b.imageRef))) {
			if (ref == null || inUse.has(ref)) continue;
			void getBeanImageStore()
				.delete(ref)
				.catch(() => {});
		}
	}

	/**
	 * Store an editor's Save (`mergeBeanEdit`'s result) as it is, its
	 * `updatedAt` included. A row deleted meanwhile stays deleted: `false`.
	 */
	saveEditedBean(bean: Bean): boolean {
		const idx = this.envelope.beans.findIndex((b) => b.id === bean.id);
		if (idx < 0) return false;
		this.envelope = {
			...this.envelope,
			beans: [...this.envelope.beans.slice(0, idx), bean, ...this.envelope.beans.slice(idx + 1)]
		};
		this.persist();
		return true;
	}

	/** Toggle the favourite flag. */
	toggleFavourite(id: string): void {
		const bean = this.getBean(id);
		if (!bean) return;
		this.updateBean(id, { favourite: !bean.favourite });
	}

	/** Stamp `archivedAt` to the current time, or clear it (unarchive). */
	toggleArchived(id: string): void {
		const bean = this.getBean(id);
		if (!bean) return;
		this.updateBean(id, { archivedAt: bean.archivedAt == null ? Date.now() : null });
	}

	/**
	 * Bulk-add beans (and synthesised roasters) — the import path's
	 * commit step. Inserts land at the front; an incoming row whose
	 * `id` already exists in the library REPLACES the existing one
	 * (last-write-wins), so a re-import of the same `.zip` is
	 * idempotent rather than producing two beans / roasters with the
	 * same id (which would break Svelte's `each` key invariant on
	 * `/beans`). Roasters are still de-duped by id only — callers that
	 * want name-level merging should resolve via `findRoasterByName`
	 * first.
	 */
	bulkAdd(beans: Bean[], roasters: Roaster[]): void {
		if (beans.length === 0 && roasters.length === 0) return;
		const incomingBeanIds = new Set(beans.map((b) => b.id));
		const incomingRoasterIds = new Set(roasters.map((r) => r.id));
		this.envelope = {
			...this.envelope,
			beans: [
				...beans,
				...this.envelope.beans.filter((b) => !incomingBeanIds.has(b.id))
			],
			roasters: [
				...this.envelope.roasters.filter((r) => !incomingRoasterIds.has(r.id)),
				...roasters
			]
		};
		this.persist();
	}

	/**
	 * Auto-debit `doseG` from a specific bean's `remaining` when a shot
	 * completes, via the shared core rule (`de1_domain::debit_remaining` over
	 * the wasm bridge): floors at 0 so the readout never goes negative, and
	 * no-ops (no write, no `updatedAt` touch) when the bag is already empty.
	 * Debits the shot's *own* bean (not whatever is active now), so switching
	 * the active bag after pulling can't bill the wrong bag.
	 *
	 * Returns the grams actually taken, for the row's `bagDebit` (0 when the
	 * bag had nothing to give), and whether this shot just **emptied** the bag
	 * (remaining went from > 0 to exactly 0), the caller's cue to prompt
	 * switch / archive / rate.
	 */
	debitBean(beanId: string, doseG: number): BagDebit {
		const bean = this.envelope.beans.find((b) => b.id === beanId);
		if (!bean) return { taken: 0, emptied: false };
		// The core decides whether there is anything to debit (returns `undefined`
		// when the bag is already empty or the dose is bad), so we persist — and
		// touch `updatedAt` — ONLY on a real debit.
		const next = debit_remaining(bean.remaining, doseG);
		if (next === undefined) return { taken: 0, emptied: false };
		const taken = debit_taken(bean.remaining, doseG);
		this.updateBean(bean.id, { remaining: next });
		return { taken, emptied: next === 0 };
	}

	/**
	 * Undo a row's debit when its shot moves off this bag: the grams the row
	 * took (`bagDebit`) go back, capped at `bagSize`, and an untracked bag gains
	 * nothing. A row from before `bagDebit` (`null`) credits its dose only while
	 * the bag holds grams (`de1_domain::credit_shot_debit`).
	 */
	creditShotDebit(beanId: string, bagDebit: number | null, doseG: number): void {
		const bean = this.envelope.beans.find((b) => b.id === beanId);
		if (!bean) return;
		const next = credit_shot_debit(bean.remaining, bean.bagSize, bagDebit ?? undefined, doseG);
		if (next === undefined) return;
		this.updateBean(bean.id, { remaining: next });
	}

	/**
	 * Re-settle a bag after a logged brew's dose is edited from `oldDoseG`
	 * to `newDoseG` (`null`/0 = no dose) — the shared core rule
	 * (`de1_domain::resettle_remaining`): only the net change moves, and no
	 * more comes back than the row took (`bagDebit`). An unchanged dose is a
	 * no-op, so a full bag never loses grams.
	 *
	 * Returns what the row now records as taken, or `null` when the bag is
	 * gone.
	 */
	resettleBean(
		beanId: string,
		bagDebit: number | null,
		oldDoseG: number | null,
		newDoseG: number | null
	): number | null {
		const bean = this.envelope.beans.find((b) => b.id === beanId);
		if (!bean) return null;
		const row = [
			bean.remaining,
			bean.bagSize,
			bagDebit ?? undefined,
			oldDoseG ?? 0,
			newDoseG ?? 0
		] as const;
		const taken = resettled_bag_debit(...row);
		const next = resettle_remaining(...row);
		if (next !== undefined) this.updateBean(bean.id, { remaining: next });
		return taken;
	}

	// ── Roaster CRUD ─────────────────────────────────────────────────

	upsertRoaster(roaster: Roaster): void {
		const next = { ...roaster, updatedAt: Date.now() };
		const idx = this.envelope.roasters.findIndex((r) => r.id === roaster.id);
		const roasters =
			idx >= 0
				? [
						...this.envelope.roasters.slice(0, idx),
						next,
						...this.envelope.roasters.slice(idx + 1)
					]
				: [next, ...this.envelope.roasters];
		this.envelope = { ...this.envelope, roasters };
		this.persist();
	}

	updateRoaster(id: string, patch: Partial<Roaster>): void {
		const idx = this.envelope.roasters.findIndex((r) => r.id === id);
		if (idx < 0) return;
		const updated: Roaster = {
			...this.envelope.roasters[idx],
			...patch,
			id,
			updatedAt: Date.now()
		};
		this.envelope = {
			...this.envelope,
			roasters: [
				...this.envelope.roasters.slice(0, idx),
				updated,
				...this.envelope.roasters.slice(idx + 1)
			]
		};
		this.persist();
	}

	/** {@link saveEditedBean} for a roaster editor's Save. */
	saveEditedRoaster(roaster: Roaster): boolean {
		const idx = this.envelope.roasters.findIndex((r) => r.id === roaster.id);
		if (idx < 0) return false;
		this.envelope = {
			...this.envelope,
			roasters: [
				...this.envelope.roasters.slice(0, idx),
				roaster,
				...this.envelope.roasters.slice(idx + 1)
			]
		};
		this.persist();
		return true;
	}

	/**
	 * Delete a roaster locally. Beans pointing at it have their `roasterId`
	 * cleared rather than disappearing — losing a roastery's name is
	 * less destructive than losing the bag. Local-only: the Visualizer-side
	 * DELETE is owned by the caller (the delete-split component), same as
	 * {@link deleteBean}.
	 */
	deleteRoaster(id: string): void {
		const roasters = this.envelope.roasters.filter((r) => r.id !== id);
		if (roasters.length === this.envelope.roasters.length) return;
		const beans = this.envelope.beans.map((b) =>
			b.roasterId === id ? { ...b, roasterId: null, updatedAt: Date.now() } : b
		);
		this.envelope = { ...this.envelope, beans, roasters };
		this.persist();
	}

	/**
	 * Delete a roaster AND every bean that pointed at it. Opt-in
	 * cascade — most users want the default {@link deleteRoaster}
	 * (soft-detach) since beans carry the high-value data. This is
	 * the rarer "I imported a roaster with junk beans I don't want
	 * either" flow. Local-only; the caller fires the Visualizer DELETEs
	 * for the roaster + each cascaded bag (it collects their ids first).
	 */
	deleteRoasterAndBeans(id: string): void {
		// GEN6: do the whole cascade as ONE envelope update + ONE persist, rather
		// than N `deleteBean` calls (each persisting) + a trailing `deleteRoaster`
		// (another). Removing every bean that points at the roaster means no
		// survivor needs its `roasterId` detached.
		const doomed = this.envelope.beans.filter((b) => b.roasterId === id);
		const beanIds = doomed.map((b) => b.id);
		const beans = this.envelope.beans.filter((b) => b.roasterId !== id);
		const roasters = this.envelope.roasters.filter((r) => r.id !== id);
		const changed =
			beans.length !== this.envelope.beans.length ||
			roasters.length !== this.envelope.roasters.length;
		if (!changed) return;
		this.envelope = { ...this.envelope, beans, roasters };
		this.persist();
		// Clear the active-bean pointer if it pointed at a cascaded bag (mirrors
		// `deleteBean`'s active-pointer cleanup).
		if (this.activeId !== null && beanIds.includes(this.activeId)) {
			this.activeId = null;
			this.persistActive();
		}
		this.dropPhotos(doomed);
	}

	/**
	 * Find an existing roaster by case-insensitive name or create a fresh
	 * one. Returns the roaster row. Used by the bean editor's "type a name
	 * to create a roaster" inline flow and by the Beanconqueror importer.
	 */
	ensureRoaster(name: string): Roaster | null {
		const trimmed = name.trim();
		if (!trimmed) return null;
		const existing = this.findRoasterByName(trimmed);
		if (existing) return existing;
		const fresh = blankRoaster(trimmed);
		this.upsertRoaster(fresh);
		return fresh;
	}

	/**
	 * Clear every bean's linked-profile reference to [profileId] — the
	 * profile-deletion cascade (wired via `ProfileStore.onDeleted` at boot).
	 */
	clearLinksTo(profileId: string): void {
		for (const b of this.envelope.beans) {
			if (b.linkedProfileId === profileId) {
				this.updateBean(b.id, { linkedProfileId: null });
			}
		}
	}

	// ── Active-bean pointer ──────────────────────────────────────────

	/** Set the active bean (or `null` to clear). Drives the brew picker. */
	setActiveBean(id: string | null): void {
		this.activeId = id;
		this.persistActive();
	}

	/**
	 * Quick-add: create + save + activate in one step. Used by the brew-page
	 * inline "+ Add bean" affordance.
	 */
	quickAdd(name: string, roasterName?: string, roastedOn?: string | null): Bean {
		const bean = blankBean();
		bean.name = name.trim() || 'New bean';
		if (roasterName) {
			const roaster = this.ensureRoaster(roasterName);
			bean.roasterId = roaster?.id ?? null;
		}
		if (roastedOn) bean.roastedOn = roastedOn;
		bean.favourite = true; // a quick-add bean is implicitly pinned to the strip
		this.upsertBean(bean);
		this.setActiveBean(bean.id);
		return bean;
	}

	// ── Grinders (#488) ──────────────────────────────────────────────

	/** Insert or replace grinder rows by id, as given (`updatedAt` is the core's). */
	private putGrinders(rows: readonly Grinder[]): void {
		if (rows.length === 0) return;
		const byId = new Map(rows.map((g) => [g.id, g]));
		const kept = this.allGrinders.map((g) => byId.get(g.id) ?? g);
		const known = new Set(kept.map((g) => g.id));
		const added = rows.filter((g) => !known.has(g.id));
		this.envelope = { ...this.envelope, grinders: [...kept, ...added] };
		this.persist();
	}

	/**
	 * Add a grinder: a catalogue entry (`catalogueId`) or a typed name. A
	 * match among the archived records comes back with the same id, so its
	 * bag grinds come back; an owned match is returned as it is. `null` when
	 * both are blank.
	 */
	addGrinder(name: string, catalogueId = ''): Grinder | null {
		const add = coreAddGrinder(this.allGrinders, name, catalogueId);
		if (!add) return null;
		if (add.kind !== 'existing') this.putGrinders([add.grinder]);
		return add.grinder;
	}

	/** A Devices edit's Save (rename, scale, preset): merged onto the row as stored now. */
	saveGrinderEdit(base: Grinder, edited: Grinder): Grinder | null {
		const current = this.getGrinder(base.id);
		if (!current) return null;
		const merged = mergeGrinderEdit(base, edited, current);
		this.putGrinders([merged]);
		return merged;
	}

	/**
	 * Remove a grinder from Devices: it is archived, never deleted, so bags'
	 * saved grinds and shots that name it stay. Archiving the active grinder
	 * clears the pointer (review F5): Brew shows "No grinder".
	 */
	archiveGrinder(id: string): void {
		const g = this.getGrinder(id);
		if (!g) return;
		const out = coreArchiveGrinder(g, this.activeGrinderPtr);
		this.putGrinders([out.grinder]);
		const next = out.activeGrinderId ?? null;
		if (next !== this.activeGrinderPtr) {
			this.activeGrinderPtr = next;
			this.persistActiveGrinder();
		}
	}

	/** An archived grinder back on Devices, same id. */
	restoreGrinder(id: string): Grinder | null {
		const g = this.getGrinder(id);
		if (!g) return null;
		const out = coreRestoreGrinder(g);
		this.putGrinders([out]);
		return out;
	}

	/** Point Brew at a grinder (`null` = no grinder). */
	setActiveGrinder(id: string | null): void {
		if (this.activeGrinderPtr === id) return;
		this.activeGrinderPtr = id;
		this.persistActiveGrinder();
	}

	/**
	 * Brew's Save of the grind to (bag, active grinder) — or auto-save's —
	 * through the core rule: the row takes the value or is added (not
	 * starred); the star never changes. Stored only when the value changed.
	 */
	saveBagGrind(beanId: string, grinder: Grinder | null, grind: string): GrindSave | null {
		const bean = this.getBean(beanId);
		if (!bean) return null;
		const out = coreSaveBagGrind(bean, grinder, grind);
		if (out.changed) this.replaceBean(out.bean);
		return out;
	}

	/** Store a bag the core produced (a grind save), its `updatedAt` as the core set it. */
	storeBagFromCore(bean: Bean): void {
		this.replaceBean(bean);
	}

	/** Records for grinder names an import carried that no grinder has yet. */
	addGrindersForNames(names: readonly string[]): Grinder[] {
		const fresh = grindersForNames(this.allGrinders, names);
		this.putGrinders(fresh);
		return fresh;
	}

	/** Bulk-insert or replace grinder records by id (an import's merge). */
	putImportedGrinders(rows: readonly Grinder[]): void {
		this.putGrinders(rows);
	}

	/**
	 * The library migration (#488), run once the library has loaded and after
	 * every import or restore; idempotent. With no grinder records it creates
	 * one per distinct name in `grinderModel` and the bags' `grinder`, and the
	 * one `grinderModel` names becomes active; every bag not yet migrated gets
	 * its old grind as a saved grind. The old fields and `updatedAt` are never
	 * written. Skipped over a library that couldn't be read (its rows would
	 * look absent), and with `settingsReadable` false (the settings grinder
	 * would look blank). Whether anything changed.
	 */
	migrateGrinders(grinderModel: string, settingsReadable = true): boolean {
		if (!this.canMigrate || !settingsReadable) return false;
		let m;
		try {
			m = migrateGrinderLibrary(this.allGrinders, this.beans, grinderModel);
		} catch (e) {
			// Malformed rows: keep what there is; a later load tries again.
			console.warn('[beans] grinder migration skipped', e);
			return false;
		}
		const changed = m.newGrinders.length > 0 || m.updatedBeans.length > 0;
		if (changed) {
			const updated = new Map(m.updatedBeans.map((b) => [b.id, b as Bean]));
			const beans = this.envelope.beans.map((b) => updated.get(b.id) ?? b);
			this.envelope = { ...this.envelope, beans, grinders: [...this.allGrinders, ...m.newGrinders] };
			this.persist();
		}
		if (m.grindersMigrated) {
			const next = m.activeGrinderId ?? null;
			if (next !== this.activeGrinderPtr) {
				this.activeGrinderPtr = next;
				this.persistActiveGrinder();
			}
		}
		return changed;
	}

	/** The stored library read (or was absent): one that couldn't be read is never migrated. */
	private get canMigrate(): boolean {
		return this.libraryRead === 'loaded' || this.libraryRead === 'absent';
	}

	// ── Visualizer sync helpers ──────────────────────────────────────

	/**
	 * Store a bean record exactly as the sync produced it (insert or replace by
	 * id) — WITHOUT re-stamping `updatedAt`. A pull is not a local edit: the
	 * core's reconcile hands back the stamp the row must keep, and a fresh one
	 * here would make the next sync push every pulled bag straight back.
	 */
	replaceBean(bean: Bean): void {
		const idx = this.envelope.beans.findIndex((b) => b.id === bean.id);
		const beans =
			idx >= 0
				? [...this.envelope.beans.slice(0, idx), bean, ...this.envelope.beans.slice(idx + 1)]
				: [bean, ...this.envelope.beans];
		this.envelope = { ...this.envelope, beans };
		this.persist();
	}

	/** The roaster counterpart of {@link replaceBean}: stored as given, `updatedAt` untouched. */
	replaceRoaster(roaster: Roaster): void {
		const idx = this.envelope.roasters.findIndex((r) => r.id === roaster.id);
		const roasters =
			idx >= 0
				? [
						...this.envelope.roasters.slice(0, idx),
						roaster,
						...this.envelope.roasters.slice(idx + 1)
					]
				: [roaster, ...this.envelope.roasters];
		this.envelope = { ...this.envelope, roasters };
		this.persist();
	}
}

// ── Singleton + back-compat alias ──────────────────────────────────────

let store: BeanLibraryStore | undefined;

/** Get the shared bean library store, creating it on first call. */
export function getBeanLibraryStore(): BeanLibraryStore {
	if (!store) {
		store = new BeanLibraryStore();
		// After the library has loaded (#488): the stored rows are in memory.
		runGrinderMigration(store);
	}
	return store;
}

/** The settings key the migration reads `grinderModel` from. */
const SETTINGS_KEY = 'crema.settings.v1';

/**
 * Run the library migration on `lib` with the settings grinder (or
 * `grinderModel`, a restore's own): at load, and after every import or
 * restore. Not while the settings couldn't be read: a blank grinder there
 * would leave no grinder active.
 */
export function runGrinderMigration(
	lib: BeanLibraryStore = getBeanLibraryStore(),
	grinderModel?: string
): boolean {
	if (grinderModel !== undefined) return lib.migrateGrinders(grinderModel, true);
	const settings = getSettingsStore();
	const health = storeHealth(SETTINGS_KEY);
	const readable = health === 'ok' || health === 'absent';
	return lib.migrateGrinders(settings.current.grinderModel ?? '', readable);
}

/**
 * Legacy alias — the pre-library API surface had `getBeanStore()` returning a
 * single-bean object. Callers that just want the active bean keep working
 * via this alias.
 */
export function getBeanStore(): BeanLibraryStore {
	return getBeanLibraryStore();
}
