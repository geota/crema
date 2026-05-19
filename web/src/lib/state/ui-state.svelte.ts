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
import type { De1State, De1Diagnostics } from '$lib/ble/de1';
import { EMPTY_DE1_DIAGNOSTICS } from '$lib/ble/de1';
import type { ScaleState } from '$lib/ble/scale';

/** Default scale beeper-volume step shown before the first live reading. */
export const DEFAULT_SCALE_VOLUME = 3;

/** Default scale auto-standby timeout (minutes) shown before the first reading. */
export const DEFAULT_SCALE_STANDBY_MINUTES = 15;

/** Cap on the rolling event log — newest-first, oldest dropped past this. */
export const MAX_LOG_LINES = 200;

/**
 * Cap on the buffered shot-telemetry series. At the DE1's ~25 Hz sample rate
 * this holds a little over a minute of samples — longer than any shot — so the
 * cap only ever trims a runaway session, never a real one.
 */
export const MAX_TELEMETRY_SAMPLES = 2000;

/**
 * One decoded telemetry sample, the structured form the dashboard renders.
 * `Event::Telemetry` carries the DE1 channels; the scale weight is folded in
 * from the most recent `ScaleReading` so a sample is a complete 4-channel
 * snapshot at a point in time.
 */
export interface TelemetrySample {
	/** Milliseconds since the shot began; `0` when no shot is in progress. */
	readonly elapsedMs: number;
	/** Group pressure, bar. */
	readonly pressure: number;
	/** Group flow, mL/s. */
	readonly flow: number;
	/** Group-head temperature, °C. */
	readonly temp: number;
	/** Mix temperature, °C — the DE1's blended "group" water temperature. */
	readonly mixTemp: number;
	/** Steam-heater temperature, °C. */
	readonly steamTemp: number;
	/** Latest scale weight at this instant, grams, or `null` if no scale. */
	readonly weightG: number | null;
}

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

	/**
	 * The DE1 water-tank level in mm — the raw depth the tank sensor reports,
	 * or `null` before the first `WaterLevel` notification. Convert to a tank
	 * volume for display with {@link waterTankMl}.
	 */
	readonly waterLevelMm: number | null;
	/**
	 * The DE1 water-tank refill threshold in mm — a refill is wanted at or
	 * below it. `null` before the first `WaterLevel` notification. Drives the
	 * "refill soon" cue (E2).
	 */
	readonly waterRefillThresholdMm: number | null;

	// ---- Structured telemetry (Task 3 — the brew dashboard) --------------
	//
	// `telemetry` above keeps the Android-parity pre-formatted line for the POC
	// readout. The fields below are the *structured* form the brew dashboard
	// renders — the latest 4-channel values and the buffered shot series.

	/** Latest structured telemetry sample, or `null` before the first one. */
	readonly latestTelemetry: TelemetrySample | null;
	/**
	 * The buffered shot-telemetry series — one sample per `Telemetry` event,
	 * oldest first. Reset on `ShotStarted` and on a machine→idle transition;
	 * the `LiveChart` plots it directly.
	 */
	readonly shotTelemetry: readonly TelemetrySample[];
	/** Whether a shot is currently in progress (between Started and Completed). */
	readonly shotInProgress: boolean;
	/** Elapsed time of the current/last shot, ms — `latestTelemetry.elapsedMs`. */
	readonly shotElapsedMs: number;
	/**
	 * Zero-based index of the profile frame the DE1 is currently executing,
	 * from `Event::ShotFrameChanged`. Reset to `0` on `ShotStarted`; drives the
	 * brew dashboard's phase indicator against the active profile's segments.
	 */
	readonly shotFrame: number;

	// ---- Active profile (Task 3 — the Profiles library) ------------------
	//
	// The profile the user marked "active" on the Profiles page — the one the
	// Brew dashboard's header reflects. UI-level only: marking a profile active
	// does NOT upload it to the DE1 (the core has no profile-upload path yet).

	/** The active profile's display name, or `null` when none is selected. */
	readonly activeProfileName: string | null;

	// ---- DE1 connection diagnostics --------------------------------------
	//
	// Proof, after a connect, that the device the chooser selected is genuinely
	// a DE1: the selected device's name + id, whether the `A000` service and
	// the three DE1 characteristics resolved (`gattVerified`), and a live
	// notification tally so the Machine settings panel can show data flowing.

	/** The DE1 connection-diagnostics snapshot, folded in from `De1Manager`. */
	readonly de1Diagnostics: De1Diagnostics;

	// ---- DE1 firmware (doc 10, D4 — the Machine settings card) -----------
	//
	// The DE1's CPU-board firmware label, decoded from the version
	// characteristic and folded in from `Event::Firmware`. Read-only.

	/** The DE1 firmware label, e.g. `"FW 1.4.142 (API 4)"`, or `null`. */
	readonly de1Firmware: string | null;

	// ---- Capture replay (developer tool) ---------------------------------
	//
	// State for the Settings → Advanced "Replay a capture" developer control:
	// playing a recorded BLE capture file back through the core so a shot can
	// be watched with no live machine. Purely UI-level; never persisted.

	/** The current capture-replay status, or `null` when no replay has run. */
	readonly replay: ReplayStatus | null;
}

