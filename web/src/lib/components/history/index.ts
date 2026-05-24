/**
 * `$lib/components/history` — the Shot History page's view components, a
 * Svelte 5 port of `ShotRow` / `ShotDetail` from the design's
 * `history-page.jsx`.
 *
 * They render a `StoredShot` from the real `lib/history` store; the curve
 * chart (`StaticShotChart`) reuses the live `LiveChart`'s SVG approach over a
 * stored telemetry series.
 */

export { default as ShotRow } from './ShotRow.svelte';
export { default as ShotDetail } from './ShotDetail.svelte';
export { default as StaticShotChart } from './StaticShotChart.svelte';
export { default as CompareOverlay } from './CompareOverlay.svelte';
export { default as BeanPicker } from './BeanPicker.svelte';
