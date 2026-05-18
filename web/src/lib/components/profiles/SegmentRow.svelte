<script lang="ts">
	/**
	 * `SegmentRow` — one editable row of the segment list, ported from
	 * `SegmentRow` in `profile-edit-page.jsx`.
	 *
	 * The design's row was display-only; here every field is an input bound
	 * **two-way** to the same segment model the curve editor drags — editing a
	 * row's target / time moves the curve's dot, and vice versa. Clicking the
	 * row selects the segment (highlighting it in the curve).
	 */
	import type { ProfileSegment, SegmentRamp } from '$lib/profiles';

	let {
		seg,
		index,
		active = false,
		onSelect,
		onEdit,
		onDelete
	}: {
		/** The segment this row edits. */
		seg: ProfileSegment;
		/** Zero-based position, for the row number. */
		index: number;
		/** Whether this segment is the selected one. */
		active?: boolean;
		/** Select this segment. */
		onSelect: () => void;
		/** Apply a partial patch to this segment. */
		onEdit: (patch: Partial<ProfileSegment>) => void;
		/** Delete this segment. */
		onDelete: () => void;
	} = $props();

	/** The target unit follows the segment mode. */
	const unit = $derived(seg.mode === 'pressure' ? 'bar' : 'ml/s');

	/** Parse a numeric input, clamping to a sane range; ignore garbage. */
	function num(value: string, min: number, max: number): number | null {
		const n = Number(value);
		if (!Number.isFinite(n)) return null;
		return Math.min(max, Math.max(min, n));
	}
</script>

<div
	class="pe-seg"
	class:is-active={active}
	role="button"
	tabindex="0"
	onclick={onSelect}
	onkeydown={(e) => (e.key === 'Enter' || e.key === ' ') && onSelect()}
>
	<div class="pe-seg-num">{index + 1}</div>

	<div class="pe-seg-name">
		<input
			class="pe-seg-name-input"
			value={seg.name}
			placeholder="Segment name"
			onclick={(e) => e.stopPropagation()}
			oninput={(e) => onEdit({ name: e.currentTarget.value })}
		/>
		<select
			class="pe-seg-mode-select"
			value={seg.mode}
			onclick={(e) => e.stopPropagation()}
			onchange={(e) =>
				onEdit({ mode: e.currentTarget.value as ProfileSegment['mode'] })}
		>
			<option value="pressure">pressure</option>
			<option value="flow">flow</option>
		</select>
	</div>

	<div class="pe-seg-field">
		<div class="pe-seg-field-label">Target</div>
		<div class="pe-seg-cell">
			<input
				class="pe-seg-numinput"
				type="number"
				step="0.1"
				min="0"
				max="12"
				value={seg.target}
				onclick={(e) => e.stopPropagation()}
				oninput={(e) => {
					const v = num(e.currentTarget.value, 0, 12);
					if (v != null) onEdit({ target: v });
				}}
			/>
			<em>{unit}</em>
		</div>
	</div>

	<div class="pe-seg-field">
		<div class="pe-seg-field-label">Time</div>
		<div class="pe-seg-cell">
			<input
				class="pe-seg-numinput"
				type="number"
				step="1"
				min="1"
				max="60"
				value={seg.time}
				onclick={(e) => e.stopPropagation()}
				oninput={(e) => {
					const v = num(e.currentTarget.value, 1, 60);
					if (v != null) onEdit({ time: Math.round(v) });
				}}
			/>
			<em>s</em>
		</div>
	</div>

	<div class="pe-seg-field">
		<div class="pe-seg-field-label">Ramp</div>
		<select
			class="pe-seg-ramp-select"
			value={seg.ramp}
			onclick={(e) => e.stopPropagation()}
			onchange={(e) => onEdit({ ramp: e.currentTarget.value as SegmentRamp })}
		>
			<option value="smooth">smooth</option>
			<option value="fast">fast</option>
		</select>
	</div>

	<div class="pe-seg-field pe-seg-field-exit">
		<div class="pe-seg-field-label">Exit when</div>
		<input
			class="pe-seg-exit-input"
			value={seg.exitAt ?? ''}
			placeholder="—"
			onclick={(e) => e.stopPropagation()}
			oninput={(e) => onEdit({ exitAt: e.currentTarget.value.trim() || null })}
		/>
	</div>

	<button
		class="pe-seg-del"
		title="Delete segment"
		aria-label="Delete segment"
		onclick={(e) => {
			e.stopPropagation();
			onDelete();
		}}
	>
		<i class="ph ph-trash" aria-hidden="true"></i>
	</button>
