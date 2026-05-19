<script lang="ts">
	/**
	 * `QStepper` — the dark `−` / value / `+` stepper, ported from the design's
	 * `QStepper` in `quick-controls.jsx`. Reuses the `.qcs*` class names so the
	 * design CSS applies verbatim.
	 *
	 * Presentation-only: it owns no state. The parent passes `value` and an
	 * `onChange` callback; the buttons clamp to `[min, max]` so `onChange` only
	 * ever fires with an in-range value.
	 */
	import type { Snippet } from 'svelte';

	let {
		label,
		value,
		unit = '',
		min = 0,
		max = 999,
		step = 0.1,
		onChange,
		fmt,
		prefix
	}: {
		/** Optional caption above the stepper row. */
		label?: string;
		/** Current value. */
		value: number;
		/** Unit shown after the digits. */
		unit?: string;
		/** Lower clamp bound. */
		min?: number;
		/** Upper clamp bound. */
		max?: number;
		/** Increment per `±` press. */
		step?: number;
		/** Called with the next clamped value on a `±` press. */
		onChange?: (next: number) => void;
		/** Optional value formatter — defaults to step-aware decimal places. */
		fmt?: (value: number) => string;
		/**
		 * Optional content rendered in the value box *before* the number — the
		 * mirror of `unit`. Used for a leading symbol such as a `>` / `<`
		 * comparator that reads like a prefix unit.
		 */
		prefix?: Snippet;
	} = $props();

	/** Format a value — the supplied `fmt`, or step-aware default. */
	const format = (v: number): string => (fmt ? fmt(v) : v.toFixed(step < 1 ? 1 : 0));

	/** Step `value` by `dir × step`, clamped to `[min, max]`. */
	function inc(dir: number): void {
		const next = Math.max(min, Math.min(max, Number((value + dir * step).toFixed(2))));
		onChange?.(next);
	}
</script>

<div class="qcs">
	{#if label}
		<div class="qcs-label">{label}</div>
	{/if}
	<div class="qcs-row">
		<button class="qcs-btn" onclick={() => inc(-1)} aria-label="Decrease {label ?? 'value'}">
			<i class="ph ph-minus" aria-hidden="true"></i>
		</button>
		<div class="qcs-val">
			{@render prefix?.()}
			<span class="qcs-num">{format(value)}</span>
			<span class="qcs-unit">{unit}</span>
		</div>
		<button class="qcs-btn" onclick={() => inc(1)} aria-label="Increase {label ?? 'value'}">
			<i class="ph ph-plus" aria-hidden="true"></i>
		</button>
	</div>
</div>
