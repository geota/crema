<script lang="ts">
	/**
	 * `PhoneDualChip` — one channel pair in the running shot (Android
	 * `DualChip`): the primary value large, its partner under a hairline. The
	 * four chips are the chart's legend, so each wears its channel's colour.
	 */
	let {
		label,
		value,
		unit,
		color,
		secondaryLabel,
		secondaryValue,
		secondaryUnit,
		secondaryColor
	}: {
		label: string;
		value: string;
		unit: string;
		color: string;
		secondaryLabel: string;
		secondaryValue: string;
		secondaryUnit: string;
		secondaryColor: string;
	} = $props();
</script>

<div class="pdual">
	<span class="pdual-l" style:color><span></span>{label}</span>
	<span class="pdual-v" style:color>{value}{#if unit}<em>{unit}</em>{/if}</span>
	<span class="pdual-sec" style:color={secondaryColor}>
		<span class="pdual-sl">{secondaryLabel}</span>
		<span class="pdual-sv">{secondaryValue}{#if secondaryUnit}<em>{secondaryUnit}</em>{/if}</span>
	</span>
</div>

<style>
	.pdual {
		display: flex;
		flex-direction: column;
		gap: 3px;
		min-width: 0;
		padding: 10px 12px;
		border-radius: 14px;
		background: var(--bg-surface);
	}
	.pdual-l {
		line-height: 1.2;
		display: inline-flex;
		align-items: center;
		gap: 5px;
		font-family: var(--font-sans);
		font-size: 9.5px;
		font-weight: 600;
		letter-spacing: 0.07em;
		text-transform: uppercase;
	}
	.pdual-l span {
		width: 6px;
		height: 6px;
		border-radius: 50%;
		background: currentColor;
	}
	.pdual-v {
		overflow: hidden;
		padding-bottom: 2px;
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.1);
		font-family: var(--font-mono);
		font-size: 27px;
		font-variant-numeric: tabular-nums;
		font-weight: 500;
		letter-spacing: -1px;
		line-height: 30px;
		white-space: nowrap;
	}
	.pdual-v em {
		margin-left: 3px;
		font-family: var(--font-sans);
		font-size: 11px;
		font-style: normal;
		font-weight: 400;
		letter-spacing: 0;
		color: var(--fg-3);
	}
	.pdual-sec {
		line-height: 1.2;
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: 6px;
		padding-top: 3px;
	}
	.pdual-sl {
		overflow: hidden;
		font-family: var(--font-sans);
		font-size: 8.5px;
		font-weight: 600;
		letter-spacing: 0.06em;
		text-overflow: ellipsis;
		text-transform: uppercase;
	}
	.pdual-sv {
		flex: none;
		font-family: var(--font-mono);
		font-size: 12px;
		font-variant-numeric: tabular-nums;
		font-weight: 500;
	}
	.pdual-sv em {
		margin-left: 2px;
		font-family: var(--font-sans);
		font-size: 9px;
		font-style: normal;
		color: var(--fg-3);
	}
</style>
