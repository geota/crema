/**
 * `$lib/state/ui-state` — the `MainUiState`-equivalent Svelte 5 runes store,
 * plus the event-folding logic. The web mirror of the Android shell's
 * `MainUiState` data class and `MainViewModel.applyEvent`.
 *
 * A flat snapshot of everything the screen shows. The Android shell models it
 * as an immutable `data class` inside a `StateFlow`; the web shell models it
 * as a `$state`-backed class — assigning a field re-renders any view that
 * reads it. The 21 fields and their defaults match Android one-for-one.
 *
 * The folding (`applyEvent`) is exported as a **pure function** over a plain
 * snapshot so it can be unit-tested without a rune runtime — `CremaUiState`
 * then just applies the returned snapshot.
 */

import type { Event, ScaleCapabilities } from '$lib/core';
import type { De1State } from '$lib/ble/de1';
import type { ScaleState } from '$lib/ble/scale';

/** Default scale beeper-volume step shown before the first live reading. */
export const DEFAULT_SCALE_VOLUME = 3;

/** Default scale auto-standby timeout (minutes) shown before the first reading. */
export const DEFAULT_SCALE_STANDBY_MINUTES = 15;

/** Cap on the rolling event log — newest-first, oldest dropped past this. */
export const MAX_LOG_LINES = 200;

/**
 * A plain, immutable snapshot of the 21 UI fields — the shape `applyEvent`
 * folds over. `CremaUiState` holds one of these in a `$state` rune.
 */
export interface UiSnapshot {
	/** Coarse state of the DE1 connection. */
	readonly de1State: De1State;
	/** Coarse state of the scale connection. */
	readonly scaleState: ScaleState;
	/** Most recent status line (connect / error transitions). */
	readonly status: string;
	/** Latest decoded machine state + substate, or null before the first one. */
	readonly machineState: string | null;
	/** Latest shot phase. */
	readonly shotPhase: string | null;
	/** Latest telemetry sample, pre-formatted. */
	readonly telemetry: string | null;
	/** Latest scale weight in grams, or null before the first reading. */
	readonly scaleWeightG: number | null;
	/** Latest scale-reported native mass-flow rate in g/s, or null. */
	readonly scaleFlowGPerS: number | null;
	/** Latest scale-reported built-in-timer reading in ms, or null. */
	readonly scaleTimerMs: number | null;
	/** What the connected scale can do, or null before a scale is connected. */
	readonly scaleCapabilities: ScaleCapabilities | null;
	/** The scale beeper-volume step the UI currently shows (two-way). */
	readonly scaleVolume: number;
	/** The scale auto-standby timeout (minutes) the UI shows (two-way). */
	readonly scaleStandbyMinutes: number;
	/** The scale's battery charge percentage, or null. Read-only. */
	readonly scaleBatteryPercent: number | null;
	/** The connected scale's BLE advertised name, or null. Read-only. */
	readonly scaleName: string | null;
	/** The connected scale's firmware version `"M.m.p"`, or null. Read-only. */
	readonly scaleFirmware: string | null;
	/** The connected scale's serial number, or null. Read-only. */
	readonly scaleSerial: string | null;
	/** Whether the scale's flow-smoothing toggle is shown as on (two-way). */
	readonly scaleFlowSmoothing: boolean;
	/** The scale's selected auto-stop mode id (0/1), or null (two-way). */
	readonly scaleAutoStop: number | null;
	/** Whether the scale's anti-mistouch toggle is shown as on (two-way). */
	readonly scaleAntiMistouch: boolean;
	/** The scale's active display-mode index (0/1/2), or null (two-way). */
	readonly scaleActiveMode: number | null;
	/** A rolling event log, newest first. */
	readonly eventLog: readonly string[];
}

/** The initial snapshot — every default matches the Android `MainUiState`. */
export const INITIAL_SNAPSHOT: UiSnapshot = {
	de1State: 'idle',
	scaleState: 'idle',
	status: 'Idle',
	machineState: null,
	shotPhase: null,
	telemetry: null,
	scaleWeightG: null,
	scaleFlowGPerS: null,
	scaleTimerMs: null,
	scaleCapabilities: null,
	scaleVolume: DEFAULT_SCALE_VOLUME,
	scaleStandbyMinutes: DEFAULT_SCALE_STANDBY_MINUTES,
	scaleBatteryPercent: null,
	scaleName: null,
	scaleFirmware: null,
	scaleSerial: null,
	scaleFlowSmoothing: false,
	scaleAutoStop: null,
	scaleAntiMistouch: false,
	scaleActiveMode: null,
	eventLog: []
};

