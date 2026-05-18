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

import { loadCore } from '$lib/core';
import {
	fromCoreProfile,
	type CremaProfile
} from './model';

/** localStorage key for the user's custom profiles (a `CremaProfile[]`). */
const CUSTOM_KEY = 'crema.profiles.custom.v1';

/**
 * localStorage key for per-built-in overrides — a map of `builtin:<n>` →
 * `{ pinned, lastUsed }`. Built-ins themselves are not stored (they come from
 * the core); only the user-owned bits of their state are.
 */
const OVERRIDES_KEY = 'crema.profiles.builtinOverrides.v1';

/** localStorage key for the id of the profile marked "active" on Brew. */
const ACTIVE_KEY = 'crema.profiles.activeId.v1';

/** The user-owned, persisted slice of a built-in profile's state. */
interface BuiltinOverride {
	/** Whether the user pinned this built-in to favorites. */
	pinned: boolean;
	/** A human "last used" label, or null. */
	lastUsed: string | null;
}

/** Read + parse a localStorage value, falling back to `fallback` on any error. */
function readJson<T>(key: string, fallback: T): T {
	if (typeof localStorage === 'undefined') return fallback;
	try {
		const raw = localStorage.getItem(key);
		return raw == null ? fallback : (JSON.parse(raw) as T);
	} catch {
		return fallback;
	}
}

/** Write a JSON value to localStorage, swallowing quota / availability errors. */
function writeJson(key: string, value: unknown): void {
	if (typeof localStorage === 'undefined') return;
	try {
		localStorage.setItem(key, JSON.stringify(value));
	} catch {
		// Static PWA with no server — a write failure is non-fatal; the live
		// in-memory state still reflects the change for this session.
	}
}

/**
 * The reactive profile library. One instance per app — {@link getProfileStore}.
 */
export class ProfileStore {
	/** The built-in profiles, adapted from the core. Empty until loaded. */
	private builtins = $state<CremaProfile[]>([]);
	/** The user's custom profiles, from localStorage. */
	private custom = $state<CremaProfile[]>(readJson<CremaProfile[]>(CUSTOM_KEY, []));
	/** Per-built-in user overrides (pin / last-used). */
	private overrides = $state<Record<string, BuiltinOverride>>(
		readJson<Record<string, BuiltinOverride>>(OVERRIDES_KEY, {})
	);
	/** The id of the profile marked active on the Brew dashboard. */
	activeId = $state<string | null>(readJson<string | null>(ACTIVE_KEY, null));
	/** Whether the built-in load has finished (drives the grid's loading state). */
	loaded = $state(false);

	/** A guard so the async built-in load runs exactly once. */
	private loadStarted = false;

	/**
	 * The full library — built-ins (with user overrides folded in) followed by
	 * custom profiles. Reactive: any mutation re-renders the grid.
	 */
	get all(): CremaProfile[] {
		const builtins = this.builtins.map((b) => {
			const ov = this.overrides[b.id];
			return ov ? { ...b, pinned: ov.pinned, lastUsed: ov.lastUsed } : b;
		});
		return [...builtins, ...this.custom];
	}

	/** Look up one profile by id, or `undefined` if it is not in the library. */
	get(id: string): CremaProfile | undefined {
		return this.all.find((p) => p.id === id);
	}

	/**
	 * Kick off the one-time built-in load. Safe to call repeatedly — only the
	 * first call does work. Resolves once the built-ins are in `all`.
	 */
	async ensureLoaded(): Promise<void> {
		if (this.loadStarted) return;
		this.loadStarted = true;
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
		}
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
	}

	/** Delete a custom profile by id and persist. Built-ins cannot be deleted. */
	delete(id: string): void {
		this.custom = this.custom.filter((p) => p.id !== id);
		this.persistCustom();
		if (this.activeId === id) this.setActive(null);
	}

	/**
	 * Toggle a profile's pinned state and persist. A custom profile is updated
	 * in place; a built-in is pinned via the overrides map (built-ins are
	 * read-only, but pinning is the user's own state).
	 */
	togglePin(id: string): void {
		if (id.startsWith('builtin:')) {
			const base = this.builtins.find((b) => b.id === id);
			if (!base) return;
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
	 * Persists the id and stamps a "just now" last-used label on the profile.
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
		if (id == null) return;
		// Stamp "just now" as the last-used label.
		const stamp = 'just now';
		if (id.startsWith('builtin:')) {
			const base = this.builtins.find((b) => b.id === id);
			if (!base) return;
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
