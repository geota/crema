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
	import type { CompletedShot } from '$lib/state';

	let {
		shot,
		dose
	}: {
		/** The finished shot's summary. */
		shot: CompletedShot;
		/** The brew dose (g) used for the yield-to-dose ratio. */
		dose: number;
	} = $props();

	/** The shared app-preferences store — drives every value's display unit. */
	const settings = getSettingsStore();
	/** The live preference bundle — reactive; a unit change re-renders the card. */
	const prefs = $derived(settings.current);

	/** Whole-second shot time. */
	const time = $derived(`${Math.round(shot.duration / 1000)}`);
	/** Final yield, in the chosen weight unit. */
	const yieldM = $derived(convertWeight(shot.yieldG, prefs.weightUnit));
	/** Yield-to-dose ratio `1:x`, or `1:—` when there is no yield. */
	const ratio = $derived(
		shot.yieldG != null && dose > 0 ? `1:${(shot.yieldG / dose).toFixed(1)}` : '1:—'
	);
	/** Peak group pressure, in the chosen pressure unit. */
	const peakBarM = $derived(convertPressure(shot.peakPressure, prefs.pressureUnit));
	/** Peak group-head temperature, in the chosen temperature unit. */
	const peakTempM = $derived(convertTemp(shot.peakTemp, prefs.tempUnit));
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
