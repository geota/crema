<script lang="ts">
	/**
	 * `ShotRow` — one row in the History list, ported from `ShotRow` in
	 * `history-page.jsx`. Time, a tiny sparkline of the stored curve, the
	 * profile name, the ratio + yield metrics and a star rating.
	 */
	import type { StoredShot } from '$lib/history';
	import {
		grindLabel,
		isManualLog,
		methodOf,
		ratioLabel,
		peaksOf,
		yieldOf,
		flatSamplesOf
	} from '$lib/history';
	import { methodLabel } from '$lib/brew/methods';
	import StarRating from '$lib/components/common/StarRating.svelte';
	import MethodMark from '$lib/components/brewlog/MethodMark.svelte';
	import { getSettingsStore, convertWeight } from '$lib/settings';
	import MiniShotChart from './MiniShotChart.svelte';
	import MiniBrewChart from './MiniBrewChart.svelte';
	import Icon from '$lib/icons/Icon.svelte';
	import { relativeAgo } from '$lib/utils/relative-time';

	let {
		shot,
		active = false,
		selectable = false,
		selected = false,
		selectionDisabled = false,
		syncPip = 'local',
		syncTitle,
		onclick
	}: {
		/** The stored shot this row renders. */
		shot: StoredShot;
		/** Whether this row is the selected-detail one. */
		active?: boolean;
		/**
		 * Cloud status pip across every enabled destination — `uploaded`
		 * (🟢) when the shot is on all of them, `partial` (◐) when it is
		 * missing from one, `pending` (🟡) when it sits in the retry queue,
		 * `failed` (🔴) when the last attempt errored, `local` (⚪) when no
		 * destination is enabled.
		 */
		syncPip?: 'uploaded' | 'partial' | 'pending' | 'failed' | 'local';
		/** Tooltip naming the destinations, e.g. "On Visualizer · not on Decent". */
		syncTitle?: string;
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

	/**
	 * The row's second line — bean + the grind it was pulled at (issue #16:
	 * "what did I grind last time" is scannable without opening the detail),
	 * ending with the extraction time. Bean-less shots keep the original
	 * "Ns extraction" line.
	 */
	/** Row method — `null` for machine espresso (issue #10). */
	const method = $derived(methodOf(shot));
	/** Manually logged (no telemetry of any kind) → "logged" tag. */
	const manual = $derived(isManualLog(shot));

	/** "0:27" / "3:05" — mm:ss reads right for both shot and pourover. */
	function fmtTime(ms: number): string {
		const total = Math.round(ms / 1000);
		if (total < 90) return `${total} s`;
		return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
	}

	const rowBeanLine = $derived.by(() => {
		const parts: string[] = [];
		const b = shot.bean;
		if (b) {
			const name = b.name?.trim();
			const roaster = b.roasterName?.trim();
			if (roaster && name) parts.push(`${roaster} · ${name}`);
			else if (name || roaster) parts.push((name || roaster) as string);
		}
		const label = grindLabel(shot);
		if (label) parts.push(label);
		// Manual logs may have no recorded time — omit rather than "0 s".
		if (shot.record.duration > 0) {
			const secs = fmtTime(shot.record.duration);
			if (parts.length === 0) return `${secs} extraction`;
			parts.push(secs);
		}
		return parts.join(' · ');
	});

	/** A relative "ago" label for the shot — shared compact vocabulary (issue 43). */
	const ago = $derived(relativeAgo(shot.completedAt));

	/** Peaks derived once from the shot's wire-shape record. */
	const peaks = $derived(peaksOf(shot));
	/** Settled yield weight, grams (recorded `yieldOut`, else peak), for the metric. */
	const yieldOut = $derived(yieldOf(shot, peaks));
	/** The yield weight in the chosen weight unit (D1). */
	const settings = getSettingsStore();
	const yieldM = $derived(convertWeight(yieldOut, settings.current.weightUnit));

	/** Sparkline samples — flat shape derived from the wire record. */
	const sparkSeries = $derived(flatSamplesOf(shot));

	/** Star rating (0..5); coerce undefined/null to 0 (unrated). */
	const rating = $derived(shot.metadata.rating ?? 0);

	/**
	 * One glance-able line of text: the forward-looking plan when set
	 * (what to do next time — the most actionable thing a row can say),
	 * else the tasting notes. Empty → the line is omitted entirely so
	 * untouched rows keep today's exact layout.
	 */
	const noteSnippet = $derived.by(() => {
		const text = shot.metadata.nextPlan?.trim() || shot.metadata.notes?.trim() || '';
		return text.replace(/\s+/g, ' ');
	});
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
			<Icon name={selected ? 'check-square' : 'square'} weight="fill" />
		</div>
	{/if}
	<div class="hi-row-time">
		<div class="hi-row-time-h">{timeH}</div>
		<div class="hi-row-time-d">{ago}</div>
	</div>
	<div class="hi-row-spark">
		{#if shot.record.samples.length > 0}
			<MiniShotChart series={sparkSeries} width={96} height={32} />
		{:else if shot.brewSeries && shot.brewSeries.samples.length > 0}
			<MiniBrewChart series={shot.brewSeries} width={96} height={32} />
		{:else}
			<MethodMark {method} tile />
		{/if}
	</div>
	<div class="hi-row-main">
		<div class="hi-row-name">
			{shot.profileName ?? shot.recipeName ?? (method ? methodLabel(method) : 'Untitled shot')}
			{#if manual}<span class="hi-row-logged">· logged</span>{/if}
		</div>
		<div class="hi-row-bean">{rowBeanLine}</div>
		{#if noteSnippet}
			<div class="hi-row-note">{noteSnippet}</div>
		{/if}
	</div>
	<div class="hi-row-metric">
		<div class="hi-row-metric-val">{ratioLabel(shot)}</div>
		<div class="hi-row-metric-l">ratio</div>
	</div>
	<div class="hi-row-metric">
		{#if method && method !== 'espresso' && (shot.metadata.waterG ?? 0) > 0}
			<div class="hi-row-metric-val">
				{Math.round(shot.metadata.waterG ?? 0)}<em>g</em>
			</div>
			<div class="hi-row-metric-l">water</div>
		{:else}
			<div class="hi-row-metric-val">
				{yieldM.value}<em>{yieldM.unit}</em>
			</div>
			<div class="hi-row-metric-l">yield</div>
		{/if}
	</div>
	<div class="hi-row-stars" class:is-unrated={rating <= 0}>
			<StarRating rating={rating} />
		</div>
	<div
		class="hi-row-pip hi-pip-{syncPip}"
		title={syncTitle ??
			(syncPip === 'uploaded'
				? 'Uploaded'
				: syncPip === 'partial'
					? 'Missing from a destination'
					: syncPip === 'pending'
						? 'Upload pending — will retry'
						: syncPip === 'failed'
							? 'Upload failed — open settings to retry'
							: 'Not uploaded — local only')}
		aria-label="Cloud status: {syncPip}"
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
	/* Quiet provenance tag on manually logged brews. */
	.hi-row-logged {
		font-weight: 400;
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.5);
	}
	.hi-row-bean {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.5);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.hi-row-note {
		font-family: var(--font-sans);
		font-size: 11px;
		font-style: italic;
		color: rgba(var(--tint-rgb), 0.65);
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
	/* On some enabled destinations but not all — a hollow ring. */
	.hi-pip-partial {
		background: transparent;
		box-shadow: inset 0 0 0 1.5px var(--success, #2faa5a);
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
