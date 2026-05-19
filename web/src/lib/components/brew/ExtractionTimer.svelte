<script lang="ts">
	/**
	 * `ExtractionTimer` — the big shot timer, ported from the design's
	 * `ExtractionTimer` in `ds/web-components.jsx`. Reuses the `.crema-timer*`
	 * class names.
	 *
	 * Wired to real data: `seconds` is the live shot elapsed time from
	 * `lib/state` (`shotElapsed / 1000`); `step` is the current shot phase.
	 */
	let {
		seconds,
		step
	}: {
		/** Shot elapsed time, seconds. */
		seconds: number;
		/** Phase caption shown above the digits (e.g. "Pre-infusion"). */
		step: string;
	} = $props();

	/** Zero-padded minutes. */
	const mm = $derived(
		Math.floor(seconds / 60)
			.toString()
			.padStart(2, '0')
	);
	/** Zero-padded whole seconds. */
	const ss = $derived(
		Math.floor(seconds % 60)
			.toString()
			.padStart(2, '0')
	);
	/**
	 * Single tenths digit — floored, not rounded. `toFixed(1)` would round
	 * (e.g. 4.97 → ".0") and so disagree with the floored `ss` above; flooring
	 * the tenths keeps the two in lock-step.
	 */
	const frac = $derived(Math.floor((seconds % 1) * 10).toString());
</script>

<div class="crema-timer">
	<div class="crema-timer-step">{step}</div>
	<div class="crema-timer-val">
		<span>{mm}:{ss}</span><span class="crema-timer-frac">.{frac}</span>
	</div>
</div>
