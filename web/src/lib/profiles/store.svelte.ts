/**
 * `$lib/profiles/store` — the profile library store.
 *
 * Backs the `/profiles` library grid and the editor. Two sources merge into
 * one reactive list:
 *
 *  1. **Built-in profiles** — *real*. Pulled from the wasm core via the
 *     `$lib/core` facade's `builtinProfiles()` and adapted with
 *     `fromCoreProfile`. Read-only: the built-in corpus is fixed at compile
 *     time in the Rust core. Editing a built-in duplicates it to a custom
 *     profile.
 *  2. **Custom profiles** — *real persistence*. Created / edited by the user
 *     and stored in `localStorage`. Crema's web shell is a static, client-only
 *     PWA — there is no server — so `localStorage` is the right store. It also
 *     records **overrides** for built-ins: a built-in's pin / last-used state
 *     is user data, so it persists separately keyed by the built-in id.
 *
 * Everything — create, edit, save, delete, pin — survives a reload.
 *
 * The store is a Svelte 5 `$state` class; obtain the singleton with
 * {@link getProfileStore}. It loads lazily on first use: built-ins arrive
 * asynchronously (the wasm core load), custom profiles synchronously from
 * `localStorage`.
 */

import { SvelteSet } from 'svelte/reactivity';

import { loadCore } from '$lib/core';
import { readJson, writeJson } from '$lib/utils/storage';
import {
	fromCoreProfile,
	type CremaProfile
} from './model';

/** localStorage key for the user's custom profiles (a `CremaProfile[]`). */
const CUSTOM_KEY = 'crema.profiles.custom.v1';

/**
 * localStorage key for per-built-in overrides — a map of built-in profile
 * UUID → `{ pinned, lastUsed }`. Built-ins themselves are not stored (they
 * come from the core); only the user-owned bits of their state are.
 */
const OVERRIDES_KEY = 'crema.profiles.builtinOverrides.v1';

/** localStorage key for the id of the profile marked "active" on Brew. */
const ACTIVE_KEY = 'crema.profiles.activeId.v1';

/**
 * One-shot migration: pre-2026-05 custom profiles were minted with a
 * `custom:<uuid>` id prefix; the editor since switched to bare UUIDs but
 * never migrated the persisted records. Strip the prefix on load so the
 * `/profiles/{uuid}/edit` route + every store lookup work with a plain
 * UUID. Idempotent (a profile whose id is already a bare UUID is returned
 * unchanged) and lossless (the underlying UUID is preserved).
 */
function stripLegacyIdPrefix(profiles: CremaProfile[]): CremaProfile[] {
	return profiles.map((p) =>
		p.id.startsWith('custom:') ? { ...p, id: p.id.slice('custom:'.length) } : p
	);
}

/** Same migration applied to the persisted active-profile id. */
function stripLegacyIdPrefixOnId(id: string | null): string | null {
	if (id == null) return null;
	return id.startsWith('custom:') ? id.slice('custom:'.length) : id;
}

/**
 * localStorage key for the set of built-in ids the user has chosen to
 * hide from the library. Built-ins are compiled into the wasm binary
 * and can't be truly deleted (the PWA has no server to persist a
 * deletion against); a hide-list lets users tidy up the grid without
 * losing the ability to restore later.
 */
const HIDDEN_BUILTINS_KEY = 'crema.profiles.hiddenBuiltins.v1';

/** The user-owned, persisted slice of a built-in profile's state. */
interface BuiltinOverride {
	/** Whether the user pinned this built-in to favorites. */
	pinned: boolean;
	/** A human "last used" label, or null. */
	lastUsed: string | null;
}

/**
 * The reactive profile library. One instance per app — {@link getProfileStore}.
 */
export class ProfileStore {
	/** The built-in profiles, adapted from the core. Empty until loaded. */
	private builtins = $state.raw<CremaProfile[]>([]);
	/** The user's custom profiles, from localStorage. */
	private custom = $state.raw<CremaProfile[]>(stripLegacyIdPrefix(readJson<CremaProfile[]>(CUSTOM_KEY, [])));
	/** Per-built-in user overrides (pin / last-used). */
	private overrides = $state.raw<Record<string, BuiltinOverride>>(
		readJson<Record<string, BuiltinOverride>>(OVERRIDES_KEY, {})
	);
	/**
	 * The set of built-in ids the user has hidden from the library.
	 * Persisted to localStorage. A hidden built-in is excluded from
	 * `all` but is still in `builtins` — restoring it is just removing
	 * the id from this set. `SvelteSet` so add/delete mutate in place
	 * and stay reactive — no per-toggle full-set allocation.
	 */
	private hiddenBuiltins = new SvelteSet<string>(
		readJson<string[]>(HIDDEN_BUILTINS_KEY, [])
	);
	/** The id of the profile marked active on the Brew dashboard. */
	activeId = $state<string | null>(stripLegacyIdPrefixOnId(readJson<string | null>(ACTIVE_KEY, null)));
	/** Whether the built-in load has finished (drives the grid's loading state). */
	loaded = $state(false);

