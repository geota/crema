<script lang="ts">
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
	import { waterTankMl, waterRefillSoon, type UiSnapshot } from '$lib/state';
	import { ShotPhase, MachineState } from '$lib/core/crema-core';
	import ModeChip from './ModeChip.svelte';
	import ModeHeadStatus from './ModeHeadStatus.svelte';
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
	import { getProfileStore, preinfuseSeconds, type CremaProfile } from '$lib/profiles';
	import { getCremaAppContext } from '$lib/shell/app-context';
	import { BrewParamState, type BrewParamSeed } from './brew-params.svelte';
	import ExtractionTimer from './ExtractionTimer.svelte';
	import ChannelReadout from './ChannelReadout.svelte';
	import PhaseIndicatorCard from './PhaseIndicatorCard.svelte';
	import BeanContextCard from './BeanContextCard.svelte';
	import LiveChart from './LiveChart.svelte';
	import QuickSheet from './QuickSheet.svelte';
	import LastShotCard from './LastShotCard.svelte';

	let {
		ui
	}: {
		/** The live UI snapshot from the shared orchestrator. */
		ui: UiSnapshot;
	} = $props();

	// ── App context (real — orchestrator for write actions) ─────────────
	/** Live ref to the shared CremaApp; used for the mode-chip writes. */
	const appCtx = getCremaAppContext();

	// ── Profile library (real — the lib/profiles store) ──────────────────
	/** The shared profile library — the source of pinned favorites + active. */
	const profileStore = getProfileStore();
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
	// Settings sections once they land (docs/21). Steam target = 8 s,
	// Flush target = 4 s. Hot water defaults to 30 s as a placeholder
	// time-budget until we wire `dispensedVolumeMl` against a
	// settings-driven `hotWaterVolMl` target.
	const MODE_TARGET_SEC: Record<'steaming' | 'dispensing' | 'flushing', number> = {
		steaming: 8.0,
		dispensing: 30.0,
		flushing: 4.0
	};
	const MODE_TARGET_LABEL = $derived<
		Record<'steaming' | 'dispensing' | 'flushing', string>
	>({
		steaming: `${formatTemp(148, prefs.tempUnit)} · 8 s`,
		dispensing: `${formatTemp(92, prefs.tempUnit)} · ${formatVolume(250, prefs.volumeUnit)}`,
		flushing: '4 s'
	});
	/**
	 * `performance.now()` when the DE1 first transitioned into the
	 * current service mode. Reset to `null` whenever the mode returns to
	 * idle so the next start gets a fresh `t = 0`. The associated
	 * `modeNowMs` ticks every 250 ms while a mode is active, giving the
	 * progress bar a smooth fill without coupling to the BLE telemetry
	 * cadence (which can be sparse during HotWater / Flush).
	 */
	let modeStartedAtMs = $state<number | null>(null);
	let modeNowMs = $state(0);
	$effect(() => {
		if (modeState === 'idle') {
			modeStartedAtMs = null;
			return;
		}
		// First tick after a transition: anchor `started` if absent and
		// align `now` so elapsed = 0 on the first render.
		if (modeStartedAtMs === null) {
			modeStartedAtMs = performance.now();
			modeNowMs = modeStartedAtMs;
		}
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
	 * (steam → steam heater temp; hot water → mix temp). Falls back to
	 * the resting `MODE_TARGET_LABEL` when an active mode has no
	 * temperature signal (Flush).
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
	/** The profile marked active on the Profiles page, or `undefined`. */
	const activeProfile = $derived(
		profileStore.activeId ? profileStore.get(profileStore.activeId) : undefined
	);

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
				yield: activeProfile.yieldG,
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
	const params = new BrewParamState(() => paramSeed);
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
	/** Live yield-to-dose ratio for the header / target cards. */
	const ratio = $derived((p.yield / p.dose).toFixed(2));
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
	const profileName = $derived(
		ui.activeProfileName ?? activeProfile?.name ?? 'No profile selected'
	);

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
	let lastShotInProgress = $state(false);
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
	const isPinned = $derived(pinnedTimeSec !== null);
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
	const weight = $derived(ui.scaleWeight);
	/** Weight readout, in the chosen weight unit. */
	const weightM = $derived(convertWeight(weight, prefs.weightUnit));
	/**
	 * The weight feeding the Yield / Ratio cards: live during a shot, then held
	 * at the last shot's final yield once it completes — so those cards read as
	 * the shot's result rather than the drifting scale. The 4-up WEIGHT readout
	 * and the foot keep the live `weight`.
	 */
	const shotWeight = $derived(showLastShot && lastShot ? lastShot.yieldG : weight);
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
	/** Puck resistance — raw value (no chart-scaling multiplier here). */
	const resistanceVal = $derived<string>(
		tel?.resistance != null && Number.isFinite(tel.resistance)
			? tel.resistance.toFixed(2)
			: '—'
	);
	/**
	 * Dispensed-volume readout in the chosen unit, with one decimal so it
	 * visually matches the density of the FLOW number next to it. The
	 * snapshot's `dispensedVolumeMl` reads `0` while idle; gate on
	 * `shotInProgress` so the card shows `—` until a shot starts rather
	 * than a misleading `0.0`.
	 */
	const dispensedVolumeVal = $derived.by<string>(() => {
		const ml = ui.shotInProgress ? ui.dispensedVolumeMl : null;
		if (ml == null || !Number.isFinite(ml)) return '—';
		if (prefs.volumeUnit === 'floz') return (ml / 29.5735).toFixed(1);
		return ml.toFixed(1);
	});
	const dispensedVolumeUnit = $derived(prefs.volumeUnit === 'floz' ? 'fl oz' : 'mL');
	/** Weight flow (g/s) — from the scale's host-side mass-flow estimate. */
	const weightFlowVal = $derived<string>(
		ui.scaleFlow != null && Number.isFinite(ui.scaleFlow) ? ui.scaleFlow.toFixed(1) : '—'
	);
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
	 * Water-tank volume (mL) for the foot readout — the DE1's `WaterLevel`
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
	}

	/**
	 * Start / stop a shot via the real DE1 control surface (real, not stub).
	 *
	 * The orchestrator's `startShot()` interposes a `HotWaterRinse` if
	 * `prefs.groupFlushBeforeShot` is on, then requests `Espresso`;
	 * `stopShot()` requests `Idle` which the firmware honours from any
	 * session state. We treat any non-Idle DE1 state as "running" so a
	 * shot the user kicked off via the on-machine touch button can be
	 * stopped from the dashboard.
	 */
	async function toggleRun(): Promise<void> {
		const app = appCtx().app;
		if (!app || stateTransitionPending) return;
		stateTransitionPending = true;
		try {
			if (running) {
				await app.stopShot();
			} else {
				await app.startShot();
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
		<div class="crema-dash-head" class:has-status={modeState !== 'idle'}>
			<div class="crema-dash-head-l">
				<div class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.55)">Profile</div>
				<div class="crema-dash-profile">
					{profileName}
					<!-- Upload-syncing chip — visible while a profile upload is in
					     flight (auto-upload-on-connect or manual Load-on-Brew). The
					     chip vanishes once `ProfileUploadCompleted` clears the
					     `profileUploadProgress` field. Subtle by design: the user
					     should notice "it's syncing" without it dominating the
					     header. -->
					{#if ui.profileUploadProgress}
						<span
							class="crema-profile-sync"
							title="Uploading {ui.profileUploadProgress.title} ({ui
								.profileUploadProgress.acksReceived}/{ui.profileUploadProgress.totalAcks})"
						>
							<i class="ph ph-arrows-clockwise" aria-hidden="true"></i>
							Uploading… {ui.profileUploadProgress.acksReceived}/{ui.profileUploadProgress
								.totalAcks}
						</span>
					{/if}
				</div>
				<div class="crema-dash-profile-meta">
					Pre-inf {p.preinf}s · 1:{ratio} ratio · {formatWeight(p.yield, prefs.weightUnit)} target · {formatTemp(p.brewTemp, prefs.tempUnit)}
				</div>
			</div>
			{#if modeState !== 'idle'}
				<!-- Header mode pill — visible only while a service mode is
				     running. Sits between the profile-info block (left) and
				     the Edit / Switch actions (right). -->
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
			<div class="crema-dash-head-r">
				{#if !quickSheetOpen}
					<!-- QuickPill — reopens the docked Quick Sheet once it's closed. -->
					<button class="qcpill is-dark" onclick={() => (quickSheetOpen = true)}>
						<i class="ph ph-sliders-horizontal" aria-hidden="true"></i>
						<span>Quick Controls</span>
					</button>
				{/if}
				<!-- TODO: wire to DE1 control — Edit / Switch need the profile model. -->
				<button class="crema-btn crema-btn-secondaryDark crema-btn-sm">
					<i class="ph ph-pencil-simple" aria-hidden="true"></i>
					<span>Edit</span>
				</button>
				<button class="crema-btn crema-btn-secondaryDark crema-btn-sm">
					<i class="ph ph-shuffle" aria-hidden="true"></i>
					<span>Switch profile</span>
				</button>
			</div>
		</div>

		<!-- Main grid: timer + targets | readouts + chart -->
		<div class="crema-dash-main">
			<div class="crema-dash-timercol">
				<ExtractionTimer seconds={elapsedSec} step={phaseLabel} />
				<div class="crema-dash-targets">
					<div class="crema-target">
						<div class="t-eyebrow">Yield</div>
						<div class="crema-target-val">
							<span>{shotWeightM.value}</span> / {yieldTarget.value}<span
								class="crema-target-unit">{yieldTarget.unit}</span
							>
						</div>
						<div class="crema-target-bar">
							<div style="width:{yieldPct}%"></div>
						</div>
					</div>
					<div class="crema-target">
						<div class="t-eyebrow">Ratio</div>
						<div class="crema-target-val">
							<span>1:{shotWeight == null ? '—' : (shotWeight / p.dose).toFixed(2)}</span>
							<span class="crema-target-unit"> · target 1:{ratio}</span>
						</div>
					</div>
					<!-- The "Volume" card was retired 2026-05-22: the dispensed
					     volume now lives as the secondary metric on the Flow
					     channel card (right column), and the water-tank level
					     stays on the foot strip. The card's left-column slot is
					     left empty so PhaseIndicator / Bean / LastShot fill the
					     space naturally. -->
					<!-- Phase + Bean cards only fit the left column when the Quick
					     Sheet is closed; the open sheet would overlap them. -->
					{#if !quickSheetOpen}
						<PhaseIndicatorCard
							seconds={elapsedSec}
							frame={ui.shotFrame}
							segments={activeProfile?.segments}
						/>
						<BeanContextCard grind={p.grind} />
						{#if showLastShot && lastShot}
							<!-- The just-finished shot's result — the bottom card of
							     the left column, shown until the next shot starts. -->
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
						secondaryUnit="bar·s²/mL"
						secondaryColor="var(--tel-pressure-2)"
					/>
					<ChannelReadout
						icon="drop"
						label="FLOW"
						value={fmt(tel?.flow)}
						unit="ml/s"
						color="var(--tel-flow)"
						secondaryLabel="VOLUME"
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
				<div class="crema-chart" class:is-pinned={isPinned}>
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
				<span class="t-eyebrow">Scale</span>
				<span
					>{scaleConnected
						? `${scaleName} · ${weightM.value}${weightM.unit ? ` ${weightM.unit}` : ''}`
						: 'Not paired'}</span
				>
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
				     converted to a tank volume in mL (see `waterTankMl`), then
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
					disabled={stateTransitionPending || !modeReady}
					onclick={toggleRun}
				>
					<i
						class={'ph-fill ph-' + (running ? 'stop' : 'coffee')}
						aria-hidden="true"
					></i>
					<span>{running ? 'Stop' : 'Coffee'}</span>
				</button>
			</div>
		</div>

		<!-- Docked Quick Sheet, variant G -->
		<QuickSheet
			{params}
			{pinnedProfiles}
			selectedProfileId={profileStore.activeId}
			open={quickSheetOpen}
			onSelectFavorite={selectFavorite}
			onClose={() => (quickSheetOpen = false)}
		/>
	</div>
</div>

<style>
	/* Pinned-state outline — the chart wrapper picks up a copper rim and
	   a faint copper wash when the user has clicked to freeze a moment.
	   Cards above pull their numbers from the pinned sample; the wrapper
	   highlight tells the user where the freeze came from. Esc / click
	   outside unpins (see `onKeydown` / `onDocClick` in script). */
	.crema-chart.is-pinned {
		outline: 2px solid var(--copper-500);
		outline-offset: -2px;
		background: rgba(var(--copper-rgb), 0.06);
		transition:
			background var(--dur-1, 140ms) var(--ease, ease),
			outline-color var(--dur-1, 140ms) var(--ease, ease);
	}
	.crema-chart {
		cursor: crosshair;
	}
</style>
