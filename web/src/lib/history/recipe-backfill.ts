/**
 * `recipe-backfill` — lazily complete a pulled shot's recipe (#12).
 *
 * A shot pulled from Visualizer carries only its profile *name*; the recipe
 * lives at the separate `/shots/{id}/profile` endpoint (the shot detail has no
 * step list). When such a shot is exported or backed up, fetch the recipe once,
 * attach it to {@link StoredShot.profile}, and persist it — cached forever, so
 * the next export/backup is offline-clean.
 *
 * Best-effort: a private shot / offline / parse failure just leaves the shot
 * as-is (name only). Unlisted shots (the sync default) are publicly fetchable
 * by id, so no auth token is needed for the common case.
 *
 * The v2-JSON → core `Profile` conversion crosses the wasm bridge, which is only
 * reachable through the Svelte-context-bound `CremaApp`; callers (components)
 * pass its `parseV2JsonProfile` in as {@link parseV2Json} so this module stays
 * context-free.
 */
import { getHistoryStore } from './store.svelte';
import { API_BASE } from '$lib/services/visualizer-call';
import type { StoredShot } from './model';
import type { Profile } from '$lib/core';

/** Parse a community-v2 profile JSON into the core `Profile` (via the wasm bridge). */
export type ParseV2Json = (v2json: string) => Promise<Profile>;

/**
 * Return `shot` with its recipe filled in, fetching + caching it from Visualizer
 * if the shot is a profile-less pulled shot. A no-op (returns `shot` unchanged)
 * when it already has a recipe, was never uploaded, or the fetch fails.
 */
export async function ensureShotRecipe(
	shot: StoredShot,
	parseV2Json: ParseV2Json
): Promise<StoredShot> {
	if (shot.profile || !shot.visualizerId) return shot;
	try {
		const res = await fetch(`${API_BASE}/shots/${shot.visualizerId}/profile?format=json`, {
			headers: { Accept: 'application/json' }
		});
		if (!res.ok) return shot;
		const v2json = await res.text();
		if (!v2json.trim()) return shot;
		const profile = await parseV2Json(v2json);
		// The shot may itself have been uploaded profile-less (an empty stub);
		// don't cache a recipe with no steps.
		if (!profile.steps || profile.steps.length === 0) return shot;
		getHistoryStore().setProfile(shot.id, profile);
		return { ...shot, profile };
	} catch {
		return shot;
	}
}

/**
 * Back-fill every profile-less pulled shot's recipe — called before a whole-app
 * backup so the archive is self-contained. Sequential (the profile-less set is
 * usually small) to avoid hammering the API.
 */
export async function backfillShotRecipes(parseV2Json: ParseV2Json): Promise<void> {
	const pending = getHistoryStore().all.filter((s) => !s.profile && s.visualizerId);
	for (const shot of pending) await ensureShotRecipe(shot, parseV2Json);
}
