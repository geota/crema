<script lang="ts">
	/**
	 * `StSelect` — a styled `<select>` with a caret affordance. Ported from the
	 * design's `StSelect` (`settings-page.jsx`).
	 */
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
</script>

<div class="st-select-wrap">
	<select
		class="st-select"
		{value}
		{disabled}
		onchange={(e) => onChange?.((e.currentTarget as HTMLSelectElement).value)}
	>
		{#each options as o (o.value)}
			<option value={o.value}>{o.label}</option>
		{/each}
	</select>
	<i class="ph ph-caret-down" aria-hidden="true"></i>
</div>
