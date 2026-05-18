<script lang="ts">
	/**
	 * `ProfileCurveEditor` — the interactive pressure-curve SVG, ported from
	 * `ProfileCurveEditor` in `profile-edit-page.jsx`.
	 *
	 * The design only *mocked* the dot drag; here it is **functional**. Each
	 * boundary dot sits at a segment's end (X = cumulative time, Y = target).
	 * A pointer drag on a dot maps the pointer position back to the segment
	 * model via `svgToSegment` and updates `segments` — so dragging genuinely
	 * edits the underlying profile. The active segment highlights with a copper
	 * band and a ringed dot; smooth ramps render as Bézier curves and fast ramps
	 * as steps; the dashed ghost flow curve is drawn beneath.
	 *
	 * The edit flows through the `onEdit` callback, the same one the segment-list
	 * rows call — both surfaces are two-way over one model.
	 */
	import {
		geometry,
		curvePath,
		flowGhostPath,
		boundaryDots,
		activeBand,
		svgToSegment,
		xFor,
		yFor,
		Y_MAX,
		PAD_X,
		PAD_Y,
		totalTime,
		type ProfileSegment
	} from '$lib/profiles';

	let {
		segments,
		activeSegId,
		onSelect,
		onEdit,
		width = 720,
		height = 300
	}: {
		/** The segment model to draw. */
		segments: ProfileSegment[];
		/** The id of the currently-selected segment, or `null`. */
		activeSegId: string | null;
		/** Select a segment (clicking its dot). */
		onSelect: (id: string) => void;
		/** Apply a `{ time, target }` patch to the segment with the given id. */
		onEdit: (id: string, patch: { time: number; target: number }) => void;
		/** SVG viewbox width. */
		width?: number;
		/** SVG viewbox height. */
		height?: number;
	} = $props();

	/** The drawing geometry — recomputed when the segments or size change. */
	const g = $derived(geometry(segments, width, height));
	const total = $derived(totalTime(segments));
	const pressurePath = $derived(curvePath(segments, g));
	const ghostPath = $derived(flowGhostPath(segments, g));
	const dots = $derived(boundaryDots(segments, g));
	const band = $derived(activeBand(segments, g, activeSegId));

	/** Y grid lines — fixed bar marks, the design's 0/3/6/9/12. */
	const gridY = [0, 3, 6, 9, 12];
	/** X grid lines — every 5 s (or 10 s on a long shot), the design's `tStep`. */
	const gridT = $derived.by(() => {
		const step = total > 30 ? 10 : 5;
		const out: number[] = [];
		for (let t = 0; t <= total; t += step) out.push(t);
		return out;
	});

	/** The fill path closes the pressure curve down to the baseline. */
	const fillPath = $derived(
		`${pressurePath} L ${xFor(g, total)} ${yFor(g, 0)} L ${PAD_X} ${yFor(g, 0)} Z`
	);

	/** The SVG element, for mapping client coordinates into user space. */
	let svgEl: SVGSVGElement | undefined = $state();
	/** The id of the segment whose dot is mid-drag, or `null`. */
	let draggingId = $state<string | null>(null);

	/**
	 * Convert a pointer event's client coordinates into the SVG's user-space
	 * `[x, y]` — the inverse of the viewBox transform.
	 */
	function clientToSvg(event: PointerEvent): [number, number] {
		if (!svgEl) return [0, 0];
		const rect = svgEl.getBoundingClientRect();
		const x = ((event.clientX - rect.left) / rect.width) * width;
		const y = ((event.clientY - rect.top) / rect.height) * height;
		return [x, y];
	}

	/** Begin dragging a boundary dot — also selects its segment. */
	function startDrag(event: PointerEvent, id: string): void {
		event.preventDefault();
		draggingId = id;
		onSelect(id);
		(event.target as Element).setPointerCapture(event.pointerId);
	}

	/** While dragging, map the pointer back onto the segment's time / target. */
	function onDragMove(event: PointerEvent): void {
		if (draggingId == null) return;
		const index = segments.findIndex((s) => s.id === draggingId);
		if (index < 0) return;
		const [x, y] = clientToSvg(event);
		onEdit(draggingId, svgToSegment(segments, g, index, x, y));
	}

	/** End the drag. */
	function endDrag(event: PointerEvent): void {
		if (draggingId == null) return;
		try {
			(event.target as Element).releasePointerCapture(event.pointerId);
		} catch {
			// The capture may already be gone — harmless.
		}
		draggingId = null;
	}

	/** Keyboard nudging — arrows adjust the focused dot's target / time. */
	function onDotKey(event: KeyboardEvent, id: string): void {
		const seg = segments.find((s) => s.id === id);
		if (!seg) return;
		let { time, target } = seg;
		switch (event.key) {
			case 'ArrowUp':
				target = Math.min(Y_MAX, Math.round((target + 0.5) * 10) / 10);
				break;
			case 'ArrowDown':
				target = Math.max(0, Math.round((target - 0.5) * 10) / 10);
				break;
			case 'ArrowRight':
				time = Math.min(60, time + 1);
				break;
			case 'ArrowLeft':
				time = Math.max(1, time - 1);
				break;
			default:
				return;
		}
		event.preventDefault();
		onSelect(id);
		onEdit(id, { time, target });
	}
</script>

