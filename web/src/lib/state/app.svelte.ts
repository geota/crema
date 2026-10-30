/**
 * `#lib/state/app` — the orchestrator: the web mirror of the Android shell's
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

import { refreshStopTargetsProjection } from './ui-state.svelte';
import {
	loadCore,
	type Command,
	type CoreOutput,
	type CremaCore
} from '#lib/core/index.js';
import { MachineState, MmrRegister } from '#lib/core/crema-core.js';
import type { BrewRecipe, MaintenanceFailure } from '#lib/core/crema-core.js';
import { getGuidedBrewStore } from '#lib/brew/session.svelte.js';
import { playBrewCue } from '#lib/brew/cues.js';
import {
	De1Manager,
	EMPTY_DE1_DIAGNOSTICS,
	ScaleManager,
	compactLine,
	reconnectTimelines,
	type ReconnectTrigger
} from '#lib/ble/index.js';
import { getBeanStore, getBeanLibraryStore } from '#lib/bean/index.js';
import { getHistoryStore, snapshotFromBean, extractCremaExtras } from '#lib/history/index.js';
import { commitShotCompletion } from '#lib/history/shot-persistence.js';
import { getCaptureStore } from '#lib/capture/index.js';
import {
	getProfileStore,
	effectiveCremaProfile,
	preinfusionSeconds,
	type CremaProfile,
	type ProfileFingerprintOverrides
} from '#lib/profiles/index.js';
import { writeJson } from '#lib/utils/storage.js';
import type { ProfileSyncStatus } from '#lib/core/index.js';
import { isSerialStolen } from '#lib/decent/stolen-serials.js';
import { SAW_MODEL_KEY, seedSawModel } from './saw-model-store';
import { getSettingsStore } from '#lib/settings/index.js';
import { dismissToast, toast } from '#lib/components/shared/toast.svelte.js';
import { CoreRecoveryWatch } from '#lib/core/recovery-watch.js';
import { CoreResetNotice } from '#lib/core/reset-notice.js';
import { canReloadNow, reloadInputsOf } from '#lib/pwa/update-gate.js';
import { getMaintenanceStore } from '#lib/maintenance/index.js';
import { parseCaptureFile } from '#lib/replay/index.js';
import { describeError } from '#lib/utils/error.js';
import { Effect, Fiber } from 'effect';
import type { AppRuntime } from '#lib/effect/runtime.js';
import { runtimePromise } from '#lib/effect/bridge.js';
import { UploadQueue } from '#lib/services/upload-queue.js';
import { Webhooks } from '#lib/services/webhooks.js';
import { ProfileSync, profileWriteAck } from '#lib/services/profile-sync.js';
import { CalibrationRefusedError, calibrationRefusalIn } from '#lib/calibration/refusal.js';
import { replayCaptureProgram } from '#lib/services/replay.js';
import {
	CremaUiState,
	DEFAULT_SCALE_STANDBY,
	DEFAULT_SCALE_VOLUME,
	EMPTY_DE1_CALIBRATION,
	getCremaUiState,
	defaultRefillPointMm,
	isMaintenanceTerminal,
	maintenanceFailedMessage,
	waterTankPercent,
	type UiSnapshot
} from './ui-state.svelte';
import { getActiveShotStore, type ActiveShotData } from './active-shot.svelte';
import { ConnectSweepRunner, readBatteryPercent } from './connect-sweep';
import { IdleSleepRunner } from './idle-sleep';
import { CoreTicker } from './core-ticker';
import { stepCapRefusal } from '#lib/profiles/step-cap.js';
import {
	ESPRESSO_ENTRY_TIMEOUT_MS,
	NOT_STARTED_NOTICE,
	PRESHOT_FLUSH_TIMEOUT_MS,
	StartSequence,
	type StartToken
} from './start-sequence';
import {
	ProfileWriteGenerations,
	UNNAMED_PROFILE_BATCH_LOG,
	isProfileWriteTarget,
	startedUploadId,
	type ProfileWriteBatch,
	type ProfileWriteOutcome
} from '#lib/services/profile-write-generations.js';
import { FirmwareUpdateController } from '#lib/firmware/firmware-update.svelte.js';
import { readPowerOk } from '#lib/firmware/power.js';
import { setFirmwareWakeLock } from '#lib/shell/wake-lock.js';
import type { FlashShellConditions } from '#lib/core/index.js';
import {
	LaunchReconnect,
	forgetDevice,
	markUserDisconnect,
	rememberDevice,
	type DeviceKind
} from '#lib/ble/launch-reconnect.js';
import { installIosBridge, isBridgedBluetooth } from '#lib/ble/ios-bridge.js';
import { watchBluetoothAvailability } from '#lib/ble/availability.js';
import { connectBlocker } from '#lib/ble/connect-blocker.js';
import { readImportText } from '#lib/utils/import-limits.js';

/** `navigator.bluetooth` as it is now (the iOS bridge may install it late). */
function currentBluetooth(): Bluetooth | undefined {
	return typeof navigator !== 'undefined' ? navigator.bluetooth : undefined;
}

/**
 * Toast and return true when a Connect can't open the chooser here (no Web
 * Bluetooth, the iOS Home Screen app, Bluetooth off) — otherwise the chooser
 * would just fail. Synchronous: it runs inside the click's user activation.
 */
function refuseConnect(): boolean {
	const blocked = connectBlocker();
	if (blocked === null) return false;
	const href = blocked.openInSafari;
	if (href !== undefined) {
		toast.action(blocked.message, 'Open in Safari', () => {
			window.location.href = href;
		});
	} else {
		toast.error(blocked.message);
	}
	return true;
}

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
 * fails (or times out) before the Espresso state request goes out, or when
 * the profile has more steps than the DE1 runs. What the DE1 holds after a
 * failed upload is unknown (the core re-uploads at Idle), so the shot is
 * refused — the caller can offer "try again" via a banner.
 */
export class ProfileSyncFailedError extends Error {
	constructor(message: string) {
		super(message);
		this.name = 'ProfileSyncFailedError';
	}
}

/**
 * Thrown by {@link CremaApp.startShot} when the "Require scale to start"
 * setting is on, this shot resolves a weight target, and no scale is
 * connected (issue #29 — the de1app
 * `start_espresso_only_if_scale_connected` / reaprime `blockOnNoScale`
 * pattern). The DE1 is left untouched; the dashboard surfaces the message
 * as a banner and the accompanying toast offers a one-tap Connect.
 */
export class ScaleRequiredError extends Error {
	constructor(
		message: string = 'Shot not started — no scale connected, and "Require scale to start" is on.'
	) {
		super(message);
		this.name = 'ScaleRequiredError';
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
} as const satisfies Partial<UiSnapshot>;

/**
 * Battery percentage at or below which the one-per-connection "charge your
 * scale" warning fires (issue #29) — early enough to charge before a session,
 * late enough not to nag.
 */
const SCALE_LOW_BATTERY_PCT = 25;

/**
 * How long the Quick Controls dial must rest before its overrides become the
 * desired profile (de1app debounces its temperature dial by 0.5 s too), so a
 * run of stepper taps uploads once.
 */
const DIAL_PUSH_DEBOUNCE_MS = 500;

/** The five Quick Sheet values a shot records as they were at its start. */
interface ShotStartBrewParams {
	yieldTarget: number;
	brewTemp: number;
	preinfuseTarget: number;
	stopOnWeight: boolean;
	autoTare: boolean;
}

/** Two override sets with the same values (absent = absent). */
function sameOverrides(a: ProfileFingerprintOverrides, b: ProfileFingerprintOverrides): boolean {
	return (
		a.dose === b.dose && a.yield === b.yield && a.brewTemp === b.brewTemp && a.preinf === b.preinf
	);
}

/**
 * The replay-specific helpers — `composeReplayStartMessage`,
 * `replayMetaToActiveShot`, `guessScaleAdvertisedName` — moved to
 * `#lib/services/replay` alongside the `replayCaptureProgram` they serve (T-28).
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

	/** One outstanding stop write at a time (issue #15 follow-up). */
	#stopWriteInFlight = false;
	/** Which profile writes may still go out and be acked (#2 N2). */
	readonly #profileWrites = new ProfileWriteGenerations();
	/** The 1 s core clock while the DE1 link is up or recovering (#2 N1). */
	private readonly ticker: CoreTicker;
	private readonly de1: De1Manager;
	private readonly scale: ScaleManager;
	/** Reconnect the last DE1 / scale on launch; a user action cancels its wait. */
	readonly #launch = new LaunchReconnect();
	/**
	 * Notices a core reset after a panic (batch 03's bridge recovery) and
	 * offers a reload: a fresh core has lost the shell's behaviour settings
	 * (stop on weight, auto-tare, targets) until the app reloads.
	 */
	readonly #coreRecovery: CoreRecoveryWatch;
	/** The engine-reset reload offer; the shell feeds it state changes. */
	readonly coreResetNotice: CoreResetNotice;

