<script lang="ts">
	/**
	 * `YieldRatioStepper` — the Yield card, ported from the design's
	 * `YieldRatioStepper` in `quick-controls-v2.jsx`. A short label with the
	 * live brew ratio as a top-right superscript.
	 */
	import type { BrewParamState } from './brew-params.svelte';
	import QuickStepper from './QuickStepper.svelte';
	import QuickChipRow from './QuickChipRow.svelte';
	import { getSettingsStore, formatWeight } from '$lib/settings';
	import { formatRatio } from '$lib/utils/ratio';

	let {
		params,
		dotOn = false,
		onDot
	}: {
		params: BrewParamState;
		/**
		 * Whether weight-stop is engaged for this shot. The label dot
		 * renders lit when `true`. Click → flip via {@link onDot}; off
		 * suppresses SAW for this shot (the parent pushes
		 * `applyWeightTargetDisabled(true)` to the core).
		 */
		dotOn?: boolean;
		/** Called when the user clicks the dot. The parent flips state. */
		onDot?: () => void;
	} = $props();

	const p = $derived(params.current);
	const prefs = $derived(getSettingsStore().current);
	/** Live yield-to-dose ratio (`1:N`, one decimal — canonical formatter). */
	const ratio = $derived(formatRatio(p.dose, p.yield));
	/**
	 * Whether the live yield differs from the active profile / brew-default
	 * seed — drives the italics + copper tint on the value, and the
	 * tooltip ("Overriding default 36 g") on hover.
	 */
	const yieldOverridden = $derived(params.isOverridden('yield'));
	const yieldSeedLabel = $derived(formatWeight(params.seedOf('yield'), prefs.weightUnit));
</script>

<div>
	<div class="qcs-label-row">
		<span class="qcs-label">
			{#if onDot}
				<button
					type="button"
					class="qcs-dot"
					class:on={dotOn}
					onclick={onDot}
					aria-pressed={dotOn}
					aria-label={dotOn
						? 'Yield target on (click to disable for this shot)'
						: 'Yield target off (click to enable for this shot)'}
				></button>
			{/if}
			Yield
		</span>
		<span class="qcs-sup">{ratio}</span>
	</div>
	<QuickStepper
		value={p.yield}
		dimension="weight"
		min={10}
		max={80}
		step={0.5}
		onChange={(v) => params.set('yield', v)}
		overridden={yieldOverridden}
		overriddenTooltip="Overriding default {yieldSeedLabel}"
	/>
	<div style="height:6px"></div>
	<QuickChipRow
		options={[28, 32, 36, 40, 45]}
		value={p.yield}
		dimension="weight"
		onChange={(v) => params.set('yield', v)}
	/>
</div>

<style>
	/* Inline dot indicator on the Yield label — same convention as the
	   ProfileEditor (PeNumber) and SegmentRow volume dot. Visible only
	   when the parent passes an `onDot` callback. */
	.qcs-dot {
		display: inline-block;
		width: 8px;
		height: 8px;
		margin-right: 6px;
		vertical-align: middle;
		border: 1px solid rgba(var(--tint-rgb), 0.35);
		border-radius: 50%;
		background: transparent;
		padding: 0;
		cursor: pointer;
		transition:
			background 0.12s,
			border-color 0.12s;
	}
	.qcs-dot.on {
		background: var(--copper-400);
		border-color: var(--copper-400);
	}
	.qcs-dot:hover {
		border-color: rgba(var(--copper-rgb), 0.6);
	}
</style>
