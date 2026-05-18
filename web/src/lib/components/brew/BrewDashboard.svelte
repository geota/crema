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
	import type { UiSnapshot } from '$lib/state';
	import { ShotPhase } from '$lib/core/crema-core';
	import { BrewParamState } from './brew-params.svelte';
	import { SAMPLE_FAVORITES, type FavoriteProfile } from './favorites';
	import ExtractionTimer from './ExtractionTimer.svelte';
	import ChannelReadout from './ChannelReadout.svelte';
	import LiveChart from './LiveChart.svelte';
	import QuickSheet from './QuickSheet.svelte';

	let {
		ui
	}: {
		/** The live UI snapshot from the shared orchestrator. */
		ui: UiSnapshot;
	} = $props();

	// ── Quick Sheet local control state (UI-only) ────────────────────────
	/** The Quick Sheet's local parameter model — never reaches the machine. */
	const params = new BrewParamState();
	/** The selected favorite profile id. */
	let favoriteId = $state(SAMPLE_FAVORITES[0].id);
	/**
	 * Local "running" flag for the Start / Stop button. UI-only — the design's
	 * brew-control surface is a stub here. The displayed shot state below comes
	 * from real telemetry instead.
	 */
	let manualRunning = $state(false);

	/** The selected favorite profile (placeholder data — see `favorites.ts`). */
	const favorite = $derived(
		SAMPLE_FAVORITES.find((f) => f.id === favoriteId) ?? SAMPLE_FAVORITES[0]
	);

	const p = $derived(params.current);

	// ── Header meta ──────────────────────────────────────────────────────
	/** Live yield-to-dose ratio for the header / target cards. */
	const ratio = $derived((p.yield / p.dose).toFixed(2));
	/** The header profile name — the selected favorite. */
	const profileName = $derived(favorite.name);

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
	/** Yield progress as a 0..100 % bar width. */
	const yieldPct = $derived(
		weight == null ? 0 : Math.min(100, (weight / p.yield) * 100)
	);

	// ── Quick Sheet callbacks ────────────────────────────────────────────
	/** Apply a picked favorite to the local Quick Sheet params. */
	function selectFavorite(profile: FavoriteProfile): void {
		favoriteId = profile.id;
		// Pull the profile's parameters into the local model. UI-only — see the
		// `// TODO: wire to DE1 control` note in `brew-params`.
		params.set('dose', profile.dose);
		params.set('yield', profile.yield);
		params.set('brewTemp', profile.brewTemp);
		params.set('preinf', profile.preinf);
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
						<LiveChart series={ui.shotTelemetry} height={220} />
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

		<!-- Docked Quick Sheet, variant G -->
		<QuickSheet
			{params}
			{profileName}
			favorite={favoriteId}
			running={manualRunning}
			onSelectFavorite={selectFavorite}
			onToggleRun={toggleRun}
		/>
	</div>
</div>
