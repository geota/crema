/**
 * `$lib/utils/ratio` — the single brew-ratio formatter.
 *
 * Both the History page (ratio per recorded shot) and the Profile library
 * (ratio per profile target) need a `1:N`-style label from a `(dose,
 * yield)` pair. The number itself comes from `core::de1_domain::brew_ratio`
 * via the wasm bridge — one canonical implementation, identical across
 * the web shell and any future Android shell.
 *
 * The format is `1:<one-decimal>` (e.g. `1:2.4`); a missing or invalid
 * pair (zero/negative/NaN dose, missing yield) renders as `1:—`. Callers
 * pass the raw numbers — no defaulting on the shell side, so the
 * History fallback to the 18 g pref is decided at the call site.
 */

import { brew_ratio } from '$lib/wasm/de1_wasm';

/** Format a brew ratio as `1:N` with one decimal, or `1:—` when undefined. */
export function formatRatio(
	dose: number | null | undefined,
	yieldG: number | null | undefined
): string {
	if (dose == null || yieldG == null) return '1:—';
	const r = brew_ratio(dose, yieldG);
	if (r == null || !Number.isFinite(r)) return '1:—';
	return `1:${r.toFixed(1)}`;
}
