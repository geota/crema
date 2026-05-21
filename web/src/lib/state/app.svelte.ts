/**
 * `$lib/state/app` — the orchestrator: the web mirror of the Android shell's
 * `MainViewModel`. Ties the core, the BLE layer, and the runes state together
 * and exposes the actions the screen calls.
 *
 * It owns one {@link CremaCore}, one {@link De1Manager}, one {@link
 * ScaleManager}, and one {@link CremaUiState}. Every `CoreOutput` — from a DE1
 * notification, a scale notification, or a config action — funnels through
 * {@link applyCoreOutput}: events fold into the state, commands run. Scale
 * config actions are **optimistic** (the UI updates immediately) and the live
 * weight / `ff12` stream then confirms the scale's real value, exactly as the
 * Android shell does.
 *
 * Construct it once with {@link createCremaApp}; expose `app.state.current` to
 * the screen.
 */

import {
	loadCore,
	type Command,
	type CoreOutput,
	type CremaCore,
	type FirmwareUpdateStatus
} from '$lib/core';
import { MachineState } from '$lib/core/crema-core';
import { De1Manager, EMPTY_DE1_DIAGNOSTICS, ScaleManager } from '$lib/ble';
import { getBeanStore } from '$lib/bean';
import { getHistoryStore } from '$lib/history';
import { getCaptureRecorder, getCaptureStore } from '$lib/capture';
import { getProfileStore } from '$lib/profiles';
import { getMaintenanceStore } from '$lib/maintenance';
import { parseCaptureFile, replayEvents, ReplayAbortedError } from '$lib/replay';
import { describeError } from '$lib/utils/error';
import {
	CremaUiState,
	DEFAULT_SCALE_STANDBY_MINUTES,
	DEFAULT_SCALE_VOLUME,
	EMPTY_DE1_CALIBRATION,
	type UiSnapshot
} from './ui-state.svelte';

/**
 * The "cleared DE1 readout" snapshot patch — every DE1-derived field returned
 * to its pre-connect blank. Applied on connect, on disconnect and at the start
 * of a capture replay, so a stale machine readout never bleeds across a
 * reconnect or into a replay. One constant, so the three call sites cannot
 * drift apart (and so a field added to the read-paths is cleared everywhere
 * the moment it is added here).
 */
const CLEARED_DE1_READOUT = {
	machineState: null,
	shotPhase: null,
	telemetry: null,
	latestTelemetry: null,
	shotTelemetry: [],
	shotInProgress: false,
	shotElapsed: 0,
	completedShot: null,
	de1Diagnostics: EMPTY_DE1_DIAGNOSTICS,
	// Read-paths added on the wire-read-paths branch — also reset, so a
	// reconnect does not show the previous machine's firmware / registers /
	// calibration / error, or a stale idle / last-shot timer.
	de1Firmware: null,
	de1MachineInfo: {},
	de1Calibration: EMPTY_DE1_CALIBRATION,
	machineError: null,
	idleSince: null,
	lastShotCompletedAt: null,
	lastShotDuration: null
} as const satisfies Partial<UiSnapshot>;

/**
 * The DE1 streams telemetry at ~25 Hz — a ~40 ms gap between samples. The
 * water-accumulation integral caps Δt here, at 50× the nominal gap: a longer
 * gap is a stall, a reconnect or a paused replay, not real dispensing, so it
 * must not be integrated as if water had flowed the whole time. Generous
 * enough to ride out a transient hiccup, tight enough to reject a real stall.
 */
const MAX_TELEMETRY_GAP_S = 2;

/**
 * How far before `ShotStarted` to begin the persisted capture slice. The
 * Idle→Espresso `MachineStateChanged` arrives a moment before `ShotStarted`;
 * including those entries means a replayed capture produces a clean shot. A
 * generous window (the rolling buffer is in-memory and the slice is small).
 */
const CAPTURE_LEAD_MS = 15_000;

