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
	 * The controls are the quick-controls primitives. The Type (Pressure |
	 * Flow), Ramp (Smooth | Fast) and Temp-sensor (Basket | Mix) toggles are
	 * `QSplitLabel`s with a category prefix — small, left-aligned label rows
	 * the size of a field label; Type and Ramp stack under the segment name.
	 * Target / Time / Temp / Volume are `QStepper`s.
	 *
	 * Every per-segment field lives on this single (wide) row, including the
	 * two compound fields — the structured exit condition and the limiter:
	 *
	 *  - **Exit** — optional (`seg.exit: SegmentExit | null`). A `.qmini-tog`
	 *    switches it on / off; when off the metric / threshold / over-under
	 *    controls render dimmed showing sensible defaults.
	 *  - **Max** — the limiter (`seg.limiter: SegmentLimiter | null`), modelled
	 *    the way Volume models its limit: a `Max` value of `0` *is* the off
	 *    state (no toggle). Bumping `Max` above 0 creates the limiter.
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

	// ── Exit condition ──────────────────────────────────────────────────
	//
	// `seg.exit` is optional. When it is null the sub-controls render dimmed
	// against sensible defaults (flow / 4 / over); the mini-toggle creates or
	// clears the condition. Editing a sub-field only patches when exit is set.

	/** The exit condition, or its dimmed default placeholder when unset. */
	const exitView = $derived<SegmentExit>(
		seg.exit ?? { metric: 'flow', compare: 'over', threshold: 4 }
	);
	/** The exit-threshold unit follows the watched metric. */
	const exitUnit = $derived(exitView.metric === 'flow' ? 'ml/s' : 'bar');

	/** Toggle the structured exit condition on / off. */
	function toggleExit(): void {
		const next: SegmentExit | null = seg.exit
			? null
			: { metric: 'flow', compare: 'over', threshold: 4 };
		onEdit({ exit: next });
	}

	/** Patch one field of the exit condition (no-op when exit is off). */
	function patchExit(p: Partial<SegmentExit>): void {
		if (!seg.exit) return;
		onEdit({ exit: { ...seg.exit, ...p } });
	}

	// ── Limiter ─────────────────────────────────────────────────────────
	//
	// Modelled like the Volume limit: `Max` value 0 ⟺ `limiter == null`. The
	// steppers stay clickable while off so bumping `Max` above 0 enables it.

	/** The limiter, or its dimmed default placeholder when unset. */
	const limiterView = $derived<SegmentLimiter>(seg.limiter ?? { value: 0, range: 0.6 });
	/** The limiter caps the *non-priority* quantity — flow when pressure-priority. */
	const limiterUnit = $derived(seg.mode === 'pressure' ? 'ml/s' : 'bar');

	/** Set the limiter's `Max` value — 0 clears the limiter, >0 creates it. */
	function setLimiterValue(v: number): void {
		if (v <= 0) {
			onEdit({ limiter: null });
		} else {
			onEdit({ limiter: { value: v, range: seg.limiter?.range ?? 0.6 } });
		}
	}

	/** Edit the limiter's tolerance range (no-op when the limiter is off). */
	function setLimiterRange(v: number): void {
		if (!seg.limiter) return;
		onEdit({ limiter: { ...seg.limiter, range: v } });
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
		<QSplitLabel
			prefix="Temp"
			options={[
				{ id: 'basket', label: 'Basket' },
				{ id: 'mix', label: 'Mix' }
			]}
			value={seg.tempSensor}
			onChange={(s) => onEdit({ tempSensor: s as ProfileSegment['tempSensor'] })}
		/>
		<QStepper
			value={seg.temperatureC}
			unit="°C"
			min={80}
			max={105}
			step={0.5}
			onChange={(v) => onEdit({ temperatureC: v })}
		/>
	</div>

	<!-- Volume limit — dimmed at 0 to read as "off"; bump it up to enable. -->
	<div class="pe-seg-field" class:is-off={seg.volumeLimitMl === 0}>
		<div class="pe-seg-field-label">Volume</div>
		<QStepper
			value={seg.volumeLimitMl}
			unit="mL"
			min={0}
			max={1023}
			step={5}
			onChange={(v) => onEdit({ volumeLimitMl: Math.round(v) })}
		/>
	</div>

	<!-- Exit condition — optional. Off ⟹ the metric / threshold / compare
	     controls render dimmed against their defaults; the mini-toggle
	     creates or clears the condition. -->
	<div class="pe-seg-exit">
		<div class="pe-seg-exit-head">
			<button
				class="qmini-tog"
				class:on={seg.exit != null}
				type="button"
				aria-label="Toggle exit condition"
				aria-pressed={seg.exit != null}
				onclick={toggleExit}
			></button>
			<QSplitLabel
				prefix="Exit"
				options={[
					{ id: 'pressure', label: 'Pressure' },
					{ id: 'flow', label: 'Flow' }
				]}
				value={exitView.metric}
				onChange={(m) => patchExit({ metric: m as SegmentExit['metric'] })}
			/>
		</div>
		<div class="pe-seg-exit-body" class:is-off={seg.exit == null}>
			<QStepper
				value={exitView.threshold}
				unit={exitUnit}
				min={0}
				max={12}
				step={0.1}
				onChange={(v) => patchExit({ threshold: v })}
			/>
			<QSplitLabel
				options={[
					{ id: 'over', label: 'over' },
					{ id: 'under', label: 'under' }
				]}
				value={exitView.compare}
				onChange={(c) => patchExit({ compare: c as SegmentExit['compare'] })}
			/>
		</div>
	</div>

	<!-- Limiter — `Max` value 0 ⟺ no limiter (Volume-style). The Max /
	     Tolerance steppers are visually grouped as one unit; the group dims
	     when off but the steppers stay clickable so Max can re-enable it. -->
	<div class="pe-seg-max" class:is-off={seg.limiter == null}>
		<div class="pe-seg-max-field">
			<div class="pe-seg-field-label">Max</div>
			<QStepper
				value={limiterView.value}
				unit={limiterUnit}
				min={0}
				max={12}
				step={0.1}
				onChange={setLimiterValue}
			/>
		</div>
		<div class="pe-seg-max-field">
			<div class="pe-seg-field-label">Tolerance</div>
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
		/* # · name(+Type+Ramp) · Target · Time · Temp+sensor · Volume · Exit ·
		   Max+Tolerance · ⌫. The QStepper columns get control-sized minima;
		   the name field — which also stacks the Type and Ramp toggles — takes
		   the slack. The row is wide; its container scrolls horizontally. */
		grid-template-columns:
			28px
			minmax(150px, 1.4fr)
			minmax(112px, 0.9fr)
			minmax(104px, 0.8fr)
			minmax(128px, 1fr)
			minmax(112px, 0.85fr)
			minmax(170px, 1.2fr)
			minmax(220px, 1.4fr)
			32px;
		gap: 12px;
		align-items: start;
		/* Keep the columns from crushing when the viewport is narrow — the
		   container scrolls instead. */
		min-width: 1000px;
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
		padding-top: 6px;
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
	/* Dimmed "off" state — used by the Volume column when its limit is 0, the
	   Exit body when no condition is set, and the Max group when no limiter is
	   set. The steppers stay clickable so bumping a value re-enables it. */
	.pe-seg-field.is-off,
	.pe-seg-exit-body.is-off,
	.pe-seg-max.is-off {
		opacity: 0.4;
	}
	.pe-seg-field-label {
		font-family: var(--font-sans);
		font-size: 9px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		font-weight: 600;
		color: rgba(244, 237, 224, 0.4);
		min-height: 16px;
		display: flex;
		align-items: center;
	}

	/* Exit column — a label row (mini-toggle + metric split-label) over the
	   threshold stepper and the over/under split-label, stacked. */
	.pe-seg-exit {
		display: flex;
		flex-direction: column;
		gap: 4px;
		min-width: 0;
	}
	.pe-seg-exit-head {
		display: flex;
		align-items: center;
		gap: 8px;
		min-height: 16px;
	}
	.pe-seg-exit-head .qmini-tog {
		flex: 0 0 30px;
		border: 0;
		padding: 0;
		cursor: pointer;
	}
	.pe-seg-exit-body {
		display: flex;
		flex-direction: column;
		gap: 4px;
		min-width: 0;
		transition: opacity var(--dur-1) var(--ease);
	}

	/* Max column — the limiter's Max + Tolerance steppers, wrapped in a
	   subtly copper-tinted bordered container so they read as one unit. */
	.pe-seg-max {
		display: flex;
		flex-direction: column;
		gap: 6px;
		min-width: 0;
		padding: 6px 8px;
		background: rgba(193, 116, 75, 0.05);
		border: 1px solid rgba(193, 116, 75, 0.22);
		border-radius: var(--radius-sm);
		transition: opacity var(--dur-1) var(--ease);
	}
	.pe-seg-max-field {
		display: flex;
		flex-direction: column;
		gap: 4px;
		min-width: 0;
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

	/* Trim the quick-controls primitives to the compact segment-row context.
	   The QSplitLabel toggles shrink to the size of a field label — small,
	   left-aligned, uppercase — so Type / Ramp / Temp / Exit read as labels. */
	.pe-seg :global(.qsplit) {
		font-size: 9px;
		gap: 6px;
		min-height: 16px;
	}
	.pe-seg :global(.qsplit-prefix) {
		color: rgba(244, 237, 224, 0.4);
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
