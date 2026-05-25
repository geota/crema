<script lang="ts">
	/**
	 * `CalibrationRow` — one calibration row with a consistent three-column
	 * layout: Current / Factory / New value, followed by Apply + Reset-to-
	 * factory buttons. Shared by the Temperature, Pressure, and Flow rows on
	 * the Settings → Calibration screen so they all read at a glance with the
	 * same scan-shape.
	 *
	 * The component is presentational — it owns no calibration state. All
	 * commits are dispatched through the `onApply` / `onResetToFactory`
	 * callbacks; the parent (`CalibrationSection`) owns the type-to-confirm
	 * modal and the wire-level setters.
	 *
	 * The "New value" column is intentionally split into two shapes:
	 *
	 * - `{ type: 'stepper', ... }` — an editable number input bounded by
	 *   `min` / `max` / `step` (used by Flow). The parent reads `value` from
	 *   the input via the `onNewValueChange` callback.
	 *
	 * - `{ type: 'disabled' }` — a greyed-out placeholder used by Temperature
	 *   and Pressure. Those targets' write path is a *paired* reported/
	 *   measured calibration — there is no single "new value" to commit
	 *   from the row. The Apply button still opens the (paired-input)
	 *   modal, so the row remains functional; the stepper just isn't the
	 *   right surface for that workflow yet. See the TODO in
	 *   `CalibrationSection` for the long-term plan to inline the
	 *   reported/measured pair into this row.
	 */
	export type NewValueInput =
		| { type: 'stepper'; min: number; max: number; step: number; value: number }
		| { type: 'disabled' };

	let {
		title,
		unit = '',
		currentValue,
		factoryValue,
		newValueInput,
		onApply,
		onResetToFactory,
		onNewValueChange,
		applyDisabled = false,
		applyDisabledReason,
		resetDisabled = false,
		resetDisabledReason,
		description,
		needsConnection = false,
		decimals = 3
	}: {
		/** Section title — one of `Flow` / `Pressure` / `Temperature`. */
		title: string;
		/** Unit suffix shown after the read-out values (e.g. `bar`, `°C`, `''`). */
		unit?: string;
		/** Live readback of the calibration value (canonical units). `null` → `—`. */
		currentValue: number | null;
		/** Factory baseline (canonical units). `null` → `—`. */
		factoryValue: number | null;
		/** Shape of the "New value" cell — see the component-level note. */
		newValueInput: NewValueInput;
		/** Invoked when the user presses Apply. The parent opens the confirm modal. */
		onApply: (newValue: number) => void;
		/** Invoked when the user presses Reset-to-factory. The parent opens the modal. */
		onResetToFactory: () => void;
		/** Called as the user types in the stepper input. Unused for `disabled`. */
		onNewValueChange?: (next: number) => void;
		/** When true, the Apply button is dimmed and disabled. */
		applyDisabled?: boolean;
		/** Hover tooltip explaining why Apply is disabled. */
		applyDisabledReason?: string;
		/** When true, the Reset button is dimmed and disabled. */
		resetDisabled?: boolean;
		/** Hover tooltip explaining why Reset is disabled. */
		resetDisabledReason?: string;
		/** Small grey copy under the row title. */
		description?: string;
		/** When true, show a "Connect DE1" hint pill on the row title. */
		needsConnection?: boolean;
		/** Decimals to render the Current / Factory values with. */
		decimals?: number;
	} = $props();

	function fmt(v: number | null | undefined): string {
		if (v == null || !Number.isFinite(v)) return '—';
		return v.toFixed(decimals);
	}

	function onStepperInput(e: Event): void {
		if (newValueInput.type !== 'stepper') return;
		const target = e.target as HTMLInputElement;
		const n = Number(target.value);
		if (Number.isFinite(n)) onNewValueChange?.(n);
	}

	function handleApply(): void {
		if (applyDisabled) return;
		if (newValueInput.type === 'stepper') onApply(newValueInput.value);
		else onApply(NaN);
	}
</script>