/**
 * The DE1 top-level states whose group flow counts toward the water-filter /
 * descale counters: an espresso shot and the two hot-water modes (hot water +
 * its rinse). Steam draws no water through the group, and idle / sleep / cal
 * states dispense nothing — the de1app likewise counts only espresso + hot
 * water. The state is the prefix of `UiSnapshot.machineState` (`"<state> /
 * <substate>"`).
 */
const WATER_COUNTING_STATES: ReadonlySet<string> = new Set([
	MachineState.Espresso,
	MachineState.HotWater,
	MachineState.HotWaterRinse
]);

/** Options for {@link CremaApp.replayCapture}. */
export interface ReplayCaptureOptions {
	/** Playback speed multiplier — `1` is real time. Defaults to `1`. */
	readonly speed?: number;
}

/**
 * The orchestrator. One instance per app. The screen reads {@link state} and
 * calls the action methods; it never touches the core or the BLE layer.
 */
export class CremaApp {
	/** The reactive UI state — the screen renders `app.state.current`. */
	readonly state = new CremaUiState();

	private readonly de1: De1Manager;
	private readonly scale: ScaleManager;

	/**
	 * The abort controller for an in-progress capture replay, or `null` when no
	 * replay is running. {@link cancelReplay} aborts it.
	 */
	private replayAbort: AbortController | null = null;

	/**
	 * `performance.now()` of the previous `Telemetry` event, or `null` before
	 * the first — the wall-clock anchor the water-accumulation integral (E1)
	 * uses for its Δt. Reset on disconnect so a stale gap is never integrated.
	 */
	private lastTelemetryAtMs: number | null = null;

	/**
	 * `performance.now()` of the most recent `ShotStarted` event, or `null`
	 * before the first / between shots. Used by `ShotCompleted` to slice the
	 * rolling BLE-capture buffer for the IndexedDB capture store.
	 */
	private shotStartedAtMs: number | null = null;

	constructor(private readonly core: CremaCore) {
		this.de1 = new De1Manager(core, {
			onCoreOutput: (output) => this.applyCoreOutput(output),
			// A DE1 status line is both the live status and an event-log entry,
			// so the connect's step-by-step diagnostics land in the log.
			onStatus: (line) => {
				this.state.patch({ status: line });
				this.state.log(line);
			},
			onState: (de1State) => this.state.patch({ de1State }),
			// The connection-diagnostics snapshot — fold it straight in.
			onDiagnostics: (de1Diagnostics) => this.state.patch({ de1Diagnostics })
		});
		this.scale = new ScaleManager(core, {
			onCoreOutput: (output) => this.applyCoreOutput(output),
			onStatus: (line) => this.state.patch({ status: line }),
			onState: (scaleState) => this.state.patch({ scaleState }),
			onScaleIdentified: (advertisedName) => {
				void this.refreshScaleCapabilities(advertisedName);
			},
			// After the auto-reconnect loop recovers the scale link, re-fire the
			// settings query so the config read-back refreshes from the device.
			onReconnected: () => {
				void this.core.queryScaleSettings().then((output) => this.applyCoreOutput(output));
			}
		});
		// Best-effort: ask the browser for persistent storage (so IndexedDB
		// captures aren't evicted under disk pressure), and garbage-collect
		// any captures whose StoredShot no longer exists. Fire and forget —
		// neither is on the critical path of app readiness.
		void this.bootCaptureStore();
	}

	/**
	 * One-shot capture-store housekeeping at app construction: request
	 * persistent storage, then drop captures whose `StoredShot` has been
	 * evicted from the history cap (or deleted by the user) since last load.
	 */
	private async bootCaptureStore(): Promise<void> {
		if (typeof navigator !== 'undefined') {
			try {
				await navigator.storage?.persist?.();
			} catch {
				// Best-effort — a refusal is fine.
			}
		}
		try {
			const ids = new Set(getHistoryStore().all.map((s) => s.id));
			await getCaptureStore().pruneTo(ids);
		} catch {
			// The captures DB may not be openable (private mode etc.) — fine.
		}
	}

