<script lang="ts">
	/**
	 * `LiveChart` — the 4-channel telemetry chart, ported from the design's
	 * `LiveChart` in `ds/web-LiveChart.jsx` to a Svelte SVG component.
	 *
	 * Unlike the design's parametric mock, this is driven by the **real**
	 * buffered shot-telemetry series from `lib/state` — one polyline per
	 * channel (pressure / flow / temp / weight), each on its own fixed Y
	 * range, the same per-channel hues, grid, time labels, now-marker and end
	 * dots as the design. The dashed pressure-goal line is static / purely
	 * illustrative for now — the real per-segment goal needs the profile model,
	 * which a later porting step adds.
	 */
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

	// ── Geometry ─────────────────────────────────────────────────────────
	const W = 1200;
	const H = $derived(height);
	const padX = 24;
	const padY = 16;
	const innerW = W - padX * 2;
	const innerH = $derived(H - padY * 2);

	/** The time window, seconds — grows with the shot up to a 60 s cap. */
	const WINDOW = 60;

	/** Elapsed seconds of the latest sample (the "now" position). */
	const nowSec = $derived(
		series.length > 0 ? series[series.length - 1].elapsedMs / 1000 : 0
	);

	// ── Scales ───────────────────────────────────────────────────────────
	/** Map a time (s) to an x coordinate. */
	const xScale = (t: number): number => padX + (t / WINDOW) * innerW;
	/** Map a value to a y coordinate within `[vmin, vmax]`. */
	const yScale = (v: number, vmin: number, vmax: number): number =>
		padY + innerH - ((v - vmin) / (vmax - vmin)) * innerH;

	/**
	 * Build an SVG path over the series for one channel. `pick` reads the
	 * channel value from a sample; samples whose value is `null` are skipped.
	 */
	function buildPath(
		pick: (s: TelemetrySample) => number | null,
		vmin: number,
		vmax: number
	): string {
		let d = '';
		let started = false;
		for (const sample of series) {
			const v = pick(sample);
			if (v == null) {
				started = false;
				continue;
			}
			const x = xScale(sample.elapsedMs / 1000).toFixed(1);
			const y = yScale(v, vmin, vmax).toFixed(1);
			d += `${started ? 'L' : 'M'} ${x} ${y} `;
			started = true;
		}
		return d.trim();
	}

	// Per-channel paths — the same Y ranges as the design's LiveChart.
	const pressurePath = $derived(buildPath((s) => s.pressure, 0, 12));
	const flowPath = $derived(buildPath((s) => s.flow, 0, 8));
	const tempPath = $derived(buildPath((s) => s.temp, 85, 100));
	const weightPath = $derived(buildPath((s) => s.weightG, 0, 50));

	/**
	 * The static, illustrative pressure-goal line: a 3-bar pre-infusion floor
	 * ramping to 9 bar. Purely cosmetic until the profile model lands.
	 */
	const goalPath = $derived.by(() => {
		const goalAt = (t: number): number => {
			if (t < 8) return 3;
			if (t < 11) return 3 + ((t - 8) / 3) * 6;
			return 9;
		};
		const N = 120;
		let d = '';
		for (let i = 0; i < N; i++) {
			const t = (i / (N - 1)) * WINDOW;
			d += `${i === 0 ? 'M' : 'L'} ${xScale(t).toFixed(1)} ${yScale(goalAt(t), 0, 12).toFixed(1)} `;
		}
		return d.trim();
	});

	/** The last sample, for the now-marker end dots. */
	const last = $derived(series.length > 0 ? series[series.length - 1] : null);
</script>

<svg
	viewBox="0 0 {W} {H}"
	preserveAspectRatio="none"
	style="width:100%;height:100%"
	aria-label="Live shot telemetry chart"
>
	<!-- grid -->
	<g stroke="rgba(244,237,224,0.05)" stroke-width="1">
		{#each [0.2, 0.4, 0.6, 0.8] as p (p)}
			<line x1={padX} x2={W - padX} y1={padY + innerH * p} y2={padY + innerH * p} />
		{/each}
		{#each [10, 20, 30, 40, 50] as t (t)}
			<line y1={padY} y2={H - padY} x1={xScale(t)} x2={xScale(t)} stroke-dasharray="2 4" />
		{/each}
	</g>

	<!-- time labels -->
	<g fill="rgba(244,237,224,0.35)" font-family="JetBrains Mono, monospace" font-size="11">
		{#each [10, 20, 30, 40, 50] as t (t)}
			<text x={xScale(t)} y={H - 2} text-anchor="middle">{t}s</text>
		{/each}
	</g>

	<!-- static illustrative goal line -->
	<path
		d={goalPath}
		stroke="rgba(244,237,224,0.28)"
		stroke-width="1.5"
		stroke-dasharray="4 4"
		fill="none"
	/>

	{#if last}
		<!-- now marker -->
		<line
			x1={xScale(nowSec)}
			x2={xScale(nowSec)}
			y1={padY}
			y2={H - padY}
			stroke="var(--copper-500)"
			stroke-width="1.5"
			opacity="0.7"
		/>

		<!-- channels -->
		<path d={tempPath} stroke="var(--tel-temp)" stroke-width="2.2" fill="none" />
		<path d={weightPath} stroke="var(--tel-weight)" stroke-width="2.2" fill="none" />
		<path d={flowPath} stroke="var(--tel-flow)" stroke-width="2.2" fill="none" />
		<path d={pressurePath} stroke="var(--tel-pressure)" stroke-width="2.6" fill="none" />

		<!-- end dots -->
		<circle
			cx={xScale(nowSec)}
			cy={yScale(last.pressure, 0, 12)}
			r="4"
			fill="var(--tel-pressure)"
		/>
		<circle cx={xScale(nowSec)} cy={yScale(last.flow, 0, 8)} r="3" fill="var(--tel-flow)" />
	{/if}
</svg>
