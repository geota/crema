<script lang="ts">
	/**
	 * `SegmentRow` — one editable row of the segment list, ported from
	 * `SegmentRow` in `profile-edit-page.jsx`.
	 *
	 * The design's row was display-only; here every field edits the same
	 * segment model the curve editor drags — editing a row's target / time
	 * moves the curve's dot, and vice versa. Clicking the row selects the
	 * segment (highlighting it in the curve).
	 *
	 * The controls are the quick-controls primitives: a `QSplitLabel` split
	 * toggle for the mode (Pressure | Flow) and ramp (Smooth | Fast), and a
	 * `QStepper` (− / value+unit / +) for the Target and Time fields.
	 */
	import type { ProfileSegment, SegmentRamp } from '$lib/profiles';
	import QStepper from '$lib/components/brew/QStepper.svelte';
	import QSplitLabel from '$lib/components/brew/QSplitLabel.svelte';

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
		<QSplitLabel
			options={[
				{ id: 'pressure', label: 'Pressure' },
				{ id: 'flow', label: 'Flow' }
			]}
			value={seg.mode}
			onChange={(m) => onEdit({ mode: m as ProfileSegment['mode'] })}
		/>
	</div>

	<div class="pe-seg-field">
		<div class="pe-seg-field-label">Target</div>
		<QStepper
			value={seg.target}
			{unit}
			min={0}
			max={12}
			step={0.1}
			onChange={(v) => onEdit({ target: v })}
		/>
	</div>

	<div class="pe-seg-field">
		<div class="pe-seg-field-label">Time</div>
		<QStepper
			value={seg.time}
			unit="s"
			min={1}
			max={60}
			step={1}
			onChange={(v) => onEdit({ time: Math.round(v) })}
		/>
	</div>

	<div class="pe-seg-field">
		<div class="pe-seg-field-label">Ramp</div>
		<QSplitLabel
			options={[
				{ id: 'smooth', label: 'Smooth' },
				{ id: 'fast', label: 'Fast' }
			]}
			value={seg.ramp}
			onChange={(r) => onEdit({ ramp: r as SegmentRamp })}
		/>
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
		/* Every cell is an editable control. The Target / Time columns hold a
		   QStepper (− value +) and the mode / ramp columns a QSplitLabel split
		   toggle, so the columns get honest control-sized minima; the name and
		   exit-when fields take the remaining flexible space. */
		grid-template-columns:
			28px
			minmax(150px, 1.4fr)
			minmax(122px, 0.9fr)
			minmax(112px, 0.9fr)
			minmax(116px, 0.8fr)
			minmax(120px, 1.1fr)
			32px;
		gap: 14px;
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
		gap: 6px;
		min-width: 0;
	}
	.pe-seg-name-input {
		width: 100%;
		min-width: 0;
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
	.pe-seg-field {
		display: flex;
		flex-direction: column;
		gap: 4px;
		min-width: 0;
	}
	.pe-seg-field-label {
		font-family: var(--font-sans);
		font-size: 9px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		font-weight: 600;
		color: rgba(244, 237, 224, 0.4);
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

	/* Trim the quick-controls primitives to the compact segment-row context —
	   the canonical stepper number (22px) and buttons (36px) are sized for the
	   wide Quick Sheet; a list row wants the design's tighter variant. */
	.pe-seg :global(.qcs-row) {
		padding: 3px;
		gap: 3px;
	}
	.pe-seg :global(.qcs-btn) {
		width: 28px;
		flex-basis: 28px;
	}
	.pe-seg :global(.qcs-num) {
		font-size: 17px;
	}
	.pe-seg :global(.qcs-val) {
		padding: 2px 4px;
	}
</style>