/**
 * Format the Bookoo's `u16` firmware version into a `"M.m.p"` string. The
 * scale encodes it as `major × 100 + minor × 10 + patch` (e.g. `141` → 1.4.1).
 */
export function formatFirmware(encoded: number): string {
	const major = Math.floor(encoded / 100);
	const minor = Math.floor(encoded / 10) % 10;
	const patch = encoded % 10;
	return `${major}.${minor}.${patch}`;
}

/**
 * Prepend a timestamped line to the log, capping it at {@link MAX_LOG_LINES}.
 * Newest-first, exactly like the Android shell's `appendLog`.
 */
function appendLog(log: readonly string[], line: string): readonly string[] {
	const time = new Date().toLocaleTimeString('en-GB', { hour12: false });
	return [`${time}  ${line}`, ...log].slice(0, MAX_LOG_LINES);
}

/**
 * Fold one core `Event` into a snapshot, returning the next snapshot.
 *
 * A faithful, pure port of the Android shell's `MainViewModel.applyEvent`:
 * the same events update the same fields, the same events append a log line,
 * and — like Android — `Telemetry` and `ScaleReading` are **not** logged
 * (they are high-rate). The result is a fresh object; the input is untouched.
 */
export function applyEvent(snapshot: UiSnapshot, event: Event): UiSnapshot {
	switch (event.type) {
		case 'MachineStateChanged': {
			const machineState = `${event.content.state} / ${event.content.substate}`;
			return {
				...snapshot,
				machineState,
				eventLog: appendLog(snapshot.eventLog, `MachineState -> ${machineState}`)
			};
		}
		case 'ShotStarted':
			return { ...snapshot, eventLog: appendLog(snapshot.eventLog, 'Shot started') };
		case 'ShotPhaseChanged': {
			const phase = event.content.phase;
			return {
				...snapshot,
				shotPhase: phase,
				eventLog: appendLog(snapshot.eventLog, `Shot phase -> ${phase}`)
			};
		}
		case 'ShotFrameChanged':
			return {
				...snapshot,
				eventLog: appendLog(snapshot.eventLog, `Shot frame -> ${event.content.frame}`)
			};
		case 'Telemetry': {
			const t = event.content;
			// Telemetry is high-rate — update the readout, never log it.
			const line =
				`t=${Math.round(t.elapsed_ms)}ms  P=${t.group_pressure.toFixed(1)}bar  ` +
				`flow=${t.group_flow.toFixed(1)}mL/s  head=${t.head_temp.toFixed(1)}°C`;
			return { ...snapshot, telemetry: line };
		}
		case 'ScaleReading': {
			const r = event.content;
			// The Bookoo echoes its live settings in every weight notification.
			// `device_*` is absent for scales that do not report a setting — in
			// which case the field keeps its last value. High-rate: not logged.
			return {
				...snapshot,
				scaleWeightG: r.weight_g,
				scaleFlowGPerS: r.device_flow_g_per_s ?? null,
				scaleTimerMs: r.device_timer_ms ?? null,
				scaleVolume: r.device_volume ?? snapshot.scaleVolume,
				scaleStandbyMinutes: r.device_standby_minutes ?? snapshot.scaleStandbyMinutes,
				scaleBatteryPercent: r.device_battery_percent ?? snapshot.scaleBatteryPercent,
				scaleFlowSmoothing: r.device_flow_smoothing ?? snapshot.scaleFlowSmoothing,
				scaleAutoStop: r.device_auto_stop ?? snapshot.scaleAutoStop
			};
		}
		case 'WaterLevel':
			return {
				...snapshot,
				eventLog: appendLog(
					snapshot.eventLog,
					`Water level: ${Math.round(event.content.level_mm)}mm`
				)
			};
		case 'StopTriggered':
			return {
				...snapshot,
				eventLog: appendLog(snapshot.eventLog, `Auto-stop: ${event.content.reason}`)
			};
		case 'ShotCompleted':
			return {
				...snapshot,
				eventLog: appendLog(
					snapshot.eventLog,
					`Shot completed: ${event.content.duration_ms}ms, ` +
						`${event.content.sample_count} samples`
				)
			};
		case 'WaterSessionStarted':
			return {
				...snapshot,
				eventLog: appendLog(
					snapshot.eventLog,
					`Water session started: ${event.content.kind}`
				)
			};
		case 'WaterSessionCompleted':
			return {
				...snapshot,
				eventLog: appendLog(
					snapshot.eventLog,
					`Water session completed: ${event.content.kind}`
				)
			};
		case 'SteamSessionStarted':
			return {
				...snapshot,
				eventLog: appendLog(snapshot.eventLog, 'Steam session started')
			};
		case 'SteamSessionCompleted':
			return {
				...snapshot,
				eventLog: appendLog(
					snapshot.eventLog,
					`Steam session completed: ${event.content.duration_ms}ms`
				)
			};
		case 'SteamClogSuspected':
			return {
				...snapshot,
				eventLog: appendLog(
					snapshot.eventLog,
					`Steam clog suspected: ${event.content.reason}`
				)
			};
		case 'SteamEcoModeChanged':
			return {
				...snapshot,
				eventLog: appendLog(snapshot.eventLog, `Steam eco mode: ${event.content.eco}`)
			};
		case 'ScaleStale':
			return {
				...snapshot,
				scaleWeightG: null,
				eventLog: appendLog(snapshot.eventLog, 'Scale stale')
			};
		case 'ScaleConfig': {
			const c = event.content;
			// The Bookoo's `ff12` channel reports its dynamic config: a `03 0c`
			// serial response carries anti_mistouch (plus serial / firmware), a
			// `03 0e` settings response carries active_mode. Any one frame fills
			// only one set — fold in whatever is present, keep the last value
			// for the rest, exactly the Android two-way pattern.
			const next: UiSnapshot = {
				...snapshot,
				scaleAntiMistouch: c.anti_mistouch ?? snapshot.scaleAntiMistouch,
				scaleActiveMode: c.active_mode ?? snapshot.scaleActiveMode,
				scaleFirmware:
					c.firmware_version !== undefined
						? formatFirmware(c.firmware_version)
						: snapshot.scaleFirmware,
				scaleSerial: c.serial ?? snapshot.scaleSerial
			};
			if (c.anti_mistouch !== undefined) {
				const serial = c.serial !== undefined ? `, serial=${c.serial}` : '';
				const fw =
					c.firmware_version !== undefined
						? `, fw=${formatFirmware(c.firmware_version)}`
						: '';
				return {
					...next,
					eventLog: appendLog(
						snapshot.eventLog,
						`Scale config: anti-mistouch=${c.anti_mistouch}${serial}${fw}`
					)
				};
			}
			if (c.active_mode !== undefined) {
				return {
					...next,
					eventLog: appendLog(
						snapshot.eventLog,
						`Scale config: active mode=${c.active_mode}, ` +
							`enabled modes=${c.enabled_modes}`
					)
				};
			}
			return next;
		}
		case 'DecodeError':
			return {
				...snapshot,
				eventLog: appendLog(snapshot.eventLog, `Decode error: ${event.content.message}`)
			};
	}
}

/**
 * The reactive UI state — a thin `$state` wrapper over a {@link UiSnapshot}.
 *
 * The orchestrator mutates it via {@link applyEvent} and the connection-state
 * setters; the screen reads `state.current`, which is reactive in any Svelte 5
 * component. The web equivalent of the Android shell's `StateFlow<MainUiState>`.
 */
export class CremaUiState {
	/** The current snapshot — reactive; assigning re-renders readers. */
	current = $state<UiSnapshot>(INITIAL_SNAPSHOT);

	/** Fold one core `Event` into the snapshot. */
	applyEvent(event: Event): void {
		this.current = applyEvent(this.current, event);
	}

	/** Patch arbitrary snapshot fields — used by connection-state plumbing. */
	patch(partial: Partial<UiSnapshot>): void {
		this.current = { ...this.current, ...partial };
	}
}
