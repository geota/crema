/**
 * `bestEffortRemoteDelete` — fire Visualizer DELETEs for an already-captured set
 * of remote ids, best-effort and quiet.
 *
 * Shared by the bean + roaster delete split-buttons (`BeanDeleteSplit`,
 * `RoasterDeleteSplit`). Both delete locally first, then call this with the
 * Visualizer ids captured *before* the local rows were removed. The posture is
 * deliberately quiet — the local delete already succeeded, so a remote failure
 * is logged, not surfaced:
 *
 *   - Free tier (`premium === false`) can't write the bag / roaster endpoints,
 *     so skip the round-trip entirely.
 *   - No app runtime yet (`services === null`) → nothing to call; skip.
 *   - Bags are deleted before the roaster, matching the cascade's old order
 *     (`deleteRoasterAndBeans` removed each bag before the roaster).
 *   - Each failure is `console.warn`ed and swallowed; one failure never blocks
 *     the rest.
 *
 * `services` is passed in (not read here) because the caller obtains it from
 * `getCremaAppContext()`, which is `getContext`-based and can only run inside a
 * component — a plain module can't reach it.
 *
 * @param services  the app-runtime service facade (`appCtx().services`), or null
 * @param beanIds   Visualizer ids of bags to delete (empty for a roaster-only detach)
 * @param roasterId Visualizer id of the roaster to delete last, if any
 */
import { readSyncSettings } from '$lib/bean';
import type { CremaServices } from '$lib/effect/crema-services';

export async function bestEffortRemoteDelete(
	services: CremaServices | null,
	beanIds: string[],
	roasterId: string | null = null
): Promise<void> {
	if (readSyncSettings().premium === false) return;
	if (!services) return;
	const warn = (e: unknown): void =>
		console.warn('Visualizer delete failed:', e instanceof Error ? e.message : String(e));
	for (const id of beanIds) {
		await services.beans.deleteBean(id).catch(warn);
	}
	if (roasterId) await services.beans.deleteRoaster(roasterId).catch(warn);
}
