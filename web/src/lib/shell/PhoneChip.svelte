<script lang="ts">
	/**
	 * `PhoneChip` — one filter chip (Android `CremaPhoneChip`): a 34 px outlined
	 * pill with a trailing count, filled when selected. The tap target reaches
	 * 44 px past the pill, into the row's padding.
	 */
	import Icon from '#lib/icons/Icon.svelte';

	let {
		label,
		count,
		selected = false,
		icon,
		title,
		onclick
	}: {
		label: string;
		count?: number;
		selected?: boolean;
		/** Phosphor icon class, e.g. `ph ph-star`. */
		icon?: string;
		title?: string;
		onclick: () => void;
	} = $props();
</script>

<button type="button" class={['pchip', { 'is-on': selected }]} aria-pressed={selected} {title} {onclick}>
	{#if icon}<Icon cls={icon} aria-hidden="true" />{/if}
	<span class="pchip-label">{label}</span>
	{#if count != null}<span class="pchip-count">{count}</span>{/if}
</button>

<style>
	.pchip {
		position: relative;
		flex: none;
		display: inline-flex;
		align-items: center;
		gap: 6px;
		height: 34px;
		max-width: 240px;
		padding: 0 14px;
		border: 1px solid var(--ph-outline);
		border-radius: 999px;
		background: transparent;
		color: var(--fg-3);
		font-family: var(--font-sans);
		font-size: 13px;
		cursor: pointer;
	}
	.pchip::after {
		content: '';
		position: absolute;
		inset: -6px 0;
	}
	.pchip.is-on {
		border-color: transparent;
		background: var(--ph-tonal);
		color: var(--ph-on-tonal);
	}
	.pchip :global(svg) {
		flex: none;
		font-size: 14px;
	}
	.pchip-label {
		overflow: hidden;
		font-weight: 500;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.pchip-count {
		opacity: 0.7;
		font-variant-numeric: tabular-nums;
	}
	.pchip:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
</style>
