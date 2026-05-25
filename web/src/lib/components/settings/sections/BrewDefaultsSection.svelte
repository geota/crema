<script lang="ts">
	/**
	 * Brew defaults section — the starting point for new profiles and the
	 * shot-behaviour toggles.
	 *
	 * ## Real vs. stubbed
	 *
	 * **Real persistence** — every value here is an app preference persisted in
	 * `lib/settings`. They are app-side defaults the Brew screen can read later;
	 * persisting them now is the whole job. None of them is written to the DE1.
	 */
	import { getSettingsStore } from '$lib/settings';
	import StSectionHead from '../StSectionHead.svelte';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StToggle from '../StToggle.svelte';
	import StStepper from '../StStepper.svelte';

	const settings = getSettingsStore();
	const prefs = $derived(settings.current);
</script>

<StSectionHead
	eyebrow="Behaviour"
	title="Brew defaults"
	sub="The starting point for new profiles, and what the Quick Controls strip resets to."
/>

<StGroup title="Targets">
	<StRow title="Default dose" sub="Dose of ground coffee per shot.">
		{#snippet control()}
			<StStepper
				value={prefs.defaultDoseG}
				dimension="weight"
				step={0.1}
				min={5}
				max={30}
				onCommit={(v) => settings.set('defaultDoseG', v)}
			/>
		{/snippet}
	</StRow>
	<StRow title="Default ratio" sub="Yield-to-dose target for new profiles.">
		{#snippet control()}
			<StStepper
				value={prefs.defaultRatio}
				unit=":1"
				decimals={1}
				step={0.1}
				min={1}
				max={5}
				onCommit={(v) => settings.set('defaultRatio', v)}
			/>
		{/snippet}
	</StRow>
	<StRow title="Default brew temp" sub="Group temperature when no profile is loaded.">
		{#snippet control()}
			<StStepper
				value={prefs.defaultBrewTempC}
				dimension="temp"
				step={0.5}
				min={80}
				max={100}
				onCommit={(v) => settings.set('defaultBrewTempC', v)}
			/>
		{/snippet}
	</StRow>
	<StRow title="Default pre-infusion" sub="Pre-infusion time when no profile is loaded.">
		{#snippet control()}
			<StStepper
				value={prefs.defaultPreinfusionS}
				unit="s"
				step={1}
				min={0}
				max={60}
				onCommit={(v) => settings.set('defaultPreinfusionS', v)}
			/>
		{/snippet}
	</StRow>
</StGroup>

<!--
	Shot behaviour: "Stop on weight" and "Auto-tare on start" were removed
	2026-05-22 — both are profile-level fields (`CremaProfile.stopOnWeight`
	/ `.autoTare`) and live on the brew Quick Sheet for per-shot override.
	The Settings copies were dead weight that the audit caught.
-->
<StGroup title="Shot behaviour">
	<StRow
		title="Auto-purge after steam"
		sub="Run a 3s flush after steaming to clear the boiler."
	>
		{#snippet control()}
			<StToggle
				on={prefs.autoPurgeAfterSteam}
				onChange={(v) => settings.set('autoPurgeAfterSteam', v)}
				label="Auto-purge after steam"
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Group flush before each shot"
		sub="Adds 4–6s to each shot but stabilizes temp."
	>
		{#snippet control()}
			<StToggle
				on={prefs.groupFlushBeforeShot}
				onChange={(v) => settings.set('groupFlushBeforeShot', v)}
				label="Group flush before each shot"
			/>
		{/snippet}
	</StRow>
</StGroup>
