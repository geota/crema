<script lang="ts">
	/**
	 * `ShotRow` — one row in the History list, ported from `ShotRow` in
	 * `history-page.jsx`. Time, a tiny sparkline of the stored curve, the
	 * profile name, the ratio + yield metrics and a star rating.
	 */
	import type { StoredShot } from '$lib/history';
	import { ratioLabel, stars } from '$lib/history';
	import { getSettingsStore, convertWeight } from '$lib/settings';
	import MiniShotChart from './MiniShotChart.svelte';

	let {
		shot,
		active = false,
		selectable = false,
		selected = false,
		selectionDisabled = false,
		syncPip = 'local',
		onclick
	}: {
		/** The stored shot this row renders. */
		shot: StoredShot;
		/** Whether this row is the selected-detail one. */
		active?: boolean;
		/**
		 * Visualizer sync status pip — `uploaded` (🟢) when this shot has a
		 * remote id, `pending` (🟡) when it sits in the retry queue,
		 * `failed` (🔴) when the last attempt errored, `local` (⚪) when
		 * sync is off / pull-only. docs/36 §5.
		 */
		syncPip?: 'uploaded' | 'pending' | 'failed' | 'local';
		/**
		 * When true, the row is in **compare-select mode**: a leading
		 * checkbox replaces the active-row treatment, and clicking toggles
		 * {@link selected} via `onclick` instead of opening a detail view.
		 */
		selectable?: boolean;
		/** Whether the row is currently picked for compare (only meaningful when `selectable`). */
		selected?: boolean;
		/**
		 * When `true` (only checked in select mode), the row is disabled
		 * because the compare cap has been reached and this row is not one
		 * of the already-picked ones. The user can still click an
		 * already-picked row to unselect it.
		 */
		selectionDisabled?: boolean;
		/** Click handler — selects the shot in normal mode, or toggles its compare-selection in select mode. */
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

	/** Final (or peak) yield weight, grams, for the yield metric. */
	const yieldOut = $derived(shot.finalWeight ?? shot.peakWeight);
	/** The yield weight in the chosen weight unit (D1). */
	const settings = getSettingsStore();
	const yieldM = $derived(convertWeight(yieldOut, settings.current.weightUnit));

	/**
	 * The always-5-glyph star string — filled glyphs for the rating, empty for
	 * the rest. An unrated shot (`rating === 0`) shows five empty stars, so the
	 * column keeps a consistent rhythm rather than collapsing to a lone dash.
	 */
	const starString = $derived(stars(shot.rating));
</script>

<button
	class="hi-row"
	class:is-active={active && !selectable}
	class:is-selected={selectable && selected}
	class:is-selectmode={selectable}
	disabled={selectable && selectionDisabled && !selected}
	{onclick}
>
	{#if selectable}
		<div class="hi-row-check" aria-hidden="true">
			<i class="ph-fill {selected ? 'ph-check-square' : 'ph-square'}"></i>
		</div>
	{/if}
	<div class="hi-row-time">
		<div class="hi-row-time-h">{timeH}</div>
		<div class="hi-row-time-d">{ago}</div>
	</div>
	<div class="hi-row-spark">
		<MiniShotChart series={shot.series} width={96} height={32} />
	</div>
	<div class="hi-row-main">
		<div class="hi-row-name">{shot.profileName ?? 'Untitled shot'}</div>
		<div class="hi-row-bean">{(shot.duration / 1000).toFixed(0)} s extraction</div>
	</div>
	<div class="hi-row-metric">
		<div class="hi-row-metric-val">{ratioLabel(shot)}</div>
		<div class="hi-row-metric-l">ratio</div>
	</div>
	<div class="hi-row-metric">
		<div class="hi-row-metric-val">
			{yieldM.value}<em>{yieldM.unit}</em>
		</div>
		<div class="hi-row-metric-l">yield</div>
	</div>
	<div
		class="hi-row-stars"
		class:is-unrated={shot.rating <= 0}
		aria-label="{Math.max(0, Math.min(5, Math.round(shot.rating)))} of 5 stars"
	>
		{starString}
	</div>
	<div
		class="hi-row-pip hi-pip-{syncPip}"
		title={syncPip === 'uploaded'
			? 'Uploaded to Visualizer'
			: syncPip === 'pending'
				? 'Upload pending — will retry'
				: syncPip === 'failed'
					? 'Upload failed — open settings to retry'
					: 'Not uploaded — local only'}
		aria-label="Visualizer sync status: {syncPip}"
	></div>
</button>

<style>
	.hi-row {
		display: grid;
		/* Slot 2 (96 px) holds the MiniShotChart — the recorded shot's
		   pressure / flow / weight silhouette, the row's at-a-glance hook. */
		grid-template-columns: 60px 96px 1fr auto auto auto 8px;
		gap: 12px;
		align-items: center;
		padding: 10px 12px;
		background: transparent;
		border: 0;
		border-radius: var(--radius-sm);
		cursor: pointer;
		text-align: left;
		color: var(--fg-1);
		transition: background var(--dur-1) var(--ease);
	}
	.hi-row.is-selectmode {
		/* Reserve a slot for the leading checkbox without shifting the rest. */
		grid-template-columns: 22px 60px 96px 1fr auto auto auto 8px;
	}
	.hi-row:hover:not(:disabled) {
		background: rgba(var(--tint-rgb), 0.04);
	}
	.hi-row.is-active {
		background: rgba(193, 116, 75, 0.1);
		box-shadow: inset 2px 0 0 var(--copper-500);
	}
	.hi-row.is-selected {
		background: rgba(193, 116, 75, 0.14);
		box-shadow: inset 2px 0 0 var(--copper-500);
	}
	.hi-row:disabled {
		opacity: 0.42;
		cursor: not-allowed;
	}
	.hi-row-check {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		color: rgba(var(--tint-rgb), 0.5);
		font-size: 18px;
	}
	.hi-row.is-selected .hi-row-check {
		color: var(--copper-500);
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
		color: var(--fg-1);
	}
	.hi-row-time-d {
		font-family: var(--font-sans);
		font-size: 10px;
		color: rgba(var(--tint-rgb), 0.45);
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
		color: var(--fg-1);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.hi-row-bean {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.5);
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
		color: var(--fg-1);
	}
	.hi-row-metric-val em {
		font-style: normal;
		font-size: 10px;
		color: rgba(var(--tint-rgb), 0.5);
		margin-left: 1px;
	}
	.hi-row-metric-l {
		font-family: var(--font-sans);
		font-size: 9px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.4);
	}
	.hi-row-stars {
		font-family: var(--font-sans);
		font-size: 11px;
		color: var(--copper-400);
		letter-spacing: -1px;
		white-space: nowrap;
	}
	/* An unrated shot still shows five (dim) glyphs, keeping the column rhythm. */
	.hi-row-stars.is-unrated {
		color: rgba(var(--tint-rgb), 0.25);
	}

	/* Visualizer sync status pip — a tiny dot at the row's trailing edge. */
	.hi-row-pip {
		width: 6px;
		height: 6px;
		border-radius: 50%;
		justify-self: end;
		background: rgba(var(--tint-rgb), 0.2);
	}
	.hi-pip-uploaded {
		background: var(--success, #2faa5a);
	}
	.hi-pip-pending {
		background: var(--warning, #d9a55a);
		animation: hi-pip-pulse 1.6s ease-in-out infinite;
	}
	.hi-pip-failed {
		background: var(--danger, #cc4c4c);
	}
	@keyframes hi-pip-pulse {
		0%, 100% { opacity: 0.45; }
		50% { opacity: 1; }
	}

	.hi-spin {
		animation: hi-spin 1.1s linear infinite;
	}
	@keyframes hi-spin {
		from { transform: rotate(0); }
		to { transform: rotate(360deg); }
	}
</style>
