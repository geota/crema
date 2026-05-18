<script lang="ts">
	/**
	 * `ChannelReadout` — one telemetry-channel tile, ported from the design's
	 * `ChannelReadout` in `ds/web-components.jsx`. Reuses the `.crema-readout*`
	 * class names.
	 *
	 * Wired to real data: the dashboard passes the latest pressure / flow /
	 * temp / weight value from `lib/state`.
	 */
	let {
		label,
		value,
		unit,
		color,
		target
	}: {
		/** Channel name — PRESSURE / FLOW / TEMP / WEIGHT. */
		label: string;
		/** The formatted current value (a placeholder dash when idle). */
		value: string;
		/** Unit shown after the value. */
		unit: string;
		/** Channel hue — a `--tel-*` token. */
		color: string;
		/** Optional target line shown below the value. */
		target?: string;
	} = $props();

	/** The Phosphor icon for each channel. */
	const ICONS: Record<string, string> = {
		PRESSURE: 'gauge',
		FLOW: 'drop',
		TEMP: 'thermometer',
		WEIGHT: 'scales'
	};
	const icon = $derived(ICONS[label] ?? 'circle');
</script>

<div class="crema-readout">
	<div class="crema-readout-head">
		<i class={'ph-duotone ph-' + icon} style="font-size:14px;color:{color}" aria-hidden="true"></i>
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
