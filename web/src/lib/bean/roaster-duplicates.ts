/**
 * `$lib/bean/roaster-duplicates` — the Roasters tab's duplicate detection +
 * merge bookkeeping, delegated to the Rust core (`de1_domain::bean_sync`) so
 * the Android shell suggests and merges identically.
 *
 * - {@link detectRoasterDuplicates}: rows sharing a normalised name (trimmed,
 *   case-insensitive) that are not already tagged; the most-recently-updated
 *   row of each group is the canonical one.
 * - {@link planRoasterMerge}: the bags to re-point at the canonical row before
 *   the dupe is tagged (`canonicalRoasterId = canonical.id`). The dupe row is
 *   kept, so clearing that pointer un-merges it.
 */

import type { RoasterDuplicate, RoasterMergePlan } from '$lib/core/crema-core';
import {
	detectRoasterDuplicates as wasmDetectRoasterDuplicates,
	planRoasterMerge as wasmPlanRoasterMerge
} from '$lib/wasm/de1_wasm';
import type { Bean, Roaster } from './model';

/** Probable duplicate pairs (`canonicalId` keeps, `dupeId` folds in). */
export function detectRoasterDuplicates(roasters: readonly Roaster[]): RoasterDuplicate[] {
	return JSON.parse(wasmDetectRoasterDuplicates(JSON.stringify(roasters))) as RoasterDuplicate[];
}

/** The merge plan, or `null` when the merge can't be done (same / missing / chained rows). */
export function planRoasterMerge(
	roasters: readonly Roaster[],
	beans: readonly Bean[],
	canonicalId: string,
	dupeId: string
): RoasterMergePlan | null {
	return JSON.parse(
		wasmPlanRoasterMerge(JSON.stringify({ roasters, beans, canonicalId, dupeId }))
	) as RoasterMergePlan | null;
}
