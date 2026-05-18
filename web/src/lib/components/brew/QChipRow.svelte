<script lang="ts">
	/**
	 * `QChipRow` — the compact horizontal preset-chip row, ported from the
	 * design's `QChipRow` in `quick-controls.jsx`. Reuses the `.qchipr` /
	 * `.qchip` class names.
	 *
	 * A chip reads active when its value matches the current `value` within a
	 * float tolerance; tapping one calls `onChange` with that preset.
	 */
	let {
		options,
		value,
		unit = '',
		onChange,
		fmt
	}: {
		/** The preset values to render as chips. */
		options: number[];
		/** Current value — the matching chip reads active. */
		value: number;
		/** Unit appended inside each chip. */
		unit?: string;
		/** Called with the chosen preset. */
		onChange?: (next: number) => void;
		/** Optional label formatter — defaults to `String`. */
		fmt?: (value: number) => string;
	} = $props();

	const format = (v: number): string => (fmt ? fmt(v) : String(v));
</script>

<div class="qchipr">
	{#each options as option (option)}
		<button
			class="qchip"
			class:is-active={Math.abs(value - option) < 0.001}
			onclick={() => onChange?.(option)}
		>
			{format(option)}{#if unit}<span class="qchip-unit">{unit}</span>{/if}
		</button>
	{/each}
</div>
