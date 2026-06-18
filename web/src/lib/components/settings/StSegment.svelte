<script lang="ts">
	/**
	 * `StSegment` — a pill-shaped segmented control. Reuses the shared
	 * `.st-segment` styling. Ported from the design's `StSegment`.
	 */
	interface Option {
		value: string;
		label: string;
		/** Per-option disable — greys out and blocks clicks for this button only. */
		disabled?: boolean;
	}

	let {
		value,
		options,
		onChange,
		disabled = false,
		equalWidth = false
	}: {
		value: string;
		options: Option[];
		onChange?: (next: string) => void;
		disabled?: boolean;
		/** Pin to a shared width so a row of same-kind toggles (e.g. the unit
		 *  selectors) line up; leave off for variable-width option sets. */
		equalWidth?: boolean;
	} = $props();
</script>

<div class="st-segment" class:is-equal={equalWidth}>
	{#each options as o (o.value)}
		<button
			type="button"
			class:is-active={value === o.value}
			disabled={disabled || o.disabled}
			onclick={() => onChange?.(o.value)}>{o.label}</button
		>
	{/each}
</div>
