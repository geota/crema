/**
 * `#lib/utils/ratio.js` — the single brew-ratio formatter.
 *
 * Both the History page (ratio per recorded shot) and the Profile library
 * (ratio per profile target) need a `1:N`-style label from a `(dose,
 * yield)` pair. The number itself comes from `core::de1_domain::brew_ratio`
 * via the wasm bridge — one canonical implementation, identical across
 * the web shell and any future Android shell.
 *
 * The format is `1:<one-decimal>` with a trailing ".0" dropped (e.g. `1:2.4`,
 * `1:2`); a missing or invalid
 * pair (zero/negative/NaN dose, missing yield) renders as `1:—`. Callers
 * pass the raw numbers — no defaulting on the shell side, so the
 * History fallback to the 18 g pref is decided at the call site.
 */

import { brew_ratio, ratioLabel } from '#lib/wasm/de1_wasm.js';

/**
 * Format a brew ratio as `1:N` — the core's one ratio label (`ratio_label`),
 * shared with the hot-water ratio: one decimal with a trailing ".0" dropped
 * (`1:2`, `1:2.4`), `1:—` when undefined.
 */
export function formatRatio(
	dose: number | null | undefined,
	yieldOut: number | null | undefined
): string {
	if (dose == null || yieldOut == null) return ratioLabel(undefined);
	return ratioLabel(brew_ratio(dose, yieldOut) ?? undefined);
}
