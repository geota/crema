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
	import { waterTankMl, type UiSnapshot } from '$lib/state';
	import { ShotPhase } from '$lib/core/crema-core';
	import { getProfileStore, preinfuseSeconds, type CremaProfile } from '$lib/profiles';
	import { BrewParamState } from './brew-params.svelte';
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

	/** The real pinned profiles, shown as favorite chips in the Quick Sheet. */
	const pinnedProfiles = $derived(profileStore.all.filter((p) => p.pinned));
	/** The profile marked active on the Profiles page, or `undefined`. */
	const activeProfile = $derived(
		profileStore.activeId ? profileStore.get(profileStore.activeId) : undefined
	);

	// ── Quick Sheet local control state ──────────────────────────────────
	/**
	 * The Quick Sheet's parameter model. Its dose / yield / temp / pre-infusion
	 * are initialised from the active profile (the `$effect` below) so the
	 * header, the ratio readout and the steppers all agree; the steppers may
	 * then edit it locally. The CONTROL side never reaches the machine in this
	 * porting step — see the `// TODO: wire to DE1 control` notes.
	 */
	const params = new BrewParamState();
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

	/**
	 * Seed the local Quick Sheet params from a profile's targets — dose, yield,
	 * brew temperature and pre-infusion (the first segment's seconds).
	 */
	function paramsFromProfile(profile: CremaProfile): void {
		params.set('dose', profile.dose);
		params.set('yield', profile.yieldG);
		params.set('brewTemp', profile.brewTemp);
		params.set('preinf', preinfuseSeconds(profile.segments));
	}

	// Initialise the params whenever the active profile changes — this bridges
	// the external library store into the local param model, so the header /
	// ratio / steppers stay in sync with whatever profile is active. Keyed on
	// the profile id so it fires once per active-profile change, not on every
	// stepper edit.
	let lastSeededId: string | undefined;
	$effect(() => {
		const profile = activeProfile;
		if (profile && profile.id !== lastSeededId) {
			lastSeededId = profile.id;
			paramsFromProfile(profile);
		}
	});

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
	/** The latest 4-channel sample, or null when no telemetry has arrived. */
	const tel = $derived(ui.latestTelemetry);
	/** Whether there is any shot data to show. */
	const hasData = $derived(ui.shotTelemetry.length > 0);
	/** Shot elapsed time, seconds — from the buffered telemetry. */
	const elapsedSec = $derived(ui.shotElapsedMs / 1000);

	/** A human phase caption for the timer, from the core's shot phase. */
	const phaseLabel = $derived.by(() => {
		if (!ui.shotInProgress && !hasData) return 'Ready';
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
				return ui.shotInProgress ? 'Extraction' : 'Shot complete';
		}
	});

	/** Format a channel value, or a dim dash when there is no reading. */
	const fmt = (v: number | null | undefined, digits = 1): string =>
		v == null ? '—' : v.toFixed(digits);

	/** Live weight (g) — from the scale stream, independent of shot state. */
	const weight = $derived(ui.scaleWeightG);
	/** Whether a scale is connected — drives the foot's "Scale" cluster. */
	const scaleConnected = $derived(
		ui.scaleState === 'ready' || ui.scaleState === 'subscribing'
	);
	/** The connected scale's advertised name, with a neutral fallback. */
	const scaleName = $derived(ui.scaleName ?? 'Scale');
	/** Yield progress as a 0..100 % bar width. */
	const yieldPct = $derived(
		weight == null ? 0 : Math.min(100, (weight / p.yield) * 100)
	);
	/**
	 * Water-tank volume (mL) for the foot readout — the DE1's `WaterLevel`
	 * depth (mm) mapped through the de1app tank-geometry table, or `null`
	 * before the first reading.
	 */
	const waterMl = $derived(waterTankMl(ui.waterLevelMm));

	// ── Quick Sheet callbacks ────────────────────────────────────────────
	/**
	 * Pick a favorite profile — mark it active in the library store and pull
	 * its targets into the local Quick Sheet params. Marking it active also
	 * drives the header name and the `$effect` seeding above.
	 */
	function selectFavorite(profile: CremaProfile): void {
		profileStore.setActive(profile.id);
		// Seed the params immediately so the steppers reflect the pick without
		// waiting for the active-profile effect to settle.
		lastSeededId = profile.id;
		paramsFromProfile(profile);
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
				<div class="t-eyebrow" style="color:rgba(244,237,224,0.55)">Profile</div>
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
				<ExtractionTimer seconds={elapsedSec} step={phaseLabel} />
				<div class="crema-dash-targets">
					<div class="crema-target">
						<div class="t-eyebrow">Yield</div>
						<div class="crema-target-val">
							<span>{fmt(weight)}</span> / {p.yield.toFixed(1)}<span class="crema-target-unit"
								>g</span
							>
						</div>
						<div class="crema-target-bar">
							<div style="width:{yieldPct}%"></div>
						</div>
					</div>
					<div class="crema-target">
						<div class="t-eyebrow">Ratio</div>
						<div class="crema-target-val">
							<span>1:{weight == null ? '—' : (weight / p.dose).toFixed(2)}</span>
							<span class="crema-target-unit"> · target 1:{ratio}</span>
						</div>
					</div>
					<!-- Phase + Bean cards only fit the left column when the Quick
					     Sheet is closed; the open sheet would overlap them. -->
					{#if !quickSheetOpen}
						<PhaseIndicatorCard seconds={elapsedSec} preinf={p.preinf} />
						<BeanContextCard grind={p.grind} />
					{/if}
				</div>
			</div>
			<div class="crema-dash-chartcol">
				<div class="crema-readouts">
					<ChannelReadout
						label="PRESSURE"
						value={fmt(tel?.pressure)}
						unit="bar"
						color="var(--tel-pressure)"
					/>
					<ChannelReadout
						label="FLOW"
						value={fmt(tel?.flow)}
						unit="ml/s"
						color="var(--tel-flow)"
					/>
					<ChannelReadout
						label="TEMP"
						value={fmt(tel?.temp)}
						unit="°C"
						color="var(--tel-temp)"
						target={p.brewTemp.toFixed(1)}
					/>
					<ChannelReadout
						label="WEIGHT"
						value={fmt(weight)}
						unit="g"
						color="var(--tel-weight)"
						target={p.yield.toFixed(1)}
					/>
				</div>
				<div class="crema-chart">
					{#if hasData}
						<LiveChart series={ui.shotTelemetry} />
					{:else}
						<!-- Clean empty state — no shot data buffered yet. -->
						<div class="crema-chart-empty">
							<i class="ph-duotone ph-chart-line" aria-hidden="true"></i>
							<div class="crema-chart-empty-title">No shot in progress</div>
							<div class="crema-chart-empty-sub">
								Live pressure, flow, temperature and weight will plot here once a
								shot begins.
							</div>
						</div>
					{/if}
				</div>
			</div>
		</div>

		<!-- Foot: a meta cluster on the left, the big Start / Stop button on the
		     right. Stays visible even with the Quick Sheet open — the docked
		     sheet sits just above it (`bottom: 96px`). -->
		<div class="crema-dash-foot is-split">
			<div class="crema-foot-meta">
				<span class="t-eyebrow">Scale</span>
				<span>{scaleConnected ? `${scaleName} · ${fmt(weight)} g` : 'Not paired'}</span>
				<span class="crema-foot-divider"></span>
				<!-- TODO: wire to DE1 control — the group / steam readouts need the
				     machine's structured temperatures, which the core does not
				     surface yet; these are the design's representative values. -->
				<span class="t-eyebrow">Group</span><span>93.2 °C</span>
				<span class="t-eyebrow">Steam</span><span>148 °C</span>
				<!-- Water tank: real `WaterLevel` telemetry, the sensor depth
				     converted to a tank volume in mL (see `waterTankMl`). -->
				<span class="t-eyebrow">Water</span>
				<span>{waterMl == null ? '—' : `${waterMl} mL`}</span>
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
