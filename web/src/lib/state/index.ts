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
	machineErrorText,
	EMPTY_DE1_CALIBRATION,
	INITIAL_SNAPSHOT,
	DEFAULT_SCALE_VOLUME,
	DEFAULT_SCALE_STANDBY,
	MAX_LOG_LINES,
	MAX_TELEMETRY_SAMPLES,
	type UiSnapshot,
	type LogLine,
	type TelemetrySample,
	type CompletedShot,
	type De1MachineInfo,
	type De1Calibration,
	type SensorCalibration,
	type ReplayStatus,
	type ReplayPhase
} from './ui-state.svelte';
export {
	CremaApp,
	createCremaApp,
	NoActiveProfileError,
	ProfileSyncFailedError,
	type ReplayCaptureOptions
} from './app.svelte';
export {
	ActiveShotStore,
	getActiveShotStore,
	type ActiveShotData,
	type ActiveShotBrewParams
} from './active-shot.svelte';
export { MachineReadout, getMachineReadout, bindMachineReadout } from './machine-readout.svelte';
export { BrewContext, getBrewContext, bindBrewContext } from './brew-context.svelte';
export {
	HistoryContext,
	getHistoryContext,
	bindHistoryContext,
	type ShotView
} from './history-context.svelte';
