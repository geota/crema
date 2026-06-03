<script lang="ts">
	import Icon from '$lib/icons/Icon.svelte';
	/**
	 * `ChannelReadout` — one telemetry-channel tile, ported from the design's
	 * `ChannelReadout` in `ds/web-components.jsx`. Reuses the `.crema-readout*`
	 * class names.
	 *
	 * Dual layout: primary (`label` / `value` / `color`) on the left with the
	 * Phosphor `icon` next to its label; secondary (`secondaryLabel` /
	 * `secondaryValue` / `secondaryColor`) on the right. Both values render at
	 * **the same font size** so they read as peers — the secondary is not
	 * "diminished UI", just another live reading the user can study.
	 *
	 * Wired to real data: the dashboard passes the latest pressure / flow /
	 * temp / weight value from `lib/state`.
	 */
	let {
		icon,
		label,
		value,
		unit,
		color,
		target,
		secondaryLabel,
		secondaryValue,
		secondaryUnit,
		secondaryColor
	}: {
		/** Phosphor icon name (e.g. 'gauge', 'drop', 'thermometer', 'scales'). */
		icon: string;
		/** Primary metric name — PRESSURE / FLOW / HEAD / WEIGHT. */
		label: string;
		/** The formatted primary value (a placeholder dash when idle). */
		value: string;
		/** Unit shown after the primary value. */
		unit: string;
		/** Primary hue — a `--tel-*` token. */
		color: string;
		/** Optional target line shown below the primary value. */
		target?: string;
		/** Secondary metric name — e.g. RESISTANCE / VOLUME / MIX / FLOW. */
		secondaryLabel?: string;
		/** Secondary value (already formatted). */
		secondaryValue?: string;
		/** Secondary unit shown after its value. */
		secondaryUnit?: string;
		/** Secondary hue — a `--tel-*-2` token. */
		secondaryColor?: string;
	} = $props();

	const hasSecondary = $derived(secondaryLabel != null && secondaryValue != null);
</script>

<div class="crema-readout" class:has-secondary={hasSecondary}>
	<div class="crema-readout-side crema-readout-primary">
		<div class="crema-readout-head">
			<Icon
				cls={'ph-duotone ph-' + icon}
				style="font-size:14px;color:{color}"
				aria-hidden="true"
			 />
			<span class="crema-readout-label" style="color:{color}">{label}</span>
		</div>
		<div class="crema-readout-val">
			<span class="crema-readout-num" style="color:{color}">{value}</span>
			<span class="crema-readout-unit">{unit}</span>
		</div>
		{#if target != null}
			<div class="crema-readout-target">target {target} {unit}</div>
		{/if}
	</div>
	{#if hasSecondary}
		<div class="crema-readout-side crema-readout-secondary">
			<div class="crema-readout-head crema-readout-head-right">
				<span class="crema-readout-label" style="color:{secondaryColor}"
					>{secondaryLabel}</span
				>
			</div>
			<div class="crema-readout-val crema-readout-val-right">
				<span class="crema-readout-num" style="color:{secondaryColor}"
					>{secondaryValue}</span
				>
				<span class="crema-readout-unit">{secondaryUnit ?? ''}</span>
			</div>
		</div>
	{/if}
</div>

<style>
	.crema-readout.has-secondary {
		display: grid;
		grid-template-columns: 1fr 1fr;
		column-gap: 12px;
		align-items: start;
	}
	.crema-readout-val-right {
		justify-content: flex-end;
	}
	.crema-readout-head-right {
		justify-content: flex-end;
	}
</style>
