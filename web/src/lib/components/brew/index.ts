/**
 * `$lib/components/brew` — the Brew dashboard's view components, a Svelte 5
 * port of the `variant === 'g'` path of `WebQDashV2` in the design's
 * `web-dashboard-v2.jsx` / `quick-controls-v2.jsx`.
 *
 * `BrewDashboard` is the entry point the `/` route renders; the rest are its
 * parts. The display side is wired to `lib/state` telemetry; the Quick Sheet
 * control surface is UI-only (see `BrewDashboard.svelte`).
 */

export { default as BrewDashboard } from './BrewDashboard.svelte';
export { default as QuickSheet } from './QuickSheet.svelte';
export { default as ExtractionTimer } from './ExtractionTimer.svelte';
export { default as ChannelReadout } from './ChannelReadout.svelte';
export { default as PhaseIndicatorCard } from './PhaseIndicatorCard.svelte';
export { default as BeanContextCard } from './BeanContextCard.svelte';
export { default as LastShotCard } from './LastShotCard.svelte';
export { default as LiveChart } from './LiveChart.svelte';
export { default as FavoritesStrip } from './FavoritesStrip.svelte';
export { default as QStepper } from './QStepper.svelte';
export { default as QChipRow } from './QChipRow.svelte';
export { default as QSparkline } from './QSparkline.svelte';
export { default as QSplitLabel } from './QSplitLabel.svelte';

export { BrewParamState, DEFAULT_BREW_PARAMS, type BrewParams } from './brew-params.svelte';
