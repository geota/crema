<script lang="ts">
	import type { CremaApp } from '$lib/state';
	import type { ScaleState } from '$lib/ble/scale';
	import Field from './Field.svelte';
	import ConfigStepper from './ConfigStepper.svelte';
	import ConfigToggle from './ConfigToggle.svelte';
	import ModeSelector from './ModeSelector.svelte';
	import AutoStopSelector from './AutoStopSelector.svelte';

	/**
	 * The scale section: a read-only identity / live-reading block, the
	 * connect / disconnect / tare buttons, and the capability-gated config
	 * controls. The web mirror of the Android shell's `ScaleSection`.
	 *
	 * Config controls are **capability-gated, never device-gated**: each
	 * renders only when its capability is present in the core's
	 * `ScaleCapabilities`. Tare and every config control enable only when the
	 * scale is READY — exactly the Android shell's `scaleReady` rule.
	 */
	let { app }: { app: CremaApp } = $props();

	const ui = $derived(app.state.current);

	/**
	 * True while a scale is connected, in a connecting/handshake phase, or
	 * auto-reconnecting. `reconnecting` counts as connected so Disconnect stays
	 * enabled (it cancels the backoff loop) and Connect stays disabled.
	 */
	const scaleConnected = $derived(
		(['connecting', 'subscribing', 'ready', 'reconnecting'] as ScaleState[]).includes(
			ui.scaleState
		)
	);
	/**
	 * Tare + config controls are live only when the scale is fully READY —
	 * never while `reconnecting`, since GATT writes would just fail mid-outage.
	 */
	const scaleReady = $derived(ui.scaleState === 'ready');

	const caps = $derived(ui.scaleCapabilities);

	/** Format a nullable weight/flow/timer reading the way Android does. */
	const weight = $derived(ui.scaleWeightG !== null ? `${ui.scaleWeightG.toFixed(1)} g` : null);
	const flow = $derived(
		ui.scaleFlowGPerS !== null ? `${ui.scaleFlowGPerS.toFixed(1)} g/s` : null
	);
	const timer = $derived(
		ui.scaleTimerMs !== null ? `${(ui.scaleTimerMs / 1000).toFixed(1)} s` : null
	);
	const battery = $derived(
		ui.scaleBatteryPercent !== null ? `${ui.scaleBatteryPercent} %` : null
	);
</script>

<div class="card bg-base-100 shadow-md">
	<div class="card-body gap-3">
		<!-- Titled with the connected scale's advertised name once known. -->
		<h2 class="card-title text-base">{ui.scaleName ?? 'Scale'}</h2>

		<div class="grid grid-cols-2 gap-3 sm:grid-cols-3">
			<Field label="Weight" value={weight} />
			<Field label="Flow" value={flow} />
			<Field label="Timer" value={timer} />
			<Field label="Battery" value={battery} />
			<Field label="Firmware" value={ui.scaleFirmware} />
			<Field label="Serial" value={ui.scaleSerial} />
		</div>

		<div class="flex flex-col gap-2">
			<button
				type="button"
				class="btn btn-primary btn-sm"
				disabled={scaleConnected}
				onclick={() => app.connectScale()}
			>
				Connect scale
			</button>
			<button
				type="button"
				class="btn btn-outline btn-sm"
				disabled={!scaleConnected}
				onclick={() => app.disconnectScale()}
			>
				Disconnect scale
			</button>
			<button
				type="button"
				class="btn btn-outline btn-sm"
				disabled={!scaleReady}
				onclick={() => app.tareScale()}
			>
				Tare
			</button>
		</div>

		<!-- Capability-gated config controls: each renders only when the core
		     reports the matching capability for the connected scale. -->
		{#if caps?.volume}
			<ConfigStepper
				label="Beep volume"
				value={ui.scaleVolume}
				range={caps.volume}
				enabled={scaleReady}
				onSetValue={(n) => app.setScaleVolume(n)}
			/>
		{/if}

		{#if caps?.standby_minutes}
			<ConfigStepper
				label="Standby (min)"
				value={ui.scaleStandbyMinutes}
				range={caps.standby_minutes}
				enabled={scaleReady}
				onSetValue={(n) => app.setScaleStandbyMinutes(n)}
			/>
		{/if}

		{#if caps?.flow_smoothing}
			<ConfigToggle
				label="Flow smoothing"
				checked={ui.scaleFlowSmoothing}
				enabled={scaleReady}
				onCheckedChange={(v) => app.setScaleFlowSmoothing(v)}
			/>
		{/if}

		{#if caps?.anti_mistouch}
			<ConfigToggle
				label="Anti-mistouch"
				checked={ui.scaleAntiMistouch}
				enabled={scaleReady}
				onCheckedChange={(v) => app.setScaleAntiMistouch(v)}
			/>
		{/if}

		{#if caps && caps.modes.length > 0}
			<ModeSelector
				modes={caps.modes}
				selectedMode={ui.scaleActiveMode}
				enabled={scaleReady}
				onSetMode={(id) => app.setScaleMode(id)}
			/>
		{/if}

		{#if caps?.auto_stop}
			<AutoStopSelector
				selectedMode={ui.scaleAutoStop}
				enabled={scaleReady}
				onSetMode={(id) => app.setScaleAutoStop(id)}
			/>
		{/if}
	</div>
</div>
