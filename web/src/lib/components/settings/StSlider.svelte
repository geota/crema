<script lang="ts">
	/**
	 * `StSlider` — a settings-row range slider with a live value readout.
	 *
	 * The sibling of {@link StStepper} for values where sweeping a range
	 * beats nudging (the fan threshold's 0–60 °C). A controlled input like
	 * the stepper: the parent passes `value` and gets `onCommit` with the
	 * final value on release (pointer-up / keyboard commit), NOT on every
	 * drag tick — so a parent can hang a confirm dialog off commit without
	 * being spammed. The readout tracks the drag live; a cancelled commit
	 * simply re-renders from the unchanged `value` prop.
	 */
	let {
		value,
		onCommit,
		min = 0,
		max = 100,
		step = 1,
		unit = '',
		format,
		label
	}: {
		/** Current committed value. */
		value: number;
		/** Called once with the final value when the drag / keypress ends. */
		onCommit: (next: number) => void;
		min?: number;
		max?: number;
		step?: number;
		/** Unit suffix for the readout (e.g. " °C"). */
		unit?: string;
		/** Optional readout formatter — wins over `unit` (e.g. 0 → "Always on"). */
		format?: (v: number) => string;
		/** Accessible label for the input. */
		label?: string;
	} = $props();

	/** The in-drag value; `null` = idle, readout shows the committed prop. */
	let dragging = $state<number | null>(null);
	const shown = $derived(dragging ?? value);
	const readout = $derived(format ? format(shown) : `${shown}${unit}`);

	function onInput(e: Event): void {
		dragging = Number((e.currentTarget as HTMLInputElement).value);
	}
	function onChange(e: Event): void {
		const next = Number((e.currentTarget as HTMLInputElement).value);
		dragging = null;
		if (next !== value) onCommit(next);
	}
</script>

<div class="st-slider">
	<input
		type="range"
		{min}
		{max}
		{step}
		value={shown}
		aria-label={label}
		oninput={onInput}
		onchange={onChange}
	/>
	<span class="st-slider-readout">{readout}</span>
</div>

<style>
	.st-slider {
		display: flex;
		align-items: center;
		gap: 12px;
		min-width: 220px;
	}
	.st-slider input[type='range'] {
		flex: 1;
		appearance: none;
		height: 4px;
		border-radius: 2px;
		background: rgba(var(--tint-rgb), 0.18);
		outline: none;
		cursor: pointer;
	}
	.st-slider input[type='range']::-webkit-slider-thumb {
		appearance: none;
		width: 16px;
		height: 16px;
		border-radius: 50%;
		background: var(--copper-400);
		border: none;
	}
	.st-slider input[type='range']::-moz-range-thumb {
		width: 16px;
		height: 16px;
		border-radius: 50%;
		background: var(--copper-400);
		border: none;
	}
	.st-slider input[type='range']:focus-visible {
		outline: 2px solid rgba(var(--copper-rgb), 0.6);
		outline-offset: 4px;
	}
	.st-slider-readout {
		font-family: 'JetBrains Mono', monospace;
		font-size: 13px;
		min-width: 64px;
		text-align: right;
		color: var(--fg-1);
		font-variant-numeric: tabular-nums;
	}
</style>
