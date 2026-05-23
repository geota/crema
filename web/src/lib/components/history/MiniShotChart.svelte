<script lang="ts">
	/**
	 * `MiniShotChart` — a tiny inline 3-channel chart of a recorded shot,
	 * sized for the History row's leading slot. Plots pressure (bar), group
	 * flow (ml/s) and scale weight (g) from the stored
	 * `TelemetrySample[]` series, each channel normalised against its own
	 * natural range so the silhouettes read at a glance even at ~100×30 px.
	 *
	 * Hand-rolled SVG — same pattern as `CompareOverlay`'s `pathFor`. uPlot
	 * would be overkill: the data per row is short, the chart has no axes,
	 * no cursor, no legend; an SVG `<path>` per channel is the right tool.
	 *
	 * Weight is conditional: when the shot was pulled with no scale paired,
	 * every sample's `weight` is `null`, and the weight line is suppressed
	 * entirely instead of drawing a flat zero. Pressure and flow are always
	 * present on a DE1 telemetry sample, so they always render.
	 */
	import type { TelemetrySample } from '$lib/state';

	let {
		series,
		width = 96,
		height = 32
	}: {
		/** The recorded shot's full telemetry series, oldest first. */
		series: readonly TelemetrySample[];
		/** Rendered width, px. Defaults to a History-row friendly 96 px. */
		width?: number;
		/** Rendered height, px. Defaults to a History-row friendly 32 px. */
		height?: number;
	} = $props();

	// ── Per-channel y-range tops. Each channel is normalised against its
	// own natural maximum so the 3 lines share the same 0..1 plot space
	// without one channel flattening the others (pressure tops at ~10 bar,
	// flow at ~4 ml/s, weight at ~40 g — wildly different magnitudes).
	const Y_PRESSURE_MAX = 10; // bar
	const Y_FLOW_MAX = 8; // ml/s — generous ceiling for fast turbo shots
	const Y_WEIGHT_MIN_MAX = 30; // g — floor so a short pull still scales nicely

	// Drawing area within the SVG box — a 1 px hairline padding keeps the
	// stroke from getting clipped along the box edges.
	const PAD = 1;

	/**
	 * Total shot length in seconds, clamped to a 1 s floor so a zero-sample
	 * (degenerate) record still has a non-zero x denominator.
	 */
	const maxTimeSec = $derived.by(() => {
		if (series.length === 0) return 1;
		const last = series[series.length - 1];
		return Math.max(1, last.elapsed / 1000);
	});

	/**
	 * Whether any sample carries a non-null weight. When a shot was pulled
	 * without a paired scale every sample's `weight` is `null` and the
	 * line would be empty — suppress the path entirely rather than drawing
	 * a single fallback point or a flat zero.
	 */
	const hasWeight = $derived(series.some((s) => s.weight != null));

	/**
	 * The per-shot weight ceiling, grams. The yield varies shot-to-shot
	 * (a ristretto tops near 20 g, a long pull crosses 50 g); using the
	 * shot's own peak weight as the scale top keeps each row's weight
	 * line filling its natural height. A `Y_WEIGHT_MIN_MAX` floor prevents
	 * a very short pull from over-amplifying noise.
	 */
	const weightMax = $derived.by(() => {
		let m = 0;
		for (const s of series) {
			const w = s.weight;
			if (w != null && w > m) m = w;
		}
		return Math.max(m, Y_WEIGHT_MIN_MAX);
	});

	/** Map a seconds value to its SVG x coordinate. */
	const xAt = (t: number): number => PAD + (t / maxTimeSec) * (width - 2 * PAD);
	/** Map a normalised 0..1 value to its SVG y coordinate (inverted). */
	const yAt = (norm: number): number =>
		PAD + (1 - Math.min(1, Math.max(0, norm))) * (height - 2 * PAD);

	/**
	 * Build an SVG path `d` for one channel. `pick(sample)` returns either
	 * the normalised 0..1 value or `null` for a gap (a `null` sample is
	 * common for `weight` before the first scale reading lands). The path
	 * lifts the pen on a gap and re-attaches with `M` on the next valid
	 * sample, so the line doesn't bridge across missing data.
	 */
	function pathFor(pick: (s: TelemetrySample) => number | null): string {
		const segs: string[] = [];
		let drawing = false;
		for (const s of series) {
			const v = pick(s);
			if (v == null || !Number.isFinite(v)) {
				drawing = false;
				continue;
			}
			const x = xAt(s.elapsed / 1000);
			const y = yAt(v);
			segs.push(`${drawing ? 'L' : 'M'} ${x.toFixed(1)} ${y.toFixed(1)}`);
			drawing = true;
		}
		return segs.join(' ');
	}

	const pressurePath = $derived(pathFor((s) => s.pressure / Y_PRESSURE_MAX));
	const flowPath = $derived(pathFor((s) => s.flow / Y_FLOW_MAX));
	const weightPath = $derived(
		hasWeight ? pathFor((s) => (s.weight == null ? null : s.weight / weightMax)) : ''
	);
</script>

<!--
	`preserveAspectRatio="none"` lets the silhouette stretch to fill the
	row slot — these are at-a-glance previews, not measured charts.
-->
<svg
	class="mini-shot-chart"
	{width}
	{height}
	viewBox="0 0 {width} {height}"
	preserveAspectRatio="none"
	aria-hidden="true"
>
	{#if hasWeight}
		<!--
			Weight first so it sits beneath flow and pressure — the hero
			ordering matches the brew dashboard's LiveChart: pressure
			reads as the primary curve, flow as secondary, weight as
			ambient context.
		-->
		<path
			class="mini-line mini-line-weight"
			d={weightPath}
			fill="none"
			stroke="var(--tel-weight)"
		/>
	{/if}
	<path class="mini-line mini-line-flow" d={flowPath} fill="none" stroke="var(--tel-flow)" />
	<path
		class="mini-line mini-line-pressure"
		d={pressurePath}
		fill="none"
		stroke="var(--tel-pressure)"
	/>
</svg>

<style>
	.mini-shot-chart {
		display: block;
		overflow: visible;
	}
	.mini-line {
		stroke-linecap: round;
		stroke-linejoin: round;
		vector-effect: non-scaling-stroke;
	}
	.mini-line-pressure {
		stroke-width: 1.5;
	}
	.mini-line-flow {
		stroke-width: 1.1;
		opacity: 0.85;
	}
	.mini-line-weight {
		stroke-width: 1.1;
		opacity: 0.7;
	}
</style>
