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
	import { getSettingsStore, convertWeight, convertPressure, convertTemp } from '$lib/settings';
	import { formatRatio } from '$lib/utils/ratio';
	import type { CompletedShot } from '$lib/state';
	import { StopReason } from '$lib/core/crema-core';
	import Icon from '$lib/icons/Icon.svelte';

	let {
		shot,
		dose,
		stopReason = null
	}: {
		/** The finished shot's summary. */
		shot: CompletedShot;
		/** The brew dose (g) used for the yield-to-dose ratio. */
		dose: number;
		/** What ended the shot — the attribution used to be a gold ring on the
		 *  stop-conditions rows, but historical info belongs on the historical
		 *  card (user direction, all shells). Null = manual / unclassified. */
		stopReason?: StopReason | null;
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
		stopReason === StopReason.Weight
			? 'scales'
			: stopReason === StopReason.Volume
				? 'drop-half'
				: stopReason === StopReason.MaxTime
					? 'timer'
					: null
	);
</script>

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

<style>
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
		color: rgba(var(--tint-rgb), 0.6);
	}
	.ls-v em {
		font-style: normal;
		font-size: 10px;
		color: rgba(var(--tint-rgb), 0.5);
		margin-left: 2px;
	}
	.ls-l {
		font-family: var(--font-sans);
		font-size: 8.5px;
		font-weight: 700;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.42);
	}
</style>
