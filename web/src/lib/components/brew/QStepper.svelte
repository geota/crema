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

	// ── Click-to-type — mirrors StStepper's behaviour. The visible digits
	//    become an inline number input on click; Enter / blur commit, Esc
	//    cancels. The buttons keep working the same.
	let editing = $state(false);
	let draft = $state('');
	let inputEl = $state<HTMLInputElement | null>(null);
	function beginEdit(): void {
		if (!onChange) return;
		draft = String(value);
		editing = true;
		queueMicrotask(() => {
			inputEl?.focus();
			inputEl?.select();
		});
	}
	function commit(): void {
		if (!editing) return;
		editing = false;
		const n = Number(draft);
		if (!Number.isFinite(n)) return;
		const next = Math.max(min, Math.min(max, n));
		if (next !== value) onChange?.(next);
	}
	function onKey(e: KeyboardEvent): void {
		if (e.key === 'Enter') {
			e.preventDefault();
			commit();
		} else if (e.key === 'Escape') {
			e.preventDefault();
			editing = false;
		}
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
			{#if editing}
				<input
					bind:this={inputEl}
					class="qcs-num qcs-num-input"
					type="number"
					{step}
					{min}
					{max}
					bind:value={draft}
					onblur={commit}
					onkeydown={onKey}
				/>
			{:else}
				<button type="button" class="qcs-num qcs-num-btn" onclick={beginEdit}>
					{format(value)}
				</button>
			{/if}
			<span class="qcs-unit">{unit}</span>
		</div>
		<button class="qcs-btn" onclick={() => inc(1)} aria-label="Increase {label ?? 'value'}">
			<i class="ph ph-plus" aria-hidden="true"></i>
		</button>
	</div>
</div>

<style>
	/* Click-to-type wiring. The button + input inherit the .qcs-num
	   typography from the global stylesheet; we only strip button chrome
	   so the digits read identically to the prior `<span>`. The input
	   gets a small focus rail for clarity while editing. */
	:global(.qcs-num-btn) {
		background: transparent;
		border: 0;
		padding: 0;
		cursor: text;
		color: inherit;
		font: inherit;
	}
	:global(.qcs-num-btn:hover) {
		color: var(--copper-400);
	}
	:global(.qcs-num-input) {
		min-width: 0;
		width: 100%;
		background: rgba(255, 255, 255, 0.06);
		border: 1px solid rgba(var(--copper-rgb), 0.5);
		border-radius: 4px;
		padding: 1px 4px;
		color: inherit;
		text-align: center;
		outline: none;
		font: inherit;
	}
	:global(.qcs-num-input::-webkit-outer-spin-button),
	:global(.qcs-num-input::-webkit-inner-spin-button) {
		-webkit-appearance: none;
		margin: 0;
	}
	:global(.qcs-num-input[type='number']) {
		-moz-appearance: textfield;
		appearance: textfield;
	}
</style>
