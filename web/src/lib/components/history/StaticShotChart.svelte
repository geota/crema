<script lang="ts">
	/**
	 * `StaticShotChart` — a static render of a stored shot's 4-channel telemetry
	 * curve, for the History page's `ShotDetail`.
	 *
	 * The static counterpart of `brew/LiveChart`: the same two locked y-axes
	 * (left ≈ 0–10 bar / ml·s, right ≈ 0–100 °C / g — pressure & flow plot
	 * raw, temperature & weight at value ÷ 10 on a shared scale), the same
	 * horizontal grid and round-numbered x labels. A finished shot is drawn
	 * whole, so there is no now-marker and no end-dots; the x-window fits the
	 * recorded shot's length.
	 */
	import { untrack } from 'svelte';
	import uPlot from 'uplot';
	import 'uplot/dist/uPlot.min.css';
	import type { TelemetrySample } from '$lib/state';
	import { theme } from '$lib/theme.svelte';
	import { getSettingsStore } from '$lib/settings';
	import { cssVar, xRangeFit, yRange, sharedAxes } from '$lib/components/charts/chartHelpers';

	let {
		series,
		height = 360
	}: {
		/** The stored shot-telemetry series, oldest first. */
		series: readonly TelemetrySample[];
		/** Panel height, px. */
		height?: number;
	} = $props();

	/**
	 * The settings store — used for the `showResistance` advanced toggle.
	 * Puck resistance is `pressure ÷ flow²`, already computed sample-by-sample
	 * (`TelemetrySample.resistance`) and skipped when flow is too small to
	 * divide by meaningfully. Matches the legacy de1app's History viewer
	 * "Resistance" overlay (`history_viewer.tcl:99-100`).
	 */
	const settings = getSettingsStore();

	/** Fallback x-window (seconds) for the axis ticks before the shot data lands;
	 *  the real window fits the recorded length via `xRangeFit()`. */
	const BASE_WINDOW_SEC = 30;

	/**
	 * Build the [x, pressure, flow, temp, weight] column arrays uPlot wants.
	 * Pressure and flow keep their raw value; temperature and weight are
	 * divided by 10 so they ride the shared scale (read back ×10 on the right
	 * axis) — identical to `LiveChart`.
	 */
	function toData(samples: readonly TelemetrySample[]): uPlot.AlignedData {
		const xs: number[] = [];
		const pressure: (number | null)[] = [];
		const flow: (number | null)[] = [];
		const temp: (number | null)[] = [];
		const weight: (number | null)[] = [];
		const resistance: (number | null)[] = [];
		// Goal / setpoint channels — what the profile *asked for*. Plotted as
		// dashed reference lines under the actuals so the shape of the shot
		// can be compared against the profile. Persisted on every sample
		// from the DE1's `setGroupPressure` / `setGroupFlow` / `setHeadTemp`
		// fields; older records pre-targets simply emit `null` here.
		const goalPressure: (number | null)[] = [];
		const goalFlow: (number | null)[] = [];
		const goalTemp: (number | null)[] = [];
		// Dispensed water — pump-side volume integral. Stays on the shared
		// 0-10 scale by reading it as `ml / 10` (so 60 ml plots as 6 — same
		// trick `temp / 10` uses).
		const water: (number | null)[] = [];
		const showResistance = settings.current.showResistance;
		for (const s of samples) {
			xs.push(s.elapsed / 1000);
			pressure.push(s.pressure ?? null);
			flow.push(s.flow ?? null);
			temp.push(s.temp == null ? null : s.temp / 10);
			weight.push(s.weight == null ? null : s.weight / 10);
			// Resistance — `null` when the toggle is off (so the line disappears
			// entirely) or when the core already skipped the sample for being
			// in the near-zero-flow region. Clamped to the chart's shared 0-10
			// scale so a low-flow spike doesn't blow the y-axis open. Old
			// records pre-resistance simply read `null` here too.
			// Resistance auto-switch: prefer the scale-derived
			// `resistanceWeight` per-sample, fall back to the DE1-flow
			// `resistance`. A shot pulled with no scale paired carries
			// only `resistance` (the per-sample fallback) and reads as
			// before; a scale-paired shot reads the truer extraction
			// signal — the legacy de1app TCL `P / scale_weight_rate²`
			// formula (`de1plus/gui.tcl:3414-3416`).
			const r = s.resistanceWeight ?? s.resistance;
			resistance.push(
				showResistance && r != null ? Math.min(10, Math.max(0, r)) : null
			);
			goalPressure.push(s.setGroupPressure ?? null);
			goalFlow.push(s.setGroupFlow ?? null);
			goalTemp.push(s.setHeadTemp == null ? null : s.setHeadTemp / 10);
			water.push(s.dispensedVolume == null ? null : s.dispensedVolume / 10);
		}
		return [xs, pressure, flow, temp, weight, resistance, goalPressure, goalFlow, goalTemp, water];
	}

	/** uPlot plugin: the four horizontal grid lines, behind the series. */
	function gridPlugin(): uPlot.Plugin {
		return {
			hooks: {
				drawClear: (u: uPlot) => {
					const ctx = u.ctx;
					const { left, top, width, height: h } = u.bbox;
					ctx.save();
					ctx.setLineDash([]);
					ctx.strokeStyle = cssVar('--chart-grid');
					ctx.lineWidth = devicePixelRatio;
					for (const p of [0.2, 0.4, 0.6, 0.8]) {
						const y = Math.round(top + h * p) + 0.5;
						ctx.beginPath();
						ctx.moveTo(left, y);
						ctx.lineTo(left + width, y);
						ctx.stroke();
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

	function buildOpts(w: number, h: number): uPlot.Options {
		// Canvas strokes can't resolve `var()` — resolve the axis/grid tokens
		// here; a theme flip rebuilds the chart against fresh values.
		const gridColor = cssVar('--chart-grid');
		const labelColor = cssVar('--chart-axis-label');
		return {
			width: w,
			height: h,
			padding: [8, 4, 4, 4],
			cursor: { show: false },
			legend: { show: false },
			scales: {
				x: { time: false, range: xRangeFit() },
				y: { range: yRange }
			},
			axes: sharedAxes({ gridColor, labelColor, baseWindowSec: BASE_WINDOW_SEC }),
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
					// Puck resistance — dashed, secondary line. Matches the
					// legacy de1app's History "Resistance" overlay
					// (`history_viewer.tcl:419-422`): a noticeable but secondary
					// trace, dashed, on the shared 0-10 scale.
					scale: 'y',
					stroke: () => cssVar('--tel-resistance'),
					width: 1.8,
					dash: [6, 4],
					points: { show: false }
				},
				// Goal / setpoint channels — dashed reference lines under
				// the actuals (matches the LiveChart). Faded alpha so the
				// actuals dominate the read; same dash pattern across the
				// three goals so "this is a target" reads consistently.
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
				// Dispensed water — solid 1.5 px in the flow-2 colour (same
				// pairing the live chart uses for WATER on the FLOW card).
				{
					scale: 'y',
					stroke: () => cssVar('--tel-flow-2'),
					width: 1.5,
					points: { show: false }
				}
			],
			plugins: [gridPlugin()]
		};
	}

	/**
	 * Repaint when the user toggles `showResistance` — `toData` re-reads
	 * the pref each call, so a fresh `setData` is enough to add or drop the
	 * dashed line without rebuilding the chart.
	 */
	$effect(() => {
		settings.current.showResistance;
		untrack(() => {
			chart?.setData(toData(series));
		});
	});

	// Create the chart once on mount; `series` / `height` are read untracked so
	// a prop change updates the live instance (effects below) instead of
	// rebuilding it.
	$effect(() => {
		// Read the `bind:this` target tracked so the effect runs once the
		// element is attached; everything after is `untrack`ed.
		const el = plotEl;
		if (!el) return;
		untrack(() => {
			const w = Math.max(1, el.clientWidth);
			chart = new uPlot(buildOpts(w, height), toData(series), el);

			// The callback re-reads the current `height` prop rather than
			// closing over the value captured at observer-creation — a height
			// change must not be reverted by the next resize.
			resizeObs = new ResizeObserver((entries) => {
				const cr = entries[0].contentRect;
				chart?.setSize({ width: Math.max(1, cr.width), height });
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

	// Re-apply data when the stored shot changes — `setData` re-runs the x/y
	// `range` callbacks, so both axes refit the new shot.
	$effect(() => {
		const data = toData(series);
		if (!chart) return;
		chart.setData(data);
	});

	// React to a height prop change without rebuilding the chart.
	$effect(() => {
		chart?.setSize({ width: chart.width, height });
	});

	// Repaint on theme flip — axis/grid colours are baked into `buildOpts` at
	// creation, so rebuild the instance against the new tokens.
	$effect(() => {
		theme.current;
		untrack(() => {
			const el = plotEl;
			if (!el || !chart) return;
			chart.destroy();
			chart = new uPlot(buildOpts(Math.max(1, el.clientWidth), height), toData(series), el);
		});
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
