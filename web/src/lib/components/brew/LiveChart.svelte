<script lang="ts">
	/**
	 * `LiveChart` — the 4-channel telemetry chart.
	 *
	 * A canvas uPlot chart: each of the four channels (pressure / flow / temp /
	 * weight) lives on its own uPlot **scale** with a fixed range, so no
	 * anchor-series hack is needed. The dashed pressure-goal line is a fifth
	 * series on the pressure scale.
	 *
	 * A small draw-hook plugin paints the design's chrome that uPlot's hidden
	 * y-axes cannot: the four horizontal grid lines (behind the series, via the
	 * `drawClear` hook) and, on top, the solid faded now-marker line plus a
	 * per-channel end-dot. uPlot's own x-axis still renders the dashed vertical
	 * grid and the elapsed-second labels.
	 *
	 * The chart fills its container — a `ResizeObserver` feeds the panel's live
	 * width *and* height into `uplot.setSize()`, so it tracks the panel as the
	 * Quick Sheet docks and never distorts the way the old SVG did.
	 */
	import { untrack } from 'svelte';
	import uPlot from 'uplot';
	import 'uplot/dist/uPlot.min.css';
	import type { TelemetrySample } from '$lib/state';

	let {
		series
	}: {
		/** The buffered shot-telemetry series, oldest first. */
		series: readonly TelemetrySample[];
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

	/** The four channel end-dots: [data column, scale, colour var, radius]. */
	const DOTS: readonly [number, string, string, number][] = [
		[1, 'pressure', '--tel-pressure', 4],
		[2, 'flow', '--tel-flow', 3],
		[3, 'temp', '--tel-temp', 3],
		[4, 'weight', '--tel-weight', 3]
	];

	/**
	 * uPlot draw plugin: the horizontal grid (behind the series) plus the
	 * now-marker line and per-channel end-dots (on top).
	 */
	function chromePlugin(): uPlot.Plugin {
		return {
			hooks: {
				// Behind the series: four horizontal grid lines, matching the
				// design's `[0.2, 0.4, 0.6, 0.8]` delimiters. uPlot's y-axes are
				// hidden, so their grid never renders — we paint it ourselves.
				drawClear: (u: uPlot) => {
					const ctx = u.ctx;
					const { left, top, width, height } = u.bbox;
					ctx.save();
					ctx.setLineDash([]);
					ctx.strokeStyle = 'rgba(244,237,224,0.05)';
					ctx.lineWidth = devicePixelRatio;
					for (const p of [0.2, 0.4, 0.6, 0.8]) {
						const y = Math.round(top + height * p) + 0.5;
						ctx.beginPath();
						ctx.moveTo(left, y);
						ctx.lineTo(left + width, y);
						ctx.stroke();
					}
					ctx.restore();
				},
				// On top: the now-marker line and the per-channel end-dots.
				draw: (u: uPlot) => {
					const n = u.data[0].length;
					if (n === 0) return;
					const ctx = u.ctx;
					const nowT = u.data[0][n - 1] as number;
					const cx = u.valToPos(nowT, 'x', true);
					const top = u.bbox.top;
					const bot = u.bbox.top + u.bbox.height;

					ctx.save();
					// Now-marker: a solid, slightly faded vertical line. uPlot
					// leaves the dashed x-grid's line-dash set on the context, so
					// reset it explicitly or the marker comes out dotted.
					ctx.setLineDash([]);
					ctx.beginPath();
					ctx.strokeStyle = cssVar('--copper-500');
					ctx.globalAlpha = 0.7;
					ctx.lineWidth = 1.5 * devicePixelRatio;
					ctx.moveTo(cx, top);
					ctx.lineTo(cx, bot);
					ctx.stroke();
					ctx.globalAlpha = 1;

					// One end-dot per channel, sitting on the now-marker.
					for (const [col, scale, varName, r] of DOTS) {
						const v = u.data[col][n - 1];
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

	/** Round-numbered second marks inside the window — design uses 10 s steps. */
	function timeSplits(max: number): number[] {
		const incr = max <= 90 ? 10 : max <= 180 ? 20 : 30;
		const out: number[] = [];
		for (let t = incr; t < max; t += incr) out.push(t);
		return out;
	}

	function buildOpts(w: number, h: number, win: number): uPlot.Options {
		const gridColor = 'rgba(244,237,224,0.05)';
		const labelColor = 'rgba(244,237,224,0.35)';
		return {
			width: w,
			height: h,
			padding: [8, 8, 4, 8],
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
					splits: (u) => timeSplits((u.scales.x.max ?? win) as number),
					values: (_u, splits) => splits.map((v) => `${v}s`)
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
			plugins: [chromePlugin()]
		};
	}

	// Create the chart once on mount. `series` is read untracked so a telemetry
	// tick doesn't tear down and rebuild the whole uPlot instance — the effect
	// below feeds updates into the live instance instead.
	$effect(() => {
		const w = Math.max(1, plotEl.clientWidth);
		const h = Math.max(1, plotEl.clientHeight);
		chart = new uPlot(
			buildOpts(w, h, untrack(() => windowSec)),
			toData(untrack(() => series)),
			plotEl
		);

		// Track the panel's live width AND height so the chart fills it and
		// follows the Quick Sheet docking in / out.
		resizeObs = new ResizeObserver((entries) => {
			const cr = entries[0].contentRect;
			chart?.setSize({
				width: Math.max(1, cr.width),
				height: Math.max(1, cr.height)
			});
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
</script>

<div class="livechart" bind:this={plotEl} aria-label="Live shot telemetry chart"></div>

<style>
	.livechart {
		width: 100%;
		height: 100%;
	}
	/* uPlot injects its own canvas; keep it from overflowing the panel. */
	.livechart :global(.u-wrap) {
		margin: 0 auto;
	}
</style>
