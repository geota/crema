<script lang="ts">
	/**
	 * `DoseGrindStepper` — the combined Dose | Grind card, ported from the
	 * design's `DoseGrindStepper` in `quick-controls-v2.jsx`. The split-pill
	 * label is the toggle; one stepper edits whichever side is active.
	 */
	import type { BrewParamState } from './brew-params.svelte';
	import { quickControlBounds } from '#lib/core/bounds.js';
	import QuickSplitLabel from './QuickSplitLabel.svelte';
	import QuickStepper from './QuickStepper.svelte';
	import QuickChipRow from './QuickChipRow.svelte';
	import { phoneLayout } from '#lib/shell/phone.svelte.js';
	import { getSettingsStore, formatWeight } from '#lib/settings/index.js';
	import GrindMarker from '#lib/components/grinders/GrindMarker.svelte';
	import { grindChips, grindInputMode, grindNumber, grindText } from '#lib/bean/grinder.js';
	import type { GrindView } from '#lib/brew/grind-view.js';
	import { getBrewGrind } from '#lib/brew/grind-session.svelte.js';

	let {
		params,
		grind = null
	}: {
		params: BrewParamState;
		/**
		 * The active grinder's scale and the unsaved-grind marker (#488). With
		 * none, today's 0–20 range from the core's table.
		 */
		grind?: GrindView | null;
	} = $props();
	/** The steppers' ranges, from the core's table (F8). */
	const b = quickControlBounds();

	const p = $derived(params.current);
	const prefs = $derived(getSettingsStore().current);
	/**
	 * Whether the live dose differs from the active profile / brew-default
	 * seed — drives the italics + copper tint on the value, and the tooltip
	 * ("Overriding default 18 g") on hover. Grind has no profile-side
	 * default (it lives only as a log field, not on the upload payload),
	 * so the indicator is dose-only.
	 */
	const doseOverridden = $derived(params.isOverridden('dose'));
	const doseSeedLabel = $derived(formatWeight(params.seedOf('dose'), prefs.weightUnit));

	/** The grind stepper's range: the active grinder's scale (#488). */
	const gs = $derived(grind?.scale ?? { min: b.grind.min, max: b.grind.max, step: b.grind.step, unit: '' });
	/**
	 * Brew's grind on the stepper (DESIGN "No grind set is its own state"):
	 * its number, or blank when not set; a grind that isn't a plain number
	 * shows as typed. The first ± from either goes to the core's start.
	 */
	const dial = $derived(grind?.dial ?? null);
	/** Single steps around Brew's grind (the core's chips); none when it isn't set. */
	const chips = $derived(grindChips(gs, grind?.recorded ?? null, phoneLayout.current ? 4 : 5).map((c) => c.value));
	/** A typed value outside the scale is kept as typed, with a warning. */
	const outOfRange = $derived(dial !== null && (dial < gs.min || dial > gs.max));
	/** The dial as a shot records it (`grind_text`): the stepper shows what is recorded. */
	const grindFmt = (v: number): string => grindText(v, gs);
	/** The chip with the bag's saved value gets a copper ring while the grind differs. */
	const ringed = $derived(grind?.marker ? grindNumber(grind.saved) : null);
	const brewGrind = getBrewGrind();
	const setGrind = (v: number): void => brewGrind.setDial(v, gs);
</script>

{#snippet rangeWarning()}
	<span class="qdg-warn">Outside {gs.min}–{gs.max}</span>
{/snippet}

<div>
	<div class="qdg-label-row">
		<QuickSplitLabel
			options={[
				{ id: 'dose', label: 'Dose' },
				{ id: 'grind', label: 'Grind' }
			]}
			value={p.doseGrindMode}
			onChange={(v) => params.set('doseGrindMode', v as 'dose' | 'grind')}
		/>
		{#if p.doseGrindMode === 'grind' && grind?.marker}
			<span class="qcs-sup qdg-marker"><GrindMarker text={grind.marker} title={grind.markerTitle} /></span>
		{/if}
	</div>
	{#if p.doseGrindMode === 'dose'}
		<QuickStepper
			value={p.dose}
			name="Dose"
			dimension="weight"
			min={b.doseG.min}
			max={b.doseG.max}
			step={b.doseG.step}
			onChange={(v) => params.set('dose', v)}
			overridden={doseOverridden}
			overriddenTooltip="Overriding default {doseSeedLabel}"
		/>
		<div style="height:8px"></div>
		<QuickChipRow
			options={phoneLayout.current ? [16, 18, 19, 20] : [16, 17, 18, 19, 20]}
			value={p.dose}
			name="Dose"
			dimension="weight"
			onChange={(v) => params.set('dose', v)}
		/>
	{:else}
		<QuickStepper
			value={dial}
			blankStart={grind?.startFromBlank ?? gs.min}
			blankText={dial === null ? (grind?.recorded ?? '') : ''}
			name="Grind"
			unit={gs.unit}
			min={gs.min}
			max={gs.max}
			step={gs.step}
			precision={6}
			clampTyped={false}
			onChange={setGrind}
			fmt={grindFmt}
			parse={grindNumber}
			inputmode={grindInputMode(gs)}
			sub={outOfRange ? rangeWarning : undefined}
		/>
		<div style="height:8px"></div>
		<QuickChipRow
			options={chips}
			value={dial ?? Number.NaN}
			name="Grind"
			unit={gs.unit}
			ringed={ringed}
			onChange={setGrind}
			fmt={grindFmt}
		/>
	{/if}
</div>

<style>
	.qdg-label-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 8px;
	}
	.qdg-marker {
		display: inline-flex;
	}
	.qdg-warn {
		color: var(--warning, #d9a54a);
	}
</style>
