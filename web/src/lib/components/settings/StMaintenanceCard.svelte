<script lang="ts">
	/**
	 * `StMaintenanceCard` — one maintenance metric card (filter / descale /
	 * backflush). Ported from the design's `MaintenanceCard`.
	 *
	 * UI-only: real filter-life / descale tracking would need the DE1's water
	 * counters, which the shell does not expose. The figures shown are the
	 * design's placeholders; "Mark complete" is a stub.
	 */
	let {
		icon,
		title,
		state: stateText,
		stateOk,
		metric,
		metricLabel,
		detail,
		onComplete
	}: {
		icon: string;
		title: string;
		state: string;
		stateOk: boolean;
		metric: string;
		metricLabel: string;
		detail: string;
		onComplete?: () => void;
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
	<button type="button" class="st-maint-action" onclick={() => onComplete?.()}
		>Mark complete</button
	>
</div>
