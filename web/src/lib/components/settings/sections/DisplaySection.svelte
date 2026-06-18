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
	import { wakeLockSupported } from '$lib/shell/wake-lock';
	import { getCremaAppContext } from '$lib/shell/app-context';
	import StSectionHead from '../StSectionHead.svelte';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StToggle from '../StToggle.svelte';
	import StSegment from '../StSegment.svelte';

	const settings = getSettingsStore();
	const prefs = $derived(settings.current);
	const appCtx = getCremaAppContext();

	/**
	 * Apply the new weight-unit pref end-to-end:
	 *   1. Persist in the settings store (drives every shell formatter).
	 *   2. Cache on the core so the Decent Scale LCD-enable auto-policy
	 *      picks the right wire packet on the next DE1 Idle entry.
	 *   3. Re-emit the LCD-enable in the new unit so a Decent Scale
	 *      that's already showing the LCD switches immediately rather
	 *      than waiting for the next Idle re-entry.
	 *
	 * (3) is a no-op when no Decent Scale is connected.
	 */
	async function setWeightUnit(value: 'g' | 'oz'): Promise<void> {
		settings.set('weightUnit', value);
		const app = appCtx().app;
		if (app) {
			await app.applyWeightUnitPref(value);
			await app.refreshDecentScaleLcd();
		}
	}
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
	<!-- Screen Wake Lock while a shot pulls — the layout's effect consumes
	     this (`$lib/shell/wake-lock`). Disabled with a note on browsers
	     without the API (pre-16.4 Safari, insecure contexts). -->
	<StRow
		title="Keep screen on while brewing"
		sub={wakeLockSupported
			? 'Holds a screen wake lock while a shot is pulling so the display can\u2019t sleep mid-extraction.'
			: 'Not supported by this browser \u2014 needs the Screen Wake Lock API.'}
	>
		{#snippet control()}
			<StToggle
				on={prefs.keepScreenOnBrew}
				onChange={(v) => settings.set('keepScreenOnBrew', v)}
				disabled={!wakeLockSupported}
				label="Keep screen on while brewing"
			/>
		{/snippet}
	</StRow>
</StGroup>

<StGroup title="Units">
	<StRow title="Weight">
		{#snippet control()}
			<StSegment
				equalWidth
				value={prefs.weightUnit}
				options={[
					{ value: 'g', label: 'g' },
					{ value: 'oz', label: 'oz' }
				]}
				onChange={(v) => void setWeightUnit(v as 'g' | 'oz')}
			/>
		{/snippet}
	</StRow>
	<StRow title="Temperature">
		{#snippet control()}
			<StSegment
				equalWidth
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
				equalWidth
				value={prefs.volumeUnit}
				options={[
					{ value: 'ml', label: 'ml' },
					{ value: 'floz', label: 'fl oz' }
				]}
				onChange={(v) => settings.set('volumeUnit', v as 'ml' | 'floz')}
			/>
		{/snippet}
	</StRow>
	<StRow title="Pressure">
		{#snippet control()}
			<StSegment
				equalWidth
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
