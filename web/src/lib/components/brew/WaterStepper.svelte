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
	import { hotWaterReadout, hotWaterSub } from '#lib/brew/hot-water.js';
	import { menuKeyboard } from '#lib/a11y/focus.js';
	import CheckIcon from 'phosphor-svelte/lib/CheckIcon';

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

	/** The amount modes: the label word, the menu's name and its one-line hint. */
	const MODES = [
		{ id: HotWaterMode.Time, label: 'time', name: 'Time', hint: 'Stops after a set time' },
		{ id: HotWaterMode.Volume, label: 'ml', name: 'Volume', hint: 'Stops at a set amount' },
		{ id: HotWaterMode.Ratio, label: 'ratio', name: 'Ratio', hint: "Follows the shot's yield" }
	] as const;
	const current = $derived(MODES.find((m) => m.id === dose.mode) ?? MODES[2]);
	const hasNoLimit = bounds.timeNoLimit;

	let menuOpen = $state(false);
	let rootEl = $state<HTMLDivElement>();
	const trigger = () => rootEl?.querySelector<HTMLElement>('.qsplit-opt.has-menu');

	function pick(mode: HotWaterMode): void {
		menuOpen = false;
		if (mode !== dose.mode) edit({ ...dose, mode });
	}

	// Outside-tap dismissal, attached after the opening tap (SortPill's rule).
	$effect(() => {
		if (!menuOpen) return;
		let attached = false;
		const close = (e: PointerEvent) => {
			if (!rootEl?.querySelector('.qhw-menu')?.contains(e.target as Node)) menuOpen = false;
		};
		queueMicrotask(() => {
			window.addEventListener('pointerdown', close, true);
			attached = true;
		});
		return () => {
			if (attached) window.removeEventListener('pointerdown', close, true);
		};
	});

	/** Store the edited dose, then push it (the core rewrites the packet). */
	function edit(next: HotWaterDose): void {
		settings.setHotWaterDose(next);
		void appCtx().app?.pushSteamHotwater();
	}

	const units = $derived({
		weightUnit: settings.current.weightUnit,
		volumeUnit: settings.current.volumeUnit
	});
	/** The stepper's secondary line: the short form, and the full sentence. */
	const sub = $derived(ui.current.hotWaterTarget ? hotWaterSub(ui.current.hotWaterTarget, units) : null);
	const readout = $derived(
		ui.current.hotWaterTarget
			? hotWaterReadout(ui.current.hotWaterTarget, units, dose.ratio)
			: ''
	);
</script>

<div class="qhw" bind:this={rootEl}>
	<QuickSplitLabel
		prefix="Hot water"
		options={[
			{ id: 'temp', label: 'temp' },
			{ id: 'amount', label: current.label, menu: true }
		]}
		value={p.waterMode}
		onChange={(v) => params.set('waterMode', v as 'temp' | 'amount')}
		onMenu={() => (menuOpen = !menuOpen)}
		{menuOpen}
	/>
	{#if menuOpen && p.waterMode === 'amount'}
		<div
			class="qhw-menu"
			role="menu"
			aria-label="Hot water amount by"
			{@attach menuKeyboard({ onClose: () => (menuOpen = false), trigger })}
		>
			{#each MODES as m (m.id)}
				<button
					type="button"
					class="qhw-menu-item"
					class:is-active={dose.mode === m.id}
					role="menuitemradio"
					aria-checked={dose.mode === m.id}
					onclick={() => pick(m.id)}
				>
					<span class="qhw-menu-name">{m.name}</span>
					<span class="qhw-menu-hint">{m.hint}</span>
					{#if dose.mode === m.id}<CheckIcon class="qhw-menu-check" aria-hidden="true" />{/if}
				</button>
			{/each}
		</div>
	{/if}
	{#snippet subLine()}
		{#if sub}
			<span class="qhw-sub" class:is-warn={sub.warn} title={readout || undefined}>
				<span class="qhw-sub-short" aria-hidden="true">{sub.short}</span><span class="qhw-sub-long"
					>{readout}</span
				>
			</span>
		{/if}
	{/snippet}
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
	{:else if dose.mode === HotWaterMode.Time}
		<QuickStepper
			value={dose.timeS}
			name="Hot water time"
			unit={dose.timeNoLimit && hasNoLimit ? '' : 's'}
			min={bounds.timeS.min}
			max={bounds.timeS.max}
			step={bounds.timeS.step}
			fmt={(v) => (dose.timeNoLimit && hasNoLimit ? '∞' : String(Math.round(v)))}
			onChange={(v) => edit({ ...dose, timeS: v, timeNoLimit: false })}
			sub={sub ? subLine : undefined}
		/>
		<div style="height:6px"></div>
		<QuickChipRow
			options={bounds.timeS.chips}
			value={dose.timeNoLimit && hasNoLimit ? Number.NaN : dose.timeS}
			name="Hot water time"
			unit="s"
			onChange={(v) => edit({ ...dose, timeS: v, timeNoLimit: false })}
			extra={hasNoLimit
				? {
						label: '∞',
						name: 'No limit',
						active: dose.timeNoLimit,
						onSelect: () => edit({ ...dose, timeNoLimit: true })
					}
				: undefined}
		/>
	{:else if dose.mode === HotWaterMode.Volume}
		<QuickStepper
			value={dose.volumeMl}
			name="Hot water volume"
			dimension="volume"
			min={bounds.volumeMl.min}
			max={bounds.volumeMl.max}
			step={bounds.volumeMl.step}
			onChange={(v) => edit({ ...dose, volumeMl: v })}
			sub={sub ? subLine : undefined}
		/>
		<div style="height:6px"></div>
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
			sub={sub ? subLine : undefined}
		/>
		<div style="height:6px"></div>
		<QuickChipRow
			options={bounds.ratio.chips}
			value={dose.ratio}
			name="Hot water ratio"
			fmt={(v) => ratioLabel(v)}
			onChange={(v) => edit({ ...dose, ratio: v })}
		/>
	{/if}
</div>