<div class="pe-curve">
	<div class="pe-curve-head">
		<div class="pe-curve-axislabel">PRESSURE · bar</div>
		<div class="pe-curve-legend">
			<span><i class="pe-leg-dot" style="background:var(--copper-400)"></i> Pressure</span>
			<span
				><i class="pe-leg-dot" style="background:rgba(140,193,207,0.7)"></i> Flow (est.)</span
			>
		</div>
	</div>
	<svg
		bind:this={svgEl}
		width="100%"
		viewBox="0 0 {width} {height}"
		style="display:block; touch-action:none"
		onpointermove={onDragMove}
		onpointerup={endDrag}
		onpointercancel={endDrag}
		role="presentation"
	>
		<!-- Y grid -->
		{#each gridY as p (p)}
			<line
				x1={PAD_X}
				y1={yFor(g, p)}
				x2={width - PAD_X}
				y2={yFor(g, p)}
				stroke="rgba(244,237,224,0.05)"
				stroke-width="1"
			/>
			<text
				x={PAD_X - 6}
				y={yFor(g, p) + 3}
				font-size="9"
				text-anchor="end"
				fill="rgba(244,237,224,0.35)"
				font-family="var(--font-mono)">{p}</text
			>
		{/each}
		<!-- X grid -->
		{#each gridT as t (t)}
			<line
				x1={xFor(g, t)}
				y1={PAD_Y}
				x2={xFor(g, t)}
				y2={height - PAD_Y}
				stroke="rgba(244,237,224,0.04)"
				stroke-width="1"
			/>
			<text
				x={xFor(g, t)}
				y={height - 3}
				font-size="9"
				text-anchor="middle"
				fill="rgba(244,237,224,0.35)"
				font-family="var(--font-mono)">{t}s</text
			>
		{/each}
		<!-- Active segment band -->
		{#if band}
			<rect
				x={band.x}
				y={PAD_Y}
				width={band.w}
				height={height - PAD_Y * 2}
				fill="rgba(193,116,75,0.06)"
			/>
		{/if}
		<!-- Flow ghost curve -->
		<path
			d={ghostPath}
			fill="none"
			stroke="rgba(140,193,207,0.5)"
			stroke-width="1.4"
			stroke-dasharray="3 3"
			stroke-linecap="round"
		/>
		<!-- Soft fill under the pressure curve -->
		<path d={fillPath} fill="url(#peFill)" opacity="0.18" />
		<!-- Pressure curve -->
		<path
			d={pressurePath}
			fill="none"
			stroke="var(--copper-400)"
			stroke-width="2"
			stroke-linecap="round"
			stroke-linejoin="round"
		/>
		<defs>
			<linearGradient id="peFill" x1="0" y1="0" x2="0" y2="1">
				<stop offset="0%" stop-color="var(--copper-400)" />
				<stop offset="100%" stop-color="var(--copper-400)" stop-opacity="0" />
			</linearGradient>
		</defs>
		<!-- Boundary dots — draggable -->
		{#each dots as d (d.id)}
			<g
				class="pe-dot"
				class:is-dragging={draggingId === d.id}
				onpointerdown={(e) => startDrag(e, d.id)}
				onkeydown={(e) => onDotKey(e, d.id)}
				role="slider"
				tabindex="0"
				aria-label="{segments.find((s) => s.id === d.id)?.name ?? 'Segment'} target"
				aria-valuenow={Math.round(segments.find((s) => s.id === d.id)?.target ?? 0)}
				aria-valuemin={0}
				aria-valuemax={Y_MAX}
			>
				<!-- A wide invisible hit target keeps the small dot easy to grab. -->
				<circle cx={d.x} cy={d.y} r="16" fill="transparent" />
				{#if activeSegId === d.id}
					<!-- Soft copper halo marks the active dot without bulk. -->
					<circle cx={d.x} cy={d.y} r="9" fill="rgba(193,116,75,0.16)" />
				{/if}
				<!-- One slim handle: a punched node on the curve when idle, a
				     solid copper dot when active. -->
				<circle
					class="pe-dot-handle"
					cx={d.x}
					cy={d.y}
					r={activeSegId === d.id ? 5 : 4}
					fill={activeSegId === d.id ? 'var(--copper-400)' : 'var(--espresso-900)'}
					stroke={activeSegId === d.id ? 'var(--espresso-900)' : 'rgba(244,237,224,0.5)'}
					stroke-width="1.5"
				/>
			</g>
		{/each}
	</svg>
	<div class="pe-curve-foot">
		<span>Total shot time</span>
		<span class="pe-curve-foot-val">{total.toFixed(0)} s</span>
	</div>
</div>

<style>
	.pe-curve {
		background: var(--espresso-900);
		border: 1px solid rgba(244, 237, 224, 0.05);
		border-radius: var(--radius-md);
		padding: 14px 14px 6px;
	}
	.pe-curve-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 0 4px 8px;
	}
	.pe-curve-axislabel {
		font-family: var(--font-sans);
		font-size: 10px;
		font-weight: 600;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(244, 237, 224, 0.45);
	}
	.pe-curve-legend {
		display: flex;
		gap: 14px;
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(244, 237, 224, 0.65);
	}
	.pe-curve-legend > span {
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.pe-leg-dot {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		display: inline-block;
	}
	.pe-curve-foot {
		display: flex;
		justify-content: space-between;
		align-items: baseline;
		padding: 6px 4px 0;
		font-family: var(--font-sans);
		font-size: 11px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(244, 237, 224, 0.45);
	}
	.pe-curve-foot-val {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 16px;
		letter-spacing: 0;
		text-transform: none;
		color: var(--copper-400);
	}
	.pe-dot {
		cursor: grab;
	}
	.pe-dot.is-dragging {
		cursor: grabbing;
	}
	.pe-dot:focus-visible {
		outline: none;
	}
	.pe-dot:focus-visible .pe-dot-handle {
		stroke: var(--copper-400);
		stroke-width: 2;
	}
</style>
