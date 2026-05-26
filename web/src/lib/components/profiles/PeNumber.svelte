<script lang="ts">
	/**
	 * `PeNumber` — a labelled −/+ number stepper, ported from `PeNumber` in
	 * `profile-edit-page.jsx`. Used in the editor's Targets grid.
	 *
	 * Optionally pass `dimension` to make the stepper unit-aware (see
	 * {@link QStepper} for the full pattern): `value` / `step` / `min` / `max`
	 * stay canonical, the display value and inline-edit draft swap to the
	 * user's chosen unit, and `onChange` still fires with canonical.
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
		label,
		value,
		step = 1,
		unit = '',
		min,
		max,
		digits = 1,
		onChange,
		dimension,
		dot = false,
		dotOn = true,
		onDot
	}: {
		/** The field label (eyebrow). */
		label: string;
		/** The current value (canonical units when `dimension` is set). */
		value: number;
		/** Increment per button press (canonical). */
		step?: number;
		/** Unit suffix shown after the number. Ignored when `dimension` is set. */
		unit?: string;
		/** Minimum allowed value (canonical). */
		min: number;
		/** Maximum allowed value (canonical). */
		max: number;
		/** Decimal places to display. Overridden by `displayDecimals` when `dimension` is set. */
		digits?: number;
		/** Called with the clamped new value (canonical). */
		onChange: (value: number) => void;
		/**
		 * Optional unit-aware dimension. When set, the stepper displays the
		 * canonical `value` in the user's chosen unit and converts the
		 * inline-edit draft back to canonical before `onChange`.
		 */
		dimension?: Dimension;
		/**
		 * Show a small left-aligned dot toggle on the label row — same
		 * affordance as a `SegmentRow` volume cell. The dot's on/off state
		 * is purely visual; the parent owns the toggle action via
		 * {@link onDot}. The stepper's input dims (`is-off`) while
		 * `dotOn` is `false` so the disabled state reads at a glance.
		 */
		dot?: boolean;
		/** Whether the dot indicator is currently lit. Only used when `dot` is `true`. */
		dotOn?: boolean;
		/** Called when the user clicks the dot. The parent flips state. */
		onDot?: () => void;
	} = $props();

	const settings = getSettingsStore();
	const effectiveUnit = $derived(dimension ? unitLabel(dimension, settings.current) : unit);
	const effectiveDigits = $derived(
		dimension ? displayDecimals(dimension, settings.current) : digits
	);

	function toDisplay(canonical: number): number {
		return dimension ? canonicalToDisplay(dimension, canonical, settings.current) : canonical;
	}
	function fromDisplay(display: number): number {
		return dimension ? displayToCanonical(dimension, display, settings.current) : display;
	}

	/**
	 * Step by `dir`. With `dimension`, work in display units so each click
	 * moves the visible digit — see {@link QStepper.inc} for the full
	 * explanation. Without `dimension`, plain canonical arithmetic.
	 */
	function inc(dir: number): void {
		if (dimension) {
			const displayNow = toDisplay(value);
			const grid = Math.pow(10, -effectiveDigits);
			const displayStep = Math.max(grid, toDisplay(value + step) - displayNow);
			const nextDisplay = Math.round((displayNow + dir * displayStep) / grid) * grid;
			const next = Math.min(max, Math.max(min, fromDisplay(nextDisplay)));
			if (next !== value) onChange(next);
			return;
		}
		const next = Math.min(max, Math.max(min, Number((value + dir * step).toFixed(2))));
		if (next !== value) onChange(next);
	}

	// Click-to-type — mirrors StStepper's behaviour. Tap the digits to set
	// a specific value instead of clicking the buttons N times.
	let editing = $state(false);
	let draft = $state('');
	let inputEl = $state<HTMLInputElement | null>(null);
	function beginEdit(): void {
		draft = String(Number(toDisplay(value).toFixed(effectiveDigits)));
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
		const next = Math.min(max, Math.max(min, fromDisplay(n)));
		if (next !== value) onChange(next);
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

<div class="pe-num">
	<div class="pe-num-label">
		{#if dot}
			<button
				type="button"
				class="pe-num-dot"
				class:on={dotOn}
				onclick={onDot}
				aria-pressed={dotOn}
				aria-label={dotOn ? `${label}: on (click to disable)` : `${label}: off (click to enable)`}
			></button>
		{/if}
		<div class="t-eyebrow">{label}</div>
	</div>
	<div class="pe-num-row" class:is-off={dot && !dotOn}>
		<button class="pe-num-btn" aria-label="Decrease {label}" onclick={() => inc(-1)}>
			<i class="ph ph-minus" aria-hidden="true"></i>
		</button>
		<div class="pe-num-val">
			{#if editing}
				<input
					bind:this={inputEl}
					class="pe-num-num pe-num-input"
					type="number"
					bind:value={draft}
					onblur={commit}
					onkeydown={onKey}
				/>
			{:else}
				<button
					type="button"
					class="pe-num-num pe-num-numbtn"
					aria-label={`Edit ${label}`}
					onclick={beginEdit}>{toDisplay(value).toFixed(effectiveDigits)}</button
				>
			{/if}
			<span class="pe-num-unit">{effectiveUnit}</span>
		</div>
		<button class="pe-num-btn" aria-label="Increase {label}" onclick={() => inc(1)}>
			<i class="ph ph-plus" aria-hidden="true"></i>
		</button>
	</div>
</div>

<style>
	.pe-num {
		display: flex;
		flex-direction: column;
		gap: 6px;
		/* Fixed height so every editor number box is identical regardless of
		   which section's grid it sits in — the Ratio read-out matches it. */
		height: 76px;
		box-sizing: border-box;
		padding: 12px;
		background: rgba(var(--tint-rgb), 0.03);
		border: 1px solid rgba(var(--tint-rgb), 0.06);
		border-radius: var(--radius-sm);
	}
	.pe-num :global(.t-eyebrow) {
		color: rgba(var(--tint-rgb), 0.5);
		/* Slightly smaller + tighter, and never wrapping, so a long label
		   like "Max total volume" stays on one line in the 2-up editor grid. */
		font-size: 10px;
		letter-spacing: 0.04em;
		white-space: nowrap;
	}
	.pe-num-row {
		display: flex;
		align-items: stretch;
		gap: 4px;
	}
	.pe-num-btn {
		width: 28px;
		flex: 0 0 28px;
		border: 0;
		border-radius: 5px;
		background: rgba(var(--tint-rgb), 0.06);
		color: var(--fg-1);
		cursor: pointer;
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 12px;
	}
	.pe-num-btn:hover {
		background: rgba(var(--tint-rgb), 0.1);
	}
	.pe-num-val {
		flex: 1 1 auto;
		display: flex;
		align-items: baseline;
		justify-content: center;
		gap: 3px;
		padding: 4px 6px;
	}
	.pe-num-num {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 20px;
		color: var(--fg-1);
	}
	.pe-num-numbtn {
		background: transparent;
		border: 0;
		padding: 0;
		cursor: text;
	}
	.pe-num-numbtn:hover {
		color: var(--copper-400);
	}
	.pe-num-input {
		min-width: 0;
		width: 100%;
		background: rgba(var(--tint-rgb), 0.08);
		border: 1px solid rgba(var(--copper-rgb), 0.5);
		border-radius: 4px;
		padding: 1px 4px;
		text-align: center;
		outline: none;
	}
	.pe-num-input::-webkit-outer-spin-button,
	.pe-num-input::-webkit-inner-spin-button {
		-webkit-appearance: none;
		margin: 0;
	}
	.pe-num-input[type='number'] {
		-moz-appearance: textfield;
		appearance: textfield;
	}
	.pe-num-unit {
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.5);
	}
	.pe-num-label {
		display: flex;
		align-items: center;
		gap: 6px;
		min-height: 12px;
	}
	.pe-num-dot {
		width: 8px;
		height: 8px;
		flex: 0 0 8px;
		border: 1px solid rgba(var(--tint-rgb), 0.35);
		border-radius: 50%;
		background: transparent;
		padding: 0;
		cursor: pointer;
		transition:
			background 0.12s,
			border-color 0.12s;
	}
	.pe-num-dot.on {
		background: var(--copper-400);
		border-color: var(--copper-400);
	}
	.pe-num-dot:hover {
		border-color: rgba(var(--copper-rgb), 0.6);
	}
	.pe-num-row.is-off {
		opacity: 0.4;
	}
	.pe-num-row.is-off :global(.pe-num-num),
	.pe-num-row.is-off :global(.pe-num-unit) {
		color: rgba(var(--tint-rgb), 0.4);
	}
</style>