	// ---- CoreOutput plumbing ----------------------------------------------

	/**
	 * Fold a `CoreOutput` into the state, then run its commands — the single
	 * funnel every notification and action passes through. Mirrors the Android
	 * shell's `onCoreOutputJson` (minus the JSON parse, which the core facade
	 * already does): events first, then commands, both in order.
	 *
	 * On a `ShotCompleted` event it also snapshots the just-finished shot into
	 * the persistent `lib/history` store — the core itself does not keep shot
	 * history, so the shell records its own. The `ShotCompleted` fold keeps the
	 * buffered `shotTelemetry` series intact, so reading `state.current` right
	 * after the fold yields the complete series to persist.
	 */
	private applyCoreOutput(output: CoreOutput): void {
		for (const event of output.events) {
			this.state.applyEvent(event);
			if (event.type === 'Telemetry') {
				// Water accumulation (E1): integrate the DE1's group flow over
				// the wall-clock gap since the previous telemetry sample, the
				// de1app's `volume += GroupFlow × Δt` approach.
				const now = performance.now();
				if (this.lastTelemetryAtMs !== null && this.countsTowardWater()) {
					const deltaS = Math.min(
						MAX_TELEMETRY_GAP_S,
						(now - this.lastTelemetryAtMs) / 1000
					);
					getMaintenanceStore().accumulate(event.content.group_flow, deltaS);
				}
				this.lastTelemetryAtMs = now;
			}
			if (event.type === 'ShotStarted') {
				// Mark the shot's start so ShotCompleted can slice the raw BLE
				// capture (with a lead-in) for the IndexedDB capture store.
				this.shotStartedAtMs = performance.now();
			}
			if (event.type === 'ShotCompleted') {
				const snapshot = this.state.current;
				// Stamp a snapshot of the current bean onto the record, so a later
				// bean change cannot rewrite history. An unlogged bean (no roaster
				// and no type) stores `null` — the History UI treats it as optional.
				const bean = getBeanStore().current;
				const roaster = bean.roaster.trim();
				const type = bean.type.trim();
				// Capture the brew dose from the active profile so the History
				// ratio divides by the real dose, not a nominal 18 g. `null` when
				// no profile is active or the library has not loaded yet.
				const profiles = getProfileStore();
				const activeProfile = profiles.activeId
					? profiles.get(profiles.activeId)
					: undefined;
				const record = getHistoryStore().record({
					duration: event.content.duration,
					profileName: snapshot.activeProfileName,
					dose: activeProfile?.dose ?? null,
					series: snapshot.shotTelemetry,
					bean:
						roaster || type
							? {
									roaster,
									type,
									roastedOn: bean.roastedOn,
									roastLevel: bean.roastLevel
								}
							: null
				});
				// Persist the just-finished shot's raw BLE capture (a slice of
				// the recorder's rolling buffer, scoped from a short lead-in
				// before ShotStarted through completion) under the new record's
				// id, so the ShotDetail "Download" can export a JSONL capture
				// that drops into Advanced → Replay. Skipped during a replay —
				// the recorder has no entries for replayed shots (the replay
				// driver feeds the core directly, bypassing the BLE managers),
				// so a put would persist a mismatched slice.
				if (record && this.shotStartedAtMs !== null && this.replayAbort === null) {
					const slice = getCaptureRecorder().slice(
						this.shotStartedAtMs - CAPTURE_LEAD_MS,
						performance.now()
					);
					if (slice.length > 0) {
						void getCaptureStore().put(record.id, slice);
					}
				}
				this.shotStartedAtMs = null;
			}
		}
		for (const command of output.commands) {
			void this.executeCommand(command);
		}
	}

