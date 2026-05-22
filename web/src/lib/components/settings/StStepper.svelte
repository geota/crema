<script lang="ts">
	/**
	 * `StStepper` — a `−` / value / `+` numeric stepper that also supports
	 * **click-to-type**: clicking the value flips it into an inline number
	 * input that commits on blur / Enter. The "best of both" pattern: tap
	 * the buttons to nudge, click the value to set a specific number.
	 *
	 * Shared across the Settings pages (Brew defaults, Water + maintenance,
	 * Sound volume). The Quick-sheet `QStepper` and the Profile-editor
	 * `PeNumber` have their own visual treatments (dark chrome and
	 * labelled-box respectively) and keep their own components, but
	 * share the click-to-type behaviour by mirroring this file's
	 * `editing` / `draft` / `commit` logic.
	 *
	 * The component owns no persistent state — it's a controlled input.
	 * The parent passes `value` and an `onCommit` callback fired with the
	 * clamped new value on every button press or accepted edit.
	 *
	 * Optionally pass `dimension` to make the stepper unit-aware: it then
	 * reads the user's unit preference from {@link getSettingsStore}, displays
	 * the canonical `value` converted to the chosen unit (and ignores any
	 * `unit` / `decimals` props), and converts the inline-edit draft back to
	 * canonical before invoking `onCommit`. `value` / `step` / `min` / `max`
	 * always remain canonical (grams / °C / mL / bar).
	 */
	import {
		canonicalToDisplay,
		displayDecimals,
		displayToCanonical,
		unitLabel,
		type Dimension
	} from '$lib/settings/format';
	import { getSettingsStore } from '$lib/settings/store.svelte';

	let {
		value,
		onCommit,
		step = 1,
		min,
		max,
		unit = '',
		decimals = 0,
		label,
		size = 'sm',
		dimension
	}: {
		/** Current numeric value (canonical units when `dimension` is set). */
		value: number;
		/** Called with the clamped next value (canonical) on a button press or edit commit. */
		onCommit: (next: number) => void;
		/** Increment per button press (canonical units when `dimension` is set). */
		step?: number;
		/** Optional lower clamp bound (canonical). */
		min?: number;
		/** Optional upper clamp bound (canonical). */
		max?: number;
		/**
		 * Unit suffix shown after the digits (e.g. " g", " °C", " s"). Ignored
		 * when `dimension` is set — the user's pref label is used instead.
		 */
		unit?: string;
		/** Decimal places to display. Ignored when `dimension` is set. */
		decimals?: number;
		/** Optional eyebrow label above the row. */
		label?: string;
		/**
		 * Visual size. `sm` (settings rows) is the default. `md` and `lg` are
		 * exposed so future surfaces can match QStepper / PeNumber sizing
		 * without forking the component.
		 */
		size?: 'sm' | 'md' | 'lg';
		/**
		 * Optional unit-aware dimension. When set, the stepper converts the
		 * canonical `value` to the user's chosen unit on display and back to
		 * canonical on commit. `unit` and `decimals` are overridden by the
		 * dimension + pref.
		 */
		dimension?: Dimension;
	} = $props();

	const settings = getSettingsStore();

	const effectiveUnit = $derived(dimension ? unitLabel(dimension, settings.current) : unit);
	const effectiveDecimals = $derived(
		dimension ? displayDecimals(dimension, settings.current) : decimals
	);

	/** Click-to-type editing state. */
	let editing = $state(false);
	let draft = $state('');
	let inputEl = $state<HTMLInputElement | null>(null);

	/** Convert a canonical value to its display-unit number. */
	function toDisplay(canonical: number): number {
		return dimension ? canonicalToDisplay(dimension, canonical, settings.current) : canonical;
	}
	/** Convert a display-unit number back to canonical. */
	function fromDisplay(display: number): number {
		return dimension ? displayToCanonical(dimension, display, settings.current) : display;
	}

	const display = $derived(toDisplay(value).toFixed(effectiveDecimals));

	function clamp(n: number): number {
		if (min !== undefined) n = Math.max(min, n);
		if (max !== undefined) n = Math.min(max, n);
		return n;
	}

	function inc(dir: number): void {
		const next = clamp(Number((value + dir * step).toFixed(4)));
		if (next !== value) onCommit(next);
	}

	function beginEdit(): void {
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
		const next = clamp(fromDisplay(n));
		if (next !== value) onCommit(next);
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

<div class={`st-stepper st-stepper-${size}`}>
	{#if label}
		<div class="t-eyebrow">{label}</div>
	{/if}
	<div class="st-stepper-row">
		<button
			type="button"
			class="st-stepper-btn"
			onclick={() => inc(-1)}
			aria-label="Decrease {label ?? 'value'}"
		>
			<i class="ph ph-minus" aria-hidden="true"></i>
		</button>
		<div class="st-stepper-val">
			{#if editing}
				<input
					bind:this={inputEl}
					class="st-stepper-input"
					type="number"
					bind:value={draft}
					onblur={commit}
					onkeydown={onKey}
				/>
			{:else}
				<button
					type="button"
					class="st-stepper-num"
					onclick={beginEdit}
					aria-label={`Edit ${label ?? 'value'}`}
				>{display}</button>
				<span class="st-stepper-unit">{effectiveUnit}</span>
			{/if}
		</div>
		<button
			type="button"
			class="st-stepper-btn"
			onclick={() => inc(1)}
			aria-label="Increase {label ?? 'value'}"
		>
			<i class="ph ph-plus" aria-hidden="true"></i>
		</button>
	</div>
</div>

<style>
	.st-stepper {
		display: inline-flex;
		flex-direction: column;
		gap: 4px;
	}
	.st-stepper-row {
		display: inline-flex;
		align-items: stretch;
		gap: 4px;
		background: rgba(var(--tint-rgb), 0.03);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
		border-radius: 6px;
		padding: 2px;
	}
	.st-stepper-btn {
		flex: 0 0 auto;
		border: 0;
		border-radius: 4px;
		background: transparent;
		color: rgba(var(--tint-rgb), 0.75);
		cursor: pointer;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		transition: background var(--dur-1, 140ms) var(--ease, ease);
	}
	.st-stepper-btn:hover {
		background: rgba(var(--tint-rgb), 0.08);
		color: var(--fg-1);
	}
	.st-stepper-btn:active {
		background: rgba(var(--tint-rgb), 0.12);
	}
	.st-stepper-val {
		flex: 1 1 auto;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: 3px;
		padding: 0 6px;
		min-width: 0;
		line-height: 1;
	}
	.st-stepper-num {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		color: var(--fg-1);
		background: transparent;
		border: 0;
		padding: 0;
		margin: 0;
		line-height: 1;
		cursor: text;
		display: inline-flex;
		align-items: center;
	}
	.st-stepper-num:hover {
		color: var(--copper-400);
	}
	.st-stepper-unit {
		font-family: var(--font-sans);
		color: rgba(var(--tint-rgb), 0.55);
		white-space: nowrap;
	}
	.st-stepper-input {
		flex: 1 1 auto;
		min-width: 0;
		width: 100%;
		background: rgba(var(--tint-rgb), 0.06);
		border: 1px solid rgba(var(--copper-rgb), 0.4);
		border-radius: 4px;
		padding: 1px 4px;
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		color: var(--fg-1);
		text-align: center;
		outline: none;
	}
	.st-stepper-input::-webkit-outer-spin-button,
	.st-stepper-input::-webkit-inner-spin-button {
		-webkit-appearance: none;
		margin: 0;
	}
	.st-stepper-input[type='number'] {
		-moz-appearance: textfield;
		appearance: textfield;
	}

	/* Size variants */
	.st-stepper-sm .st-stepper-btn {
		width: 24px;
		height: 24px;
		font-size: 11px;
	}
	.st-stepper-sm .st-stepper-num,
	.st-stepper-sm .st-stepper-input {
		font-size: 12px;
	}
	.st-stepper-sm .st-stepper-unit {
		font-size: 10px;
	}
	.st-stepper-sm .st-stepper-row {
		min-width: 110px;
	}

	.st-stepper-md .st-stepper-btn {
		width: 30px;
		height: 30px;
		font-size: 13px;
	}
	.st-stepper-md .st-stepper-num,
	.st-stepper-md .st-stepper-input {
		font-size: 16px;
	}
	.st-stepper-md .st-stepper-unit {
		font-size: 11px;
	}

	.st-stepper-lg .st-stepper-btn {
		width: 36px;
		height: 36px;
		font-size: 15px;
	}
	.st-stepper-lg .st-stepper-num,
	.st-stepper-lg .st-stepper-input {
		font-size: 22px;
	}
	.st-stepper-lg .st-stepper-unit {
		font-size: 12px;
	}
</style>
