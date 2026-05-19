<script lang="ts">
	/**
	 * `SegmentAdvanced` — the advanced-settings panel for the **selected**
	 * segment, rendered inline beneath the segment list.
	 *
	 * `SegmentRow` stays a compact 7-column row; the per-segment fields a DE1
	 * profile supports beyond the row's common columns (temperature, structured
	 * exit condition, volume limit, advanced limiter, temp sensor) live here so
	 * the row never has to grow. Editing a field applies a partial patch to the
	 * same segment model the row and the curve editor share.
	 *
	 * The controls are the quick-controls primitives — `QStepper` for the
	 * numeric fields and `QSplitLabel` for the metric / compare / sensor
	 * toggles — and the `.qmini-tog` pill for the exit / limiter enable
	 * switches, matching the editor's existing visual language.
	 */
	import type { ProfileSegment, SegmentExit, SegmentLimiter } from '$lib/profiles';
	import QStepper from '$lib/components/brew/QStepper.svelte';
	import QSplitLabel from '$lib/components/brew/QSplitLabel.svelte';

	let {
		seg,
		index,
		onEdit
	}: {
		/** The selected segment this panel edits. */
		seg: ProfileSegment;
		/** Zero-based position, for the panel heading. */
		index: number;
		/** Apply a partial patch to this segment. */
		onEdit: (patch: Partial<ProfileSegment>) => void;
	} = $props();

	/** The exit-threshold unit follows the watched metric. */
	const exitUnit = $derived(seg.exit?.metric === 'flow' ? 'ml/s' : 'bar');

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

	/** Toggle the advanced limiter on / off. */
	function toggleLimiter(): void {
		const next: SegmentLimiter | null = seg.limiter ? null : { value: 6, range: 0.6 };
		onEdit({ limiter: next });
	}

	/** Patch one field of the limiter (no-op when the limiter is off). */
	function patchLimiter(p: Partial<SegmentLimiter>): void {
		if (!seg.limiter) return;
		onEdit({ limiter: { ...seg.limiter, ...p } });
	}
</script>

