<script lang="ts">
	/**
	 * `MiniBrewChart` — the guided-brew sibling of `MiniShotChart`
	 * (issue #10): a tiny inline weight curve from a `BrewSeries`, with
	 * hairline ticks at the recorded stage boundaries so the bloom /
	 * pours / drawdown rhythm reads at a glance. Same hand-rolled-SVG
	 * posture — short data, no axes, no cursor.
	 */
	import type { BrewSeries } from '$lib/core/crema-core';

	let {
		series,
		width = 96,
		height = 32
	}: {
		/** The recorded session's weight series + stage marks. */
		series: BrewSeries;
		width?: number;
		height?: number;
	} = $props();

	const PAD = 1;

	const maxTimeMs = $derived.by(() => {
		const samples = series.samples;
		if (samples.length === 0) return 1;
		return Math.max(1, samples[samples.length - 1].elapsedMs);
	});

	const weightMax = $derived.by(() => {
		let m = 0;
		for (const s of series.samples) {
			if (s.weightG > m) m = s.weightG;
		}
		// A generous floor so a tiny cold-brew top-up doesn't amplify noise.
		return Math.max(m, 50);
	});

	const xAt = (tMs: number): number => PAD + (tMs / maxTimeMs) * (width - 2 * PAD);
	const yAt = (norm: number): number =>
		PAD + (1 - Math.min(1, Math.max(0, norm))) * (height - 2 * PAD);

	const weightPath = $derived.by(() => {
		const segs: string[] = [];
		let drawing = false;
		for (const s of series.samples) {
			if (!Number.isFinite(s.weightG)) {
				drawing = false;
				continue;
			}
			const x = xAt(s.elapsedMs);
			const y = yAt(s.weightG / weightMax);
			segs.push(`${drawing ? 'L' : 'M'} ${x.toFixed(1)} ${y.toFixed(1)}`);
			drawing = true;
		}
		return segs.join(' ');
	});

	/** Stage boundaries (skip the step-0 mark at t=0 — it's the left edge). */
	const stageXs = $derived(
		series.stageMarks.filter((m) => m.elapsedMs > 0).map((m) => xAt(m.elapsedMs))
	);
</script>

<svg
	class="mini-brew-chart"
	{width}
	{height}
	viewBox="0 0 {width} {height}"
	preserveAspectRatio="none"
	aria-hidden="true"
>
	{#each stageXs as x (x)}
		<line class="mini-stage" x1={x} y1={PAD} x2={x} y2={height - PAD} />
	{/each}
	<path class="mini-weight" d={weightPath} fill="none" stroke="var(--tel-weight)" />
</svg>

<style>
	.mini-brew-chart {
		display: block;
		overflow: visible;
	}
	.mini-weight {
		stroke-width: 1.5;
		stroke-linecap: round;
		stroke-linejoin: round;
		vector-effect: non-scaling-stroke;
	}
	.mini-stage {
		stroke: rgba(var(--tint-rgb), 0.18);
		stroke-width: 1;
		vector-effect: non-scaling-stroke;
	}
</style>
