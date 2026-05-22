<script lang="ts">
	/**
	 * `PowerButton` — a sleek viewport-corner power control for the DE1.
	 *
	 * Renders only when the DE1 is connected. Reads `snapshot.machineState`
	 * and swaps between two visuals:
	 *
	 *  - DE1 awake (any state other than `Sleep`) → moon icon, neutral
	 *    surface. Click → request Sleep.
	 *  - DE1 asleep → sun icon, copper-tinted. Click → request Idle (which
	 *    also wakes from sleep).
	 *
	 * Lives in the app shell (`+layout.svelte`) so it's available on every
	 * route. Fixed positioning at top-right; z-index above the page but
	 * below modals / scrims. Mirrors the sidebar's connection-status pattern
	 * — small, always-on, one-click toggle of a critical machine state.
	 */
	import type { CremaApp } from '$lib/state';
	import { MachineState } from '$lib/core/crema-core';
	import type { De1State } from '$lib/ble';

	let { app }: { app: CremaApp | null } = $props();

	/** Live snapshot, or null before the orchestrator loads. */
	const ui = $derived(app?.state.current ?? null);

	/** "Connected enough to send commands" — matches the sidebar's gate. */
	const liveStates: readonly De1State[] = ['ready'];
	const connected = $derived(ui !== null && liveStates.includes(ui.de1State));

	/** True when the firmware reports `MachineState.Sleep`. */
	const asleep = $derived(ui?.machineState === MachineState.Sleep);

	function toggle(): void {
		if (!app || !connected) return;
		void app.requestMachineState(asleep ? MachineState.Idle : MachineState.Sleep);
	}
</script>

{#if connected}
	<button
		type="button"
		class="power-btn"
		class:is-asleep={asleep}
		onclick={toggle}
		title={asleep ? 'DE1 is asleep — click to wake' : 'Click to put the DE1 to sleep'}
		aria-label={asleep ? 'Wake DE1' : 'Sleep DE1'}
	>
		<i
			class={asleep ? 'ph-fill ph-sun-dim' : 'ph-fill ph-moon'}
			aria-hidden="true"
		></i>
	</button>
{/if}

<style>
	.power-btn {
		position: fixed;
		top: 14px;
		right: 14px;
		width: 40px;
		height: 40px;
		border-radius: 50%;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		border: 1px solid var(--hairline, rgba(255, 255, 255, 0.08));
		background: var(--bg-surface);
		color: rgba(var(--tint-rgb), 0.7);
		font-size: 18px;
		cursor: pointer;
		box-shadow: 0 1px 3px rgba(0, 0, 0, 0.25);
		transition:
			background var(--dur-1, 140ms) var(--ease, cubic-bezier(0.32, 0.72, 0, 1)),
			color var(--dur-1, 140ms) var(--ease, cubic-bezier(0.32, 0.72, 0, 1)),
			transform 80ms;
		/* Above page chrome (modal scrims top out at ~31; sidebar is 50;
		   sit between so the sidebar dropdowns / scrims still win, but the
		   page content can never cover this). */
		z-index: 40;
	}
	.power-btn:hover {
		background: var(--bg-elevated, var(--bg-surface));
		color: var(--fg-1);
	}
	.power-btn:active {
		transform: scale(0.94);
	}
	.power-btn.is-asleep {
		background: rgba(var(--copper-rgb), 0.16);
		color: var(--copper-400);
		border-color: rgba(var(--copper-rgb), 0.35);
	}
	.power-btn.is-asleep:hover {
		background: rgba(var(--copper-rgb), 0.24);
		color: var(--copper-300, var(--copper-400));
	}
</style>
