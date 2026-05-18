<script lang="ts">
	/**
	 * `StaticShotChart` — a static render of a stored shot's 4-channel telemetry
	 * curve, for the History page's `ShotDetail`.
	 *
	 * It reuses the **same SVG approach** as the live `brew/LiveChart`: one
	 * polyline per channel (pressure / flow / temp / weight), each on its own
	 * fixed Y range, the same per-channel hues and the same grid. The only
	 * difference from the live chart is that there is no animated "now" marker
	 * or end dots — a finished shot is drawn whole — and the time window
	 * stretches to fit the recorded shot's actual length.
	 */
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

	// ── Geometry ─────────────────────────────────────────────────────────
	const W = 1200;
	const H = $derived(height);
	const padX = 24;
	const padY = 18;
	const innerW = W - padX * 2;
	const innerH = $derived(H - padY * 2);

	/** The time window, seconds — the recorded shot's length, min 28 s. */
	const windowSec = $derived(
		series.length > 0
			? Math.max(28, series[series.length - 1].elapsedMs / 1000)
			: 28
	);

	/** Map a time (s) to an x coordinate. */
	const xScale = (t: number): number => padX + (t / windowSec) * innerW;
	/** Map a value to a y coordinate within `[vmin, vmax]`. */
	const yScale = (v: number, vmin: number, vmax: number): number =>
		padY + innerH - ((v - vmin) / (vmax - vmin)) * innerH;

	/**
	 * Build an SVG path over the series for one channel. `pick` reads the
	 * channel value; samples whose value is `null` are skipped.
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

	// Per-channel paths — the same Y ranges as the live LiveChart.
	const pressurePath = $derived(buildPath((s) => s.pressure, 0, 12));
	const flowPath = $derived(buildPath((s) => s.flow, 0, 8));
	const tempPath = $derived(buildPath((s) => s.temp, 85, 100));
	const weightPath = $derived(buildPath((s) => s.weightG, 0, 50));

	/** Time gridlines at sensible intervals across the window. */
	const ticks = $derived(
		[0.2, 0.4, 0.6, 0.8].map((p) => Math.round(windowSec * p))
	);
</script>

<svg
	viewBox="0 0 {W} {H}"
	preserveAspectRatio="none"
	style="display:block;width:100%;height:100%"
	aria-label="Stored shot telemetry chart"
>
	<!-- grid -->
	<g stroke="rgba(244,237,224,0.05)" stroke-width="1">
		{#each [0.2, 0.4, 0.6, 0.8] as p (p)}
			<line x1={padX} x2={W - padX} y1={padY + innerH * p} y2={padY + innerH * p} />
		{/each}
		{#each ticks as t (t)}
			<line y1={padY} y2={H - padY} x1={xScale(t)} x2={xScale(t)} stroke-dasharray="2 4" />
		{/each}
	</g>

	<!-- time labels -->
	<g fill="rgba(244,237,224,0.35)" font-family="JetBrains Mono, monospace" font-size="11">
		{#each ticks as t (t)}
			<text x={xScale(t)} y={H - 3} text-anchor="middle">{t}s</text>
		{/each}
	</g>

	{#if series.length > 0}
		<!-- channels -->
		<path d={tempPath} stroke="var(--tel-temp)" stroke-width="2.2" fill="none" />
		<path d={weightPath} stroke="var(--tel-weight)" stroke-width="2.2" fill="none" />
		<path d={flowPath} stroke="var(--tel-flow)" stroke-width="2.2" fill="none" />
		<path d={pressurePath} stroke="var(--tel-pressure)" stroke-width="2.6" fill="none" />
	{/if}
</svg>
