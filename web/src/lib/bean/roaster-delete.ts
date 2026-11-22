/**
 * `#lib/bean/roaster-delete.js` — what a roaster delete removes, delegated to the
 * Rust core (`de1_domain::plan_roaster_delete`) so the Android shell plans it
 * identically: detach (keep the linked bags, clear their roaster) or cascade
 * (delete them too), plus the Visualizer ids an "also delete on Visualizer"
 * sends — every synced deleted bag, then the roaster — and what the
 * active-bag pointer becomes (cleared when the cascade deletes the active
 * bag; no other bag is made active, REVIEW-CORE M3). A roaster on Visualizer
 * leaves a tombstone, and `localOnly` ("from this device only") keeps its bags
 * filed under it (#488 review SF1).
 */

import type { RoasterDeletePlan } from '#lib/core/crema-core.js';
import { planRoasterDelete as wasmPlanRoasterDelete } from '#lib/wasm/de1_wasm.js';
import type { Bean, Roaster } from './model';

/** The delete plan, or `null` when the roaster isn't in the directory. */
export function planRoasterDelete(
	roasters: readonly Roaster[],
	beans: readonly Bean[],
	roasterId: string,
	cascade: boolean,
	activeBeanId: string | null = null,
	localOnly = false
): RoasterDeletePlan | null {
	return JSON.parse(
		wasmPlanRoasterDelete(
			JSON.stringify({ roasters, beans, roasterId, cascade, activeBeanId: activeBeanId ?? undefined, localOnly })
		)
	) as RoasterDeletePlan | null;
}