<div class="pe-adv">
	<div class="pe-adv-head">
		Advanced — segment {index + 1}
		<span class="pe-adv-head-name">{seg.name}</span>
	</div>

	<div class="pe-adv-grid">
		<!-- Temperature -->
		<div class="pe-adv-field">
			<div class="pe-adv-label">Temperature</div>
			<QStepper
				value={seg.temperatureC}
				unit="°C"
				min={80}
				max={105}
				step={0.5}
				onChange={(v) => onEdit({ temperatureC: v })}
			/>
		</div>

		<!-- Temp sensor -->
		<div class="pe-adv-field">
			<div class="pe-adv-label">Temp sensor</div>
			<QSplitLabel
				options={[
					{ id: 'basket', label: 'Basket' },
					{ id: 'mix', label: 'Mix' }
				]}
				value={seg.tempSensor}
				onChange={(s) => onEdit({ tempSensor: s as ProfileSegment['tempSensor'] })}
			/>
		</div>

		<!-- Volume limit -->
		<div class="pe-adv-field">
			<div class="pe-adv-label">Volume limit</div>
			<QStepper
				value={seg.volumeLimitMl}
				unit="mL"
				min={0}
				max={1023}
				step={5}
				onChange={(v) => onEdit({ volumeLimitMl: Math.round(v) })}
			/>
			<div class="pe-adv-hint">0 = no limit</div>
		</div>
	</div>

	<!-- Exit condition -->
	<div class="pe-adv-block">
		<button class="pe-adv-tog" type="button" onclick={toggleExit}>
			<span class="qmini-tog" class:on={seg.exit != null}></span>
			<span class="pe-adv-tog-title">Exit condition</span>
		</button>
		{#if seg.exit}
			<div class="pe-adv-row">
				<div class="pe-adv-field">
					<div class="pe-adv-label">Metric</div>
					<QSplitLabel
						options={[
							{ id: 'pressure', label: 'Pressure' },
							{ id: 'flow', label: 'Flow' }
						]}
						value={seg.exit.metric}
						onChange={(m) => patchExit({ metric: m as SegmentExit['metric'] })}
					/>
				</div>
				<div class="pe-adv-field">
					<div class="pe-adv-label">Compare</div>
					<QSplitLabel
						options={[
							{ id: 'over', label: 'Over' },
							{ id: 'under', label: 'Under' }
						]}
						value={seg.exit.compare}
						onChange={(c) => patchExit({ compare: c as SegmentExit['compare'] })}
					/>
				</div>
				<div class="pe-adv-field">
					<div class="pe-adv-label">Threshold</div>
					<QStepper
						value={seg.exit.threshold}
						unit={exitUnit}
						min={0}
						max={12}
						step={0.1}
						onChange={(v) => patchExit({ threshold: v })}
					/>
				</div>
			</div>
		{/if}
	</div>

	<!-- Advanced limiter -->
	<div class="pe-adv-block">
		<button class="pe-adv-tog" type="button" onclick={toggleLimiter}>
			<span class="qmini-tog" class:on={seg.limiter != null}></span>
			<span class="pe-adv-tog-title">Advanced limiter</span>
		</button>
		{#if seg.limiter}
			<div class="pe-adv-row">
				<div class="pe-adv-field">
					<div class="pe-adv-label">Limit value</div>
					<QStepper
						value={seg.limiter.value}
						unit={seg.mode === 'pressure' ? 'ml/s' : 'bar'}
						min={0}
						max={12}
						step={0.1}
						onChange={(v) => patchLimiter({ value: v })}
					/>
				</div>
				<div class="pe-adv-field">
					<div class="pe-adv-label">Range</div>
					<QStepper
						value={seg.limiter.range}
						unit=""
						min={0}
						max={6}
						step={0.1}
						onChange={(v) => patchLimiter({ range: v })}
					/>
				</div>
			</div>
		{/if}
	</div>
</div>

<style>
	.pe-adv {
		display: flex;
		flex-direction: column;
		gap: 14px;
		padding: 14px;
		background: rgba(193, 116, 75, 0.04);
		border: 1px solid var(--copper-500);
		border-radius: var(--radius-sm);
	}
	.pe-adv-head {
		font-family: var(--font-sans);
		font-size: 9px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		font-weight: 600;
		color: rgba(244, 237, 224, 0.45);
		display: flex;
		align-items: baseline;
		gap: 8px;
	}
	.pe-adv-head-name {
		font-size: 12px;
		letter-spacing: 0;
		text-transform: none;
		font-weight: 500;
		color: var(--ink-50);
	}
	.pe-adv-grid {
		display: grid;
		grid-template-columns: repeat(3, 1fr);
		gap: 12px;
	}
	.pe-adv-row {
		display: grid;
		grid-template-columns: repeat(3, 1fr);
		gap: 12px;
	}
	.pe-adv-field {
		display: flex;
		flex-direction: column;
		gap: 4px;
		min-width: 0;
	}
	.pe-adv-label {
		font-family: var(--font-sans);
		font-size: 9px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		font-weight: 600;
		color: rgba(244, 237, 224, 0.4);
	}
	.pe-adv-hint {
		font-family: var(--font-sans);
		font-size: 10px;
		color: rgba(244, 237, 224, 0.35);
	}
	.pe-adv-block {
		display: flex;
		flex-direction: column;
		gap: 10px;
		padding-top: 12px;
		border-top: 1px solid rgba(244, 237, 224, 0.06);
	}
	.pe-adv-tog {
		display: flex;
		align-items: center;
		gap: 10px;
		background: transparent;
		border: 0;
		padding: 0;
		cursor: pointer;
		text-align: left;
	}
	.pe-adv-tog .qmini-tog {
		flex: 0 0 30px;
	}
	.pe-adv-tog-title {
		font-family: var(--font-sans);
		font-size: 13px;
		color: var(--ink-50);
	}

	/* Trim the quick-controls primitives to this compact panel context — the
	   canonical stepper is sized for the wide Quick Sheet. Mirrors `SegmentRow`. */
	.pe-adv :global(.qcs-row) {
		padding: 3px;
		gap: 3px;
	}
	.pe-adv :global(.qcs-btn) {
		width: 28px;
		flex-basis: 28px;
	}
	.pe-adv :global(.qcs-num) {
		font-size: 17px;
	}
	.pe-adv :global(.qcs-val) {
		padding: 2px 4px;
	}
</style>
