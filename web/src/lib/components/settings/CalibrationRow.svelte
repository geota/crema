<script lang="ts">
	/**
	 * `CalibrationRow` — one calibration row, sleek and single-line.
	 *
	 * Layout: `[Title]  Current: x.xxx · Factory: x.xxx · New: [input]   [Apply] [Reset]`
	 * — everything on one horizontal row. The optional `description` slides
	 * in as a small grey caption under the title only when present; the
	 * row's height stays small either way.
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
		decimals = 3
	}: {
		title: string;
		unit?: string;
		currentValue: number | null;
		factoryValue: number | null;
		newValueInput: NewValueInput;
		onApply: (newValue: number) => void;
		onResetToFactory: () => void;
		onNewValueChange?: (next: number) => void;
		applyDisabled?: boolean;
		applyDisabledReason?: string;
		resetDisabled?: boolean;
		resetDisabledReason?: string;
		description?: string;
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

<div class="cal-row">
	<div class="cal-row-label">
		<span class="cal-row-title">{title}</span>
		{#if description}
			<span class="cal-row-sub">{description}</span>
		{/if}
	</div>

	<div class="cal-row-values">
		<span class="cal-stat"><em>Current</em> {fmt(currentValue)}{#if unit}<small> {unit}</small>{/if}</span>
		<span class="cal-stat"><em>Factory</em> {fmt(factoryValue)}{#if unit}<small> {unit}</small>{/if}</span>
		<span class="cal-stat cal-stat-new">
			<em>New</em>
			{#if newValueInput.type === 'stepper'}
				<input
					type="number"
					min={newValueInput.min}
					max={newValueInput.max}
					step={newValueInput.step}
					value={Number(newValueInput.value.toFixed(decimals))}
					oninput={onStepperInput}
					aria-label={`${title} new value`}
				/>
			{:else}
				<input
					type="text"
					value="—"
					disabled
					readonly
					title="Set via the Apply dialog"
					aria-label={`${title} new value (set via Apply dialog)`}
				/>
			{/if}
			{#if unit}<small>{unit}</small>{/if}
		</span>
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
		display: flex;
		align-items: center;
		gap: 16px;
		padding: 8px 0;
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.06);
	}
	.cal-row:last-child {
		border-bottom: 0;
	}
	.cal-row-label {
		display: flex;
		flex-direction: column;
		min-width: 100px;
		flex: 0 0 auto;
	}
	.cal-row-title {
		font-size: 13px;
		font-weight: 600;
		color: var(--fg-1);
		line-height: 1.2;
	}
	.cal-row-sub {
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.55);
		line-height: 1.3;
		margin-top: 1px;
	}
	.cal-row-values {
		display: flex;
		align-items: center;
		gap: 14px;
		flex: 1 1 auto;
		min-width: 0;
		flex-wrap: wrap;
	}
	.cal-stat {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		font-family: var(--font-mono);
		font-size: 12px;
		font-variant-numeric: tabular-nums;
		color: var(--fg-1);
		white-space: nowrap;
	}
	.cal-stat em {
		font-style: normal;
		font-family: var(--font-sans);
		font-size: 10px;
		letter-spacing: var(--track-allcaps, 0.06em);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.5);
	}
	.cal-stat small {
		font-family: var(--font-sans);
		font-size: 10px;
		color: rgba(var(--tint-rgb), 0.5);
		margin-left: 1px;
	}
	.cal-stat-new input {
		width: 5.5em;
		border: 1px solid rgba(var(--tint-rgb), 0.18);
		border-radius: 4px;
		background: rgba(var(--tint-rgb), 0.04);
		padding: 2px 6px;
		font-family: var(--font-mono);
		font-size: 12px;
		font-variant-numeric: tabular-nums;
		color: var(--fg-1);
		outline: none;
	}
	.cal-stat-new input:focus-visible {
		border-color: rgba(var(--copper-rgb), 0.5);
		background: rgba(var(--tint-rgb), 0.06);
	}
	.cal-stat-new input:disabled {
		opacity: 0.5;
		cursor: not-allowed;
		text-align: center;
	}
	.cal-row-actions {
		display: flex;
		gap: 6px;
		flex: 0 0 auto;
	}

	@media (max-width: 720px) {
		.cal-row {
			flex-wrap: wrap;
		}
		.cal-row-values {
			gap: 10px;
		}
	}
</style>
