<script lang="ts">
	/**
	 * `PhoneShotTimer` — the running shot's timer and yield bar (Android
	 * `RunningBody`): phase, `MM:SS.f`, then `Yield · 1:n` over `now / target`.
	 */
	let {
		seconds,
		phase,
		ratio,
		yieldText,
		yieldPct
	}: {
		/** Shot elapsed time, seconds. */
		seconds: number;
		phase: string;
		/** The live ratio, `1:1.7`. */
		ratio: string;
		/** `12.0 / 35.0 g`. */
		yieldText: string;
		/** Bar fill, 0–100. */
		yieldPct: number;
	} = $props();

	const clock = $derived.by(() => {
		const whole = Math.max(0, Math.floor(seconds));
		const mm = String(Math.floor(whole / 60)).padStart(2, '0');
		const ss = String(whole % 60).padStart(2, '0');
		return `${mm}:${ss}`;
	});
	const frac = $derived(Math.floor((Math.max(0, seconds) % 1) * 10));
</script>

<section class="pcard ptimer" aria-label="Shot timer">
	<span class="ptimer-phase">{phase}</span>
	<span class="ptimer-clock">{clock}<em>.{frac}</em></span>
	<div class="ptimer-yield">
		<span class="ptimer-l">Yield · {ratio}</span>
		<span class="ptimer-v">{yieldText}</span>
	</div>
	<div class="ptimer-bar"><span style:width="{yieldPct}%"></span></div>
</section>

<style>
	.ptimer {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 5px;
		padding: 10px 16px 12px;
	}
	.ptimer-phase {
		font-family: var(--font-sans);
		font-size: 11px;
		font-weight: 500;
		letter-spacing: 0.05em;
		text-transform: uppercase;
		color: var(--fg-accent);
	}
	.ptimer-clock {
		font-family: var(--font-mono);
		font-size: 60px;
		font-variant-numeric: tabular-nums;
		font-weight: 500;
		letter-spacing: -2px;
		line-height: 54px;
		color: var(--fg-1);
	}
	.ptimer-clock em {
		font-size: 28px;
		font-style: normal;
		letter-spacing: -1px;
		color: var(--fg-3);
	}
	.ptimer-yield {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 8px;
		width: 100%;
		margin-top: 1px;
	}
	.ptimer-l {
		font-family: var(--font-sans);
		font-size: 11px;
		font-weight: 500;
		letter-spacing: 0.04em;
		text-transform: uppercase;
		color: var(--fg-3);
	}
	.ptimer-v {
		font-family: var(--font-mono);
		font-size: 14px;
		font-variant-numeric: tabular-nums;
		font-weight: 500;
		color: var(--fg-1);
	}
	.ptimer-bar {
		width: 100%;
		height: 8px;
		overflow: hidden;
		border-radius: var(--radius-pill);
		background: rgba(var(--tint-rgb), 0.08);
	}
	.ptimer-bar span {
		display: block;
		height: 100%;
		border-radius: inherit;
		background: var(--copper-500);
	}
</style>
