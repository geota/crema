<script lang="ts">
	/**
	 * `ProfilePreview` — the profile-card mini-chart.
	 *
	 * A live uPlot instance, rendered like the Brew `LiveChart`: a dashed time
	 * grid, faint horizontal bar lines, a left bar / ml·s axis and a right °C
	 * axis. The pressure curve, the damped "estimated flow" ghost and the
	 * per-segment temperature step are computed from the profile's own segments
	 * via `sampleCurve` — the same sampler the curve editor uses. Pressure and
	 * flow carry a soft top-down gradient fill.
	 *
	 * One uPlot instance per card is cheap, but a gridful constructed in a
	 * single frame would hitch on first paint — so each chart is built lazily,
	 * only once its card scrolls within `MOUNT_MARGIN` of the viewport.
	 */
	import { untrack } from 'svelte';
	import uPlot from 'uplot';
	import 'uplot/dist/uPlot.min.css';
	import { sampleCurve, preinfuseSeconds, type ProfileSegment } from '$lib/profiles';
	import { theme } from '$lib/theme.svelte';

	let {
		segments,
		active = false,
		compact = false
	}: {
		/** The profile's segments — the real curve is computed from these. */
		segments: ProfileSegment[];
		/** Whether the owning card is the active profile (warmer box tint). */
		active?: boolean;
		/**
		 * Compact thumbnail mode — used by the brew Quick Sheet favorites strip,
		 * which renders the preview at chip size (~70×30 px). Chrome is stripped
		 * down to a bare silhouette of the curves:
		 *   - no dashed time grid, no axis labels (left or right)
		 *   - no pre-infusion chip, no channel-legend chips
		 *   - temperature line dropped (3 lines → 2: pressure + flow)
		 *   - mounts eagerly — favourite chips are always on-screen while the
		 *     strip is, so the IntersectionObserver lazy-mount machinery would
		 *     just add a paint round-trip for no benefit.
		 */
		compact?: boolean;
	} = $props();

	/** Build a chart once its card scrolls within this margin of the viewport. */
	const MOUNT_MARGIN = '300px';

	/** The temperature axis range, °C — matches the curve editor. */
	const TEMP_MIN = 80;
	const TEMP_MAX = 105;
	/** A stepped path — temperature holds across a segment, then jumps. */
	const tempStepPath = uPlot.paths.stepped?.({ align: 1 });

	/** Leading pre-infusion seconds — shown as the top-right chip. */
	const preinf = $derived(preinfuseSeconds(segments));

	/** Resolve a CSS custom property to a concrete colour string. */
	function cssVar(name: string): string {
		if (typeof window === 'undefined') return '#888';
		return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#888';
	}

	/** Flow-ghost damping — matches the curve editor's `dampFlow`. */
	function dampFlow(target: number): number {
		return Math.min(4, target * 0.35 + 0.5);
	}

	/** The target temperature of the segment a given elapsed time falls in. */
	function tempAt(segs: readonly ProfileSegment[], x: number): number {
		let t = 0;
		for (const s of segs) {
			if (x < t + s.time) return s.temp;
			t += s.time;
		}
		return segs[segs.length - 1]?.temp ?? 93;
	}

	/**
	 * uPlot's column arrays. Flow is column 1 and pressure column 2 so pressure
	 * — the hero curve — draws its line and fill *on top of* the flow ghost.
	 */
	function toData(segs: readonly ProfileSegment[]): uPlot.AlignedData {
		const pressure = sampleCurve(segs);
		const flow = sampleCurve(segs, dampFlow);
		const temp = pressure.time.map((x) => tempAt(segs, x));
		return [pressure.time, flow.value, pressure.value, temp];
	}

	/** Round-numbered second marks — every 5 s, or 10 s on a long shot. */
	function timeSplits(max: number): number[] {
		const step = max > 30 ? 10 : 5;
		const out: number[] = [];
		for (let t = step; t < max; t += step) out.push(t);
		return out;
	}

	/**
	 * A soft top-down gradient fill for a channel — its colour at `topHexAlpha`
	 * fading to clear at the baseline. `cssVar` resolves to a 6-digit hex, so an
	 * 8-digit `#RRGGBBAA` stop just appends the alpha byte.
	 */
	function fillFor(varName: string, topHexAlpha: string) {
		return (u: uPlot): CanvasGradient | string => {
			const c = cssVar(varName);
			if (!/^#[0-9a-fA-F]{6}$/.test(c)) return c;
			const g = u.ctx.createLinearGradient(0, u.bbox.top, 0, u.bbox.top + u.bbox.height);
			g.addColorStop(0, c + topHexAlpha);
			g.addColorStop(1, c + '00');
			return g;
		};
	}

	function buildOpts(w: number, h: number, isCompact: boolean): uPlot.Options {
		// Canvas strokes can't resolve `var()` — resolve the chart tokens here.
		const gridColor = cssVar('--chart-grid');
		const labelColor = cssVar('--chart-axis-label');
		const font = '9px "JetBrains Mono", monospace';
		// Compact mode strips all chrome down to bare curves — no axis chips, no
		// grid, no labels, and only the pressure + flow channels (temperature
		// drops out so the small box does not look noisy with 3 stacked lines).
		const axes: uPlot.Axis[] = isCompact
			? [
				{ scale: 'x', show: false, grid: { show: false }, ticks: { show: false } },
				{ scale: 'y', show: false, grid: { show: false }, ticks: { show: false } },
				{ scale: 'temp', show: false, grid: { show: false }, ticks: { show: false } }
			]
			: [
				{
					scale: 'x',
					stroke: labelColor,
					grid: { stroke: gridColor, width: 1, dash: [2, 4] },
					ticks: { show: false },
					font,
					size: 22,
					splits: (u) => timeSplits((u.scales.x.max ?? 1) as number),
					values: (_u, splits) => splits.map((v) => `${v}s`)
				},
				{
					// Left axis — bar (pressure) / ml·s (flow).
					scale: 'y',
					side: 3,
					stroke: labelColor,
					grid: { stroke: gridColor, width: 1 },
					ticks: { show: false },
					font,
					size: 26,
					splits: () => [0, 3, 6, 9, 12],
					values: (_u, splits) => splits.map((v) => `${v}`)
				},
				{
					// Right axis — °C (temperature).
					scale: 'temp',
					side: 1,
					stroke: labelColor,
					grid: { show: false },
					ticks: { show: false },
					font,
					size: 30,
					splits: () => [80, 90, 100],
					values: (_u, splits) => splits.map((v) => `${v}°`)
				}
			];
		// The temp series — kept as a placeholder column so the data shape
		// (x, flow, pressure, temp) stays identical between modes, but the
		// line itself is hidden in compact previews.
		const tempSeries: uPlot.Series = isCompact
			? { scale: 'temp', show: false, points: { show: false } }
			: {
				scale: 'temp',
				stroke: () => cssVar('--tel-temp'),
				width: 1.6,
				paths: tempStepPath,
				points: { show: false }
			};
		return {
			width: w,
			height: h,
			// Compact has no axis labels, so it can hug the box edges.
			padding: isCompact ? [1, 1, 1, 1] : [14, 4, 2, 2],
			cursor: { show: false },
			legend: { show: false },
			scales: {
				// 0 → total shot time; a one-second floor avoids a zero-width axis.
				x: {
					time: false,
					range: (_u, _min, dataMax) => [0, Math.max(1, Number.isFinite(dataMax) ? dataMax : 1)]
				},
				// A fixed 0–12 bar / ml·s scale — the design's grid max.
				y: { range: () => [0, 12] },
				// Temperature on its own scale + right axis.
				temp: { range: () => [TEMP_MIN, TEMP_MAX] }
			},
			axes,
			series: [
				{},
				{
					// The damped "estimated flow" ghost — dashed, light fill.
					scale: 'y',
					stroke: () => cssVar('--tel-flow'),
					fill: fillFor('--tel-flow', '24'),
					width: isCompact ? 1.2 : 1.4,
					dash: [3, 3],
					points: { show: false }
				},
				{
					// The pressure curve — the hero line, soft gradient fill.
					scale: 'y',
					stroke: () => cssVar('--tel-pressure'),
					fill: fillFor('--tel-pressure', '4D'),
					width: isCompact ? 1.6 : 2.4,
					points: { show: false }
				},
				tempSeries
			]
		};
	}

	// Both `bind:this` targets are read inside an `$effect` (the Intersection
	// observer / the chart build), so both are `$state` — the effects must
	// re-run once the elements are attached.
	let boxEl = $state<HTMLDivElement>();
	let plotEl = $state<HTMLDivElement>();
	let chart: uPlot | null = null;
	let resizeObs: ResizeObserver | null = null;
	/**
	 * Flips true once the card nears the viewport — gates chart construction
	 * in the full preview. Compact previews skip the IntersectionObserver and
	 * mount eagerly: favourite chips are visible the whole time their strip
	 * is, so the lazy machinery would add a paint round-trip for no benefit.
	 *
	 * `compact` is a prop (so technically reactive), but the mode never
	 * changes for a given instance — a chip stays a chip. `untrack` the
	 * one-shot initial read so svelte-check doesn't flag this as
	 * "captures only the initial value" (it does, deliberately).
	 */
	let mounted = $state(untrack(() => compact));

	// Arm the chart lazily: build uPlot only once the card scrolls into reach,
	// so a gridful of profiles doesn't construct every chart in one frame.
	// Compact previews skip this — they pre-mount above.
	$effect(() => {
		if (compact || mounted || !boxEl) return;
		const io = new IntersectionObserver(
			(entries) => {
				if (entries.some((e) => e.isIntersecting)) {
					mounted = true;
					io.disconnect();
				}
			},
			{ rootMargin: MOUNT_MARGIN }
		);
		io.observe(boxEl);
		return () => io.disconnect();
	});

	// Build the chart once armed. `segments` is read untracked so an edit feeds
	// the live instance (effect below) instead of tearing it down and rebuilding.
	$effect(() => {
		if (!mounted || !plotEl) return;
		const el = plotEl;
		const w = Math.max(1, el.clientWidth);
		const h = Math.max(1, el.clientHeight);
		chart = new uPlot(buildOpts(w, h, compact), toData(untrack(() => segments)), el);

		// Track the card's live width / height so the chart always fills its box.
		resizeObs = new ResizeObserver((entries) => {
			const cr = entries[0].contentRect;
			chart?.setSize({ width: Math.max(1, cr.width), height: Math.max(1, cr.height) });
		});
		resizeObs.observe(el);

		return () => {
			resizeObs?.disconnect();
			resizeObs = null;
			chart?.destroy();
			chart = null;
		};
	});

	// Feed segment edits into the live chart. `setData` re-runs the x / y `range`
	// callbacks, so the axes track the profile's length and shape.
	$effect(() => {
		const data = toData(segments);
		chart?.setData(data);
	});

	// Repaint on theme flip — axis/grid colours are baked into `buildOpts` at
	// creation, so rebuild the instance against the new tokens.
	$effect(() => {
		theme.current;
		untrack(() => {
			const el = plotEl;
			if (!el || !chart) return;
			const w = Math.max(1, el.clientWidth);
			const h = Math.max(1, el.clientHeight);
			chart.destroy();
			chart = new uPlot(buildOpts(w, h, compact), toData(segments), el);
		});
	});
</script>

<div
	class="pp-preview"
	class:is-active={active}
	class:is-compact={compact}
	bind:this={boxEl}
>
	{#if mounted}
		<div class="pp-preview-plot" bind:this={plotEl}></div>
	{/if}

	<!--
		Channel legend + pre-inf chip are full-preview chrome only; compact
		thumbnails drop them so the silhouette can breathe at chip size.
	-->
	{#if !compact}
		<div class="pp-preview-legend">
			<span class="pp-preview-chip" style="--c:var(--tel-pressure)">
				<i></i><span>Pressure</span>
			</span>
			<span class="pp-preview-chip" style="--c:var(--tel-flow)">
				<i></i><span>Flow</span>
			</span>
			<span class="pp-preview-chip pp-preview-chip-dashed" style="--c:var(--tel-temp)">
				<i></i><span>Temp</span>
			</span>
		</div>

		{#if preinf > 0}
			<div class="pp-preview-corner">Pre-inf · {preinf}s</div>
		{/if}
	{/if}
</div>

<style>
	/* The handoff sized this box at a fixed 132 px tall and letterboxed an SVG
	   inside it. The uPlot canvas fills the box exactly, so the box now carries
	   a fixed aspect ratio instead — full card width, proportionally taller. */
	.pp-preview {
		height: auto;
		aspect-ratio: 11 / 5;
	}
	/*
	 * Compact thumbnail — used by the brew Quick Sheet favorites strip. The
	 * shared `.pp-preview` rules (in `styles/profiles-page.css`) give the
	 * box a 132 px height, a card-tinted background, a border and rounded
	 * corners. At chip size none of that reads — override to a small
	 * transparent box so the silhouette can sit next to the name / ratio
	 * text on equal terms.
	 */
	.pp-preview.is-compact {
		height: 30px;
		width: 70px;
		aspect-ratio: auto;
		background: transparent;
		border: 0;
		border-radius: 0;
		margin: 0;
		overflow: visible;
		flex: 0 0 auto;
	}
	/* uPlot mounts here and fills the box; the legend / chip overlay on top. */
	.pp-preview-plot {
		position: absolute;
		inset: 0;
	}
	.pp-preview-plot :global(.uplot),
	.pp-preview-plot :global(.u-wrap) {
		width: 100%;
	}
	/* Top-right pre-infusion chip — same terse style as the channel chips. */
	.pp-preview-corner {
		position: absolute;
		top: 8px;
		right: 12px;
		font-family: var(--font-sans);
		font-size: 9px;
		font-weight: 600;
		letter-spacing: var(--track-allcaps, 0.08em);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.4);
		pointer-events: none;
	}
</style>
