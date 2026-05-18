<script lang="ts">
	/**
	 * Advanced section — telemetry display toggles, integration stubs, and the
	 * reset actions.
	 *
	 * ## Real vs. stubbed
	 *
	 * **Real** — the telemetry-display toggles and the debug-panel toggle are
	 * persisted app preferences in `lib/settings`. "Show debug / event-log
	 * panel" is a genuine BLE-diagnostics preference the shell can honour (the
	 * shared `UiSnapshot.eventLog` already exists). "Reset preferences" is real
	 * — it calls `SettingsStore.reset()`; profiles and history are untouched.
	 *
	 * **UI-only** — Home Assistant, the webhook and API-token actions need a
	 * network layer the shell lacks; factory reset is destructive and gated
	 * behind a confirm. Each is marked with a `// TODO`.
	 */
	import { getSettingsStore } from '$lib/settings';
	import StSectionHead from '../StSectionHead.svelte';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StToggle from '../StToggle.svelte';
	import StButton from '../StButton.svelte';

	const settings = getSettingsStore();
	const prefs = $derived(settings.current);

	// TODO: Home Assistant MQTT bridge needs a network layer; local UI only.
	let homeAssistant = $state(false);

	function resetPreferences(): void {
		if (
			typeof window === 'undefined' ||
			window.confirm(
				'Restore default brew, display, sound and advanced settings? Profiles and shot history are untouched.'
			)
		) {
			settings.reset();
		}
	}

	function factoryReset(): void {
		// TODO: a true factory reset would clear every Crema localStorage key
		// (profiles, history, settings). Gated behind a confirm; left as a stub
		// so it cannot silently wipe data during the port.
		if (
			typeof window !== 'undefined' &&
			window.confirm(
				'Factory reset erases everything on this device — profiles, history and settings. This cannot be undone. Continue?'
			)
		) {
			// Intentionally not wired during the port. See TODO above.
		}
	}

	function configureWebhook(): void {
		// TODO: a shot-end webhook needs a network layer. Stub.
	}
	function newToken(): void {
		// TODO: API tokens need an account / server. Stub.
	}
</script>

<StSectionHead
	eyebrow="Power user"
	title="Advanced"
	sub="Things most people shouldn't touch."
/>

<StGroup title="Telemetry">
	<StRow title="Show flow curve in chart">
		{#snippet control()}
			<StToggle
				on={prefs.showFlowCurve}
				onChange={(v) => settings.set('showFlowCurve', v)}
				label="Show flow curve in chart"
			/>
		{/snippet}
	</StRow>
	<StRow title="Show estimated puck resistance">
		{#snippet control()}
			<StToggle
				on={prefs.showPuckResistance}
				onChange={(v) => settings.set('showPuckResistance', v)}
				label="Show estimated puck resistance"
			/>
		{/snippet}
	</StRow>
	<StRow title="Smooth pressure curve">
		{#snippet control()}
			<StToggle
				on={prefs.smoothPressure}
				onChange={(v) => settings.set('smoothPressure', v)}
				label="Smooth pressure curve"
			/>
		{/snippet}
	</StRow>
</StGroup>

<StGroup title="Diagnostics">
	<StRow
		title="Show debug / event-log panel"
		sub="Surfaces the raw BLE event log — connect / notify / decode lines — for diagnosing machine and scale issues."
	>
		{#snippet control()}
			<StToggle
				on={prefs.showDebugPanel}
				onChange={(v) => settings.set('showDebugPanel', v)}
				label="Show debug / event-log panel"
			/>
		{/snippet}
	</StRow>
</StGroup>

<StGroup title="Integrations">
	<StRow
		title="Home Assistant"
		sub="Expose shot start/end and temperature as MQTT topics."
	>
		{#snippet control()}
			<StToggle
				on={homeAssistant}
				onChange={(v) => (homeAssistant = v)}
				label="Home Assistant"
			/>
		{/snippet}
	</StRow>
	<StRow title="Webhook on shot end" sub="POST shot JSON to a URL of your choice.">
		{#snippet control()}
			<StButton label="Configure" icon="link" onClick={configureWebhook} />
		{/snippet}
	</StRow>
	<StRow
		title="API access"
		sub="Generate a personal API token for third-party clients."
	>
		{#snippet control()}
			<StButton label="New token" icon="key" onClick={newToken} />
		{/snippet}
	</StRow>
</StGroup>

<StGroup title="Reset">
	<StRow
		title="Reset preferences"
		sub="Restore default brew/display/sound settings. Profiles and shot history are untouched."
	>
		{#snippet control()}
			<StButton
				label="Reset"
				icon="arrow-counter-clockwise"
				onClick={resetPreferences}
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Factory reset"
		sub="Erases everything on this device — profiles, history and settings."
	>
		{#snippet control()}
			<button type="button" class="st-btn st-btn-danger" onclick={factoryReset}>
				<i class="ph ph-warning" aria-hidden="true"></i>Factory reset
			</button>
		{/snippet}
	</StRow>
</StGroup>
