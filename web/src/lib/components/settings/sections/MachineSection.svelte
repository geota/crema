<script lang="ts">
	/**
	 * Machine section — the espresso machine's connection, peripherals, and the
	 * hero machine card with the firmware-update block.
	 *
	 * ## Real vs. stubbed
	 *
	 * **Real** — the DE1 connection state reads `lib/state`'s `UiSnapshot`
	 * (`de1State`); Connect / Disconnect call `app.connectDe1()` /
	 * `app.disconnectDe1()`. Auto-connect-on-launch and the telemetry rate are
	 * persisted app preferences in `lib/settings`.
	 *
	 * **UI-only** — the shell exposes no DE1 firmware version, MAC, or group
	 * temperature, so those read "—" until connected and are otherwise
	 * placeholders. The firmware-update block, Rename, Forget-device, and the
	 * peripherals list need DE1 / extra-BLE-device support the shell does not
	 * have; each is marked with a `// TODO`.
	 */
	import type { CremaApp } from '$lib/state';
	import type { De1State } from '$lib/ble';
	import { getSettingsStore } from '$lib/settings';
	import StSectionHead from '../StSectionHead.svelte';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StToggle from '../StToggle.svelte';
	import StSelect from '../StSelect.svelte';
	import StButton from '../StButton.svelte';
	import StStatusDot from '../StStatusDot.svelte';

	let { app, de1State }: { app: CremaApp | null; de1State: De1State } = $props();

	const settings = getSettingsStore();
	const prefs = $derived(settings.current);

	/** A DE1 link counts as up once connecting through ready / reconnecting. */
	const connected = $derived(
		(['connecting', 'subscribing', 'ready', 'reconnecting'] as const).includes(
			de1State as 'connecting'
		)
	);

	/** Human label for the coarse DE1 connection state. */
	const stateLabel = $derived(
		(
			{
				idle: 'Not connected',
				connecting: 'Connecting…',
				subscribing: 'Subscribing…',
				ready: 'Connected',
				reconnecting: 'Reconnecting…',
				disconnected: 'Disconnected',
				failed: 'Connection failed'
			} as const
		)[de1State]
	);

	/** Connect a DE1 — a Web-Bluetooth gesture handler. */
	function connect(): void {
		void app?.connectDe1();
	}
	/** Disconnect the DE1. */
	function disconnect(): void {
		void app?.disconnectDe1();
	}

	function updateFirmware(): void {
		// TODO: the DE1 firmware-update path needs a BLE write channel the shell
		// does not expose. Stubbed until the core gains a firmware-upload command.
	}
	function rename(): void {
		// TODO: a per-device display name needs a small device registry the
		// shell does not yet keep. Stubbed.
	}
	function forget(): void {
		// TODO: forgetting a paired device needs a device registry; the shell
		// only holds the live connection. Stubbed.
	}
</script>

<StSectionHead
	eyebrow="Hardware"
	title="Machine"
	sub="Your espresso machine, scale, and grinder. Crema talks to these over Bluetooth or WiFi."
/>

