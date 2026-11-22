/**
 * `#lib/bean/import-merge.js` — how an imported bean library merges into the
 * local one, delegated to the Rust core (`de1_domain::merge_bean_import`) so
 * Android merges identically: a shared id goes to the newer `updatedAt` (a tie
 * keeps local; an older row never resurrects a local tombstone), an earlier
 * Beanconqueror import and a (roaster, name, roast date) match are skipped, and
 * a same-named library roaster is reused.
 */

import type { BeanImportMerge } from '#lib/core/crema-core.js';
import { mergeBeanImportJson } from '#lib/wasm/de1_wasm.js';
import type { Grinder } from './grinder';
import type { Bean, Roaster } from './model';

/**
 * Grinder records (#488) merge by id, then by name, and the bags' saved
 * grinds are repointed to match; the caller runs the library migration
 * after applying it, so bags from an older app get their saved grinds.
 */
export function mergeBeanImport(
	incomingBeans: readonly Bean[],
	incomingRoasters: readonly Roaster[],
	libraryBeans: readonly Bean[],
	libraryRoasters: readonly Roaster[],
	incomingGrinders: readonly Grinder[] = [],
	libraryGrinders: readonly Grinder[] = []
): BeanImportMerge {
	return JSON.parse(
		mergeBeanImportJson(
			JSON.stringify({
				incomingBeans,
				incomingRoasters,
				libraryBeans,
				libraryRoasters,
				incomingGrinders,
				libraryGrinders
			})
		)
	) as BeanImportMerge;
}
