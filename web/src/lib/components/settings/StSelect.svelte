<script lang="ts">
	import CaretDownIcon from 'phosphor-svelte/lib/CaretDownIcon';
	/**
	 * `StSelect` — a styled `<select>` with a caret affordance. Ported from the
	 * design's `StSelect` (`settings-page.jsx`).
	 */
	import { tick } from 'svelte';

	interface Option {
		value: string;
		label: string;
	}

	let {
		value,
		options,
		onChange,
		disabled = false
	}: {
		value: string;
		options: Option[];
		onChange?: (next: string) => void;
		disabled?: boolean;
	} = $props();

	let selectEl: HTMLSelectElement | undefined;

	// Re-sync DOM .value to the controlled `value` prop after every change.
	// Why: Svelte only re-applies `value={...}` when the expression itself
	// changes. If the parent's `onChange` opens a confirmation modal instead
	// of committing, the prop stays put — but the DOM <select> already shows
	// the user's pick. Without this revert, "Cancel" leaves the select
	// displaying a value the app never actually adopted.
	async function handleChange(e: Event) {
		const el = e.currentTarget as HTMLSelectElement;
		const next = el.value;
		onChange?.(next);
		await tick();
		if (selectEl && selectEl.value !== value) selectEl.value = value;
	}
</script>

<div class="st-select-wrap">
	<select
		bind:this={selectEl}
		class="st-select"
		{value}
		{disabled}
		onchange={handleChange}
	>
		{#each options as o (o.value)}
			<option value={o.value}>{o.label}</option>
		{/each}
	</select>
	<CaretDownIcon aria-hidden="true" />
</div>
