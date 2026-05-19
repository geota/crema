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
	import { ShotPhase } from '$lib/core/crema-core';
	import {
		getSettingsStore,
		convertWeight,
		convertTemp,
		convertPressure,
		convertVolume
	} from '$lib/settings';
	import { getProfileStore, preinfuseSeconds, type CremaProfile } from '$lib/profiles';
	import { BrewParamState, type BrewParamSeed } from './brew-params.svelte';
	import ExtractionTimer from './ExtractionTimer.svelte';
	import ChannelReadout from './ChannelReadout.svelte';
	import PhaseIndicatorCard from './PhaseIndicatorCard.svelte';
	import BeanContextCard from './BeanContextCard.svelte';
	import LiveChart from './LiveChart.svelte';
	import QuickSheet from './QuickSheet.svelte';

	let {
		ui
	}: {
		/** The live UI snapshot from the shared orchestrator. */
		ui: UiSnapshot;
	} = $props();

	// ── Profile library (real — the lib/profiles store) ──────────────────
	/** The shared profile library — the source of pinned favorites + active. */
	const profileStore = getProfileStore();
	void profileStore.ensureLoaded();

	// ── Unit preferences (real — the lib/settings store) ─────────────────
	/** The shared app-preferences store — drives every readout's display unit. */
	const settings = getSettingsStore();
	/** The live preference bundle — reactive; a unit change re-renders readouts. */
	const prefs = $derived(settings.current);

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
	 * Local "running" flag for the Start / Stop button. UI-only — the design's
	 * brew-control surface is a stub here. The displayed shot state below comes
	 * from real telemetry instead.
	 */
	let manualRunning = $state(false);

	const p = $derived(params.current);

	// ── Header meta ──────────────────────────────────────────────────────
	/** Live yield-to-dose ratio for the header / target cards. */
	const ratio = $derived((p.yield / p.dose).toFixed(2));
	/**
	 * The header profile name. The Profiles page can mark a profile "active"
	 * (UI-level — see `lib/profiles`); when it has, that name wins. Otherwise
	 * the snapshot's mirror of it, then a neutral fallback.
	 */
	const profileName = $derived(
		activeProfile?.name ?? ui.activeProfileName ?? 'No profile selected'
	);

	// ── Real telemetry (the DISPLAY side — wired to lib/state) ───────────
	//
	// The timer column and the four top-right readouts show LIVE telemetry
	// while a shot runs, then FREEZE at the shot's final values once it ends
	// (`ui.completedShot`): the DE1 keeps streaming idle telemetry after the
	// shot, so without the freeze the readouts would snap back to idle. The
	// foot's Group / Steam temps and Scale weight read the live snapshot
	// directly and never freeze.

	/** The latest live 4-channel sample, or null before any telemetry. */
	const tel = $derived(ui.latestTelemetry);
	/** The just-finished shot's frozen end state, or null. */
	const completed = $derived(ui.completedShot);
	/** True while a finished shot's values are being held on screen. */
	const showingCompleted = $derived(!ui.shotInProgress && completed !== null);
	/** The sample feeding the timer column + top-right readouts — live, then frozen. */
	const shotSample = $derived(showingCompleted ? completed!.sample : tel);
	/** Shot elapsed seconds — live, then frozen at the final duration. */
	const shotSeconds = $derived(
		(showingCompleted ? completed!.durationMs : ui.shotElapsedMs) / 1000
	);
	/** The executing profile-frame index — live, then frozen. */
	const shotFrame = $derived(showingCompleted ? completed!.frame : ui.shotFrame);

	/** A human phase caption for the timer, from the core's shot phase. */
	const phaseLabel = $derived.by(() => {
		if (!ui.shotInProgress && !showingCompleted) return 'Ready';
		const phase = showingCompleted ? completed!.phase : ui.shotPhase;
		switch (phase) {
			case ShotPhase.Heating:
				return 'Heating';
			case ShotPhase.Preinfusion:
				return 'Pre-infusion';
			case ShotPhase.Pouring:
				return 'Extraction';
			case ShotPhase.Ending:
				return 'Ending';
			default:
				return ui.shotInProgress ? 'Extraction' : 'Shot complete';
		}
	});

	/** Format a channel value, or a dim dash when there is no reading. */
	const fmt = (v: number | null | undefined, digits = 1): string =>
		v == null ? '—' : v.toFixed(digits);

	// Unit-aware channel measurements — all driven by the Settings unit prefs
	// so a unit change in Settings re-renders every readout at once (D1).
	/** Pressure readout (top-right) — live during a shot, frozen after it. */
	const pressureM = $derived(convertPressure(shotSample?.pressure, prefs.pressureUnit));
	/** Group-head temperature readout (top-right) — live, then frozen. */
	const tempM = $derived(convertTemp(shotSample?.temp, prefs.tempUnit));
	/** Mix ("group") temperature for the foot — always the live reading. */
	const mixTempM = $derived(convertTemp(tel?.mixTemp, prefs.tempUnit));
	/** Steam-heater temperature for the foot — always the live reading. */
	const steamTempM = $derived(convertTemp(tel?.steamTemp, prefs.tempUnit));

	/** Live scale weight (g) — exactly what the scale sends; feeds the foot. */
	const liveWeight = $derived(ui.scaleWeightG);
	/** Live scale weight in the chosen unit — the foot's Scale cluster. */
	const liveWeightM = $derived(convertWeight(liveWeight, prefs.weightUnit));
	/**
	 * The weight for the shot readouts (top-right WEIGHT tile, Yield, Ratio):
	 * the live scale weight during a shot, the shot's frozen final weight once
	 * it completes — so the yield does not drift as the cup is removed.
	 */
	const shotWeight = $derived(
		showingCompleted ? (completed!.sample?.weightG ?? null) : liveWeight
	);
	/** Shot weight in the chosen weight unit. */
	const shotWeightM = $derived(convertWeight(shotWeight, prefs.weightUnit));
	/** Brew-temperature target, in the chosen temperature unit. */
	const brewTempTarget = $derived(convertTemp(p.brewTemp, prefs.tempUnit));
	/** Yield target, in the chosen weight unit. */
	const yieldTarget = $derived(convertWeight(p.yield, prefs.weightUnit));
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
	const waterMl = $derived(waterTankMl(ui.waterLevelMm));
	/** The water-tank volume formatted in the chosen volume unit. */
	const convertVolumeText = (ml: number | null): string => {
		const m = convertVolume(ml, prefs.volumeUnit);
		return m.unit ? `${m.value} ${m.unit}` : m.value;
	};
	/** Whether the tank is near the DE1's refill threshold — the E2 cue. */
	const refillSoon = $derived(
		waterRefillSoon(ui.waterLevelMm, ui.waterRefillThresholdMm)
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

	/** Toggle the local running flag — the brew-control stub. */
	function toggleRun(): void {
		// TODO: wire to DE1 control — starting / stopping an extraction is a
		// net-new feature; today this only flips local UI state.
		manualRunning = !manualRunning;
	}
</script>

<div class="qcontain">
	<div class="crema-dash">
		<!-- Profile header strip -->
		<div class="crema-dash-head">
			<div class="crema-dash-head-l">
				<div class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.55)">Profile</div>
				<div class="crema-dash-profile">{profileName}</div>
				<div class="crema-dash-profile-meta">
					Pre-inf {p.preinf}s · 1:{ratio} ratio · {p.yield.toFixed(1)} g target · {p.brewTemp.toFixed(
						1
					)} °C
				</div>
			</div>
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
				<ExtractionTimer seconds={shotSeconds} step={phaseLabel} />
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
					<!-- Phase + Bean cards only fit the left column when the Quick
					     Sheet is closed; the open sheet would overlap them. -->
					{#if !quickSheetOpen}
						<PhaseIndicatorCard
							seconds={shotSeconds}
							frame={shotFrame}
							segments={activeProfile?.segments}
						/>
						<BeanContextCard grind={p.grind} />
					{/if}
				</div>
			</div>
			<div class="crema-dash-chartcol">
				<div class="crema-readouts">
					<ChannelReadout
						label="PRESSURE"
						value={pressureM.value}
						unit={pressureM.unit || 'bar'}
						color="var(--tel-pressure)"
					/>
					<ChannelReadout
						label="FLOW"
						value={fmt(shotSample?.flow)}
						unit="ml/s"
						color="var(--tel-flow)"
					/>
					<ChannelReadout
						label="TEMP"
						value={tempM.value}
						unit={tempM.unit || '°C'}
						color="var(--tel-temp)"
						target={brewTempTarget.value}
					/>
					<ChannelReadout
						label="WEIGHT"
						value={shotWeightM.value}
						unit={shotWeightM.unit || 'g'}
						color="var(--tel-weight)"
						target={yieldTarget.value}
					/>
				</div>
				<div class="crema-chart">
					<!-- Always mounted: an empty series renders bare axes + grid (the
					     "ready" state); a finished shot's curve stays until the next
					     shot starts. -->
					<LiveChart
						series={ui.shotTelemetry}
						goalSegments={activeProfile?.segments}
						showFlowCurve={prefs.showFlowCurve}
						smoothPressure={prefs.smoothPressure}
						telemetryRateHz={prefs.telemetryRateHz}
					/>
				</div>
			</div>
		</div>

		<!-- Foot: a meta cluster on the left, the big Start / Stop button on the
		     right. Stays visible even with the Quick Sheet open — the docked
		     sheet sits just above it (`bottom: 96px`). -->
		<div class="crema-dash-foot is-split">
			<div class="crema-foot-meta">
				<span class="t-eyebrow">Scale</span>
				<span
					>{scaleConnected
						? `${scaleName} · ${liveWeightM.value}${liveWeightM.unit ? ` ${liveWeightM.unit}` : ''}`
						: 'Not paired'}</span
				>
				<span class="crema-foot-divider"></span>
				<!-- Group / steam temperatures: real telemetry. The DE1's
				     blended "group" water temperature is `ShotSample.mix_temp`;
				     the steam-heater temperature is `steam_temp`. Both arrive on
				     every `Telemetry` event (see `applyEvent`). Display unit
				     follows the Settings temperature preference (D1). -->
				<span class="t-eyebrow">Group</span><span
					>{mixTempM.value}{mixTempM.unit ? ` ${mixTempM.unit}` : ''}</span
				>
				<span class="t-eyebrow">Steam</span><span
					>{steamTempM.value}{steamTempM.unit ? ` ${steamTempM.unit}` : ''}</span
				>
				<!-- Water tank: real `WaterLevel` telemetry, the sensor depth
				     converted to a tank volume in mL (see `waterTankMl`), then
				     to the Settings volume unit (D1). A "refill soon" cue (E2)
				     shows when the level nears the DE1's refill threshold. -->
				<span class="t-eyebrow">Water</span>
				<span style:color={refillSoon ? 'var(--warning)' : undefined}>
					{convertVolumeText(waterMl)}{#if refillSoon}
						· refill soon{/if}
				</span>
			</div>
			<!-- TODO: wire to DE1 control — starting / stopping a shot is a net-new
			     feature; today this only flips the local `running` flag. -->
			<button class="crema-bigbtn" class:running={manualRunning} onclick={toggleRun}>
				<i
					class={'ph-fill ph-' + (manualRunning ? 'stop' : 'play')}
					aria-hidden="true"
				></i>
				<span>{manualRunning ? 'Stop extraction' : 'Start extraction'}</span>
			</button>
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
