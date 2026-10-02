/**
 * `$lib/bean/roaster-delete` — what a roaster delete removes, delegated to the
 * Rust core (`de1_domain::plan_roaster_delete`) so the Android shell plans it
 * identically: detach (keep the linked bags, clear their roaster) or cascade
 * (delete them too), plus the Visualizer ids an "also delete on Visualizer"
 * sends — every synced deleted bag, then the roaster.
 */

import type { RoasterDeletePlan } from '$lib/core/crema-core';
import { planRoasterDelete as wasmPlanRoasterDelete } from '$lib/wasm/de1_wasm';
import type { Bean, Roaster } from './model';

/** The delete plan, or `null` when the roaster isn't in the directory. */
export function planRoasterDelete(
	roasters: readonly Roaster[],
	beans: readonly Bean[],
	roasterId: string,
	cascade: boolean
): RoasterDeletePlan | null {
	return JSON.parse(
		wasmPlanRoasterDelete(JSON.stringify({ roasters, beans, roasterId, cascade }))
	) as RoasterDeletePlan | null;
}
