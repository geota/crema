<script lang="ts">
	/**
	 * `QuickSheet` — the docked Quick Sheet, variant G, ported from the
	 * `variant === 'g'` path of `WebQDashV2` in `web-dashboard-v2.jsx`.
	 *
	 * Header (serif title, Save / Reset / Close — no profile chip; the profile
	 * already sits in the dash header), the `FavoritesStrip`, the one-row
	 * six-card body (Dose|Grind · Yield · Brew Temp · Steam · Hot Water ·
	 * Pre-Infuse|Flush), and the footer (just the two mini-toggles — the big
	 * Start button lives on the dash-foot, which stays visible behind the sheet).
	 *
	 * The whole brew-CONTROL surface is **UI-only** in this porting step — the
	 * core treats the DE1 as read-only. Every action that would reach the
	 * machine is marked `// TODO: wire to DE1 control`.
	 */
	import type { BrewParamState } from './brew-params.svelte';
	import type { CremaProfile } from '$lib/profiles';
	import FavoritesStrip from './FavoritesStrip.svelte';
	import DoseGrindStepper from './DoseGrindStepper.svelte';
	import YieldRatioStepper from './YieldRatioStepper.svelte';
	import BrewTempStepper from './BrewTempStepper.svelte';
	import SteamStepper from './SteamStepper.svelte';
	import WaterStepper from './WaterStepper.svelte';
	import PreinfFlushStepper from './PreinfFlushStepper.svelte';
	import { getSettingsStore, type Settings } from '$lib/settings';

	const settings = getSettingsStore();
	const prefs = $derived(settings.current);

	/**
	 * The chart-channel toggles. Each entry pairs a settings field with its
	 * channel label + the `--tel-*` colour token that the LiveChart strokes
	 * with. Primary channels listed first, then secondaries; arrangement in
	 * a 4×2 grid matches the channel cards above the chart.
	 */
	const CHANNEL_TOGGLES: ReadonlyArray<{
		key: keyof Settings;
		label: string;
		color: string;
		sub?: string;
	}> = [
		{ key: 'showPressure', label: 'Pressure', color: 'var(--tel-pressure)' },
		{
			key: 'showResistance',
			label: 'Resistance',
			color: 'var(--tel-pressure-2)',
			sub: '×5'
		},
		{ key: 'showFlow', label: 'Flow', color: 'var(--tel-flow)' },
		{ key: 'showVolume', label: 'Volume', color: 'var(--tel-flow-2)' },
		{ key: 'showHeadTemp', label: 'Head temp', color: 'var(--tel-temp)' },
		{ key: 'showMixTemp', label: 'Mix temp', color: 'var(--tel-temp-2)' },
		{ key: 'showWeight', label: 'Weight', color: 'var(--tel-weight)' },
		{ key: 'showWeightFlow', label: 'Weight flow', color: 'var(--tel-weight-2)' }
	];

	let {
		params,
		pinnedProfiles,
		selectedProfileId,
		open,
		onSelectFavorite,
		onClose
	}: {
		/** The shared Quick Sheet parameter store. */
		params: BrewParamState;
		/** The pinned profiles shown as favorite chips. */
		pinnedProfiles: readonly CremaProfile[];
		/** The active profile's id (the highlighted chip), or `null`. */
		selectedProfileId: string | null;
		/** Whether the sheet is docked open; when false it slides away. */
		open: boolean;
		/** Called when a favorite chip is picked. */
		onSelectFavorite: (profile: CremaProfile) => void;
		/** Called to dismiss the sheet (Close button or scrim tap). */
		onClose: () => void;
	} = $props();

	const p = $derived(params.current);
</script>

<!-- The scrim dims the dashboard behind the open sheet; tapping it — or the
     header's Close — dismisses the sheet. When closed both slide / fade away
     (the CSS base state) and the dashboard's QuickPill brings it back. -->
<button
	type="button"
	class="qsheet-scrim"
	class:is-open={open}
	aria-label="Close Quick Controls"
	onclick={onClose}