<div class="cal-row" class:cal-row-disconnected={needsConnection}>
	<div class="cal-row-head">
		<div class="cal-row-title">
			<span>{title}</span>
			{#if needsConnection}
				<span class="cal-row-pill cal-row-pill-conn" title="This setting needs a connected DE1.">
					Connect DE1
				</span>
			{/if}
		</div>
		{#if description}
			<div class="cal-row-sub">{description}</div>
		{/if}
	</div>

	<div class="cal-row-grid">
		<div class="cal-col">
			<div class="t-eyebrow">Current</div>
			<div class="cal-val">
				{fmt(currentValue)}{#if unit}<span class="cal-val-unit"> {unit}</span>{/if}
			</div>
		</div>
		<div class="cal-col">
			<div class="t-eyebrow">Factory</div>
			<div class="cal-val">
				{fmt(factoryValue)}{#if unit}<span class="cal-val-unit"> {unit}</span>{/if}
			</div>
		</div>
		<div class="cal-col">
			<div class="t-eyebrow">New value</div>
			{#if newValueInput.type === 'stepper'}
				<div class="cal-new">
					<input
						type="number"
						min={newValueInput.min}
						max={newValueInput.max}
						step={newValueInput.step}
						value={Number(newValueInput.value.toFixed(decimals))}
						oninput={onStepperInput}
						disabled={needsConnection}
						aria-label={`${title} new value`}
					/>
					{#if unit}<span class="cal-val-unit">{unit}</span>{/if}
				</div>
			{:else}
				<div class="cal-new cal-new-disabled" title="Set via the Apply dialog">
					<input
						type="text"
						value="—"
						disabled
						readonly
						aria-label={`${title} new value (set via Apply dialog)`}
					/>
					{#if unit}<span class="cal-val-unit">{unit}</span>{/if}
				</div>
			{/if}
		</div>
	</div>

	<div class="cal-row-actions">
		<button
			type="button"
			class="st-btn st-btn-secondary"
			onclick={handleApply}
			disabled={applyDisabled}
			title={applyDisabled ? applyDisabledReason : undefined}
		>
			Apply
		</button>
		<button
			type="button"
			class="st-btn st-btn-danger"
			onclick={onResetToFactory}
			disabled={resetDisabled}
			title={resetDisabled ? resetDisabledReason : undefined}
		>
			Reset to factory
		</button>
	</div>
</div>

<style>
	.cal-row {
		display: grid;
		grid-template-columns: 1fr;
		gap: 12px;
		padding: 14px 16px;
		border: 1px solid rgba(var(--tint-rgb), 0.08);
		border-radius: 10px;
		background: rgba(var(--tint-rgb), 0.02);
	}
	.cal-row + :global(.cal-row) {
		margin-top: 8px;
	}
	.cal-row-disconnected .cal-row-grid {
		opacity: 0.7;
	}
	.cal-row-head {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.cal-row-title {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		font-size: 14px;
		font-weight: 600;
		color: var(--fg-1);
	}
	.cal-row-sub {
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.65);
	}
	.cal-row-pill {
		font-family: var(--font-sans);
		font-size: 9px;
		font-weight: 600;
		letter-spacing: var(--track-allcaps, 0.06em);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.6);
		background: rgba(var(--tint-rgb), 0.08);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: 999px;
		padding: 2px 8px;
		white-space: nowrap;
	}
	.cal-row-pill-conn {
		color: var(--copper-400);
		background: rgba(var(--copper-rgb), 0.08);
		border-color: rgba(var(--copper-rgb), 0.22);
	}
	.cal-row-grid {
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
		gap: 16px;
		align-items: end;
	}
	.cal-col {
		display: flex;
		flex-direction: column;
		gap: 4px;
		min-width: 0;
	}
	.cal-val {
		font-family: var(--font-mono);
		font-size: 14px;
		font-variant-numeric: tabular-nums;
		color: var(--fg-1);
		line-height: 1.2;
	}
	.cal-val-unit {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.55);
		margin-left: 2px;
	}
	.cal-new {
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.cal-new input {
		width: 6.5em;
		border: 1px solid rgba(var(--tint-rgb), 0.18);
		border-radius: 6px;
		background: rgba(var(--tint-rgb), 0.04);
		padding: 4px 8px;
		font-family: var(--font-mono);
		font-size: 13px;
		font-variant-numeric: tabular-nums;
		color: var(--fg-1);
		outline: none;
	}
	.cal-new input:focus-visible {
		border-color: rgba(var(--copper-rgb), 0.5);
		background: rgba(var(--tint-rgb), 0.06);
	}
	.cal-new input:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}
	.cal-new-disabled input {
		text-align: center;
		color: rgba(var(--tint-rgb), 0.5);
	}
	.cal-row-actions {
		display: flex;
		gap: 8px;
		justify-content: flex-end;
		align-items: center;
	}

	@media (max-width: 560px) {
		.cal-row-grid {
			grid-template-columns: 1fr 1fr;
		}
		.cal-row-actions {
			justify-content: flex-start;
			flex-wrap: wrap;
		}
	}
</style>
