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
	import { theme } from '$lib/theme.svelte';

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

	/** The temperature axis range, °C — the per-segment editable bounds. */
	const TEMP_MIN = 80;
	const TEMP_MAX = 105;
	/** A stepped path builder — temperature holds across a segment, then jumps. */
	const tempStepPath = uPlot.paths.stepped?.({ align: 1 });

	/** The total shot time — the right edge of the x-axis. */
	const total = $derived(totalTime(segments));

	/**
	 * Build uPlot's `[x, pressure, flow]` column arrays. `sampleCurve` samples
	 * on a time grid set purely by the segments and their ramps — the `damp`
	 * function only rescales the *values* — so the pressure and flow samples
	 * share an identical, strictly-increasing time column and feed uPlot
	 * directly. (An earlier merge-and-dedupe collapsed the two same-x points a
	 * fast step needs; `sampleCurve` now offsets the step by `STEP_EPS` so the
	 * column stays strictly increasing and the step survives intact.)
	 */
	function toData(segs: readonly ProfileSegment[]): uPlot.AlignedData {
		const pressure = sampleCurve(segs);
		const flow = sampleCurve(segs, dampFlow);
		// Temperature is a per-segment hold — the DE1 frame temperature steps
		// at each boundary — so it is the containing segment's temp at every
		// sample time, drawn with a stepped path on its own right-hand axis.
		const temp = pressure.time.map((x) => tempAt(segs, x));
		return [pressure.time, pressure.value, flow.value, temp];
	}

	/** The target temperature of the segment a given elapsed time falls in. */
	function tempAt(segs: readonly ProfileSegment[], x: number): number {
		let t = 0;
		for (const s of segs) {
			if (x < t + s.time) return s.temperatureC;
			t += s.time;
		}
		return segs[segs.length - 1]?.temperatureC ?? 93;
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
						ctx.fillStyle = cssVar('--chart-band');
						ctx.fillRect(x0, top, x1 - x0, bot - top);
					}

					// Fixed bar marks — the design's 0 / 3 / 6 / 9 / 12 grid.
					ctx.strokeStyle = cssVar('--chart-grid');
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
		// Canvas strokes can't resolve `var()` — resolve the chart tokens here.
		const gridColor = cssVar('--chart-grid');
		const labelColor = cssVar('--chart-axis-label');
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
				y: { range: () => [0, Y_MAX] },
				// Temperature on its own scale + right axis, so it isn't
				// squeezed into the 0–12 bar / flow range.
				temp: { range: () => [TEMP_MIN, TEMP_MAX] }
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
				},
				{
					// The temperature axis — right-hand side, °C.
					scale: 'temp',
					side: 1,
					stroke: labelColor,
					grid: { show: false },
					ticks: { show: false },
					font,
					size: 38,
					splits: () => [80, 85, 90, 95, 100, 105],
					values: (_u, splits) => splits.map((v) => `${v}°`)
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
				},
				{
					// The per-segment temperature — a stepped line on the °C axis.
					scale: 'temp',
					stroke: () => cssVar('--tel-temp'),
					width: 2,
					paths: tempStepPath,
					points: { show: false }
				}
			],
			plugins: [gridPlugin()]
		};
	}

	// `bind:this` target read inside the create `$effect` — `$state` so the
	// effect re-runs once the element is attached. `chart` / `resizeObs` are
	// purely imperative handles, so they stay plain `let`.
	let plotEl = $state<HTMLDivElement>();
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
	 *
	 * Position is computed in **device pixels** from the uPlot canvas
	 * (`valToPos(…, true)`), then converted to CSS pixels and offset by the
	 * canvas's own rect within the plot wrap. Measuring the canvas rect is
	 * robust to the axis insets and any wrap offset — no guessing uPlot's
	 * CSS-coordinate origin, which is what left the handles off the curve.
	 */
	function syncHandles(): void {
		if (!chart || !plotEl) {
			handles = [];
			return;
		}
		const cRect = chart.ctx.canvas.getBoundingClientRect();
		const wRect = plotEl.getBoundingClientRect();
		const dpr = window.devicePixelRatio || 1;
		const offX = cRect.left - wRect.left;
		const offY = cRect.top - wRect.top;
		const out: Handle[] = [];
		let t = 0;
		for (const s of segments) {
			t += s.time;
			out.push({
				id: s.id,
				x: offX + chart.valToPos(t, 'x', true) / dpr,
				y: offY + chart.valToPos(Math.min(Y_MAX, Math.max(0, s.target)), 'y', true) / dpr
			});
		}
		handles = out;
	}

	// Create the chart once. `segments` is read untracked so a model edit feeds
	// the live instance (the effect below) rather than tearing uPlot down.
	// `syncHandles()` also reads `segments`, so it too must be `untrack`ed —
	// without it a segment edit would re-run this whole effect and rebuild the
	// uPlot instance, matching the `toData` guard above it.
	$effect(() => {
		// Read the `bind:this` target tracked so the effect runs once the
		// element is attached; everything after is `untrack`ed.
		const el = plotEl;
		if (!el) return;
		untrack(() => {
			const w = Math.max(1, el.clientWidth);
			const h = Math.max(1, el.clientHeight);
			chart = new uPlot(buildOpts(w, h), toData(segments), el);
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
			resizeObs.observe(el);
		});

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

	// Repaint on theme flip — axis/grid colours are baked into `buildOpts` at
	// creation, so rebuild the instance against the new tokens, then re-sync
	// the drag handles to the fresh geometry.
	$effect(() => {
		theme.current;
		untrack(() => {
			const el = plotEl;
			if (!el || !chart) return;
			const w = Math.max(1, el.clientWidth);
			const h = Math.max(1, el.clientHeight);
			chart.destroy();
			chart = new uPlot(buildOpts(w, h), toData(segments), el);
			syncHandles();
		});
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
		// The exact inverse of `syncHandles`: map the pointer through
		// `posToVal` in device pixels, measured from the canvas rect.
		const cRect = chart.ctx.canvas.getBoundingClientRect();
		const dpr = window.devicePixelRatio || 1;
		const cumTime = chart.posToVal((event.clientX - cRect.left) * dpr, 'x', true);
		const value = chart.posToVal((event.clientY - cRect.top) * dpr, 'y', true);
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
			<span><i class="pe-leg-dot" style="background:var(--tel-temp)"></i> Temp</span>
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
		background: var(--bg-surface);
		border: 1px solid rgba(var(--tint-rgb), 0.05);
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
		color: rgba(var(--tint-rgb), 0.45);
	}
	.pe-curve-legend {
		display: flex;
		gap: 14px;
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.65);
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
		background: var(--bg-page);
		border: 1px solid rgba(var(--tint-rgb), 0.55);
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
		color: rgba(var(--tint-rgb), 0.45);
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
