<script lang="ts">
	/**
	 * `PhoneDropdownPill` — a filter that picks one of several (Android
	 * `CremaFilterDropdown`): a glyph, a hairline, the current choice and a
	 * caret. The choices open as a bottom sheet.
	 */
	import CaretDownIcon from 'phosphor-svelte/lib/CaretDownIcon';
	import Icon from '#lib/icons/Icon.svelte';
	import PhoneOptionSheet, { type PhoneOption } from './PhoneOptionSheet.svelte';

	let {
		icon,
		label,
		options,
		value,
		onpick
	}: {
		/** Phosphor icon class, e.g. `ph ph-calendar`. */
		icon: string;
		/** What the pill picks: the sheet's title and the pill's accessible name. */
		label: string;
		options: readonly PhoneOption[];
		value: string;
		onpick: (id: string) => void;
	} = $props();

	let open = $state(false);
	const current = $derived(options.find((o) => o.id === value) ?? options[0]);
</script>

<button
	type="button"
	class="pdrop"
	aria-haspopup="dialog"
	aria-label="{label}: {current?.label}"
	onclick={() => (open = true)}
>
	<span class="pdrop-glyph"><Icon cls={icon} aria-hidden="true" /></span>
	<span class="pdrop-label">{current?.label}</span>
	<CaretDownIcon aria-hidden="true" />
</button>
<PhoneOptionSheet {open} title={label} {options} {value} {onpick} onClose={() => (open = false)} />

<style>
	.pdrop {
		position: relative;
		flex: none;
		display: inline-flex;
		align-items: center;
		gap: 6px;
		height: 32px;
		max-width: 200px;
		padding: 0 10px 0 0;
		border: 1px solid var(--ph-outline-variant);
		border-radius: 999px;
		background: transparent;
		color: var(--fg-3);
		font-family: var(--font-sans);
		font-size: 14px;
		font-weight: 500;
		cursor: pointer;
	}
	.pdrop::after {
		content: '';
		position: absolute;
		inset: -7px 0;
	}
	.pdrop-glyph {
		display: inline-flex;
		align-items: center;
		align-self: stretch;
		padding: 0 10px;
		margin-right: 6px;
		border-right: 1px solid var(--ph-outline-variant);
		font-size: 15px;
	}
	.pdrop-label {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.pdrop > :global(svg) {
		flex: none;
		font-size: 13px;
	}
	.pdrop:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
</style>
