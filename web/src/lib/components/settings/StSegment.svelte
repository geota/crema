<script lang="ts">
	/**
	 * `StSegment` — a pill-shaped segmented control. Reuses the shared
	 * `.st-segment` styling. Ported from the design's `StSegment`.
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

<div class="st-segment">
	{#each options as o (o.value)}
		<button
			type="button"
			class:is-active={value === o.value}
			{disabled}
			onclick={() => onChange?.(o.value)}>{o.label}</button
		>
	{/each}
</div>
