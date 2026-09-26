<script lang="ts">
	/**
	 * `BrewSessionChart` — the guided-brew detail chart (issue #10):
	 * the recorded weight curve (weight green, the hero), the derived
	 * pour rate (flow blue, secondary), and alternating stage bands at
	 * the recorded step boundaries so the bloom / pours / drawdown
	 * rhythm is legible. When the stage marks carry the recipe's
	 * snapshotted water targets, a dashed "planned" staircase sits under
	 * the solid "poured" weight curve. Hand-rolled SVG like the mini charts — a brew
	 * series is ≤200 stored samples with no cursor interactions, so
	 * uPlot would be overkill.
	 */
	import type { BrewSeries } from '$lib/core/crema-core';
	import { maxPlannedTarget, plannedStaircase, staircasePath } from '$lib/brew/staircase';

	let {
		series,
		height = 300,
		extentMs = 0,
		legend = true
	}: {
		series: BrewSeries;
		height?: number;
		/** Draw the in-chart "planned / poured" legend when targets are
		 *  present. Off when the host labels the dashed line itself. */
		legend?: boolean;
		/** Live use (the running session): the session clock, so the time
		 *  axis and the current stage band grow with it even between
		 *  samples — or with no scale at all. 0 = size to the samples. */
		extentMs?: number;
	} = $props();

	// Fixed virtual width; the SVG scales to its container.
	const W = 640;
	const PAD_L = 34;
	const PAD_R = 10;
	const PAD_T = 8;
	/** Top padding with the legend on: a strip above the plot, so the
	 *  legend never sits on the data (the flow line tops out at the plot's
	 *  upper edge, and early in a live brew so does the weight curve). */
	const PAD_T_LEGEND = 24;
	const PAD_B = 22;

	const maxTimeMs = $derived.by(() => {
		const s = series.samples;
		const lastSample = s.length ? s[s.length - 1].elapsedMs : 0;
		if (s.length === 0 && extentMs <= 0) return 1000;
		// Round up to a 30 s grid so the axis ends on a clean tick.
		const last = Math.max(lastSample, extentMs);
		return Math.max(30_000, Math.ceil(last / 30_000) * 30_000);
	});

	/** The largest snapshotted target — 0 for manual logs / old records. */
	const plannedMax = $derived(maxPlannedTarget(series.stageMarks));

	const maxWeight = $derived.by(() => {
		let m = 0;
		for (const s of series.samples) if (s.weightG > m) m = s.weightG;
		// Keep the planned line in view (with a little headroom above it)
		// even when the pour fell short of it.
		if (plannedMax > 0) m = Math.max(m, plannedMax * 1.04);
		// Round up to a clean 50 g step.
		return Math.max(50, Math.ceil(m / 50) * 50);
	});

	const maxFlow = $derived.by(() => {
		let m = 0;
		for (const s of series.samples) {
			const f = s.flowGS;
			if (f != null && f > m) m = f;
		}
		return Math.max(4, Math.ceil(m));
	});

	/** The in-chart legend shows only when a mark carries a target. */
	const showLegend = $derived(legend && plannedMax > 0);
	const padT = $derived(showLegend ? PAD_T_LEGEND : PAD_T);

	const plotW = W - PAD_L - PAD_R;
	const plotH = $derived(height - padT - PAD_B);

	const xAt = (tMs: number): number => PAD_L + (tMs / maxTimeMs) * plotW;
	const yAtW = (g: number): number =>
		padT + (1 - Math.min(1, Math.max(0, g / maxWeight))) * plotH;
	const yAtF = (f: number): number =>
		padT + (1 - Math.min(1, Math.max(0, f / maxFlow))) * plotH;

	function pathFor(pick: (s: BrewSeries['samples'][number]) => number | null): string {
		const segs: string[] = [];
		let drawing = false;
		for (const s of series.samples) {
			const v = pick(s);
			if (v == null || !Number.isFinite(v)) {
				drawing = false;
				continue;
			}
			segs.push(`${drawing ? 'L' : 'M'} ${xAt(s.elapsedMs).toFixed(1)} ${v.toFixed(1)}`);
			drawing = true;
		}
		return segs.join(' ');
	}

	const weightPath = $derived(pathFor((s) => yAtW(s.weightG)));
	const flowPath = $derived(pathFor((s) => (s.flowGS == null ? null : yAtF(s.flowGS))));

	/** Alternating stage bands from the recorded boundaries. */
	/** Where the series ends: the live clock, else the last sample. */
	const endT = $derived.by(() => {
		const lastSample = series.samples.length
			? series.samples[series.samples.length - 1].elapsedMs
			: 0;
		return extentMs > 0 ? Math.max(lastSample, extentMs) : series.samples.length ? lastSample : maxTimeMs;
	});

	const bands = $derived.by(() => {
		const marks = [...series.stageMarks].sort((a, b) => a.elapsedMs - b.elapsedMs);
		if (marks.length === 0) return [];
		const lastT = endT;
		return marks.map((m, i) => {
			const from = m.elapsedMs;
			const to = i + 1 < marks.length ? marks[i + 1].elapsedMs : lastT;
			return { from, to, index: Number(m.stepIndex) };
		});
	});

	/** The planned-water staircase (empty when no mark has a target). */
	const plannedPath = $derived(
		staircasePath(plannedStaircase(series.stageMarks, endT), xAt, yAtW)
	);

	const ariaLabel = $derived(
		plannedMax > 0
			? `Guided brew weight curve with stage boundaries and the recipe's planned water targets, up to ${Math.round(plannedMax)} g`
			: 'Guided brew weight curve with stage boundaries'
	);

	/** Time-axis ticks on the 30 s grid (label every other for >4 min). */
	const ticks = $derived.by(() => {
		const step = maxTimeMs > 240_000 ? 60_000 : 30_000;
		const out: number[] = [];
		for (let t = 0; t <= maxTimeMs; t += step) out.push(t);
		return out;
	});

	function tickLabel(ms: number): string {
		const total = Math.round(ms / 1000);
		return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
	}
