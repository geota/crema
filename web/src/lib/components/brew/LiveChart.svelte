<script lang="ts">
	/**
	 * `LiveChart` — the 4-channel telemetry chart.
	 *
	 * ## Two axes, one scale
	 *
	 * The four channels share **one** uPlot y-scale so the two axes stay locked
	 * exactly 10× apart:
	 *
	 * - pressure (bar) and flow (ml/s) plot at their raw value;
	 * - temperature (°C) and weight (g) plot at value ÷ 10.
	 *
	 * The **left** axis labels the scale as-is (≈ 1–10, bar / ml·s); the
	 * **right** axis labels the same ticks ×10 (≈ 10–100, °C / g). The scale's
	 * `range` callback grows the top from 10 to whatever the data needs, so a
	 * mid-shot flow or pressure spike to 11–13 simply lifts both axes together.
	 *
	 * uPlot's left axis draws the horizontal grid; a small draw-hook plugin adds
	 * the solid faded now-marker and a per-channel end-dot. The x-axis renders
	 * the dashed vertical grid and round-numbered elapsed-second labels.
	 *
	 * The chart fills its container — a `ResizeObserver` feeds the panel's live
	 * width *and* height into `uplot.setSize()`.
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

	/** The default x-window, seconds, before any telemetry has arrived. */
	const BASE_WINDOW_SEC = 60;

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

	/**
	 * Build the [x, pressure, flow, temp, weight, goal] column arrays uPlot
	 * wants. Pressure and flow keep their raw value; temperature and weight are
	 * divided by 10 so they ride the shared scale (and read back ×10 on the
	 * right axis).
	 */
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
			temp.push(s.temp == null ? null : s.temp / 10);
			weight.push(s.weightG == null ? null : s.weightG / 10);
			goal.push(goalAt(t));
		}
		return [xs, pressure, flow, temp, weight, goal];
	}

	/** The four channel end-dots: [data column, colour var, radius]. */
	const DOTS: readonly [number, string, number][] = [
		[1, '--tel-pressure', 4],
		[2, '--tel-flow', 3],
		[3, '--tel-temp', 3],
		[4, '--tel-weight', 3]
	];

	/** uPlot draw plugin: the now-marker line and per-channel end-dots. */
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

					// One end-dot per channel, sitting on the now-marker. Every
					// channel rides the shared 'y' scale.
					for (const [col, varName, r] of DOTS) {
						const v = u.data[col][n - 1];
						if (v == null) continue;
						const cy = u.valToPos(v as number, 'y', true);
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

	function buildOpts(w: number, h: number): uPlot.Options {
		const gridColor = 'rgba(244,237,224,0.05)';
		const labelColor = 'rgba(244,237,224,0.35)';
		const yFont = '11px "JetBrains Mono", monospace';
		return {
			width: w,
			height: h,
			padding: [8, 4, 4, 4],
			cursor: { show: false },
			legend: { show: false },
			scales: {
				// The x-window starts at 60 s and grows to the shot's length —
				// a pull past a minute simply extends the axis to the right
				// rather than clipping. Data-driven, like the y scale below.
				x: {
					time: false,
					range: (_u, _min, dataMax) => [
						0,
						Number.isFinite(dataMax)
							? Math.max(BASE_WINDOW_SEC, Math.ceil(dataMax))
							: BASE_WINDOW_SEC
					]
				},
				// One shared scale for all four channels. The top floats from 10
				// upward so a mid-shot flow / pressure spike grows both axes.
				y: {
					// A hair of headroom (+0.3) keeps a peak that lands on a round
					// number off the very top edge — so its dot never half-clips.
					range: (_u, _min, dataMax) => [
						0,
						Number.isFinite(dataMax) ? Math.max(10, Math.ceil(dataMax + 0.3)) : 10
					]
				}
			},
			axes: [
				{
					scale: 'x',
					stroke: labelColor,
					grid: { stroke: gridColor, width: 1, dash: [2, 4] },
					ticks: { show: false },
					font: '11px "JetBrains Mono", monospace',
					splits: (u) => timeSplits((u.scales.x.max ?? BASE_WINDOW_SEC) as number),
					values: (_u, splits) => splits.map((v) => `${v}s`)
				},
				{
					// Left axis: the scale value as-is — bar (pressure) / ml·s (flow).
					scale: 'y',
					side: 3,
					stroke: labelColor,
					grid: { stroke: gridColor, width: 1 },
					ticks: { show: false },
					font: yFont,
					size: 34,
					values: (_u, splits) => splits.map((v) => `${v}`)
				},
				{
					// Right axis: the same ticks ×10 — °C (temp) / g (weight).
					scale: 'y',
					side: 1,
					stroke: labelColor,
					grid: { show: false },
					ticks: { show: false },
					font: yFont,
					size: 38,
					values: (_u, splits) => splits.map((v) => `${v * 10}`)
				}
			],
			series: [
				{},
				{
					scale: 'y',
					stroke: () => cssVar('--tel-pressure'),
					width: 2.6,
					points: { show: false }
				},
				{
					scale: 'y',
					stroke: () => cssVar('--tel-flow'),
					width: 2.2,
					points: { show: false }
				},
				{
					scale: 'y',
					stroke: () => cssVar('--tel-temp'),
					width: 2.2,
					points: { show: false }
				},
				{
					scale: 'y',
					stroke: () => cssVar('--tel-weight'),
					width: 2.2,
					points: { show: false }
				},
				{
					scale: 'y',
					stroke: 'rgba(244,237,224,0.28)',
					width: 1.5,
					dash: [4, 4],
					points: { show: false }
				}
			],
			plugins: [markerPlugin()]
		};
	}

	// Create the chart once on mount. `series` is read untracked so a telemetry
	// tick doesn't tear down and rebuild the whole uPlot instance — the effect
	// below feeds updates into the live instance instead.
	$effect(() => {
		const w = Math.max(1, plotEl.clientWidth);
		const h = Math.max(1, plotEl.clientHeight);
		chart = new uPlot(buildOpts(w, h), toData(untrack(() => series)), plotEl);

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

	// Push new telemetry into the existing chart instance. `setData` re-runs
	// the x/y `range` callbacks, so the x-window auto-grows past 60 s and the
	// y-axis auto-grows with the data — no manual `setScale` needed.
	$effect(() => {
		const data = toData(series);
		if (!chart) return;
		chart.setData(data);
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
