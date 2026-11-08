<script lang="ts">
	/**
	 * `LastShotCard` — a compact summary of the just-finished shot, shown at the
	 * bottom of the Brew dashboard's left column once a shot completes and until
	 * the next one starts.
	 *
	 * Distinct from the live readouts: those keep showing the machine's current
	 * state (temperature is the warmed-up signal and must stay live), while this
	 * card is a frozen *result* — duration, yield, ratio and the shot's peaks.
	 */
	import { getSettingsStore, convertWeight, convertPressure, convertTemp } from '#lib/settings/index.js';
	import { formatRatio } from '#lib/utils/ratio.js';
	import type { CompletedShot, TelemetrySample } from '#lib/state/index.js';
	import { StopReason } from '#lib/core/crema-core.js';
	import Icon from '#lib/icons/Icon.svelte';
	import CaretRightIcon from 'phosphor-svelte/lib/CaretRightIcon';
	import { relativeAgo } from '#lib/brew/phone-brew.js';
	import StarRating from '#lib/components/common/StarRating.svelte';
	import StaticShotChart from '#lib/components/history/StaticShotChart.svelte';
	import type { StoredShot } from '#lib/history/index.js';
	import { phoneLayout } from '#lib/shell/phone.svelte.js';
	import PhoneStat from './PhoneStat.svelte';

	let {
		shot,
		dose,
		stopReason = null,
		stored = null,
		series = [],
		onOpen,
		onRate
	}: {
		/** The finished shot's summary. */
		shot: CompletedShot;
		/** The brew dose (g) used for the yield-to-dose ratio. */
		dose: number;
		/** What ended the shot — the attribution used to be a gold ring on the
		 *  stop-conditions rows, but historical info belongs on the historical
		 *  card (user direction, all shells). Null = manual / unclassified. */
		stopReason?: StopReason | null;
		/** Phone: the shot's history row, for the context line and the rating. */
		stored?: StoredShot | null;
		/** Phone: the recorded telemetry, drawn on the card. */
		series?: readonly TelemetrySample[];
		/** Phone: open the shot in History. */
		onOpen?: (id: string) => void;
		/** Phone: set the shot's rating. */
		onRate?: (id: string, rating: number) => void;
	} = $props();

	/** The shared app-preferences store — drives every value's display unit. */
	const settings = getSettingsStore();
	/** The live preference bundle — reactive; a unit change re-renders the card. */
	const prefs = $derived(settings.current);

	/** Whole-second shot time. */
	const time = $derived(`${Math.round(shot.duration / 1000)}`);
	/** Final yield, in the chosen weight unit. */
	const yieldM = $derived(convertWeight(shot.yieldOut, prefs.weightUnit));
	/** Yield-to-dose ratio `1:x`, or `1:—` when there is no yield — the
	 *  core-owned ratio math + label (review #42). */
	const ratio = $derived(formatRatio(dose, shot.yieldOut));
	/** Peak group pressure, in the chosen pressure unit. */
	const peakBarM = $derived(convertPressure(shot.peakPressure, prefs.pressureUnit));
	/** Peak group-head temperature, in the chosen temperature unit. */
	const peakTempM = $derived(convertTemp(shot.peakTemp, prefs.tempUnit));
	/** "Stopped" label — mirrors the Android `stopLabel` mapping. */
	const stopped = $derived(
		stopReason === StopReason.Weight
			? 'Yield'
			: stopReason === StopReason.EstimatedWeight
				? 'Yield, on estimate'
			: stopReason === StopReason.Volume
				? 'Volume'
				: stopReason === StopReason.MaxTime
					? 'Time'
					: '\u2014'
	);
	/** Icon ONLY in the cell (user direction) — the same icon the condition
	 *  wears on the stop-conditions rows (Android `stopIcon`); the word
	 *  survives as the aria-label. */
	const stoppedIcon = $derived(
		stopReason === StopReason.Weight || stopReason === StopReason.EstimatedWeight
			? 'scales'
			: stopReason === StopReason.Volume
				? 'drop-half'
				: stopReason === StopReason.MaxTime
					? 'timer'
					: null
	);
	/** `completedAt` is a `performance.now()` stamp; the clock time for "2m ago". */
	const wallClockAt = $derived(Date.now() - (performance.now() - shot.completedAt));
	/** Profile · bean of the stored shot, when it landed in History. */
	const context = $derived(
		stored
			? [stored.profileName, stored.bean?.name].filter(Boolean).join(' · ')
			: ''
	);
</script>