	/**
	 * The in-flight (or settled) built-in load promise — the atomic guard so the
	 * async load runs exactly once. Storing the `Promise` itself, rather than a
	 * boolean, closes the race where two synchronous callers both observe a
	 * not-yet-started load: the first caller assigns it, every later caller sees
	 * the same promise and awaits it.
	 */
	private loadPromise: Promise<void> | undefined;

	/**
	 * The full visible library — built-ins (with user overrides folded
	 * in, minus those the user has hidden) followed by custom profiles.
	 * Reactive: any mutation re-renders the grid.
	 */
	get all(): CremaProfile[] {
		const builtins = this.builtins
			.filter((b) => !this.hiddenBuiltins.has(b.id))
			.map((b) => this.applyOverrides(b));
		return [...builtins, ...this.custom];
	}

	/**
	 * Only the built-in profiles the user has hidden — drives the
	 * Profiles page's "Hidden" filter pill. Same override-folding as
	 * `all` so a hidden-then-pinned-then-hidden round-trip preserves
	 * the user's earlier pin state.
	 */
	get hiddenBuiltinProfiles(): CremaProfile[] {
		return this.builtins
			.filter((b) => this.hiddenBuiltins.has(b.id))
			.map((b) => this.applyOverrides(b));
	}

	/** How many built-ins the user has hidden — gates the filter pill. */
	get hiddenBuiltinCount(): number {
		return this.hiddenBuiltins.size;
	}

	/** Check whether a specific built-in is currently hidden. */
	isHidden(id: string): boolean {
		return this.hiddenBuiltins.has(id);
	}

	/** Internal: fold per-built-in `pinned`/`lastUsed` overrides onto a base profile. */
	private applyOverrides(base: CremaProfile): CremaProfile {
		const ov = this.overrides[base.id];
		return ov ? { ...base, pinned: ov.pinned, lastUsed: ov.lastUsed } : base;
	}

	constructor() {
		// After the field initializers ran `stripLegacyIdPrefix*` over the
		// raw localStorage data, persist the migrated values so subsequent
		// loads observe clean ids. The writes are no-ops when nothing was
		// migrated (`writeJson` doesn't diff, but the cost is one
		// `JSON.stringify` once per app boot — negligible).
		writeJson(CUSTOM_KEY, this.custom);
		writeJson(ACTIVE_KEY, this.activeId);
	}

	/** Look up one profile by id, or `undefined` if it is not in the library. */
	get(id: string): CremaProfile | undefined {
		return this.all.find((p) => p.id === id);
	}

	/**
	 * Kick off the one-time built-in load. Safe to call repeatedly — only the
	 * first call does work; every call returns the same promise. Resolves once
	 * the built-ins are in `all`.
	 */
	ensureLoaded(): Promise<void> {
		if (!this.loadPromise) this.loadPromise = this._load();
		return this.loadPromise;
	}

	/** The actual one-time built-in load, memoized by {@link ensureLoaded}. */
	private async _load(): Promise<void> {
		try {
			const core = await loadCore();
			const profiles = await core.builtinProfiles();
			this.builtins = profiles.map((p, i) => fromCoreProfile(p, i));
		} catch {
			// If the wasm core fails to load the grid still works — it just
			// shows the custom profiles. A failure here is non-fatal.
			this.builtins = [];
		} finally {
			this.loaded = true;
			// Now that the library is hydrated, push the persisted active
			// profile's recipe targets into the core. The boot sequence in
			// `createCremaApp` pushes app-level settings, but the active
			// profile's `yieldOut` / `maxTotalVolumeMl` can only resolve
			// once `builtins` and `custom` are both loaded.
			void this.pushActiveProfileToCore();
		}
	}

	/**
	 * Push the currently active profile's recipe targets (yield + volume
	 * limit) into the core via the wasm bridge, so SAW + SAV arming uses
	 * the right values on the next shot. `undefined` for both when no
	 * profile is active or its fields are at 0 (the disabled sentinel).
	 * Fire-and-forget — the core consults its latched values lazily on
	 * the next `ShotEvent::Started` / first flowing phase.
	 */
	private async pushActiveProfileToCore(): Promise<void> {
		const core = await loadCore();
		const id = this.activeId;
		if (id == null) {
			// No active profile → disable everything.
			await core.setWeightTargetDisabled(true);
			await core.setProfileTargetWeight(undefined);
			await core.setProfileVolumeLimit(undefined);
			return;
		}
		const p = this.get(id);
		if (!p) return;
		// The per-shot weight-target dot follows the profile's intent on
		// load: a profile with `yieldOut > 0` engages the target (dot ON,
		// `disabled = false`); a profile with `yieldOut === 0` disables it
		// (dot OFF, `disabled = true`). The user can still flip it per
		// shot via the QC card.
		await core.setWeightTargetDisabled(p.yieldOut <= 0);
		await core.setProfileTargetWeight(p.yieldOut > 0 ? p.yieldOut : undefined);
		await core.setProfileVolumeLimit(p.maxTotalVolumeMl > 0 ? p.maxTotalVolumeMl : undefined);
	}

