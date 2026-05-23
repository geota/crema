<script lang="ts">
	/**
	 * Display & units section — theme, density, screensaver and the four unit
	 * choices.
	 *
	 * ## Real vs. stubbed
	 *
	 * **Real** — every control here is a persisted app preference in
	 * `lib/settings`. The theme control is fully end-to-end: it sets
	 * `data-theme` on `<html>` (the design ships `[data-theme="dark"]`; dark
	 * stays the default) and persists, so a reload paints the chosen theme. The
	 * units feed the rest of the app's value formatting; density and screensaver
	 * are persisted app preferences.
	 */
	import { getSettingsStore } from '$lib/settings';
	import StSectionHead from '../StSectionHead.svelte';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StToggle from '../StToggle.svelte';
	import StSegment from '../StSegment.svelte';

	const settings = getSettingsStore();
	const prefs = $derived(settings.current);
</script>

<StSectionHead
	eyebrow="Visual"
	title="Display & units"
	sub="How values render across the app."
/>

<StGroup title="Appearance">
	<StRow
		title="Theme"
		sub="Crema is designed for low-light cafés. A light theme is available for bright rooms."
	>
		{#snippet control()}
			<StSegment
				value={prefs.theme}
				options={[
					{ value: 'dark', label: 'Dark' },
					{ value: 'light', label: 'Light' }
				]}
				onChange={(v) => settings.set('theme', v as 'dark' | 'light')}
			/>
		{/snippet}
	</StRow>
	<StRow title="Density" sub="Card padding and stepper sizes." notImplemented>
		{#snippet control()}
			<StSegment
				value={prefs.density}
				options={[
					{ value: 'compact', label: 'Compact' },
					{ value: 'comfortable', label: 'Comfortable' },
					{ value: 'spacious', label: 'Spacious' }
				]}
				onChange={(v) =>
					settings.set('density', v as 'compact' | 'comfortable' | 'spacious')}
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Screensaver"
		sub="After 10 minutes of inactivity, show a calm pour animation."
		notImplemented
	>
		{#snippet control()}
			<StToggle
				on={prefs.screensaver}
				onChange={(v) => settings.set('screensaver', v)}
				label="Screensaver"
			/>
		{/snippet}
	</StRow>
</StGroup>

<StGroup title="Units">
	<StRow title="Weight">
		{#snippet control()}
			<StSegment
				value={prefs.weightUnit}
				options={[
					{ value: 'g', label: 'g' },
					{ value: 'oz', label: 'oz' }
				]}
				onChange={(v) => settings.set('weightUnit', v as 'g' | 'oz')}
			/>
		{/snippet}
	</StRow>
	<StRow title="Temperature">
		{#snippet control()}
			<StSegment
				value={prefs.tempUnit}
				options={[
					{ value: 'C', label: '°C' },
					{ value: 'F', label: '°F' }
				]}
				onChange={(v) => settings.set('tempUnit', v as 'C' | 'F')}
			/>
		{/snippet}
	</StRow>
	<StRow title="Volume">
		{#snippet control()}
			<StSegment
				value={prefs.volumeUnit}
				options={[
					{ value: 'ml', label: 'mL' },
					{ value: 'floz', label: 'fl oz' }
				]}
				onChange={(v) => settings.set('volumeUnit', v as 'ml' | 'floz')}
			/>
		{/snippet}
	</StRow>
	<StRow title="Pressure">
		{#snippet control()}
			<StSegment
				value={prefs.pressureUnit}
				options={[
					{ value: 'bar', label: 'bar' },
					{ value: 'psi', label: 'psi' }
				]}
				onChange={(v) => settings.set('pressureUnit', v as 'bar' | 'psi')}
			/>
		{/snippet}
	</StRow>
</StGroup>
