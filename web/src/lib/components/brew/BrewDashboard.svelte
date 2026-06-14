<script lang="ts">
	import { untrack } from 'svelte';
	import Icon from '$lib/icons/Icon.svelte';
	import ArrowsClockwiseIcon from 'phosphor-svelte/lib/ArrowsClockwiseIcon';
	/**
	 * `BrewDashboard` — the Brew route's centrepiece, ported from the
	 * `variant === 'g'` path of `WebQDashV2` in `web-dashboard-v2.jsx`.
	 *
	 * Top→bottom: the profile header strip, the `320px 1fr` main grid (left:
	 * `ExtractionTimer` + Yield / Ratio target cards; right: a 4-up
	 * `ChannelReadout` grid above the `LiveChart`), and the docked Quick Sheet
	 * (variant G).
	 *
	 * ## What is wired to real data vs. UI-only
	 *
	 * The **display** side is wired to `lib/state`'s telemetry — the timer, the
	 * four readouts, the Yield / Ratio cards and the chart all read the live
	 * `UiSnapshot`. The **control** side (the Quick Sheet steppers, favorites
	 * selection and the Start / Stop button) is faithful UI backed by local
	 * component state — the core treats the DE1 as read-only in this step, so
	 * driving the machine is a separate net-new feature (see the `// TODO: wire
	 * to DE1 control` markers in `QuickSheet.svelte` and `brew-params`).
	 */
	import {
		waterTankMl,
		waterRefillSoon,
		NoActiveProfileError,
		ProfileSyncFailedError,
		type UiSnapshot
	} from '$lib/state';
	import { ShotPhase, MachineState, MmrRegister } from '$lib/core/crema-core';
	import ModeChip from './ModeChip.svelte';
	import ModeHeadStatus from './ModeHeadStatus.svelte';
	import MachineErrorBanner from './MachineErrorBanner.svelte';
	import {
		getSettingsStore,
		convertWeight,
		convertTemp,
		convertPressure,
		convertVolume,
		formatTemp,
		formatVolume,
		formatWeight
	} from '$lib/settings';
	import { getProfileStore, newProfileId, preinfuseSeconds, type CremaProfile } from '$lib/profiles';
	import { formatRatio } from '$lib/utils/ratio';
	import {
		getBeanStore,
		roasterMarkTone,
		daysOffRoast,
		roastBand,
		roastFreshness,
		type Bean,
		type Roaster, activateBean } from '$lib/bean';
	import { getMaintenanceStore } from '$lib/maintenance';
	import { getCremaAppContext } from '$lib/shell/app-context';
	import { getActiveShotStore, getBrewContext, getMachineReadout } from '$lib/state';
	import { BrewParamState, type BrewParamSeed } from './brew-params.svelte';
	import ExtractionTimer from './ExtractionTimer.svelte';
	import ChannelReadout from './ChannelReadout.svelte';
	import PhaseIndicatorCard from './PhaseIndicatorCard.svelte';
	import MaxStopConditionsCard, { type StopConditionRow } from './MaxStopConditionsCard.svelte';
	import BrewHeader from './BrewHeader.svelte';
	import { type PickerItem } from './HeaderPicker.svelte';
	import LiveChart from './LiveChart.svelte';
	import QuickSheet from './QuickSheet.svelte';
	import LastShotCard from './LastShotCard.svelte';
	import PowerButton from '$lib/components/PowerButton.svelte';
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { promptDialog } from '$lib/components/shared/confirm-dialog.svelte';

	let {
		ui
	}: {
		/** The live UI snapshot from the shared orchestrator. */
		ui: UiSnapshot;
	} = $props();

	// ── App context (real — orchestrator for write actions) ─────────────
	/** Live ref to the shared CremaApp; used for the mode-chip writes. */
	const appCtx = getCremaAppContext();
	/** The CremaApp orchestrator, or `null` while the core is loading. */
	const app = $derived(appCtx().app);

	// ── Profile library (real — the lib/profiles store) ──────────────────
	/** The shared profile library — the source of pinned favorites + active. */
	const profileStore = getProfileStore();
	const brew = getBrewContext();
	const machine = getMachineReadout();
	void profileStore.ensureLoaded();

	// ── Mode chips (Steam / Hot water / Flush) ───────────────────────────
	//
	// Derives a coarse `'idle' | 'steaming' | 'dispensing' | 'flushing'`
	// state from the live MachineState the DE1 reports. Espresso is NOT
	// represented here — the big Start button in the foot handles it; the
	// chip row is for service modes only.
	//
	// `ready` gates on de1State === 'ready' (matches the floating
	// PowerButton's threshold so the user only sees actionable controls).
	type ModeState = 'idle' | 'steaming' | 'dispensing' | 'flushing';
	const modeState = $derived.by<ModeState>(() => {
		switch (ui.machineStateName) {
			case MachineState.Steam:
				return 'steaming';
			case MachineState.HotWater:
				return 'dispensing';
			case MachineState.HotWaterRinse:
				return 'flushing';
			default:
				return 'idle';
		}
	});
	const modeReady = $derived(ui.de1State === 'ready');
	/** Tap handlers — write RequestedState; cancel returns to Idle. */
	function tapSteam(): void {
		void appCtx().app?.requestMachineState(MachineState.Steam);
	}
	function tapWater(): void {
		void appCtx().app?.requestMachineState(MachineState.HotWater);
	}
	function tapFlush(): void {
		void appCtx().app?.requestMachineState(MachineState.HotWaterRinse);
	}
	function cancelMode(): void {
		void appCtx().app?.requestMachineState(MachineState.Idle);
	}
	/** Header pill labels — keyed by active mode. */
	const headStatusName = $derived(
		modeState === 'steaming'
			? 'Steaming'
			: modeState === 'dispensing'
				? 'Hot water'
				: modeState === 'flushing'
					? 'Flushing'
					: ''
	);

	// ── Unit preferences (real — the lib/settings store) ─────────────────
	/** The shared app-preferences store — drives every readout's display unit. */
	const settings = getSettingsStore();
	/** The live preference bundle — reactive; a unit change re-renders readouts. */
	const prefs = $derived(settings.current);

	// ── Live mode telemetry — drives the head pill's progress bar
	//
	// Targets are hardcoded for now; they'll come from the per-mode
	// Settings sections once they land. Steam target = 8 s,
	// Flush target = 4 s. Hot water defaults to 30 s as a placeholder
	// time-budget until we wire `dispensedVolume` against a
	// settings-driven `hotWaterVolMl` target.
	/**
	 * Per-mode target ceilings: the steam / hot-water timeouts the DE1's
	 * firmware enforces (the cap, not the typical session length), and the
	 * legacy 4 s flush window. Steam + hot-water targets come from the
	 * `ShotSettings` snapshot the connect-time Read populates; fallbacks
	 * match the legacy de1app defaults so the chip has a sensible reading
	 * before the read returns.
	 *
	 * Uses `posOr` rather than `??` because a partial / pre-handshake
	 * `ShotSettings` payload can carry a literal `0` for these fields,
	 * which `??` happily passes through. A zero timeout is meaningless
	 * anyway — treat it as missing.
	 */
	const posOr = (v: number | undefined, fallback: number): number =>
		v != null && Number.isFinite(v) && v > 0 ? v : fallback;
	// `$derived.by(fn)` rather than `$derived(value)` so the closure runs
	// lazily — `params` is declared later in the file, and a non-lazy
	// expression would hit the temporal-dead-zone error.
	const MODE_TARGET_SEC = $derived.by<
		Record<'steaming' | 'dispensing' | 'flushing', number>
	>(() => ({
		// Precedence: live machine value (ShotSettings/MMR) > the user's
		// Quick Controls value (the QuickSheet steppers persist these
		// per-shot params via `params.current`) > hardcoded legacy default.
		// The QC value is the user's intent; the machine value is what
		// the firmware currently has loaded.
		steaming: posOr(ui.de1ShotSettings?.steamTimeout, posOr(params.current.steamTime, 90)),
		dispensing: posOr(ui.de1ShotSettings?.hotWaterTimeout, 30),
		flushing: posOr(params.current.flushTime, 4)
	}));
	/**
	 * Flush water target temperature, °C.
	 *
	 * Precedence: machine MMR `FlushTemp` (`0x00803844`, wire value
	 * `°C × 10`) > the user's Quick Controls value (`params.current
	 * .flushTemp` — Flush bucket's Temp option) > legacy 95 °C default.
	 * The MMR read happens at connect time; before it lands, QC is
	 * what's shown on the chip + active banner. `posOr` so a partial
	 * payload's 0 falls through.
	 */
	const flushTempC = $derived.by<number>(() => {
		const raw = machine.flushTempC;
		const fromMachine = raw != null && Number.isFinite(raw) ? raw / 10 : NaN;
		return posOr(fromMachine, posOr(params.current.flushTemp, 95));
	});

	/**
	 * The resting chip sub-labels — the *target* (set) values the firmware
	 * will hold during the session. Steam + hot-water targets come from
	 * `ShotSettings`; flush target temp comes from the FlushTemp MMR
	 * register above. Fallbacks (148 °C steam target, 92 °C / 250 ml hot
	 * water) match the legacy de1app defaults so the chips paint
	 * sensibly before the connect-time reads return.
	 *
	 * The *active* banner (`headStatusMeta` below) uses the *measured*
	 * live values instead — so the chip says "what will happen" and the
	 * banner says "what's happening now."
	 */
	// Same lazy-closure pattern as MODE_TARGET_SEC above. Precedence on
	// each field: machine read > Quick Controls value (where it exists)
	// > hardcoded legacy default. Steam *temp* has no QC analogue
	// (Quick Sheet doesn't expose a steam-temp stepper); flush *temp*
	// likewise — both keep their machine-or-hardcoded fallback.
	const MODE_TARGET_LABEL = $derived.by<
		Record<'steaming' | 'dispensing' | 'flushing', string>
	>(() => ({
		steaming: `${formatTemp(posOr(ui.de1ShotSettings?.steamTemp, posOr(params.current.steamTemp, 148)), prefs.tempUnit)} · ${posOr(ui.de1ShotSettings?.steamTimeout, posOr(params.current.steamTime, 90))} s`,
		dispensing: `${formatTemp(posOr(ui.de1ShotSettings?.hotWaterTemp, posOr(params.current.waterTemp, 92)), prefs.tempUnit)} · ${formatVolume(posOr(ui.de1ShotSettings?.hotWaterVolume, posOr(params.current.waterVolume, 250)), prefs.volumeUnit)}`,
		flushing: `${formatTemp(flushTempC, prefs.tempUnit)} · ${posOr(params.current.flushTime, 4)} s`
	}));
	/**
	 * `performance.now()` when the DE1 transitioned into the current
	 * service mode — a pure `$derived` keyed off `modeState`. Recomputes
	 * (and re-anchors to the wall clock) exactly when `modeState`
	 * changes, so an idle → non-idle flip yields a fresh `t = 0` and a
	 * non-idle → non-idle flip (cancel-and-restart) re-anchors cleanly
	 * too. The associated `modeNowMs` is a tiny write-only effect that
	 * ticks every 250 ms while a mode is active, giving the progress
	 * bar a smooth fill without coupling to the BLE telemetry cadence.
	 */
	const modeStartedAtMs = $derived<number | null>(
		modeState === 'idle' ? null : performance.now()
	);
	let modeNowMs = $state(0);
	// Tick: pure write — seeds `modeNowMs` to the current wall clock on
	// transition (so the first frame reads elapsed ≈ 0) and ticks it every
	// 250 ms while a mode is active. No own-state read.
	$effect(() => {
		if (modeState === 'idle') return;
		modeNowMs = performance.now();
		const id = window.setInterval(() => {
			modeNowMs = performance.now();
		}, 250);
		return () => window.clearInterval(id);
	});
	/** Seconds since the active mode began (0 when idle). */
	const modeElapsedSec = $derived(
		modeStartedAtMs === null ? 0 : Math.max(0, (modeNowMs - modeStartedAtMs) / 1000)
	);
	/** Target seconds for the active mode — 0 when idle. */
	const modeTargetSec = $derived(
		modeState === 'idle' ? 0 : MODE_TARGET_SEC[modeState]
	);
	/** Progress percentage 0-100 for the head pill's inline bar. */
	const modeProgressPct = $derived(
		modeTargetSec > 0 ? Math.min(100, (modeElapsedSec / modeTargetSec) * 100) : 0
	);
	/**
	 * Meta line in the head pill. While running, formats `elapsed / total`
	 * seconds with the live measured temperature where it's meaningful
	 * (steam → steam heater temp; hot water → head temp; flush → head
	 * temp, since the firmware holds head_temp to FlushTemp during a
	 * rinse cycle). The resting chip sub-labels carry the *target*
	 * temperature; this active banner carries the *measured* one.
	 */
	const headStatusMeta = $derived.by(() => {
		if (modeState === 'idle') return '';
		const total = modeTargetSec.toFixed(1);
		const elapsed = modeElapsedSec.toFixed(1);
		if (modeState === 'steaming') {
			const steamTemp = ui.latestTelemetry?.steamTemp;
			const tempLabel =
				steamTemp != null ? ` · ${formatTemp(steamTemp, prefs.tempUnit)}` : '';
			return `${elapsed} / ${total} s${tempLabel}`;
		}
		if (modeState === 'dispensing') {
			const headTemp = ui.latestTelemetry?.temp;
			const tempLabel =
				headTemp != null ? ` · ${formatTemp(headTemp, prefs.tempUnit)}` : '';
			return `${elapsed} / ${total} s${tempLabel}`;
		}
		if (modeState === 'flushing') {
			const headTemp = ui.latestTelemetry?.temp;
			const tempLabel =
				headTemp != null ? ` · ${formatTemp(headTemp, prefs.tempUnit)}` : '';
			return `${elapsed} / ${total} s${tempLabel}`;
		}
		return `${elapsed} / ${total} s`;
	});
	/**
	 * Per-chip sub line — the resting target when idle, a live
	 * `elapsed / total s` counter while the chip's mode is the one
	 * running.
	 */
	const steamChipSub = $derived(
		modeState === 'steaming'
			? `${modeElapsedSec.toFixed(1)} / ${modeTargetSec.toFixed(1)} s`
			: MODE_TARGET_LABEL.steaming
	);
	const waterChipSub = $derived(
		modeState === 'dispensing'
			? `${modeElapsedSec.toFixed(1)} / ${modeTargetSec.toFixed(1)} s`
			: MODE_TARGET_LABEL.dispensing
	);
	const flushChipSub = $derived(
		modeState === 'flushing'
			? `${modeElapsedSec.toFixed(1)} / ${modeTargetSec.toFixed(1)} s`
			: MODE_TARGET_LABEL.flushing
	);

	/** The real pinned profiles, shown as favorite chips in the Quick Sheet. */
	const pinnedProfiles = $derived(profileStore.all.filter((p) => p.pinned));

	/**
	 * The profile the brew page should display — routed through
	 * {@link BrewContext.activeProfile}, so the in-flight shot's
	 * profileName (live or replay META) resolves through the library
	 * before falling back to the user's currently-active selection. Same
	 * shape as `profileStore.get(...)` — components downstream don't need
	 * to know about the in-flight branch.
	 */
	const activeProfile = $derived(brew.activeProfile);

	// ── Bean library — drives the bean half of the unified favourites strip ─
	/** The shared bean library — pinned beans + roasters + active pointer. */
	const beanLibrary = getBeanStore();
	/**
	 * The pinned beans shown alongside profiles in the Quick Sheet's unified
	 * favourites strip. "Pinned" = favourited and not archived; an archived
	 * bag is a finished one, which should not appear in the brew picker.
	 */
	const pinnedBeans = $derived(
		beanLibrary.beans.filter((b) => b.favourite && b.archivedAt == null)
	);
	/**
	 * Set the active bean from the strip — same idiom as `selectFavorite` for
	 * profiles, so picking a bag from the QC strip activates it for the brew.
	 */
	function selectBean(bean: Bean): void {
		activateBean(bean.id, { shotInProgress: ui.shotInProgress });
	}

	// ── Quick Sheet local control state ──────────────────────────────────
	/**
	 * The brew-target seed for the Quick Sheet params — a pure `$derived`. When
	 * a profile is active its dose / yield / brew temp / pre-infusion win; with
	 * no active profile the Settings brew defaults seed it instead (D2 — the
	 * yield is `dose × ratio`, the `defaultRatio` being the `x` in `1:x`).
	 *
	 * This is the seed `BrewParamState` mirrors: a stepper edit reassigns the
	 * param `$derived` away from this seed, and a genuine seed change (a
	 * different profile, an edited Settings default) re-seeds it — no sentinel,
	 * no state-syncing `$effect`.
	 */
	const paramSeed = $derived.by<BrewParamSeed>(() => {
		if (activeProfile) {
			return {
				dose: activeProfile.dose,
				yield: activeProfile.yieldOut,
				brewTemp: activeProfile.brewTemp,
				preinf: preinfuseSeconds(activeProfile.segments)
			};
		}
		return {
			dose: prefs.defaultDoseG,
			yield: prefs.defaultDoseG * prefs.defaultRatio,
			brewTemp: prefs.defaultBrewTempC,
			preinf: prefs.defaultPreinfusionS
		};
	});
	/**
	 * The Quick Sheet's parameter model. Its dose / yield / temp / pre-infusion
	 * track {@link paramSeed} so the header, the ratio readout and the steppers
	 * all agree; the steppers may then edit it locally. The CONTROL side never
	 * reaches the machine in this porting step — see the `// TODO: wire to DE1
	 * control` notes.
	 */
	/**
	 * Save the current QC dial values as a new custom profile, cloned
	 * from the active profile. Prompts for a name; clamps blanks to a
	 * timestamped default. The new profile is persisted via
	 * ProfileStore and (per #83 wiring) activated + eagerly uploaded to
	 * the DE1 if connected.
	 */
	async function savePreset(): Promise<void> {
		const base = activeProfile;
		if (!base) return;
		const stamp = new Date().toISOString().slice(0, 16).replace('T', ' ');
		const suggested = `${base.name} — preset ${stamp}`;
		const name = (
			await promptDialog({
				title: 'Save preset',
				message: 'Name for the new preset:',
				initialValue: suggested,
				confirmLabel: 'Save'
			})
		)?.trim();
		if (!name) return;
		const live = params.current;
		const cloned: CremaProfile = {
			...base,
			source: 'custom',
			id: newProfileId(),
			name,
			pinned: false,
			lastUsed: null,
			// Apply the QC dial values that genuinely affect the recipe.
			// Steam / hot-water / flush fields on BrewParams are
			// session-only UI; they aren't part of the profile model.
			dose: live.dose,
			yieldOut: live.yield,
			brewTemp: live.brewTemp
		};
		profileStore.save(cloned);
		profileStore.setActive(cloned.id);
	}

	// Quick Sheet steam/hot-water key → its persisted Settings field (issue 14).
	// Only the four fields that share the cuuid_0B packet need the map; the rest
	// persist via literal keys in their own `case`.
	const QC_SETTING = {
		steamTime: 'qcSteamTimeS',
		steamTemp: 'qcSteamTempC',
		waterTemp: 'qcHotWaterTempC',
		waterVolume: 'qcHotWaterVolumeMl'
	} as const;

	const params = new BrewParamState(
		() => paramSeed,
		(key, value) => {
			if (typeof value !== 'number') return;
			switch (key) {
				// Yield → the core's per-shot dial override. The core composes
				// (dial > profile-recipe) at shot-start, so a QC bump on top of
				// an active profile takes effect on the very next shot — GHC
				// shots included.
				case 'yield':
					void app?.applyShotTargetWeight(value);
					break;
				// Steam temp/time + hot-water temp/vol share the one cuuid_0B
				// packet — persist + read-modify-write all four together (issue
				// 14), reading the just-updated `params.current`.
				case 'steamTime':
				case 'steamTemp':
				case 'waterTemp':
				case 'waterVolume': {
					settings.set(QC_SETTING[key as keyof typeof QC_SETTING], value);
					const c = params.current;
					void app?.setSteamHotwater({
						steamTempC: c.steamTemp,
						steamTimeoutS: c.steamTime,
						hotWaterTempC: c.waterTemp,
						hotWaterVolumeMl: c.waterVolume
					});
					break;
				}
				// Steam flow + flush temp/time are standalone MMR writes.
				case 'steamFlow':
					settings.set('qcSteamFlowMlS', value);
					void app?.setSteamFlow(value);
					break;
				case 'flushTime':
					settings.set('qcFlushTimeS', value);
					void app?.setFlushTimeoutS(value);
					break;
				case 'flushTemp':
					settings.set('qcFlushTempC', value);
					void app?.setFlushTemp(value);
					break;
			}
		},
		// Seed the steam / hot-water / flush params from the persisted Quick
		// Sheet values so they stick across reloads. Read `untrack`ed: this is a
		// one-time default source, not a live dependency — otherwise every QC
		// edit (which persists to `prefs`) would re-run `current` and wipe the
		// user's dose / yield / brew overrides back to the profile seed.
		() =>
			untrack(() => ({
				steamTime: prefs.qcSteamTimeS,
				steamFlow: prefs.qcSteamFlowMlS,
				steamTemp: prefs.qcSteamTempC,
				waterTemp: prefs.qcHotWaterTempC,
				waterVolume: prefs.qcHotWaterVolumeMl,
				flushTime: prefs.qcFlushTimeS,
				flushTemp: prefs.qcFlushTempC
			}))
	);
	/**
	 * Whether the Quick Sheet is docked open. Starts hidden — the dashboard is
	 * the primary view; the header's QuickPill opens the sheet, and its Close
	 * button or a scrim tap dismisses it again.
	 */
	let quickSheetOpen = $state(false);
	/**
	 * Whether a state-request transition we initiated is still in flight.
	 * Used to debounce double-clicks on the big Start button while a
	 * pre-shot flush + Espresso sequence is in progress, since the
	 * orchestrator's `startShot()` awaits the flush completion before
	 * issuing the espresso request.
	 */
	let stateTransitionPending = $state(false);
	/**
	 * Whether the DE1 is currently running an espresso shot — strict
	 * equality on the typed state name covers every Espresso substate
	 * (Heating, Stabilising, Preinfusion, Pouring, Ending). Honest,
	 * two-way: a shot the user kicks off via the on-machine touch button
	 * reads as running here too, and the dashboard's Stop button can end
	 * it.
	 */
	const running = $derived(ui.machineStateName === MachineState.Espresso);

	const p = $derived(params.current);

	// ── Header meta ──────────────────────────────────────────────────────
	/** Live yield-to-dose ratio for the header / target cards (`1:N`, one decimal). */
	const ratio = $derived(formatRatio(p.dose, p.yield));
	/**
	 * The header profile name. Prefers the DE1's *real* active profile —
	 * the name Crema most recently uploaded successfully
	 * (`ui.activeProfileName`, populated by the `ProfileUploadCompleted`
	 * event handler) — so the brew page always reflects what the machine
	 * actually has loaded. Falls back to the Profiles-page UI selection
	 * (`activeProfile.name`) for the brief window between a Load-on-Brew
	 * click and the upload completing, then to a neutral fallback for
	 * first-launch / no-profile-ever.
	 */
	// During an in-flight shot (live or replay), prefer the ActiveShot's
	// frozen profile name — it's the authoritative answer to "what is
	// the shot in progress brewing against?" For replay specifically
	// this is essential: the user's loaded profile is unrelated to the
	// replayed shot. See docs/49 §3. The full fallback chain
	// (in-flight → snapshot.activeProfileName → library → placeholder)
	// lives in `BrewContext.displayProfileName`.
	const activeShot = getActiveShotStore();
	const profileName = $derived(brew.displayProfileName);
	/**
	 * Compact summary of the profile the DE1 firmware actually has loaded
	 * — drawn from `loadedProfileShape` (the `ProfileHeaderRead` cached at
	 * connect time). The DE1's wire shape doesn't carry the profile
	 * title, so this is shape-only ("4 frames · 1 preinfuse · max 8 ml/s")
	 * and complements the `profileName` displayed above it. `null` before
	 * the first `HeaderRead` lands (pre-connect or pre-handshake), and
	 * the subline element hides itself in that case.
	 */
	const loadedShapeSubline = $derived.by<string | null>(() => {
		const s = ui.loadedProfileShape;
		if (!s) return null;
		const parts: string[] = [];
		parts.push(`${s.frameCount} frame${s.frameCount === 1 ? '' : 's'}`);
		if (s.preinfuseFrameCount > 0) parts.push(`${s.preinfuseFrameCount} preinfuse`);
		if (s.maximumFlow > 0)
			parts.push(`max ${s.maximumFlow.toFixed(1)} ml/s`);
		return `On DE1: ${parts.join(' · ')}`;
	});

	// ── Real telemetry (the DISPLAY side — wired to lib/state) ───────────
	// The timer, the four readouts and the foot all show LIVE machine data —
	// temperature in particular is the warmed-up signal, so it must never
	// freeze. The just-finished shot's result is shown separately by the
	// `LastShotCard` at the bottom of the left column.
	// ── Chart pinning ────────────────────────────────────────────────────
	/**
	 * Click-to-pin state. Elapsed-time (seconds) of a moment the user
	 * clicked on the live chart; `null` = live. While pinned, the 4 top
	 * cards swap from the latest telemetry to the values at this moment
	 * — useful for studying "what was the pressure when the flow peaked?"
	 * The footer stays live as a "what's happening now" reference.
	 *
	 * Cleared when a new shot starts (`shotInProgress` flips low→high) so
	 * a stale pin from the previous shot doesn't outlive its data.
	 */
	let pinnedTimeSec = $state<number | null>(null);
	// Plain `let` (not `$state`) so the rising-edge effect only tracks the prop.
	let lastShotInProgress = false;
	$effect(() => {
		if (ui.shotInProgress && !lastShotInProgress) {
			pinnedTimeSec = null;
		}
		lastShotInProgress = ui.shotInProgress;
	});
	/** Esc unpins. Document-level click outside the chart also unpins. */
	function onKeydown(e: KeyboardEvent): void {
		if (e.key === 'Escape' && pinnedTimeSec !== null) {
			pinnedTimeSec = null;
		}
	}

	/**
	 * Find the sample whose elapsed-time is nearest the pinned moment.
	 * Linear scan — the buffer is small (≤ 2000 samples capped). Returns
	 * `null` when there is no pin or no buffered series.
	 */
	const pinnedSample = $derived.by(() => {
		if (pinnedTimeSec === null) return null;
		const series = ui.shotTelemetry;
		if (series.length === 0) return null;
		const targetMs = pinnedTimeSec * 1000;
		let best = series[0];
		let bestD = Math.abs(best.elapsed - targetMs);
		for (let i = 1; i < series.length; i++) {
			const d = Math.abs(series[i].elapsed - targetMs);
			if (d < bestD) {
				bestD = d;
				best = series[i];
			}
		}
		return best;
	});

	/**
	 * The sample the channel cards read. When a pin is set, swap the live
	 * `latestTelemetry` for the nearest sample at the pinned time — every
	 * `convert*` reactive derives downstream picks this up automatically.
	 */
	const tel = $derived(pinnedSample ?? ui.latestTelemetry);
	/** Shot elapsed time, seconds — live; resets to 0 between shots. */
	const elapsedSec = $derived(ui.shotElapsed / 1000);
	/** The just-finished shot's summary, or null. */
	const lastShot = $derived(ui.completedShot);
	/** Show the Last-shot card once a shot has finished, until the next starts. */
	const showLastShot = $derived(lastShot !== null && !ui.shotInProgress);

	/** A human phase caption for the timer, from the core's shot phase. */
	const phaseLabel = $derived.by(() => {
		if (!ui.shotInProgress) return 'Ready';
		switch (ui.shotPhase) {
			case ShotPhase.Heating:
				return 'Heating';
			case ShotPhase.Preinfusion:
				return 'Pre-infusion';
			case ShotPhase.Pouring:
				return 'Extraction';
			case ShotPhase.Ending:
				return 'Ending';
			default:
				return 'Extraction';
		}
	});

	/** Format a channel value, or a dim dash when there is no reading. */
	const fmt = (v: number | null | undefined, digits = 1): string =>
		v == null ? '—' : v.toFixed(digits);

	/**
	 * Format a dispensed-water reading. Stored canonically in ml; rendered
	 * in ml or fl oz per the volume pref. One decimal so the digit density
	 * matches FLOW next to it on the card. `null`/non-finite → `'0.0'` so
	 * the caller can independently decide pre-telemetry → `'—'`.
	 */
	function formatDispensedMl(ml: number | null | undefined, unit: 'ml' | 'floz'): string {
		if (ml == null || !Number.isFinite(ml)) return '0.0';
		return (unit === 'floz' ? ml / 29.5735 : ml).toFixed(1);
	}

	// Unit-aware channel measurements — all driven by the Settings unit prefs
	// so a unit change in Settings re-renders every readout at once (D1).
	/** Pressure readout, in the chosen pressure unit. */
	const pressureM = $derived(convertPressure(tel?.pressure, prefs.pressureUnit));
	/** Group-head temperature readout, in the chosen temperature unit. */
	const tempM = $derived(convertTemp(tel?.temp, prefs.tempUnit));
	/** Mix ("group") temperature readout, in the chosen temperature unit. */
	const mixTempM = $derived(convertTemp(tel?.mixTemp, prefs.tempUnit));
	/** Steam-heater temperature readout, in the chosen temperature unit. */
	const steamTempM = $derived(convertTemp(tel?.steamTemp, prefs.tempUnit));

	/** Live weight (g) — from the scale stream, independent of shot state. */
	/**
	 * The final sample of the just-finished shot — used to freeze the
	 * shot-relevant readouts (weight, dispensed volume, weight flow) at
	 * their end-of-shot values between `ShotCompleted` and the next
	 * `ShotStarted`. Without this, the cards would keep tracking the
	 * scale's live weight stream — so lifting the cup off the scale
	 * after a shot read as a "weight went up" surprise.
	 *
	 * The `shotTelemetry` buffer is preserved through ShotCompleted
	 * (the LastShotCard reads its peaks); the last entry is exactly
	 * the final sample.
	 *
	 * Returns `null` when a shot is in progress (cards should be live)
	 * OR when there's no recorded shot yet (no buffer to read from).
	 * Temperature channels intentionally do NOT consult this — heater
	 * temp is the warmed-up signal and must stay live regardless.
	 */
	const finalShotSample = $derived.by(() => {
		if (ui.shotInProgress) return null;
		if (!lastShot) return null;
		const series = ui.shotTelemetry;
		return series.length > 0 ? series[series.length - 1] : null;
	});

	/**
	 * Scale weight readout. Three modes:
	 *  - pinned moment on the chart → buffered sample's weight
	 *  - between shots (post-`ShotCompleted`) → final shot weight (so
	 *    lifting the cup doesn't push the card around)
	 *  - otherwise → live `scaleWeight` stream
	 */
	const weight = $derived(
		pinnedSample
			? pinnedSample.weight
			: finalShotSample
				? finalShotSample.weight
				: ui.scaleWeight
	);
	/** Weight readout, in the chosen weight unit. */
	const weightM = $derived(convertWeight(weight, prefs.weightUnit));
	/**
	 * The weight feeding the Yield / Ratio cards: live during a shot, then held
	 * at the last shot's final yield once it completes — so those cards read as
	 * the shot's result rather than the drifting scale. The 4-up WEIGHT readout
	 * and the foot keep the live `weight`.
	 */
	const shotWeight = $derived(showLastShot && lastShot ? lastShot.yieldOut : weight);
	/** Yield / Ratio weight in the chosen weight unit. */
	const shotWeightM = $derived(convertWeight(shotWeight, prefs.weightUnit));
	/** Brew-temperature target, in the chosen temperature unit. */
	const brewTempTarget = $derived(convertTemp(p.brewTemp, prefs.tempUnit));
	/** Yield target, in the chosen weight unit. */
	const yieldTarget = $derived(convertWeight(p.yield, prefs.weightUnit));

	// ── Secondary-channel measurements (the right-side number on each
	//    ChannelReadout). Each value is converted to the user's unit; the
	//    line itself is plotted on the LiveChart only when the matching
	//    toggle (prefs.show*) is on, but the *number* on the card stays
	//    visible regardless so the user never loses the data.
	/**
	 * Puck resistance — raw value (no chart-scaling multiplier here).
	 *
	 * Pre-telemetry (no `tel` sample → pre-connect) shows `—`. Once
	 * telemetry is flowing but the value is `null` (flow below the
	 * core's resistance floor — between shots, near-zero flow), shows
	 * `0.00` to match the other "connected but quiet" channels rather
	 * than the eye-grabbing dash.
	 */
	// Auto-switch to the scale-derived resistance when a scale
	// estimate exists for this sample (`resistanceWeight ?? resistance`).
	// Same per-sample fallback the charts apply — a shot with no
	// scale paired reads the DE1-flow value, a paired shot reads
	// the truer extraction signal (what exits the puck).
	const resistanceVal = $derived.by<string>(() => {
		let r: number | null | undefined;
		if (pinnedSample) {
			r = pinnedSample.resistanceWeight ?? pinnedSample.resistance;
		} else if (finalShotSample) {
			r = finalShotSample.resistanceWeight ?? finalShotSample.resistance;
		} else if (tel === null) {
			return '—';
		} else {
			r = tel.resistanceWeight ?? tel.resistance;
		}
		return r != null && Number.isFinite(r) ? r.toFixed(2) : '0.00';
	});
	/**
	 * Which resistance source the readout is currently displaying — drives
	 * the unit label. Mirrors the `resistanceWeight ?? resistance` fallback
	 * above so the unit is always in sync with the number above it. Flow-
	 * based is `bar / (ml/s)² = bar·s²/ml²`; weight-based swaps `ml` → `g`
	 * since the scale flow is in g/s. Reflects per-sample, so a shot that
	 * starts unscaled and later pairs a scale auto-swaps mid-shot.
	 */
	const resistanceUnit = $derived.by<string>(() => {
		const sample = pinnedSample ?? finalShotSample ?? tel;
		const fromWeight = sample != null && sample.resistanceWeight != null;
		return fromWeight ? 'bar·s²/g²' : 'bar·s²/ml²';
	});
	/**
	 * Dispensed-water readout — the volume of water the pump has emitted,
	 * integrated from `group_flow × Δt`. Matches the legacy de1app's
	 * `water_dispensed` channel and the v2 export's `totals.water_dispensed`.
	 * **Not** the espresso volume in the cup — the puck absorbs ~2× the dose
	 * before the first drop falls, so dispensed-water > cup-volume always.
	 *
	 * Empty-state rule (matches the other primary readouts): pre-telemetry
	 * (no `tel` sample yet — pre-connect or pre-handshake) shows `—`; once
	 * the DE1 is streaming, shows the integrator value (0 between shots,
	 * climbing live, frozen at the final value via `finalShotSample`
	 * post-shot, pinned-sample value when the chart is pinned).
	 */
	const dispensedVolumeVal = $derived.by<string>(() => {
		if (pinnedSample) {
			const v = pinnedSample.dispensedVolume;
			return v != null && Number.isFinite(v)
				? formatDispensedMl(v, prefs.volumeUnit)
				: '0.0';
		}
		if (finalShotSample) {
			const v = finalShotSample.dispensedVolume;
			return v != null && Number.isFinite(v)
				? formatDispensedMl(v, prefs.volumeUnit)
				: '0.0';
		}
		if (tel === null) return '—';
		return formatDispensedMl(ui.dispensedVolume, prefs.volumeUnit);
	});
	const dispensedVolumeUnit = $derived(prefs.volumeUnit === 'floz' ? 'fl oz' : 'ml');
	/**
	 * Weight flow (g/s) — the scale's host-side mass-flow estimate. Same
	 * pre-/post-/pinned/live ladder as the other secondary readouts. Pre-
	 * telemetry: `—`; otherwise the current value (0 when no flow).
	 */
	const weightFlowVal = $derived.by<string>(() => {
		let v: number | null | undefined;
		if (pinnedSample) {
			v = pinnedSample.weightFlow;
		} else if (finalShotSample) {
			v = finalShotSample.weightFlow;
		} else if (tel === null) {
			return '—';
		} else {
			v = ui.scaleFlow;
		}
		return v != null && Number.isFinite(v) ? v.toFixed(1) : '0.0';
	});
	/** Whether a scale is connected — drives the foot's "Scale" cluster. */
	const scaleConnected = $derived(
		ui.scaleState === 'ready' || ui.scaleState === 'subscribing'
	);
	/** The connected scale's advertised name, with a neutral fallback. */
	const scaleName = $derived(ui.scaleName ?? 'Scale');
	/** Yield progress as a 0..100 % bar width. */
	const yieldPct = $derived(
		shotWeight == null ? 0 : Math.min(100, (shotWeight / p.yield) * 100)
	);

	/**
	 * Whether the per-shot weight target is engaged for the next / current
	 * shot. Reseeds on every active-profile change to mirror the profile's
	 * own intent: a profile with `yieldOut > 0` engages the target on
	 * load; a profile with `yieldOut === 0` (or none active) leaves it
	 * off. Reassigning via {@link onToggleYieldTarget} breaks the
	 * `$derived` track until the next reseed, so per-shot flips persist
	 * until the user activates a different profile.
	 */
	let yieldTargetOn = $derived.by(() => (activeProfile?.yieldOut ?? 0) > 0);
	/**
	 * Flip the yield-target dot for the current shot. Pushes the inverse
	 * to the core so SAW arming consults the same value next time
	 * `ShotEvent::Started` fires (Crema-tap or GHC-tap).
	 */
	function onToggleYieldTarget(): void {
		yieldTargetOn = !yieldTargetOn;
		void app?.applyWeightTargetDisabled(!yieldTargetOn);
	}

	/**
	 * Whether the Yield stop-condition row should render — gated on the active
	 * profile having a configured weight target (`yieldOut > 0`, the SAW source
	 * the core stops on) and a positive live target. Deliberately independent of
	 * the per-shot dot, so the row reflects the profile's configured stop even
	 * when the dot is toggled off for the current shot.
	 */
	const yieldCardVisible = $derived((activeProfile?.yieldOut ?? 0) > 0 && p.yield > 0);
	/** Whether to render the Max Volume target card. */
	const maxVolumeCardVisible = $derived((activeProfile?.maxTotalVolumeMl ?? 0) > 0);
	/** Volume progress as 0..100 % for the max-volume card's bar. */
	const maxVolumePct = $derived.by(() => {
		const cap = activeProfile?.maxTotalVolumeMl ?? 0;
		if (cap <= 0) return 0;
		return Math.min(100, ((ui.dispensedVolume ?? 0) / cap) * 100);
	});
	/** Whether to render the Max Duration target card. */
	const maxDurationCardVisible = $derived(prefs.maxShotDurationS > 0);
	/** Elapsed-time progress as 0..100 % for the max-duration card's bar. */
	const maxDurationPct = $derived.by(() => {
		const cap = prefs.maxShotDurationS;
		if (cap <= 0) return 0;
		return Math.min(100, (elapsedSec / cap) * 100);
	});

	/**
	 * Stop-condition rows for `MaxStopConditionsCard`, built from the SAME
	 * gates/values as the old inline `.crema-target-stack` — only the
	 * presentation changes, not what shows or when. Yield = weight (scales /
	 * --tel-weight), Volume = flow (drop-half / --tel-flow), Time = neutral.
	 */
	const stopConditionRows = $derived.by<StopConditionRow[]>(() => {
		const out: StopConditionRow[] = [];
		if (yieldCardVisible)
			out.push({
				key: 'yield',
				label: 'Yield',
				icon: 'scales',
				color: 'var(--tel-weight)',
				live: shotWeightM.value,
				target: yieldTarget.value,
				unit: yieldTarget.unit,
				pct: yieldPct
			});
		if (maxVolumeCardVisible)
			out.push({
				key: 'volume',
				label: 'Volume',
				icon: 'drop-half',
				color: 'var(--tel-flow)',
				live: (ui.dispensedVolume ?? 0).toFixed(0),
				target: String(activeProfile?.maxTotalVolumeMl ?? ''),
				unit: 'ml',
				pct: maxVolumePct
			});
		if (maxDurationCardVisible)
			out.push({
				key: 'time',
				label: 'Time',
				icon: 'timer',
				color: 'rgba(var(--tint-rgb), 0.6)',
				live: elapsedSec.toFixed(0),
				target: String(prefs.maxShotDurationS),
				unit: 's',
				pct: maxDurationPct
			});
		return out;
	});

	// ── BrewHeader wiring (presentational; reuses the profile + bean stores) ──
	/** Profile rows for the header's ▾ picker — all profiles, pinned first. */
	const profilePickerItems = $derived.by<PickerItem[]>(() =>
		profileStore.all.map((pr) => ({
			id: pr.id,
			primary: pr.name,
			secondary: pr.author || (pr.tags.length ? pr.tags.join(' · ') : undefined),
			pinned: pr.pinned,
			segments: pr.segments,
			search: `${pr.name} ${pr.author ?? ''} ${pr.tags.join(' ')}`.toLowerCase()
		}))
	);

	/** The active bean's roaster record (for the avatar mark/tone), or null. */
	/** Profile identity line — non-espresso beverage type, roast level, tags. */
	const profileIdentity = $derived.by(() => {
		const pr = activeProfile;
		if (!pr) return '';
		const parts: string[] = [];
		if (pr.beverageType) parts.push(pr.beverageType[0].toUpperCase() + pr.beverageType.slice(1));
		if (pr.roast) parts.push(pr.roast[0].toUpperCase() + pr.roast.slice(1));
		const author = pr.author?.trim();
		if (author) parts.push(author);
		return parts.join(' · ');
	});
	/** Profile tags — the faint third line. */
	const profileTags = $derived(
		activeProfile ? activeProfile.tags.filter((t) => t && t !== 'Built-in').join(' · ') : ''
	);
	const activeRoaster = $derived.by<Roaster | null>(() => {
		const b = beanLibrary.activeBean;
		if (!b?.roasterId) return null;
		return beanLibrary.roasters.find((r) => r.id === b.roasterId) ?? null;
	});
	const headerBeanName = $derived.by(() => {
		const b = beanLibrary.activeBean;
		if (!b) return 'No bean selected';
		const r = activeRoaster?.name?.trim() ?? '';
		const t = b.name.trim();
		return r && t ? `${r} · ${t}` : r || t || 'Untitled bean';
	});
	const headerBeanRoast = $derived.by(() => {
		const b = beanLibrary.activeBean;
		if (!b) return null;
		const band = roastBand(b.roastLevel);
		return band ? band[0].toUpperCase() + band.slice(1) : null;
	});
	const headerBeanDaysOff = $derived(
		beanLibrary.activeBean ? daysOffRoast(beanLibrary.activeBean.roastedOn) : null
	);
	const headerFreshLabel = $derived(
		headerBeanDaysOff != null ? `${headerBeanDaysOff}d off roast` : null
	);
	const headerFreshColor = $derived.by(() => {
		const b = beanLibrary.activeBean;
		const f = b ? roastFreshness(roastBand(b.roastLevel), headerBeanDaysOff) : null;
		return f === 'best'
			? 'var(--success)'
			: f === 'ok'
				? 'var(--warning)'
				: f === 'bad'
					? 'var(--danger)'
					: 'rgba(var(--tint-rgb), 0.4)';
	});
	const headerBeanMeta = $derived.by(() => {
		const b = beanLibrary.activeBean;
		if (!b) return 'Tap ▾ to choose a bag';
		const loc = [b.origin.country, b.origin.region]
			.filter((x): x is string => !!x && x.trim() !== '')
			.map((x) => x.trim())
			.join(', ');
		const parts: string[] = [];
		if (loc) parts.push(loc);
		const variety = b.origin.variety?.trim();
		if (variety) parts.push(variety);
		const proc = b.origin.processing?.trim();
		if (proc) parts.push(proc);
		return parts.join(' · ');
	});
	/** Second bean meta line — roast level + grind. */
	/** Bean tags — the faint third line. */
	const headerBeanTags = $derived(
		beanLibrary.activeBean ? beanLibrary.activeBean.tags.filter(Boolean).join(' · ') : ''
	);
	const headerBeanPrep = $derived.by(() => {
		const b = beanLibrary.activeBean;
		if (!b) return '';
		const parts: string[] = [];
		if (headerBeanRoast) parts.push(headerBeanRoast);
		if (b.roastType) parts.push(b.roastType[0].toUpperCase() + b.roastType.slice(1));
		if (b.mix) parts.push(b.mix[0].toUpperCase() + b.mix.slice(1));
		parts.push(`Grind ${p.grind}`);
		return parts.join(' · ');
	});

	/** Bean rows for the header's ▾ picker — all non-archived, favourites first. */
	const beanPickerItems = $derived.by<PickerItem[]>(() =>
		beanLibrary.beans
			.filter((b) => b.archivedAt == null)
			.map((b) => {
				const r = b.roasterId ? (beanLibrary.roasters.find((x) => x.id === b.roasterId) ?? null) : null;
				const mt = roasterMarkTone(r);
				const rn = r?.name?.trim() ?? '';
				const tn = b.name.trim();
				return {
					id: b.id,
					primary: rn && tn ? `${rn} · ${tn}` : rn || tn || 'Untitled bean',
					secondary: b.origin.country || undefined,
					pinned: b.favourite,
					mark: mt.mark,
					tone: mt.tone,
					linked: b.linkedProfileId != null,
					search: `${rn} ${tn} ${b.origin.country ?? ''} ${b.tags.join(' ')}`.toLowerCase()
				};
			})
	);

	/** Profile-upload sync chip for the header, or null when idle. */
	const profileSyncLabel = $derived(
		ui.profileUploadProgress
			? `Uploading… ${ui.profileUploadProgress.acksReceived}/${ui.profileUploadProgress.totalAcks}`
			: null
	);
	const profileSyncTitle = $derived(
		ui.profileUploadProgress
			? `Uploading ${ui.profileUploadProgress.title} (${ui.profileUploadProgress.acksReceived}/${ui.profileUploadProgress.totalAcks})`
			: ''
	);

	function editActiveProfile(): void {
		const id = profileStore.activeId;
		if (id) void goto(resolve(`/profiles/${encodeURIComponent(id)}/edit?from=brew`));
	}
	function editActiveBean(): void {
		const id = beanLibrary.activeBeanId;
		void goto(resolve(id ? `/beans/${encodeURIComponent(id)}/edit?from=brew` : '/beans'));
	}
	/**
	 * Water-tank volume (ml) for the foot readout — the DE1's `WaterLevel`
	 * depth (mm) mapped through the de1app tank-geometry table, or `null`
	 * before the first reading.
	 */
	const waterMl = $derived(waterTankMl(ui.waterLevel));
	/** The water-tank volume formatted in the chosen volume unit. */
	const convertVolumeText = (ml: number | null): string => {
		const m = convertVolume(ml, prefs.volumeUnit);
		return m.unit ? `${m.value} ${m.unit}` : m.value;
	};
	/** Whether the tank is near the DE1's refill threshold — the E2 cue. */
	const refillSoon = $derived(
		waterRefillSoon(ui.waterLevel, ui.waterRefillThreshold)
	);

	// ── Quick Sheet callbacks ────────────────────────────────────────────
	/**
	 * Pick a favorite profile — mark it active in the library store. The active
	 * profile feeds {@link paramSeed}, so the header, the ratio readout and the
	 * steppers re-seed reactively; there is no imperative pre-set to keep in
	 * sync.
	 */
	function selectFavorite(profile: CremaProfile): void {
		profileStore.setActive(profile.id);
		// Picking a profile resolves the "Select a profile first" blocker
		// (and any sync-failed banner — the user's natural next move is
		// to tap Coffee again, which retries the upload).
		shotStartError = null;
	}

	/**
	 * Pre-shot banner shown when the user taps Coffee in an invalid state —
	 * the most common case being "no active profile selected". Sits in the
	 * same dashboard header slot as `MachineErrorBanner` (and uses the
	 * same `.is-error` skeleton), but is **transient** rather than
	 * substate-driven: the user clears it by selecting a profile or by
	 * tapping the dismiss `✕` on the banner.
	 *
	 * `null` means "no shot-start blocker active". A non-null string is
	 * shown as the banner's body; the title is always "Can't start shot".
	 */
	let shotStartError = $state<string | null>(null);

	// ── Maintenance advisory banner ──────────────────────────────────────
	// The maintenance store (`$lib/maintenance`) integrates flow into a
	// litre counter and flags filter / descale / clean as `Ok = false`
	// once the configured intervals are exceeded. Surface that as a yellow
	// banner on the brew page so the user sees it without digging into
	// Settings → Water; click routes them to the maintenance cards.
	const maintenance = getMaintenanceStore();
	const maintReadout = $derived(maintenance.readout);
	/**
	 * In-memory dismissed-keys set — a maintenance category the user
	 * dismissed *for this session*. Re-shown on page reload so the user
	 * cannot permanently silence a real maintenance need by tapping ✕.
	 * Re-derives the visible banner from the live readout minus this
	 * set so a re-tripped counter after dismiss still surfaces.
	 */
	let maintDismissed = $state(new Set<'filter' | 'descale' | 'clean'>());
	/**
	 * The subset of due categories the user has not dismissed this
	 * session — joined into a comma-separated banner body. Empty string
	 * when everything is on schedule (or fully dismissed); the template
	 * branches on `!== ''` to decide whether to render.
	 */
	const maintVisibleText = $derived.by<string>(() => {
		const parts: string[] = [];
		if (!maintReadout.filterOk && !maintDismissed.has('filter')) parts.push('Filter due');
		if (!maintReadout.descaleOk && !maintDismissed.has('descale')) parts.push('Descale due');
		if (!maintReadout.cleanOk && !maintDismissed.has('clean'))
			parts.push('Clean due');
		return parts.join(', ');
	});
	/** Dismiss every currently-due maintenance category for this session. */
	function dismissMaintenance(): void {
		const next = new Set(maintDismissed);
		if (!maintReadout.filterOk) next.add('filter');
		if (!maintReadout.descaleOk) next.add('descale');
		if (!maintReadout.cleanOk) next.add('clean');
		maintDismissed = next;
	}
	/** Route to Settings → Water & maintenance — the cards' canonical home. */
	function openMaintenance(): void {
		void goto(resolve('/settings#water'));
	}

	/**
	 * Whether a profile-sync upload is in flight — drives the "Syncing to
	 * DE1…" pip + spinner glyph on the Coffee button while the lazy
	 * re-upload's 1-2 s window is open. Reads the snapshot's
	 * `profileUploadProgress` (set by `ProfileUploadStarted` and cleared
	 * by `ProfileUploadCompleted` / `ProfileUploadFailed`) so any upload
	 * surface — connect-time defensive sync, shot-start lazy sync, or a
	 * manual Profiles-page Load on Brew — paints the indicator
	 * uniformly without per-surface bookkeeping.
	 */
	const profileSyncing = $derived(ui.profileUploadProgress !== null);

	/**
	 * Start / stop a shot via the real DE1 control surface (real, not stub).
	 *
	 * The orchestrator's `startShot(qc)` first verifies an active profile
	 * is selected, then lazily re-uploads the effective profile to the
	 * DE1 if the user's dial changes have drifted from what's currently
	 * loaded (the `activeProfileFingerprint` cache), then interposes a
	 * `HotWaterRinse` if `prefs.groupFlushBeforeShot` is on, then
	 * requests `Espresso`. `stopShot()` requests `Idle` which the
	 * firmware honours from any session state. We treat any non-Idle DE1
	 * state as "running" so a shot the user kicked off via the on-machine
	 * touch button can be stopped from the dashboard.
	 *
	 * Two error classes flow back as banners: {@link NoActiveProfileError}
	 * (the user hasn't picked a profile) and {@link ProfileSyncFailedError}
	 * (the lazy re-upload failed). Both clear themselves when the user
	 * picks a profile / taps Coffee again.
	 */
	async function toggleRun(): Promise<void> {
		if (!app || stateTransitionPending) return;
		stateTransitionPending = true;
		shotStartError = null;
		try {
			if (running) {
				await app.stopShot();
				return;
			}
			// Freeze the live Quick Sheet snapshot onto the orchestrator
			// so the next `ShotCompleted` can persist it onto the
			// `StoredShot` (yield target, brew temp, pre-infuse, stop-
			// on-weight, auto-tare). Mirrors the bean / grinder-model
			// snapshot pattern — capture-time-frozen, not live-read at
			// export, so a later dial change cannot rewrite history.
			const live = params.current;
			app.setBrewParamsSnapshot({
				yieldTarget: live.yield,
				brewTemp: live.brewTemp,
				preinfuseTarget: live.preinf,
				stopOnWeight: live.stopOnWeight,
				autoTare: live.autoTare
			});
			// The fingerprint compare + lazy re-upload happens inside
			// `startShot()`; when no upload is needed the call returns
			// promptly. `profileUploadProgress` (above) is the visible
			// indicator during a genuine upload window.
			await app.startShot(params.qcOverrides());
		} catch (e) {
			if (e instanceof NoActiveProfileError || e instanceof ProfileSyncFailedError) {
				shotStartError = e.message;
			} else {
				throw e;
			}
		} finally {
			stateTransitionPending = false;
		}
	}

	/**
	 * Document-level click handler: any click that lands outside the chart
	 * unpins. The LiveChart's own `onPin` callback fires *before* this for
	 * in-chart clicks, so it always wins; we only see clicks that bubbled
	 * up through the document without being absorbed by the chart's
	 * overlay div. Listener installed only while pinned to keep the page
	 * quiet otherwise.
	 */
	function onDocClick(e: MouseEvent): void {
		if (pinnedTimeSec === null) return;
		const target = e.target as Element | null;
		if (target?.closest('.crema-chart')) return;
		pinnedTimeSec = null;
	}
