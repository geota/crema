<script lang="ts">
	/**
	 * `InspectableShotChart` — the History chart plus its readout bar (#298).
	 * The bar sits above the plot and shows the time and one coloured value per
	 * drawn line for the sample under the finger. Its cells stay laid out while
	 * idle, so scrubbing never moves the chart.
	 *
	 * Gestures. Inline: a horizontal drag scrubs, a tap expands, and a vertical
	 * drag stays with the page (`touch-action: pan-y`, and `classifyDrag` for a
	 * mouse). Expanded: a tap or drag scrubs. The last position stays on release
	 * so it can be read, and clears when the shot changes.
	 *
	 * uPlot's cursor is off, as on every chart here, so this uses pointer events
	 * on the wrapper. `brew/LiveChart` does the same for its pin.
	 */
	import { getSettingsStore } from '#lib/settings/index.js';
	import {
		chartReadout,
		classifyDrag,
		nearestSampleIndex,
		type ChartChannel
	} from '#lib/history/chart-readout.js';
	import type { TelemetrySample } from '#lib/state/index.js';
	import StaticShotChart from './StaticShotChart.svelte';

	let {
		series,
		channels,
		height = 360,
		expanded = false,
		onexpand,
		externalExpand = false
	}: {
		/** The stored shot-telemetry series, oldest first. */
		series: readonly TelemetrySample[];
		/** The lines the chart draws, from `drawnChannels`. */
		channels: readonly ChartChannel[];
		/** Plot height, px. Ignored when `expanded`, which fills the parent. */
		height?: number;
		/** Fill the chart modal, where a tap scrubs instead of expanding. */
		expanded?: boolean;
		/** Inline only: called on a tap on the plot. */
		onexpand?: () => void;
		/**
		 * The page has its own Enlarge button, so the tap target stays out of
		 * the tab order and the accessibility tree.
		 */
		externalExpand?: boolean;
	} = $props();

	const settings = getSettingsStore();

	/** Index into `series`; a writable derived, so a new shot clears it. */
	let index = $derived.by((): number | null => {
		void series;
		return null;
	});

	const readout = $derived(
		chartReadout(index === null ? null : (series[index] ?? null), channels, {
			weight: settings.current.weightUnit,
			temp: settings.current.tempUnit,
			pressure: settings.current.pressureUnit,
			volume: settings.current.volumeUnit
		})
	);

	let chart = $state<StaticShotChart>();
	let fillHeight = $state(0);

	/** The press in progress, until it scrubs, ends or goes to the page. */
	let press: { id: number; x: number; y: number; scrubbing: boolean } | null = null;
	/** The click after a mouse scrub isn't a tap. */
	let swallowClick = false;

	function scrubTo(clientX: number): void {
		const elapsed = chart?.elapsedAtClientX(clientX);
		if (elapsed == null) return;
		const i = nearestSampleIndex(series, elapsed);
		if (i >= 0) index = i;
	}

	function beginScrub(e: PointerEvent): void {
		if (!press) return;
		press.scrubbing = true;
		swallowClick = !expanded;
		try {
			(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
		} catch {
			// Capture only keeps a drag alive past the plot's edge.
		}
		scrubTo(e.clientX);
	}

	function onPointerDown(e: PointerEvent): void {
		if (e.button !== 0) return;
		swallowClick = false;
		press = { id: e.pointerId, x: e.clientX, y: e.clientY, scrubbing: false };
		if (expanded) beginScrub(e);
	}

	function onPointerMove(e: PointerEvent): void {
		if (!press || e.pointerId !== press.id) return;
		if (press.scrubbing) {
			scrubTo(e.clientX);
			return;
		}
		const kind = classifyDrag(e.clientX - press.x, e.clientY - press.y);
		if (kind === 'scroll') press = null;
		else if (kind === 'scrub') beginScrub(e);
	}

	function onPointerEnd(e: PointerEvent): void {
		if (press?.id === e.pointerId) press = null;
	}

	function onHitClick(): void {
		if (swallowClick) swallowClick = false;
		else onexpand?.();
	}
</script>

<div class={['isc', { expanded }]}>
	<div class="isc-bar">
		<div class={['isc-cells', { idle: index === null }]} style:--cells={readout.fields.length + 1}>
			<span class="isc-cell isc-time"><span class="isc-sr">Time </span>{readout.time}</span>
			{#each readout.fields as field (field.channel)}
				<span class="isc-cell" style:color={field.color}
					><span class="isc-sr">{field.label} </span>{field.value}{field.unit
						? ` ${field.unit}`
						: ''}</span
				>
			{/each}
		</div>
		{#if index === null}
			<span class="isc-hint">
				{expanded ? 'Tap or drag to read values' : 'Drag sideways to read values'}
			</span>
		{/if}
	</div>
	<div
		class="isc-plot"
		role="presentation"
		bind:clientHeight={fillHeight}
		onpointerdown={onPointerDown}
		onpointermove={onPointerMove}
		onpointerup={onPointerEnd}
		onpointercancel={onPointerEnd}
	>
		<StaticShotChart
			bind:this={chart}
			{series}
			height={expanded ? Math.max(fillHeight, 120) : height}
			scaleFlow
			markerIndex={index}
		/>
		{#if onexpand && !expanded}
			<button
				type="button"
				class="isc-hit"
				aria-label="Enlarge chart"
				aria-hidden={externalExpand ? 'true' : undefined}
				tabindex={externalExpand ? -1 : undefined}
				onclick={onHitClick}
			></button>
		{/if}
	</div>
</div>

<style>
	.isc {
		min-width: 0;
	}
	.isc.expanded {
		display: flex;
		flex-direction: column;
		height: 100%;
	}
	.isc-bar {
		position: relative;
		/* Room for a corner button in the chart card or the modal. */
		padding: 0 var(--readout-inset-end, 0px) 4px 4px;
	}
	.isc.expanded .isc-bar {
		padding-right: 32px;
	}
	/* The cell count and the bar's width set the rows, never the values, so
	   the height can't change mid-scrub. */
	.isc-cells {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(54px, 1fr));
		max-width: calc(var(--cells) * 84px);
		font-family: var(--font-mono);
		font-size: 10.5px;
		line-height: 16px;
		white-space: nowrap;
	}
	.isc-cells.idle {
		visibility: hidden;
	}
	.isc-time {
		color: var(--fg-2);
	}
	.isc-sr {
		position: absolute;
		width: 1px;
		height: 1px;
		overflow: hidden;
		clip-path: inset(50%);
	}
	.isc-hint {
		position: absolute;
		top: 0;
		left: 4px;
		font-family: var(--font-sans);
		font-size: 11px;
		line-height: 16px;
		color: var(--fg-3);
		pointer-events: none;
	}
	.isc-plot {
		position: relative;
		overflow: hidden;
		touch-action: pan-y;
		user-select: none;
		-webkit-user-select: none;
	}
	.isc.expanded .isc-plot {
		flex: 1 1 auto;
		min-height: 0;
		cursor: crosshair;
		touch-action: none;
	}
	.isc-hit {
		position: absolute;
		inset: 0;
		border: 0;
		border-radius: 8px;
		background: transparent;
		cursor: zoom-in;
		touch-action: pan-y;
	}
	.isc-hit:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
</style>
