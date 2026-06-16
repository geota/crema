<script lang="ts">
	import MinusIcon from 'phosphor-svelte/lib/MinusIcon';
	import PlusIcon from 'phosphor-svelte/lib/PlusIcon';
	/**
	 * `StStepper` — a `−` / value / `+` numeric stepper that also supports
	 * **click-to-type**: clicking the value flips it into an inline number
	 * input that commits on blur / Enter. The "best of both" pattern: tap
	 * the buttons to nudge, click the value to set a specific number.
	 *
	 * Shared across the Settings pages (Brew defaults, Water + maintenance,
	 * Sound volume). The Quick-sheet `QuickStepper` shares this stepper's numeric
	 * + click-to-type core via the {@link useStepper} helper, under its own dark
	 * chrome; the Profile-editor `PeNumber` keeps a separate labelled-box
	 * treatment with its own logic.
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
	 * always remain canonical (grams / °C / ml / bar).
	 */
	import { type Dimension } from '$lib/settings/format';
	import { useStepper } from '../useStepper.svelte';

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
		dimension,
		dot = false,
		dotOn = true,
		onDot
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
		 * exposed so future surfaces can match QuickStepper / PeNumber sizing
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
		/**
		 * Show a small left-aligned dot toggle to indicate / control an
		 * optional / disabled state — matches the
		 * `PeNumber` + `SegmentRow` convention. The dot is purely visual
		 * here; the parent owns the toggle action via {@link onDot}. The
		 * stepper input dims (`is-off`) while `dotOn` is `false`.
		 */
		dot?: boolean;
		/** Whether the dot indicator is currently lit. Only used when `dot` is `true`. */
		dotOn?: boolean;
		/** Called when the user clicks the dot. The parent flips state. */
		onDot?: () => void;
	} = $props();

	// Numeric core (clamp + canonical↔display + grid-snapped inc + click-to-type)
	// is shared with QuickStepper via `useStepper`. StStepper keeps `incPrecision:
	// 4` and its explicit `decimals` prop; min/max stay optional. Presentation
	// (size variants, the optional dot toggle) stays here.
	const stepper = useStepper({
		value: () => value,
		commit: (n) => onCommit(n),
		step: () => step,
		min: () => min,
		max: () => max,
		dimension: () => dimension,
		unit: () => unit,
		decimals: () => decimals,
		incPrecision: 4
	});
</script>

<div class={`st-stepper st-stepper-${size}`}>
	{#if label || dot}
		<div class="st-stepper-label">
			{#if dot}
				<button
					type="button"
					class="st-stepper-dot"
					class:on={dotOn}
					onclick={onDot}
					aria-pressed={dotOn}
					aria-label={dotOn ? `${label ?? 'value'}: on (click to disable)` : `${label ?? 'value'}: off (click to enable)`}
				></button>
			{/if}
			{#if label}
				<div class="t-eyebrow">{label}</div>
			{/if}
		</div>
	{/if}
	<div class="st-stepper-row" class:is-off={dot && !dotOn}>
		<button
			type="button"
			class="st-stepper-btn"
			onclick={() => stepper.inc(-1)}
			aria-label="Decrease {label ?? 'value'}"
		>
			<MinusIcon aria-hidden="true" />
		</button>
		<div class="st-stepper-val">
			{#if stepper.editing}
				<input
					bind:this={stepper.inputEl}
					class="st-stepper-input"
					type="number"
					bind:value={stepper.draft}
					onblur={stepper.commit}
					onkeydown={stepper.onKey}
				/>
			{:else}
				<button
					type="button"
					class="st-stepper-num"
					onclick={stepper.beginEdit}
					aria-label={`Edit ${label ?? 'value'}`}
				>{stepper.display}</button>
				<span class="st-stepper-unit">{stepper.unit}</span>
			{/if}
		</div>
		<button
			type="button"
			class="st-stepper-btn"
			onclick={() => stepper.inc(1)}
			aria-label="Increase {label ?? 'value'}"
		>
			<PlusIcon aria-hidden="true" />
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
		/* `width: 5em` keeps the input the same width as the display-
		   mode `<button>NN.N</button><span>unit</span>` it replaces,
		   independent of the browser's huge default for `input[type=
		   "number"]` (~150 px on Chromium) which would otherwise push
		   the row out via the surrounding `inline-flex` layout. */
		flex: 0 0 auto;
		width: 5em;
		min-width: 0;
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

	/* Optional dot affordance — same convention as PeNumber / SegmentRow.
	   When the dot is off, the input row dims so the disabled state reads
	   at a glance. The parent retains ownership of the toggle behaviour
	   (typically: clicking the dot flips the underlying value between a
	   sensible default and the 0-sentinel that means "disabled"). */
	.st-stepper-label {
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.st-stepper-dot {
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
	.st-stepper-dot.on {
		background: var(--copper-400);
		border-color: var(--copper-400);
	}
	.st-stepper-dot:hover {
		border-color: rgba(var(--copper-rgb), 0.6);
	}
	.st-stepper-row.is-off {
		opacity: 0.4;
	}
	.st-stepper-row.is-off .st-stepper-num,
	.st-stepper-row.is-off .st-stepper-unit {
		color: rgba(var(--tint-rgb), 0.4);
	}
</style>
