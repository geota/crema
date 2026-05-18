<script lang="ts">
	/**
	 * `StaticShotChart` — a static render of a stored shot's 4-channel telemetry
	 * curve, for the History page's `ShotDetail`.
	 *
	 * uPlot spike: the static counterpart of `brew/LiveChart`. Same four
	 * per-channel uPlot scales (pressure / flow / temp / weight) and the same
	 * `--tel-*` hues, but a finished shot is drawn whole — no now-marker or
	 * end-dots — and the x window stretches to the recorded shot's length.
	 */
	import { untrack } from 'svelte';
	import uPlot from 'uplot';
	import 'uplot/dist/uPlot.min.css';
	import type { TelemetrySample } from '$lib/state';

	let {
		series,
		height = 240
	}: {
		/** The stored shot-telemetry series, oldest first. */
		series: readonly TelemetrySample[];
		/** Panel height, px. */
		height?: number;
	} = $props();

	/** The time window, seconds — the recorded shot's length, min 28 s. */
	const windowSec = $derived(
		series.length > 0 ? Math.max(28, series[series.length - 1].elapsedMs / 1000) : 28
	);

	/** Resolve a CSS custom property to a concrete colour string. */
	function cssVar(name: string): string {
		if (typeof window === 'undefined') return '#888';
		return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#888';
	}

	/** Build the [x, pressure, flow, temp, weight] column arrays uPlot wants. */
	function toData(samples: readonly TelemetrySample[]): uPlot.AlignedData {
		const xs: number[] = [];
		const pressure: (number | null)[] = [];
		const flow: (number | null)[] = [];
		const temp: (number | null)[] = [];
		const weight: (number | null)[] = [];
		for (const s of samples) {
			xs.push(s.elapsedMs / 1000);
			pressure.push(s.pressure ?? null);
			flow.push(s.flow ?? null);
			temp.push(s.temp ?? null);
			weight.push(s.weightG ?? null);
		}
		return [xs, pressure, flow, temp, weight];
	}

	let plotEl: HTMLDivElement;
	let chart: uPlot | null = null;
	let resizeObs: ResizeObserver | null = null;

	function buildOpts(w: number, h: number, win: number): uPlot.Options {
		const gridColor = 'rgba(244,237,224,0.05)';
		const labelColor = 'rgba(244,237,224,0.35)';
		return {
			width: w,
			height: h,
			padding: [8, 8, 0, 8],
			cursor: { show: false },
			legend: { show: false },
			scales: {
				x: { time: false, range: [0, win] },
				pressure: { range: [0, 12] },
				flow: { range: [0, 8] },
				temp: { range: [85, 100] },
				weight: { range: [0, 50] }
			},
			axes: [
				{
					scale: 'x',
					stroke: labelColor,
					grid: { stroke: gridColor, width: 1, dash: [2, 4] },
					ticks: { show: false },
					font: '11px "JetBrains Mono", monospace',
					values: (_u, splits) => splits.map((v) => (v === 0 ? '' : `${Math.round(v)}s`)),
					splits: () => [0.2, 0.4, 0.6, 0.8].map((p) => Math.round(win * p))
				},
				{
					scale: 'pressure',
					show: false,
					grid: { stroke: gridColor, width: 1 },
					splits: (u) => {
						const [min, max] = u.scales.pressure.range as unknown as [number, number];
						return [0.2, 0.4, 0.6, 0.8].map((p) => min + (max - min) * p);
					}
				}
			],
			series: [
				{},
				{ scale: 'pressure', stroke: () => cssVar('--tel-pressure'), width: 2.6, points: { show: false } },
				{ scale: 'flow', stroke: () => cssVar('--tel-flow'), width: 2.2, points: { show: false } },
				{ scale: 'temp', stroke: () => cssVar('--tel-temp'), width: 2.2, points: { show: false } },
				{ scale: 'weight', stroke: () => cssVar('--tel-weight'), width: 2.2, points: { show: false } }
			]
		};
	}

	// Create the chart once on mount; `series`/`windowSec`/`height` are read
	// untracked so a prop change updates the live instance (effect below)
	// instead of rebuilding it.
	$effect(() => {
		const w = Math.max(1, plotEl.clientWidth);
		chart = new uPlot(
			buildOpts(w, untrack(() => height), untrack(() => windowSec)),
			toData(untrack(() => series)),
			plotEl
		);

		resizeObs = new ResizeObserver((entries) => {
			const cr = entries[0].contentRect;
			chart?.setSize({ width: Math.max(1, cr.width), height });
		});
		resizeObs.observe(plotEl);

		return () => {
			resizeObs?.disconnect();
			resizeObs = null;
			chart?.destroy();
			chart = null;
		};
	});

	// Re-apply data + x-window when the stored shot changes.
	$effect(() => {
		const data = toData(series);
		const win = windowSec;
		if (!chart) return;
		chart.setData(data, false);
		chart.setScale('x', { min: 0, max: win });
	});
</script>

<div
	class="staticchart"
	style="height:{height}px"
	bind:this={plotEl}
	aria-label="Stored shot telemetry chart"
></div>

<style>
	.staticchart {
		width: 100%;
	}
	.staticchart :global(.u-wrap) {
		margin: 0 auto;
	}
</style>