</script>

<svg
	class="bsc"
	viewBox="0 0 {W} {height}"
	preserveAspectRatio="none"
	role="img"
	aria-label={ariaLabel}
>
	<!-- Alternating stage bands -->
	{#each bands as b (b.index)}
		{#if b.index % 2 === 1}
			<rect
				class="bsc-band"
				x={xAt(b.from)}
				y={padT}
				width={Math.max(0, xAt(b.to) - xAt(b.from))}
				height={plotH}
			/>
		{/if}
		{#if b.from > 0}
			<line class="bsc-bound" x1={xAt(b.from)} y1={padT} x2={xAt(b.from)} y2={padT + plotH} />
		{/if}
	{/each}

	<!-- Weight gridlines + labels (left axis) -->
	{#each [0.5, 1] as frac (frac)}
		<line
			class="bsc-grid"
			x1={PAD_L}
			y1={yAtW(maxWeight * frac)}
			x2={W - PAD_R}
			y2={yAtW(maxWeight * frac)}
		/>
		<text class="bsc-ylabel" x={PAD_L - 6} y={yAtW(maxWeight * frac) + 3}>
			{Math.round(maxWeight * frac)}
		</text>
	{/each}

	<!-- Time axis -->
	{#each ticks as t (t)}
		<text class="bsc-xlabel" x={xAt(t)} y={height - 6}>{tickLabel(t)}</text>
	{/each}

	<path class="bsc-flow" d={flowPath} fill="none" />
	{#if plannedPath}
		<path class="bsc-planned" d={plannedPath} fill="none" />
	{/if}
	<path class="bsc-weight" d={weightPath} fill="none" />

	{#if plannedPath && showLegend}
		<!-- Legend: planned (dashed) vs poured (solid), in its own strip
		     above the plot so it never covers the curves. -->
		<g class="bsc-legend" aria-hidden="true">
			<line class="bsc-planned" x1={PAD_L + 2} y1={11} x2={PAD_L + 20} y2={11} />
			<text class="bsc-legend-text" x={PAD_L + 25} y={14}>planned</text>
			{#if series.samples.length > 0}
				<!-- A scale-less live session has no poured curve to label. -->
				<line class="bsc-weight" x1={PAD_L + 74} y1={11} x2={PAD_L + 92} y2={11} />
				<text class="bsc-legend-text" x={PAD_L + 97} y={14}>poured</text>
			{/if}
		</g>
	{/if}
</svg>

<style>
	.bsc {
		display: block;
		width: 100%;
		height: auto;
		background: var(--bg-page);
		border-radius: var(--radius-sm);
	}
	.bsc-band {
		fill: rgba(var(--tint-rgb), 0.04);
	}
	.bsc-bound {
		stroke: rgba(var(--tint-rgb), 0.16);
		stroke-width: 1;
		stroke-dasharray: 2 3;
		vector-effect: non-scaling-stroke;
	}
	.bsc-grid {
		stroke: rgba(var(--tint-rgb), 0.08);
		stroke-width: 1;
		vector-effect: non-scaling-stroke;
	}
	.bsc-ylabel {
		font-family: var(--font-mono);
		font-size: 9px;
		fill: rgba(var(--tint-rgb), 0.45);
		text-anchor: end;
	}
	.bsc-xlabel {
		font-family: var(--font-mono);
		font-size: 9px;
		fill: rgba(var(--tint-rgb), 0.45);
		text-anchor: middle;
	}
	.bsc-weight {
		stroke: var(--tel-weight);
		stroke-width: 2;
		stroke-linecap: round;
		stroke-linejoin: round;
		vector-effect: non-scaling-stroke;
	}
	.bsc-planned {
		stroke: rgba(var(--tint-rgb), 0.5);
		stroke-width: 1.4;
		stroke-dasharray: 5 4;
		stroke-linejoin: miter;
		vector-effect: non-scaling-stroke;
	}
	.bsc-legend-text {
		font-family: var(--font-mono);
		font-size: 9px;
		fill: rgba(var(--tint-rgb), 0.55);
	}
	.bsc-flow {
		stroke: var(--tel-flow);
		stroke-width: 1.2;
		opacity: 0.75;
		stroke-linecap: round;
		stroke-linejoin: round;
		vector-effect: non-scaling-stroke;
	}
</style>
