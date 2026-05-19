<script lang="ts">
	/**
	 * `SegmentRow` — one editable row of the segment list, ported from
	 * `SegmentRow` in `profile-edit-page.jsx`.
	 *
	 * Every per-segment field a DE1 profile supports lives on this single
	 * (wide) row. The controls are the quick-controls primitives: `QSplitLabel`
	 * split toggles, `QStepper`s for the numbers.
	 *
	 * ## Layout
	 *
	 * Each field is a uniform **cell** — a fixed-size box (`.pe-seg-cell`,
	 * invisible by default) holding a label pinned to the top and an input
	 * pinned to the bottom. The Max + Tolerance pair is the *visible* variant
	 * of that same box (`.is-grouped`), so it lines up with the rest instead
	 * of needing layout hacks: every label sits on one line, every input bar
	 * on another, all bars equal width.
	 *
	 * ## Optional groups
	 *
	 * Volume, Exit and the Max limiter are optional. Each is gated by a
	 * **toggle-label** — a dotted copper chip doubling as the label — that
	 * turns the group on / off; while off its controls dim.
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

	// ── Volume limit ────────────────────────────────────────────────────
	/** Whether the per-step volume limit is set. */
	const volumeOn = $derived(seg.volumeLimitMl > 0);
	/** Toggle the volume limit — a default 50 mL on, 0 (no limit) off. */
	function toggleVolume(): void {
		onEdit({ volumeLimitMl: volumeOn ? 0 : 50 });
	}

	// ── Exit condition ──────────────────────────────────────────────────
	//
	// `seg.exit` is optional. When it is null the sub-controls render dimmed
	// against sensible defaults (flow / over / 4); the toggle-label creates or
	// clears the condition. Editing a sub-field only patches when exit is set.

	/** Whether an exit condition is set. */
	const exitOn = $derived(seg.exit != null);
	/** The exit condition, or its dimmed default placeholder when unset. */
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
	// pressure on a flow step). Optional — gated by the same toggle-label.

	/** Whether the limiter is set. */
	const limiterOn = $derived(seg.limiter != null);
	/** The limiter, or its dimmed default placeholder when unset. */
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

<!--
	The toggle-label — a dotted chip that enables / disables an optional group.
	Filled copper dot when on; hollow when off. Stays full-opacity even while
	its group is dimmed, so it can always be seen and clicked.