/** Where a {@link ReplayStatus} is in its lifecycle. */
export type ReplayPhase = 'running' | 'done' | 'cancelled' | 'error';

/** The progress / outcome of a capture replay — drives the Advanced dev control. */
export interface ReplayStatus {
	/** Lifecycle phase. */
	readonly phase: ReplayPhase;
	/** The replayed file's name, for display. */
	readonly fileName: string;
	/** Events delivered so far. */
	readonly done: number;
	/** Total replayable events in the capture. */
	readonly total: number;
	/** A human-readable message — the error text on `phase === 'error'`. */
	readonly message: string;
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
	eventLog: [],
	waterLevelMm: null,
	waterRefillThresholdMm: null,
	latestTelemetry: null,
	shotTelemetry: [],
	shotInProgress: false,
	shotElapsedMs: 0,
	shotFrame: 0,
	activeProfileName: null,
	de1Diagnostics: EMPTY_DE1_DIAGNOSTICS,
	de1Firmware: null,
	replay: null
};

/**
 * The DE1 tank-level → volume lookup table — mL of water in the tank at each
 * integer mm of sensor depth. Ported verbatim from the de1app's
 * `water_tank_level_to_milliliters` (`de1plus/vars.tcl`), the same table the
 * DSx skin uses: the DE1 protocol only reports a depth (mm), so the tank's
 * (non-linear) geometry has to be looked up. Index 0..67 → 0..2058 mL.
 */
const TANK_MM_TO_ML: readonly number[] = [
	0, 16, 43, 70, 97, 124, 151, 179, 206, 233, 261, 288, 316, 343, 371, 398,
	426, 453, 481, 509, 537, 564, 592, 620, 648, 676, 704, 732, 760, 788, 816,
	844, 872, 900, 929, 957, 985, 1013, 1042, 1070, 1104, 1138, 1172, 1207,
	1242, 1277, 1312, 1347, 1382, 1417, 1453, 1488, 1523, 1559, 1594, 1630,
	1665, 1701, 1736, 1772, 1808, 1843, 1879, 1915, 1951, 1986, 2022, 2058
];

/**
 * Convert a DE1 tank-level reading (mm, see {@link UiSnapshot.waterLevelMm})
 * to the tank's water volume in mL via {@link TANK_MM_TO_ML}. Returns `null`
 * for a missing reading; a depth past the table's range clamps to the 2058 mL
 * ceiling — matching the de1app / DSx behaviour.
 */
export function waterTankMl(mm: number | null | undefined): number | null {
	if (mm == null || !Number.isFinite(mm)) return null;
	const i = Math.trunc(mm);
	return i >= 0 && i < TANK_MM_TO_ML.length ? TANK_MM_TO_ML[i] : 2058;
}

/**
 * The mm of headroom above the refill threshold at or below which a "refill
 * soon" cue is shown (E2) — the tank is close to, but not yet at, the DE1's
 * own refill point.
 */
