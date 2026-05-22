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
		showPressure = true,
		showResistance = false,
		showFlow = true,
		showVolume = false,
		showHeadTemp = false,
		showMixTemp = false,
		showWeight = true,
		showWeightFlow = false,
		smoothPressure = true,
		telemetryRateHz = 0,
		pinnedTimeSec = null,
		onPin
	}: {
		/** The buffered shot-telemetry series, oldest first. */
		series: readonly TelemetrySample[];
		/**
		 * The active profile's segments — the goal line is the pressure target
		 * curve sampled from these. `undefined` when no profile is active, in
		 * which case the goal series is omitted.
		 */
		goalSegments?: readonly ProfileSegment[];
		/** Plot the pressure line. */
		showPressure?: boolean;
		/** Plot the puck-resistance secondary line (paired with pressure). */
		showResistance?: boolean;
		/** Plot the flow line. */
		showFlow?: boolean;
		/** Plot the dispensed-volume secondary line (paired with flow). */
		showVolume?: boolean;
		/** Plot the head-temperature line. */
		showHeadTemp?: boolean;
		/** Plot the mix-temperature secondary line (paired with head temp). */
		showMixTemp?: boolean;
		/** Plot the scale-weight line. */
		showWeight?: boolean;
		/** Plot the scale-flow secondary line in g/s (paired with weight). */
		showWeightFlow?: boolean;
		/** Smooth the pressure curve (the rolling-mean window). */
		smoothPressure?: boolean;
		/**
		 * The chart's display sample rate, Hz — the `telemetryRateHz` setting
		 * (D3). The buffered series is decimated to at most this many samples
		 * per second before plotting; `0`/unset plots every sample.
		 */
		telemetryRateHz?: number;
		/**
		 * Elapsed-time (seconds) of a pinned moment, drawn as a copper
		 * vertical marker over the live curves. `null` = no pin. The
		 * dashboard owns the state; the chart only renders the marker and
		 * fires {@link onPin} on click.
		 */
		pinnedTimeSec?: number | null;
		/**
		 * Click-on-chart handler: fires with the elapsed-time (seconds) the
		 * user clicked, clamped to `[0, last-sample]`. Wire to the
		 * dashboard's pin state.
		 */
		onPin?: (timeSec: number) => void;
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
	 * column is fed all-`null` when `showFlow` is off, hiding the channel.
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
		// Secondary channels — paired siblings of the primaries above. Each
		// gated on its toggle; an off toggle fills the column with null so
		// uPlot draws nothing.
		const resistance: (number | null)[] = [];
		const volume: (number | null)[] = [];
		const mixTemp: (number | null)[] = [];
		const weightFlow: (number | null)[] = [];
		for (let i = 0; i < samples.length; i++) {
			const s = samples[i];
			const t = s.elapsed / 1000;
			xs.push(t);
			pressure.push(showPressure ? pressureAt(samples, i) : null);
			flow.push(showFlow ? (s.flow ?? null) : null);
			temp.push(showHeadTemp ? (s.temp == null ? null : s.temp / 10) : null);
			weight.push(showWeight ? (s.weight == null ? null : s.weight / 10) : null);
			goal.push(goalAt(t));
			// Live setpoint dashed overlay — what the firmware was aiming for
			// at each AC half-cycle. Distinct from `goal` (profile-derived)
			// because the DE1 interpolates / ramps / clamps internally per
			// frame logic. Same scale-divisor pattern as the measured channels
			// (temp /10; pressure/flow native). Historical shots stored
			// before these fields existed have them as `undefined`; coerce to
			// null so uPlot draws a gap rather than throwing. Each setpoint
			// follows its primary channel's toggle.
			setPressure.push(showPressure ? (s.setGroupPressure ?? null) : null);
			setFlow.push(showFlow ? (s.setGroupFlow ?? null) : null);
			setTemp.push(
				showHeadTemp ? (s.setHeadTemp == null ? null : s.setHeadTemp / 10) : null
			);
			// Secondary lines — each rides the shared y scale:
			// • resistance × 5 then clamp 10 — raw values are typically 0.1–2
			//   (occasional channeling spike to 5), which would crowd the
			//   bottom of the chart. The chart shows the SHAPE; the card
			//   reads the raw value. See the Quick Sheet's "Resistance ×5"
			//   sub-label;
			// • volume / mix divide by 10 like their primary's scale (60 mL →
			//   6 on plot; 90 °C → 9 on plot);
			// • weight flow rides native (g/s ≈ mL/s).
			const r = s.resistance;
			resistance.push(showResistance && r != null ? Math.min(10, Math.max(0, r * 5)) : null);
			const v = s.dispensedVolume;
			volume.push(showVolume && v != null ? v / 10 : null);
			mixTemp.push(showMixTemp && s.mixTemp != null ? s.mixTemp / 10 : null);
			const wf = s.weightFlow;
			weightFlow.push(showWeightFlow && wf != null ? wf : null);
		}
		return [
			xs,
			pressure,
			flow,
			temp,
			weight,
			goal,
			setPressure,
			setFlow,
			setTemp,
			resistance,
			volume,
			mixTemp,
			weightFlow
		];
	}

	/** The four channel end-dots: [data column, colour var, radius]. */
	const DOTS: readonly [number, string, number][] = [
		[1, '--tel-pressure', 4],
		[2, '--tel-flow', 3],
		[3, '--tel-temp', 3],
		[4, '--tel-weight', 3]
	];

	/**
	 * Closure-accessible mirror of `pinnedTimeSec` for the marker plugin's
	 * draw hook, which runs inside the uPlot instance (no Svelte reactivity).
	 * Updated by the effect below; a change kicks `chart.redraw()` so the
	 * pin marker repaints without rebuilding the chart.
	 */
	let pluginPinnedT: number | null = null;

	/** uPlot draw plugin: the now-marker, the pin-marker, per-channel end-dots. */
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

					// Pin-marker: a brighter vertical line at the pinned moment,
					// with the same per-channel dots so the user sees the values
					// the cards are showing. Only drawn when set.
					if (pluginPinnedT !== null) {
						const px = u.valToPos(pluginPinnedT, 'x', true);
						ctx.beginPath();
						ctx.strokeStyle = cssVar('--copper-500');
						ctx.lineWidth = 2 * devicePixelRatio;
						ctx.moveTo(px, top);
						ctx.lineTo(px, bot);
						ctx.stroke();
						// Locate the nearest sample column for the dots.
						const xs = u.data[0] as readonly number[];
						let bestI = 0;
						let bestD = Math.abs(xs[0] - pluginPinnedT);
						for (let i = 1; i < xs.length; i++) {
							const d = Math.abs(xs[i] - pluginPinnedT);
							if (d < bestD) {
								bestD = d;
								bestI = i;
							}
						}
						for (const [col, varName, r] of DOTS) {
							const v = u.data[col][bestI];
							if (v == null) continue;
							const cy = u.valToPos(v as number, 'y', true);
							ctx.beginPath();
							ctx.fillStyle = cssVar(varName);
							ctx.arc(px, cy, (r + 1) * devicePixelRatio, 0, Math.PI * 2);
							ctx.fill();
							// White ring to distinguish from the now-dot.
							ctx.beginPath();
							ctx.strokeStyle = cssVar('--bg-page');
							ctx.lineWidth = 1.5 * devicePixelRatio;
							ctx.arc(px, cy, (r + 1) * devicePixelRatio, 0, Math.PI * 2);
							ctx.stroke();
						}
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
				},
				// Secondary channel lines — paired siblings of the four
				// primaries above. Solid 1.5 px stroke; dash is reserved for
				// the goal/setpoint lines. Colour tokens follow the Palette B
				// "bold siblings" set in `tokens.css`.
				{
					scale: 'y',
					stroke: () => cssVar('--tel-pressure-2'),
					width: 1.5,
					points: { show: false }
				},
				{
					scale: 'y',
					stroke: () => cssVar('--tel-flow-2'),
					width: 1.5,
					points: { show: false }
				},
				{
					scale: 'y',
					stroke: () => cssVar('--tel-temp-2'),
					width: 1.5,
					points: { show: false }
				},
				{
					scale: 'y',
					stroke: () => cssVar('--tel-weight-2'),
					width: 1.5,
					points: { show: false }
				}
			],
			plugins: [markerPlugin()]
		};
	}

	// Create the chart once on mount. The whole opts + initial-data construction
	// is `untrack`ed: `toData` transitively reads `series`, `telemetryRateHz`,
	// `showFlow` and `smoothPressure`, so without `untrack` toggling any of
	// those settings (or switching profile) would tear down and rebuild the
	// entire uPlot instance. Creation must depend on nothing reactive — the
	// `$derived` data + `setData` effect below own every live update instead.
	$effect(() => {
		// Read the `bind:this` target tracked so the effect runs once the
		// element is attached; everything after is `untrack`ed.
		const el = plotEl;
		if (!el) return;
		let onChartClick: ((e: MouseEvent) => void) | null = null;
		let pinDragStart: ((e: PointerEvent) => void) | null = null;
		let pinDragMove: ((e: PointerEvent) => void) | null = null;
		let pinDragEnd: ((e: PointerEvent) => void) | null = null;
		let pinDragActive = false;
		untrack(() => {
			const w = Math.max(1, el.clientWidth);
			const h = Math.max(1, el.clientHeight);
			chart = new uPlot(buildOpts(w, h), toData(series), el);

			// Click + drag to pin: the user can either single-click anywhere
			// on the chart to drop a pin, or click-drag to slide an existing
			// pin to a different moment. Both flows feed `onPin(timeSec)` —
			// the dashboard just owns the state and re-routes it to the
			// readouts. Bound to the wrapping container (`el`) rather than
			// uPlot's `chart.over` because uPlot suppresses pointer events
			// on `.u-over` when `cursor.show: false` (which this chart sets).
			// `chart.bbox` is in canvas pixels — divide by devicePixelRatio
			// to land in CSS coords that `posToVal` accepts.
			const tFromClientX = (clientX: number): number | null => {
				if (!chart) return null;
				const rect = el.getBoundingClientRect();
				const padLeftCss = chart.bbox.left / devicePixelRatio;
				const localX = clientX - rect.left - padLeftCss;
				if (localX < 0) return null;
				const t = chart.posToVal(localX, 'x');
				return Number.isFinite(t) ? Math.max(0, t) : null;
			};
			onChartClick = (e: MouseEvent): void => {
				if (!onPin) return;
				const t = tFromClientX(e.clientX);
				if (t !== null) onPin(t);
			};
			el.addEventListener('click', onChartClick);

			// Drag tracking — when a pointer is held down on the chart, every
			// pointermove updates the pin. Captures the pointer so the drag
			// continues if the user wanders outside the chart bounds; releases
			// on pointerup / pointercancel.
			pinDragMove = (e: PointerEvent): void => {
				if (!pinDragActive || !onPin) return;
				const t = tFromClientX(e.clientX);
				if (t !== null) onPin(t);
			};
			pinDragEnd = (e: PointerEvent): void => {
				pinDragActive = false;
				try {
					el.releasePointerCapture(e.pointerId);
				} catch {
					// pointer wasn't actually captured (e.g. drag began outside);
					// the no-op release is harmless.
				}
			};
			pinDragStart = (e: PointerEvent): void => {
				if (e.button !== 0) return; // primary button only
				pinDragActive = true;
				try {
					el.setPointerCapture(e.pointerId);
				} catch {
					// Pointer capture isn't critical — drag still works without it,
					// just doesn't continue past the chart bounds.
				}
			};
			el.addEventListener('pointerdown', pinDragStart);
			el.addEventListener('pointermove', pinDragMove);
			el.addEventListener('pointerup', pinDragEnd);
			el.addEventListener('pointercancel', pinDragEnd);

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
			if (el && onChartClick) el.removeEventListener('click', onChartClick);
			if (el && pinDragStart) el.removeEventListener('pointerdown', pinDragStart);
			if (el && pinDragMove) el.removeEventListener('pointermove', pinDragMove);
			if (el && pinDragEnd) {
				el.removeEventListener('pointerup', pinDragEnd);
				el.removeEventListener('pointercancel', pinDragEnd);
			}
			chart?.destroy();
			chart = null;
		};
	});

	// Push the latest `pinnedTimeSec` into the plugin closure + redraw —
	// avoids rebuilding the chart on every pin move.
	$effect(() => {
		pluginPinnedT = pinnedTimeSec ?? null;
		chart?.redraw();
	});

	/**
	 * The plotted column data — re-derives whenever the buffered series or any
	 * telemetry-display setting (`telemetryRateHz`, `showFlow`,
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
