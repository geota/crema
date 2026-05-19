<script lang="ts">
	/**
	 * `ProfileCurveEditor` — the interactive pressure-curve chart.
	 *
	 * The chart itself is **uPlot**, styled to match the Brew page's `LiveChart`:
	 * the same faded horizontal grid drawn in a `drawClear` hook, the same dashed
	 * vertical time grid, round-numbered "Ns" labels and container-filling
	 * `ResizeObserver` sizing. The pressure curve plots in `--tel-pressure` and a
	 * damped "estimated flow" ghost plots dashed in `--tel-flow`.
	 *
	 * A profile is an ordered list of `ProfileSegment`s, each smooth- or
	 * fast-ramping to its target. uPlot's per-series path renderer can't mix
	 * smooth and stepped ramps in one line, so the curve is **densely sampled**
	 * (`sampleCurve`) into a single linear-path series — fast ramps become
	 * vertical steps, smooth ramps become eased point clouds.
	 *
	 * The drag editing the design only *mocked* is **functional**. One handle
	 * per segment boundary is an absolutely-positioned DOM element, placed over
	 * the uPlot canvas with `uplot.valToPos`. Dragging maps the pointer back
	 * through `uplot.posToVal` to a `{ time, target }` patch and calls `onEdit` —
	 * the same callback the segment-list rows use, so both surfaces are two-way
	 * over one model. The active segment highlights with a copper band drawn
	 * behind the curve in the same `drawClear` hook.
	 */
	import { untrack } from 'svelte';
	import uPlot from 'uplot';
	import 'uplot/dist/uPlot.min.css';
	import {
		sampleCurve,
		totalTime,
		Y_MAX,
		type ProfileSegment
	} from '$lib/profiles';

	let {
		segments,
		activeSegId,
		onSelect,
		onEdit
	}: {
		/** The segment model to draw. */
		segments: ProfileSegment[];
		/** The id of the currently-selected segment, or `null`. */
		activeSegId: string | null;
		/** Select a segment (clicking its handle). */
		onSelect: (id: string) => void;
		/** Apply a `{ time, target }` patch to the segment with the given id. */
		onEdit: (id: string, patch: { time: number; target: number }) => void;
	} = $props();

	/** The damping the design applies to derive the "estimated flow" ghost. */
	function dampFlow(target: number): number {
		return Math.min(4, target * 0.35 + 0.5);
	}

	/** Resolve a CSS custom property to a concrete colour string. */
	function cssVar(name: string): string {
		if (typeof window === 'undefined') return '#888';
		return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#888';
	}

	/** The total shot time — the right edge of the x-axis. */
	const total = $derived(totalTime(segments));

	/**
	 * Build uPlot's `[x, pressure, flow]` column arrays. Both curves are
	 * sampled on their own time grid, then merged onto one shared, sorted x
	 * column (uPlot needs every series aligned to a single x array) — a missing
	 * sample on one curve becomes a `null` so uPlot interpolates across it.
	 */
	function toData(segs: readonly ProfileSegment[]): uPlot.AlignedData {
		const pressure = sampleCurve(segs);
		const flow = sampleCurve(segs, dampFlow);
		// One sorted, de-duplicated set of all sample times.
		const xs = [...new Set([...pressure.time, ...flow.time])].sort((a, b) => a - b);
		const pAt = new Map(pressure.time.map((t, i) => [t, pressure.value[i]]));
		const fAt = new Map(flow.time.map((t, i) => [t, flow.value[i]]));
		const pCol = xs.map((t) => pAt.get(t) ?? null);
		const fCol = xs.map((t) => fAt.get(t) ?? null);
		return [xs, pCol, fCol];
	}

	/** Round-numbered second marks — every 5 s, or 10 s on a long shot. */
	function timeSplits(max: number): number[] {
		const step = max > 30 ? 10 : 5;
		const out: number[] = [];
		for (let t = step; t < max; t += step) out.push(t);
		return out;
	}

	/**
	 * uPlot `drawClear` plugin: the faded horizontal y-grid and the copper
	 * active-segment band — both drawn *before* the series so the curve sits on
	 * top, exactly like `LiveChart`'s grid hook.
	 */
	function gridPlugin(): uPlot.Plugin {
		return {
			hooks: {
				drawClear: (u: uPlot) => {
					const ctx = u.ctx;
					const left = u.bbox.left;
					const right = u.bbox.left + u.bbox.width;
					const top = u.bbox.top;
					const bot = u.bbox.top + u.bbox.height;

					ctx.save();
					ctx.setLineDash([]);

					// The copper active-segment band, behind the curve.
					if (bandSpan) {
						const x0 = u.valToPos(bandSpan[0], 'x', true);
						const x1 = u.valToPos(bandSpan[1], 'x', true);
						ctx.fillStyle = 'rgba(193,116,75,0.06)';
						ctx.fillRect(x0, top, x1 - x0, bot - top);
					}

					// Fixed bar marks — the design's 0 / 3 / 6 / 9 / 12 grid.
					ctx.strokeStyle = 'rgba(244,237,224,0.05)';
					ctx.lineWidth = 1;
					for (const v of [0, 3, 6, 9, 12]) {
						const y = Math.round(u.valToPos(v, 'y', true)) + 0.5;
						ctx.beginPath();
						ctx.moveTo(left, y);
						ctx.lineTo(right, y);
						ctx.stroke();
					}
					ctx.restore();
				}
			}
		};
	}

	/**
	 * The `[startTime, endTime]` of the active segment, or `null` — the span
	 * the `drawClear` plugin tints copper. The plugin's draw hook reads this
	 * derived value directly, but the hook itself isn't reactive, so the redraw
	 * effect below nudges uPlot whenever the span changes.
	 */
	const bandSpan = $derived.by<[number, number] | null>(() => {
		if (activeSegId == null) return null;
		let t = 0;
		for (const s of segments) {
			if (s.id === activeSegId) return [t, t + s.time];
			t += s.time;
		}
		return null;
	});

	function buildOpts(w: number, h: number): uPlot.Options {
		const gridColor = 'rgba(244,237,224,0.05)';
		const labelColor = 'rgba(244,237,224,0.35)';
		const font = '11px "JetBrains Mono", monospace';
		return {
			width: w,
			height: h,
			padding: [10, 6, 4, 4],
			cursor: { show: false },
			legend: { show: false },
			scales: {
				// 0 → total shot time; a one-second floor avoids a zero-width axis
				// on an empty profile.
				x: {
					time: false,
					range: (_u, _min, dataMax) => [0, Math.max(1, Number.isFinite(dataMax) ? dataMax : 1)]
				},
				// A fixed 0–12 scale — the design's grid max, matching `Y_MAX`.
				y: { range: () => [0, Y_MAX] }
			},
			axes: [
				{
					scale: 'x',
					stroke: labelColor,
					grid: { stroke: gridColor, width: 1, dash: [2, 4] },
					ticks: { show: false },
					font,
					splits: (u) => timeSplits((u.scales.x.max ?? 1) as number),
					values: (_u, splits) => splits.map((v) => `${v}s`)
				},
				{
					// The y-grid is drawn by the plugin; the axis only labels it.
					scale: 'y',
					side: 3,
					stroke: labelColor,
					grid: { show: false },
					ticks: { show: false },
					font,
					size: 34,
					splits: () => [0, 3, 6, 9, 12],
					values: (_u, splits) => splits.map((v) => `${v}`)
				}
			],
			series: [
				{},
				{
					// The pressure curve — a single linear path over the dense samples.
					scale: 'y',
					stroke: () => cssVar('--tel-pressure'),
					width: 2.6,
					points: { show: false }
				},
				{
					// The damped "estimated flow" ghost, dashed.
					scale: 'y',
					stroke: () => cssVar('--tel-flow'),
					width: 1.6,
					dash: [3, 3],
					points: { show: false }
				}
			],
			plugins: [gridPlugin()]
		};
	}

	let plotEl: HTMLDivElement;
	let chart: uPlot | null = null;
	let resizeObs: ResizeObserver | null = null;

	/** A drag handle's screen position, in CSS px relative to the plot wrap. */
	interface Handle {
		/** The segment this handle terminates. */
		id: string;
		/** The handle's CSS-pixel X within `plotEl`. */
		x: number;
		/** The handle's CSS-pixel Y within `plotEl`. */
		y: number;
	}

	/** The live handle positions — recomputed on every chart draw / resize. */
	let handles = $state<Handle[]>([]);
	/** The id of the handle mid-drag, or `null`. */
	let draggingId = $state<string | null>(null);

	/**
	 * Recompute every handle's screen position from the current chart geometry.
	 * Each handle sits at a segment's *end* — cumulative time on x, target on y.
	 * `valToPos(..., false)` returns CSS pixels relative to the plotting area,
	 * which the absolutely-positioned overlay divs use directly.
	 */
	function syncHandles(): void {
		if (!chart) {
			handles = [];
			return;
		}
		const out: Handle[] = [];
		let t = 0;
		for (const s of segments) {
			t += s.time;
			out.push({
				id: s.id,
				x: chart.valToPos(t, 'x', false),
				y: chart.valToPos(Math.min(Y_MAX, Math.max(0, s.target)), 'y', false)
			});
		}
		handles = out;
	}

	// Create the chart once. `segments` is read untracked so a model edit feeds
	// the live instance (the effect below) rather than tearing uPlot down.
	$effect(() => {
		const w = Math.max(1, plotEl.clientWidth);
		const h = Math.max(1, plotEl.clientHeight);
		const initial = untrack(() => segments);
		chart = new uPlot(buildOpts(w, h), toData(initial), plotEl);
		syncHandles();

		// uPlot redraws on resize and `setData`; reposition the handles each time.
		resizeObs = new ResizeObserver((entries) => {
			const cr = entries[0].contentRect;
			chart?.setSize({
				width: Math.max(1, cr.width),
				height: Math.max(1, cr.height)
			});
			syncHandles();
		});
		resizeObs.observe(plotEl);

		return () => {
			resizeObs?.disconnect();
			resizeObs = null;
			chart?.destroy();
			chart = null;
		};
	});

	// Push model edits into the live chart. `setData` re-runs the x `range`
	// callback so the axis tracks the total time; then the handles re-sync.
	$effect(() => {
		const data = toData(segments);
		if (!chart) return;
		chart.setData(data);
		syncHandles();
	});

	// The active-segment band is plugin-drawn from `bandSpan`, so a selection
	// change has to trigger a redraw — reading `bandSpan` here keeps the effect
	// subscribed; `redraw(false)` re-runs the draw hooks without recomputing
	// the data.
	$effect(() => {
		void bandSpan;
		chart?.redraw(false);
	});

	/**
	 * Map a pointer event's client coordinates onto a `{ time, target }` patch
	 * for the segment with `id`. The handle sits at the segment end, so its x
	 * is cumulative time; the new `time` is that minus all preceding durations.
	 */
	function pointerToPatch(event: PointerEvent, id: string): { time: number; target: number } | null {
		if (!chart) return null;
		const index = segments.findIndex((s) => s.id === id);
		if (index < 0) return null;
		const rect = plotEl.getBoundingClientRect();
		// `posToVal` wants a CSS-pixel coordinate relative to the plotting area.
		const cumTime = chart.posToVal(event.clientX - rect.left, 'x');
		const value = chart.posToVal(event.clientY - rect.top, 'y');
		const startTime = segments.slice(0, index).reduce((a, s) => a + s.time, 0);
		const time = Math.min(60, Math.max(1, Math.round((cumTime - startTime) * 2) / 2));
		const target = Math.min(Y_MAX, Math.max(0, Math.round(value * 10) / 10));
		return { time, target };
	}

	/** Begin dragging a handle — also selects its segment. */
	function startDrag(event: PointerEvent, id: string): void {
		event.preventDefault();
		draggingId = id;
		onSelect(id);
		(event.currentTarget as Element).setPointerCapture(event.pointerId);
	}

	/** While dragging, map the pointer back onto the segment's time / target. */
	function onDragMove(event: PointerEvent, id: string): void {
		if (draggingId !== id) return;
		const patch = pointerToPatch(event, id);
		if (patch) onEdit(id, patch);
	}

	/** End the drag. */
	function endDrag(event: PointerEvent, id: string): void {
		if (draggingId !== id) return;
		try {
			(event.currentTarget as Element).releasePointerCapture(event.pointerId);
		} catch {
			// The capture may already be gone — harmless.
		}
		draggingId = null;
	}

	/** Keyboard nudging — arrows adjust the focused handle's target / time. */
	function onHandleKey(event: KeyboardEvent, id: string): void {
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
			<span><i class="pe-leg-dot" style="background:var(--tel-pressure)"></i> Pressure</span>
			<span><i class="pe-leg-dot" style="background:var(--tel-flow)"></i> Flow (est.)</span>
		</div>
	</div>
	<!-- The plot wrap is the positioning context for the handle overlay; the
	     uPlot canvas fills it and the handles sit absolutely on top. -->
	<div class="pe-curve-plotwrap">
		<div class="pe-curve-plot" bind:this={plotEl} aria-label="Pressure profile chart"></div>
		{#each handles as h (h.id)}
			{@const seg = segments.find((s) => s.id === h.id)}
			{@const active = activeSegId === h.id}
			<div
				class="pe-handle"
				class:is-active={active}
				class:is-dragging={draggingId === h.id}
				style="left:{h.x}px; top:{h.y}px"
				role="slider"
				tabindex="0"
				aria-label="{seg?.name ?? 'Segment'} target"
				aria-valuenow={Math.round(seg?.target ?? 0)}
				aria-valuemin={0}
				aria-valuemax={Y_MAX}
				onpointerdown={(e) => startDrag(e, h.id)}
				onpointermove={(e) => onDragMove(e, h.id)}
				onpointerup={(e) => endDrag(e, h.id)}
				onpointercancel={(e) => endDrag(e, h.id)}
				onkeydown={(e) => onHandleKey(e, h.id)}
			>
				<!-- A ~14 px invisible hit area keeps the small ring easy to grab. -->
				<span class="pe-handle-hit"></span>
				<span class="pe-handle-ring"></span>
				{#if active}
					<span class="pe-handle-core"></span>
				{/if}
			</div>
		{/each}
	</div>
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
	/* The plot wrap fills its column and gives the chart a fixed height; the
	   uPlot canvas + handle overlay both fill it. */
	.pe-curve-plotwrap {
		position: relative;
		width: 100%;
		height: 280px;
	}
	.pe-curve-plot {
		width: 100%;
		height: 100%;
	}
	.pe-curve-plot :global(.u-wrap) {
		margin: 0 auto;
	}
	/* One drag handle, absolutely placed over the canvas at its segment end. */
	.pe-handle {
		position: absolute;
		width: 0;
		height: 0;
		transform: translate(-50%, -50%);
		cursor: grab;
		touch-action: none;
		z-index: 2;
	}
	.pe-handle.is-dragging {
		cursor: grabbing;
	}
	.pe-handle:focus-visible {
		outline: none;
	}
	/* The invisible ~14 px grab target, centred on the handle. */
	.pe-handle-hit {
		position: absolute;
		left: 50%;
		top: 50%;
		width: 28px;
		height: 28px;
		transform: translate(-50%, -50%);
	}
	/* Resting ring: ~5 px radius, hairline. */
	.pe-handle-ring {
		position: absolute;
		left: 50%;
		top: 50%;
		width: 10px;
		height: 10px;
		transform: translate(-50%, -50%);
		border-radius: 50%;
		background: var(--espresso-950);
		border: 1px solid rgba(244, 237, 224, 0.55);
		box-sizing: border-box;
	}
	/* Active ring: ~6 px radius, copper. */
	.pe-handle.is-active .pe-handle-ring {
		width: 12px;
		height: 12px;
		border: 1.5px solid var(--copper-500);
	}
	.pe-handle:focus-visible .pe-handle-ring {
		border: 2px solid var(--copper-400);
	}
	/* Inner copper dot — active handles only. */
	.pe-handle-core {
		position: absolute;
		left: 50%;
		top: 50%;
		width: 4px;
		height: 4px;
		transform: translate(-50%, -50%);
		border-radius: 50%;
		background: var(--copper-400);
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
</style>
