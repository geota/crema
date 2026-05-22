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
	import { sampleCurve, type ProfileSegment } from '$lib/profiles';
	import { theme } from '$lib/theme.svelte';

	let {
		series,
		goalSegments,
		showFlowCurve = true,
		smoothPressure = true,
		telemetryRateHz = 0
	}: {
		/** The buffered shot-telemetry series, oldest first. */
		series: readonly TelemetrySample[];
		/**
		 * The active profile's segments — the goal line is the pressure target
		 * curve sampled from these. `undefined` when no profile is active, in
		 * which case the goal series is omitted.
		 */
		goalSegments?: readonly ProfileSegment[];
		/** Whether to plot the flow channel — the `showFlowCurve` setting (D3). */
		showFlowCurve?: boolean;
		/** Whether to smooth the pressure channel — the `smoothPressure` setting (D3). */
		smoothPressure?: boolean;
		/**
		 * The chart's display sample rate, Hz — the `telemetryRateHz` setting
		 * (D3). The buffered series is decimated to at most this many samples
		 * per second before plotting; `0`/unset plots every sample.
		 */
		telemetryRateHz?: number;
	} = $props();

	/** The rolling-mean window (sample count) the `smoothPressure` setting uses. */
	const PRESSURE_SMOOTH_WINDOW = 5;

	/**
	 * Decimate the buffered series to the `telemetryRateHz` display rate —
	 * keep a sample only when at least `1000 / rate` ms have passed since the
	 * last kept one. The final sample is always kept so the now-marker and
	 * end-dots sit on the live value. A `0`/non-finite rate keeps everything.
	 */
	function decimate(samples: readonly TelemetrySample[]): readonly TelemetrySample[] {
		const rate = telemetryRateHz ?? 0;
		if (!Number.isFinite(rate) || rate <= 0 || samples.length < 3) return samples;
		const minGapMs = 1000 / rate;
		const out: TelemetrySample[] = [];
		let lastMs = -Infinity;
		for (let i = 0; i < samples.length; i++) {
			const s = samples[i];
			if (i === samples.length - 1 || s.elapsed - lastMs >= minGapMs) {
				out.push(s);
				lastMs = s.elapsed;
			}
		}
		return out;
	}

	/** The default x-window, seconds, before any telemetry has arrived. */
	const BASE_WINDOW_SEC = 60;

	/** Resolve a CSS custom property to a concrete colour string. */
	function cssVar(name: string): string {
		if (typeof window === 'undefined') return '#888';
		return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#888';
	}

	/**
	 * The pressure-goal line, sampled from the active profile's segments. Only
	 * pressure-priority segments contribute a target; a flow-priority segment
	 * holds the previous pressure target (the DE1 has no pressure goal there).
	 * Returns `null` when no profile is active so the goal series is dropped.
	 */
	const goalCurve = $derived.by(() => {
		if (!goalSegments || goalSegments.length === 0) return null;
		// Sample only the pressure target: a flow-priority segment damps to the
		// last pressure value so the goal line holds flat through it.
		let lastPressure = 0;
		const pressureSegments = goalSegments.map((s) => {
			if (s.mode === 'pressure') {
				lastPressure = s.target;
				return s;
			}
			return { ...s, target: lastPressure };
		});
		return sampleCurve(pressureSegments);
	});

	/** The goal pressure at elapsed time `t`, linearly interpolated. */
	function goalAt(t: number): number | null {
		const c = goalCurve;
		if (!c || c.time.length === 0) return null;
		if (t <= c.time[0]) return c.value[0];
		const last = c.time.length - 1;
		if (t >= c.time[last]) return c.value[last];
		for (let i = 1; i <= last; i++) {
			if (t <= c.time[i]) {
				const span = c.time[i] - c.time[i - 1];
				const u = span === 0 ? 0 : (t - c.time[i - 1]) / span;
				return c.value[i - 1] + u * (c.value[i] - c.value[i - 1]);
			}
		}
		return c.value[last];
	}

	/**
	 * The pressure value at `i`, optionally smoothed (D3 — `smoothPressure`).
	 * Smoothing is a trailing rolling mean over {@link PRESSURE_SMOOTH_WINDOW}
	 * samples — calmer than the raw ~25 Hz trace, no lookahead.
	 */
	function pressureAt(samples: readonly TelemetrySample[], i: number): number | null {
		const raw = samples[i].pressure;
		if (raw == null) return null;
		if (!smoothPressure) return raw;
		let sum = 0;
		let n = 0;
		for (let j = Math.max(0, i - PRESSURE_SMOOTH_WINDOW + 1); j <= i; j++) {
			const v = samples[j].pressure;
			if (v != null) {
				sum += v;
				n++;
			}
		}
		return n > 0 ? sum / n : raw;
	}

	/**
	 * Build the [x, pressure, flow, temp, weight, goal] column arrays uPlot
	 * wants. Pressure and flow keep their raw value; temperature and weight are
	 * divided by 10 so they ride the shared scale (and read back ×10 on the
	 * right axis). Pressure is smoothed when `smoothPressure` is on; the flow
	 * column is fed all-`null` when `showFlowCurve` is off, hiding the channel.
	 * The input is first decimated to the `telemetryRateHz` display rate.
	 */
	function toData(input: readonly TelemetrySample[]): uPlot.AlignedData {
		const samples = decimate(input);
		const xs: number[] = [];
		const pressure: (number | null)[] = [];
		const flow: (number | null)[] = [];
		const temp: (number | null)[] = [];
		const weight: (number | null)[] = [];
		const goal: (number | null)[] = [];
		const setPressure: (number | null)[] = [];
		const setFlow: (number | null)[] = [];
		const setTemp: (number | null)[] = [];
		for (let i = 0; i < samples.length; i++) {
			const s = samples[i];
			const t = s.elapsed / 1000;
			xs.push(t);
			pressure.push(pressureAt(samples, i));
			flow.push(showFlowCurve ? (s.flow ?? null) : null);
			temp.push(s.temp == null ? null : s.temp / 10);
			weight.push(s.weight == null ? null : s.weight / 10);
			goal.push(goalAt(t));
			// Live setpoint dashed overlay — what the firmware was aiming for
			// at each AC half-cycle. Distinct from `goal` (profile-derived)
			// because the DE1 interpolates / ramps / clamps internally per
			// frame logic. Same scale-divisor pattern as the measured channels
			// (temp /10; pressure/flow native). Historical shots stored
			// before these fields existed have them as `undefined`; coerce to
			// null so uPlot draws a gap rather than throwing.
			setPressure.push(s.setGroupPressure ?? null);
			setFlow.push(showFlowCurve ? (s.setGroupFlow ?? null) : null);
			setTemp.push(s.setHeadTemp == null ? null : s.setHeadTemp / 10);
		}
		return [xs, pressure, flow, temp, weight, goal, setPressure, setFlow, setTemp];
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

	// `bind:this` target read inside the create `$effect` — `$state` so the
	// effect re-runs once the element is attached. `chart` / `resizeObs` are
	// purely imperative handles, so they stay plain `let`.
	let plotEl = $state<HTMLDivElement>();
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
		// Canvas strokes can't resolve `var()` — resolve the chart tokens to
		// concrete colours here. `cssVar` re-reads on every rebuild, so a theme
		// flip (which triggers `redraw()` below) picks up the new values.
		const gridColor = cssVar('--chart-grid');
		const labelColor = cssVar('--chart-axis-label');
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
					stroke: () => cssVar('--chart-axis-label'),
					width: 1.5,
					dash: [4, 4],
					points: { show: false }
				},
				// Live setpoint overlays — color-keyed to each measured curve,
				// dashed to read as "target". Width 1.2 to read as background
				// (the solid measured curves stay primary). Setpoints come
				// straight from `ShotSample.set_*` per Telemetry event.
				{
					scale: 'y',
					stroke: () => cssVar('--tel-pressure'),
					width: 1.2,
					dash: [3, 3],
					alpha: 0.6,
					points: { show: false }
				},
				{
					scale: 'y',
					stroke: () => cssVar('--tel-flow'),
					width: 1.2,
					dash: [3, 3],
					alpha: 0.6,
					points: { show: false }
				},
				{
					scale: 'y',
					stroke: () => cssVar('--tel-temp'),
					width: 1.2,
					dash: [3, 3],
					alpha: 0.6,
					points: { show: false }
				}
			],
			plugins: [markerPlugin()]
		};
	}

	// Create the chart once on mount. The whole opts + initial-data construction
	// is `untrack`ed: `toData` transitively reads `series`, `telemetryRateHz`,
	// `showFlowCurve` and `smoothPressure`, so without `untrack` toggling any of
	// those settings (or switching profile) would tear down and rebuild the
	// entire uPlot instance. Creation must depend on nothing reactive — the
	// `$derived` data + `setData` effect below own every live update instead.
	$effect(() => {
		// Read the `bind:this` target tracked so the effect runs once the
		// element is attached; everything after is `untrack`ed.
		const el = plotEl;
		if (!el) return;
		untrack(() => {
			const w = Math.max(1, el.clientWidth);
			const h = Math.max(1, el.clientHeight);
			chart = new uPlot(buildOpts(w, h), toData(series), el);

			// Track the panel's live width AND height so the chart fills it and
			// follows the Quick Sheet docking in / out.
			resizeObs = new ResizeObserver((entries) => {
				const cr = entries[0].contentRect;
				chart?.setSize({
					width: Math.max(1, cr.width),
					height: Math.max(1, cr.height)
				});
			});
			resizeObs.observe(el);
		});

		return () => {
			resizeObs?.disconnect();
			resizeObs = null;
			chart?.destroy();
			chart = null;
		};
	});

	/**
	 * The plotted column data — re-derives whenever the buffered series or any
	 * telemetry-display setting (`telemetryRateHz`, `showFlowCurve`,
	 * `smoothPressure`) changes. Kept as a `$derived` so the `setData` effect
	 * below depends only on this value, never on the chart-creation path.
	 */
	const data = $derived(toData(series));

	// Push new telemetry into the existing chart instance. `setData` re-runs
	// the x/y `range` callbacks, so the x-window auto-grows past 60 s and the
	// y-axis auto-grows with the data — no manual `setScale` needed.
	$effect(() => {
		chart?.setData(data);
	});

	// Repaint on theme flip. Axis/grid colours are baked into `buildOpts` at
	// creation, so a `redraw()` alone won't refresh them — rebuild the instance
	// in place against the new tokens. `series` etc. are read untracked so this
	// effect depends only on `theme.current`.
	$effect(() => {
		theme.current;
		untrack(() => {
			const el = plotEl;
			if (!el || !chart) return;
			const w = Math.max(1, el.clientWidth);
			const h = Math.max(1, el.clientHeight);
			chart.destroy();
			chart = new uPlot(buildOpts(w, h), data, el);
		});
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
