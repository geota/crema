<script lang="ts">
	/**
	 * `RailDevices` — the DE1 and scale link buttons at the foot of the rail,
	 * wired to the shared `CremaApp`. A third, the grinder's, sits under the
	 * scale when "Show in Devices and the rail" is on and one of the user's
	 * grinders connects over Bluetooth (`#lib/grinder/devices-entry`).
	 */
	import type { CremaApp } from '#lib/state/index.js';
	import { NO_GRINDER } from '#lib/state/ui-state.svelte.js';
	import {
		grinderDeviceName,
		grinderInDevices,
		linkedGrinderRecords
	} from '#lib/grinder/devices-entry.svelte.js';
	import { getGrinderLinkStore } from '#lib/grinder/connected-link.svelte.js';
	import RailDeviceButton from './RailDeviceButton.svelte';

	let { app }: { app: CremaApp | null } = $props();

	/** Live snapshot from the shared orchestrator, or null before it loads. */
	const ui = $derived(app?.state.current ?? null);
	const showGrinder = $derived(grinderInDevices());
	const grinder = $derived(ui?.grinder ?? NO_GRINDER);
	const grinderName = $derived(
		showGrinder ? grinderDeviceName(grinder, getGrinderLinkStore().links, linkedGrinderRecords()) : null
	);

	/** The grinder's connect: the same device again after it stopped sending data, else the chooser. */
	function connectGrinder(): void {
		if (!app) return;
		void (grinder.problem?.kind === 'no-data' ? app.reconnectGrinder() : app.connectGrinder());
	}

	/**
	 * Connect a device from the button — a click, so Web Bluetooth's gesture
	 * holds. The app toasts and bails when this browser can't connect (no Web
	 * Bluetooth, the iOS Home Screen app, Bluetooth off).
	 */
	function connect(kind: 'de1' | 'scale'): void {
		if (!app) return;
		void (kind === 'de1' ? app.connectDe1() : app.connectScale());
	}
</script>

<RailDeviceButton
	device="de1"
	linkState={ui?.de1State ?? 'idle'}
	deviceName={ui?.de1Diagnostics.deviceName ?? null}
	disabled={!app}
	onConnect={() => connect('de1')}
	onRetry={() => app?.retryReconnect('de1')}
	onDisconnect={() => void app?.disconnectDe1()}
/>
<RailDeviceButton
	device="scale"
	linkState={ui?.scaleState ?? 'idle'}
	deviceName={ui?.scaleName ?? null}
	disabled={!app}
	onConnect={() => connect('scale')}
	onRetry={() => app?.retryReconnect('scale')}
	onDisconnect={() => void app?.disconnectScale()}
/>
{#if showGrinder}
	<RailDeviceButton
		device="grinder"
		linkState={grinder.state}
		deviceName={grinderName}
		disabled={!app}
		onConnect={connectGrinder}
		onRetry={() => app?.retryReconnect('grinder')}
		onDisconnect={() => void app?.disconnectGrinder()}
	/>
{/if}
