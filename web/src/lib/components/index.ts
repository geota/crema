/**
 * `$lib/components` — the POC screen's view components, each a faithful port
 * of an Android-shell composable (`MainActivity.kt`). Screen-agnostic: they
 * take the orchestrator or a `UiSnapshot` and render; they own no state.
 */

export { default as Field } from './Field.svelte';
export { default as ConfigStepper } from './ConfigStepper.svelte';
export { default as ConfigToggle } from './ConfigToggle.svelte';
export { default as ModeSelector } from './ModeSelector.svelte';
export { default as AutoStopSelector } from './AutoStopSelector.svelte';
export { default as ScaleCard } from './ScaleCard.svelte';
export { default as ReadoutCard } from './ReadoutCard.svelte';

/** App shell — the fixed left nav rail. */
export { default as CremaSidebar } from './CremaSidebar.svelte';
