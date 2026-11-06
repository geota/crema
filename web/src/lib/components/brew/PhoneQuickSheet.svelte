<script lang="ts">
	/**
	 * `PhoneQuickSheet` — Quick Controls at phone width, Android's arrangement
	 * (`PhoneQuickSheet`): Dial-in, Chart and Shot tabs in a bottom sheet, with
	 * Reset and Save profile pinned under them.
	 *
	 * The controls are the docked sheet's own: the same stepper cards on the
	 * same `BrewParamState`, the same Settings fields and the same callbacks, so
	 * what reaches the machine doesn't depend on the layout.
	 */
	import ArrowCounterClockwiseIcon from 'phosphor-svelte/lib/ArrowCounterClockwiseIcon';
	import BookmarkSimpleIcon from 'phosphor-svelte/lib/BookmarkSimpleIcon';
	import Icon from '#lib/icons/Icon.svelte';
	import { getSettingsStore } from '#lib/settings/index.js';
	import PhoneSheet from '#lib/shell/PhoneSheet.svelte';
	import BrewTempStepper from './BrewTempStepper.svelte';
	import { CHANNEL_GROUPS } from './channel-groups';
	import DoseGrindStepper from './DoseGrindStepper.svelte';
	import PreinfFlushStepper from './PreinfFlushStepper.svelte';
	import SteamStepper from './SteamStepper.svelte';
	import WaterStepper from './WaterStepper.svelte';
	import YieldRatioStepper from './YieldRatioStepper.svelte';
	import type { BrewParamState } from './brew-params.svelte';

	let {
		params,
		open,
		onClose,
		yieldTargetOn,
		onToggleYieldTarget,
		onToggleStopOnWeight,
		onToggleAutoTare,
		onToggleSteamEco,
		onSavePreset
	}: {
		params: BrewParamState;
		open: boolean;
		onClose: () => void;
		yieldTargetOn: boolean;
		onToggleYieldTarget: () => void;
		onToggleStopOnWeight: (enabled: boolean) => void;
		onToggleAutoTare: (enabled: boolean) => void;
		onToggleSteamEco: (enabled: boolean) => void;
		/** Save the dial as a new profile; `undefined` with no active profile. */
		onSavePreset?: () => void;
	} = $props();

	const settings = getSettingsStore();
	const prefs = $derived(settings.current);

	const TABS = [
		{ id: 'dial', label: 'Dial-in' },
		{ id: 'chart', label: 'Chart' },
		{ id: 'shot', label: 'Shot' }
	] as const;
	let tab = $state<(typeof TABS)[number]['id']>('dial');

	const shotToggles = $derived([
		{ label: 'Stop on weight', on: prefs.stopOnWeight, flip: onToggleStopOnWeight },
		{ label: 'Auto-tare on start', on: prefs.autoTareOnShotStart, flip: onToggleAutoTare },
		{
			label: 'Pre-flush before shot',
			on: prefs.groupFlushBeforeShot,
			flip: (v: boolean) => settings.set('groupFlushBeforeShot', v)
		},
		{
			label: 'Steam purge after',
			on: prefs.autoPurgeAfterSteam,
			flip: (v: boolean) => settings.set('autoPurgeAfterSteam', v)
		},
		{ label: 'Steam eco mode', on: prefs.steamEcoMode, flip: onToggleSteamEco }
	]);
</script>

