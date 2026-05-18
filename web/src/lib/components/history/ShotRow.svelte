<script lang="ts">
	/**
	 * `ShotRow` — one row in the History list, ported from `ShotRow` in
	 * `history-page.jsx`. Time, a tiny sparkline of the stored curve, the
	 * profile name, the ratio + yield metrics and a star rating.
	 */
	import type { ShotRecord } from '$lib/history';
	import { ratioLabel, stars } from '$lib/history';
	import QSparkline, { type SparkShape } from '$lib/components/brew/QSparkline.svelte';

	let {
		shot,
		active = false,
		onclick
	}: {
		/** The shot record this row renders. */
		shot: ShotRecord;
		/** Whether this row is the selected one. */
		active?: boolean;
		/** Click handler — selects the shot. */
		onclick: () => void;
	} = $props();

	/** Wall-clock time of the shot, `HH:mm`. */
	const timeH = $derived(
		new Date(shot.completedAt).toLocaleTimeString('en-GB', {
			hour: '2-digit',
			minute: '2-digit'
		})
	);

	/** A relative "ago" label for the shot. */
	const ago = $derived.by(() => {
		const ms = Date.now() - shot.completedAt;
		const min = Math.round(ms / 60000);
		if (min < 1) return 'just now';
		if (min < 60) return `${min} min ago`;
		const hr = Math.round(min / 60);
		if (hr < 24) return `${hr} hour${hr === 1 ? '' : 's'} ago`;
		const d = Math.round(hr / 24);
		if (d < 7) return `${d} day${d === 1 ? '' : 's'} ago`;
		return `${Math.round(d / 7)} wk ago`;
	});

	/**
	 * Pick a sparkline silhouette from the curve's peak pressure — a rough,
	 * purely-cosmetic classification, the History-page analogue of the
	 * profile library's `sparkShape`.
	 */
	const shape = $derived<SparkShape>(
		shot.peakPressure >= 10
			? 'classic'
			: shot.peakPressure >= 7
				? 'rao'
				: shot.peakPressure >= 4
					? 'turbo'
					: 'blooming'
	);

	/** Final (or peak) yield weight, grams, for the yield metric. */
	const yieldG = $derived(shot.finalWeightG ?? shot.peakWeightG);
</script>

<button class="hi-row" class:is-active={active} {onclick}>
	<div class="hi-row-time">
		<div class="hi-row-time-h">{timeH}</div>
		<div class="hi-row-time-d">{ago}</div>
	</div>
	<div class="hi-row-spark">
		<QSparkline
			{shape}
			width={48}
			height={20}
			color={active ? 'var(--copper-400)' : 'rgba(244,237,224,0.45)'}
		/>
	</div>
	<div class="hi-row-main">
		<div class="hi-row-name">{shot.profileName ?? 'Untitled shot'}</div>
		<div class="hi-row-bean">{(shot.durationMs / 1000).toFixed(0)} s extraction</div>
	</div>
	<div class="hi-row-metric">
		<div class="hi-row-metric-val">{ratioLabel(shot)}</div>
		<div class="hi-row-metric-l">ratio</div>
	</div>
	<div class="hi-row-metric">
		<div class="hi-row-metric-val">
			{yieldG != null ? yieldG.toFixed(1) : '–'}<em>g</em>
		</div>
		<div class="hi-row-metric-l">yield</div>
	</div>
	<div class="hi-row-stars">{shot.rating > 0 ? stars(shot.rating) : '–'}</div>
</button>

<style>
	.hi-row {
		display: grid;
		grid-template-columns: 60px 56px 1fr auto auto auto;
		gap: 12px;
		align-items: center;
		padding: 10px 12px;
		background: transparent;
		border: 0;
		border-radius: var(--radius-sm);
		cursor: pointer;
		text-align: left;
		color: var(--ink-50);
		transition: background var(--dur-1) var(--ease);
	}
	.hi-row:hover {
		background: rgba(244, 237, 224, 0.04);
	}
	.hi-row.is-active {
		background: rgba(193, 116, 75, 0.1);
		box-shadow: inset 2px 0 0 var(--copper-500);
	}
	.hi-row-time {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.hi-row-time-h {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 12px;
		color: var(--ink-50);
	}
	.hi-row-time-d {
		font-family: var(--font-sans);
		font-size: 10px;
		color: rgba(244, 237, 224, 0.45);
	}
	.hi-row-spark {
		display: flex;
		align-items: center;
		justify-content: center;
	}
	.hi-row-main {
		display: flex;
		flex-direction: column;
		gap: 2px;
		min-width: 0;
	}
	.hi-row-name {
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 500;
		color: var(--ink-50);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.hi-row-bean {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(244, 237, 224, 0.5);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.hi-row-metric {
		text-align: right;
	}
	.hi-row-metric-val {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 13px;
		color: var(--ink-50);
	}
	.hi-row-metric-val em {
		font-style: normal;
		font-size: 10px;
		color: rgba(244, 237, 224, 0.5);
		margin-left: 1px;
	}
	.hi-row-metric-l {
		font-family: var(--font-sans);
		font-size: 9px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(244, 237, 224, 0.4);
	}
	.hi-row-stars {
		font-family: var(--font-sans);
		font-size: 11px;
		color: var(--copper-400);
		letter-spacing: -1px;
		white-space: nowrap;
	}
</style>
