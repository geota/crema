<script lang="ts">
	/**
	 * Equipment section — properties of the user's gear (as opposed to a
	 * specific bag of beans). Today: a single "Grinder model" free-text
	 * field used as the equipment-level default on shot uploads to
	 * Visualizer (`grinder_model` on the `ShotUpdateRequest` PATCH).
	 *
	 * ## Why this isn't in Machine
	 *
	 * The "Machine" section owns the DE1's connection, MMR identity and
	 * the connection diagnostics — actual writes to the device. Grinder
	 * model is host-side metadata Crema never sends to the DE1 (and the
	 * grinder is currently not paired at all — there's no BLE grinder
	 * support in the shell yet), so it lives in its own equipment-scoped
	 * section.
	 *
	 * ## Why free text
	 *
	 * There's no canonical grinder catalogue; users own everything from
	 * a Niche Zero to a Eureka Mignon Specialita to a hand grinder.
	 * Validation just trims whitespace.
	 *
	 * ## Cascade
	 *
	 * On shot completion, `state/app.svelte.ts` snapshots
	 * `settings.prefs.grinderModel.trim() || null` onto the shot's own
	 * `grinderModel` so a later edit here cannot rewrite history. The
	 * shot-detail panel exposes a per-shot override; empty there means
	 * "fall back to the settings default". The upload PATCH picks
	 * `shot.grinderModel ?? settings.prefs.grinderModel?.trim() ||
	 * undefined`, so an empty default skips the wire field entirely.
	 */
	import { getSettingsStore } from '$lib/settings';
	import StSectionHead from '../StSectionHead.svelte';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';

	const settings = getSettingsStore();
	const prefs = $derived(settings.current);
</script>

<StSectionHead
	eyebrow="Hardware"
	title="Equipment"
	sub="Properties of your gear (as opposed to a specific bag of beans). Used when uploading shots to Visualizer."
/>

<StGroup
	title="Grinder"
	sub="Crema doesn't talk to grinders over Bluetooth yet — these are host-side notes that ride on Visualizer uploads."
>
	<StRow
		title="Grinder model"
		sub="Used only when uploading shots to Visualizer. Free text — e.g. 'Niche Zero' or 'Eureka Mignon Specialita'. Override per shot in shot details."
	>
		{#snippet control()}
			<input
				type="text"
				class="eq-grinder"
				placeholder="e.g. Niche Zero"
				value={prefs.grinderModel}
				oninput={(e) =>
					settings.set(
						'grinderModel',
						(e.currentTarget as HTMLInputElement).value
					)}
			/>
		{/snippet}
	</StRow>
</StGroup>

<style>
	/* Plain text input, sized for a grinder-model string. Mirrors the
	   Advanced section's webhook-URL field metrics so the two single-
	   line text fields in Settings look consistent. */
	.eq-grinder {
		width: 280px;
		max-width: 100%;
		padding: 6px 10px;
		font-family: var(--font-sans);
		font-size: 12px;
		color: var(--fg-1);
		background: rgba(var(--tint-rgb), 0.06);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: 6px;
		outline: none;
	}
	.eq-grinder:focus {
		border-color: rgba(var(--tint-rgb), 0.4);
	}
</style>
