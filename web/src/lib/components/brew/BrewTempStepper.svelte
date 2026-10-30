<script lang="ts">
	/**
	 * `BrewTempStepper` — the BREW bucket: a `Temp | Pre-infuse` split.
	 *
	 * Pre-infuse used to live in the combined `PreinfFlushStepper` bucket
	 * with Flush; that bucket was retired 2026-05-22 — Pre-infuse pairs
	 * with Brew temp (same shot phase, both shape the extraction's start)
	 * and Flush became its own bucket (Time | Temp) on the right side of
	 * the Quick Sheet.
	 *
	 * The component name is kept as `BrewTempStepper` so existing imports
	 * keep working; conceptually it's now "BrewStepper."
	 */
	import type { BrewParamState } from './brew-params.svelte';
	import QuickSplitLabel from './QuickSplitLabel.svelte';
	import QuickStepper from './QuickStepper.svelte';
	import QuickChipRow from './QuickChipRow.svelte';
	import { getSettingsStore, formatTemp } from '#lib/settings/index.js';
	import { celsius_to_fahrenheit as celsiusToFahrenheit } from '#lib/wasm/de1_wasm.js';

	let { params }: { params: BrewParamState } = $props();

	const p = $derived(params.current);
	const prefs = $derived(getSettingsStore().current);
	/**
	 * Drift indicators for the two split-pill modes. Both fields live on
	 * the active profile / brew-default seed (`brewTemp` and `preinf`),
	 * so italics + copper tint apply to whichever the user is currently
	 * editing.
	 */
	const tempOverridden = $derived(params.isOverridden('brewTemp'));
	const tempSeedLabel = $derived(formatTemp(params.seedOf('brewTemp'), prefs.tempUnit));
	const preinfOverridden = $derived(params.isOverridden('preinf'));
	const preinfSeedLabel = $derived(`${params.seedOf('preinf')} s`);
	/**
	 * The dial shows an absolute temperature; the core's range for the active
	 * profile bounds it (no step may leave the core's temperature limits once
	 * every step is shifted). The quick-pick chips outside it are hidden.
	 */
	const tempBounds = $derived(params.brewTempBounds());
	const tempChips = $derived(
		[88, 91, 93, 95, 97].filter((t) => t >= tempBounds.min && t <= tempBounds.max)
	);
	/**
	 * "+x.x °C from profile": the dial's distance from the profile's own brew
	 * temperature, in the user's unit (a °F offset is the converted
	 * difference).
	 */
	const tempOffsetLabel = $derived.by(() => {
		const seed = params.seedOf('brewTemp');
		const delta =
			prefs.tempUnit === 'F'
				? celsiusToFahrenheit(p.brewTemp) - celsiusToFahrenheit(seed)
				: p.brewTemp - seed;
		const sign = delta > 0 ? '+' : '−';
		return `${sign}${Math.abs(delta).toFixed(1)} °${prefs.tempUnit === 'F' ? 'F' : 'C'} from profile`;
	});
</script>

<div>
	<!-- The on/off dot rides only on the Pre-infuse mode (Brew temp has no
	     "disabled" sentinel). It flips `preinf` between 0 (off) and the
	     8 s brew-default, dimming the stepper + chips while off — the same
	     affordance as the segment editor's optional groups. -->
	<QuickSplitLabel
		prefix="Brew"
		options={[
			{ id: 'temp', label: 'temp' },
			{ id: 'preinf', label: 'pre-infuse' }
		]}
		value={p.brewMode}
		onChange={(v) => params.set('brewMode', v as 'temp' | 'preinf')}
		dot={p.brewMode === 'preinf'}
		dotOn={p.preinf > 0}
		onDot={() => params.set('preinf', p.preinf > 0 ? 0 : 8)}
	/>
	{#if p.brewMode === 'temp'}
		<QuickStepper
			value={p.brewTemp}
			name="Brew temperature"
			dimension="temp"
			min={tempBounds.min}
			max={tempBounds.max}
			step={0.5}
			onChange={(v) => params.set('brewTemp', v)}
			overridden={tempOverridden}
			overriddenTooltip="Overriding default {tempSeedLabel}"
		/>
		{#if tempOverridden}
			<div class="bts-offset" role="status">
				<span>{tempOffsetLabel}</span>
				<button
					type="button"
					class="bts-reset"
					onclick={() => params.resetKey('brewTemp')}
					aria-label="Reset brew temperature to the profile's {tempSeedLabel}"
				>
					Reset
				</button>
			</div>
		{/if}
		<div style="height:6px"></div>
		<QuickChipRow
			options={tempChips}
			value={p.brewTemp}
			name="Brew temperature"
			dimension="temp"
			onChange={(v) => params.set('brewTemp', v)}
		/>
	{:else}
		<div class="bts-preinf" class:is-off={p.preinf === 0}>
			<QuickStepper
				value={p.preinf}
				name="Pre-infusion"
				unit="s"
				min={0}
				max={30}
				step={1}
				onChange={(v) => params.set('preinf', v)}
				fmt={(v) => v.toFixed(0)}
				overridden={preinfOverridden}
				overriddenTooltip="Overriding default {preinfSeedLabel}"
			/>
			<div style="height:6px"></div>
			<QuickChipRow
				options={[0, 4, 8, 12, 16]}
				value={p.preinf}
				name="Pre-infusion"
				unit="s"
				onChange={(v) => params.set('preinf', v)}
			/>
		</div>
	{/if}
</div>

<style>
	/* Pre-infuse stepper + chips dim while off (preinf === 0) — the same
	   `is-off` treatment the profile editor's optional segment groups use,
	   so the disabled state reads at a glance. The label's dot stays lit /
	   clickable to bring it back. */
	.bts-preinf {
		transition: opacity var(--dur-1) var(--ease);
	}
	.bts-preinf.is-off {
		opacity: 0.4;
	}
	/* "+x.x °C from profile" + Reset, under the dial while it is overridden. */
	.bts-offset {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 8px;
		margin-top: 4px;
		font-size: 11px;
		color: var(--fg-accent);
	}
	.bts-reset {
		background: none;
		border: 0;
		padding: 2px 4px;
		font: inherit;
		color: inherit;
		text-decoration: underline;
		cursor: pointer;
	}
</style>
