<script lang="ts">
	/**
	 * `SegmentRow` — one editable row of the segment list, ported from
	 * `SegmentRow` in `profile-edit-page.jsx`.
	 *
	 * Every per-segment field a DE1 profile supports lives on this single
	 * (wide) row. The controls are the quick-controls primitives.
	 *
	 * ## Labels
	 *
	 * Every field label is one `QSplitLabel` — the shell's label primitive —
	 * so they all share the quick-controls look and line up exactly. A label
	 * may be a bare word (Target / Time), a word + split toggle (Temp), or
	 * carry a leading on/off dot for an optional group (Volume / Exit / Max).
	 *
	 * ## Layout
	 *
	 * Each field is a uniform **cell** — a fixed box (`.pe-seg-cell`, invisible
	 * by default) with the label pinned to the top and the input to the
	 * bottom. The Max + Tolerance pair is the *visible* (`.is-grouped`) variant
	 * of that same box, so it lines up with the rest.
	 *
	 * ## Optional groups
	 *
	 * Volume, Exit and the Max limiter are optional; the label's on/off dot
	 * turns each on / off, and its input dims while off.
	 */
	import type {
		ProfileSegment,
		SegmentRamp,
		SegmentExit,
		SegmentLimiter
	} from '$lib/profiles';
	import QStepper from '$lib/components/brew/QStepper.svelte';
	import QSplitLabel from '$lib/components/brew/QSplitLabel.svelte';

	let {
		seg,
		index,
		count,
		active = false,
		onSelect,
		onEdit,
		onDelete,
		onReorder
	}: {
		/** The segment this row edits. */
		seg: ProfileSegment;
		/** Zero-based position, for the row number. */
		index: number;
		/** Total number of segments — bounds the row-number input. */
		count: number;
		/** Whether this segment is the selected one. */
		active?: boolean;
		/** Select this segment. */
		onSelect: () => void;
		/** Apply a partial patch to this segment. */
		onEdit: (patch: Partial<ProfileSegment>) => void;
		/** Delete this segment. */
		onDelete: () => void;
		/** Move the segment `id` to `toIndex` — drag-reorder + number edit. */
		onReorder: (id: string, toIndex: number) => void;
	} = $props();

	/** The target unit follows the segment mode. */
	const unit = $derived(seg.mode === 'pressure' ? 'bar' : 'ml/s');

	// ── Reorder ─────────────────────────────────────────────────────────
	//
	// Two ways to reorder: drag the row by its grip handle, or type a new
	// position into the row-number input — both route through `onReorder`,
	// which slides the other segments along.

	/** The dataTransfer key carrying the dragged segment's id. */
	const DRAG_KEY = 'application/x-crema-segment';
	/** The row element — used as the drag image. `$state` per the `bind:this` convention. */
	let rowEl = $state<HTMLDivElement>();
	/** True while a dragged segment hovers this row — shows the drop line. */
	let dropTarget = $state(false);

	/** Begin dragging this segment (from the grip handle). */
	function onDragStart(e: DragEvent): void {
		if (!e.dataTransfer) return;
		e.dataTransfer.setData(DRAG_KEY, seg.id);
		e.dataTransfer.effectAllowed = 'move';
		if (rowEl) e.dataTransfer.setDragImage(rowEl, 24, 20);
	}
	/** Allow a drop here and show the indicator. */
	function onDragOver(e: DragEvent): void {
		if (!e.dataTransfer?.types.includes(DRAG_KEY)) return;
		e.preventDefault();
		e.dataTransfer.dropEffect = 'move';
		dropTarget = true;
	}
	/** Drop a dragged segment onto this row's position. */
	function onDrop(e: DragEvent): void {
		e.preventDefault();
		dropTarget = false;
		const id = e.dataTransfer?.getData(DRAG_KEY);
		if (id && id !== seg.id) onReorder(id, index);
	}
	/** Reorder this segment from the 1-based row-number input. */
	function onNumCommit(raw: string): void {
		const n = Number(raw);
		if (!Number.isFinite(n)) return;
		onReorder(seg.id, Math.min(count, Math.max(1, Math.round(n))) - 1);
	}

	// ── Volume limit ────────────────────────────────────────────────────
	/** Whether the per-step volume limit is set. */
	const volumeOn = $derived(seg.volumeLimitMl > 0);
	/** Toggle the volume limit — a default 50 mL on, 0 (no limit) off. */
	function toggleVolume(): void {
		onEdit({ volumeLimitMl: volumeOn ? 0 : 50 });
	}

	// ── Exit condition ──────────────────────────────────────────────────
	//
	// `seg.exit` is optional. When it is null the threshold renders dimmed
	// against sensible defaults (flow / over / 4); the label dot creates or
	// clears the condition. Editing a sub-field only patches when exit is set.

	/** Whether an exit condition is set. */
	const exitOn = $derived(seg.exit != null);
	/** The exit condition, or its default placeholder when unset. */
	const exitView = $derived<SegmentExit>(
		seg.exit ?? { metric: 'flow', compare: 'over', threshold: 4 }
	);
	/** The exit-threshold unit follows the watched metric. */
	const exitUnit = $derived(exitView.metric === 'flow' ? 'ml/s' : 'bar');

	/** Toggle the exit condition on / off. */
	function toggleExit(): void {
		onEdit({ exit: exitOn ? null : { metric: 'flow', compare: 'over', threshold: 4 } });
	}
	/** Patch one field of the exit condition (no-op when exit is off). */
	function patchExit(p: Partial<SegmentExit>): void {
		if (!seg.exit) return;
		onEdit({ exit: { ...seg.exit, ...p } });
	}
	/** Flip the comparison between over (`>`) and under (`<`). */
	function flipCompare(): void {
		patchExit({ compare: exitView.compare === 'over' ? 'under' : 'over' });
	}

	// ── Max limiter ─────────────────────────────────────────────────────
	//
	// The limiter caps the *non-priority* quantity (flow on a pressure step,
	// pressure on a flow step). Optional — gated by its label's on/off dot.

	/** Whether the limiter is set. */
	const limiterOn = $derived(seg.limiter != null);
	/** The limiter, or its default placeholder when unset. */
	const limiterView = $derived<SegmentLimiter>(seg.limiter ?? { value: 6, range: 0.6 });
	/** The limiter caps the non-priority quantity — flow when pressure-priority. */
	const limiterUnit = $derived(seg.mode === 'pressure' ? 'ml/s' : 'bar');

	/** Toggle the limiter on / off. */
	function toggleLimiter(): void {
		onEdit({ limiter: limiterOn ? null : { value: 6, range: 0.6 } });
	}
	/** Set the limiter's Max value — 0 clears the limiter, >0 creates it. */
	function setLimiterValue(v: number): void {
		onEdit({ limiter: v <= 0 ? null : { value: v, range: seg.limiter?.range ?? 0.6 } });
	}
	/** Edit the limiter's tolerance (no-op when the limiter is off). */
	function setLimiterRange(v: number): void {
		if (!seg.limiter) return;
		onEdit({ limiter: { ...seg.limiter, range: v } });
	}