	/**
	 * Devices the user took over this session with a Connect tap that has not
	 * reached ready yet (chooser dismissed, a non-DE1 picked, a failed
	 * connect). Tab-return / Bluetooth-on kicks must not silently reconnect
	 * the remembered device behind that choice; a connect that reaches ready
	 * clears it, and the next app launch starts fresh.
	 */
	readonly #userTookOver: Record<DeviceKind, boolean> = { de1: false, scale: false };

	/**
	 * The fiber running an in-progress capture replay, or `null` when none is
	 * running. {@link cancelReplay} interrupts it (T-28 — the old
	 * `AbortController`; `Effect.sleep` pacing is interrupted for free).
	 */
	private replayFiber: Fiber.Fiber<void> | null = null;

	/**
	 * Whether the low-battery toast has fired for the current scale
	 * connection (issue #29) — reset on disconnect so the next session
	 * warns again, but a single connection never nags twice.
	 */
	private scaleLowBatteryWarned = false;

	/**
	 * Whether the connected DE1's serial is on Decent's stolen-machine list
	 * (`#lib/decent/stolen-serials.js`) — drives a non-blocking one-line notice
	 * in Settings → Machine. Checked when the serial is read; cleared on
	 * disconnect. Never blocks anything.
	 */
	stolenSerial = $state(false);

	/**
	 * `performance.now()` of the most recent `ShotStarted` event, or `null`
	 * before the first / between shots. Used by `ShotCompleted` to slice the
	 * rolling BLE-capture buffer for the IndexedDB capture store.
	 */
	private shotStartedAtMs: number | null = null;

	// The core owns the profile sync (when to upload, what the DE1 holds);
	// the `ProfileSync` service only lets a start wait for its fingerprint.
	// The `ProfileSyncHolds` / `ProfileUploadCompleted` / `ProfileUploadFailed`
	// fold arms call `ProfileSync.holds` / `.completed` / `.failed`;
	// `requestMachineState` awaits `ProfileSync.profileDownloadGuard`.

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

	/** The DE1 connect sweep + USB-charger minute check (`connect-sweep.ts`). */
	private readonly sweep: ConnectSweepRunner;

	/** The idle-sleep timer's feed ("Sleep after", `idle-sleep.ts`). */
	readonly idleSleep: IdleSleepRunner;