{#snippet phoneHead()}
	<span class="pls-head-text">
		<span class="pls-eyebrow">Last shot · {relativeAgo(wallClockAt)}</span>
		{#if context}<span class="pls-context">{context}</span>{/if}
	</span>
{/snippet}

{#if phoneLayout.current}
	<section class="pcard pls" aria-label="Last shot">
		{#if stored}
			<button
				type="button"
				class="pls-head"
				aria-label="Open this shot in History"
				onclick={() => onOpen?.(stored.id)}
			>
				{@render phoneHead()}
				<CaretRightIcon aria-hidden="true" />
			</button>
		{:else}
			<div class="pls-head">{@render phoneHead()}</div>
		{/if}
		<div class="pls-chart">
			{#if series.length > 0}
				<StaticShotChart {series} height={96} compact />
			{:else}
				<p>No curve recorded for this shot.</p>
			{/if}
		</div>
		<div class="pls-stats">
			<PhoneStat label="Time" value={(shot.duration / 1000).toFixed(1)} unit="s" />
			<PhoneStat
				label="Yield"
				value={shot.yieldOut != null ? yieldM.value : '—'}
				unit={shot.yieldOut != null ? yieldM.unit : ''}
			/>
			<PhoneStat label="Ratio" value={shot.yieldOut != null ? ratio : '—'} />
			<PhoneStat label="Peak" value={peakBarM.value} unit={peakBarM.unit} />
			{#if stoppedIcon}
				<PhoneStat label="Stopped">
					<Icon cls={'ph ph-' + stoppedIcon} aria-label={stopped} />
				</PhoneStat>
			{:else}
				<PhoneStat label="Stopped" value={stopped} />
			{/if}
		</div>
		{#if stored}
			<StarRating
				rating={stored.metadata.rating}
				interactive
				size={18}
				onRate={(n) => onRate?.(stored.id, n)}
			/>
		{/if}
	</section>
{:else}
<div class="crema-target ls-card">
	<div class="t-eyebrow">Last shot</div>
	<div class="ls-grid">
		<div class="ls-stat">
			<span class="ls-v">{time}<em>s</em></span>
			<span class="ls-l">Time</span>
		</div>
		<div class="ls-stat">
			<span class="ls-v">{yieldM.value}<em>{yieldM.unit}</em></span>
			<span class="ls-l">Yield</span>
		</div>
		<div class="ls-stat">
			<span class="ls-v">{ratio}</span>
			<span class="ls-l">Ratio</span>
		</div>
		<div class="ls-stat">
			<span class="ls-v">{peakBarM.value}<em>{peakBarM.unit}</em></span>
			<span class="ls-l">Peak</span>
		</div>
		<div class="ls-stat">
			<span class="ls-v">{peakTempM.value}<em>{peakTempM.unit}</em></span>
			<span class="ls-l">Peak temp</span>
		</div>
		<div class="ls-stat ls-stat-stop">
			<span class="ls-v ls-stop">
				{#if stoppedIcon}<Icon cls={'ph ph-' + stoppedIcon} aria-label={stopped} />{:else}{stopped}{/if}</span>
			<span class="ls-l">Stopped</span>
		</div>
	</div>
</div>
{/if}

<style>
	/* The phone card (Android's Last shot): context, the curve, the outcome. */
	.pls {
		display: flex;
		flex-direction: column;
		gap: 10px;
		padding: 12px 16px;
	}
	.pls-head {
		display: flex;
		align-items: center;
		gap: 8px;
		width: 100%;
		min-height: 44px;
		margin: -6px 0;
		padding: 0;
		border: 0;
		border-radius: 8px;
		background: transparent;
		color: var(--fg-3);
		font: inherit;
		text-align: left;
	}
	button.pls-head {
		cursor: pointer;
	}
	button.pls-head:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
	.pls-head > :global(svg) {
		flex: none;
		font-size: 16px;
	}
	.pls-head-text {
		flex: 1 1 auto;
		display: flex;
		flex-direction: column;
		gap: 2px;
		min-width: 0;
	}
	.pls-eyebrow {
		font-family: var(--font-sans);
		font-size: 11px;
		font-weight: 500;
		letter-spacing: 0.04em;
		text-transform: uppercase;
		color: var(--fg-3);
	}
	.pls-context {
		overflow: hidden;
		font-family: var(--font-sans);
		font-size: 12px;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--fg-3);
	}
	.pls-chart {
		height: 96px;
		overflow: hidden;
		border-radius: var(--radius-md);
		background: var(--bg-page);
	}
	.pls-chart p {
		display: flex;
		align-items: center;
		justify-content: center;
		height: 100%;
		margin: 0;
		font-family: var(--font-sans);
		font-size: 12px;
		color: var(--fg-3);
	}
	.pls-stats {
		display: grid;
		grid-template-columns: repeat(5, minmax(0, 1fr));
		gap: 6px;
	}

	/* `.crema-target` (web-kit.css) gives the card chrome — background, border,
	   radius, padding — shared with the Yield / Ratio cards above it. */
	.ls-card {
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.ls-grid {
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
		gap: 8px 10px;
	}
	.ls-stat {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.ls-v {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 15px;
		line-height: 1.1;
		color: var(--fg-1);
	}
	.ls-stop {
		display: inline-flex;
		align-items: center;
		gap: 4px;
	}
	/* Icon-only cell: shrink to the label's width and centre the lone glyph
	   on it — the label keeps the shared left edge with the other columns. */
	.ls-stat-stop {
		width: fit-content;
		align-items: center;
	}
	.ls-stop :global(.ph) {
		/* Value-sized: the icon stands alone in the cell. */
		font-size: 15px;
		color: var(--fg-3);
	}
	.ls-v em {
		font-style: normal;
		font-size: 10px;
		color: var(--fg-muted);
		margin-left: 2px;
	}
	.ls-l {
		font-family: var(--font-sans);
		font-size: 8.5px;
		font-weight: 700;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: var(--fg-muted);
	}
</style>