	/**
	 * Whether the machine's current state is one whose group flow should feed
	 * the water-accumulation counters — see {@link WATER_COUNTING_STATES}. Read
	 * after the `Telemetry` event is folded, so `machineState` already reflects
	 * the session this sample belongs to (a `MachineStateChanged` for the
	 * session is folded before its telemetry). Gates out steam, where the DE1
	 * still reports a small group flow that would otherwise overcount.
	 */
	private countsTowardWater(): boolean {
		const machineState = this.state.current.machineState;
		if (machineState === null) return false;
		// `machineState` is `"<state> / <substate>"` — match the state prefix.
		const state = machineState.split(' / ')[0];
		return WATER_COUNTING_STATES.has(state);
	}

	/**
	 * Run one core `Command`.
	 *
	 * `WriteScale` → write the bytes to the scale (manual tare, auto-tare, and
	 * every config write all surface here). `WriteCharacteristic` → a no-op:
	 * the DE1 is read-only in the shell, exactly as in the Android shell.
	 */
	private async executeCommand(command: Command): Promise<void> {
		switch (command.type) {
			case 'WriteScale': {
				const data = new Uint8Array(command.content.data);
				// Log the outgoing write so the on-screen event log shows the
				// whole ff12 round-trip — the write, then the scale's response.
				const hex = [...data].map((b) => b.toString(16).padStart(2, '0')).join('');
				this.state.log(`→ scale write ${hex}`);
				await this.scale.writeScale(data);
				break;
			}
			case 'WriteCharacteristic':
				// DE1 machine control is not driven by the shell — no-op,
				// mirroring the Android shell's command sink.
				break;
		}
	}

	// ---- DE1 actions ------------------------------------------------------

	/**
	 * Connect a DE1 — call from a button handler (Web Bluetooth gesture).
	 *
	 * The shared `eventLog` is deliberately *not* cleared here: it is shared
	 * between DE1 and scale events, so wiping it on a DE1 connect would discard
	 * in-progress scale entries. None of `connectDe1` / `disconnectDe1` /
	 * `connectScale` / `disconnectScale` touches the log — a single device's
	 * lifecycle never erases the other device's history.
	 */
	async connectDe1(): Promise<void> {
		this.state.patch({ ...CLEARED_DE1_READOUT });
		await this.de1.connect();
	}

	/** Disconnect the DE1 and clear its readout fields. */
	async disconnectDe1(): Promise<void> {
		await this.de1.disconnect();
		// Drop the telemetry wall-clock anchor — the next connect must not
		// integrate the (arbitrarily long) gap across the disconnect.
		this.lastTelemetryAtMs = null;
		this.state.patch({ ...CLEARED_DE1_READOUT });
	}

	// ---- Scale actions ----------------------------------------------------

	/** Connect a Bookoo scale — call from a button handler. */
	async connectScale(): Promise<void> {
		await this.scale.connect();
	}

	/** Disconnect the scale and clear every scale-derived field. */
	async disconnectScale(): Promise<void> {
		await this.scale.disconnect();
		this.state.patch({
			scaleWeight: null,
			scaleFlow: null,
			scaleTimer: null,
			scaleCapabilities: null,
			scaleVolume: DEFAULT_SCALE_VOLUME,
			scaleStandbyMinutes: DEFAULT_SCALE_STANDBY_MINUTES,
			scaleFlowSmoothing: false,
			scaleAutoStop: null,
			scaleAntiMistouch: false,
			scaleActiveMode: null,
			scaleBattery: null,
			scaleName: null,
			scaleFirmware: null,
			scaleSerial: null
		});
	}

	/**
	 * Read the connected scale's capabilities from the core and fold them in,
	 * capturing the advertised name. Called once the core identifies the
	 * scale; the screen gates config controls on the result. Mirrors the
	 * Android shell's `refreshScaleCapabilities`.
	 */
	private async refreshScaleCapabilities(advertisedName: string): Promise<void> {
		const caps = await this.core.scaleCapabilities();
		this.state.patch({ scaleCapabilities: caps ?? null, scaleName: advertisedName });
	}

