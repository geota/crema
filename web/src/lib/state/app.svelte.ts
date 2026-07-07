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
import { getBeanStore, getBeanLibraryStore } from '$lib/bean';
import { getHistoryStore, snapshotFromBean, extractCremaExtras } from '$lib/history';
import { commitShotCompletion } from '$lib/history/shot-persistence';
import { getCaptureStore } from '$lib/capture';
import {
	getProfileStore,
	profileFingerprint,
	toCoreProfile,
	type CremaProfile,
	type ProfileFingerprintOverrides
} from '$lib/profiles';
import { readJson, writeJson } from '$lib/utils/storage';
import { getSettingsStore } from '$lib/settings';
import { toast } from '$lib/components/shared/toast.svelte';
import { getMaintenanceStore } from '$lib/maintenance';
import { parseCaptureFile } from '$lib/replay';
import { describeError } from '$lib/utils/error';
import { Effect, Fiber } from 'effect';
import type { AppRuntime } from '$lib/effect/runtime';
import { runtimePromise } from '$lib/effect/bridge';
import { UploadQueue } from '$lib/services/upload-queue';
import { Webhooks } from '$lib/services/webhooks';
import { ProfileSync } from '$lib/services/profile-sync';
import { replayCaptureProgram } from '$lib/services/replay';
import {
	CremaUiState,
	DEFAULT_SCALE_STANDBY,
	DEFAULT_SCALE_VOLUME,
	EMPTY_DE1_CALIBRATION,
	getCremaUiState,
	type UiSnapshot
} from './ui-state.svelte';
import { getActiveShotStore, type ActiveShotData } from './active-shot.svelte';

/**
 * Thrown by {@link CremaApp.startShot} when the user taps Coffee without an
 * active profile selected. The brew dashboard catches it and surfaces a
 * transient "Select a profile first" banner (re-using the
 * `MachineErrorBanner` visual pattern); the DE1 is left untouched —
 * without an active profile there are no bytes to load and no shot to start.
 */
export class NoActiveProfileError extends Error {
	constructor(
		message: string = 'Select a profile first — Crema needs an active profile to upload and start a shot.'
	) {
		super(message);
		this.name = 'NoActiveProfileError';
	}
}

/**
 * Thrown by {@link CremaApp.startShot} when the lazy profile-sync upload
 * fails (or times out) before the Espresso state request goes out. The
 * DE1 still holds whatever profile it had before the failed upload, so
 * the shot is refused — the caller can offer "try again" via a banner.
 */
export class ProfileSyncFailedError extends Error {
	constructor(message: string) {
		super(message);
		this.name = 'ProfileSyncFailedError';
	}
}

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
	idleSince: null
	// `activeProfileFingerprint` is **deliberately omitted** here so the
	// connect / replay-start paths retain the hydrated localStorage
	// value across the clear — without that, the connect-time
	// `ensureLoadedMatches()` would always see a `null` cache and
	// re-upload, defeating the persistence design. `disconnectDe1` /
	// `replayCapture` clear the fingerprint explicitly (the DE1 is
	// unreachable / the replay is offline; the cache is no longer
	// authoritative).
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
 * localStorage key for the most recent profile-upload fingerprint —
 * persisted on every `ProfileUploadCompleted` and hydrated into the
 * snapshot on app construction. Lets a page reload that keeps the DE1
 * connected (or a quick reconnect) skip the defensive auto-upload when
 * the machine still has the bytes we last sent.
 */
const LAST_FINGERPRINT_KEY = 'crema.profile-sync.lastFingerprint.v1';

/**
 * localStorage key for the core's learned SAW drip model — an opaque
 * core-owned JSON blob (`de1_domain::saw_learning`), seeded at boot and
 * saved after every completed shot.
 */