	/** Persist the custom-profile list to localStorage. */
	private persistCustom(): void {
		writeJson(CUSTOM_KEY, this.custom);
	}

	/** Persist the per-built-in overrides to localStorage. */
	private persistOverrides(): void {
		writeJson(OVERRIDES_KEY, this.overrides);
	}

	/**
	 * Insert or update a custom profile and persist. A built-in id never
	 * reaches here — the editor duplicates a built-in to a custom profile
	 * before saving, so every saved profile is `source: 'custom'`.
	 */
	save(profile: CremaProfile): void {
		const idx = this.custom.findIndex((p) => p.id === profile.id);
		if (idx >= 0) {
			this.custom = [
				...this.custom.slice(0, idx),
				profile,
				...this.custom.slice(idx + 1)
			];
		} else {
			this.custom = [...this.custom, profile];
		}
		this.persistCustom();
		// If this save edited the active profile, propagate any changes to
		// its recipe targets (yield / max volume) into the core right
		// away — the next shot picks them up without needing a re-activate.
		if (this.activeId === profile.id) void this.pushActiveProfileToCore();
	}

	/**
	 * Delete a profile by id and persist.
	 *
	 * - Custom profiles are removed from the custom list and gone for
	 *   good (re-import the file to bring them back).
	 * - Built-ins are added to the hidden-builtins set (they stay
	 *   compiled into the wasm binary; this just hides them from
	 *   `all`). Restore via {@link unhideAllBuiltins}.
	 *
	 * Either way, the active-id is cleared if it pointed at the
	 * removed profile.
	 */
	delete(id: string): void {
		const profile = this.get(id);
		if (profile?.source === 'builtin') {
			this.hiddenBuiltins.add(id);
			this.persistHiddenBuiltins();
		} else {
			this.custom = this.custom.filter((p) => p.id !== id);
			this.persistCustom();
		}
		if (this.activeId === id) this.setActive(null);
	}

	/** Persist the hidden-builtins set to localStorage. */
	private persistHiddenBuiltins(): void {
		writeJson(HIDDEN_BUILTINS_KEY, Array.from(this.hiddenBuiltins));
	}

	/**
	 * Restore a single hidden built-in to the library — drops it from
	 * the hide-set. The user-facing action behind the eye-icon button
	 * the card shows while in the "Hidden" filter view.
	 */
	unhideBuiltin(id: string): void {
		if (!this.hiddenBuiltins.delete(id)) return;
		this.persistHiddenBuiltins();
	}

	/**
	 * Toggle a profile's pinned state and persist. A custom profile is updated
	 * in place; a built-in is pinned via the overrides map (built-ins are
	 * read-only, but pinning is the user's own state).
	 */
	togglePin(id: string): void {
		const base = this.builtins.find((b) => b.id === id);
		if (base) {
			const current = this.overrides[id] ?? {
				pinned: base.pinned,
				lastUsed: base.lastUsed
			};
			this.overrides = {
				...this.overrides,
				[id]: { ...current, pinned: !current.pinned }
			};
			this.persistOverrides();
		} else {
			const p = this.custom.find((c) => c.id === id);
			if (p) this.save({ ...p, pinned: !p.pinned });
		}
	}

	/**
	 * Mark a profile "active" — the one shown on the Brew dashboard header.
	 * Persists the id and stamps a real last-used timestamp on the profile.
	 *
	 * `lastUsed` holds an ISO-8601 instant; {@link relativeLastUsed} formats it
	 * relatively for display. (Legacy records may hold the old `'just now'`
	 * label — the formatter falls back gracefully.)
	 *
	 * This is UI-level only: it does **not** write the profile to the DE1.
	 * Uploading a profile to the machine needs the DE1 profile-upload path,
	 * which the core does not yet expose.
	 *
	 * TODO: wire to DE1 profile upload — when the core gains a profile-upload
	 * command, `setActive` should also assemble and upload the profile.
	 */
	setActive(id: string | null): void {
		this.activeId = id;
		writeJson(ACTIVE_KEY, id);
		// Push the new active profile's recipe targets to the core so SAW
		// + SAV arming consult the right values on the next shot — works
		// for Crema-tap and GHC-tap starts alike (the core latches the
		// values and reads them on `ShotEvent::Started`).
		void this.pushActiveProfileToCore();
		if (id == null) return;
		// Stamp the current instant as the last-used timestamp (ISO-8601).
		const stamp = new Date().toISOString();
		const base = this.builtins.find((b) => b.id === id);
		if (base) {
			const current = this.overrides[id] ?? {
				pinned: base.pinned,
				lastUsed: base.lastUsed
			};
			this.overrides = {
				...this.overrides,
				[id]: { ...current, lastUsed: stamp }
			};
			this.persistOverrides();
		} else {
			const p = this.custom.find((c) => c.id === id);
			if (p) this.save({ ...p, lastUsed: stamp });
		}
	}
}

/** The process-wide singleton — one library shared by every route. */
let store: ProfileStore | undefined;

/** Get the shared {@link ProfileStore}, creating it on first call. */
export function getProfileStore(): ProfileStore {
	if (!store) store = new ProfileStore();
	return store;
}
