<script lang="ts">
	/**
	 * `WaterStepper` — the Quick Sheet's Hot water card (hot-water modes spec
	 * §2.7). A "Hot water" prefix with a Temp | Amount toggle (view state); the
	 * Amount view sets the persisted dose: a Time · ml · Ratio switch, the
	 * mode's stepper and chips (ranges from the core, `hotWaterBounds`), and
	 * one readout line from the core's resolved target.
	 *
	 * The dose lives in the settings store (`hotWaterDose()` /
	 * `setHotWaterDose()`), and every edit goes to the core through the app's
	 * one push. This component computes no amount.
	 */
	import type { BrewParamState } from './brew-params.svelte';
	import QuickSplitLabel from './QuickSplitLabel.svelte';
	import QuickStepper from './QuickStepper.svelte';
	import QuickChipRow from './QuickChipRow.svelte';
	import type { HotWaterBounds, HotWaterDose } from '#lib/core/crema-core.js';
	import { HotWaterMode } from '#lib/core/crema-core.js';
	import { hotWaterBoundsJson, ratioLabel } from '#lib/wasm/de1_wasm.js';
	import { getSettingsStore } from '#lib/settings/store.svelte.js';
	import { getCremaUiState } from '#lib/state/ui-state.svelte.js';
	import { getCremaAppContext } from '#lib/shell/app-context.js';
	import { hotWaterReadout } from '#lib/brew/hot-water.js';

	let { params }: { params: BrewParamState } = $props();

	const p = $derived(params.current);
	const settings = getSettingsStore();
	const ui = getCremaUiState();
	const appCtx = getCremaAppContext();

	/** The core's steppers' ranges, chips and defaults (fixed per build). */
	const bounds = JSON.parse(hotWaterBoundsJson()) as HotWaterBounds;

	const dose = $derived.by(() => {
		void settings.current;
		return settings.hotWaterDose();
	});

	const MODES = [
		{ id: HotWaterMode.Time, full: 'Time', short: 's', name: 'Time' },
		{ id: HotWaterMode.Volume, full: 'ml', short: 'ml', name: 'Volume' },
		{ id: HotWaterMode.Ratio, full: 'Ratio', short: '1:x', name: 'Ratio of the shot' }
	] as const;

	/** Store the edited dose, then push it (the core rewrites the packet). */
	function edit(next: HotWaterDose): void {
		settings.setHotWaterDose(next);
		void appCtx().app?.pushSteamHotwater();
	}

	const readout = $derived(
		ui.current.hotWaterTarget
			? hotWaterReadout(
					ui.current.hotWaterTarget,
					{ weightUnit: settings.current.weightUnit, volumeUnit: settings.current.volumeUnit },
					dose.ratio
				)
			: ''
	);
</script>

<div class="qhw">
	<QuickSplitLabel
		prefix="Hot water"
		options={[
			{ id: 'temp', label: 'temp' },
			{ id: 'amount', label: 'amount' }
		]}
		value={p.waterMode}
		onChange={(v) => params.set('waterMode', v as 'temp' | 'amount')}
	/>
	{#if p.waterMode === 'temp'}
		<QuickStepper
			value={p.waterTemp}
			name="Hot water temperature"
			dimension="temp"
			min={40}
			max={98}
			step={1}
			onChange={(v) => params.set('waterTemp', v)}
		/>
		<div style="height:6px"></div>
		<QuickChipRow
			options={[60, 75, 85, 92, 96]}
			value={p.waterTemp}
			name="Hot water temperature"
			dimension="temp"
			onChange={(v) => params.set('waterTemp', v)}
		/>
	{:else}
		<div class="qhw-seg" role="radiogroup" aria-label="Hot water amount by">
			{#each MODES as m (m.id)}
				<button
					type="button"
					role="radio"
					class="qhw-seg-opt"
					class:is-active={dose.mode === m.id}
					aria-checked={dose.mode === m.id}
					aria-label={m.name}
					onclick={() => edit({ ...dose, mode: m.id })}
				>
					<span class="qhw-seg-full">{m.full}</span><span class="qhw-seg-short">{m.short}</span>
				</button>
			{/each}
		</div>
		{#if dose.mode === HotWaterMode.Time}
			<QuickStepper
				value={dose.timeS}
				name="Hot water time"
				unit="s"
				min={bounds.timeS.min}
				max={bounds.timeS.max}
				step={bounds.timeS.step}
				fmt={(v) => (dose.timeNoLimit && bounds.timeNoLimit ? 'no limit' : String(Math.round(v)))}
				onChange={(v) => edit({ ...dose, timeS: v, timeNoLimit: false })}
			/>
			<QuickChipRow
				options={bounds.timeS.chips}
				value={dose.timeNoLimit && bounds.timeNoLimit ? Number.NaN : dose.timeS}
				name="Hot water time"
				unit="s"
				onChange={(v) => edit({ ...dose, timeS: v, timeNoLimit: false })}
			/>
			{#if bounds.timeNoLimit}
				<button
					type="button"
					class="qchip qhw-nolimit"
					class:is-active={dose.timeNoLimit}
					aria-pressed={dose.timeNoLimit}
					onclick={() => edit({ ...dose, timeNoLimit: true })}>No limit</button
				>
			{/if}
		{:else if dose.mode === HotWaterMode.Volume}
			<QuickStepper
				value={dose.volumeMl}
				name="Hot water volume"
				dimension="volume"
				min={bounds.volumeMl.min}
				max={bounds.volumeMl.max}
				step={bounds.volumeMl.step}
				onChange={(v) => edit({ ...dose, volumeMl: v })}
			/>
			<QuickChipRow
				options={bounds.volumeMl.chips}
				value={dose.volumeMl}
				name="Hot water volume"
				dimension="volume"
				onChange={(v) => edit({ ...dose, volumeMl: v })}
			/>
		{:else}
			<QuickStepper
				value={dose.ratio}
				name="Hot water ratio"
				min={bounds.ratio.min}
				max={bounds.ratio.max}
				step={bounds.ratio.step}
				fmt={(v) => ratioLabel(v)}
				onChange={(v) => edit({ ...dose, ratio: v })}
			/>
			<QuickChipRow
				options={bounds.ratio.chips}
				value={dose.ratio}
				name="Hot water ratio"
				unit="1:"
				unitBefore
				fmt={(v) => ratioLabel(v).slice(2)}
				onChange={(v) => edit({ ...dose, ratio: v })}
			/>
		{/if}
		<p class="qhw-readout" title={readout || undefined} aria-live="polite">{readout}</p>
	{/if}
</div>
