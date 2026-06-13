/**
 * `$lib/maintenance` — the water-accumulation & maintenance layer.
 *
 * The DE1 keeps no cumulative water-volume counter; the shell derives one by
 * integrating group flow over time (the de1app's approach). The running litre
 * total is persisted, and the Settings → Water section's filter / descale /
 * clean readouts derive from it plus user-set intervals. See
 * `store.svelte.ts`.
 */

export { MaintenanceStore, getMaintenanceStore } from './store.svelte';
// The state/readout shapes are the typeshare-generated core types — one source.
export type { MaintenanceState, MaintenanceReadout } from '$lib/core/crema-core';
