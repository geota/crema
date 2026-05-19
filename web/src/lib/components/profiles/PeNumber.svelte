<script lang="ts">
	/**
	 * `PeNumber` — a labelled −/+ number stepper, ported from `PeNumber` in
	 * `profile-edit-page.jsx`. Used in the editor's Targets grid.
	 */
	let {
		label,
		value,
		step = 1,
		unit,
		min,
		max,
		digits = 1,
		onChange
	}: {
		/** The field label (eyebrow). */
		label: string;
		/** The current value. */
		value: number;
		/** Increment per button press. */
		step?: number;
		/** Unit suffix shown after the number. */
		unit: string;
		/** Minimum allowed value. */
		min: number;
		/** Maximum allowed value. */
		max: number;
		/** Decimal places to display. */
		digits?: number;
		/** Called with the clamped new value. */
		onChange: (value: number) => void;
	} = $props();

	/** Step the value by `dir × step`, clamped to `[min, max]`. */
	function inc(dir: number): void {
		const next = Math.min(max, Math.max(min, Number((value + dir * step).toFixed(2))));
		onChange(next);
	}
</script>

<div class="pe-num">
	<div class="t-eyebrow">{label}</div>
	<div class="pe-num-row">
		<button class="pe-num-btn" aria-label="Decrease {label}" onclick={() => inc(-1)}>
			<i class="ph ph-minus" aria-hidden="true"></i>
		</button>
		<div class="pe-num-val">
			<span class="pe-num-num">{value.toFixed(digits)}</span>
			<span class="pe-num-unit">{unit}</span>
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
		color: var(--ink-50);
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
		color: var(--ink-50);
	}
	.pe-num-unit {
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.5);
	}
</style>