-->
{#snippet togLabel(text: string, on: boolean, toggle: () => void)}
	<button class="pe-seg-tog" class:on type="button" onclick={toggle}>
		<span class="pe-seg-tog-dot"></span>
		{text}
	</button>
{/snippet}

<div
	class="pe-seg"
	class:is-active={active}
	role="button"
	tabindex="0"
	onclick={onSelect}
	onkeydown={(e) => (e.key === 'Enter' || e.key === ' ') && onSelect()}
>
	<div class="pe-seg-num">{index + 1}</div>

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
			<div class="pe-seg-label-row"><span class="pe-seg-label">Target</span></div>
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
			<div class="pe-seg-label-row"><span class="pe-seg-label">Time</span></div>
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
			<div class="pe-seg-label-row">
				<span class="pe-seg-label">Temp</span>
				<QSplitLabel
					options={[
						{ id: 'basket', label: 'Basket' },
						{ id: 'mix', label: 'Mix' }
					]}
					value={seg.tempSensor}
					onChange={(s) => onEdit({ tempSensor: s as ProfileSegment['tempSensor'] })}
				/>
			</div>
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

	<!-- Volume limit — gated by its toggle-label; dimmed while off. -->
	<div class="pe-seg-cell">
		<div class="pe-seg-cell-body">
			<div class="pe-seg-label-row">
				{@render togLabel('Volume', volumeOn, toggleVolume)}
			</div>
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
			<div class="pe-seg-label-row">
				{@render togLabel('Exit', exitOn, toggleExit)}
				<div class="pe-seg-dim" class:is-off={!exitOn}>
					<QSplitLabel
						options={[
							{ id: 'pressure', label: 'Pressure' },
							{ id: 'flow', label: 'Flow' }
						]}
						value={exitView.metric}
						onChange={(m) => patchExit({ metric: m as SegmentExit['metric'] })}
					/>
				</div>
			</div>
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
	     by side in a copper-tinted frame; gated by the Max toggle-label. -->
	<div class="pe-seg-cell is-grouped">
		<div class="pe-seg-cell-body">
			<div class="pe-seg-label-row">
				{@render togLabel('Max', limiterOn, toggleLimiter)}
			</div>
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
			<div class="pe-seg-label-row"><span class="pe-seg-label">Tolerance</span></div>
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
		/* The cells are fixed-height; centre them in the row so the visible
		   Max box and every invisible field box sit mid-row. The name column
		   is the sole flexible track — it absorbs all slack, keeping the
		   delete button pinned to the row's right edge. */
		align-items: center;
		min-width: 1420px;
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
	   row sits on the same line and is the same width. */
	.pe-seg-cell-input {
		margin-top: auto;
		transition: opacity var(--dur-1) var(--ease);
	}

	/* The uniform label row — every column has exactly one, a fixed-height
	   flex box that vertically centres whatever sits inside it (a plain label
	   span, a split-toggle, or a dotted chip). Because the container is
	   identical everywhere, every input bar below starts on the same line —
	   the dot in a toggle-chip is just centred content and shifts nothing. */
	.pe-seg-label-row {
		height: 18px;
		display: flex;
		align-items: center;
		gap: 8px;
	}
	.pe-seg-label {
		font-family: var(--font-sans);
		font-size: 9px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		font-weight: 600;
		color: rgba(244, 237, 224, 0.4);
	}
	/* A dimmable inline wrapper — the Exit metric while the group is off. */
	.pe-seg-dim {
		display: flex;
		align-items: center;
		transition: opacity var(--dur-1) var(--ease);
	}
	.pe-seg-dim.is-off,
	.pe-seg-cell-input.is-off {
		opacity: 0.4;
	}

	/* The toggle-label chip — a dotted enable switch doubling as the label.
	   No height of its own: it is centred content inside `.pe-seg-label-row`,
	   exactly like a plain label span, so it never shifts the input below. */
	.pe-seg-tog {
		display: inline-flex;
		align-items: center;
		gap: 5px;
		background: transparent;
		border: 0;
		padding: 0;
		cursor: pointer;
		font-family: var(--font-sans);
		font-size: 9px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		font-weight: 600;
		color: rgba(244, 237, 224, 0.3);
	}
	.pe-seg-tog.on {
		color: rgba(244, 237, 224, 0.6);
	}
	.pe-seg-tog-dot {
		width: 7px;
		height: 7px;
		border-radius: 50%;
		border: 1px solid rgba(244, 237, 224, 0.3);
		box-sizing: border-box;
		flex: 0 0 7px;
		transition: all var(--dur-1) var(--ease);
	}
	.pe-seg-tog.on .pe-seg-tog-dot {
		background: var(--copper-500);
		border-color: var(--copper-500);
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
		margin-top: 7px;
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

	/* Trim the quick-controls primitives to the compact segment-row context.
	   The QSplitLabel toggles shrink to field-label size and centre their
	   content (overriding the baseline alignment) so a split-toggle label
	   lines up with a plain label and a chip. */
	/* No forced height — the split-toggle sizes to its content and is centred
	   by `.pe-seg-label-row`, exactly like a plain label span or a chip, so
	   "Temp" sits at the same height as "Target" / "Time". */
	.pe-seg :global(.qsplit) {
		font-size: 9px;
		gap: 6px;
		align-items: center;
	}
	.pe-seg :global(.qsplit-prefix) {
		color: rgba(244, 237, 224, 0.4);
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
