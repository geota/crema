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
	 * The four channel groups in the foot's "Chart" strip. Each group has
	 * the channel's Phosphor icon + colour and two toggles (primary,
	 * secondary). Groups are visually separated by a thin vertical
	 * divider.
	 */
	const CHANNEL_GROUPS: ReadonlyArray<{
		icon: string;
		color: string;
		primary: { key: keyof Settings; label: string; color: string };
		secondary: { key: keyof Settings; label: string; color: string };
	}> = [
		{
			icon: 'gauge',
			color: 'var(--tel-pressure)',
			primary: { key: 'showPressure', label: 'Pressure', color: 'var(--tel-pressure)' },
			secondary: {
				key: 'showResistance',
				label: 'Resistance',
				color: 'var(--tel-pressure-2)'
			}
		},
		{
			icon: 'drop',
			color: 'var(--tel-flow)',
			primary: { key: 'showFlow', label: 'Flow', color: 'var(--tel-flow)' },
			secondary: { key: 'showVolume', label: 'Volume', color: 'var(--tel-flow-2)' }
		},
		{
			icon: 'thermometer',
			color: 'var(--tel-temp)',
			primary: { key: 'showHeadTemp', label: 'Head', color: 'var(--tel-temp)' },
			secondary: { key: 'showMixTemp', label: 'Mix', color: 'var(--tel-temp-2)' }
		},
		{
			icon: 'scales',
			color: 'var(--tel-weight)',
			primary: { key: 'showWeight', label: 'Weight', color: 'var(--tel-weight)' },
			secondary: { key: 'showWeightFlow', label: 'Flow', color: 'var(--tel-weight-2)' }
		}
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

	<div class="qsheet-foot">
		<!-- "Chart" strip — 4 channel groups, each with its Phosphor icon,
		     channel colour, and primary/secondary checkboxes. Thin vertical
		     hairlines between groups so the eye groups them as pairs. State
		     persists to the Settings bundle. -->
		<div class="qsheet-chart">
			<span class="qsheet-chart-lead">Chart</span>
			{#each CHANNEL_GROUPS as g, idx (g.icon)}
				{@const on1 = prefs[g.primary.key] as boolean}
				{@const on2 = prefs[g.secondary.key] as boolean}
				{#if idx > 0}<span class="qsheet-chart-div" aria-hidden="true"></span>{/if}
				<div class="qsheet-chart-group">
					<i
						class={'ph-duotone ph-' + g.icon}
						style="font-size:14px;color:{g.color}"
						aria-hidden="true"
					></i>
					<label class="qsheet-ch-tog">
						<button
							type="button"
							class="qmini-tog"
							class:on={on1}
							style={on1 ? `background:${g.primary.color}` : undefined}
							onclick={() => settings.set(g.primary.key, !on1)}
							aria-pressed={on1}
							aria-label={g.primary.label}
						></button>
						<span>{g.primary.label}</span>
					</label>
					<label class="qsheet-ch-tog">
						<button
							type="button"
							class="qmini-tog"
							class:on={on2}
							style={on2 ? `background:${g.secondary.color}` : undefined}
							onclick={() => settings.set(g.secondary.key, !on2)}
							aria-pressed={on2}
							aria-label={g.secondary.label}
						></button>
						<span>{g.secondary.label}</span>
					</label>
				</div>
			{/each}
		</div>

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
	/* "Chart" strip in the foot — 4 channel groups (icon + 2 checkboxes
	   each) with vertical hairline dividers. Sits on the left of the foot
	   row; the existing stop-on-weight / auto-tare toggles sit on the
	   right (already styled by the shared `.qsheet-foot` rules). */
	.qsheet-chart {
		display: inline-flex;
		align-items: center;
		gap: 12px;
		flex-wrap: wrap;
	}
	.qsheet-chart-lead {
		font-family: var(--font-sans);
		font-size: 9px;
		font-weight: 700;
		letter-spacing: var(--track-allcaps, 0.06em);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.5);
	}
	.qsheet-chart-div {
		display: inline-block;
		width: 1px;
		height: 18px;
		background: rgba(var(--tint-rgb), 0.12);
	}
	.qsheet-chart-group {
		display: inline-flex;
		align-items: center;
		gap: 10px;
	}
	/* One channel-toggle row — the pill switch (existing .qmini-tog) plus
	   its label. Mirrors the Stop-on-weight / Auto-tare toggles on the
	   same foot row so the chart toggles read as the same affordance. */
	.qsheet-ch-tog {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.72);
		cursor: pointer;
	}
</style>
