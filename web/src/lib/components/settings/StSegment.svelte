<script lang="ts">
	/**
	 * `StSegment` — a pill-shaped segmented control. Reuses the shared
	 * `.st-segment` styling. Ported from the design's `StSegment`.
	 *
	 * The selected option is `aria-pressed` and the group is named after the
	 * enclosing `StRow` title (or `label`), so "Theme: System, pressed" is
	 * announced, not just shown in copper (final cohort review 07 N3).
	 */
	import { getStRowContext, hasStRowContext } from './st-row-context.js';

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
		equalWidth = false,
		label
	}: {
		value: string;
		options: Option[];
		onChange?: (next: string) => void;
		disabled?: boolean;
		/** Pin to a shared width so a row of same-kind toggles (e.g. the unit
		 *  selectors) line up; leave off for variable-width option sets. */
		equalWidth?: boolean;
		/** Group name; defaults to the enclosing StRow's title. */
		label?: string;
	} = $props();

	const rowTitleId = hasStRowContext() ? getStRowContext().titleId : undefined;
</script>

<div
	class="st-segment"
	class:is-equal={equalWidth}
	role="group"
	aria-label={label}
	aria-labelledby={label ? undefined : rowTitleId}
>
	{#each options as o (o.value)}
		<button
			type="button"
			class:is-active={value === o.value}
			aria-pressed={value === o.value}
			disabled={disabled || o.disabled}
			onclick={() => onChange?.(o.value)}>{o.label}</button
		>
	{/each}
</div>
