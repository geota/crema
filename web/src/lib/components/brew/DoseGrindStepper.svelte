<script lang="ts">
	/**
	 * `DoseGrindStepper` — the combined Dose | Grind card, ported from the
	 * design's `DoseGrindStepper` in `quick-controls-v2.jsx`. The split-pill
	 * label is the toggle; one stepper edits whichever side is active.
	 */
	import type { BrewParamState } from './brew-params.svelte';
	import QuickSplitLabel from './QuickSplitLabel.svelte';
	import QuickStepper from './QuickStepper.svelte';
	import QuickChipRow from './QuickChipRow.svelte';
	import { getSettingsStore, formatWeight } from '$lib/settings';

	let { params }: { params: BrewParamState } = $props();

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
</script>

<div>
	<QuickSplitLabel
		options={[
			{ id: 'dose', label: 'Dose' },
			{ id: 'grind', label: 'Grind' }
		]}
		value={p.doseGrindMode}
		onChange={(v) => params.set('doseGrindMode', v as 'dose' | 'grind')}
	/>
	{#if p.doseGrindMode === 'dose'}
		<QuickStepper
			value={p.dose}
			dimension="weight"
			min={5}
			max={30}
			step={0.1}
			onChange={(v) => params.set('dose', v)}
			overridden={doseOverridden}
			overriddenTooltip="Overriding default {doseSeedLabel}"
		/>
		<div style="height:8px"></div>
		<QuickChipRow
			options={[16, 17, 18, 19, 20]}
			value={p.dose}
			dimension="weight"
			onChange={(v) => params.set('dose', v)}
		/>
	{:else}
		<QuickStepper
			value={p.grind}
			min={0}
			max={20}
			step={0.1}
			onChange={(v) => params.set('grind', v)}
			fmt={(v) => v.toFixed(1)}
		/>
		<div style="height:8px"></div>
		<QuickChipRow
			options={[3.8, 4.0, 4.2, 4.4, 4.6]}
			value={p.grind}
			onChange={(v) => params.set('grind', v)}
			fmt={(v) => v.toFixed(1)}
		/>
	{/if}
</div>
