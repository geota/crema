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

import { isIdOrNull, isJsonObject, readJson, writeJson } from '#lib/utils/storage.js';
import {
	credit_shot_debit,
	debit_remaining,
	debit_taken,
	resettle_remaining,
	resettled_bag_debit
} from '#lib/wasm/de1_wasm.js';
import {
	type Bean,
	type Roaster,
	blankBean,
	blankRoaster,
	coerceBean,
	coerceRoaster,
	migrateLegacyCurrentBean
} from './model';

// ── localStorage keys (versioned envelope) ─────────────────────────────

/** Library envelope key — beans + roasters + schemaVersion. */
const LIBRARY_KEY = 'crema.beans.v1';
/** Active bean id — the brew-page picker's pointer. */
const ACTIVE_KEY = 'crema.beans.activeBeanId.v1';
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
}

function isLibraryEnvelope(raw: unknown): raw is LibraryEnvelope {
	return (
		isJsonObject(raw) &&
		'schemaVersion' in raw &&
		Array.isArray(raw.beans) &&
		Array.isArray(raw.roasters)
	);
}

function readLibrary(): LibraryEnvelope {
	// A stored value that isn't an envelope is kept aside (not silently read
	// as an empty library and overwritten by the next save).
	const raw = readJson<unknown>(LIBRARY_KEY, null, { what: 'bean library', valid: isLibraryEnvelope });
	if (raw !== null && isLibraryEnvelope(raw)) {
		const env = raw as LibraryEnvelope;
		const beans = dedupById(
			env.beans.map(coerceBean).filter((b): b is Bean => b !== null)
		);
		const roasters = dedupById(
			env.roasters.map(coerceRoaster).filter((r): r is Roaster => r !== null)
		);
		return { schemaVersion: LIBRARY_SCHEMA, beans, roasters };
	}
	// First load — try the legacy single-bean migration.
	const migrated = migrateLegacyCurrentBean(readJson<unknown>(LEGACY_KEY, null));
	if (migrated) {
		return {
			schemaVersion: LIBRARY_SCHEMA,
			beans: [migrated.bean],
			roasters: migrated.roaster ? [migrated.roaster] : []
		};
	}
	return { schemaVersion: LIBRARY_SCHEMA, beans: [], roasters: [] };
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
	private envelope = $state.raw<LibraryEnvelope>(readLibrary());
	private activeId = $state.raw<string | null>(
		readJson<string | null>(ACTIVE_KEY, null, { valid: isIdOrNull })
	);

	// ── Reads ────────────────────────────────────────────────────────

	/** The full bean list, ordered by `updatedAt` desc by default in the UI. */
	get beans(): Bean[] {
		return this.envelope.beans;
	}

	/** The full roaster directory. */
	get roasters(): Roaster[] {
		return this.envelope.roasters;
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

	/** Wipe the entire library (beans + roasters + active pointer) — for a
	 *  "replace from backup" restore, which writes the bundle fresh after. */
	clearAll(): void {
		this.envelope = { schemaVersion: LIBRARY_SCHEMA, beans: [], roasters: [] };
		this.activeId = null;
		this.persist();
		this.persistActive();
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
		const beans = this.envelope.beans.filter((b) => b.id !== id);
		if (beans.length === this.envelope.beans.length) return;
		this.envelope = { ...this.envelope, beans };
		this.persist();
		if (this.activeId === id) {
			this.activeId = null;
			this.persistActive();
		}
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
		const beanIds = this.envelope.beans
			.filter((b) => b.roasterId === id)
			.map((b) => b.id);
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
	if (!store) store = new BeanLibraryStore();
	return store;
}

/**
 * Legacy alias — the pre-library API surface had `getBeanStore()` returning a
 * single-bean object. Callers that just want the active bean keep working
 * via this alias.
 */
export function getBeanStore(): BeanLibraryStore {
	return getBeanLibraryStore();
}