	/**
	 * Compare the most recently observed DE1 firmware version against the
	 * latest firmware Crema was compiled against. Read-only — no BLE traffic.
	 * Returns the `Unknown` variant until the DE1's `Version` characteristic
	 * has been read at least once (it is read at connect time, so the value
	 * is non-`Unknown` as soon as the DE1 is connected and subscribed).
	 */
	async firmwareUpdateStatus(): Promise<FirmwareUpdateStatus> {
		return this.core.firmwareUpdateStatus();
	}

	/** Tare the connected scale. Routes the core's `WriteScale` to the scale. */
	async tareScale(): Promise<void> {
		this.applyCoreOutput(await this.core.tareScale());
	}

	/**
	 * Set the scale beeper volume to `level`. Clamps to the scale's
	 * `volume` capability bounds, updates the shown value optimistically, then
	 * routes the core's command — the live `device_volume` stream confirms it.
	 */
	async setScaleVolume(level: number): Promise<void> {
		const range = this.state.current.scaleCapabilities?.volume;
		const clamped =
			range !== undefined ? Math.min(Math.max(level, range.min), range.max) : level;
		this.state.patch({ scaleVolume: clamped });
		this.applyCoreOutput(await this.core.setScaleVolume(clamped));
	}

	/**
	 * Set the scale auto-standby timeout to `minutes`. Clamped to the
	 * `standby_minutes` capability bounds; optimistic, then stream-confirmed.
	 */
	async setScaleStandbyMinutes(minutes: number): Promise<void> {
		const range = this.state.current.scaleCapabilities?.standby_minutes;
		const clamped =
			range !== undefined ? Math.min(Math.max(minutes, range.min), range.max) : minutes;
		this.state.patch({ scaleStandbyMinutes: clamped });
		this.applyCoreOutput(await this.core.setScaleStandbyMinutes(clamped));
	}

	/** Toggle the scale's flow smoothing. Optimistic, then stream-confirmed. */
	async setScaleFlowSmoothing(enabled: boolean): Promise<void> {
		this.state.patch({ scaleFlowSmoothing: enabled });
		this.applyCoreOutput(await this.core.setScaleFlowSmoothing(enabled));
	}

	/**
	 * Toggle the scale's anti-mistouch. Optimistic; the `03 0c` serial
	 * response confirms it.
	 */
	async setScaleAntiMistouch(enabled: boolean): Promise<void> {
		this.state.patch({ scaleAntiMistouch: enabled });
		this.applyCoreOutput(await this.core.setScaleAntiMistouch(enabled));
	}

	/**
	 * Switch the scale display mode to `modeId`. Switching is three
	 * `WriteScale` commands the core emits in order; {@link applyCoreOutput}'s
	 * loop preserves that order. Optimistic, then stream-confirmed.
	 */
	async setScaleMode(modeId: number): Promise<void> {
		this.state.patch({ scaleActiveMode: modeId });
		this.applyCoreOutput(await this.core.setScaleMode(modeId));
	}

	/**
	 * Select the scale auto-stop mode (`0` = flow-stop, `1` = cup-removal).
	 * Optimistic; the live `device_auto_stop` stream confirms it.
	 */
	async setScaleAutoStop(modeId: number): Promise<void> {
		this.state.patch({ scaleAutoStop: modeId });
		this.applyCoreOutput(await this.core.setScaleAutoStop(modeId));
	}

	// ---- Capture replay (developer tool) ----------------------------------