</script>

<svelte:window onkeydown={onKeydown} onclick={onDocClick} />

<div class="qcontain">
	<div class="crema-dash">
		<!-- Profile header strip -->
		<BrewHeader
			{profileName}
			profileMeta={`Pre-inf ${p.preinf}s · ${ratio} · ${formatWeight(p.yield, prefs.weightUnit)} · ${formatTemp(p.brewTemp, prefs.tempUnit)}`}
			profileSpec={profileIdentity}
			profileTags={profileTags}
			loadedSubline={loadedShapeSubline}
			syncLabel={profileSyncLabel}
			syncTitle={profileSyncTitle}
			canEditProfile={!!profileStore.activeId}
			profileItems={profilePickerItems}
			activeProfileId={profileStore.activeId}
			beanName={headerBeanName}
			beanMeta={headerBeanMeta}
			beanPrep={headerBeanPrep}
			beanTags={headerBeanTags}
			freshLabel={headerFreshLabel}
			freshColor={headerFreshColor}
			beanItems={beanPickerItems}
			activeBeanId={beanLibrary.activeBeanId}
			{quickSheetOpen}
			onEditProfile={editActiveProfile}
			onSelectProfile={(id) => {
				profileStore.setActive(id);
				shotStartError = null;
			}}
			onAddProfile={() => void goto(resolve('/profiles/new'))}
			onProfileLibrary={() => void goto(resolve('/profiles'))}
			onEditBean={editActiveBean}
			onSelectBean={(id) => activateBean(id, { shotInProgress: ui.shotInProgress })}
			onAddBean={() => void goto(resolve('/beans/new'))}
			onBeanLibrary={() => void goto(resolve('/beans'))}
			onOpenQuick={() => (quickSheetOpen = true)}
		>
			{#snippet status()}
				{#if ui.machineError != null}
					<div class="crema-dash-head-mid"><MachineErrorBanner text={ui.machineError} /></div>
				{:else if shotStartError != null}
					<div class="crema-dash-head-mid">
						<MachineErrorBanner text={shotStartError} title="Can't start shot" onDismiss={() => (shotStartError = null)} />
					</div>
				{:else if maintVisibleText !== ''}
					<div class="crema-dash-head-mid">
						<MachineErrorBanner
							text={maintVisibleText}
							title="Maintenance due"
							variant="warning"
							onClick={openMaintenance}
							onDismiss={dismissMaintenance}
						/>
					</div>
				{:else if modeState !== 'idle'}
					<div class="crema-dash-head-mid">
						<ModeHeadStatus
							state={modeState}
							nameLabel={headStatusName}
							metaLabel={headStatusMeta}
							progressPct={modeProgressPct}
							onCancel={cancelMode}
						/>
					</div>
				{/if}
			{/snippet}
		</BrewHeader>

		<!-- Main grid: timer + targets | readouts + chart -->
		<div class="crema-dash-main">
			<div class="crema-dash-timercol">
				<ExtractionTimer seconds={elapsedSec} step={phaseLabel} />
				<div class="crema-dash-targets">
					<!-- Stop-conditions (Yield / Volume / Time) — renders nothing when none apply. -->
					<MaxStopConditionsCard rows={stopConditionRows} />
					<!-- Brew ratio — live during a shot, then the last shot's, vs target. -->
					<div class="crema-target">
						<div class="t-eyebrow">Ratio</div>
						<div class="crema-target-val">
							<span>{formatRatio(p.dose, shotWeight)}</span>
							<span class="crema-target-unit"> · target {ratio}</span>
						</div>
					</div>
					<!-- Phase + Last-shot only fit the left column when the Quick Sheet is
					     closed; the open sheet would overlap them. The Phase card flex-grows
					     to fill the column's remaining height. -->
					{#if !quickSheetOpen}
						<PhaseIndicatorCard
							seconds={elapsedSec}
							frame={ui.shotFrame}
							segments={activeProfile?.segments}
						/>
						{#if showLastShot && lastShot}
							<LastShotCard shot={lastShot} dose={p.dose} />
						{/if}
					{/if}
				</div>
			</div>
			<div class="crema-dash-chartcol">
				<div class="crema-readouts">
					<ChannelReadout
						icon="gauge"
						label="PRESSURE"
						value={pressureM.value}
						unit={pressureM.unit}
						color="var(--tel-pressure)"
						secondaryLabel="RESISTANCE"
						secondaryValue={resistanceVal}
						secondaryUnit={resistanceUnit}
						secondaryColor="var(--tel-pressure-2)"
					/>
					<ChannelReadout
						icon="drop"
						label="FLOW"
						value={fmt(tel?.flow)}
						unit="ml/s"
						color="var(--tel-flow)"
						secondaryLabel="WATER"
						secondaryValue={dispensedVolumeVal}
						secondaryUnit={dispensedVolumeUnit}
						secondaryColor="var(--tel-flow-2)"
					/>
					<ChannelReadout
						icon="thermometer"
						label="COFFEE"
						value={tempM.value}
						unit={tempM.unit}
						color="var(--tel-temp)"
						target={brewTempTarget.value}
						secondaryLabel="WATER"
						secondaryValue={mixTempM.value}
						secondaryUnit={mixTempM.unit}
						secondaryColor="var(--tel-temp-2)"
					/>
					<ChannelReadout
						icon="scales"
						label="WEIGHT"
						value={weightM.value}
						unit={weightM.unit}
						color="var(--tel-weight)"
						target={yieldTarget.value}
						secondaryLabel="FLOW"
						secondaryValue={weightFlowVal}
						secondaryUnit="g/s"
						secondaryColor="var(--tel-weight-2)"
					/>
				</div>
				<div class="crema-chart">
					<!-- Always mounted: an empty series renders bare axes + grid (the
					     "ready" state); a finished shot's curve stays until the next
					     shot starts. -->
					<LiveChart
						series={ui.shotTelemetry}
						goalSegments={activeProfile?.segments}
						showPressure={prefs.showPressure}
						showResistance={prefs.showResistance}
						showFlow={prefs.showFlow}
						showVolume={prefs.showVolume}
						showHeadTemp={prefs.showHeadTemp}
						showMixTemp={prefs.showMixTemp}
						showWeight={prefs.showWeight}
						showWeightFlow={prefs.showWeightFlow}
						smoothPressure={prefs.smoothPressure}
						telemetryRateHz={prefs.telemetryRateHz}
						{pinnedTimeSec}
						onPin={(t) => (pinnedTimeSec = t)}
					/>
				</div>
			</div>
		</div>

		<!-- Foot: meta cluster on the left, service-mode chips in the middle,
		     big Start / Stop button on the right. Stays visible even with
		     the Quick Sheet open — the docked sheet sits just above it
		     (`bottom: 96px`).
		     The chips share the foot with Start because they're all
		     primary "what do I want the DE1 to do?" controls; espresso
		     just gets the prominent copper pill. A subtle vertical hairline
		     separates the chip cluster from Start so they don't read as
		     one row of buttons. -->
		<div class="crema-dash-foot is-split">
			<div class="crema-foot-meta">
				<!-- Sleep / wake the DE1 — rendered inline as the leftmost
				     foot-meta item. Moved here from the layout's fixed
				     top-right corner, which was overlapping the
				     profile-switcher dropdown in the dashboard header. -->
				<PowerButton {app} />
				<!-- Machine: the formatted "State / Substate" string from the
				     last `MachineStateChanged` event (e.g. "Sleep / Ready",
				     "Espresso / Pouring", "Idle / HeatWaterTank"). `—` until
				     the first notification arrives. -->
				<span class="t-eyebrow">Machine</span>
				<span>{ui.machineState ?? '—'}</span>
				<span class="crema-foot-divider"></span>
				<span class="t-eyebrow">Scale</span>
				<span>{scaleConnected ? scaleName : '—'}</span>
				<span class="crema-foot-divider"></span>
				<!-- Coffee / water-tank temperatures: the in-card COFFEE
				     readout above the chart already covers the group-head
				     thermocouple; the footer's job is the *other* live
				     references — steam-heater temperature and water-tank
				     volume — so the user has them at a glance without
				     duplicating the card. -->
				<span class="t-eyebrow">Steam</span><span
					>{steamTempM.value}{steamTempM.unit ? ` ${steamTempM.unit}` : ''}</span
				>
				<!-- Water tank: real `WaterLevel` telemetry, the sensor depth
				     converted to a tank volume in ml (see `waterTankMl`), then
				     to the Settings volume unit (D1). A "refill soon" cue (E2)
				     shows when the level nears the DE1's refill threshold. -->
				<span class="t-eyebrow">Tank</span>
				<span style:color={refillSoon ? 'var(--warning)' : undefined}>
					{convertVolumeText(waterMl)}{#if refillSoon}
						· refill soon{/if}
				</span>
			</div>
			<!-- Right cluster: three service-mode chips + a subtle vertical
			     hairline + the big Start button. Wrapped in one element so
			     the foot's `1fr auto` grid keeps a single right column
			     (without this wrapper the extra children wrap to a new row
			     above Start). Chip labels are single-line per the design;
			     active state still shows the inline ✕ cancel. -->
			<div class="crema-foot-actions">
				<div class="mc-foot-chips">
					<ModeChip
						kind="steam"
						active={modeState === 'steaming'}
						ready={modeReady}
						icon="cloud"
						label="Steam"
						sub={steamChipSub}
						onTap={() => (modeState === 'steaming' ? cancelMode() : tapSteam())}
					/>
					<ModeChip
						kind="water"
						active={modeState === 'dispensing'}
						ready={modeReady}
						icon="drop"
						label="Hot water"
						sub={waterChipSub}
						onTap={() => (modeState === 'dispensing' ? cancelMode() : tapWater())}
					/>
					<ModeChip
						kind="flush"
						active={modeState === 'flushing'}
						ready={modeReady}
						icon="sparkle"
						label="Flush"
						sub={flushChipSub}
						onTap={() => (modeState === 'flushing' ? cancelMode() : tapFlush())}
					/>
				</div>
				<span class="mc-foot-rule" aria-hidden="true"></span>
				<button
					class="crema-bigbtn"
					class:running
					class:is-syncing={profileSyncing && !running}
					disabled={stateTransitionPending || !modeReady}
					onclick={toggleRun}
				>
					{#if profileSyncing && !running}
						<!-- Sync pip — visible only while a profile upload
						     is in flight on the Coffee leg (not while the
						     shot is already running). Spinner glyph + the
						     "Syncing to DE1…" caption replaces the usual
						     coffee icon + label for the 1-2 s upload
						     window. -->
						<ArrowsClockwiseIcon class="crema-bigbtn-spinner" aria-hidden="true" />
						<span>Syncing to DE1…</span>
					{:else}
						<Icon
							cls={'ph-fill ph-' + (running ? 'stop' : 'coffee')}
							aria-hidden="true"
						 />
						<span>{running ? 'Stop' : 'Coffee'}</span>
					{/if}
				</button>
			</div>
		</div>

		<!-- Docked Quick Sheet, variant G -->
		<QuickSheet
			{params}
			{pinnedProfiles}
			selectedProfileId={profileStore.activeId}
			{pinnedBeans}
			roasters={beanLibrary.roasters}
			activeBeanId={beanLibrary.activeBeanId}
			open={quickSheetOpen}
			onSelectFavorite={selectFavorite}
			onSelectBean={selectBean}
			onClose={() => (quickSheetOpen = false)}
			onToggleAutoTare={(v) => {
				settings.set('autoTareOnShotStart', v);
				void app?.applyAutoTare(v);
			}}
			onToggleStopOnWeight={(v) => {
				settings.set('stopOnWeight', v);
				void app?.applyStopOnWeight(v);
			}}
			{yieldTargetOn}
			{onToggleYieldTarget}
			onSavePreset={activeProfile ? savePreset : undefined}
			onToggleSteamEco={(v) => {
				settings.set('steamEcoMode', v);
				void app?.applySteamEcoMode(v);
			}}
		/>
	</div>
</div>

<style>
	/* The pinned moment renders as a vertical line on the chart itself
	   (drawn by LiveChart's marker plugin); no chrome on the wrapper. */
	.crema-chart {
		cursor: crosshair;
	}
/* Coffee-button sync state — visually mirrors the profile-sync chip
	   in the header (subtle copper hue, spinning icon) so the same
	   sync event reads identically wherever it appears. The button
	   keeps its copper hue to remain "the shot control"; only the
	   inner content swaps. */
	:global(.crema-bigbtn.is-syncing) {
		background: var(--copper-500);
		box-shadow: 0 8px 24px rgba(var(--copper-rgb), 0.22);
	}
	:global(.crema-bigbtn-spinner) {
		animation: bigbtn-spin 1.2s linear infinite;
		font-size: 18px;
	}
	@keyframes bigbtn-spin {
		from {
			transform: rotate(0deg);
		}
		to {
			transform: rotate(360deg);
		}
	}
</style>
