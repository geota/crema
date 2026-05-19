<script lang="ts" module>
	/**
	 * `ProfilePreview` — the 3-curve profile-card mini-chart, ported from
	 * `ProfilePreview` in `profiles-page.jsx`.
	 *
	 * A decorative illustration of a profile's *shape* — pressure, flow and
	 * temperature curves with gradient fills, a shaded pre-infusion band, a
	 * 9-bar target hairline and channel-label chips. It is **not** real
	 * telemetry: the curves come from a fixed silhouette table keyed on the
	 * profile's classified {@link SparkShape}. Hand-crafted SVG by design — the
	 * look depends on the gradient fills, so it stays SVG (uPlot is only for
	 * real telemetry charts).
	 */

	/** One profile shape's pressure / flow / temp control points, normalised. */
	interface ShapeCurves {
		/** Pressure points — `[time 0..1, value 0..1]`, 1.0 ≈ 12 bar. */
		p: [number, number][];
		/** Flow points — `[time 0..1, value 0..1]`, 1.0 ≈ 6 mL/s. */
		f: [number, number][];
		/** Temperature points — `[time 0..1, value 0..1]`. */
		t: [number, number][];
	}

	/**
	 * Each profile shape defines pressure + flow + temp paths in normalised
	 * `[time 0..1, value 0..1]` coords. Verbatim from `profiles-page.jsx`.
	 */
	const SHAPES: Record<SparkShape, ShapeCurves> = {
		rao: {
			p: [[0, 0.05], [0.12, 0.45], [0.22, 0.92], [0.32, 0.82], [0.55, 0.74], [0.78, 0.66], [1, 0.58]],
			f: [[0, 0], [0.12, 0.1], [0.25, 0.38], [0.45, 0.55], [0.7, 0.62], [0.9, 0.6], [1, 0.55]],
			t: [[0, 0.42], [0.15, 0.46], [0.35, 0.5], [0.6, 0.52], [0.85, 0.5], [1, 0.48]]
		},
		blooming: {
			p: [[0, 0.04], [0.18, 0.18], [0.3, 0.55], [0.5, 0.85], [0.72, 0.82], [0.88, 0.74], [1, 0.66]],
			f: [[0, 0], [0.18, 0.05], [0.32, 0.18], [0.55, 0.45], [0.78, 0.58], [0.92, 0.6], [1, 0.58]],
			t: [[0, 0.34], [0.2, 0.38], [0.4, 0.42], [0.65, 0.44], [0.9, 0.43], [1, 0.42]]
		},
		decline: {
			p: [[0, 0.1], [0.12, 0.78], [0.22, 0.96], [0.38, 0.92], [0.6, 0.82], [0.82, 0.7], [1, 0.5]],
			f: [[0, 0], [0.12, 0.12], [0.25, 0.35], [0.5, 0.55], [0.72, 0.62], [0.88, 0.65], [1, 0.62]],
			t: [[0, 0.38], [0.15, 0.42], [0.4, 0.46], [0.65, 0.46], [0.9, 0.44], [1, 0.42]]
		},
		classic: {
			p: [[0, 0.08], [0.15, 0.84], [0.3, 0.9], [0.55, 0.9], [0.78, 0.88], [0.92, 0.86], [1, 0.78]],
			f: [[0, 0], [0.15, 0.18], [0.3, 0.42], [0.55, 0.55], [0.78, 0.6], [0.92, 0.62], [1, 0.62]],
			t: [[0, 0.48], [0.2, 0.52], [0.45, 0.55], [0.7, 0.56], [0.92, 0.55], [1, 0.54]]
		},
		turbo: {
			p: [[0, 0.15], [0.1, 0.65], [0.22, 0.7], [0.45, 0.7], [0.68, 0.7], [0.88, 0.7], [1, 0.68]],
			f: [[0, 0], [0.1, 0.32], [0.22, 0.55], [0.45, 0.7], [0.68, 0.78], [0.88, 0.8], [1, 0.8]],
			t: [[0, 0.38], [0.15, 0.42], [0.4, 0.44], [0.65, 0.44], [0.9, 0.42], [1, 0.4]]
		},
		cold: {
			p: [[0, 0.06], [0.22, 0.22], [0.4, 0.42], [0.6, 0.55], [0.78, 0.62], [0.92, 0.62], [1, 0.6]],
			f: [[0, 0], [0.22, 0.04], [0.42, 0.18], [0.62, 0.36], [0.8, 0.5], [0.92, 0.55], [1, 0.55]],
			t: [[0, 0.18], [0.2, 0.22], [0.4, 0.24], [0.65, 0.24], [0.9, 0.22], [1, 0.2]]
		}
	};

	// Geometry — the design's fixed 300×108 viewbox + margins.
	const W = 300;
	const H = 108;
	const M = { t: 16, r: 14, b: 14, l: 14 };
	const CW = W - M.l - M.r;
	const CH = H - M.t - M.b;

	/**
	 * Smooth a normalised point list into an SVG path via Catmull-Rom-to-Bézier
	 * — verbatim from `profiles-page.jsx`'s `toPath`.
	 */
	function toPath(pts: [number, number][]): string {
		const P = pts.map(([x, y]): [number, number] => [M.l + x * CW, M.t + (1 - y) * CH]);
		let d = `M ${P[0][0].toFixed(1)} ${P[0][1].toFixed(1)}`;
		for (let i = 0; i < P.length - 1; i++) {
			const p0 = P[i - 1] ?? P[i];
			const p1 = P[i];
			const p2 = P[i + 1];
			const p3 = P[i + 2] ?? p2;
			const c1x = p1[0] + (p2[0] - p0[0]) / 6;
			const c1y = p1[1] + (p2[1] - p0[1]) / 6;
			const c2x = p2[0] - (p3[0] - p1[0]) / 6;
			const c2y = p2[1] - (p3[1] - p1[1]) / 6;
			d += ` C ${c1x.toFixed(1)} ${c1y.toFixed(1)}, ${c2x.toFixed(1)} ${c2y.toFixed(1)}, ${p2[0].toFixed(1)} ${p2[1].toFixed(1)}`;
		}
		return d;
	}

	/** Close a stroke path down to the baseline for the gradient fill. */
	function areaPath(pts: [number, number][]): string {
		const stroke = toPath(pts);
		const lastX = M.l + pts[pts.length - 1][0] * CW;
		const firstX = M.l + pts[0][0] * CW;
		const baseY = M.t + CH;
		return `${stroke} L ${lastX.toFixed(1)} ${baseY.toFixed(1)} L ${firstX.toFixed(1)} ${baseY.toFixed(1)} Z`;
	}