></button>
<div class="qsheet is-v2 is-onerow" class:is-open={open}>
	<div class="qsheet-v2-head">
		<div class="qsheet-v2-title-block">
			<div class="qsheet-v2-title">Quick Controls</div>
		</div>
		<div class="qsheet-v2-actions">
			<!-- TODO: wire to DE1 control — saving a preset needs the profile model. -->
			<button class="qsheet-cta">
				<i class="ph ph-bookmark-simple" aria-hidden="true"></i> Save preset
			</button>
			<button class="qsheet-cta" onclick={() => params.reset()}>
				<i class="ph ph-arrow-counter-clockwise" aria-hidden="true"></i> Reset
			</button>
			<button class="qsheet-cta" onclick={onClose}>
				<i class="ph ph-x" aria-hidden="true"></i> Close
			</button>
		</div>
	</div>

	<FavoritesStrip
		profiles={pinnedProfiles}
		selectedId={selectedProfileId}
		onSelect={onSelectFavorite}
	/>

	<div class="qsheet-g-grid is-six">
		<DoseGrindStepper {params} />
		<YieldRatioStepper {params} />
		<BrewTempStepper {params} />
		<SteamStepper {params} />
		<WaterStepper {params} />
		<PreinfFlushStepper {params} />
	</div>

	<!-- Chart channels — eight per-line on/off toggles for the LiveChart.
	     Primaries (Pressure / Flow / Head / Weight) default on; secondaries
	     (Resistance / Volume / Mix / Weight flow) default off. State lives
	     on the persisted Settings bundle so a reload remembers. -->
	<div class="qsheet-channels">
		<div class="qsheet-channels-label">Chart channels</div>
		<div class="qsheet-channels-grid">
			{#each CHANNEL_TOGGLES as ch (ch.key)}
				{@const on = prefs[ch.key] as boolean}
				<label class="qsheet-ch">
					<button
						type="button"
						class="qmini-tog"
						class:on
						style={on ? `--qch-color:${ch.color}` : undefined}
						onclick={() => settings.set(ch.key, !on)}
						aria-pressed={on}
						aria-label={ch.label}
					></button>
					<span class="qsheet-ch-label">
						<span class="qsheet-ch-dot" style="background:{ch.color}"></span>
						{ch.label}
						{#if ch.sub}<em class="qsheet-ch-sub">{ch.sub}</em>{/if}
					</span>
				</label>
			{/each}
		</div>
	</div>

	<div class="qsheet-foot">
		<div class="qsheet-toggles">
			<label class="qsheet-tog">
				<!-- TODO: wire to DE1 control — stop-on-weight is local UI state today. -->
				<button
					class="qmini-tog"
					class:on={p.stopOnWeight}
					onclick={() => params.set('stopOnWeight', !p.stopOnWeight)}
					aria-pressed={p.stopOnWeight}
					aria-label="Stop on weight"
				></button>
				<span>Stop on weight</span>
			</label>
			<label class="qsheet-tog">
				<!-- TODO: wire to DE1 control — auto-tare is local UI state today. -->
				<button
					class="qmini-tog"
					class:on={p.autoTare}
					onclick={() => params.set('autoTare', !p.autoTare)}
					aria-pressed={p.autoTare}
					aria-label="Auto-tare"
				></button>
				<span>Auto-tare</span>
			</label>
		</div>
	</div>
</div>

<style>
	.qsheet-channels {
		padding: 12px 18px 4px;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.qsheet-channels-label {
		font-family: var(--font-sans);
		font-size: 9px;
		font-weight: 700;
		letter-spacing: var(--track-allcaps, 0.06em);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.5);
	}
	.qsheet-channels-grid {
		display: grid;
		grid-template-columns: repeat(4, 1fr);
		gap: 4px 10px;
	}
	.qsheet-ch {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		font-family: var(--font-sans);
		font-size: 11px;
		color: var(--fg-1);
		cursor: pointer;
	}
	.qsheet-ch-label {
		display: inline-flex;
		align-items: center;
		gap: 5px;
	}
	.qsheet-ch-dot {
		display: inline-block;
		width: 8px;
		height: 8px;
		border-radius: 2px;
	}
	.qsheet-ch-sub {
		font-style: normal;
		font-size: 9px;
		color: rgba(var(--tint-rgb), 0.45);
		font-family: var(--font-mono);
	}
</style>