<!-- Hero machine card -->
<div class="st-machinecard">
	<div class="st-machinecard-img">
		<svg viewBox="0 0 110 130" width="80" height="100" aria-hidden="true">
			<rect
				x="20"
				y="20"
				width="70"
				height="80"
				rx="6"
				fill="#3a2a1d"
				stroke="rgba(244,237,224,0.18)"
			/>
			<rect
				x="28"
				y="28"
				width="54"
				height="34"
				rx="3"
				fill="#0d0907"
				stroke="rgba(244,237,224,0.12)"
			/>
			<circle cx="55" cy="45" r="2" fill="var(--copper-400)" />
			<rect x="42" y="68" width="26" height="6" rx="2" fill="rgba(244,237,224,0.18)" />
			<rect x="48" y="74" width="14" height="20" rx="2" fill="rgba(244,237,224,0.10)" />
			<ellipse cx="55" cy="110" rx="32" ry="4" fill="rgba(0,0,0,0.4)" />
		</svg>
	</div>
	<div class="st-machinecard-info">
		<div class="t-eyebrow" style="color:rgba(244,237,224,0.55)">{stateLabel}</div>
		<div class="st-machinecard-name">
			{connected ? 'DE1 · Crema Bar' : 'No machine connected'}
		</div>
		<div class="st-machinecard-meta">
			<!-- The shell exposes no DE1 firmware / MAC / group temp — "—" until
			     the core surfaces them. -->
			<span>Firmware <strong>—</strong></span>
			<span>BLE —</span>
			<span>Group —</span>
		</div>
		<div class="st-machinecard-actions">
			{#if connected}
				<StButton
					label="Disconnect"
					icon="bluetooth-slash"
					onClick={disconnect}
				/>
			{:else}
				<StButton
					label="Connect"
					icon="bluetooth"
					variant="primary"
					disabled={!app}
					onClick={connect}
				/>
			{/if}
			<!-- TODO: Rename / Forget need a device registry the shell lacks. -->
			<StButton label="Rename" icon="pencil-simple" onClick={rename} />
			<StButton label="Re-pair" icon="arrows-clockwise" disabled={!app} onClick={connect} />
		</div>
	</div>
	<div class="st-machinecard-fw">
		<div class="t-eyebrow" style="color:var(--copper-400)">Firmware</div>
		<div class="st-machinecard-fw-ver">No update info</div>
		<div class="st-machinecard-fw-notes">
			<!-- TODO: firmware update needs a DE1 write channel + an update
			     feed. Faithful UI, but the action is stubbed. -->
			Crema cannot yet read your DE1's firmware version or check for updates —
			that needs a machine write channel the web shell does not have.
		</div>
		<StButton
			label="Check for updates"
			icon="arrow-circle-up"
			variant="primary"
			onClick={updateFirmware}
		/>
	</div>
</div>

<StGroup title="Connection">
	<StRow
		title="Auto-connect on launch"
		sub="Crema reconnects to your last-used DE1 when you open the app."
	>
		{#snippet control()}
			<StToggle
				on={prefs.autoConnectOnLaunch}
				onChange={(v) => settings.set('autoConnectOnLaunch', v)}
				label="Auto-connect on launch"
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Telemetry rate"
		sub="How often the chart samples. Higher = smoother curves, more battery."
	>
		{#snippet control()}
			<StSelect
				value={String(prefs.telemetryRateHz)}
				options={[
					{ value: '10', label: '10 Hz' },
					{ value: '25', label: '25 Hz' },
					{ value: '50', label: '50 Hz (recommended)' },
					{ value: '100', label: '100 Hz' }
				]}
				onChange={(v) => settings.set('telemetryRateHz', Number(v))}
			/>
		{/snippet}
	</StRow>
</StGroup>

<StGroup title="Peripherals" sub="Bluetooth devices Crema can talk to.">
	<!-- TODO: a peripheral registry (grinder / tamper) is not in the shell;
	     the scale's live state lives on the Scale page. Faithful UI only. -->
	<StRow title="Scale" sub="Used for stop-on-weight and auto-tare. Manage on the Scale page.">
		{#snippet control()}
			<StStatusDot ok={false} label="See Scale page" />
		{/snippet}
	</StRow>
	<StRow title="Grinder" sub="Doesn't report dose; Crema logs a manual grind setting.">
		{#snippet control()}
			<StStatusDot ok={false} label="Not paired" />
		{/snippet}
		{#snippet hint()}
			<StButton label="Pair" icon="bluetooth" disabled />
		{/snippet}
	</StRow>
	<StRow title="Weight-aware tamper" sub="Pressure logged with each shot.">
		{#snippet control()}
			<StStatusDot ok={false} label="Not paired" />
		{/snippet}
		{#snippet hint()}
			<StButton label="Pair" icon="bluetooth" disabled />
		{/snippet}
	</StRow>
</StGroup>
