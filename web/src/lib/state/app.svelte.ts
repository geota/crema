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

import { loadCore, type Command, type CoreOutput, type CremaCore } from '$lib/core';
import { De1Manager, EMPTY_DE1_DIAGNOSTICS, ScaleManager } from '$lib/ble';
import { getBeanStore } from '$lib/bean';
import { getHistoryStore } from '$lib/history';
import { parseCaptureFile, replayEvents, ReplayAbortedError } from '$lib/replay';
import { CremaUiState, DEFAULT_SCALE_STANDBY_MINUTES, DEFAULT_SCALE_VOLUME } from './ui-state.svelte';

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
			if (event.type === 'ShotCompleted') {
				const snapshot = this.state.current;
				// Stamp a snapshot of the current bean onto the record, so a later
				// bean change cannot rewrite history. An unlogged bean (no roaster
				// and no type) stores `null` — the History UI treats it as optional.
				const bean = getBeanStore().current;
				const roaster = bean.roaster.trim();
				const type = bean.type.trim();
				getHistoryStore().record({
					durationMs: event.content.duration_ms,
					profileName: snapshot.activeProfileName,
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
			}
		}
		for (const command of output.commands) {
			void this.executeCommand(command);
		}
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
		this.state.patch({
			machineState: null,
			shotPhase: null,
			telemetry: null,
			latestTelemetry: null,
			shotTelemetry: [],
			shotInProgress: false,
			shotElapsedMs: 0,
			de1Diagnostics: EMPTY_DE1_DIAGNOSTICS
		});
		await this.de1.connect();
	}

	/** Disconnect the DE1 and clear its readout fields. */
	async disconnectDe1(): Promise<void> {
		await this.de1.disconnect();
		this.state.patch({
			machineState: null,
			shotPhase: null,
			telemetry: null,
			latestTelemetry: null,
			shotTelemetry: [],
			shotInProgress: false,
			shotElapsedMs: 0,
			de1Diagnostics: EMPTY_DE1_DIAGNOSTICS
		});
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
			scaleWeightG: null,
			scaleFlowGPerS: null,
			scaleTimerMs: null,
			scaleCapabilities: null,
			scaleVolume: DEFAULT_SCALE_VOLUME,
			scaleStandbyMinutes: DEFAULT_SCALE_STANDBY_MINUTES,
			scaleFlowSmoothing: false,
			scaleAutoStop: null,
			scaleAntiMistouch: false,
			scaleActiveMode: null,
			scaleBatteryPercent: null,
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
		this.state.patch({
			machineState: null,
			shotPhase: null,
			telemetry: null,
			latestTelemetry: null,
			shotTelemetry: [],
			shotInProgress: false,
			shotElapsedMs: 0,
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

/** Best-effort human-readable text for an unknown thrown value. */
function describeError(error: unknown): string {
	return error instanceof Error ? error.message : String(error);
}

/**
 * Load the wasm core and construct the orchestrator. Call once at app start,
 * e.g. in the screen's `onMount`.
 */
export async function createCremaApp(): Promise<CremaApp> {
	const core = await loadCore();
	return new CremaApp(core);
}
