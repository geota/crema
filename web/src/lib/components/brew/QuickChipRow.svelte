<script lang="ts">
	/**
	 * `QuickChipRow` — the compact horizontal preset-chip row, ported from the
	 * design's `QuickChipRow` in `quick-controls.jsx`. Reuses the `.qchipr` /
	 * `.qchip` class names.
	 *
	 * A chip reads active when its value matches the current `value` within a
	 * float tolerance; tapping one calls `onChange` with that preset.
	 *
	 * Optionally pass `dimension` to make the row unit-aware: the chips'
	 * `options` stay in canonical units (and `onChange` still receives
	 * canonical), but the chip labels render in the user's chosen unit.
	 */
	import {
		canonicalToDisplay,
		displayDecimals,
		unitLabel,
		type Dimension
	} from '$lib/settings/format';
	import { getSettingsStore } from '$lib/settings/store.svelte';

	let {
		options,
		value,
		unit = '',
		onChange,
		fmt,
		dimension
	}: {
		/** The preset values to render as chips (canonical when `dimension` set). */
		options: number[];
		/** Current value — the matching chip reads active. */
		value: number;
		/** Unit appended inside each chip. Ignored when `dimension` is set. */
		unit?: string;
		/** Called with the chosen preset (canonical). */
		onChange?: (next: number) => void;
		/** Optional label formatter — defaults to `String`. */
		fmt?: (value: number) => string;
		/** Optional unit-aware dimension. */
		dimension?: Dimension;
	} = $props();

	const settings = getSettingsStore();
	const effectiveUnit = $derived(dimension ? unitLabel(dimension, settings.current) : unit);
	const effectiveDecimals = $derived(
		dimension ? displayDecimals(dimension, settings.current) : null
	);

	function toDisplay(canonical: number): number {
		return dimension ? canonicalToDisplay(dimension, canonical, settings.current) : canonical;
	}

	const format = (v: number): string => {
		if (fmt) return fmt(v);
		if (effectiveDecimals !== null) return v.toFixed(effectiveDecimals);
		return String(v);
	};
</script>

<div class="qchipr">
	{#each options as option (option)}
		<button
			class="qchip"
			class:is-active={Math.abs(value - option) < 0.001}
			onclick={() => onChange?.(option)}
		>
			{format(toDisplay(option))}{#if effectiveUnit}<span class="qchip-unit">{effectiveUnit}</span>{/if}
		</button>
	{/each}
</div>
