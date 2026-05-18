/**
 * `$lib/state` — the runes state layer: the `MainUiState`-equivalent store,
 * the pure event-folding logic, and the `MainViewModel`-equivalent
 * orchestrator. Mirrors the Android shell's `ui` package.
 */

export {
	CremaUiState,
	applyEvent,
	formatFirmware,
	INITIAL_SNAPSHOT,
	DEFAULT_SCALE_VOLUME,
	DEFAULT_SCALE_STANDBY_MINUTES,
	MAX_LOG_LINES,
	type UiSnapshot
} from './ui-state.svelte';
export { CremaApp, createCremaApp } from './app.svelte';
