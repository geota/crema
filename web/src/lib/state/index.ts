/**
 * `$lib/state` — the runes state layer: the `MainUiState`-equivalent store,
 * the pure event-folding logic, and the `MainViewModel`-equivalent
 * orchestrator. Mirrors the Android shell's `ui` package.
 */

export {
	CremaUiState,
	applyEvent,
	formatFirmware,
	waterTankMl,
	waterRefillSoon,
	INITIAL_SNAPSHOT,
	DEFAULT_SCALE_VOLUME,
	DEFAULT_SCALE_STANDBY_MINUTES,
	MAX_LOG_LINES,
	MAX_TELEMETRY_SAMPLES,
	type UiSnapshot,
	type TelemetrySample,
	type ReplayStatus,
	type ReplayPhase
} from './ui-state.svelte';
export { CremaApp, createCremaApp, type ReplayCaptureOptions } from './app.svelte';
