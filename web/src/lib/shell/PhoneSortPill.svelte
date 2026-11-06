<script lang="ts">
	/**
	 * `PhoneSortPill` — sort field and direction (Android `CremaSortControl`):
	 * the left half flips the direction, the right half opens the fields as a
	 * bottom sheet. Same `value` / `onchange` shape as `SortPill`.
	 */
	import ArrowDownIcon from 'phosphor-svelte/lib/ArrowDownIcon';
	import ArrowUpIcon from 'phosphor-svelte/lib/ArrowUpIcon';
	import CaretDownIcon from 'phosphor-svelte/lib/CaretDownIcon';
	import PhoneOptionSheet from './PhoneOptionSheet.svelte';

	interface SortValue {
		field: string;
		direction: 'asc' | 'desc';
	}

	let {
		value,
		options,
		onchange
	}: {
		value: SortValue;
		options: readonly { field: string; label: string }[];
		onchange: (next: SortValue) => void;
	} = $props();

	let open = $state(false);
	const sheetOptions = $derived(options.map((o) => ({ id: o.field, label: o.label })));
	const current = $derived(options.find((o) => o.field === value.field) ?? options[0]);
	const descending = $derived(value.direction === 'desc');
</script>

<div class="psort">
	<button
		type="button"
		class="psort-dir"
		aria-label={descending ? 'Sorted descending, switch to ascending' : 'Sorted ascending, switch to descending'}
		onclick={() => onchange({ field: value.field, direction: descending ? 'asc' : 'desc' })}
	>
		{#if descending}<ArrowDownIcon aria-hidden="true" />{:else}<ArrowUpIcon aria-hidden="true" />{/if}
	</button>
	<button
		type="button"
		class="psort-key"
		aria-haspopup="dialog"
		aria-label="Sort by: {current?.label}"
		onclick={() => (open = true)}
	>
		<span>{current?.label}</span>
		<CaretDownIcon aria-hidden="true" />
	</button>
</div>
<PhoneOptionSheet
	{open}
	title="Sort by"
	options={sheetOptions}
	value={value.field}
	onpick={(field) => onchange({ field, direction: value.direction })}
	onClose={() => (open = false)}
/>

<style>
	.psort {
		flex: none;
		display: inline-flex;
		align-items: stretch;
		height: 32px;
		border: 1px solid var(--ph-outline-variant);
		border-radius: 999px;
	}
	.psort-dir,
	.psort-key {
		position: relative;
		display: inline-flex;
		align-items: center;
		border: 0;
		background: transparent;
		color: var(--fg-3);
		font-family: var(--font-sans);
		font-size: 14px;
		font-weight: 500;
		white-space: nowrap;
		cursor: pointer;
	}
	.psort-dir::after,
	.psort-key::after {
		content: '';
		position: absolute;
		inset: -7px 0;
	}
	.psort-dir {
		padding: 0 10px;
		border-right: 1px solid var(--ph-outline-variant);
		border-radius: 999px 0 0 999px;
		font-size: 15px;
	}
	.psort-key {
		gap: 6px;
		padding: 0 10px 0 12px;
		border-radius: 0 999px 999px 0;
	}
	.psort-key :global(svg) {
		font-size: 13px;
	}
	.psort-dir:focus-visible,
	.psort-key:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
</style>