const REFILL_SOON_MARGIN_MM = 5;

/**
 * Whether the tank is low enough to warrant a "refill soon" cue — the level
 * is within {@link REFILL_SOON_MARGIN_MM} of (or already below) the DE1's
 * refill threshold. `false` when either reading is missing.
 */
export function waterRefillSoon(
	levelMm: number | null | undefined,
	thresholdMm: number | null | undefined
): boolean {
	if (levelMm == null || thresholdMm == null) return false;
	return levelMm <= thresholdMm + REFILL_SOON_MARGIN_MM;
}

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
			// A drop to a resting state (Idle / Sleep) ends any shot in progress,
			// but the finished curve STAYS on screen — it is reset only when the
			// next shot starts (`ShotStarted`) or on connect/disconnect. Clearing
			// `shotTelemetry` here wiped the just-finished shot the instant the
			// machine idled — and wiped a whole replayed capture, which always
			// ends with a return to Idle.
			const resting =
				event.content.state === 'Idle' ||
				event.content.state === 'Sleep' ||
				event.content.state === 'SchedIdle' ||
				event.content.state === 'GoingToSleep';
			return {
				...snapshot,
				machineState,
				...(resting ? { shotInProgress: false } : null),
				eventLog: appendLog(snapshot.eventLog, `MachineState -> ${machineState}`)
			};
		}
		case 'ShotStarted':
			// A new shot begins — clear the buffered series and arm the timer.
			return {
				...snapshot,
				shotTelemetry: [],
				shotInProgress: true,
				shotElapsedMs: 0,
				shotFrame: 0,
				eventLog: appendLog(snapshot.eventLog, 'Shot started')
			};
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
				shotFrame: event.content.frame,
				eventLog: appendLog(snapshot.eventLog, `Shot frame -> ${event.content.frame}`)
			};
		case 'Telemetry': {
			const t = event.content;
			// Telemetry is high-rate — update the readout, never log it.
			const line =
				`t=${Math.round(t.elapsed_ms)}ms  P=${t.group_pressure.toFixed(1)}bar  ` +
				`flow=${t.group_flow.toFixed(1)}mL/s  head=${t.head_temp.toFixed(1)}°C`;
			// Build the structured sample, folding in the latest scale weight so
			// every sample is a complete 4-channel snapshot.
			const sample: TelemetrySample = {
				elapsedMs: t.elapsed_ms,
				pressure: t.group_pressure,
				flow: t.group_flow,
				temp: t.head_temp,
				mixTemp: t.mix_temp,
				steamTemp: t.steam_temp,
				weightG: snapshot.scaleWeightG
			};
			// Append to the series; only buffer while a shot is in progress so
			// idle-state telemetry never grows the chart. Cap the length.
			const series = snapshot.shotInProgress
				? [...snapshot.shotTelemetry, sample].slice(-MAX_TELEMETRY_SAMPLES)
				: snapshot.shotTelemetry;
			return {
				...snapshot,
				telemetry: line,
				latestTelemetry: sample,
				shotTelemetry: series,
				shotElapsedMs: t.elapsed_ms
			};
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
				waterLevelMm: event.content.level_mm,
				waterRefillThresholdMm: event.content.refill_threshold_mm,
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
			// The shot ended — stop the timer, but keep the buffered series so
			// the finished curve stays on screen until the next shot or idle.
			return {
				...snapshot,
				shotInProgress: false,
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
		case 'Firmware':
			// The DE1 reported its firmware versions — surface the label on the
			// Machine settings card. Logged once; it arrives at connect time.
			return {
				...snapshot,
				de1Firmware: event.content.firmware_string,
				eventLog: appendLog(
					snapshot.eventLog,
					`DE1 firmware: ${event.content.firmware_string}`
				)
			};
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

	/**
	 * Append a one-off line to the event log for something that is not a core
	 * `Event` — e.g. an outgoing command write. Same newest-first 200-line cap
	 * and `HH:mm:ss` stamp as event-driven log lines.
	 */
	log(line: string): void {
		this.current = { ...this.current, eventLog: appendLog(this.current.eventLog, line) };
	}
}
