/**
 * `#lib/profiles/core-types.js` — a thin re-export of the profile types the
 * profiles module needs, so `model.ts` has one stable import surface.
 *
 * `Profile` / `ProfileStep` come from the `#lib/core/index.js` facade (the JSON shape
 * the wasm bridge's `builtin_profiles_json()` produces); `SparkShape` comes
 * from the brew components' `QuickSparkline`. Collecting them here keeps the rest
 * of the module free of deep import paths.
 */

export type { Profile, ProfileStep } from '#lib/core/index.js';
export type { SparkShape } from '#lib/components/brew/QuickSparkline.svelte';