</script>

<div
	class="pe-seg"
	class:is-active={active}
	class:is-drop-target={dropTarget}
	role="button"
	tabindex="0"
	bind:this={rowEl}
	onclick={onSelect}
	onkeydown={(e) => (e.key === 'Enter' || e.key === ' ') && onSelect()}
	ondragover={onDragOver}
	ondragleave={() => (dropTarget = false)}
	ondrop={onDrop}
>
	<!-- Position: a grip handle to drag-reorder, and an editable 1-based
	     number — typing a new position slides the other segments along. -->
	<div class="pe-seg-pos">
		<button
			class="pe-seg-grip"
			type="button"
			draggable="true"
			aria-label="Drag to reorder segment"
			title="Drag to reorder"
			ondragstart={onDragStart}
			onclick={(e) => e.stopPropagation()}
		>
			<i class="ph ph-dots-six-vertical" aria-hidden="true"></i>
		</button>
		<input
			class="pe-seg-num"
			type="text"
			inputmode="numeric"
			value={index + 1}
			aria-label="Segment position"
			onclick={(e) => e.stopPropagation()}
			onchange={(e) => onNumCommit(e.currentTarget.value)}
		/>
	</div>

	<!-- Name, with the Type and Ramp toggles stacked beneath it. -->
	<div class="pe-seg-name">
		<input
			class="pe-seg-name-input"
			value={seg.name}
			placeholder="Segment name"
			onclick={(e) => e.stopPropagation()}
			oninput={(e) => onEdit({ name: e.currentTarget.value })}
		/>
		<QSplitLabel
			prefix="Type"
			options={[
				{ id: 'pressure', label: 'Pressure' },
				{ id: 'flow', label: 'Flow' }
			]}
			value={seg.mode}
			onChange={(m) => onEdit({ mode: m as ProfileSegment['mode'] })}
		/>
		<QSplitLabel
			prefix="Ramp"
			options={[
				{ id: 'smooth', label: 'Smooth' },
				{ id: 'fast', label: 'Fast' }
			]}
			value={seg.ramp}
			onChange={(r) => onEdit({ ramp: r as SegmentRamp })}
		/>
	</div>

	<!-- Target -->
	<div class="pe-seg-cell">
		<div class="pe-seg-cell-body">
			<QSplitLabel prefix="Target" />
			<div class="pe-seg-cell-input">
				<QStepper
					value={seg.target}
					{unit}
					min={0}
					max={12}
					step={0.1}
					onChange={(v) => onEdit({ target: v })}
				/>
			</div>
		</div>
	</div>

	<!-- Time -->
	<div class="pe-seg-cell">
		<div class="pe-seg-cell-body">
			<QSplitLabel prefix="Time" />
			<div class="pe-seg-cell-input">
				<QStepper
					value={seg.time}
					unit="s"
					min={1}
					max={60}
					step={1}
					onChange={(v) => onEdit({ time: Math.round(v) })}
				/>
			</div>
		</div>
	</div>

	<!-- Temp -->
	<div class="pe-seg-cell">
		<div class="pe-seg-cell-body">
			<QSplitLabel
				prefix="Temp"
				options={[
					{ id: 'basket', label: 'Basket' },
					{ id: 'mix', label: 'Mix' }
				]}
				value={seg.tempSensor}
				onChange={(s) => onEdit({ tempSensor: s as ProfileSegment['tempSensor'] })}
			/>
			<div class="pe-seg-cell-input">
				<QStepper
					value={seg.temperatureC}
					unit="°C"
					min={80}
					max={105}
					step={0.5}
					onChange={(v) => onEdit({ temperatureC: v })}
				/>
			</div>
		</div>
	</div>

	<!-- Volume limit — the label dot enables it; the input dims while off. -->
	<div class="pe-seg-cell">
		<div class="pe-seg-cell-body">
			<QSplitLabel prefix="Volume" dot dotOn={volumeOn} onDot={toggleVolume} />
			<div class="pe-seg-cell-input" class:is-off={!volumeOn}>
				<QStepper
					value={seg.volumeLimitMl}
					unit="mL"
					min={0}
					max={1023}
					step={5}
					onChange={(v) => onEdit({ volumeLimitMl: Math.round(v) })}
				/>
			</div>
		</div>
	</div>

	<!-- Exit condition — the over/under comparison renders as a `>` / `<`
	     symbol left of the threshold value, the way a unit sits to its right. -->
	<div class="pe-seg-cell">
		<div class="pe-seg-cell-body">
			<QSplitLabel
				prefix="Exit"
				dot
				dotOn={exitOn}
				onDot={toggleExit}
				options={[
					{ id: 'pressure', label: 'Pressure' },
					{ id: 'flow', label: 'Flow' }
				]}
				value={exitView.metric}
				onChange={(m) => patchExit({ metric: m as SegmentExit['metric'] })}
			/>
			<div class="pe-seg-cell-input" class:is-off={!exitOn}>
				<QStepper
					value={exitView.threshold}
					unit={exitUnit}
					min={0}
					max={12}
					step={0.1}
					onChange={(v) => patchExit({ threshold: v })}
				>
					{#snippet prefix()}
						<button
							class="pe-seg-cmp"
							type="button"
							aria-label="Toggle over / under"
							onclick={flipCompare}
						>
							{exitView.compare === 'over' ? '>' : '<'}
						</button>
					{/snippet}
				</QStepper>
			</div>
		</div>
	</div>

	<!-- Limiter — the visible variant of the cell box: Max + Tolerance side
	     by side in a copper-tinted frame; the Max label dot gates both. -->
	<div class="pe-seg-cell is-grouped">
		<div class="pe-seg-cell-body">
			<QSplitLabel prefix="Max" dot dotOn={limiterOn} onDot={toggleLimiter} />
			<div class="pe-seg-cell-input" class:is-off={!limiterOn}>
				<QStepper
					value={limiterView.value}
					unit={limiterUnit}
					min={0}
					max={12}
					step={0.1}
					onChange={setLimiterValue}
				/>
			</div>
		</div>
		<div class="pe-seg-cell-body">
			<QSplitLabel prefix="Tolerance" />
			<div class="pe-seg-cell-input" class:is-off={!limiterOn}>
				<QStepper
					value={limiterView.range}
					unit=""
					min={0}
					max={6}
					step={0.1}
					onChange={setLimiterRange}
				/>
			</div>
		</div>
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
		/* # · name(+Type+Ramp) · Target · Time · Temp · Volume · Exit ·
		   Max+Tolerance · ⌫. The five single field columns are an equal fixed
		   width; the Max box is two of them. The row is wide — it scrolls. */
		grid-template-columns:
			28px
			minmax(150px, 1fr)
			repeat(5, 160px)
			320px
			32px;
		gap: 12px;
		/* Centre the fixed-height cells in the row. The name column is the
		   sole flexible track — it absorbs the slack, keeping the delete
		   button pinned to the row's right edge. */
		align-items: center;
		min-width: 1420px;
		padding: 10px 14px;
		background: var(--bg-surface);
		border: 1px solid rgba(var(--tint-rgb), 0.05);
		border-radius: var(--radius-sm);
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	.pe-seg:hover {
		border-color: rgba(var(--tint-rgb), 0.12);
	}
	.pe-seg.is-active {
		border-color: var(--copper-500);
		background: rgba(193, 116, 75, 0.06);
	}
	/* A dragged segment will land at the top of this row. */
	.pe-seg.is-drop-target {
		box-shadow: inset 0 2px 0 var(--copper-500);
	}

	/* Position column — grip handle stacked over the editable number. */
	.pe-seg-pos {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 3px;
	}
	.pe-seg-grip {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 22px;
		height: 15px;
		padding: 0;
		background: transparent;
		border: 0;
		border-radius: 4px;
		color: rgba(var(--tint-rgb), 0.35);
		cursor: grab;
		font-size: 13px;
		transition: all var(--dur-1) var(--ease);
	}
	.pe-seg-grip:hover {
		color: rgba(var(--tint-rgb), 0.7);
		background: rgba(var(--tint-rgb), 0.06);
	}
	.pe-seg-grip:active {
		cursor: grabbing;
	}
	.pe-seg-num {
		width: 26px;
		padding: 2px 0;
		background: transparent;
		border: 0;
		border-bottom: 1px solid transparent;
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.55);
		text-align: center;
		outline: 0;
		transition: border-color var(--dur-1) var(--ease);
	}
	.pe-seg-num:hover,
	.pe-seg-num:focus {
		border-bottom-color: rgba(var(--tint-rgb), 0.25);
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
		color: var(--fg-1);
		padding: 1px 0;
		outline: 0;
		transition: border-color var(--dur-1) var(--ease);
	}
	.pe-seg-name-input:hover,
	.pe-seg-name-input:focus {
		border-bottom-color: rgba(var(--tint-rgb), 0.2);
	}

	/* The uniform field box. Invisible by default (transparent border); the
	   limiter cell makes it visible with `.is-grouped`. Every cell is the
	   same size, so its label (top) and input (bottom) line up across the
	   whole row — the visible Max box and the invisible ones are one shape. */
	.pe-seg-cell {
		display: flex;
		gap: 12px;
		height: 68px;
		padding: 7px;
		box-sizing: border-box;
		border: 1px solid transparent;
		border-radius: var(--radius-sm);
		min-width: 0;
	}
	.pe-seg-cell.is-grouped {
		background: rgba(193, 116, 75, 0.05);
		border-color: rgba(193, 116, 75, 0.22);
	}
	.pe-seg-cell-body {
		flex: 1;
		display: flex;
		flex-direction: column;
		min-width: 0;
	}
	/* The input bar — pinned to the bottom of the cell, so every bar in the
	   row sits on the same line; dims while its optional group is off. */
	.pe-seg-cell-input {
		margin-top: auto;
		transition: opacity var(--dur-1) var(--ease);
	}
	.pe-seg-cell-input.is-off {
		opacity: 0.4;
	}

	/* The over/under comparator — a `>` / `<` symbol left of the threshold,
	   rendered into the QStepper value box like a leading unit. */
	.pe-seg-cmp {
		background: transparent;
		border: 0;
		padding: 0;
		cursor: pointer;
		font-family: var(--font-mono);
		font-size: 15px;
		font-weight: 600;
		line-height: 1;
		color: var(--copper-400);
		transition: color var(--dur-1) var(--ease);
	}
	.pe-seg-cmp:hover {
		color: var(--copper-300);
	}

	.pe-seg-del {
		width: 30px;
		height: 30px;
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.4);
		border-radius: 6px;
		cursor: pointer;
		font-size: 14px;
		transition: all var(--dur-1) var(--ease);
	}
	.pe-seg-del:hover {
		color: var(--warning);
		background: rgba(217, 119, 87, 0.08);
	}

	/* Every label is a QSplitLabel — trim it to the compact segment-row
	   context: field-label size, centred content (overriding the design's
	   baseline alignment) so the on/off dot, prefix word and split toggle
	   all line up, and the prefix in the muted field-label colour. */
	.pe-seg :global(.qsplit) {
		font-size: 9px;
		gap: 6px;
		align-items: center;
	}
	.pe-seg :global(.qsplit-prefix) {
		color: rgba(var(--tint-rgb), 0.4);
	}
	/* Every stepper bar fills its (equal-width) cell. */
	.pe-seg :global(.qcs) {
		width: 100%;
	}
	/* The canonical stepper (22px digits, 36px buttons) is sized for the wide
	   Quick Sheet; a list row wants the design's tighter variant. */
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
