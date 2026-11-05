<script lang="ts">
	/**
	 * `RailDevices` — the DE1 and scale link buttons, wired to the shared
	 * `CremaApp`. The rail shows them at its foot, `PhoneTopBar` in the bar.
	 */
	import type { CremaApp } from '#lib/state/index.js';
	import RailDeviceButton from './RailDeviceButton.svelte';

	let { app, touch = false }: { app: CremaApp | null; touch?: boolean } = $props();

	/** Live snapshot from the shared orchestrator, or null before it loads. */
	const ui = $derived(app?.state.current ?? null);

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
	{touch}
	onConnect={() => connect('de1')}
	onRetry={() => app?.retryReconnect('de1')}
	onDisconnect={() => void app?.disconnectDe1()}
/>
<RailDeviceButton
	device="scale"
	linkState={ui?.scaleState ?? 'idle'}
	deviceName={ui?.scaleName ?? null}
	disabled={!app}
	{touch}
	onConnect={() => connect('scale')}
	onRetry={() => app?.retryReconnect('scale')}
	onDisconnect={() => void app?.disconnectScale()}
/>