{#snippet toggle(label: string, on: boolean, color: string, flip: () => void)}
	<button type="button" class={['pqs-tog', { on }]} aria-pressed={on} onclick={flip}>
		<span class="pqs-pill" style:background={on ? color : undefined}><span></span></span>
		<span class="pqs-tog-l">{label}</span>
	</button>
{/snippet}

<PhoneSheet
	{open}
	{onClose}
	title="Quick Controls"
	subtitle="Applies to the next shot — your saved profile is never changed."
>
	<div class="pqs">
		<div class="pqs-tabs" role="group" aria-label="Quick Controls section">
			{#each TABS as t (t.id)}
				<button
					type="button"
					class={['pqs-tab', { 'is-active': tab === t.id }]}
					aria-pressed={tab === t.id}
					onclick={() => (tab = t.id)}>{t.label}</button
				>
			{/each}
		</div>

		{#if tab === 'dial'}
			<div class="pqs-grid">
				<div class="pqs-cell"><DoseGrindStepper {params} /></div>
				<div class="pqs-cell">
					<YieldRatioStepper {params} dotOn={yieldTargetOn} onDot={onToggleYieldTarget} />
				</div>
				<div class="pqs-cell"><BrewTempStepper {params} /></div>
				<div class="pqs-cell"><PreinfFlushStepper {params} /></div>
				<div class="pqs-cell is-wide"><SteamStepper {params} /></div>
				<div class="pqs-cell is-wide"><WaterStepper {params} /></div>
			</div>
		{:else if tab === 'chart'}
			<p class="pqs-hint">Which channels draw on the live chart.</p>
			<div class="pqs-rows">
				{#each CHANNEL_GROUPS as g (g.icon)}
					{@const on1 = prefs[g.primary.key] as boolean}
					{@const on2 = prefs[g.secondary.key] as boolean}
					<div class="pqs-row">
						<Icon cls={'ph ph-' + g.icon} style="font-size:18px;color:{g.color}" aria-hidden="true" />
						{@render toggle(g.primary.phoneLabel, on1, g.primary.color, () =>
							settings.set(g.primary.key, !on1)
						)}
						{@render toggle(g.secondary.phoneLabel, on2, g.secondary.color, () =>
							settings.set(g.secondary.key, !on2)
						)}
					</div>
				{/each}
			</div>
		{:else}
			<p class="pqs-hint">Behaviour armed for the next shot.</p>
			<div class="pqs-rows">
				{#each shotToggles as t (t.label)}
					<div class="pqs-row">
						{@render toggle(t.label, t.on, 'var(--copper-500)', () => t.flip(!t.on))}
					</div>
				{/each}
			</div>
		{/if}
	</div>
	{#snippet foot()}
		<div class="pqs-foot">
			<button type="button" class="pqs-btn" onclick={() => params.reset()}>
				<ArrowCounterClockwiseIcon aria-hidden="true" /> Reset
			</button>
			{#if onSavePreset}
				<button type="button" class="pqs-btn is-primary" onclick={onSavePreset}>
					<BookmarkSimpleIcon aria-hidden="true" /> Save profile
				</button>
			{/if}
		</div>
	{/snippet}
</PhoneSheet>

<style>
	.pqs {
		display: flex;
		flex-direction: column;
		gap: 14px;
	}
	.pqs-tabs {
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
		gap: 4px;
		padding: 4px;
		border-radius: var(--radius-pill);
		background: rgba(var(--tint-rgb), 0.06);
	}
	.pqs-tab {
		min-height: 44px;
		border: 0;
		border-radius: var(--radius-pill);
		background: transparent;
		color: var(--fg-3);
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 500;
		cursor: pointer;
	}
	.pqs-tab.is-active {
		background: rgba(var(--copper-rgb), 0.18);
		color: var(--fg-1);
	}
	.pqs-tab:focus-visible,
	.pqs-tog:focus-visible,
	.pqs-btn:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
	.pqs-hint {
		margin: 0;
		font-family: var(--font-sans);
		font-size: 12px;
		color: var(--fg-3);
	}
	.pqs-grid {
		display: grid;
		grid-template-columns: repeat(2, minmax(0, 1fr));
		gap: 10px;
	}
	.pqs-cell {
		min-width: 0;
		padding: 10px 12px 12px;
		border-radius: 14px;
		background: var(--bg-surface);
	}
	.pqs-cell.is-wide {
		grid-column: 1 / -1;
	}
	/* Two steppers can't sit side by side on the narrowest phones: the round
	   buttons leave the digits under 50 px. */
	@media (max-width: 359px) {
		.pqs-grid {
			grid-template-columns: minmax(0, 1fr);
		}
	}

	/* The stepper cards' global `.qcs*` / `.qchip*` classes, as Android's QcCell:
	   round − value +, then a row of filled preset chips. The visuals stay small
	   and an invisible ::before keeps each target 44 px tall. */
	.pqs :global(.qcs-label-row),
	.pqs :global(.qsplit) {
		min-height: 18px;
		margin-bottom: 6px;
	}
	.pqs :global(.qcs-row) {
		align-items: center;
		justify-content: space-between;
		padding: 0;
		gap: 2px;
		background: transparent;
		box-shadow: none;
	}
	.pqs :global(.qcs-btn) {
		position: relative;
		flex: 0 0 34px;
		width: 34px;
		height: 34px;
		border-radius: 50%;
		background: rgba(var(--tint-rgb), 0.08);
	}
	.pqs :global(.qcs-btn::before) {
		content: '';
		position: absolute;
		inset: -5px;
	}
	.pqs :global(.qcs-val) {
		min-width: 0;
		padding: 0;
		gap: 2px;
	}
	.pqs :global(.qcs-num) {
		font-size: clamp(20px, 5.9vw, 23px);
		font-weight: 500;
	}
	.pqs :global(.qcs-num-btn) {
		min-height: 44px;
	}
	.pqs :global(.qcs-unit) {
		font-size: 12px;
	}
	.pqs :global(.qchipr) {
		flex-wrap: nowrap;
		gap: 6px;
	}
	.pqs :global(.qchip) {
		position: relative;
		flex: 1 1 0;
		min-height: 26px;
		align-items: center;
		padding: 5px 0;
		border: 0;
		background: rgba(var(--tint-rgb), 0.08);
		color: var(--fg-3);
		font-size: 11px;
	}
	.pqs :global(.qchip::before) {
		content: '';
		position: absolute;
		inset: -9px 0;
	}
	.pqs :global(.qchip.is-active) {
		background: rgba(var(--copper-rgb), 0.24);
		color: var(--fg-1);
	}
	.pqs :global(.qchip-unit) {
		display: none;
	}
	.pqs :global(.qsplit-opt::before) {
		content: '';
		position: absolute;
		inset: -14px -4px;
	}
	.pqs :global(.qcs-dot) {
		position: relative;
	}
	.pqs :global(.qcs-dot::before) {
		content: '';
		position: absolute;
		inset: -18px;
		border-radius: 50%;
	}

	.pqs-rows {
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.pqs-row {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 0 12px;
		border-radius: 12px;
		background: var(--bg-surface);
	}
	.pqs-tog {
		flex: 1 1 0;
		display: flex;
		align-items: center;
		gap: 8px;
		min-width: 0;
		min-height: 48px;
		padding: 0;
		border: 0;
		background: transparent;
		color: var(--fg-3);
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 500;
		text-align: left;
		cursor: pointer;
	}
	.pqs-tog.on {
		color: var(--fg-1);
	}
	.pqs-tog-l {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.pqs-pill {
		flex: none;
		display: flex;
		align-items: center;
		justify-content: flex-start;
		width: 38px;
		height: 22px;
		padding: 0 4px;
		border-radius: var(--radius-pill);
		background: rgba(var(--tint-rgb), 0.08);
		box-shadow: inset 0 0 0 1px rgba(var(--tint-rgb), 0.24);
	}
	.pqs-pill span {
		width: 12px;
		height: 12px;
		border-radius: 50%;
		background: rgba(var(--tint-rgb), 0.4);
	}
	.pqs-tog.on .pqs-pill {
		justify-content: flex-end;
		padding: 0 3px;
		box-shadow: none;
	}
	.pqs-tog.on .pqs-pill span {
		width: 16px;
		height: 16px;
		background: #fff;
	}

	.pqs-foot {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 8px;
	}
	.pqs-btn {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		min-height: 44px;
		padding: 0 20px;
		border: 0;
		border-radius: var(--radius-pill);
		background: rgba(var(--tint-rgb), 0.1);
		color: var(--fg-2);
		font-family: var(--font-sans);
		font-size: 14px;
		font-weight: 600;
		cursor: pointer;
	}
	.pqs-btn.is-primary {
		background: var(--copper-500);
		color: var(--fg-on-accent);
	}
	.pqs-btn :global(svg) {
		font-size: 18px;
	}
</style>
