<script lang="ts">
	/**
	 * `ManualScaleLine` — the scale status under the Manual page's steps
	 * (brew modes §3), with the setup toggles while no brew runs: Tare, Start
	 * on first pour (with a scale) and the cue bell.
	 */
	import BellIcon from 'phosphor-svelte/lib/BellIcon';
	import BellSlashIcon from 'phosphor-svelte/lib/BellSlashIcon';
	import { brewCueSoundOn, getSettingsStore } from '#lib/settings/index.js';
	import { primeBrewCues } from '#lib/brew/cues.js';
	import type { GuidedRun } from '#lib/brew/guided-run.svelte.js';

	let {
		run,
		connected,
		onTare
	}: {
		run: GuidedRun;
		connected: boolean;
		onTare?: () => void;
	} = $props();

	const settings = getSettingsStore();
	// The same setting as Settings → Display → Guided brew sounds.
	const soundOn = $derived(brewCueSoundOn(settings.current));

	function toggleSound(): void {
		const next = !soundOn;
		settings.set('brewCueSound', next);
		if (next) primeBrewCues();
	}
</script>

<div class="msl">
	<span class="msl-status">
		<span class={['msl-dot', { 'is-on': connected }]} aria-hidden="true"></span>
		{connected ? 'Scale connected — targets are live' : 'No scale — steps run on timers and taps'}
	</span>
	{#if run.idle}
		<span class="msl-tools">
			{#if connected}
				<button type="button" class="msl-btn" onclick={onTare}>Tare</button>
				<label class="msl-toggle">
					<input type="checkbox" bind:checked={run.startOnPour} />
					Start on first pour
				</label>
			{/if}
			<button
				type="button"
				class={['msl-bell', { 'is-on': soundOn }]}
				aria-pressed={soundOn}
				aria-label="Cue sound"
				title={soundOn ? 'Cue sound on — tap to mute' : 'Cue sound off — tap to chime at steps'}
				onclick={toggleSound}
			>
				{#if soundOn}
					<BellIcon size={16} weight="fill" aria-hidden="true" />
				{:else}
					<BellSlashIcon size={16} aria-hidden="true" />
				{/if}
			</button>
		</span>
	{/if}
</div>

<style>
	.msl {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		justify-content: space-between;
		gap: 8px 12px;
		font-family: var(--font-sans);
		font-size: 12px;
		color: var(--fg-3);
	}
	.msl-status {
		display: inline-flex;
		align-items: center;
		gap: 8px;
	}
	.msl-dot {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		background: rgba(var(--tint-rgb), 0.25);
	}
	.msl-dot.is-on {
		background: var(--success);
	}
	.msl-tools {
		display: inline-flex;
		align-items: center;
		gap: 10px;
	}
	.msl-btn {
		border: 1px solid rgba(var(--tint-rgb), 0.14);
		border-radius: 999px;
		background: transparent;
		color: var(--fg-2);
		padding: 4px 12px;
		font: inherit;
		cursor: pointer;
	}
	.msl-toggle {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		color: var(--fg-2);
		cursor: pointer;
	}
	.msl-bell {
		width: 30px;
		height: 30px;
		border-radius: 50%;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		background: transparent;
		color: var(--fg-3);
		cursor: pointer;
	}
	.msl-bell.is-on {
		color: var(--fg-accent);
		border-color: var(--copper-600);
	}
	.msl-btn:focus-visible,
	.msl-bell:focus-visible {
		outline: 2px solid var(--copper-400);
		outline-offset: 2px;
	}
</style>