</script>

<script lang="ts">
	import type { SparkShape } from '$lib/components/brew/QSparkline.svelte';

	let {
		id,
		shape,
		preinf,
		active = false
	}: {
		/** The owning profile's id — namespaces the gradient `<defs>`. */
		id: string;
		/** The classified curve silhouette. */
		shape: SparkShape;
		/** Leading pre-infusion seconds — drives the shaded band + axis label. */
		preinf: number;
		/** Whether the owning card is the active profile (warmer accent). */
		active?: boolean;
	} = $props();

	/** The curve control points for this shape. */
	const curves = $derived(SHAPES[shape] ?? SHAPES.classic);

	/** A unique-per-profile gradient id prefix. */
	const fid = $derived(`pp-grad-${id}`);

	const pressureStroke = $derived(toPath(curves.p));
	const pressureArea = $derived(areaPath(curves.p));
	const flowStroke = $derived(toPath(curves.f));
	const flowArea = $derived(areaPath(curves.f));
	const tempStroke = $derived(toPath(curves.t));

	/** Pre-infusion zone — `preinf` seconds, visual span capped at 30 s. */
	const preinfFrac = $derived(Math.min(preinf / 30, 0.45));
	const preinfX2 = $derived(M.l + preinfFrac * CW);

	/** 9-bar target hairline — 9/12 of the pressure scale. */
	const targetY = M.t + (1 - 9 / 12) * CH;

	/** The horizontal gridline ratios. */
	const gridlines = [0.25, 0.5, 0.75];

	/** Channel colors — pressure warms slightly on the active card. */
	const pressureColor = $derived(active ? '#E5A65A' : 'var(--tel-pressure, #D89030)');
	const flowColor = 'var(--tel-flow, #4A6FA5)';
	const tempColor = 'var(--tel-temp, #C44E3F)';
