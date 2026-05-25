/**
 * `$lib/maintenance` — the water-accumulation & maintenance layer.
 *
 * The DE1 keeps no cumulative water-volume counter; the shell derives one by
 * integrating group flow over time (the de1app's approach). The running litre
 * total is persisted, and the Settings → Water section's filter / descale /
 * clean readouts derive from it plus user-set intervals. See
 * `store.svelte.ts`.
 */

export {
	MaintenanceStore,
	getMaintenanceStore,
	type MaintenanceState,
	type MaintenanceReadout
} from './store.svelte';