</div>

<style>
	.pe-seg {
		display: grid;
		grid-template-columns: 30px 1.2fr 0.9fr 0.7fr 0.9fr 1.3fr 32px;
		gap: 12px;
		align-items: center;
		padding: 10px 14px;
		background: var(--espresso-900);
		border: 1px solid rgba(244, 237, 224, 0.05);
		border-radius: var(--radius-sm);
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	.pe-seg:hover {
		border-color: rgba(244, 237, 224, 0.12);
	}
	.pe-seg.is-active {
		border-color: var(--copper-500);
		background: rgba(193, 116, 75, 0.06);
	}
	.pe-seg-num {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 11px;
		color: rgba(244, 237, 224, 0.4);
		text-align: center;
	}
	.pe-seg-name {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.pe-seg-name-input {
		background: transparent;
		border: 0;
		border-bottom: 1px solid transparent;
		font-family: var(--font-sans);
		font-size: 13px;
		color: var(--ink-50);
		padding: 1px 0;
		outline: 0;
		transition: border-color var(--dur-1) var(--ease);
	}
	.pe-seg-name-input:hover,
	.pe-seg-name-input:focus {
		border-bottom-color: rgba(244, 237, 224, 0.2);
	}
	.pe-seg-mode-select,
	.pe-seg-ramp-select {
		background: transparent;
		border: 0;
		font-family: var(--font-sans);
		color: rgba(244, 237, 224, 0.6);
		cursor: pointer;
		outline: 0;
		padding: 0;
	}
	.pe-seg-mode-select {
		font-size: 9px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
	}
	.pe-seg-ramp-select {
		font-size: 12px;
		color: rgba(244, 237, 224, 0.75);
	}
	.pe-seg-mode-select option,
	.pe-seg-ramp-select option {
		background: var(--espresso-850);
		color: var(--ink-50);
	}
	.pe-seg-field-label {
		font-family: var(--font-sans);
		font-size: 9px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		font-weight: 600;
		color: rgba(244, 237, 224, 0.4);
		margin-bottom: 2px;
	}
	.pe-seg-cell {
		display: flex;
		align-items: baseline;
		gap: 2px;
	}
	.pe-seg-cell em {
		font-style: normal;
		font-size: 10px;
		color: rgba(244, 237, 224, 0.5);
	}
	.pe-seg-numinput {
		width: 44px;
		background: rgba(244, 237, 224, 0.04);
		border: 1px solid rgba(244, 237, 224, 0.08);
		border-radius: 4px;
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 13px;
		color: var(--ink-50);
		padding: 2px 4px;
		outline: 0;
		transition: border-color var(--dur-1) var(--ease);
	}
	.pe-seg-numinput:focus {
		border-color: var(--copper-500);
	}
	.pe-seg-exit-input {
		background: rgba(244, 237, 224, 0.04);
		border: 1px solid rgba(244, 237, 224, 0.08);
		border-radius: 4px;
		font-family: var(--font-mono);
		font-size: 11px;
		color: rgba(244, 237, 224, 0.7);
		padding: 3px 6px;
		width: 100%;
		min-width: 0;
		outline: 0;
		transition: border-color var(--dur-1) var(--ease);
	}
	.pe-seg-exit-input:focus {
		border-color: var(--copper-500);
	}
	.pe-seg-del {
		width: 30px;
		height: 30px;
		background: transparent;
		border: 0;
		color: rgba(244, 237, 224, 0.4);
		border-radius: 6px;
		cursor: pointer;
		font-size: 14px;
		transition: all var(--dur-1) var(--ease);
	}
	.pe-seg-del:hover {
		color: #d97757;
		background: rgba(217, 119, 87, 0.08);
	}
</style>
