<script lang="ts">
	import Icon from '$lib/icons/Icon.svelte';
	/**
	 * `StMaintenanceRow` — one upkeep item as a full-width settings row: glyph,
	 * title (with a "Due" pill when overdue), note, the tracked metric, its
	 * actions, and a progress bar against the interval.
	 *
	 * Replaces the three-across `StMaintenanceCard` grid. Those cards were the
	 * only tiled surface on a page built entirely from full-width rows, so the
	 * section read as two unrelated designs stacked — and the three columns
	 * squeezed each card's detail line into four ragged lines while the row form
	 * has the whole width for it.
	 *
	 * Deliberately the same anatomy as the Compose `MaintenanceRow` (20dp glyph,
	 * title + Due pill, note, value+unit, text action, 6dp track) so the two
	 * shells' Water & maintenance sections read identically.
	 */

	let {
		icon,
		title,
		note,
		metric,
		unit,
		pct,
		due = false,
		onRun,
		runLabel = 'Run',
		runDisabled = false,
		runDisabledReason,
		onComplete
	}: {
		/** Phosphor icon name, no `ph-` prefix. */
		icon: string;
		title: string;
		/** The status line — what has accumulated and against what interval. */
		note: string;
		/** Tracked value, already formatted; omitted for an untracked item. */
		metric?: string;
		/** Unit for [metric], e.g. `L`, `hr`, `%`. */
		unit?: string;
		/** Progress 0–100 toward the interval; omitted draws no track. */
		pct?: number;
		/** Past its interval — amber title pill, metric and bar. */
		due?: boolean;
		/** Runs the DE1 cycle, when this item has one. */
		onRun?: () => void;
		runLabel?: string;
		runDisabled?: boolean;
		/** Tooltip explaining a disabled Run (not connected, machine busy). */
		runDisabledReason?: string;
		/** Rebaselines the counter, when this item tracks one. */
		onComplete?: () => void;
	} = $props();
</script>

<div class="st-maintrow" class:is-due={due}>
	<div class="st-maintrow-main">
		<span class="st-maintrow-icon"><Icon cls={'ph ph-' + icon} aria-hidden="true" /></span>
		<div class="st-row-text">
			<div class="st-row-title">
				<span>{title}</span>
				{#if due}<span class="st-maintrow-due">Due</span>{/if}
			</div>
			<div class="st-row-sub">{note}</div>
		</div>
		{#if metric}
			<span class="st-maintrow-metric">{metric}{#if unit}<em>{unit}</em>{/if}</span>
		{/if}
		<span class="st-maintrow-actions">
			{#if onRun}
				<button
					class="st-maint-action"
					onclick={onRun}
					disabled={runDisabled}
					title={runDisabled ? runDisabledReason : undefined}>{runLabel}</button
				>
			{/if}
			{#if onComplete}
				<button class="st-maint-action" onclick={onComplete}>Mark complete</button>
			{/if}
		</span>
	</div>
	{#if pct != null}
		<div class="st-maintrow-bar">
			<span style="width:{Math.min(100, Math.max(0, pct))}%"></span>
		</div>
	{/if}
</div>

<style>
	/* Owns its row chrome rather than borrowing `.st-row`. That class is
	   redefined by the settings page as `.st-content .st-row { display: grid }`,
	   which outranks a scoped rule — the bar ended up in the grid's `auto`
	   column and collapsed to zero width, so every track rendered empty
	   regardless of its percentage. Padding and rule are copied from `.st-row`
	   so the row still lines up with its neighbours. */
	.st-maintrow {
		display: flex;
		flex-direction: column;
		gap: 10px;
		padding: 14px 18px;
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.04);
	}
	.st-maintrow:last-child {
		border-bottom: 0;
	}
	.st-maintrow-main {
		display: flex;
		align-items: center;
		gap: 12px;
	}
	.st-maintrow-main .st-row-text {
		flex: 1 1 auto;
		min-width: 0;
	}
	.st-maintrow-icon {
		display: inline-flex;
		color: rgba(var(--tint-rgb), 0.55);
		font-size: 20px;
	}
	.st-maintrow-due {
		font-family: var(--font-sans);
		font-size: 9px;
		font-weight: 600;
		letter-spacing: var(--track-allcaps, 0.06em);
		text-transform: uppercase;
		color: var(--warning);
		background: rgba(219, 167, 100, 0.16);
		border-radius: 999px;
		padding: 2px 8px;
		white-space: nowrap;
	}
	.st-maintrow-metric {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 15px;
		color: var(--fg-1);
		white-space: nowrap;
	}
	.is-due .st-maintrow-metric {
		color: var(--warning);
	}
	.st-maintrow-metric em {
		font-style: normal;
		font-family: var(--font-sans);
		font-size: 10px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.5);
		margin-inline-start: 3px;
	}
	.st-maintrow-actions {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		flex: 0 0 auto;
	}
	/* `.st-maint-action` is shared with the old card styling in
	   settings-page.css; it already carries the button treatment, but the card
	   used it as a block that pushed itself down. */
	.st-maintrow-actions :global(.st-maint-action) {
		margin-top: 0;
		align-self: center;
	}
	.st-maint-action:disabled {
		opacity: 0.45;
		cursor: not-allowed;
	}
	.st-maintrow-bar {
		height: 6px;
		border-radius: 999px;
		background: rgba(var(--tint-rgb), 0.08);
		overflow: hidden;
	}
	.st-maintrow-bar > span {
		display: block;
		height: 100%;
		border-radius: 999px;
		background: var(--copper-500);
	}
	.is-due .st-maintrow-bar > span {
		background: var(--warning);
	}
</style>
