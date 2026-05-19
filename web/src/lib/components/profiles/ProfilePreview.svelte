<script lang="ts" module>
	/**
	 * `ProfilePreview` — the profile-card mini-chart.
	 *
	 * The **real** profile graph: the pressure curve and a damped "estimated
	 * flow" ghost are computed from the profile's own segments via
	 * `sampleCurve` (the same sampler the curve editor uses), and the
	 * per-segment temperature is drawn as a step. Plus the design's chrome — a
	 * shaded pre-infusion band, a 9-bar target hairline, faint gridlines,
	 * gradient fills and channel-label chips. Hand-crafted SVG: one tiny
	 * static chart per card, so SVG beats a uPlot instance per card.
	 */

	// Geometry — the design's fixed 300×108 viewbox + margins.
	const W = 300;
	const H = 108;
	const M = { t: 16, r: 14, b: 14, l: 14 };
	const CW = W - M.l - M.r;
	const CH = H - M.t - M.b;

	/**
	 * A straight-segment SVG path through the points. The curve shape is
	 * already baked into the dense `sampleCurve` samples, so no extra
	 * smoothing is applied — that would distort the real curve.
	 */
	function polyPath(pts: readonly [number, number][]): string {
		if (pts.length === 0) return '';
		let d = `M ${pts[0][0].toFixed(1)} ${pts[0][1].toFixed(1)}`;
		for (let i = 1; i < pts.length; i++) {
			d += ` L ${pts[i][0].toFixed(1)} ${pts[i][1].toFixed(1)}`;
		}
		return d;
	}

	/** Close a stroke path down to the baseline for the gradient fill. */
	function areaPath(pts: readonly [number, number][]): string {
		if (pts.length === 0) return '';
		const baseY = (M.t + CH).toFixed(1);
		return `${polyPath(pts)} L ${pts[pts.length - 1][0].toFixed(1)} ${baseY} L ${pts[0][0].toFixed(1)} ${baseY} Z`;
	}
</script>

<script lang="ts">
	import {
		sampleCurve,
		totalTime,
		preinfuseSeconds,
		sparkShape,
		type ProfileSegment
	} from '$lib/profiles';

	let {
		id,
		segments,
		active = false
	}: {
		/** The owning profile's id — namespaces the gradient `<defs>`. */
		id: string;
		/** The profile's segments — the real curve is computed from these. */
		segments: ProfileSegment[];
		/** Whether the owning card is the active profile (warmer accent). */
		active?: boolean;
	} = $props();

	/** Flow-ghost damping — matches the curve editor's `dampFlow`. */
	function dampFlow(target: number): number {
		return Math.min(4, target * 0.35 + 0.5);
	}

	/** Total shot time — the x extent (≥ 1 s to avoid a zero-width axis). */
	const total = $derived(Math.max(1, totalTime(segments)));
	/** Leading pre-infusion seconds — drives the shaded band + axis label. */
	const preinf = $derived(preinfuseSeconds(segments));
	/** A coarse silhouette name for the top-right meta tag. */
	const shape = $derived(sparkShape(segments));
	/** A unique-per-profile gradient id prefix. */
	const fid = $derived(`pp-grad-${id}`);

	/** Time (s) → SVG x. */
	function sx(t: number): number {
		return M.l + (t / total) * CW;
	}
	/** A bar / flow value on the 0–12 scale → SVG y. */
	function syBar(v: number): number {
		return M.t + (1 - Math.min(12, Math.max(0, v)) / 12) * CH;
	}
	/**
	 * A temperature (°C) → SVG y. 80–105 °C is kept to the upper band so the
	 * temp line reads as a context strip and doesn't fight the pressure sweep.
	 */
	function syTemp(c: number): number {
		const f = Math.min(1, Math.max(0, (c - 80) / 25));
		return M.t + (1 - (0.58 + f * 0.37)) * CH;
	}

	/** The real pressure curve, scaled to the viewbox. */
	const pressurePts = $derived.by<[number, number][]>(() => {
		const s = sampleCurve(segments);
		return s.time.map((t, i) => [sx(t), syBar(s.value[i])]);
	});
	/** The damped "estimated flow" ghost, scaled to the viewbox. */
	const flowPts = $derived.by<[number, number][]>(() => {
		const s = sampleCurve(segments, dampFlow);
		return s.time.map((t, i) => [sx(t), syBar(s.value[i])]);
	});
	/** The per-segment temperature, as a stepped polyline. */
	const tempPts = $derived.by<[number, number][]>(() => {
		const out: [number, number][] = [];
		let t = 0;
		for (const seg of segments) {
			const y = syTemp(seg.temperatureC);
			out.push([sx(t), y], [sx(t + seg.time), y]);
			t += seg.time;
		}
		return out;
	});

	const pressureStroke = $derived(polyPath(pressurePts));
	const pressureArea = $derived(areaPath(pressurePts));
	const flowStroke = $derived(polyPath(flowPts));
	const flowArea = $derived(areaPath(flowPts));
	const tempStroke = $derived(polyPath(tempPts));

	/** Pre-infusion zone — `preinf` seconds, visual span capped at 30 s. */
	const preinfFrac = $derived(Math.min(preinf / 30, 0.45));
	const preinfX2 = $derived(M.l + preinfFrac * CW);

	/** 9-bar target hairline — 9/12 of the pressure scale. */
	const targetY = M.t + (1 - 9 / 12) * CH;

	/** The horizontal gridline ratios. */
	const gridlines = [0.25, 0.5, 0.75];

	/** Channel colors — pressure brightens slightly on the active card. */
	const pressureColor = $derived(active ? '#8BAA82' : 'var(--tel-pressure, #6B8C5F)');
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
