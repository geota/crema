<script lang="ts">
	/**
	 * Sound & feedback section — the cue toggles and the cue volume.
	 *
	 * ## Real vs. stubbed
	 *
	 * **Real persistence** — every control here is a persisted app preference in
	 * `lib/settings`. They drive when Crema chimes; persisting them is the job.
	 */
	import { getSettingsStore } from '$lib/settings';
	import StSectionHead from '../StSectionHead.svelte';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StToggle from '../StToggle.svelte';
	import StValueChip from '../StValueChip.svelte';

	const settings = getSettingsStore();
	const prefs = $derived(settings.current);
</script>

<StSectionHead
	eyebrow="Cues"
	title="Sound & feedback"
	sub="When Crema should chime, click, or tone."
/>

<StGroup>
	<StRow title="Shot start tone" sub="Soft tone when extraction begins.">
		{#snippet control()}
			<StToggle
				on={prefs.shotStartTone}
				onChange={(v) => settings.set('shotStartTone', v)}
				label="Shot start tone"
			/>
		{/snippet}
	</StRow>
	<StRow title="Shot end tone" sub="Different tone when stop-on-weight triggers.">
		{#snippet control()}
			<StToggle
				on={prefs.shotEndTone}
				onChange={(v) => settings.set('shotEndTone', v)}
				label="Shot end tone"
			/>
		{/snippet}
	</StRow>
	<StRow title="Maintenance reminders">
		{#snippet control()}
			<StToggle
				on={prefs.maintenanceReminders}
				onChange={(v) => settings.set('maintenanceReminders', v)}
				label="Maintenance reminders"
			/>
		{/snippet}
	</StRow>
	<StRow title="Volume">
		{#snippet control()}
			<StValueChip
				value={prefs.volumePercent}
				suffix="%"
				step={5}
				min={0}
				max={100}
				onCommit={(v) => settings.set('volumePercent', v)}
			/>
		{/snippet}
	</StRow>
</StGroup>