	/**
	 * Settings → Machine → "Update firmware…" (alpha): the flow's state and
	 * the driver that performs the core's firmware-flash actions over the
	 * DE1 link. See `#lib/firmware/firmware-update.svelte.js`.
	 */
	readonly firmware: FirmwareUpdateController;

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
	 * `null` between shots and on a path that does not pass through the
	 * Quick Sheet: a start at the group head records the dial the DE1 was
	 * given instead ({@link pushedBrewParams}); a replay sets its own.
	 * Cleared at `ShotCompleted` so a never-completed shot does not leak its
	 * snapshot into the next one.
	 */
	private brewParamsAtShotStart: ShotStartBrewParams | null = null;

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
		// The Reload goes through the update offer's idle gate (final review
		// 03 P3-2): never mid-shot, mid-steam, mid-brew or mid-flash.
		this.coreResetNotice = new CoreResetNotice({
			canReload: () =>
				canReloadNow(
					reloadInputsOf(this.state.current, getGuidedBrewStore().phase, this.firmware?.running ?? false)
				),
			show: (apply, closed) => {
				const id = toast.action(
					'Crema hit an internal error and restarted its engine. Stop-at-weight and other ' +
						'shot settings are off until you reload.',
					'Reload',
					apply,
					0,
					// Assertive (`role="alert"`, 07 P2 #7): stop-at-weight is off.
					'error',
					// Closed with ×: a later reset offers it again (V3 #3).
					{ onDismiss: closed }
				);
				return () => dismissToast(id);
			},
			// Past the bridge's reset cap every core call fails until a reload
			// (final cohort review 2026-10-28, 01 P3-1). Nothing works on a
			// stopped core, so this notice replaces the reset notice and its
			// Reload is not gated: there is no recording or live control left to
			// protect (V3 #7). Assertive, like the reset notice (V2 P3-1).
			showStopped: (reload) => {
				toast.action(
					'Crema hit repeated internal errors and stopped its engine. Reload to start it again.',
					'Reload',
					reload,
					0,
					'error'
				);
			},
			onDeferred: () =>
				toast.info('The machine is busy — the reload will be offered again once it is idle.', 6000),
			reload: () => location.reload()
		});
		this.#coreRecovery = new CoreRecoveryWatch(core.panicRecoveries?.bind(core), (count) => {
			this.state.log(`Core reset after an internal error (reset #${count})`);
			// The fresh core runs no upload and numbers uploads from 1 again:
			// no old batch may write, ack or report into it (C2 P3-1).
			this.#profileWrites.supersede();
			this.coreResetNotice.onReset();
		});
		// Logged once with the bridge's own message as context; the per-call
		// failures that follow are not logged (V2 P3-2), so this line and the
		// panic before it stay in the diagnostics export.
		core.onCoreStopped?.((reason) => {
			this.state.log(
				`Core stopped after repeated internal errors; reload required${reason ? ` (${reason})` : ''}`
			);
			this.coreResetNotice.onCoreStopped();
		});
		this.sweep = new ConnectSweepRunner({
			core,
			settings: () => getSettingsStore().current,
			tankTempC: async () => {
				const profiles = getProfileStore();
				await profiles.ensureLoaded();
				const id = profiles.activeId;
				return (id !== null ? profiles.get(id)?.tankTemperatureC : undefined) ?? 0;
			},
			battery: readBatteryPercent,
			apply: (out) => this.applyCoreOutput(out),
			// The core's clock is the notification clock (performance.now());
			// a wall-clock stamp here pushed it decades ahead and every
			// watchdog then compared against the wrong epoch.
			now: () => performance.now()
		});
		this.ticker = new CoreTicker({
			core,
			apply: (out) => this.applyCoreOutput(out),
			now: () => performance.now()
		});
		this.idleSleep = new IdleSleepRunner({
			core,
			apply: (out) => this.applyCoreOutput(out),
			minutes: () => getSettingsStore().current.idleSleepMin
		});
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
				// Leaving `ready` for anything but `subscribing` means the link is
				// gone (or being re-seeded after a reconnect): the last state the
				// DE1 reported is history. Clear it so nothing reads "Idle" from
				// before the drop until the next StateInfo seed read lands —
				// mirrors Android's `onDe1Dropped`.
				if (de1State !== 'ready' && de1State !== 'subscribing') {
					this.state.patch({ machineState: null, machineStateName: null, machineSubstate: null });
					// A start never resumes on a later connection (Decenza
					// cancels its deferred start on disconnect too).
					this.cancelStart('disconnected');
				}
				// The active profile goes to the DE1 on every connect: the
				// core's profile sync uploads it after the connect sweep
				// below (legacy de1app `save_settings_to_de1` →
				// `de1_send_shot_frames`, reaprime `WorkflowDeviceSync`).
				if (de1State === 'ready' && !wasReady) {
					// Every connect path (fresh, launch, reload, auto-reconnect)
					// tells a firmware flash the link is up, so a power-cycle wait
					// reads the build (audit r2 M-A).
					void this.firmware?.onLinkUp(this.state.current.de1Diagnostics.deviceId ?? null);
					this.#rememberConnected('de1');
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
				// While a firmware flash holds the link (a transfer, an interrupted
				// erase, or the power-cycle wait), a reconnect must not touch the
				// DE1: no profile auto-upload, user-present poke or settings
				// sweep (audit H2). They run once the flash lets go.
				const held = this.firmware?.holdsLink ?? false;
				if (de1State === 'ready' && !held) {
					// The post-connect steps are queued; the reconnect's timeline
					// ends here (a no-op with no open episode).
					reconnectTimelines.postConnectDone('DE1');
				}
				// The idle-sleep timer learns the link state (a reconnect is not
				// presence: no poke here — the core pokes on real activity only).
				this.idleSleep.onDe1State(de1State);
				// The core's watchdogs (upload acks, machine-model wait, re-upload
				// repeat, lost scale, steam eco) run on this clock.
				this.ticker.onDe1State(de1State);
				// The connect sweep: every machine setting Crema owns a
				// preference for, re-asserted because the DE1 forgets them
				// across a power cycle. The list is core-owned
				// (`CremaCore::connect_sweep` — de1app
				// `later_new_de1_connection_setup` + `set_heater_tweaks`,
				// Decenza `sendInitialSettings`): USB charger, the
				// user-presence feature flag, fan threshold, heater tweaks,
				// refill point, tank threshold, steam / hot water, steam
				// flow, flush, eco. Also runs the once-a-minute
				// USB-charger check while ready.
				this.sweep.onDe1State(held ? 'held-by-firmware' : de1State);
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
				// The lost-scale watchdog runs on the core clock, so a scale
				// alone keeps it ticking (a scale-only session).
				this.ticker.onScaleState(scaleState);
				// A terminal drop (auto-reconnect exhausted, or a failure)
				// must stop the heartbeat clock — it otherwise kept writing
				// to a dead device every ~2 s forever (review #34). The
				// user-disconnect path already clears it in disconnectScale.
				if (scaleState === 'disconnected' || scaleState === 'failed') {
					this.clearScaleHeartbeat();
				}
				// Fire `scaleConnected` on the first transition into ready.
				// The advertised name and capabilities arrive a beat later
				// via `onScaleIdentified` / `refreshScaleCapabilities`, so
				// `scaleName` may still be `null` here; payload tolerates it.
				if (scaleState === 'ready' && !wasReady) {
					this.#rememberConnected('scale');
					this.fireWebhook('scaleConnected', {
						deviceName: this.state.current.scaleName,
						deviceId: null
					});
				}
			},
			onScaleIdentified: (advertisedName) => {
				void this.refreshScaleCapabilities(advertisedName);
			},
			onWriteFailed: () => {
				// Re-query the scale's settings so the response stream
				// overwrites whatever a rejected write optimistically
				// patched (review #34). The manager fires this only while the
				// scale is ready, once per run of rejected writes.
				void this.core
					.queryScaleSettings()
					.then((out) => this.applyCoreOutput(out));
			},
			// After the auto-reconnect loop recovers the scale link, re-fire the
			// settings query so the config read-back refreshes from the device.
			onReconnected: () => {
				void this.core.queryScaleSettings().then((output) => this.applyCoreOutput(output));
			}
		}, runtime);
		this.firmware = new FirmwareUpdateController({
			core,
			io: {
				write: (target, data) => this.de1.firmwareWrite(target, data),
				readFwMap: () => this.de1.firmwareReadFwMap(),
				subscribeFwMap: () => this.de1.firmwareSubscribeFwMap(),
				setExclusive: (on) => this.de1.setFirmwareExclusive(on),
				setFwMapListener: (listener) => this.de1.setFwMapListener(listener),
				addLinkListener: (listener) => this.de1.addLinkListener(listener)
			},
			conditions: () => this.firmwareConditions(),
			installedBuild: () =>
				this.state.current.de1MachineInfo[MmrRegister.FirmwareVersion] ?? null,
			log: (line) => this.state.log(line),
			keepAlive: { set: setFirmwareWakeLock },
			onRelease: () => this.afterFirmwareRelease(),
			machineId: () => this.state.current.de1Diagnostics.deviceId ?? null,
			linkUp: () => this.state.current.de1State === 'ready',
			unavailableReason: () =>
				isBridgedBluetooth()
					? "Firmware updates aren't available on iPhone or iPad: Bluetooth runs through the Beacio Safari extension, and a dropped link mid-flash is too risky. Use Chrome or Edge on a computer, or the Android app."
					: null,
			transportLabel: () => {
				const d = this.state.current.de1Diagnostics;
				const ua = typeof navigator === 'undefined' ? 'unknown browser' : navigator.userAgent;
				const api = isBridgedBluetooth() ? 'Web Bluetooth via Beacio' : 'Web Bluetooth';
				return `${api} (${ua}), DE1 ${d.deviceName ?? '?'} id ${d.deviceId ?? '?'}`;
			}
		});
		// A flash that was waiting for the DE1's power-cycle before a reload
		// comes back with its lock (audit M3).
		void this.firmware.restore();
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
		// Each finished reconnect episode → one compact line in the event log.
		reconnectTimelines.onFinished = (t) => this.state.log(compactLine(t));
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
		this.#coreRecovery.check();
		for (const event of output.events) {
			// Stash the prior error text so we can detect a `null → text`
			// transition into an `Error*` substate after the fold below.
			const priorMachineError = this.state.current.machineError;
			this.state.applyEvent(event);
			if (event.type === 'MachineStateChanged') {
				this.startSequence.onMachineState(event.content.state);
			}
			if (event.type === 'ScaleLinkDead') {
				// The core presumed the scale link dead (silent past the grace,
				// or writes failing): drop it so the reconnect loop recovers it.
				this.scale.presumeDead(event.content.reason);
			}
			if (event.type === 'MachineErrorChanged') {
				const nextMachineError = this.state.current.machineError;
				if (nextMachineError !== null && nextMachineError !== priorMachineError) {
					// Edge-triggered: only the entry into an error fires — the
					// core emits one event per change, and holds the spurious
					// ErrorNoAc blip back entirely, so a heating machine never
					// pages anyone. A persistent error does not re-fire.
					this.fireWebhook('machineError', {
						errorText: nextMachineError,
						machineState: event.content.state,
						subState: event.content.substate
					});
				}
			}
			if (event.type === 'Telemetry') {
				// Water accumulation (E1): the core counts the water — espresso
				// and hot water only, gap-capped (`counted_ml`); the shell keeps
				// the persisted total. Replayed telemetry must never advance the
				// REAL water-filter / descale counters (review #35).
				const counted = event.content.counted_ml;
				if (counted != null && counted > 0 && this.replayFiber === null) {
					getMaintenanceStore().addCountedMl(counted);
				}
			}
			if (event.type === 'ShotCompleted') {
				// Persist the learned SAW drip model — a weight-stopped shot
				// just added a training sample in the core.
				void this.core
					.sawModelJson()
					.then((blob) => writeJson(SAW_MODEL_KEY, blob))
					.catch(() => undefined);
			}
			if (event.type === 'BrewSessionStarted') {
				getGuidedBrewStore().started(performance.now());
			}
			if (event.type === 'BrewStepChanged') {
				const store = getGuidedBrewStore();
				store.stepChanged(event.content.step_index, event.content.at_ms);
				// The chime for an auto-advanced boundary rides the step
				// change itself (the Boundary cue covers hold-steps).
				if (event.content.step_index > 0) {
					playBrewCue('step');
					store.cue('step');
				}
			}
			if (event.type === 'BrewCueDue') {
				const kind = event.content.cue === 'approach' ? 'approach' : 'boundary';
				playBrewCue(kind);
				// Visual cues are always on — the sound / haptics toggles
				// only gate the audible and tactile legs.
				getGuidedBrewStore().cue(kind);
			}
			if (event.type === 'BrewSessionCompleted') {
				getGuidedBrewStore().completed(event.content.summary);
				playBrewCue('done');
				this.stopBrewTick();
			}
			if (event.type === 'ScaleReading') {
				// Low-battery warning (issue #29): one toast per connection
				// the first time the scale's battery reads at or below the
				// threshold, so it can be charged before it dies mid-shot.
				const batt = this.state.current.scaleBattery;
				if (batt !== null && batt <= SCALE_LOW_BATTERY_PCT && !this.scaleLowBatteryWarned) {
					this.scaleLowBatteryWarned = true;
					toast.info(`Scale battery low (${batt}%) — charge it soon`);
				}
			}
			if (event.type === 'WaterLow') {
				// The core's low-water monitor (#33 follow-up): once per dip
				// below the user's threshold, re-armed on a refill, a threshold
				// change or a new link. Shown in the user's tank unit.
				const left =
					getSettingsStore().current.waterLevelUnit === 'percent'
						? `${waterTankPercent(event.content.level)}%`
						: `${Math.round(event.content.ml)} ml`;
				toast.info(`Water low — ${left} left, refill soon`);
			}
			if (event.type === 'MmrValue' && event.content.register === MmrRegister.SerialNumber) {
				// Decent's stolen-machine list: fetched at most daily, cached,
				// failures silent — a missing list just means no notice.
				const serial = event.content.value;
				void isSerialStolen(serial).then((stolen) => {
					if (this.state.current.de1MachineInfo[MmrRegister.SerialNumber] === serial) {
						this.stolenSerial = stolen;
					}
				});
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
			if (event.type === 'TareRefusedMidShot') {
				// The core dropped a tare that landed mid-pour rather than
				// obeying it (geota/crema#50) — otherwise the Tare control just
				// looks broken.
				toast.info(
					"Tare ignored — a shot is running. Taring now would break stop-at-weight."
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
			if (event.type === 'MaintenanceProgress' && event.content.cold_workaround) {
				this.onColdMaintenanceProgress(
					event.content.phase,
					event.content.state,
					event.content.failure
				);
			}
			if (event.type === 'ProfileUploadCompleted') {
				// Stamp the download-guard clock (opening the BC 9788201734
				// window `profileDownloadGuard` reads). `runSync` is safe:
				// `completed` is fully synchronous. No runtime ⇒ no DE1 ⇒ this
				// event never fires, so the guard is belt-and-braces.
				this.runtime?.runSync(ProfileSync.pipe(Effect.flatMap((ps) => ps.completed)));
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
				// A start waiting on the DE1 fails (the core retries the upload
				// on its own backoff and puts the profile back after any other
				// failed upload).
				this.runtime?.runSync(ProfileSync.pipe(Effect.flatMap((ps) => ps.failed)));
			}
			if (event.type === 'ProfileSyncHolds') {
				// The DE1 holds this effective profile: a start waiting for it
				// goes on.
				const { fingerprint } = event.content;
				this.runtime?.runSync(ProfileSync.pipe(Effect.flatMap((ps) => ps.holds(fingerprint))));
			}
			if (event.type === 'ProfileSyncWarning') {
				// The core's one non-blocking warning per streak of failed
				// profile uploads (it keeps retrying: 3 s, 10 s, then 30 s).
				if (event.content.active) {
					toast.info('Could not load the profile on the DE1 — retrying.', 6000);
					this.state.log('Profile upload to the DE1 failed — the core is retrying.');
				} else {
					this.state.log('Profile upload to the DE1 recovered.');
				}
			}
			if (event.type === 'WaterSessionStarted' && event.content.kind === 'Flush') {
				// OUR pre-shot flush began — only a completion of a flush
				// that started after the waiter armed may release it, so a
				// stray Flush completion (a manual flush already running, a
				// just-finished auto-purge) can't (review #34).
				this.startSequence.onFlushStarted();
			}
			if (event.type === 'WaterSessionCompleted' && event.content.kind === 'Flush') {
				// The pre-shot flush we requested just finished — release
				// the `startShot()` waiter so it can issue the Espresso
				// state request. The 30 s ceiling is the backstop if this
				// notification is missed.
				this.startSequence.onFlushCompleted();
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
				// See `#lib/history/shot-persistence.js` for the whole pipeline.
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
		// The profile writes of this output form one batch: a header write
		// supersedes every earlier upload's queued writes (#2 N2).
		const batch = this.#profileWrites.begin(output.commands, startedUploadId(output.events));
		if (this.#profileWrites.unnamed(batch)) this.state.log(UNNAMED_PROFILE_BATCH_LOG);
		for (const command of output.commands) {
			void this.executeCommand(command, batch);
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
		// A start at the group head has no Quick Sheet snapshot: record the
		// dial the DE1 was given instead.
		const brewParams =
			this.brewParamsAtShotStart ?? (activeProfile ? this.pushedBrewParams(activeProfile) : null);

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
	 * The shot-start snapshot for a shot the app did not start (the group
	 * head): `profile` with the overrides last given to the core as its
	 * desired profile — what the DE1 runs — or, before any, the live dial.
	 */
	private pushedBrewParams(profile: CremaProfile): ShotStartBrewParams {
		const desired = this.#desiredOverrides;
		const qc = desired?.profileId === profile.id ? desired.qc : this.brewOverridesFor(profile);
		const run = effectiveCremaProfile(profile, qc);
		const settings = getSettingsStore().current;
		return {
			yieldTarget: run.yieldOut,
			brewTemp: run.brewTemp,
			preinfuseTarget: preinfusionSeconds(run),
			stopOnWeight: settings.stopOnWeight,
			autoTare: settings.autoTareOnShotStart
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
	 * Run one core `Command`.
	 *
	 * `WriteScale` → write the bytes to the scale (manual tare, auto-tare, and
	 * every config write all surface here). `WriteCharacteristic` → route to
	 * the DE1 manager, which maps the `WriteTarget` to its GATT UUID and
	 * dispatches the bytes. The write-side wiring per target lives in
	 * `web/src/lib/ble/de1.ts:uuidForWriteTarget`; targets with no UUID
	 * mapping are dropped with a status log.
	 */
	/**
	 * The flash let go of the link: run what a reconnect skipped while it held
	 * it (the identity reads, the settings sweep, after which the core's
	 * profile sync uploads). The identity reads go first, like Android's
	 * `afterFirmwareRelease`: the core refused them during the hold, and the
	 * flash changed the build.
	 */
	private afterFirmwareRelease(): void {
		if (this.state.current.de1State !== 'ready') return;
		void this.de1.readMachineInfo();
		this.sweep.onDe1State('ready');
		// No presence poke here: the idle-sleep timer (#139) pokes on real
		// activity only, and the core pinned its countdown while the flash
		// held the DE1, so it starts over from this release.
	}

	/** What the shell knows for the firmware-flash preconditions. */
	private async firmwareConditions(): Promise<FlashShellConditions> {
		const de1State = this.state.current.de1State;
		return {
			connected: de1State === 'ready' || de1State === 'subscribing',
			ready: de1State === 'ready',
			// The core tracks guided brews itself (`brew_session`).
			guidedBrewActive: false,
			powerOk: await readPowerOk()
		};
	}

	private async executeCommand(
		command: Command,
		batch: ProfileWriteBatch | null = null
	): Promise<void> {
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
				// Stop-write hygiene (issue #15 follow-up, Android parity):
				// while a shot runs, machine-state writes are the stop. They
				// COALESCE — the core's enforcer re-commands every second, but
				// stacking identical Idle bytes behind a stalled write adds
				// nothing, and every extra copy is a stale-write risk once the
				// machine stops (a late Idle kills a steam/flush started right
				// after the shot). Web Bluetooth ops can't be cancelled
				// mid-flight, so unlike Android the ONE outstanding write
				// can't be revoked at ShotCompleted — bounding the exposure
				// to a single write is the strongest guard available here.
				const isStopWrite =
					command.content.target === 'De1RequestedState' && this.state.current.shotInProgress;
				if (isStopWrite && this.#stopWriteInFlight) {
					this.state.log('Stop write already in flight — not stacking another');
					break;
				}
				if (isStopWrite) this.#stopWriteInFlight = true;
				// A profile write goes out only while its upload is the live one
				// and none of its earlier writes failed — checked when it reaches
				// the head of the GATT queue, since every command of an output is
				// queued at once (#2 N2).
				const profileBatch = isProfileWriteTarget(command.content.target) ? batch : null;
				let skipped = false;
				const guard =
					profileBatch === null
						? undefined
						: () => {
								skipped = !this.#profileWrites.mayWrite(profileBatch);
								return !skipped;
							};
				// The result is recorded inside the queued step (`settled`), so a
				// failure stops this batch before its next write checks the guard.
				let outcome: ProfileWriteOutcome | null = null;
				const settled =
					profileBatch === null
						? undefined
						: (written: boolean) => {
								outcome = this.#profileWrites.settle(profileBatch, written);
							};
				let delivered = false;
				try {
					const data = new Uint8Array(command.content.data);
					const hex = [...data].map((b) => b.toString(16).padStart(2, '0')).join('');
					if (profileBatch === null || this.#profileWrites.mayWrite(profileBatch)) {
						this.state.log(`→ DE1 write ${command.content.target} ${hex}`);
					}
					delivered = await this.de1.writeCharacteristic(
						command.content.target,
						data,
						guard,
						settled
					);
				} finally {
					if (isStopWrite) this.#stopWriteInFlight = false;
				}
				if (profileBatch !== null) {
					if (skipped) break;
					// A skip before the queue (no link, firmware lock) never reached
					// the step: settle it here.
					outcome ??= this.#profileWrites.settle(profileBatch, delivered);
					if (outcome === 'failed') {
						// Stop the rest of this upload and fail it now, so the
						// re-upload does not wait for the ack watchdog.
						this.state.log('Profile write failed — stopping this upload');
						// Named by its upload, so a report that lands after a
						// newer upload started leaves that one alone (V3 #1);
						// not sent once superseded (a newer header, or a core
						// rebuilt after a panic, C2 P3-1).
						if (
							profileBatch.uploadId !== null &&
							this.#profileWrites.isCurrent(profileBatch)
						) {
							this.applyCoreOutput(
								await this.core.profileWriteFailed(performance.now(), profileBatch.uploadId)
							);
						}
						break;
					}
					// Superseded between the write and now: its ack must not reach
					// the newer upload.
					if (outcome === 'drop' || !this.#profileWrites.mayWrite(profileBatch)) break;
				}
				// The DE1 does *not* emit Handle Value Notifications on
				// FrameWrite (cuuid_10) — confirmed by the 2026-05-21
				// HCI snoop of a legacy-app session: every write gets only
				// an empty ATT Write Response. The legacy's "frame ack"
				// is its BLE wrapper echoing the written value into a
				// callback for debug logs, not real device traffic.
				//
				// To keep the core's per-frame upload state machine the
				// canonical source of truth without a phantom notification
				// stream, synthesize a De1HeaderAck / De1FrameAck from the
				// data we just wrote — but ONLY when the write succeeded.
				// A failed header or frame write gets no ack, so the core
				// fails the upload rather than report a half-written
				// profile as loaded (cohort review 02 P0-2).
				const ack = profileWriteAck(command.content.target, delivered);
				// Named by its upload, so the core drops it if a newer upload
				// started meanwhile (round-2 V1 P3-1).
				if (ack !== null && profileBatch?.uploadId != null) {
					const ackOut = await this.core.onProfileWriteAck(
						ack,
						new Uint8Array(command.content.data),
						performance.now(),
						profileBatch.uploadId
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
		if (refuseConnect()) return;
		this.#launch.cancel('de1');
		this.#userTookOver.de1 = true;
		this.state.patch({ ...CLEARED_DE1_READOUT });
		await this.de1.connect();
	}

	/**
	 * Cancel the maintenance cycle the core is following (Settings → Water's
	 * Cancel) — drops a request held for preheat on old firmware, or asks a
	 * running cycle to stop.
	 */
	async cancelMaintenance(): Promise<void> {
		this.applyCoreOutput(await this.core.cancelMaintenance());
	}

	/**
	 * The core loaded its 1 °C cold-maintenance profile (old firmware, cold
	 * machine). The core's profile sync puts the active profile back once the
	 * cycle is over (Decenza re-uploads it on leaving its descale page); the
	 * shell only reports a failed run.
	 */
	private onColdMaintenanceProgress(
		phase: string,
		state: string,
		failure: MaintenanceFailure | null | undefined
	): void {
		// The cold profile never loaded (or was replaced), so the core did not
		// send the request.
		if (phase === 'Failed') toast.error(maintenanceFailedMessage(state, failure));
	}

	/**
	 * Disconnect the DE1 and clear its readout fields. Every caller is a user
	 * action (rail menu, Settings), so the next launch won't reconnect it.
	 */
	async disconnectDe1(): Promise<void> {
		this.#launch.cancel('de1');
		markUserDisconnect('de1');
		// Leave the DE1's USB port charging (Decenza
		// `BatteryManager::ensureChargerOn`): smart charging may have
		// switched it off, and nothing would switch it back on once Crema is
		// gone.
		// Not while a firmware flash holds the DE1 (the core refuses it too).
		if (this.state.current.de1State === 'ready' && !(this.firmware?.holdsLink ?? false)) {
			try {
				this.applyCoreOutput(await this.core.setUsbChargerOn(true));
			} catch {
				// Best-effort: the DE1 re-enables its port within ~10 minutes.
			}
		}
		this.sweep.stop();
		await this.de1.disconnect();
		this.stolenSerial = false;
		this.state.patch(CLEARED_DE1_READOUT);
	}

	// ---- Reconnect on launch ----------------------------------------------

	/** A device reached ready: remember it for the next launch. */
	#rememberConnected(kind: DeviceKind): void {
		this.#userTookOver[kind] = false;
		const id = kind === 'de1' ? this.de1.identity() : this.scale.identity();
		if (id !== null) rememberDevice(kind, id);
	}

	/**
	 * Reconnect the DE1 / scale the user last connected, where the browser
	 * exposes `navigator.bluetooth.getDevices()` (see `#lib/ble/launch-reconnect.js`).
	 * A silent no-op elsewhere. A user Connect / Disconnect meanwhile cancels
	 * the wait for that device.
	 */
	async reconnectLastOnLaunch(bluetooth: Bluetooth | undefined = currentBluetooth()): Promise<void> {
		await this.#launch.run({
			bluetooth,
			isIdle: (kind) => {
				if (this.#userTookOver[kind]) return false;
				const s = kind === 'de1' ? this.state.current.de1State : this.state.current.scaleState;
				return s === 'idle' || s === 'disconnected' || s === 'failed';
			},
			connect: async (kind, device) => {
				if (kind === 'de1') {
					this.state.patch({ ...CLEARED_DE1_READOUT });
					await this.de1.connectKnown(device);
				} else {
					await this.scale.connectKnown(device);
				}
			}
		});
	}

	// ---- Reconnect kicks --------------------------------------------------

	/**
	 * Reconnect NOW — the tab came back into view (`visibilitychange` /
	 * `pageshow`). For each device whose auto-reconnect loop is waiting out a
	 * backoff or the 60 s lurk (which Chrome throttles in a hidden tab), the
	 * wait is cut short and the ladder resets to the fast burst. A no-op for a
	 * connected device, one the user disconnected, or no device at all;
	 * debounced per device.
	 */
	kickReconnect(trigger: ReconnectTrigger): void {
		const de1 = this.de1.kick(trigger);
		const scale = this.scale.kick(trigger);
		if (de1 === 'kicked') this.state.log(`DE1: reconnecting now (${trigger})`);
		if (scale === 'kicked') this.state.log(`Scale: reconnecting now (${trigger})`);
		// A launch reconnect that gave up (or whose advertisement watch the
		// browser stopped while the page was hidden) gets another pass. It
		// skips devices that are connected, connecting, or user-disconnected.
		void this.reconnectLastOnLaunch().catch((e: unknown) => {
			this.state.log(`Reconnect (${trigger}) failed: ${describeError(e)}`);
		});
	}

	/**
	 * Forget the DE1: disconnect, drop it from launch memory, and revoke this
	 * site's Bluetooth permission for it where the browser can.
	 */
	async forgetDe1(): Promise<void> {
		const live = this.de1.bluetoothDevice();
		await this.disconnectDe1();
		const revoked = await forgetDevice('de1', live, currentBluetooth());
		this.state.log(revoked ? 'DE1 forgotten and its Bluetooth permission revoked' : 'DE1 forgotten');
	}

	/** Forget the scale — see {@link forgetDe1}. */
	async forgetScale(): Promise<void> {
		const live = this.scale.bluetoothDevice();
		await this.disconnectScale();
		const revoked = await forgetDevice('scale', live, currentBluetooth());
		this.state.log(
			revoked ? 'Scale forgotten and its Bluetooth permission revoked' : 'Scale forgotten'
		);
	}

	/** "Retry now" on a reconnecting device. */
	retryReconnect(device: 'de1' | 'scale'): void {
		const result = (device === 'de1' ? this.de1 : this.scale).kick('user-retry');
		if (result === 'kicked') {
			this.state.log(`${device === 'de1' ? 'DE1' : 'Scale'}: reconnecting now (user-retry)`);
		}
	}

	// ---- Scale actions ----------------------------------------------------

	/** Connect a Bookoo scale — call from a button handler. */
	async connectScale(): Promise<void> {
		if (refuseConnect()) return;
		this.#launch.cancel('scale');
		this.#userTookOver.scale = true;
		await this.scale.connect();
	}

	/**
	 * Disconnect the scale and clear every scale-derived field. A user action,
	 * so the next launch won't reconnect it.
	 */
	async disconnectScale(): Promise<void> {
		this.#launch.cancel('scale');
		markUserDisconnect('scale');
		// Tear down the scale-heartbeat clock before the GATT teardown,
		// so an in-flight `scaleHeartbeat` write doesn't race against the
		// disconnect.
		this.clearScaleHeartbeat();
		await this.scale.disconnect();
		this.scaleLowBatteryWarned = false;
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
			// Only while the scale is ready: a reconnecting scale can't take the
			// write (Android stops its heartbeat when not READY too).
			if (this.state.current.scaleState !== 'ready') return;
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
	 * The live Quick Controls overrides and the profile they were dialled on —
	 * app-level state, the one source of truth whichever screen is showing:
	 * the Brew dashboard's dial reads them when it mounts and writes them as it
	 * moves. They are part of what the DE1 should hold, so a group-head start
	 * runs the dialled profile. Not persisted (like Android's `brewParams`): a
	 * restart starts with none. A profile change clears them.
	 */
	#brewOverrides = $state.raw<{ profileId: string; qc: ProfileFingerprintOverrides } | null>(
		null
	);

	/** The profile and overrides last given to the core as its desired profile. */
	#desiredOverrides: { profileId: string; qc: ProfileFingerprintOverrides } | null = null;

	/** The pending debounced push of a dial change ({@link setBrewOverrides}). */
	#overridesPush: ReturnType<typeof setTimeout> | null = null;

	/**
	 * Set the live overrides for `profile` (the dial moved). They become the
	 * core's desired profile after the dial rests {@link DIAL_PUSH_DEBOUNCE_MS}
	 * (de1app's 0.5 s), so stepping the dial uploads once, not per step. A
	 * repeat of the current overrides changes nothing.
	 */
	setBrewOverrides(profile: CremaProfile, qc: ProfileFingerprintOverrides): void {
		const cur = this.#brewOverrides;
		if (cur?.profileId === profile.id && sameOverrides(cur.qc, qc)) return;
		this.#brewOverrides = { profileId: profile.id, qc };
		if (this.#overridesPush !== null) clearTimeout(this.#overridesPush);
		this.#overridesPush = setTimeout(() => {
			this.#overridesPush = null;
			void this.setDesiredProfile(profile, qc);
		}, DIAL_PUSH_DEBOUNCE_MS);
	}

	/**
	 * The live overrides dialled on `profile`, or none — what the dial shows
	 * when it mounts, and what goes with that profile to the DE1. Reactive.
	 */
	brewOverridesFor(profile: CremaProfile | null): ProfileFingerprintOverrides {
		const cur = this.#brewOverrides;
		return profile !== null && cur?.profileId === profile.id ? cur.qc : {};
	}

	/**
	 * Tell the core which profile the DE1 should hold: the library `profile`
	 * (`null` = none active) with the Quick Controls overrides `qc` — by
	 * default the live ones dialled on that profile, so activation, edits and
	 * the boot push the overridden profile, never a bare one over a dial. The
	 * core's profile sync uploads it (the shell's only way to put a profile on
	 * the DE1) — on every connect, on a change, coalesced while an upload
	 * runs, retried with backoff, never while the machine is busy. The core
	 * ignores a repeat. Any upload it starts is applied like every other
	 * output. Returns the sync's status.
	 */
	async setDesiredProfile(
		profile: CremaProfile | null,
		qc: ProfileFingerprintOverrides = this.brewOverridesFor(profile)
	): Promise<ProfileSyncStatus> {
		// Another profile (or none) is active: the old dial no longer applies.
		if (this.#brewOverrides !== null && this.#brewOverrides.profileId !== profile?.id) {
			this.#brewOverrides = null;
		}
		this.#desiredOverrides = profile === null ? null : { profileId: profile.id, qc };
		const { output, status } = await this.core.setDesiredProfile(profile, qc, performance.now());
		this.applyCoreOutput(output);
		return status;
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
	 * Tell the core the low-water warning threshold, ml (`null` = the core's
	 * default, `0` = off). The Water settings call this on every change; the
	 * core re-arms the warning.
	 */
	async applyWaterWarnMl(ml: number | null): Promise<void> {
		await this.core.setWaterWarnMl(ml ?? undefined);
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
		await this.refreshStopTargets();
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
		await this.refreshStopTargets();
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
	 * Write the fan-on temperature threshold (°C, clamped 0..=50 by the
	 * core) to the DE1. Re-seeded on every connect — the machine boots
	 * with a low firmware default, so without the seed the fan runs
	 * near-constantly (geota/crema#31).
	 */
	async applyFanThreshold(celsius: number): Promise<void> {
		this.applyCoreOutput(await this.core.setFanThreshold(Math.round(celsius)));
	}

	/**
	 * Toggle the DE1 firmware's two-tap steam stop: first stop tap ends
	 * steam without the wand auto-purge; a second tap purges
	 * (geota/crema#34).
	 */
	async applySteamTwoTapStop(enabled: boolean): Promise<void> {
		this.applyCoreOutput(await this.core.setSteamTwoTapStop(enabled ? 1 : 0));
	}

	/**
	 * Write the machine's OWN refill threshold (raw sensor mm) — the
	 * `WaterLevels` `StartFillLevel`. Below it the DE1 blinks for water and
	 * refuses to pour regardless of what the app shows, so this is the
	 * setting that decides when the machine itself asks for a refill
	 * (geota/crema#47). Re-seeded on every connect, like de1app's
	 * `de1_send_waterlevel_settings` and Decenza's `water/refillPoint`.
	 */
	async applyRefillPoint(mm: number): Promise<void> {
		this.applyCoreOutput(await this.core.setRefillThreshold(mm));
	}

	/**
	 * Push the global max-shot-duration safety guardrail into the core,
	 * in seconds. `0` (or any non-positive value) clears the limit.
	 */
	async applyMaxShotDuration(seconds: number): Promise<void> {
		await this.core.setMaxShotDuration(seconds > 0 ? seconds : undefined);
		await this.refreshStopTargets();
	}

	/**
	 * Push the active profile's recipe target weight (yield, grams) into
	 * the core. Called whenever the active profile changes or its yield
	 * value is edited. `0` clears the target (SAW becomes a no-op for the
	 * profile recipe — the per-shot dial may still override).
	 */
	/**
	 * Re-read the core's stop-target projection into the snapshot.
	 *
	 * The core pushes `StopTargetsArmed` on every change, but only from the
	 * scale-reading and telemetry paths — with nothing connected there is no
	 * beat to ride. Called wherever the shell itself changes an input, which
	 * is the one moment it knows the picture moved (geota/crema#56).
	 */
	async refreshStopTargets(): Promise<void> {
		await refreshStopTargetsProjection(this.core);
	}

	async applyProfileTargetWeight(grams: number): Promise<void> {
		await this.core.setProfileTargetWeight(grams > 0 ? grams : undefined);
		await this.refreshStopTargets();
	}

	/**
	 * Push the active profile's volume limit (SAV, millilitres) into the
	 * core. Called whenever the active profile changes or its
	 * `maxTotalVolumeMl` is edited. `0` clears the limit.
	 */
	async applyProfileVolumeLimit(milliliters: number): Promise<void> {
		await this.core.setProfileVolumeLimit(milliliters > 0 ? milliliters : undefined);
		await this.refreshStopTargets();
	}

	/**
	 * Push the per-shot dial-override target weight (grams) into the
	 * core. Called by the QC yield stepper. `0` clears the override and
	 * lets the profile recipe target take effect.
	 */
	async applyShotTargetWeight(grams: number): Promise<void> {
		await this.core.setShotTargetWeight(grams > 0 ? grams : undefined);
		await this.refreshStopTargets();
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
	 * Write the four QC-modeled steam / hot-water fields (issue 14). The core
	 * owns the read-modify-write of the combined cuuid_0B packet: the group
	 * target is the effective profile's temperature, and the fields the Quick
	 * Sheet doesn't model (flags, timeouts, espresso volume) keep their
	 * current values.
	 */
	async setSteamHotwater(values: {
		steamTempC: number;
		steamTimeoutS: number;
		hotWaterTempC: number;
		hotWaterVolumeMl: number;
	}): Promise<void> {
		this.applyCoreOutput(await this.core.setSteamHotwaterTargets(values));
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
	): Promise<{ profile: import('#lib/core/index.js').Profile | null; error: string | null }> {
		let content: string;
		try {
			content = await readImportText(file);
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
	async exportProfileAsV2Json(profile: import('#lib/core/index.js').Profile): Promise<string> {
		return this.core.exportV2JsonProfile(profile);
	}

	/**
	 * Parse one community-v2 profile JSON document (a single line of a
	 * `.jsonl` bundle, or a `.json` file's content). Throws on parse
	 * failure — callers handle per-line error reporting in bulk
	 * imports.
	 */
	async parseV2JsonProfile(content: string): Promise<import('#lib/core/index.js').Profile> {
		return this.core.importV2JsonProfile(content);
	}

	async importShotFile(
		file: File
	): Promise<{ record: ReturnType<ReturnType<typeof getHistoryStore>['addImported']>; error: string | null }> {
		let content: string;
		try {
			content = await readImportText(file);
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
	 * Make `profile` with the overrides `qc` the desired profile and wait until
	 * the DE1 holds it — the core's profile sync uploads it unless this
	 * connection already pushed it. Resolves `true` once the core reports it
	 * landed (`ProfileSyncHolds`, or at once when its status already says so),
	 * `false` on a failed upload (the core keeps retrying) or after 15 s.
	 * Several callers on the same profile share one wait.
	 */
	async syncActiveProfile(
		profile: CremaProfile,
		qc: ProfileFingerprintOverrides
	): Promise<boolean> {
		// The start's own push supersedes a dial push still settling.
		if (this.#overridesPush !== null) {
			clearTimeout(this.#overridesPush);
			this.#overridesPush = null;
		}
		const status = await this.setDesiredProfile(profile, qc);
		const alreadyLoaded = status.desired != null && status.desired === status.lastPushed;
		// No runtime ⇒ no Effect-routed services (and no Web Bluetooth ⇒ no DE1
		// to upload to): only the core's status answers.
		if (this.runtime === null || status.desired == null) return alreadyLoaded;
		const desired = status.desired;
		return runtimePromise(
			this.runtime,
			ProfileSync.pipe(
				Effect.flatMap((ps) =>
					ps.sync({ desired, alreadyLoaded, log: (line) => this.state.log(line) })
				)
			)
		);
	}

	/** Tare the connected scale. Routes the core's `WriteScale` to the scale. */
	async tareScale(): Promise<void> {
		this.applyCoreOutput(await this.core.tareScale());
	}

	// ---- Guided brew sessions (issue #10) ---------------------------------
	//
	// The scale-only session lane. Commands round-trip through the core's
	// `BrewSessionMonitor`; while a session is live a 250 ms tick drives
	// its countdowns (the core's `on_tick` is timestamp-based, so cadence
	// jitter is harmless). No DE1 write ever originates here.

	/** The session-countdown tick, live only while a session runs. */
	private brewTick: ReturnType<typeof setInterval> | null = null;

	private startBrewTick(): void {
		this.brewTick ??= setInterval(() => {
			const now = performance.now();
			void this.core.onTick(now).then((out) => this.applyCoreOutput(out));
			// Feed the live session chart (display-only; the core records
			// the series that gets saved).
			const snap = this.state.current;
			getGuidedBrewStore().sample(now, snap.scaleWeight, snap.scaleFlow);
		}, 250);
	}

	private stopBrewTick(): void {
		if (this.brewTick !== null) {
			clearInterval(this.brewTick);
			this.brewTick = null;
		}
	}

	/** Arm a session for `recipe`; `startOnPour` starts it at first pour. */
	async brewSessionArm(recipe: BrewRecipe, startOnPour: boolean): Promise<void> {
		this.applyCoreOutput(
			await this.core.brewSessionArm(JSON.stringify(recipe), startOnPour)
		);
		getGuidedBrewStore().armed(recipe, startOnPour);
		this.startBrewTick();
	}

	/** Start an armed session's clock (the Start tap). */
	async brewSessionBegin(): Promise<void> {
		this.applyCoreOutput(await this.core.brewSessionBegin(performance.now()));
	}

	async brewSessionPause(): Promise<void> {
		const now = performance.now();
		this.applyCoreOutput(await this.core.brewSessionPause(now));
		getGuidedBrewStore().paused(now);
	}

	async brewSessionResume(): Promise<void> {
		const now = performance.now();
		this.applyCoreOutput(await this.core.brewSessionResume(now));
		getGuidedBrewStore().resumed(now);
	}

	async brewSessionSkip(): Promise<void> {
		this.applyCoreOutput(await this.core.brewSessionSkip(performance.now()));
	}

	async brewSessionFinish(): Promise<void> {
		this.applyCoreOutput(await this.core.brewSessionFinish(performance.now()));
	}

	/** Drop the session without a summary (backing out of setup). */
	async brewSessionCancel(): Promise<void> {
		this.applyCoreOutput(await this.core.brewSessionCancel());
		getGuidedBrewStore().reset();
		this.stopBrewTick();
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
		sensor: import('#lib/core/index.js').CalTarget,
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
		sensor: import('#lib/core/index.js').CalTarget,
		reported: number,
		measured: number
	): Promise<void> {
		const out = await this.core.writeCalibration(sensor, reported, measured);
		this.applyCoreOutput(out);
		// The core's guard (Sleep / Idle only, finite in-band values) refused
		// it: nothing was written, so say why instead of re-reading.
		const refusal = calibrationRefusalIn(out);
		if (refusal !== null) throw new CalibrationRefusedError(refusal);
		await this.readCalibration(sensor, false);
	}

	/**
	 * Reset a sensor to its factory calibration. After the reset, refreshes
	 * both the in-use and factory values so the UI reflects what the DE1
	 * now applies (and a stale factory readout from a prior session is
	 * brought up to date).
	 */
	async resetCalibrationToFactory(sensor: import('#lib/core/index.js').CalTarget): Promise<void> {
		const out = await this.core.resetCalibrationToFactory(sensor);
		this.applyCoreOutput(out);
		const refusal = calibrationRefusalIn(out);
		if (refusal !== null) throw new CalibrationRefusedError(refusal);
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
	async readMmr(register: import('#lib/core/index.js').MmrRegister): Promise<void> {
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
		// the start wins: the purge checks for a pending start when it fires.
		// `rinsePending` only keeps two purges from being scheduled at once.
		if (this.rinsePending) return;
		this.rinsePending = true;
		window.setTimeout(() => {
			try {
				// Only fire from Idle — avoid stepping on a manual user action
				// or a shot start that began during the defer.
				if (this.state.current.machineStateName !== MachineState.Idle) return;
				if (this.startSequence.active) return;
				void this.writeMachineState(MachineState.HotWaterRinse);
			} finally {
				this.rinsePending = false;
			}
		}, 1500);
	}

	/** A post-steam auto-purge is scheduled (one at a time). A shot start
	 *  is kept apart by `startSequence.active`, checked when the purge fires. */
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
	 * request goes out). A manual on-machine start (no Crema tap) finds it
	 * `null` (cleared at `ShotCompleted` and on a failed start) and records
	 * the dial the core was given instead.
	 */
	setBrewParamsSnapshot(snapshot: {
		yieldTarget: number;
		brewTemp: number;
		preinfuseTarget: number;
		stopOnWeight: boolean;
		autoTare: boolean;
		grind?: number | null;
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
	 *  2. **Profile sync.** The active profile + the caller's per-shot QC
	 *     overrides become the core's desired profile; the core uploads it
	 *     unless this connection already pushed it, and the start waits for
	 *     `ProfileSyncHolds` before going further. The upload's
	 *     "Uploading… N/N" pip in the dash header is the user-visible sync
	 *     indicator during the 1-2 s upload window.
	 *  3. **Pre-shot flush (optional)** + **Espresso state-request**.
	 *     Mirrors the prior behaviour: when `groupFlushBeforeShot` is
	 *     on, fire `HotWaterRinse` and wait for its
	 *     `WaterSessionCompleted(Flush)` (with a 30 s ceiling) before
	 *     requesting Espresso.
	 *
	 * The whole chain is one owned start (05 F2, {@link StartSequence}): a
	 * second call while it runs, or while a shot runs, is ignored, and
	 * {@link stopShot} (or any other machine request) cancels it, so a Stop
	 * during the pre-flush never ends in Espresso. The start stays pending
	 * until the machine reports Espresso (`ui.shotStarting`).
	 *
	 * @param qc Per-shot Quick Controls overrides — the caller passes the
	 *   live `BrewParamState.current` fields so the fingerprint reflects
	 *   the user's *current* intent, not just the profile's own defaults.
	 *   Defaults to no overrides for callers that don't have a brew sheet
	 *   (e.g. headless scripts) — equivalent to running the profile bare.
	 */
	async startShot(qc: ProfileFingerprintOverrides = {}): Promise<void> {
		// Claimed before the first await, so a second tap is refused at once.
		const start = this.startSequence.begin();
		if (start === null) {
			this.state.log(
				this.startSequence.active
					? 'A shot is already starting — Start ignored.'
					: 'A shot is already running — Start ignored.'
			);
			return;
		}
		try {
			await this.runStart(start, qc);
		} catch (e) {
			// A start that failed brews nothing: its dial snapshot must not be
			// recorded onto a later group-head shot.
			this.brewParamsAtShotStart = null;
			throw e;
		} finally {
			this.startSequence.end(start);
		}
	}

	/** The stages of {@link startShot} for one claimed `start`. Each await
	 *  can see a cancel; nothing is requested after one. */
	private async runStart(start: StartToken, qc: ProfileFingerprintOverrides): Promise<void> {
		const live = () => this.startSequence.isLive(start);
		const profiles = getProfileStore();
		await profiles.ensureLoaded();
		// Stopped before the library loaded: no toasts, upload or errors for it.
		if (!live()) return;
		const id = profiles.activeId;
		if (id === null) {
			throw new NoActiveProfileError();
		}
		const profile = profiles.get(id);
		if (!profile) {
			throw new NoActiveProfileError();
		}
		// Pre-shot scale gate (issue #29). Only when this shot actually
		// resolves a weight target (QC dial override, else the profile's own
		// yield — the same precedence SAW uses): a profile with no target
		// never needed a scale, so it gets no noise (the Decenza/reaprime
		// "profile uses weight" gate).
		const weightTarget = qc.yield ?? profile.yieldOut;
		if (
			getSettingsStore().current.stopOnWeight &&
			weightTarget > 0 &&
			this.state.current.scaleState !== 'ready'
		) {
			// The toast's action button is a user gesture, so Web Bluetooth
			// allows the chooser straight from it; the #15 fix arms SAW
			// mid-shot, so a quick connect can still save this very shot.
			if (getSettingsStore().current.requireScale) {
				// Opt-in hard block (de1app `start_espresso_only_if_scale_connected`
				// / reaprime `blockOnNoScale` — both also default off). The DE1
				// is left untouched.
				toast.action('Connect the scale to start the shot', 'Connect', () =>
					void this.connectScale()
				);
				throw new ScaleRequiredError();
			}
			// Default: NON-blocking cue — a modal between the user and a
			// tamped puck is workflow poison.
			toast.action(
				'No scale connected — stop-at-weight is off for this shot',
				'Connect',
				() => void this.connectScale()
			);
		}
		// A profile saved before the 20-step limit can't upload: say so, rather
		// than fail the sync and blame the DE1 (#6 #6).
		const overLimit = stepCapRefusal(profile.name, profile.segments.length);
		if (overLimit !== null) {
			throw new ProfileSyncFailedError(overLimit);
		}
		// The shot's profile (with the dial) becomes the desired one; the core
		// uploads it unless this connection already pushed it, and the start
		// waits until the DE1 holds it.
		const synced = await this.syncActiveProfile(profile, qc);
		if (!live()) return;
		if (!synced) {
			// `ProfileUploadFailed`'s fold already logged the cause; the
			// dashboard's machine-error / log banner will pick it up.
			throw new ProfileSyncFailedError(
				'The profile did not upload, so the shot was not started. ' +
					'Try Coffee again, or reload the profile from the Profiles page.'
			);
		}
		if (getSettingsStore().current.groupFlushBeforeShot) {
			// Pre-shot group flush: kick off the rinse, then wait for its
			// completion (or the 30 s ceiling) before requesting Espresso. A
			// just-scheduled auto-purge stands down while the start is pending.
			// Arm the waiter BEFORE the state request: the flush's Started
			// notification could otherwise beat it and the correlation marker
			// would be missed (review #34).
			start.flushArmed = true;
			await this.writeMachineState(MachineState.HotWaterRinse, live);
			const released = await start.waitFor(() => start.flushEnded, PRESHOT_FLUSH_TIMEOUT_MS);
			if (!live()) return;
			// The flush ended but the machine went somewhere else (a
			// group-head button mid-flush): that is not ours to override.
			const state = this.state.current.machineStateName;
			if (released && state !== MachineState.Idle) {
				this.state.log(`Pre-shot flush ended in ${state ?? 'an unknown state'} — shot not started.`);
				toast.error('Shot not started — the machine left the flush');
				return;
			}
		}
		if (!live()) return;
		start.espressoRequested = true;
		await this.writeMachineState(MachineState.Espresso, live);
		// Stay pending until the machine reports Espresso, so a Start in the
		// gap is refused rather than starting a second chain.
		if (this.state.current.machineStateName === MachineState.Espresso) return;
		const entered = await start.waitFor(() => start.espressoEntered, ESPRESSO_ENTRY_TIMEOUT_MS);
		if (!entered && live()) {
			// A machine that only starts from the group head (GHC required)
			// ignores the app's request.
			this.state.log(
				`The machine did not enter Espresso within ${ESPRESSO_ENTRY_TIMEOUT_MS / 1000} s of the request.`
			);
			toast.info(NOT_STARTED_NOTICE, 7000);
		}
	}

	/** Stop a running shot — requests `Idle`, which the DE1 honours from any
	 *  session state. Also cancels a shot start still pending (05 F2). */
	async stopShot(): Promise<void> {
		await this.requestMachineState(MachineState.Idle);
	}

	/**
	 * The one shot start in flight (05 F2). Its writes go through
	 * {@link writeMachineState}, so they don't cancel it; every user request
	 * through {@link requestMachineState} does.
	 */
	private readonly startSequence = new StartSequence(
		() => this.state.current.machineStateName,
		(shotStarting) => this.state.patch({ shotStarting }),
		(line) => this.state.log(line)
	);

	/** Drop the pending shot start, if any. */
	private cancelStart(why: string): void {
		if (this.startSequence.cancel() !== null) this.state.log(`Shot start cancelled (${why}).`);
	}

	/**
	 * Ask the DE1 to enter a machine state — most usefully Sleep or Idle.
	 * One byte gets written to the RequestedState characteristic
	 * (cuuid_02). Idle also stops a running shot and wakes the machine
	 * from sleep. Other requestable states (Espresso, Steam, HotWater,
	 * Flush, Descale, Clean) are normally triggered by on-machine touch
	 * buttons; the shell exposes them for completeness.
	 */
	async requestMachineState(state: MachineState): Promise<void> {
		// A user's request (a control, the power button, a maintenance run)
		// cancels a pending shot start first (05 F2): a Stop, or another
		// mode, during the sync or the pre-flush must not end in Espresso.
		this.cancelStart(state);
		await this.writeMachineState(state);
	}

	/** The write behind {@link requestMachineState}, also used by the shot
	 *  start's own steps and the auto-purge, which must not cancel it. A
	 *  `wanted` check that fails after the guard drops the write (a Stop
	 *  during the guard cancelled the start). */
	private async writeMachineState(
		state: MachineState,
		wanted: () => boolean = () => true
	): Promise<void> {
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
		if (!wanted()) return;
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
	 * `#lib/services/replay`'s `replayCaptureProgram`); `opts.speed` scales the
	 * pace. Progress and outcome are exposed on `state.current.replay`;
	 * {@link cancelReplay} stops it.
	 *
	 * Only one replay runs at a time — a second call while one is in progress is
	 * ignored. The replay does not interact with any live BLE connection: it
	 * resets the core, so a connected device's session would be discarded — it
	 * is strictly an offline diagnostic.
	 */
	async replayCapture(file: File, opts: ReplayCaptureOptions = {}): Promise<void> {
		// A replay swaps the live core out; a firmware flash must not lose its
		// notifications and link events to it (audit L3).
		if (this.firmware.status !== null) {
			toast.error('Finish or close the firmware update before replaying a capture.');
			return;
		}
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
	// needed. See `#lib/state/{brew-context,machine-readout,history-context}.svelte`.
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
	// The low-water threshold drives the core's warning (`WaterLow`).
	void core.setWaterWarnMl(getSettingsStore().current.waterWarnMl ?? undefined);
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
	// persisted store; saved back after every completed shot. A corrupt
	// blob is quarantined, not lost (see `saw-model-store.ts`).
	void seedSawModel(core).catch(() => undefined);
	// The idle-sleep timer ("Sleep after", `idle-sleep.ts`): the core owns
	// the decision; the shell feeds it the setting, DOM activity (pointer,
	// keyboard, the tab turning visible), the link state and a 30 s tick
	// whose expiry is computed from wall-clock timestamps, so a throttled
	// hidden-tab timer still fires on its next run. Replaces the old
	// per-touch UserPresent heartbeat: presence is now poked by the core on
	// activity only, throttled.
	app.idleSleep.configure(getSettingsStore().current.idleSleepMin);
	app.idleSleep.start();
	if (typeof document !== 'undefined') app.idleSleep.attach(document);
	// The active profile is the core's desired profile: set at boot (once the
	// library has loaded) and on every activation (`ProfileStore.setActive`
	// from BrewDashboard, ProfileEditor, ShotDetail, the profiles page, a
	// delete that re-points), with no overrides — a profile change clears
	// them. The Brew dashboard adds the live overrides; edits of the active
	// profile set it again. The core's profile sync decides when to upload
	// (every connect, a change, retries; never while busy) — so activating
	// while disconnected simply goes out on the next connect.
	{
		const profiles = getProfileStore();
		// Profile-deletion cascade: clear bean linked-profile references so
		// activation never chases a tombstone (registered here, not imported
		// in the store — $lib/bean/activate imports $lib/profiles).
		profiles.onDeleted = (id) => {
			getBeanLibraryStore().clearLinksTo(id);
		};
		profiles.onActivate = (profile) => {
			void app.setDesiredProfile(profile);
		};
		void profiles.ensureLoaded().then(() => {
			const id = profiles.activeId;
			return app.setDesiredProfile(id === null ? null : (profiles.get(id) ?? null));
		});
	}
	// Reconnect the last DE1 / scale where the browser can (client-only: this
	// factory runs from the layout's onMount, never during SSR / prerender).
	// On iPhone / iPad the Beacio bridge goes in first, so it can see the
	// remembered devices; an extension that shows up later re-runs it.
	let stopAvailability = (): void => undefined;
	const onBluetoothReady = (bridged: boolean): Promise<void> => {
		if (bridged) app.state.log('Bluetooth: using the Beacio Safari extension');
		// Bluetooth switched off / back on: log it, and on "on" retry a
		// device that is reconnecting or was never reached at launch.
		stopAvailability();
		let known: boolean | null = null;
		stopAvailability = watchBluetoothAvailability((on) => {
			// The first answer is only news when it's "off".
			if (known !== null || !on) {
				app.state.log(on ? 'Bluetooth is available again' : 'Bluetooth is off or unavailable');
			}
			if (on && known === false) app.kickReconnect('bt-on');
			known = on;
		});
		return app.reconnectLastOnLaunch();
	};
	void installIosBridge({
		onLateInstall: () => {
			void onBluetoothReady(true).catch((e: unknown) => {
				app.state.log(`Reconnect on launch failed: ${describeError(e)}`);
			});
		}
	})
		.then(onBluetoothReady)
		.catch((e: unknown) => {
			app.state.log(`Reconnect on launch failed: ${describeError(e)}`);
		});
	return app;
}
