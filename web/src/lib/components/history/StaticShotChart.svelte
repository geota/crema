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

	let {
		series,
		height = 360
	}: {
		/** The stored shot-telemetry series, oldest first. */
		series: readonly TelemetrySample[];
		/** Panel height, px. */
		height?: number;
	} = $props();

	/** The default x-window, seconds, for a very short recorded shot. */
	const BASE_WINDOW_SEC = 30;

	/** Resolve a CSS custom property to a concrete colour string. */
	function cssVar(name: string): string {
		if (typeof window === 'undefined') return '#888';
		return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#888';
	}

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
		for (const s of samples) {
			xs.push(s.elapsed / 1000);
			pressure.push(s.pressure ?? null);
			flow.push(s.flow ?? null);
			temp.push(s.temp == null ? null : s.temp / 10);
			weight.push(s.weight == null ? null : s.weight / 10);
		}
		return [xs, pressure, flow, temp, weight];
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

	/** Round-numbered second marks inside the window — design uses 10 s steps. */
	function timeSplits(max: number): number[] {
		const incr = max <= 90 ? 10 : max <= 180 ? 20 : 30;
		const out: number[] = [];
		for (let t = incr; t < max; t += incr) out.push(t);
		return out;
	}

	function buildOpts(w: number, h: number): uPlot.Options {
		// Canvas strokes can't resolve `var()` — resolve the chart tokens here.
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
				// The x-window fits the recorded shot's length.
				x: {
					time: false,
					range: (_u, _min, dataMax) => [
						0,
						Number.isFinite(dataMax)
							? Math.max(BASE_WINDOW_SEC, Math.ceil(dataMax))
							: BASE_WINDOW_SEC
					]
				},
				// One shared scale for all four channels — see `toData`.
				y: {
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
					font: yFont,
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
				}
			],
			plugins: [gridPlugin()]
		};
	}

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
