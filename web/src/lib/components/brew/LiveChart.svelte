<script lang="ts">
	/**
	 * `LiveChart` — the 4-channel telemetry chart.
	 *
	 * uPlot spike: ported from the hand-rolled SVG to a canvas uPlot chart.
	 * Each of the four channels (pressure / flow / temp / weight) lives on its
	 * own uPlot **scale** with a fixed range — uPlot supports multiple scales
	 * natively, so no anchor-series hack is needed (unlike Lightweight Charts).
	 * The dashed pressure-goal line is a fifth series on the pressure scale.
	 *
	 * The now-marker vertical line and the per-channel end-dots are drawn by a
	 * small `drawClear`/`draw`-hook plugin. The x-axis renders elapsed seconds
	 * with an "Ns" suffix via a custom axis `values` formatter.
	 *
	 * The chart is sized to its container with a `ResizeObserver` →
	 * `uplot.setSize()`, so it never distorts the way the SVG's
	 * `preserveAspectRatio="none"` did.
	 */
	import { untrack } from 'svelte';
	import uPlot from 'uplot';
	import 'uplot/dist/uPlot.min.css';
	import type { TelemetrySample } from '$lib/state';

	let {
		series,
		height = 220
	}: {
		/** The buffered shot-telemetry series, oldest first. */
		series: readonly TelemetrySample[];
		/** Panel height, px. */
		height?: number;
	} = $props();

	/**
	 * The time window, seconds. Starts at 60 s and grows to the shot's length
	 * so a long pull is never clipped — the same adaptive behaviour as
	 * `StaticShotChart`.
	 */
	const windowSec = $derived(
		series.length > 0
			? Math.max(60, series[series.length - 1].elapsedMs / 1000)
			: 60
	);

	/** Resolve a CSS custom property to a concrete colour string. */
	function cssVar(name: string): string {
		if (typeof window === 'undefined') return '#888';
		return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#888';
	}

	/**
	 * The static, illustrative pressure-goal line: a 3-bar pre-infusion floor
	 * ramping to 9 bar. Purely cosmetic until the profile model lands.
	 */
	function goalAt(t: number): number {
		if (t < 8) return 3;
		if (t < 11) return 3 + ((t - 8) / 3) * 6;
		return 9;
	}

	/** Build the [x, pressure, flow, temp, weight, goal] column arrays uPlot wants. */
	function toData(samples: readonly TelemetrySample[]): uPlot.AlignedData {
		const xs: number[] = [];
		const pressure: (number | null)[] = [];
		const flow: (number | null)[] = [];
		const temp: (number | null)[] = [];
		const weight: (number | null)[] = [];
		const goal: number[] = [];
		for (const s of samples) {
			const t = s.elapsedMs / 1000;
			xs.push(t);
			pressure.push(s.pressure ?? null);
			flow.push(s.flow ?? null);
			temp.push(s.temp ?? null);
			weight.push(s.weightG ?? null);
			goal.push(goalAt(t));
		}
		return [xs, pressure, flow, temp, weight, goal];
	}

	/** uPlot draw plugin: now-marker vertical line + per-channel end-dots. */
	function markerPlugin(): uPlot.Plugin {
		return {
			hooks: {
				draw: (u: uPlot) => {
					const n = u.data[0].length;
					if (n === 0) return;
					const ctx = u.ctx;
					const nowT = u.data[0][n - 1] as number;
					const cx = u.valToPos(nowT, 'x', true);
					const top = u.bbox.top;
					const bot = u.bbox.top + u.bbox.height;

					ctx.save();
					// now-marker line
					ctx.beginPath();
					ctx.strokeStyle = cssVar('--copper-500');
					ctx.globalAlpha = 0.7;
					ctx.lineWidth = 1.5 * devicePixelRatio;
					ctx.moveTo(cx, top);
					ctx.lineTo(cx, bot);
					ctx.stroke();
					ctx.globalAlpha = 1;

					// end-dots: pressure (r4) and flow (r3)
					const dots: [number, string, string, number][] = [
						[1, 'pressure', '--tel-pressure', 4],
						[2, 'flow', '--tel-flow', 3]
					];
					for (const [si, scale, varName, r] of dots) {
						const v = u.data[si][n - 1];
						if (v == null) continue;
						const cy = u.valToPos(v as number, scale, true);
						ctx.beginPath();
						ctx.fillStyle = cssVar(varName);
						ctx.arc(cx, cy, r * devicePixelRatio, 0, Math.PI * 2);
						ctx.fill();
					}
					ctx.restore();
				}
			}
		};
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
					values: (_u, splits) =>
						splits.map((v) => (v === 0 ? '' : `${Math.round(v)}s`)),
					splits: (u) => {
						const max = (u.scales.x.range as unknown as [number, number])[1];
						return [0.2, 0.4, 0.6, 0.8].map((p) => Math.round(max * p));
					}
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
				{
					scale: 'pressure',
					stroke: () => cssVar('--tel-pressure'),
					width: 2.6,
					points: { show: false }
				},
				{
					scale: 'flow',
					stroke: () => cssVar('--tel-flow'),
					width: 2.2,
					points: { show: false }
				},
				{
					scale: 'temp',
					stroke: () => cssVar('--tel-temp'),
					width: 2.2,
					points: { show: false }
				},
				{
					scale: 'weight',
					stroke: () => cssVar('--tel-weight'),
					width: 2.2,
					points: { show: false }
				},
				{
					scale: 'pressure',
					stroke: 'rgba(244,237,224,0.28)',
					width: 1.5,
					dash: [4, 4],
					points: { show: false }
				}
			],
			plugins: [markerPlugin()]
		};
	}

	// Create the chart once on mount. `series`/`height` are read untracked so a
	// telemetry tick doesn't tear down and rebuild the whole uPlot instance —
	// the dedicated effects below feed updates into the live instance instead.
	$effect(() => {
		const w = Math.max(1, plotEl.clientWidth);
		const h = Math.max(1, untrack(() => height));
		chart = new uPlot(
			buildOpts(w, h, untrack(() => windowSec)),
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

	// Push new telemetry into the existing chart instance and grow the x-window
	// to the shot's length so a long pull is never clipped.
	$effect(() => {
		const data = toData(series);
		const win = windowSec;
		if (!chart) return;
		chart.setData(data, false);
		chart.setScale('x', { min: 0, max: win });
	});

	// React to a height prop change without rebuilding the whole chart.
	$effect(() => {
		chart?.setSize({ width: chart.width, height });
	});
</script>

<div
	class="livechart"
	style="height:{height}px"
	bind:this={plotEl}
	aria-label="Live shot telemetry chart"
></div>

<style>
	.livechart {
		width: 100%;
	}
	/* uPlot injects its own canvas; keep it from overflowing the panel. */
	.livechart :global(.u-wrap) {
		margin: 0 auto;
	}
</style>
