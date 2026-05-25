<script lang="ts">
	/**
	 * `StMaintenanceCard` — one maintenance metric card (filter / descale /
	 * clean). Ported from the design's `MaintenanceCard`.
	 *
	 * A pure presentational component: every figure is passed in as a prop.
	 * `WaterSection` feeds it the real, derived readouts from the
	 * `lib/maintenance` store (which integrates the DE1's group flow into a
	 * persisted litre counter), and wires "Mark complete" to the store's
	 * rebaseline actions.
	 *
	 * Cards that have a DE1-driven cycle (Descale, Clean, SteamRinse) can pass
	 * `onRun` + `runLabel` to surface a primary "Run now" button alongside the
	 * existing "Mark complete" action. The button can be disabled via
	 * `runDisabled` + `runDisabledReason` (tooltip) — the parent gates by DE1
	 * connection + firmware machine state.
	 */
	let {
		icon,
		title,
		state: stateText,
		stateOk,
		metric,
		metricLabel,
		detail,
		onComplete,
		onRun,
		runLabel,
		runDisabled = false,
		runDisabledReason
	}: {
		icon: string;
		title: string;
		state: string;
		stateOk: boolean;
		metric: string;
		metricLabel: string;
		detail: string;
		onComplete?: () => void;
		/** Fires when the user taps the "Run now" button. Omit to hide the button. */
		onRun?: () => void;
		/** Label for the run-now button (e.g. `'Run descale'`). */
		runLabel?: string;
		/** Whether the run-now button is disabled (machine not in a runnable state). */
		runDisabled?: boolean;
		/** Hover tooltip explaining why the button is disabled. */
		runDisabledReason?: string;
	} = $props();
</script>

<div class="st-maint">
	<div class="st-maint-head">
		<i class={'ph-duotone ph-' + icon} aria-hidden="true"></i>
		<div class="st-maint-title">{title}</div>
	</div>
	<div class="st-maint-state" class:is-warn={!stateOk}>{stateText}</div>
	<div class="st-maint-metric"><span>{metric}</span><em>{metricLabel}</em></div>
	<div class="st-maint-detail">{detail}</div>
	<div class="st-maint-actions">
		{#if onRun && runLabel}
			<button
				type="button"
				class="st-maint-action st-maint-action-primary"
				onclick={() => onRun?.()}
				disabled={runDisabled}
				title={runDisabled ? runDisabledReason : undefined}
			>
				{runLabel}
			</button>
		{/if}
		<button
			type="button"
			class="st-maint-action"
			onclick={() => onComplete?.()}>Mark complete</button
		>
	</div>
</div>

<style>
	/* The grid layout + base `.st-maint-action` styling live in the settings
	   stylesheet (settings-page.css ported); the additions below only apply
	   when this card has been upgraded with a Run-now button. */
	.st-maint-actions {
		display: flex;
		flex-wrap: wrap;
		gap: 8px;
	}
	.st-maint-action-primary {
		background: rgba(var(--copper-rgb), 0.16);
		color: var(--copper-400);
		border-color: rgba(var(--copper-rgb), 0.35);
	}
	.st-maint-action-primary:hover:not(:disabled) {
		background: rgba(var(--copper-rgb), 0.22);
	}
	.st-maint-action:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}
</style>
