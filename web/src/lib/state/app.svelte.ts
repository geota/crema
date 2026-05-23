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
import { MachineState, MmrRegister } from '$lib/core/crema-core';
import { De1Manager, EMPTY_DE1_DIAGNOSTICS, ScaleManager } from '$lib/ble';
import { getBeanStore } from '$lib/bean';
import { getHistoryStore } from '$lib/history';
import { getCaptureStore, type CaptureEntry } from '$lib/capture';
import { getProfileStore, toCoreProfile } from '$lib/profiles';
import { getSettingsStore } from '$lib/settings';
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
 * Minimum ms between `ProfileUploadCompleted` and the next
 * `requestMachineState` write. The DE1 firmware finishes the final
 * flash write inside `APIView::write` for the tail frame, and only
 * clears `ProfileDownloadInProgress` when that write returns. A
 * state-request that hits the firmware task loop inside the window
 * aborts the shot to HeaterDown after preinfuse — BC 9788201734.
 * Reaprime's value (`ConnectionTimings.profileDownloadGuard`); Crema
 * matches. docs/22 §1.2.
 */
const PROFILE_DOWNLOAD_GUARD_MS = 500;

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

/**
 * Best-effort scale identification from the first SCALE_WEIGHT payload.
 *
 * Used by the replay path — the capture format doesn't record the scale
 * model, so we sniff well-known wire signatures off the first weight
 * notification's bytes:
 *
 * - **Bookoo** — every BOOKOO_SC weight packet starts with `03 0B` (header
 *   byte 0x03, length 0x0B). No other supported scale uses that pair.
 *
 * Add new signatures as more scales make it into Crema's replay tests.
 * Returns `undefined` if no signature matches; the replay then proceeds
 * with no scale identified, which surfaces as an empty weight series —
 * the existing (broken) behaviour, so we don't regress.
 */