const SAW_MODEL_KEY = 'crema.saw-model.v1';

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
 * The replay-specific helpers — `composeReplayStartMessage`,
 * `replayMetaToActiveShot`, `guessScaleAdvertisedName` — moved to
 * `$lib/services/replay` alongside the `replayCaptureProgram` they serve (T-28).
 */

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
	readonly state = getCremaUiState();

	private readonly de1: De1Manager;
	private readonly scale: ScaleManager;

	/**
	 * The fiber running an in-progress capture replay, or `null` when none is
	 * running. {@link cancelReplay} interrupts it (T-28 — the old
	 * `AbortController`; `Effect.sleep` pacing is interrupted for free).
	 */
	private replayFiber: Fiber.RuntimeFiber<void> | null = null;

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

	// The profile-upload coordination state — the download-guard clock, the
	// in-flight fingerprint, and the completion waiter — moved into the
	// `ProfileSync` service (T-21). `syncActiveProfile` drives it; the
	// `ProfileUploadCompleted` / `ProfileUploadFailed` fold arms call
	// `ProfileSync.completed` / `.failed`; `requestMachineState` awaits
	// `ProfileSync.profileDownloadGuard`.

	/**
	 * Active `setInterval` id for the scale-heartbeat loop, or `null` when
	 * no heartbeat-needing scale is connected. The Decent Scale's on-scale
	 * LCD sleeps after a few seconds of silence; the host fires
	 * `scaleHeartbeat` every ~2 s to keep it awake. The core is sans-IO
	 * — the clock lives here. Capability-gated via
	 * `ScaleCapabilities.heartbeat_interval_ms` (non-null = arm a clock
	 * at that cadence; the core owns both the device gate and the cadence).
	 */
	private scaleHeartbeatId: ReturnType<typeof setInterval> | null = null;

	/**
	 * The Quick Sheet's `BrewParams` at shot start, stashed by the brew
	 * dashboard via {@link setBrewParamsSnapshot} just before
	 * {@link startShot} fires. Read by the `ShotCompleted` handler so the
	 * five user-facing fields the v2 schema doesn't carry — `yieldTarget`,
	 * `brewTemp`, `preinfuseTarget`, `stopOnWeight`, `autoTare` — freeze
	 * onto the persisted `StoredShot` instead of being read live (which
	 * would let a post-shot dial change retroactively rewrite history,
	 * the snapshot-vs-live failure mode).
	 *
	 * `null` between shots and on a headless / replay path that does not
	 * pass through the Quick Sheet — both leave the `StoredShot`'s QC
	 * fields absent and downstream readers fall back to the embedded
	 * profile slot. Cleared at `ShotCompleted` so a never-completed shot
	 * does not leak its snapshot into the next one.
	 */
	private brewParamsAtShotStart: {
		yieldTarget: number;
		brewTemp: number;
		preinfuseTarget: number;
		stopOnWeight: boolean;
		autoTare: boolean;
	} | null = null;

	constructor(
		private readonly core: CremaCore,
		/**
		 * The app-wide Effect runtime (Option 3, T-16). `null` only on
		 * unsupported browsers where the shell never mounts it; every Visualizer
		 * side effect this orchestrator owns (`UploadQueue.armLifecycle`, the
		 * shot-completion upload) routes through it and is a no-op when absent.
		 */
		private readonly runtime: AppRuntime | null = null
	) {
		this.de1 = new De1Manager(core, {
			onCoreOutput: (output) => this.applyCoreOutput(output),
			// A DE1 status line is both the live status and an event-log entry,
			// so the connect's step-by-step diagnostics land in the log.
			onStatus: (line) => {
				this.state.patch({ status: line });
				this.state.log(line);
			},
			onState: (de1State) => {
				const wasReady = this.state.current.de1State === 'ready';
				this.state.patch({ de1State });
				// Defensive re-upload of the active profile when the DE1
				// becomes ready. Mirrors the legacy DE1-app's
				// `save_settings_to_de1` → `de1_send_shot_frames` chain
				// (the 2026-05-21 HCI snoop confirmed it fires ~80 ms
				// after the connect-time subscriptions complete).
				// `ensureLoadedMatches()` consults the cached
				// fingerprint (persisted across reloads via localStorage)
				// to skip the upload when the DE1 still has the right
				// bytes — without it, the DE1 wakes up with no profile
				// loaded and the user has to remember to click Load on
				// Brew every session.
				if (de1State === 'ready' && !wasReady) {
					// Fire a `de1Connected` webhook on the first transition
					// into `ready`. The firmware string lands a moment later
					// via `Event::Firmware`, so it may be `null` here on the
					// very first connect — payload tolerates `null`.
					const diag = this.state.current.de1Diagnostics;
					this.fireWebhook('de1Connected', {
						firmwareVersion: this.state.current.de1Firmware,
						model: this.state.current.de1MachineInfo[MmrRegister.MachineModel] ?? null,
						deviceId: diag.deviceId,
						deviceName: diag.deviceName
					});
				}
				if (de1State === 'ready') {
					this.ensureLoadedMatches();
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
		}, runtime);
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
			onState: (scaleState) => {
				const wasReady = this.state.current.scaleState === 'ready';
				this.state.patch({ scaleState });
				// Fire `scaleConnected` on the first transition into ready.
				// The advertised name and capabilities arrive a beat later
				// via `onScaleIdentified` / `refreshScaleCapabilities`, so
				// `scaleName` may still be `null` here; payload tolerates it.
				if (scaleState === 'ready' && !wasReady) {
					this.fireWebhook('scaleConnected', {
						deviceName: this.state.current.scaleName,
						deviceId: null
					});
				}
			},
			onScaleIdentified: (advertisedName) => {
				void this.refreshScaleCapabilities(advertisedName);
			},
			// After the auto-reconnect loop recovers the scale link, re-fire the
			// settings query so the config read-back refreshes from the device.
			onReconnected: () => {
				void this.core.queryScaleSettings().then((output) => this.applyCoreOutput(output));
			}
		}, runtime);
		// Hydrate the last-uploaded profile fingerprint from localStorage,
		// so a page reload that kept the DE1 connected (or a quick
		// reconnect to the same machine) skips the defensive auto-upload
		// on `de1State === 'ready'`. `null` (no prior upload, or a fresh
		// install) leaves the snapshot's field untouched — the connect-
		// time `ensureLoadedMatches()` will see no cache and upload.
		const persisted = readJson<string | null>(LAST_FINGERPRINT_KEY, null);
		if (persisted !== null) {
			this.state.patch({ activeProfileFingerprint: persisted });
		}
		// Best-effort: ask the browser for persistent storage (so IndexedDB
		// captures aren't evicted under disk pressure), and garbage-collect
		// any captures whose StoredShot no longer exists. Fire and forget —
		// neither is on the critical path of app readiness.
		void this.bootCaptureStore();
		// Wire the Visualizer upload-queue lifecycle (drain once on app start +
		// every 5 min foreground tick). The `online` / `visibilitychange` DOM
		// listeners are wired at the shell (`+layout.svelte`), which owns the
		// runtime's lifetime. No-op when there's no runtime (unsupported browser).
		void this.runtime?.runPromise(Effect.flatMap(UploadQueue, (q) => q.armLifecycle));
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
			// Stash the prior error text so we can detect a `null → text`
			// transition into an `Error*` substate after the fold below.
			const priorMachineError = this.state.current.machineError;
			this.state.applyEvent(event);
			if (event.type === 'MachineStateChanged') {
				const nextMachineError = this.state.current.machineError;
				if (nextMachineError !== null && nextMachineError !== priorMachineError) {
					// Edge-triggered: only the entry into an error substate
					// fires. A persistent error that re-asserts the same
					// substate does not re-fire; the user does not want a
					// per-notification flood.
					this.fireWebhook('machineError', {
						errorText: nextMachineError,
						machineState: event.content.state,
						subState: event.content.substate
					});
				}
			}
			if (event.type === 'Telemetry') {
				// Water accumulation (E1): integrate the DE1's group flow over
				// the wall-clock gap since the previous telemetry sample, the
				// de1app's `volume += GroupFlow × Δt` approach.
				const now = performance.now();
				// Replayed telemetry must never advance the REAL water-filter /
				// descale counters (review #35) — the machine-state gate passes
				// during a replay because the replayed events drive the same
				// snapshot.
				if (
					this.lastTelemetryAtMs !== null &&
					this.countsTowardWater() &&
					this.replayFiber === null
				) {
					const deltaS = Math.min(
						MAX_TELEMETRY_GAP_S,
						(now - this.lastTelemetryAtMs) / 1000
					);
					getMaintenanceStore().accumulate(event.content.group_flow, deltaS);
				}
				this.lastTelemetryAtMs = now;
			}
			if (event.type === 'ShotCompleted') {
				// Persist the learned SAW drip model — a weight-stopped shot
				// just added a training sample in the core.
				void this.core
					.sawModelJson()
					.then((blob) => writeJson(SAW_MODEL_KEY, blob))
					.catch(() => undefined);
			}
			if (event.type === 'SawAutoZeroed') {
				// The guard trusted the settled cup and re-tared in software —
				// SAW keeps working on the net weight.
				toast.info(
					`Scale re-zeroed on the fly — ${Math.round(event.content.offset_g)} g was on it`
				);
			}
			if (event.type === 'SawSuppressedUntaredCup') {
				// Untared-cup guard (Decenza weightprocessor.cpp:242-253) —
				// tell the user WHY stop-at-weight went quiet this shot.
				toast.info(
					`Stop-at-weight off for this shot — the scale wasn't tared (${Math.round(event.content.weight_g)} g on it)`
				);
			}
			if (event.type === 'ShotStarted') {
				// Mark the shot's start so ShotCompleted can slice the raw BLE
				// capture (with a lead-in) for the IndexedDB capture store.
				// Use the core's most-recent notification timestamp rather
				// than `performance.now()`: the rolling capture buffer is
				// keyed in whatever timebase the caller hands the core, and
				// during replay that's the replay file's original
				// timestamps. Falling back to wall clock here would put the
				// slice bounds in a different timebase than the buffer's
				// entries, missing every byte. The `?? performance.now()`
				// guard covers the rare path where ShotStarted somehow folds
				// before any notification has reached the core.
				this.shotStartedAtMs =
					this.core.lastNotificationAtMs ?? performance.now();
				// Populate the ActiveShot store with the live shell's view
				// of the user's intent. Replay populates it itself before
				// the events fire, so when a replay's `ShotStarted` event
				// arrives the store is already set — we leave it alone.
				// See docs/49-replay-architecture-activeshot.md §3.
				if (!getActiveShotStore().isActive) {
					getActiveShotStore().set(this.buildLiveActiveShot());
				}
			}
			if (event.type === 'ProfileUploadCompleted') {
				// Hand the completion to the `ProfileSync` service (T-21): it
				// stamps the download-guard clock (opening the BC 9788201734
				// window `profileDownloadGuard` reads), resolves the
				// `syncActiveProfile` waiter (the lazy re-upload path on
				// `startShot()`), and returns the just-landed fingerprint — or
				// `null` if no upload was pending. `runSync` is safe: `completed`
				// is fully synchronous, so the supersession `cancel` in `sync`
				// observes the cleared pending before it returns. No runtime ⇒ no
				// DE1 ⇒ this event never fires, so the guard is belt-and-braces.
				const committed = this.runtime
					? this.runtime.runSync(ProfileSync.pipe(Effect.flatMap((ps) => ps.completed)))
					: null;
				// Commit the fingerprint into the snapshot + persist it across
				// reloads (the rune store + localStorage stay here — the service
				// owns coordination, not persistence). Skip when nothing was
				// pending (e.g. a late completion after a 15 s timeout).
				if (committed !== null) {
					this.state.patch({ activeProfileFingerprint: committed });
					writeJson(LAST_FINGERPRINT_KEY, committed);
				}
				// Fire `profileUploaded` webhook — the core gives us the
				// title; the dose / yield come from the active profile in
				// the library (the same profile we just uploaded). `null`
				// for fields the library cannot resolve.
				{
					const profiles = getProfileStore();
					const activeProfile = profiles.activeId
						? profiles.get(profiles.activeId)
						: undefined;
					this.fireWebhook('profileUploaded', {
						profileName: event.content.title,
						profileId: activeProfile?.id ?? null,
						doseG: activeProfile?.dose ?? null,
						yieldG: activeProfile?.yieldOut ?? null
					});
				}
			}
			if (event.type === 'ProfileUploadFailed') {
				// The DE1 still runs whatever it had before the failed upload, so
				// the cached fingerprint is unchanged — `ProfileSync.failed`
				// resolves the waiter `false` and clears the pending entry so
				// `syncActiveProfile` surfaces the problem to its caller (T-21).
				this.runtime?.runSync(ProfileSync.pipe(Effect.flatMap((ps) => ps.failed)));
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
				// Persist the shot's history row, run live-only side effects
				// (bean burn-down, webhook), enqueue the Visualizer push,
				// and persist the capture slice with both META preludes.
				// See `$lib/history/shot-persistence` for the whole pipeline.
				commitShotCompletion(event, {
					snapshot: this.state.current,
					shotStartedAtMs: this.shotStartedAtMs,
					lastNotificationAtMs: this.core.lastNotificationAtMs,
					sliceJsonl: (from, to) => this.core.captureSliceJsonl(from, to),
					fireWebhook: (eventType, payload) => this.fireWebhook(eventType, payload),
					runtime: this.runtime
				});
				// Orchestrator-owned timers / snapshot caches still clear
				// here — they're our state, not the persistence module's.
				// `brewParamsAtShotStart` cleared AFTER the commit call so
				// the at-shot-start META build inside the commit (which
				// reads from the ActiveShot store, not from this snapshot
				// directly) can't race a clear.
				this.shotStartedAtMs = null;
				this.brewParamsAtShotStart = null;
			}
		}
		for (const command of output.commands) {
			void this.executeCommand(command);
		}
	}

	/**
	 * Build an `ActiveShotData` from current shell state — the active
	 * library bean, the active profile (for dose + name), the pre-tap
	 * Quick Sheet snapshot, and the grinder-model cascade. Called at
	 * `ShotStarted` to populate the in-flight context.
	 *
	 * The replay path populates `ActiveShot` directly from `parsed.meta`
	 * and never invokes this helper — so live-store reads are scoped
	 * exclusively to live shots.
	 */
	private buildLiveActiveShot(): ActiveShotData {
		const library = getBeanStore();
		const activeBean = library.activeBean;
		const activeRoaster = activeBean?.roasterId
			? library.getRoaster(activeBean.roasterId)
			: null;
		const beanSnapshot = snapshotFromBean(activeBean, activeRoaster);

		const profiles = getProfileStore();
		const activeProfile = profiles.activeId
			? profiles.get(profiles.activeId)
			: undefined;

		// Three-tier cascade for grinder model (most-specific wins) —
		// matches the resolution that today's ShotCompleted handler
		// performs inline. Resolved at WRITE time (now) so a later
		// settings change can't rewrite the historical record.
		const grinderModel =
			activeBean?.grinder?.trim() ||
			getSettingsStore().current.grinderModel?.trim() ||
			null;

		const snapshot = this.state.current;
		const profileName = snapshot.activeProfileName ?? activeProfile?.name ?? null;
		const dose = activeProfile?.dose ?? null;
		const brewParams = this.brewParamsAtShotStart;

		return {
			bean: beanSnapshot,
			profileName,
			dose,
			brewParams: brewParams ? { ...brewParams } : null,
			grinderModel,
			source: 'live'
		};
	}

	/**
	 * Fire a webhook for `eventType` with `payload`, gated on the user's
	 * settings (`webhookEnabled`, a non-empty `webhookUrl`, and the
	 * matching `webhookEvents.<eventType>` toggle).
	 *
	 * Fire-and-forget — the caller does not await, the response is
	 * discarded, and any error (network, abort) lands as a log line in
	 * the BLE debug panel instead of surfacing to the user. No retries;
	 * if the user's endpoint is down, that's their problem.
	 *
	 * Sent with `mode: 'no-cors'` so it reaches endpoints that don't
	 * return CORS headers (most webhook receivers). Two consequences,
	 * both intentional: (1) no-cors only permits a "safelisted"
	 * Content-Type, so we send `text/plain` — the body is still a JSON
	 * string the receiver can `JSON.parse`, just not labelled
	 * `application/json`; endpoints that *require* that header AND lack
	 * CORS (e.g. Discord) are unreachable from any browser regardless.
	 * (2) The response is opaque, so status is unreadable — fine here
	 * since we discard it.
	 *
	 * The "Send test" button in Advanced → Webhooks uses
	 * {@link sendTestWebhook} instead, which surfaces the outcome.
	 */
	private fireWebhook(eventType: string, payload: object): void {
		// Delegated to the `Webhooks` service (T-23): it owns the same gating
		// (enabled + non-empty URL + per-event toggle), the no-cors `text/plain`
		// delivery, and the never-fail posture. `runFork` is detached fire-and-
		// forget — same as the old `void fetch()`. No-op when there's no runtime.
		// (Observability change per plan T-15: a delivery failure is now an
		// `Effect.logError` rather than a BLE-debug-panel line.)
		this.runtime?.runFork(Effect.flatMap(Webhooks, (w) => w.fire(eventType, payload)));
	}

	/**
	 * Send a one-shot test webhook to the configured URL and return a
	 * human-readable outcome the `AdvancedSection` test button surfaces
	 * inline.
	 *
	 * Tries a normal (CORS) request first so it can report a real HTTP
	 * status when the endpoint supports CORS. If that's blocked — the
	 * common case, since most receivers send no CORS headers — it retries
	 * with `mode: 'no-cors'`, exactly how {@link fireWebhook} delivers
	 * live events. The opaque retry can't be inspected, so success there
	 * means "dispatched without a network/CSP error"; delivery itself
	 * can't be confirmed from the browser, which the message says plainly.
	 */
	async sendTestWebhook(): Promise<{ ok: boolean; message: string }> {
		// Delegated to `Webhooks.sendTest` (T-23): same CORS-first → opaque-
		// fallback ladder + readable outcome. `runPromise` is safe (the service's
		// error channel is `never`).
		if (this.runtime === null) return { ok: false, message: 'App runtime unavailable.' };
		const url = getSettingsStore().current.webhookUrl;
		return this.runtime.runPromise(Effect.flatMap(Webhooks, (w) => w.sendTest(url)));
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
		// The fingerprint cache (snapshot + localStorage) is wiped on
		// an explicit disconnect: the DE1 may be powered off or the
		// user may be moving to a different machine, so the cached
		// "what's on it" assumption no longer holds. A page-reload
		// path (no explicit disconnect) keeps the localStorage value
		// for cheap reconnects — see the hydrate in the constructor.
		writeJson(LAST_FINGERPRINT_KEY, null);
		this.state.patch({
			...CLEARED_DE1_READOUT,
			activeProfileFingerprint: null
		});
	}

	// ---- Scale actions ----------------------------------------------------

	/** Connect a Bookoo scale — call from a button handler. */
	async connectScale(): Promise<void> {
		await this.scale.connect();
	}

	/** Disconnect the scale and clear every scale-derived field. */
	async disconnectScale(): Promise<void> {
		// Tear down the scale-heartbeat clock before the GATT teardown,
		// so an in-flight `scaleHeartbeat` write doesn't race against the
		// disconnect.
		this.clearScaleHeartbeat();
		await this.scale.disconnect();
		this.state.patch({
			scaleWeight: null,
			scaleFlow: null,
			scaleTimer: null,
			scaleCapabilities: null,
			scaleVolume: DEFAULT_SCALE_VOLUME,
			scaleStandby: DEFAULT_SCALE_STANDBY,
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
	 *
	 * Also (re)arms the Decent-Scale heartbeat clock when the connected scale
	 * is a Decent Scale — the LCD enable packet that the core's auto-policy
	 * sends on the next DE1 Idle transition arms a heartbeat-required flag on
	 * the scale; without periodic heartbeats the LCD goes back to sleep.
	 */
	private async refreshScaleCapabilities(advertisedName: string): Promise<void> {
		const caps = await this.core.scaleCapabilities();
		this.state.patch({ scaleCapabilities: caps ?? null, scaleName: advertisedName });
		this.armScaleHeartbeat(caps?.heartbeat_interval_ms ?? null);
	}

	/**
	 * Start (or restart) the scale-heartbeat clock when the connected
	 * scale's capability declares a non-null cadence; clear any existing
	 * clock otherwise. The cadence + device identification both live in
	 * the core (`ScaleCapabilities::heartbeat_interval_ms`); the shell
	 * just owns the host clock.
	 */
	private armScaleHeartbeat(intervalMs: number | null): void {
		this.clearScaleHeartbeat();
		if (intervalMs == null || intervalMs <= 0) return;
		this.scaleHeartbeatId = setInterval(() => {
			void this.core
				.scaleHeartbeat()
				.then((output) => this.applyCoreOutput(output))
				.catch(() => {
					// Best-effort; the next interval retries.
				});
		}, intervalMs);
	}

	/** Clear the scale-heartbeat clock if one is running. */
	private clearScaleHeartbeat(): void {
		if (this.scaleHeartbeatId !== null) {
			clearInterval(this.scaleHeartbeatId);
			this.scaleHeartbeatId = null;
		}
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
	 * "AC mains frequency" select. 50/60 selections go through
	 * `MainsConfirmModal` first; `0` (Auto) does not need confirmation.
	 */
	async setLineFrequencyOverride(hz: 0 | 50 | 60): Promise<void> {
		await this.core.setLineFrequencyOverride(hz);
	}

	/**
	 * Tell the core which unit the user wants on the connected scale's
	 * on-scale LCD. The Settings page calls this whenever the
	 * `weightUnit` pref changes.
	 *
	 * The core caches the pref so the Decent Scale and Skale II LCD-enable
	 * auto-policy picks the right wire packet on the next DE1 Idle entry,
	 * and the Eureka Precisa / Solo Barista's set-unit-grams write fires
	 * automatically. To push the new variant *immediately* (rather than
	 * waiting for an Idle re-entry), follow this call with
	 * {@link refreshScaleLcd}.
	 */
	async applyWeightUnitPref(unit: 'g' | 'oz'): Promise<void> {
		await this.core.setWeightUnitPref(unit);
	}

	/**
	 * Push the user's "auto-tare on shot start" preference into the core.
	 * The core gates the start-of-shot tare on this latched flag, so
	 * toggling it OFF takes effect on the very next shot — including
	 * GHC-initiated shots that bypass {@link startShot}.
	 */
	async applyAutoTare(enabled: boolean): Promise<void> {
		await this.core.setAutoTare(enabled);
	}

	/**
	 * Push the user's "stop on weight" preference into the core. When
	 * off, SAW never arms even if a target weight is configured.
	 */
	async applyStopOnWeight(enabled: boolean): Promise<void> {
		await this.core.setStopOnWeight(enabled);
	}

	/**
	 * Per-shot kill switch for the weight target. Set when the user
	 * toggles the QC yield dot OFF (suppressed=true) or ON
	 * (suppressed=false). The core consults this on every shot start in
	 * addition to {@link applyStopOnWeight}; either flag suppresses.
	 */
	async applyWeightTargetDisabled(disabled: boolean): Promise<void> {
		await this.core.setWeightTargetDisabled(disabled);
	}

	/**
	 * Opt into the profile's max-volume stop racing stop-at-weight while a
	 * scale is connected. Off (default) = volume only arms as the no-scale
	 * fallback. Purely core-side — the firmware tail volume stop is never
	 * uploaded, so no profile re-upload is needed.
	 */
	async applyVolumeStopWithScale(enabled: boolean): Promise<void> {
		await this.core.setVolumeStopWithScale(enabled);
	}

	/**
	 * Clear the running scale-derived peaks (peak weight + final weight).
	 * The Scale page's "Reset peak" button calls this when the user
	 * wants a fresh measurement window.
	 */
	async resetScalePeaks(): Promise<void> {
		await this.core.resetScalePeaks();
	}

	/**
	 * Toggle steam eco mode on the DE1 — low-flow, lower-temp steam for
	 * cleaner milk texture on small jugs. The core writes the new steam
	 * settings to the firmware; `applyCoreOutput` dispatches the BLE
	 * commands.
	 */
	async applySteamEcoMode(enabled: boolean): Promise<void> {
		this.applyCoreOutput(await this.core.enableSteamEcoMode(enabled));
	}

	/**
	 * Push the global max-shot-duration safety guardrail into the core,
	 * in seconds. `0` (or any non-positive value) clears the limit.
	 */
	async applyMaxShotDuration(seconds: number): Promise<void> {
		await this.core.setMaxShotDuration(seconds > 0 ? seconds : undefined);
	}

	/**
	 * Push the active profile's recipe target weight (yield, grams) into
	 * the core. Called whenever the active profile changes or its yield
	 * value is edited. `0` clears the target (SAW becomes a no-op for the
	 * profile recipe — the per-shot dial may still override).
	 */
	async applyProfileTargetWeight(grams: number): Promise<void> {
		await this.core.setProfileTargetWeight(grams > 0 ? grams : undefined);
	}

	/**
	 * Push the active profile's volume limit (SAV, millilitres) into the
	 * core. Called whenever the active profile changes or its
	 * `maxTotalVolumeMl` is edited. `0` clears the limit.
	 */
	async applyProfileVolumeLimit(milliliters: number): Promise<void> {
		await this.core.setProfileVolumeLimit(milliliters > 0 ? milliliters : undefined);
	}

	/**
	 * Push the per-shot dial-override target weight (grams) into the
	 * core. Called by the QC yield stepper. `0` clears the override and
	 * lets the profile recipe target take effect.
	 */
	async applyShotTargetWeight(grams: number): Promise<void> {
		await this.core.setShotTargetWeight(grams > 0 ? grams : undefined);
	}

	/**
	 * Re-emit per-scale unit / LCD packets in the *current* weight unit
	 * for every scale that has a reactive surface tied to the user's
	 * weight-unit pref. Use after {@link applyWeightUnitPref} when the
	 * user wants the on-scale display to switch units immediately.
	 *
	 * Each underlying core method is independently capability-gated, so a
	 * call when (say) a Bookoo is connected dispatches nothing — only the
	 * scale that is currently connected reacts.
	 *
	 * Covers (per PR G): Decent Scale (LCD enable in g/oz), Skale II
	 * (`ED EC` + optional `0x03` enable-grams), Eureka Precisa / Solo
	 * Barista (`SET_UNIT_GRAMS` when the pref is grams), Difluid
	 * Microbalance (`SET_UNIT_GRAMS`), Hiroia Jimmy (toggle-unit if the
	 * scale is currently non-grams — the toggle fires automatically from
	 * the weight-notification path; no manual write here).
	 */
	async refreshScaleLcd(): Promise<void> {
		const unit = getSettingsStore().current.weightUnit;
		// Capability-driven now: one `enableScaleLcd` (handles Decent +
		// Skale) and, on grams, one `setScaleUnitGrams` (handles Eureka /
		// Solo / Difluid). Both reject with `UnsupportedOnHardware` for
		// scales without the capability — caught and silently ignored,
		// since this method is called for whatever scale happens to be
		// connected and a no-op on the wrong shape is intended.
		const ignoreUnsupported = (out: Promise<CoreOutput>) =>
			out.then((o) => this.applyCoreOutput(o)).catch(() => undefined);
		await ignoreUnsupported(this.core.enableScaleLcd(unit));
		if (unit === 'g') {
			await ignoreUnsupported(this.core.setScaleUnitGrams());
		}
	}

	/**
	 * Alias preserved for backwards compatibility: `refreshDecentScaleLcd`
	 * was the PR-F shell entry point, before the same reactive flow was
	 * extended to Skale II + Eureka Precisa + Difluid in PR G. New code
	 * should call {@link refreshScaleLcd}.
	 */
	async refreshDecentScaleLcd(): Promise<void> {
		await this.refreshScaleLcd();
	}

	/**
	 * Toggle whether the connected scale should be powered off when the
	 * DE1 enters Sleep. Off by default; the user opts in via the Machine
	 * settings page. Capability-driven — applies to any scale with a
	 * host-driven power-off (Decent / Eureka / Solo today).
	 */
	async setAutoOffScaleOnSleep(enabled: boolean): Promise<void> {
		await this.core.setAutoOffScaleOnSleep(enabled);
	}

	/**
	 * Whether the connected scale is configured to power off on Sleep
	 * entry. Read by the Machine settings page to populate the toggle.
	 */
	async autoOffScaleOnSleep(): Promise<boolean> {
		return this.core.autoOffScaleOnSleep();
	}

	/**
	 * Power off the connected scale. Capability-driven — Decent / Eureka
	 * / Solo expose this; every other scale rejects with
	 * `UnsupportedOnHardware`.
	 *
	 * No UI surface for this today; exposed here so a future Settings
	 * action or Disconnect-button affordance can call it without touching
	 * the core wiring again.
	 */
	async powerOffScale(): Promise<void> {
		this.applyCoreOutput(await this.core.powerOffScale());
	}

	/** Beep the connected scale's buzzer — a test tone / locate-my-scale ping. */
	async scaleBeep(): Promise<void> {
		this.applyCoreOutput(await this.core.scaleBeep());
	}

	/**
	 * Commit the mains heater voltage to MMR `0x803834`. **Hardware-damaging
	 * if mis-set** — the caller MUST have gone through `MainsConfirmModal`
	 * first. Only `120` and `230` are accepted; the core throws otherwise.
	 *
	 * Wire-side, the core encodes `volts + 1000` (the firmware's
	 * user-committed marker). The shell does not need to know about
	 * that — pass `120` or `230` straight from the modal's confirmed value.
	 */
	async setHeaterVoltage(volts: 120 | 230): Promise<void> {
		this.applyCoreOutput(await this.core.setHeaterVoltage(volts));
	}

	/**
	 * Reset 8 DE1 machine settings to factory baseline — fan threshold,
	 * hot-water idle temp, heater phase 1/2 flows, espresso warmup
	 * timeout, refill kit auto mode, flow-calibration multiplier, and
	 * steam purge mode. Mirrors reaprime's `DELETE /api/v1/machine/
	 * settings/reset`. Profiles, shot history, and app preferences are
	 * untouched; only the DE1's MMR registers change. The Advanced
	 * settings section gates this behind a native `window.confirm`
	 * (blast radius: the user retunes their settings — non-damaging).
	 */
	async resetMachineDefaults(): Promise<void> {
		this.applyCoreOutput(await this.core.resetMachineDefaults());
	}

	/**
	 * Set the flush water target temperature, °C — the MMR `FlushTemp`
	 * register (`0x803844`) the firmware holds during a group-flush. Real
	 * write; the brew page's Flush stepper calls this on commit so the
	 * QC value reaches the machine. Wire scale `°C × 10` lives in the
	 * core. No read-back here — the connect-time MMR sweep populates
	 * `de1MachineInfo[FlushTemp]`, and the BrewDashboard's `flushTempC`
	 * derive already prefers the live machine value when present.
	 */
	async setFlushTemp(celsius: number): Promise<void> {
		this.applyCoreOutput(await this.core.setFlushTemp(celsius));
	}

	/**
	 * Write the four QC-modeled steam / hot-water fields via read-modify-write
	 * (issue 14). The combined cuuid_0B packet carries eight fields; the
	 * timeouts / espresso volume / group temp the Quick Sheet doesn't model are
	 * seeded from the machine's last `ShotSettingsRead` (`de1ShotSettings`) so
	 * this change doesn't reset them. Pre-read, the legacy-app defaults stand
	 * in. `steamFlags` is always 0 (legacy parity; bits undocumented).
	 */
	async setSteamHotwater(values: {
		steamTempC: number;
		steamTimeoutS: number;
		hotWaterTempC: number;
		hotWaterVolumeMl: number;
	}): Promise<void> {
		const m = this.state.current.de1ShotSettings;
		this.applyCoreOutput(
			await this.core.setSteamHotwaterSettings({
				steamFlags: 0,
				steamTempC: values.steamTempC,
				steamTimeoutS: values.steamTimeoutS,
				hotWaterTempC: values.hotWaterTempC,
				hotWaterVolumeMl: values.hotWaterVolumeMl,
				hotWaterTimeoutS: m?.hotWaterTimeout ?? 60,
				espressoVolumeMl: m?.espressoVolume ?? 200,
				groupTempC: m?.groupTemp ?? 92
			})
		);
	}

	/** Set the steam flow rate, ml/s — the Quick Sheet Steam→Flow stepper. */
	async setSteamFlow(mlPerS: number): Promise<void> {
		this.applyCoreOutput(await this.core.setSteamFlow(mlPerS));
	}

	/** Set the group-flush timeout, seconds — the Quick Sheet Flush→Time
	 *  stepper. The core's setter takes milliseconds. */
	async setFlushTimeoutS(seconds: number): Promise<void> {
		this.applyCoreOutput(await this.core.setFlushTimeout(Math.round(seconds * 1000)));
	}

	/**
	 * Set the cup-warmer plate temperature — Bengle hardware only.
	 * Celsius is the canonical unit (see docs/25 §7). MMR `CupWarmerTemp`
	 * (`0x803874`). `0` turns the plate off; the firmware ignores the
	 * write on non-Bengle models so the call is safe to gate UI-side
	 * without core-side guards.
	 */
	async setCupWarmerTemp(celsius: number): Promise<void> {
		this.applyCoreOutput(await this.core.setCupWarmerTemp(celsius));
	}

	/**
	 * Start the connected scale's built-in timer (Bookoo today;
	 * capability-gated in the core — a weight-only scale gets a no-op).
	 * Surfaced for manual milk-only sessions where shot-state auto-wiring
	 * doesn't fire.
	 */
	async startTimer(): Promise<void> {
		this.applyCoreOutput(await this.core.startTimer());
	}

	/** Stop the connected scale's built-in timer. */
	async stopTimer(): Promise<void> {
		this.applyCoreOutput(await this.core.stopTimer());
	}

	/** Reset the connected scale's built-in timer to zero. */
	async resetTimer(): Promise<void> {
		this.applyCoreOutput(await this.core.resetTimer());
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
	 * error string the caller can surface as a toast.
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
			// Crema-exported `.shot.json` files ride extras under
			// `metadata.crema.*` that the v2 schema (and the Rust wasm
			// parser) doesn't model — grinder model, tags, QC-override
			// snapshot, the inline-bean snapshot's roast extras. Extract
			// them before they're lost so a Crema → file → Crema round-
			// trip reconstructs the same `StoredShot`. Legacy `.shot`
			// imports skip this — TCL files predate the escape valve.
			const extras = isV2Json ? extractCremaExtras(content) ?? undefined : undefined;
			const record = getHistoryStore().addImported(imported, extras);
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
	 * Defensive ensure-loaded on a fresh DE1 connect: if the active
	 * profile's effective fingerprint differs from the snapshot's cached
	 * `activeProfileFingerprint` (or no fingerprint is cached at all),
	 * upload it. Mirrors the legacy de1app's `save_settings_to_de1`
	 * convention — guarantees the DE1 always has what the user wants
	 * loaded, without re-uploading bytes the machine already has.
	 *
	 * Fire-and-forget — a failure surfaces in the event log via the
	 * normal `applyCoreOutput` stream and does not block any other
	 * connect step. Profile store still loading → wait for it (the
	 * `ensureLoaded` call resolves once the built-in library has been
	 * deserialised; without this guard a fresh launch would skip the
	 * upload because `activeId` is briefly `null`).
	 *
	 * No active profile → no upload — the DE1 stays empty until the
	 * user picks one from the Profiles page; the next connect (or the
	 * next shot tap) is when the sync kicks in.
	 */
	private ensureLoadedMatches(): void {
		void (async () => {
			const profiles = getProfileStore();
			await profiles.ensureLoaded();
			const id = profiles.activeId;
			if (id === null) {
				this.state.log(
					'No active profile to sync — select one on Profiles; subsequent connects will auto-push it.'
				);
				return;
			}
			const profile = profiles.get(id);
			if (!profile) {
				this.state.log(
					`Active profile id "${id}" not found in store — sync skipped.`
				);
				return;
			}
			// Compute the connect-time fingerprint with no QC overrides —
			// the user hasn't dialed anything yet, so the "current intent"
			// is just the profile's own defaults. The shot-start path
			// (below) reuses the same hash with the user's QC overrides
			// merged in.
			const desired = profileFingerprint(profile, {});
			if (this.state.current.activeProfileFingerprint === desired) {
				this.state.log(
					`DE1 already has "${profile.name}" loaded (fingerprint match) — sync skipped.`
				);
				return;
			}
			this.state.log(`Sync on connect: ${profile.name}`);
			try {
				await this.syncActiveProfile(profile, {});
			} catch (e) {
				// `syncActiveProfile` already logs failure detail through
				// the `ProfileUploadFailed` event fold; swallow the
				// rejection so it doesn't bubble out of this fire-and-
				// forget caller.
				void e;
			}
		})();
	}

	/**
	 * Upload `profile` to the DE1 unless the snapshot's cached
	 * `activeProfileFingerprint` already matches the desired one. When an
	 * upload is needed, awaits `ProfileUploadCompleted` so callers can
	 * sequence a follow-up action (most commonly: `startShot()`'s
	 * `requestMachineState(Espresso)`) after the bytes are confirmed
	 * landed.
	 *
	 * Returns `true` when the DE1 ends up holding the desired fingerprint
	 * — either because the cache already matched, or because the upload
	 * completed successfully. Returns `false` if the upload failed; the
	 * `ProfileUploadFailed` event's fold logs the cause. A pending sync
	 * already running on the same fingerprint is awaited rather than
	 * re-issued, so two rapid Coffee taps don't double-upload.
	 *
	 * Throws only on a *write-side* failure (the BLE characteristic
	 * write rejected before the core could even emit `ProfileUploadStarted`);
	 * the typical async-failure path is `false` via `ProfileUploadFailed`.
	 */
	async syncActiveProfile(
		profile: CremaProfile,
		qc: ProfileFingerprintOverrides
	): Promise<boolean> {
		const desired = profileFingerprint(profile, qc);
		const alreadyLoaded = this.state.current.activeProfileFingerprint === desired;
		// No runtime ⇒ no Effect-routed services (and no Web Bluetooth ⇒ no DE1
		// to upload to). The cache check is the only meaningful answer; a real
		// upload can't happen, so report "loaded" only on a hit.
		if (this.runtime === null) return alreadyLoaded;
		// All coordination — the cache short-circuit, piggyback on an in-flight
		// upload of the same fingerprint, supersession of a different one
		// (`cancel` → `ProfileUploadFailed{Aborted}` → waiter resolves `false`),
		// and the 15 s backstop — lives in the `ProfileSync` service (T-21). The
		// upload still leaves through the core + BLE write cascade, confirmed by
		// the `ProfileUploadCompleted` fold calling `ProfileSync.completed`.
		return runtimePromise(
			this.runtime,
			ProfileSync.pipe(
				Effect.flatMap((ps) =>
					ps.sync({
						desired,
						alreadyLoaded,
						upload: Effect.promise(() => this.uploadProfile(toCoreProfile(profile))),
						cancel: Effect.promise(() => this.cancelProfileUpload()),
						log: (line) => this.state.log(line)
					})
				)
			)
		);
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
	 * `standby` capability bounds; optimistic, then stream-confirmed.
	 */
	async setScaleStandby(minutes: number): Promise<void> {
		const range = this.state.current.scaleCapabilities?.standby;
		const clamped =
			range !== undefined ? Math.min(Math.max(minutes, range.min), range.max) : minutes;
		this.state.patch({ scaleStandby: clamped });
		this.applyCoreOutput(await this.core.setScaleStandby(clamped));
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

	/**
	 * Write a new sensor calibration: the DE1 reported `reported` while the
	 * externally-measured true value was `measured`. Both arguments are in
	 * the sensor's canonical units (°C / bar / ml·s⁻¹) — the caller is
	 * responsible for converting from display units. Re-reads the current
	 * calibration after the write so the UI reflects the value the DE1 now
	 * reports as in-use; the factory read is unchanged.
	 */
	async writeCalibration(
		sensor: import('$lib/core').CalTarget,
		reported: number,
		measured: number
	): Promise<void> {
		this.applyCoreOutput(await this.core.writeCalibration(sensor, reported, measured));
		await this.readCalibration(sensor, false);
	}

	/**
	 * Reset a sensor to its factory calibration. After the reset, refreshes
	 * both the in-use and factory values so the UI reflects what the DE1
	 * now applies (and a stale factory readout from a prior session is
	 * brought up to date).
	 */
	async resetCalibrationToFactory(sensor: import('$lib/core').CalTarget): Promise<void> {
		this.applyCoreOutput(await this.core.resetCalibrationToFactory(sensor));
		await this.readCalibration(sensor, false);
		await this.readCalibration(sensor, true);
	}

	/**
	 * Read one MMR register from the DE1. The reply lands as an
	 * `Event::MmrValue` and folds into `de1MachineInfo[register]` on the
	 * snapshot. Used by the Calibration screen on mount to populate the
	 * flow-multiplier row from `MmrRegister::CalibrationFlowMultiplier`,
	 * and after each Apply / Reset so the row's "Current" label re-renders
	 * from the live read.
	 */
	async readMmr(register: import('$lib/core').MmrRegister): Promise<void> {
		this.applyCoreOutput(await this.core.readMmr(register));
	}

	/**
	 * Write the DE1's flow-calibration multiplier (MMR `0x80383C`). The core
	 * clamps to `0.13..=2.0` and encodes `int(1000 × multiplier)` LE on the
	 * wire. Re-reads the register after the write so `de1MachineInfo` (and
	 * the Calibration screen's Current label) reflect the firmware's
	 * accepted value.
	 */
	async setCalibrationFlowMultiplier(multiplier: number): Promise<void> {
		this.applyCoreOutput(await this.core.setCalibrationFlowMultiplier(multiplier));
	}

	// ---- User-presence heartbeat ------------------------------------------
	//
	// `markUserPresent()` writes a `1` to `0x803860` via `WriteToMMR`
	// (`cuuid_06`) — the legacy `set_user_present` heartbeat
	// (`de1_comms.tcl:1166`). Resets the DE1's "user has gone away"
	// timer. Debounced to once per minute by the caller (`createCremaApp`
	// attaches the document listener).
	//
	// The `suppressDe1Sleep` setting gates whether the heartbeat loop in
	// `createCremaApp` actually fires; the FeatureFlags bit is written
	// unconditionally as `1` on every connect (`onState('ready')` block,
	// matching reaprime's `enableUserPresenceFeature()`), so the DE1 is
	// always listening to the UserPresent register. Suppress=ON → feature
	// bit=1 + heartbeat running → DE1 sleeps only when Crema is genuinely
	// idle. Suppress=OFF → feature bit=1 + heartbeat silent → DE1 follows
	// its own ~30 min timer. The setting is read directly off the
	// settings store on each tick, so no orchestrator-side setter is
	// needed.

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
		// Auto-purge and the pre-shot flush both fire `HotWaterRinse`. If a user
		// steams and immediately presses "Brew" within the 1.5 s defer window,
		// both paths can race to request the same state. The `rinsePending`
		// flag (shared with `startShot`) lets whichever path acquires it first
		// proceed; the other bails out cleanly.
		if (this.rinsePending) return;
		this.rinsePending = true;
		window.setTimeout(() => {
			try {
				// Only fire from Idle — avoid stepping on a manual user action.
				if (this.state.current.machineStateName !== MachineState.Idle) return;
				void this.requestMachineState(MachineState.HotWaterRinse);
			} finally {
				this.rinsePending = false;
			}
		}, 1500);
	}

	/**
	 * Shared in-flight guard for `HotWaterRinse` requests. Set by either the
	 * post-steam auto-purge path or the pre-shot flush path while their rinse
	 * is pending; the other path checks it and bails out so two rinses cannot
	 * race when a user steams then immediately starts a shot.
	 */
	private rinsePending = false;

	/**
	 * Stash the live Quick Sheet `BrewParams` snapshot on the orchestrator
	 * so the next `ShotCompleted` can freeze them onto the persisted
	 * `StoredShot`. Mirrors the bean / grinder-model / tags snapshot
	 * pattern: capture-time-frozen, not live-read at export, so a later
	 * dial change cannot rewrite history.
	 *
	 * Callable independently of {@link startShot} — the brew dashboard
	 * calls this every time the user taps Coffee (just before the shot
	 * request goes out), and a manual on-machine start (no Crema tap)
	 * simply leaves the snapshot at its previous value; the `null`
	 * fallback after `ShotCompleted` keeps that case sane.
	 */
	setBrewParamsSnapshot(snapshot: {
		yieldTarget: number;
		brewTemp: number;
		preinfuseTarget: number;
		stopOnWeight: boolean;
		autoTare: boolean;
	}): void {
		this.brewParamsAtShotStart = { ...snapshot };
	}

	/**
	 * Start an espresso shot — the Brew screen's primary action. Three
	 * sequential steps, each pre-condition-gated:
	 *
	 *  1. **No-profile blocker.** If no active profile is selected, refuse
	 *     the shot via a thrown {@link NoActiveProfileError} so the caller
	 *     can surface a "Select a profile first" banner. The DE1 simply
	 *     has nothing to load without an active profile.
	 *  2. **Lazy profile sync.** Compute the effective fingerprint from
	 *     the active profile + the caller's per-shot QC overrides; if it
	 *     differs from the cached `activeProfileFingerprint`, upload +
	 *     await `ProfileUploadCompleted` before going further. The
	 *     upload's "Uploading… N/N" pip in the dash header is the
	 *     user-visible sync indicator during the 1-2 s upload window.
	 *  3. **Pre-shot flush (optional)** + **Espresso state-request**.
	 *     Mirrors the prior behaviour: when `groupFlushBeforeShot` is
	 *     on, fire `HotWaterRinse` and wait for its
	 *     `WaterSessionCompleted(Flush)` (with a 30 s ceiling) before
	 *     requesting Espresso.
	 *
	 * @param qc Per-shot Quick Controls overrides — the caller passes the
	 *   live `BrewParamState.current` fields so the fingerprint reflects
	 *   the user's *current* intent, not just the profile's own defaults.
	 *   Defaults to no overrides for callers that don't have a brew sheet
	 *   (e.g. headless scripts) — equivalent to running the profile bare.
	 */
	async startShot(qc: ProfileFingerprintOverrides = {}): Promise<void> {
		const profiles = getProfileStore();
		await profiles.ensureLoaded();
		const id = profiles.activeId;
		if (id === null) {
			throw new NoActiveProfileError();
		}
		const profile = profiles.get(id);
		if (!profile) {
			throw new NoActiveProfileError();
		}
		// Lazy profile sync — compare the desired fingerprint against the
		// cached "what's on the DE1" fingerprint. `syncActiveProfile` is
		// a no-op when they match (cheap djb2 + one snapshot read).
		const synced = await this.syncActiveProfile(profile, qc);
		if (!synced) {
			// `ProfileUploadFailed`'s fold already logged the cause; the
			// dashboard's machine-error / log banner will pick it up.
			throw new ProfileSyncFailedError(
				'Profile sync failed — DE1 still holds the previous profile. ' +
					'Try Coffee again, or reload the profile from the Profiles page.'
			);
		}
		if (!getSettingsStore().current.groupFlushBeforeShot) {
			await this.requestMachineState(MachineState.Espresso);
			return;
		}
		// Pre-shot group flush. Hold the shared `rinsePending` guard so a
		// just-scheduled auto-purge (within its 1.5 s defer) bails out
		// instead of racing this flush. Kick off the rinse, then wait for
		// its completion (or the 30 s ceiling) before requesting Espresso.
		this.rinsePending = true;
		try {
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
		} finally {
			this.rinsePending = false;
		}
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
		// Profile-download race guard (BC 9788201734): the DE1 firmware holds
		// `ProfileDownloadInProgress` for a short window after the final frame
		// write — a state-request inside it is aborted to HeaterDown after
		// preinfuse. `ProfileSync.profileDownloadGuard` waits out the remaining
		// 500 ms window since the last `ProfileUploadCompleted` (T-21). No-op
		// when no upload has completed, or when there's no runtime.
		if (this.runtime !== null) {
			await runtimePromise(
				this.runtime,
				ProfileSync.pipe(Effect.flatMap((ps) => ps.profileDownloadGuard))
			);
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
	 * `$lib/services/replay`'s `replayCaptureProgram`); `opts.speed` scales the
	 * pace. Progress and outcome are exposed on `state.current.replay`;
	 * {@link cancelReplay} stops it.
	 *
	 * Only one replay runs at a time — a second call while one is in progress is
	 * ignored. The replay does not interact with any live BLE connection: it
	 * resets the core, so a connected device's session would be discarded — it
	 * is strictly an offline diagnostic.
	 */
	async replayCapture(file: File, opts: ReplayCaptureOptions = {}): Promise<void> {
		if (this.replayFiber) return;
		// A live DE1 keeps notifying while the replay's shadow core is
		// installed — its packets would land in the shadow session and both
		// streams would fight over the one display (review #35). Replay is
		// strictly an offline diagnostic: require a disconnect first.
		if (this.state.current.de1State === 'ready' || this.state.current.de1State === 'subscribing') {
			this.state.patch({
				replay: {
					phase: 'error',
					fileName: file.name,
					done: 0,
					total: 0,
					message: 'Disconnect the DE1 before replaying — a live session would fight the replay for the display.'
				}
			});
			return;
		}

		// Pre-flight (no shadow core touched yet, so a failure just paints
		// `error` and returns): the file-size cap, the parse, and the empty check.
		const MAX_REPLAY_FILE_BYTES = 50 * 1024 * 1024; // 50 MB
		const errorState = (message: string) =>
			this.state.patch({ replay: { phase: 'error', fileName: file.name, done: 0, total: 0, message } });

		if (file.size > MAX_REPLAY_FILE_BYTES) {
			errorState(
				`Capture file is ${(file.size / 1024 / 1024).toFixed(1)} MB — Crema caps replay at ${MAX_REPLAY_FILE_BYTES / 1024 / 1024} MB to keep the tab responsive. Trim the file or replay it through the Rust CLI.`
			);
			return;
		}

		let parsed;
		try {
			parsed = await parseCaptureFile(file);
		} catch (error) {
			errorState(`Could not read capture: ${describeError(error)}`);
			return;
		}
		if (parsed.events.length === 0) {
			errorState('No replayable notifications found in this capture.');
			return;
		}

		// The shadow-core swap + paced loop run as an Effect (T-28). It needs no
		// services (everything's injected), so it runs on Effect's *default*
		// runtime — offline replay keeps working even where the Web-Bluetooth-
		// gated `AppRuntime` never mounts. The fiber is held for `cancelReplay`
		// → `Fiber.interrupt`; the program restores the live core + clears
		// ActiveShot in its `acquireUseRelease` release, no matter how it ends.
		const fiber = Effect.runFork(
			replayCaptureProgram({
				core: this.core,
				parsed,
				fileName: file.name,
				speed: opts.speed,
				clearedReadout: CLEARED_DE1_READOUT,
				applyOutput: (output) => this.applyCoreOutput(output),
				patch: (snapshot) => this.state.patch(snapshot),
				setActiveShot: (data) => getActiveShotStore().set(data),
				clearActiveShot: () => getActiveShotStore().clear(),
				resetTelemetryAnchor: () => {
					this.lastTelemetryAtMs = null;
				},
				currentDone: () => this.state.current.replay?.done ?? 0
			})
		);
		this.replayFiber = fiber;
		try {
			await Effect.runPromise(Fiber.await(fiber));
		} finally {
			this.replayFiber = null;
		}
	}

	/** Cancel an in-progress capture replay, if one is running. Interrupting the
	 *  fiber cancels the in-flight `Effect.sleep` and runs the program's release
	 *  (restore live core + clear ActiveShot) → `cancelled` state. */
	cancelReplay(): void {
		if (this.replayFiber) Effect.runFork(Fiber.interrupt(this.replayFiber));
	}
}

/**
 * Load the wasm core and construct the orchestrator. Call once at app start,
 * e.g. in the screen's `onMount`.
 */
export async function createCremaApp(runtime: AppRuntime | null = null): Promise<CremaApp> {
	const core = await loadCore();
	const app = new CremaApp(core, runtime);
	// The read-side context singletons (BrewContext, MachineReadout,
	// HistoryContext) construct themselves lazily on first access via
	// the shared store + `getCremaUiState()` singletons — no bind step
	// needed. See `$lib/state/{brew-context,machine-readout,history-context}.svelte`.
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
	// Push the persisted weight-unit pref into the core so the Decent
	// Scale LCD-enable auto-policy (triggered on the DE1's Idle entry)
	// picks the right wire packet from the first state notification.
	// Subsequent changes flow via `app.applyWeightUnitPref(...)` invoked
	// from the Settings page on every `weightUnit` change.
	void core.setWeightUnitPref(getSettingsStore().current.weightUnit);
	// Push the persisted shot-behaviour settings into the core so SAW +
	// auto-tare fire correctly on the very first shot — including a shot
	// initiated via the DE1's group-head touch (GHC), which bypasses
	// `startShot()`. The core latches these and consults them on
	// `ShotEvent::Started` / first flowing phase regardless of initiator.
	// Subsequent changes flow via the matching `applyXxx(...)` methods
	// on `CremaApp` whenever the Settings / Profile / QC surfaces mutate.
	{
		const s = getSettingsStore().current;
		void core.setAutoTare(s.autoTareOnShotStart);
		void core.setStopOnWeight(s.stopOnWeight);
		void core.setVolumeStopWithScale(s.volumeStopWithScale);
		void core.setMaxShotDuration(s.maxShotDurationS > 0 ? s.maxShotDurationS : undefined);
	}
	// Seed the learned SAW drip model (core-owned opaque blob) from its
	// persisted store; saved back after every completed shot.
	{
		const blob = readJson<string | null>(SAW_MODEL_KEY, null);
		if (blob) void core.setSawModelJson(blob);
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
	// Register the eager DE1 upload hook on profile activation. Every
	// `ProfileStore.setActive(...)` site (BrewDashboard, ProfileEditor,
	// ShotDetail, profiles page, library delete-self) gets a
	// fingerprint-gated upload for free.
	//
	// Connection-state policy: only upload when the DE1 is `ready`. If
	// the user activates a profile while disconnected, the upload is
	// deferred — the existing `ensureLoadedMatches()` call on the next
	// `de1State === 'ready'` transition picks up the fingerprint
	// mismatch and pushes the bytes then. This avoids firing BLE writes
	// at a missing device + keeps "activate now, connect later" working.
	//
	// Fire-and-forget — failures surface via the existing
	// `profileUploadProgress` / log UI.
	{
		const profiles = getProfileStore();
		// Profile-deletion cascade: clear bean linked-profile references so
		// activation never chases a tombstone (registered here, not imported
		// in the store — $lib/bean/activate imports $lib/profiles).
		profiles.onDeleted = (id) => {
			getBeanLibraryStore().clearLinksTo(id);
		};
		profiles.onActivate = (profile) => {
			if (profile == null) return;
			if (app.state.current.de1State !== 'ready') return;
			void app.syncActiveProfile(profile, {}).catch((e) => {
				app.state.log(
					`Eager profile upload failed: ${e instanceof Error ? e.message : String(e)}`
				);
			});
		};
	}
	return app;
}
