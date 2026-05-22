<script lang="ts">
	/**
	 * `PowerButton` — a compact circular power control for the DE1.
	 *
	 * Renders only when the DE1 is connected. Reads `snapshot.machineState`
	 * and swaps between two visuals:
	 *
	 *  - DE1 awake (any state other than `Sleep`) → **sun** icon, neutral
	 *    surface. Click → request Sleep.
	 *  - DE1 asleep → **moon** icon, copper-tinted. Click → request Idle
	 *    (which also wakes from sleep).
	 *
	 * Pattern: the icon shows the *current* state (sun = on, moon =
	 * asleep), and clicking transitions to the opposite. The icon swap
	 * after the click is the affordance — confirms the state change
	 * landed.
	 *
	 * Renders inline wherever it's placed (no fixed positioning). Today the
	 * Brew dashboard puts it as the leftmost item in its foot-meta strip,
	 * next to the Machine label. Moved out of the app shell on 2026-05-22
	 * because it was overlapping the profile-switcher dropdown at the top
	 * of the Brew page.
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

	function toggle(): void {
		if (!app || !connected) return;
		void app.requestMachineState(asleep ? MachineState.Idle : MachineState.Sleep);
	}
</script>

<!--
	Renders at all times — the foot-meta row reads as a hole if the slot
	disappears when the DE1 disconnects. Disabled when not connected;
	matches the Coffee button's disabled-state pattern (opacity dim, no
	hover/active transforms, not-allowed cursor).
-->
<button
	type="button"
	class="power-btn"
	class:is-asleep={asleep}
	disabled={!connected}
	onclick={toggle}
	title={!connected
		? 'No DE1 connected'
		: asleep
			? 'DE1 is asleep — click to wake'
			: 'Click to put the DE1 to sleep'}
	aria-label={asleep ? 'Wake DE1' : 'Sleep DE1'}
>
	<i
		class={asleep ? 'ph-fill ph-moon' : 'ph-fill ph-sun'}
		aria-hidden="true"
	></i>
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
	/* Disabled — no DE1 connected. Same opacity-drop pattern as the
	   Coffee button + service-mode chips. */
	.power-btn:disabled {
		opacity: 0.4;
		cursor: not-allowed;
	}
	.power-btn:disabled:hover {
		background: var(--bg-surface);
		color: rgba(var(--tint-rgb), 0.7);
	}
</style>