function guessScaleAdvertisedName(firstWeightBytes: Uint8Array): string | undefined {
	if (firstWeightBytes.length >= 2 && firstWeightBytes[0] === 0x03 && firstWeightBytes[1] === 0x0b) {
		return 'BOOKOO_SC';
	}
	return undefined;
}

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

	/**
	 * `performance.now()` of the most recent `ProfileUploadCompleted` event,
	 * or `null` before any upload. `requestMachineState` consults this to
	 * defer a state-request that hits the firmware too soon after a
	 * profile upload — the BC 9788201734 race: the DE1 only clears
	 * `ProfileDownloadInProgress` after the final flash write returns; a
	 * state=Espresso request before that aborts the shot to HeaterDown
	 * after preinfuse. Reaprime's `ConnectionTimings.profileDownloadGuard`
	 * is 500 ms; Crema matches. docs/22 §1.2.
	 */
	private lastProfileUploadCompletedAtMs: number | null = null;

	constructor(private readonly core: CremaCore) {
		this.de1 = new De1Manager(core, {
			onCoreOutput: (output) => this.applyCoreOutput(output),
			// A DE1 status line is both the live status and an event-log entry,
			// so the connect's step-by-step diagnostics land in the log.
			onStatus: (line) => {
				this.state.patch({ status: line });
				this.state.log(line);
			},
			onState: (de1State) => {
				this.state.patch({ de1State });
				// Auto re-upload the active profile on every DE1 connect.
				// Mirrors the legacy DE1-app's `save_settings_to_de1` →
				// `de1_send_shot_frames` chain, which the 2026-05-21 HCI
				// snoop confirmed fires ~80 ms after the connect-time
				// subscriptions complete (docs/16 §6.2). Without this the
				// DE1 wakes up with no profile loaded and the user has to
				// remember to click Load on Brew every session.
				if (de1State === 'ready') {
					this.autoUploadActiveProfileOnReady();
					// Enable the firmware's user-presence feature
					// unconditionally on every connect. With the bit set,
					// the DE1 listens to the `UserPresent` register; whether
					// Crema sends heartbeats is gated separately by the
					// `suppressDe1Sleep` setting (the heartbeat loop in
					// `createCremaApp`). Without sending the bit, the
					// heartbeats Crema ships in commit `6c8be97` are a
					// silent no-op on a fresh session.
					// Matches reaprime's `enableUserPresenceFeature()` call
					// in the connect path (unified_de1.dart:205). Legacy
					// counterpart: `set_feature_flags` in
					// `de1_comms.tcl:1202-1206`.
					void this.core
						.setFeatureFlags(1)
						.then((out) => this.applyCoreOutput(out))
						.catch(() => {
							// Best-effort; the next connect retries.
						});
					// Fire one heartbeat at connect when the user has
					// suppressDe1Sleep on, so the DE1's presence timer
					// starts from a fresh "user just touched" state rather
					// than from whenever the user last interacted on the
					// prior session.
					if (getSettingsStore().current.suppressDe1Sleep) {
						void this.markUserPresent();
					}
				}
			},
			// The connection-diagnostics snapshot — fold it straight in.
			onDiagnostics: (de1Diagnostics) => this.state.patch({ de1Diagnostics })
		});
		this.scale = new ScaleManager(core, {
			onCoreOutput: (output) => this.applyCoreOutput(output),
			// Like DE1 onStatus: the scale's connect/disconnect/reconnect
			// diagnostics are both the live status and an event-log entry,
			// so the Scale page's Activity panel sees the connection
			// timeline alongside the wire-level config events.
			onStatus: (line) => {
				this.state.patch({ status: line });
				this.state.log(line);
			},
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
			if (event.type === 'ProfileUploadCompleted') {
				// Open the profile-download race window — see the comment on
				// `lastProfileUploadCompletedAtMs`. `requestMachineState`
				// reads the value to delay any state-request that lands
				// inside the 500 ms guard window.
				this.lastProfileUploadCompletedAtMs = performance.now();
			}
			if (
				event.type === 'WaterSessionCompleted' &&
				event.content.kind === 'Flush' &&
				this.pendingPreshotFlush !== null
			) {
				// The pre-shot flush we requested just finished — release
				// the `startShot()` waiter so it can issue the Espresso
				// state request. The 30 s timeout in `startShot` is the
				// backstop if this notification is missed.
				this.pendingPreshotFlush();
			}
			if (event.type === 'SteamSessionCompleted') {
				// Auto-purge after steam (Settings → Brew defaults). A
				// short HotWaterRinse clears the group plumbing after
				// the user releases the steam control. Gated on the
				// pref, and only when the DE1 is in a state that
				// accepts a fresh state-request (the firmware refuses
				// state-requests during error / cal / clean modes).
				if (getSettingsStore().current.autoPurgeAfterSteam) {
					this.scheduleAutoPurge();
				}
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
					// Peak / final metrics ride on the event itself — the
					// core's `ShotMetricsAccumulator` tracks them in real
					// time, removing three sites of buffered-series
					// re-iteration (this one, the `applyEvent`
					// `ShotCompleted` fold, and `history/store.record`).
					peakPressure: event.content.peak_pressure ?? null,
					peakTemp: event.content.peak_temp ?? null,
					peakWeight: event.content.peak_weight ?? null,
					finalWeight: event.content.final_weight ?? null,
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
					// The core owns the rolling buffer (`core/de1-app/src/capture.rs`)
					// and prepends both the identity-keeper entries and the META
					// prelude with the scale's advertised name + DE1 firmware /
					// model / serial. Read the slice as JSONL — same wire format
					// the Android `BleSessionRecorder`, the Rust replay tool, and
					// the web replay parser all consume — parse it back into the
					// `CaptureEntry[]` IndexedDB structured-clones.
					const fromMs = this.shotStartedAtMs - CAPTURE_LEAD_MS;
					const toMs = performance.now();
					const shotId = record.id;
					void this.core.captureSliceJsonl(fromMs, toMs).then((jsonl) => {
						if (!jsonl) return;
						const entries: CaptureEntry[] = jsonl
							.split('\n')
							.filter((line) => line.length > 0)
							.map((line) => JSON.parse(line) as CaptureEntry);
						if (entries.length > 0) {
							void getCaptureStore().put(shotId, entries);
						}
					});
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
		const name = this.state.current.machineStateName;
		return name !== null && WATER_COUNTING_STATES.has(name);
	}

	/**
	 * Run one core `Command`.
	 *
	 * `WriteScale` → write the bytes to the scale (manual tare, auto-tare, and
	 * every config write all surface here). `WriteCharacteristic` → route to
	 * the DE1 manager, which maps the `WriteTarget` to its GATT UUID and
	 * dispatches the bytes. The write-side wiring per target lives in
	 * `web/src/lib/ble/de1.ts:uuidForWriteTarget`; targets with no UUID
	 * mapping are dropped with a status log.
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
			case 'WriteCharacteristic': {
				const data = new Uint8Array(command.content.data);
				const hex = [...data].map((b) => b.toString(16).padStart(2, '0')).join('');
				this.state.log(`→ DE1 write ${command.content.target} ${hex}`);
				await this.de1.writeCharacteristic(command.content.target, data);
				// The DE1 does *not* emit Handle Value Notifications on
				// FrameWrite (cuuid_10) — confirmed by the 2026-05-21
				// HCI snoop of a legacy-app session: every write gets only
				// an empty ATT Write Response. The legacy's "frame ack"
				// is its BLE wrapper echoing the written value into a
				// callback for debug logs, not real device traffic.
				//
				// To keep the core's per-frame upload state machine the
				// canonical source of truth without a phantom notification
				// stream, synthesize a De1FrameAck from the data we just
				// successfully wrote — byte 0 (FrameToWrite) is all the
				// core matches on. Profile-header writes are not acked by
				// the orchestrator, so they're skipped here.
				if (command.content.target === 'De1ProfileFrame') {
					const ackOut = await this.core.onNotification(
						'De1FrameAck',
						data,
						performance.now()
					);
					this.applyCoreOutput(ackOut);
				}
				break;
			}
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

	/**
	 * Upload `profile` to the DE1. The core builds the BLE writes; the
	 * orchestrator routes each `WriteCharacteristic` through
	 * `de1.writeCharacteristic`. Progress events (`ProfileUploadStarted` /
	 * `ProfileUploadProgress` / `ProfileUploadCompleted` /
	 * `ProfileUploadFailed`) arrive over the same `applyCoreOutput` path
	 * the rest of the events use; the UI snapshot's `profileUploadProgress`
	 * and `activeProfileName` fields reflect them.
	 *
	 * `profile` is the typed `Profile` (de1-domain shape) — the shell's
	 * `lib/profiles` model converts via `toCoreProfile` before calling this.
	 */
	async uploadProfile(profile: import('$lib/core').Profile): Promise<void> {
		const now = performance.now();
		this.applyCoreOutput(await this.core.uploadProfile(profile, now));
	}

	/** Cancel an in-progress profile upload (emits ProfileUploadFailed{Aborted}). */
	async cancelProfileUpload(): Promise<void> {
		this.applyCoreOutput(await this.core.cancelProfileUpload());
	}

	/**
	 * Pin the AC mains frequency the core's volume integrator uses. Called
	 * by the Advanced settings section whenever the user changes the
	 * "AC mains frequency" select.
	 */
	async setLineFrequencyOverride(hz: 0 | 50 | 60): Promise<void> {
		await this.core.setLineFrequencyOverride(hz);
	}

	/**
	 * Write the Group Head Controller mode (MMR `0x803820`). `0` disables
	 * the touch-to-confirm gate on host-initiated state changes so Crema
	 * can start a shot from the dashboard without a physical button tap;
	 * any non-zero value re-enables it (legacy de1app uses `4` for "on").
	 *
	 * After the write, the core re-reads `GhcMode` so the Settings toggle
	 * reflects the firmware's new state. The user-visible row is a
	 * real-machine setting (writes through to the DE1), so the toggle's
	 * commit only fires through this wrapper, not through any local cache.
	 */
	async setGhcMode(mode: number): Promise<void> {
		this.applyCoreOutput(await this.core.setGhcMode(mode));
		// Read back to confirm + refresh the snapshot's de1MachineInfo
		// (the toggle binds against `ui.de1MachineInfo[MmrRegister.GhcMode]`,
		// which is populated by MmrValue events the read fires).
		this.applyCoreOutput(await this.core.readMmr(MmrRegister.GhcMode));
	}

	/**
	 * Result of one file submitted to {@link CremaApp.importShotFile}. The
	 * imported record is the same shape the History page reads from the
	 * shared store; `null` `record` means "couldn't parse" — `message`
	 * carries a human-readable explanation in that case.
	 */
	// (Type alias inline so callers don't need a separate import.)

	/**
	 * Import a single legacy de1app `.shot` (Tcl-dict) or modern
	 * `.shot.json` (v2) file and add the resulting shot to the History
	 * store. Picks the parser by file extension: `.json` → v2, anything
	 * else → legacy TCL. Returns the imported record on success, or an
	 * error string the caller can surface as a toast. docs/22 §5.1.
	 */
	/**
	 * Import a single community-v2 `.json` or legacy de1app `.tcl`
	 * profile file. Routes the parser by extension: `.tcl` → legacy
	 * TCL parser, anything else → v2 JSON parser. Returns the parsed
	 * Rust-shape `Profile` JSON on success (the caller adopts it into
	 * the local store via `fromCoreProfile` + `ProfileStore.save`).
	 */
	async importProfileFile(
		file: File
	): Promise<{ profile: import('$lib/core').Profile | null; error: string | null }> {
		let content: string;
		try {
			content = await file.text();
		} catch (e) {
			return {
				profile: null,
				error: `Could not read ${file.name}: ${e instanceof Error ? e.message : String(e)}`
			};
		}
		const isLegacyTcl = file.name.toLowerCase().endsWith('.tcl');
		try {
			const profile = isLegacyTcl
				? await this.core.importLegacyTclProfile(content)
				: await this.core.importV2JsonProfile(content);
			return { profile, error: null };
		} catch (e) {
			return {
				profile: null,
				error: `Could not import ${file.name}: ${e instanceof Error ? e.message : String(e)}`
			};
		}
	}

	/** Serialize a Crema `Profile` (TS shape) as a community-v2 JSON string. */
	async exportProfileAsV2Json(profile: import('$lib/core').Profile): Promise<string> {
		return this.core.exportV2JsonProfile(profile);
	}

	/**
	 * Parse one community-v2 profile JSON document (a single line of a
	 * `.jsonl` bundle, or a `.json` file's content). Throws on parse
	 * failure — callers handle per-line error reporting in bulk
	 * imports.
	 */
	async parseV2JsonProfile(content: string): Promise<import('$lib/core').Profile> {
		return this.core.importV2JsonProfile(content);
	}

	async importShotFile(
		file: File
	): Promise<{ record: ReturnType<ReturnType<typeof getHistoryStore>['addImported']>; error: string | null }> {
		let content: string;
		try {
			content = await file.text();
		} catch (e) {
			return {
				record: null,
				error: `Could not read ${file.name}: ${e instanceof Error ? e.message : String(e)}`
			};
		}
		const isV2Json = file.name.toLowerCase().endsWith('.json');
		try {
			const imported = isV2Json
				? await this.core.importV2JsonShot(content)
				: await this.core.importLegacyTclShot(content);
			const record = getHistoryStore().addImported(imported);
			if (!record) {
				return {
					record: null,
					error: `${file.name} parsed cleanly but had no telemetry samples.`
				};
			}
			return { record, error: null };
		} catch (e) {
			return {
				record: null,
				error: `Could not import ${file.name}: ${e instanceof Error ? e.message : String(e)}`
			};
		}
	}

	/**
	 * The effective AC mains frequency the core's volume integrator is
	 * currently using — `null` until either the user pins a value or the
	 * auto-detector locks (1+ s of telemetry into the first shot).
	 */
	async lineFrequencyHz(): Promise<number | null> {
		return this.core.lineFrequencyHz();
	}

	/**
	 * Re-upload the active profile right after the DE1 connection enters
	 * `ready`. Fire-and-forget — a failure here is logged via the normal
	 * `applyCoreOutput` event stream and does not block any other connect
	 * step.
	 *
	 * No active profile → no upload (the DE1 stays empty until the user
	 * clicks Load on Brew). Profile store still loading → wait for it,
	 * then upload (the `ensureLoaded` call resolves once the built-in
	 * library has been deserialised; without this guard a fresh launch
	 * would skip the auto-upload because `activeId` is briefly `null`).
	 */
	private autoUploadActiveProfileOnReady(): void {
		void (async () => {
			const profiles = getProfileStore();
			await profiles.ensureLoaded();
			const id = profiles.activeId;
			if (id === null) {
				this.state.log(
					'No active profile to auto-upload — open Profiles and click Load on Brew once; subsequent connects will auto-push it.'
				);
				return;
			}
			const profile = profiles.get(id);
			if (!profile) {
				this.state.log(
					`Active profile id "${id}" not found in store — auto-upload skipped.`
				);
				return;
			}
			this.state.log(`Auto-upload on connect: ${profile.name}`);
			await this.uploadProfile(toCoreProfile(profile));
		})();
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

	/**
	 * Read one DE1 sensor's calibration value. `factory: true` reads the
	 * factory baseline; `false` (the default) reads the in-use value the
	 * DE1 is applying. The DE1 replies on the Calibration characteristic
	 * (`cuuid_12`), which decodes to an `Event::Calibration` that lands
	 * on `snapshot.de1Calibration[sensor].{current|factory}`.
	 *
	 * Used by the Settings → Calibration screen on mount (legacy reference:
	 * `gui.tcl:2445-2452` reads temperature + pressure current/factory on
	 * calibration-screen open).
	 */
	async readCalibration(
		sensor: import('$lib/core').CalTarget,
		factory = false
	): Promise<void> {
		this.applyCoreOutput(await this.core.readCalibration(sensor, factory));
	}

	// ---- User-presence heartbeat ------------------------------------------
	//
	// Two related write paths, both into `WriteToMMR` (`cuuid_06`):
	//
	// - `markUserPresent()` writes a `1` to `0x803860` — the legacy
	//   `set_user_present` heartbeat (`de1_comms.tcl:1166`). Resets the
	//   DE1's "user has gone away" timer. Debounced to once per minute by
	//   the caller (`createCremaApp` attaches the document listener).
	// - `setSuppressDe1Sleep(true/false)` — local-state only now. The
	//   FeatureFlags bit is written unconditionally as `1` on every
	//   connect (`onState('ready')` block, matching reaprime's
	//   `enableUserPresenceFeature()`), so the DE1 is always listening to
	//   the UserPresent register. The setting just gates whether the
	//   heartbeat loop in `createCremaApp` actually fires; off → no
	//   heartbeats → DE1 falls back to its own timer.
	//
	// Together: Suppress=ON → feature bit=1 + heartbeat running → DE1
	// sleeps only when Crema is genuinely idle. Suppress=OFF → feature
	// bit=1 + heartbeat silent → DE1 follows its own ~30 min timer.

	/**
	 * Send a `UserPresent = 1` heartbeat to the DE1. Best-effort: ignores
	 * failures so a flaky link doesn't bubble exceptions into the
	 * shell-side debounce loop. The typed core method enforces the
	 * firmware-upload lockout (no MMR writes while a firmware upload is
	 * in progress) — see CremaCore::set_user_present.
	 */
	async markUserPresent(): Promise<void> {
		try {
			this.applyCoreOutput(await this.core.setUserPresent(true));
		} catch {
			// Best-effort: the next debounce tick will retry.
		}
	}

	/**
	 * Track the user's `suppressDe1Sleep` preference. The setting only
	 * gates the local heartbeat loop in `createCremaApp` — the firmware
	 * FeatureFlags bit is enabled unconditionally at every connect (in
	 * `onState('ready')`), matching reaprime's `enableUserPresenceFeature`
	 * call. So this method is a no-op on the wire; it exists for symmetry
	 * with the rest of the orchestrator's setter API.
	 */
	async setSuppressDe1Sleep(_suppress: boolean): Promise<void> {
		// Intentional no-op. The setting is read directly from
		// `getSettingsStore().current.suppressDe1Sleep` by the heartbeat
		// loop on each tick, so flipping the toggle takes effect on the
		// next interval without any explicit write.
	}

	/**
	 * Kick off a HotWaterRinse a brief moment after a steam session ended.
	 *
	 * The DE1 transitions Steam → Idle internally when the user releases the
	 * steam control; the SteamSessionCompleted event arrives on or just
	 * before that transition. Firing the state-request *during* the
	 * Steam→Idle transition can race with the firmware, so we defer by a
	 * short tick and bail out if the DE1 has already left Idle (e.g. user
	 * pressed Espresso themselves) or a HotWaterRinse is already running.
	 */
	private scheduleAutoPurge(): void {
		window.setTimeout(() => {
			// Only fire from Idle — avoid stepping on a manual user action.
			if (this.state.current.machineStateName !== MachineState.Idle) return;
			void this.requestMachineState(MachineState.HotWaterRinse);
		}, 1500);
	}

	/**
	 * Start an espresso shot — the Brew screen's primary action. Optionally
	 * pre-flushes the group head if the user's `groupFlushBeforeShot` pref
	 * is on, then requests `Espresso`.
	 *
	 * The pre-flush is a one-shot side effect: kick off `HotWaterRinse` and
	 * subscribe (once) to the next `WaterSessionCompleted(Flush)` event to
	 * advance to Espresso. The DE1's `FlushTimeout` MMR setting caps the
	 * flush duration; the wall-clock ceiling here is just a backstop so a
	 * lost Completion notification doesn't trap the start.
	 */
	async startShot(): Promise<void> {
		if (!getSettingsStore().current.groupFlushBeforeShot) {
			await this.requestMachineState(MachineState.Espresso);
			return;
		}
		// Pre-shot group flush. Kick off the rinse, then wait for its
		// completion (or the 30 s ceiling) before requesting Espresso.
		await this.requestMachineState(MachineState.HotWaterRinse);
		await new Promise<void>((resolve) => {
			const timeout = window.setTimeout(() => {
				this.pendingPreshotFlush = null;
				resolve();
			}, 30_000);
			this.pendingPreshotFlush = () => {
				window.clearTimeout(timeout);
				this.pendingPreshotFlush = null;
				resolve();
			};
		});
		await this.requestMachineState(MachineState.Espresso);
	}

	/** Stop a running shot — requests `Idle`, which the DE1 honours from any session state. */
	async stopShot(): Promise<void> {
		await this.requestMachineState(MachineState.Idle);
	}

	/**
	 * The pre-shot-flush waiter, populated by `startShot()` while the
	 * pre-flush is running. The event loop in `applyCoreOutput` resolves it
	 * when `WaterSessionCompleted(Flush)` arrives, freeing `startShot` to
	 * issue the `Espresso` request.
	 */
	private pendingPreshotFlush: (() => void) | null = null;

	/**
	 * Ask the DE1 to enter a machine state — most usefully Sleep or Idle.
	 * One byte gets written to the RequestedState characteristic
	 * (cuuid_02). Idle also stops a running shot and wakes the machine
	 * from sleep. Other requestable states (Espresso, Steam, HotWater,
	 * Flush, Descale, Clean) are normally triggered by on-machine touch
	 * buttons; the shell exposes them for completeness.
	 */
	async requestMachineState(state: MachineState): Promise<void> {
		// Profile-download race guard (docs/22 §1.2). The DE1 firmware
		// holds `ProfileDownloadInProgress` for a short window after the
		// final frame write — a state-request that arrives inside the
		// window is aborted to HeaterDown after preinfuse (BC 9788201734).
		// Mirrors reaprime's 500 ms `ConnectionTimings.profileDownloadGuard`.
		if (this.lastProfileUploadCompletedAtMs !== null) {
			const remaining =
				PROFILE_DOWNLOAD_GUARD_MS -
				(performance.now() - this.lastProfileUploadCompletedAtMs);
			if (remaining > 0) {
				await new Promise<void>((resolve) =>
					window.setTimeout(resolve, remaining)
				);
			}
		}
		this.applyCoreOutput(await this.core.requestMachineState(state));
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
		// Suppress the core's rolling capture buffer for the duration of the
		// replay — without this the replay's own notifications would be
		// re-recorded and the next `ShotCompleted` would persist a slice of
		// replayed bytes back to IndexedDB, double-writing the capture. The
		// flag is paired with the `finally` below that unconditionally
		// clears it.
		await this.core.setReplayMode(true);
		// The replay also drops the telemetry wall-clock anchor — replayed
		// timestamps must not integrate the gap since the last live sample.
		this.lastTelemetryAtMs = null;

		// Identify the scale so SCALE_WEIGHT bytes decode into ScaleReading
		// events on the way through. Without a `connectScale` call the core
		// has no decoder selected and the bytes are dropped, leaving the
		// replayed shot's weight series empty.
		//
		// Preference order: the explicit META prelude (Crema captures now
		// prepend one with the scale's advertised name on persist) → the
		// first SCALE_WEIGHT payload's header bytes for a known signature
		// (legacy fallback for captures pre-dating META).
		const scaleNameToConnect =
			parsed.meta.scaleName ??
			(() => {
				const firstScale = parsed.events.find((e) => e.source === 'ScaleWeight');
				return firstScale ? guessScaleAdvertisedName(firstScale.data) : undefined;
			})();
		if (scaleNameToConnect) {
			try {
				await this.core.connectScale(scaleNameToConnect);
			} catch {
				// Non-fatal — if identification fails, weight just stays empty.
			}
		}
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
			// Always clear the replay-mode flag — including on the abort /
			// error paths — so the next live BLE session resumes capturing.
			await this.core.setReplayMode(false);
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
	const app = new CremaApp(core);
	// Push the persisted AC mains-frequency preference into the core's
	// volume integrator. `0` = auto (the core's auto-detector decides);
	// `50` / `60` pin the value. Done once at construction; subsequent
	// changes flow via `app.setLineFrequencyOverride(...)` from the
	// Advanced settings section.
	const hz = getSettingsStore().current.lineFrequencyHz;
	if (hz === 50 || hz === 60) {
		void core.setLineFrequencyOverride(hz);
	} else {
		void core.setLineFrequencyOverride(0);
	}
	// Install the user-presence heartbeat — every user touch / keystroke
	// (debounced to once per minute) writes `UserPresent = 1` to the DE1,
	// resetting its "user has gone away" timer. The actual MMR write is
	// gated by the `suppressDe1Sleep` preference being on AND the DE1 being
	// connected; either condition false is a no-op. Mirrors the legacy app
	// firing `set_user_present` on every touch (`de1_comms.tcl:1166`), but
	// debounced to keep the BLE channel quiet — the DE1's threshold for
	// "user is gone" is on the order of minutes, so once-per-minute resets
	// are plenty.
	if (typeof document !== 'undefined') {
		const HEARTBEAT_DEBOUNCE_MS = 60_000;
		let lastFiredAt = 0;
		const tick = () => {
			if (!getSettingsStore().current.suppressDe1Sleep) return;
			if (app.state.current.de1State !== 'ready') return;
			const now = performance.now();
			if (now - lastFiredAt < HEARTBEAT_DEBOUNCE_MS) return;
			lastFiredAt = now;
			void app.markUserPresent();
		};
		document.addEventListener('pointerdown', tick, { passive: true });
		document.addEventListener('keydown', tick, { passive: true });
	}
	return app;
}
