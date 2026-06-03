<script lang="ts">
	import Icon from '$lib/icons/Icon.svelte';
	/**
	 * `PowerButton` — a compact circular power control for the DE1.
	 *
	 * Three visual states, each tinted in its semantic colour so the
	 * connection / sleep state reads at a glance:
	 *
	 *  - **Disconnected** → plug icon, red tint. The button is disabled —
	 *    nothing actionable until a DE1 pairs. Click target stays so the
	 *    foot-meta row's layout doesn't shift on disconnect.
	 *  - **Connected + awake** → sun icon, yellow tint. Click → request Sleep.
	 *  - **Connected + asleep** → moon icon, dark-blue tint. Click → request
	 *    Idle (which also wakes from sleep).
	 *
	 * Pattern: the icon shows the *current* state, and clicking transitions
	 * to the opposite. The icon swap after the click confirms the state
	 * change landed.
	 *
	 * Lives inline at the leftmost slot of the brew dashboard's foot-meta
	 * strip, next to the Machine label.
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
	const asleep = $derived(ui?.machineStateName === MachineState.Sleep);

	/** Which of the three states the button currently renders. */
	const state = $derived<'disconnected' | 'awake' | 'asleep'>(
		!connected ? 'disconnected' : asleep ? 'asleep' : 'awake'
	);

	function toggle(): void {
		if (!app || !connected) return;
		void app.requestMachineState(asleep ? MachineState.Idle : MachineState.Sleep);
	}
</script>

<button
	type="button"
	class="power-btn"
	class:is-disconnected={state === 'disconnected'}
	class:is-awake={state === 'awake'}
	class:is-asleep={state === 'asleep'}
	disabled={!connected}
	onclick={toggle}
	title={state === 'disconnected'
		? 'No DE1 connected'
		: state === 'asleep'
			? 'DE1 is asleep — click to wake'
			: 'Click to put the DE1 to sleep'}
	aria-label={state === 'disconnected'
		? 'No DE1 connected'
		: state === 'asleep'
			? 'Wake DE1'
			: 'Sleep DE1'}
>
	<Icon
		cls={state === 'disconnected'
			? 'ph-fill ph-plugs'
			: state === 'asleep'
				? 'ph-fill ph-moon'
				: 'ph-fill ph-sun'}
		aria-hidden="true"
	 />
</button>

<style>
	.power-btn {
		/* Inline circular control. Sized to sit next to a foot-strip
		   eyebrow label without dragging the row's height up. */
		width: 28px;
		height: 28px;
		border-radius: 50%;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		border: 1px solid var(--hairline, rgba(255, 255, 255, 0.08));
		background: var(--bg-surface);
		color: rgba(var(--tint-rgb), 0.7);
		font-size: 14px;
		cursor: pointer;
		transition:
			background var(--dur-1, 140ms) var(--ease, cubic-bezier(0.32, 0.72, 0, 1)),
			color var(--dur-1, 140ms) var(--ease, cubic-bezier(0.32, 0.72, 0, 1)),
			transform 80ms;
		flex: 0 0 auto;
	}
	.power-btn:active:not(:disabled) {
		transform: scale(0.94);
	}

	/* Disconnected — red plug. */
	.power-btn.is-disconnected {
		background: rgba(var(--danger-rgb), 0.12);
		border-color: rgba(var(--danger-rgb), 0.35);
		color: var(--danger);
	}

	/* Awake — sun with yellow tint. Uses --warning-rgb (which is the
	   amber/yellow semantic token defined in tokens.css), not copper —
	   keeps the copper accent reserved for the Coffee button. */
	.power-btn.is-awake {
		background: rgba(var(--warning-rgb), 0.14);
		border-color: rgba(var(--warning-rgb), 0.35);
		color: var(--warning);
	}
	.power-btn.is-awake:hover {
		background: rgba(var(--warning-rgb), 0.22);
	}

	/* Asleep — moon with dark-blue tint. Uses --info-rgb (blue semantic). */
	.power-btn.is-asleep {
		background: rgba(var(--info-rgb), 0.14);
		border-color: rgba(var(--info-rgb), 0.4);
		color: var(--info);
	}
	.power-btn.is-asleep:hover {
		background: rgba(var(--info-rgb), 0.22);
	}

	/* Disabled — no DE1 connected. Keep the red tint readable but show
	   non-actionable cursor + slight opacity drop. */
	.power-btn:disabled {
		opacity: 0.65;
		cursor: not-allowed;
	}
</style>