</script>

<div class="pp-preview" class:is-active={active}>
	<svg
		viewBox="0 0 {W} {H}"
		preserveAspectRatio="xMidYMid meet"
		width="100%"
		height="100%"
		shape-rendering="geometricPrecision"
	>
		<defs>
			<linearGradient id="{fid}-p" x1="0" y1="0" x2="0" y2="1">
				<stop offset="0%" stop-color={pressureColor} stop-opacity="0.32" />
				<stop offset="100%" stop-color={pressureColor} stop-opacity="0" />
			</linearGradient>
			<linearGradient id="{fid}-f" x1="0" y1="0" x2="0" y2="1">
				<stop offset="0%" stop-color={flowColor} stop-opacity="0.22" />
				<stop offset="100%" stop-color={flowColor} stop-opacity="0" />
			</linearGradient>
		</defs>

		<!-- Pre-infusion shaded band -->
		{#if preinfFrac > 0.01}
			<rect
				x={M.l}
				y={M.t}
				width={preinfX2 - M.l}
				height={CH}
				fill="rgba(244,237,224,0.045)"
			/>
		{/if}

		<!-- Subtle horizontal hairlines at 25 / 50 / 75% of the pressure axis -->
		{#each gridlines as t (t)}
			<line
				x1={M.l}
				y1={M.t + (1 - t) * CH}
				x2={W - M.r}
				y2={M.t + (1 - t) * CH}
				stroke="rgba(244,237,224,0.045)"
				stroke-width="1"
			/>
		{/each}

		<!-- 9-bar target dotted line -->
		<line
			x1={M.l}
			y1={targetY}
			x2={W - M.r}
			y2={targetY}
			stroke="rgba(244,237,224,0.18)"
			stroke-width="1"
			stroke-dasharray="2 3"
		/>

		<!-- Areas -->
		<path d={flowArea} fill="url(#{fid}-f)" />
		<path d={pressureArea} fill="url(#{fid}-p)" />

		<!-- Strokes -->
		<path
			d={flowStroke}
			stroke={flowColor}
			stroke-width="1.4"
			fill="none"
			stroke-linecap="round"
			stroke-linejoin="round"
			opacity="0.85"
			vector-effect="non-scaling-stroke"
		/>
		<path
			d={tempStroke}
			stroke={tempColor}
			stroke-width="1.2"
			fill="none"
			stroke-linecap="round"
			stroke-linejoin="round"
			opacity="0.7"
			stroke-dasharray="3 2"
			vector-effect="non-scaling-stroke"
		/>
		<path
			d={pressureStroke}
			stroke={pressureColor}
			stroke-width="1.8"
			fill="none"
			stroke-linecap="round"
			stroke-linejoin="round"
			vector-effect="non-scaling-stroke"
		/>

		<!-- Pre-infusion left-edge marker -->
		{#if preinfFrac > 0.01}
			<line
				x1={preinfX2}
				y1={M.t}
				x2={preinfX2}
				y2={M.t + CH}
				stroke="rgba(244,237,224,0.18)"
				stroke-width="1"
				stroke-dasharray="1 2"
			/>
		{/if}
	</svg>

	<!-- Channel legend (top-left) -->
	<div class="pp-preview-legend">
		<span class="pp-preview-chip" style="--c:{pressureColor}">
			<i></i><span>Pressure</span>
		</span>
		<span class="pp-preview-chip" style="--c:{flowColor}">
			<i></i><span>Flow</span>
		</span>
		<span class="pp-preview-chip pp-preview-chip-dashed" style="--c:{tempColor}">
			<i></i><span>Temp</span>
		</span>
	</div>

	<!-- Meta (top-right) -->
	<div class="pp-preview-meta">
		<span class="pp-preview-shape">{shape}</span>
	</div>

	<!-- Axis ticks (bottom) -->
	<div class="pp-preview-axis">
		{#if preinf > 0}
			<span
				class="pp-preview-preinf"
				style="width:{(preinfFrac * 100).toFixed(1)}%"
			>
				pre-infusion · {preinf}s
			</span>
		{/if}
	</div>
</div>
