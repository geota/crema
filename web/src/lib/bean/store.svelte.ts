/**
 * `$lib/bean/store` — the bean library store.
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

import { readJson, writeJson } from '$lib/utils/storage';
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

function readLibrary(): LibraryEnvelope {
	const raw = readJson<unknown>(LIBRARY_KEY, null);
	if (
		raw &&
		typeof raw === 'object' &&
		'schemaVersion' in raw &&
		Array.isArray((raw as LibraryEnvelope).beans) &&
		Array.isArray((raw as LibraryEnvelope).roasters)
	) {
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
export class BeanLibraryStore {
	private envelope = $state.raw<LibraryEnvelope>(readLibrary());
	private activeId = $state.raw<string | null>(
		readJson<string | null>(ACTIVE_KEY, null)
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
	 * Hard-delete a bean (and clear the active pointer if it was active). Also
	 * fires a best-effort DELETE against Visualizer so the remote stays in
	 * sync — failure is logged and dropped, never blocks the local delete.
	 */
	deleteBean(id: string): void {
		const bean = this.getBean(id);
		const beans = this.envelope.beans.filter((b) => b.id !== id);
		if (beans.length === this.envelope.beans.length) return;
		this.envelope = { ...this.envelope, beans };
		this.persist();
		if (this.activeId === id) {
			this.activeId = null;
			this.persistActive();
		}
		// Fire-and-forget remote delete. Import inline to avoid pulling the
		// sync module into the store's circular-dep surface.
		if (bean?.visualizerId) {
			void import('./visualizer-sync').then(({ deleteRemoteBean }) =>
				deleteRemoteBean(bean).then((r) => {
					if (!r.ok) console.warn('Visualizer delete failed:', r.error);
				})
			);
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
	 * Auto-debit `doseG` from the active bean's `remaining` when a shot
	 * completes. Floors at `0` so the readout never goes negative. No-op
	 * when there is no active bean or the bag size is unset.
	 */
	debitFromActive(doseG: number): void {
		const bean = this.activeBean;
		if (!bean || !(doseG > 0)) return;
		if (bean.bagSize <= 0) return; // no bag size set → no burn-down to track
		const remaining = Math.max(0, bean.remaining - doseG);
		this.updateBean(bean.id, { remaining: remaining });
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
	 * Delete a roaster. Beans pointing at it have their `roasterId`
	 * cleared rather than disappearing — losing a roastery's name is
	 * less destructive than losing the bag. Fires a best-effort DELETE
	 * against Visualizer to keep the remote in sync.
	 */
	deleteRoaster(id: string): void {
		const roaster = this.getRoaster(id);
		const roasters = this.envelope.roasters.filter((r) => r.id !== id);
		if (roasters.length === this.envelope.roasters.length) return;
		const beans = this.envelope.beans.map((b) =>
			b.roasterId === id ? { ...b, roasterId: null, updatedAt: Date.now() } : b
		);
		this.envelope = { ...this.envelope, beans, roasters };
		this.persist();
		if (roaster?.visualizerId) {
			void import('./visualizer-sync').then(({ deleteRemoteRoaster }) =>
				deleteRemoteRoaster(roaster).then((r) => {
					if (!r.ok) console.warn('Visualizer delete failed:', r.error);
				})
			);
		}
	}

	/**
	 * Delete a roaster AND every bean that pointed at it. Opt-in
	 * cascade — most users want the default {@link deleteRoaster}
	 * (soft-detach) since beans carry the high-value data. This is
	 * the rarer "I imported a roaster with junk beans I don't want
	 * either" flow. Each bean is soft-deleted via {@link deleteBean}
	 * so its Visualizer tombstone fires; the roaster delete itself
	 * uses the soft-detach path (the beans have already been
	 * removed, so the detach is a no-op).
	 */
	deleteRoasterAndBeans(id: string): void {
		const beanIds = this.envelope.beans
			.filter((b) => b.roasterId === id)
			.map((b) => b.id);
		for (const beanId of beanIds) this.deleteBean(beanId);
		this.deleteRoaster(id);
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
	 * Replace a bean's record with one pulled from Visualizer — used by the
	 * sync path's "remote wins" merge.
	 */
	replaceBean(bean: Bean): void {
		this.upsertBean(bean);
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
