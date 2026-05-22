<script lang="ts">
	/**
	 * `ChannelReadout` — one telemetry-channel tile, ported from the design's
	 * `ChannelReadout` in `ds/web-components.jsx`. Reuses the `.crema-readout*`
	 * class names.
	 *
	 * Now a dual readout: the primary (`label` / `value` / `color`) renders on
	 * the left, the secondary (`secondaryLabel` / `secondaryValue` /
	 * `secondaryColor`) on the right. Both columns are equal width; the
	 * primary's `target` (if any) sits below the value as before. When the
	 * secondary props are omitted the card collapses to single-column —
	 * unchanged from the original shape.
	 *
	 * Wired to real data: the dashboard passes the latest pressure / flow /
	 * temp / weight value from `lib/state`.
	 */
	let {
		label,
		value,
		unit,
		color,
		target,
		secondaryLabel,
		secondaryValue,
		secondaryUnit,
		secondaryColor,
		secondaryEnabled = false
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
		/** Secondary metric label — e.g. RESISTANCE / VOLUME / MIX / FLOW. */
		secondaryLabel?: string;
		/** Secondary value (already formatted). */
		secondaryValue?: string;
		/** Secondary unit shown after its value. */
		secondaryUnit?: string;
		/** Secondary hue — a `--tel-*-2` token. */
		secondaryColor?: string;
		/**
		 * Whether the secondary chart line is currently enabled. When false,
		 * the right column still renders (so the user can see the number) but
		 * the colour dot reads as "off" (hollow). Click handling lives on the
		 * Quick Sheet; this is display-only.
		 */
		secondaryEnabled?: boolean;
	} = $props();

	/** The Phosphor icon for each channel. */
	const ICONS: Record<string, string> = {
		PRESSURE: 'gauge',
		FLOW: 'drop',
		TEMP: 'thermometer',
		WEIGHT: 'scales'
	};
	const icon = $derived(ICONS[label] ?? 'circle');
	const hasSecondary = $derived(secondaryLabel != null && secondaryValue != null);
</script>

<div class="crema-readout" class:has-secondary={hasSecondary}>
	<div class="crema-readout-side crema-readout-primary">
		<div class="crema-readout-head">
			<i
				class={'ph-duotone ph-' + icon}
				style="font-size:14px;color:{color}"
				aria-hidden="true"
			></i>
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
				<span
					class="crema-readout-label"
					style="color:{secondaryEnabled ? secondaryColor : 'rgba(var(--tint-rgb), 0.5)'}"
					>{secondaryLabel}</span
				>
				<i
					class="ph crema-readout-dot"
					class:is-off={!secondaryEnabled}
					style={secondaryEnabled
						? `font-size:8px;color:${secondaryColor}`
						: 'font-size:8px;color:rgba(var(--tint-rgb), 0.35)'}
					aria-hidden="true"
					title={secondaryEnabled ? 'Plotted' : 'Hidden (toggle in Quick Sheet)'}
				>{secondaryEnabled ? '●' : '○'}</i>
			</div>
			<div class="crema-readout-val crema-readout-val-right">
				<span
					class="crema-readout-num"
					style="color:{secondaryEnabled
						? secondaryColor
						: 'rgba(var(--tint-rgb), 0.6)'}">{secondaryValue}</span
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
	}
	.crema-readout-secondary .crema-readout-val,
	.crema-readout-secondary .crema-readout-num {
		text-align: right;
	}
	.crema-readout-val-right {
		justify-content: flex-end;
	}
	.crema-readout-head-right {
		justify-content: flex-end;
	}
	.crema-readout-dot {
		font-family: var(--font-mono);
		line-height: 1;
		display: inline-block;
		margin-left: 4px;
	}
</style>
