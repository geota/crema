<script lang="ts">
	/**
	 * `QuickStepper` — the dark `−` / value / `+` stepper, ported from the design's
	 * `QuickStepper` in `quick-controls.jsx`. Reuses the `.qcs*` class names so the
	 * design CSS applies verbatim.
	 *
	 * Presentation-only: it owns no state. The parent passes `value` and an
	 * `onChange` callback; the buttons clamp to `[min, max]` so `onChange` only
	 * ever fires with an in-range value.
	 */
	import type { Snippet } from 'svelte';
	import {
		canonicalToDisplay,
		displayDecimals,
		displayToCanonical,
		unitLabel,
		type Dimension
	} from '$lib/settings/format';
	import { getSettingsStore } from '$lib/settings/store.svelte';

	let {
		label,
		value,
		unit = '',
		min = 0,
		max = 999,
		step = 0.1,
		onChange,
		fmt,
		prefix,
		dimension,
		overridden = false,
		overriddenTooltip
	}: {
		/** Optional caption above the stepper row. */
		label?: string;
		/** Current value (canonical units when `dimension` is set). */
		value: number;
		/** Unit shown after the digits. Ignored when `dimension` is set. */
		unit?: string;
		/** Lower clamp bound (canonical). */
		min?: number;
		/** Upper clamp bound (canonical). */
		max?: number;
		/** Increment per `±` press (canonical). */
		step?: number;
		/** Called with the next clamped value (canonical) on a `±` press. */
		onChange?: (next: number) => void;
		/**
		 * Optional value formatter — receives the value already in display
		 * units when `dimension` is set. Defaults to step-aware decimal places
		 * (or `displayDecimals` for the dimension's unit).
		 */
		fmt?: (value: number) => string;
		/**
		 * Optional content rendered in the value box *before* the number — the
		 * mirror of `unit`. Used for a leading symbol such as a `>` / `<`
		 * comparator that reads like a prefix unit.
		 */
		prefix?: Snippet;
		/**
		 * Optional unit-aware dimension. When set, the stepper converts the
		 * canonical `value` to the user's chosen unit on display and back to
		 * canonical on commit. `unit` is overridden by the user's pref label.
		 */
		dimension?: Dimension;
		/**
		 * Whether the current value differs from the active profile / brew
		 * default seed — drives the italics + copper-tint "drift" cue on
		 * the value display. Owned by the parent stepper component, which
		 * reads `BrewParamState.isOverridden(key)`. Defaults to `false`
		 * (the indicator is opt-in per stepper; not every Quick Sheet
		 * card has a profile-side default to drift from).
		 */
		overridden?: boolean;
		/**
		 * Tooltip text shown on hover of an overridden value — typically
		 * `"Overriding default 93 °C"` with the seed value formatted in
		 * the user's pref. Ignored when `overridden` is `false`.
		 */
		overriddenTooltip?: string;
	} = $props();

	const settings = getSettingsStore();
	const effectiveUnit = $derived(dimension ? unitLabel(dimension, settings.current) : unit);
	const effectiveDecimals = $derived(
		dimension ? displayDecimals(dimension, settings.current) : step < 1 ? 1 : 0
	);

	/** Canonical → display number. */
	function toDisplay(canonical: number): number {
		return dimension ? canonicalToDisplay(dimension, canonical, settings.current) : canonical;
	}
	/** Display → canonical number. */
	function fromDisplay(display: number): number {
		return dimension ? displayToCanonical(dimension, display, settings.current) : display;
	}

	/** Format a value (already in display units) — the supplied `fmt`, or step-aware default. */
	const format = (v: number): string => (fmt ? fmt(v) : v.toFixed(effectiveDecimals));

	/**
	 * Step `value` by `dir × step`, clamped to `[min, max]`.
	 *
	 * Without `dimension`: pure canonical arithmetic — `value + dir × step`.
	 *
	 * With `dimension`: work in **display units** so every click changes
	 * the visible digit. A canonical step that's finer than the display
	 * decimal grid would otherwise produce hidden clicks — e.g. dose
	 * `step=0.1 g` ≈ 0.0035 oz, which rounds away at 2 decimals so the
	 * user clicks `+` twice before seeing anything happen. Convert the
	 * canonical step into display units, floor at one visible grid step
	 * (10^-decimals), then snap the next value to the grid so visited
	 * display numbers stay round.
	 */
	function inc(dir: number): void {
		if (dimension) {
			const displayNow = toDisplay(value);
			const grid = Math.pow(10, -effectiveDecimals);
			const displayStep = Math.max(grid, toDisplay(value + step) - displayNow);
			const nextDisplay = Math.round((displayNow + dir * displayStep) / grid) * grid;
			const next = Math.max(min, Math.min(max, fromDisplay(nextDisplay)));
			if (next !== value) onChange?.(next);
			return;
		}
		const next = Math.max(min, Math.min(max, Number((value + dir * step).toFixed(2))));
		if (next !== value) onChange?.(next);
	}

	// ── Click-to-type — mirrors StStepper's behaviour. The visible digits
	//    become an inline number input on click; Enter / blur commit, Esc
	//    cancels. The buttons keep working the same.
	let editing = $state(false);
	let draft = $state('');
	let inputEl = $state<HTMLInputElement | null>(null);
	function beginEdit(): void {
		if (!onChange) return;
		draft = String(Number(toDisplay(value).toFixed(effectiveDecimals)));
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
		const next = Math.max(min, Math.min(max, fromDisplay(n)));
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
		<div class="qcs-val" class:is-overridden={overridden}>
			{@render prefix?.()}
			{#if editing}
				<input
					bind:this={inputEl}
					class="qcs-num qcs-num-input"
					type="number"
					bind:value={draft}
					onblur={commit}
					onkeydown={onKey}
				/>
			{:else}
				<button
					type="button"
					class="qcs-num qcs-num-btn"
					onclick={beginEdit}
					title={overridden ? overriddenTooltip : undefined}
				>
					{format(toDisplay(value))}
				</button>
			{/if}
			<span class="qcs-unit">{effectiveUnit}</span>
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
	/* Drift indicator — the value differs from the active profile /
	   brew-default seed. Italics + copper tint on both the digits and
	   the unit suffix so the cue reads at a glance ("this isn't the
	   default"). The unit colour follows the digits via `currentColor`-
	   adjacent inheritance: the `.qcs-unit` element is dim by default;
	   inside an overridden cell it picks up the copper hue too. */
	.is-overridden :global(.qcs-num),
	.is-overridden :global(.qcs-unit) {
		font-style: italic;
		color: var(--copper-400);
	}
	/* Keep the hover affordance on the click-to-edit button while
	   overridden — the rule above turns the colour copper, so the
	   prior generic hover would step back to copper-400 too; explicit
	   here so the user sees a visible state change on hover. */
	.is-overridden :global(.qcs-num-btn:hover) {
		filter: brightness(1.12);
	}
</style>