	/**
	 * Replay a recorded BLE capture file through the core — a developer/admin
	 * tool that lets a previously-exported shot be watched in the web UI with no
	 * live machine.
	 *
	 * The file is parsed into inbound notifications (see `lib/replay`), the core
	 * is `reset()` so the replay starts from a clean session, and each event is
	 * fed through `core.onNotification(source, bytes, t)` with its captured
	 * timestamp — exactly the path a live notification takes. Every resulting
	 * `CoreOutput` funnels through {@link applyCoreOutput}, so the UI fills just
	 * as it would from a real device: telemetry into the `LiveChart`, the timer
	 * and readouts, and `ShotCompleted` into the History store.
	 *
	 * The replay is paced at real time by the events' timestamps (see
	 * `replayEvents`); `opts.speed` scales the pace. Progress and outcome are
	 * exposed on `state.current.replay`; {@link cancelReplay} stops it.
	 *
	 * Only one replay runs at a time — a second call while one is in progress is
	 * ignored. The replay does not interact with any live BLE connection: it
	 * resets the core, so a connected device's session would be discarded — it
	 * is strictly an offline diagnostic.
	 */
	async replayCapture(file: File, opts: ReplayCaptureOptions = {}): Promise<void> {
		if (this.replayAbort) return;

		const abort = new AbortController();
		this.replayAbort = abort;

		let parsed;
		try {
			parsed = await parseCaptureFile(file);
		} catch (error) {
			this.replayAbort = null;
			this.state.patch({
				replay: {
					phase: 'error',
					fileName: file.name,
					done: 0,
					total: 0,
					message: `Could not read capture: ${describeError(error)}`
				}
			});
			return;
		}

		if (parsed.events.length === 0) {
			this.replayAbort = null;
			this.state.patch({
				replay: {
					phase: 'error',
					fileName: file.name,
					done: 0,
					total: 0,
					message: 'No replayable notifications found in this capture.'
				}
			});
			return;
		}

		// Start from a clean core session so the replayed shot is not blended
		// with any prior (or live) session's state.
		await this.core.reset();
		// The replay also drops the telemetry wall-clock anchor — replayed
		// timestamps must not integrate the gap since the last live sample.
		this.lastTelemetryAtMs = null;
		this.state.patch({
			...CLEARED_DE1_READOUT,
			replay: {
				phase: 'running',
				fileName: file.name,
				done: 0,
				total: parsed.events.length,
				message: `Replaying ${parsed.events.length} events from ${file.name}…`
			}
		});

		try {
			await replayEvents(
				parsed.events,
				async (event) => {
					// Feed the raw bytes straight through the core, exactly as a
					// live notification would arrive — then fold the output.
					const output = await this.core.onNotification(
						event.source,
						event.data,
						event.t
					);
					this.applyCoreOutput(output);
				},
				{
					speed: opts.speed,
					signal: abort.signal,
					onProgress: (index, total) => {
						this.state.patch({
							replay: {
								phase: 'running',
								fileName: file.name,
								done: index + 1,
								total,
								message: `Replaying ${file.name} — ${index + 1} / ${total}`
							}
						});
					}
				}
			);
			this.state.patch({
				replay: {
					phase: 'done',
					fileName: file.name,
					done: parsed.events.length,
					total: parsed.events.length,
					message: `Replay finished — ${parsed.events.length} events from ${file.name}.`
				}
			});
		} catch (error) {
			if (error instanceof ReplayAbortedError) {
				const status = this.state.current.replay;
				this.state.patch({
					replay: {
						phase: 'cancelled',
						fileName: file.name,
						done: status?.done ?? 0,
						total: parsed.events.length,
						message: `Replay cancelled — ${status?.done ?? 0} / ${parsed.events.length} events.`
					}
				});
			} else {
				this.state.patch({
					replay: {
						phase: 'error',
						fileName: file.name,
						done: this.state.current.replay?.done ?? 0,
						total: parsed.events.length,
						message: `Replay failed: ${describeError(error)}`
					}
				});
			}
		} finally {
			this.replayAbort = null;
		}
	}

	/** Cancel an in-progress capture replay, if one is running. */
	cancelReplay(): void {
		this.replayAbort?.abort();
	}
}

/**
 * Load the wasm core and construct the orchestrator. Call once at app start,
 * e.g. in the screen's `onMount`.
 */
export async function createCremaApp(): Promise<CremaApp> {
	const core = await loadCore();
	return new CremaApp(core);
}
