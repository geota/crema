<script lang="ts" module>
	/** The named pressure-curve shapes a favorite chip can render. */
	export type SparkShape = 'rao' | 'blooming' | 'decline' | 'classic' | 'turbo' | 'cold';

	/** Each shape as a list of normalised `[x, y]` control points (0..1). */
	const SHAPES: Record<SparkShape, [number, number][]> = {
		rao: [
			[0, 0.1],
			[0.2, 0.4],
			[0.3, 0.9],
			[0.55, 0.85],
			[0.85, 0.7],
			[1, 0.6]
		],
		blooming: [
			[0, 0.05],
			[0.15, 0.15],
			[0.25, 0.55],
			[0.45, 0.85],
			[0.7, 0.85],
			[1, 0.7]
		],
		decline: [
			[0, 0.1],
			[0.18, 0.85],
			[0.35, 0.95],
			[0.55, 0.9],
			[0.8, 0.75],
			[1, 0.5]
		],
		classic: [
			[0, 0.1],
			[0.2, 0.85],
			[0.4, 0.9],
			[0.7, 0.9],
			[0.85, 0.85],
			[1, 0.7]
		],
		turbo: [
			[0, 0.2],
			[0.12, 0.7],
			[0.3, 0.7],
			[0.55, 0.7],
			[0.8, 0.7],
			[1, 0.65]
		],
		cold: [
			[0, 0.1],
			[0.25, 0.3],
			[0.5, 0.55],
			[0.75, 0.7],
			[1, 0.7]
		]
	};
</script>

<script lang="ts">
	/**
	 * `QSparkline` — the tiny pressure-curve thumbnail used in favorite chips,
	 * ported from the design's `QSparkline` in `quick-controls.jsx`. Pure SVG.
	 *
	 * The path is drawn in a fixed `100 × 100` user-space `viewBox`; the SVG
	 * itself is sized by CSS (`width`/`height` props become the box) and uses
	 * `preserveAspectRatio="none"` *intentionally* — these are decorative
	 * silhouettes, so stretching the curve to fill the chip is the desired
	 * behaviour and avoids the letterboxing the old fixed-attr SVG produced
	 * inside flex containers of a different aspect ratio.
	 */
	let {
		shape = 'rao',
		width = 28,
		height = 14,
		color = 'var(--copper-400)'
	}: {
		/** Which named curve to draw. */
		shape?: SparkShape;
		/** Rendered width, px (the CSS box, not the path's user space). */
		width?: number;
		/** Rendered height, px (the CSS box, not the path's user space). */
		height?: number;
		/** Stroke colour. */
		color?: string;
	} = $props();

	/** Fixed user space the path is authored in — decoupled from the CSS box. */
	const VB = 100;

	/** The SVG path `d` for the chosen shape, in the fixed `VB × VB` viewBox. */
	const path = $derived(
		(SHAPES[shape] ?? SHAPES.classic)
			.map(
				([x, y], i) =>
					`${i === 0 ? 'M' : 'L'} ${(x * VB).toFixed(2)} ${((1 - y) * VB).toFixed(2)}`
			)
			.join(' ')
	);

</script>

<svg
	viewBox="0 0 {VB} {VB}"
	style="display:block;width:{width}px;height:{height}px;overflow:visible"
	preserveAspectRatio="none"
	aria-hidden="true"
>
	<path
		d={path}
		stroke={color}
		stroke-width="1.4"
		vector-effect="non-scaling-stroke"
		fill="none"
		stroke-linecap="round"
		stroke-linejoin="round"
	/>
</svg>
