<script lang="ts">
	/**
	 * `FirmwareUpdateHost` — mounts the firmware-update flow app-wide (in the
	 * root layout), so leaving Settings → Machine never hides a running flash
	 * or its power-cycle step (audit L4). While the flow is hidden but the
	 * flash still holds the DE1 (a hidden power-cycle wait, a restored lock
	 * after a reload, an interrupted erase), a banner on every screen says DE1
	 * control is paused and opens the flow with its Exit (audit r2 M-B) —
	 * whether the DE1 is off, disconnected or connected.
	 */
	import Icon from '$lib/icons/Icon.svelte';
	import type { CremaApp } from '$lib/state';
	import { getMachineReadout } from '$lib/state';
	import { heldBannerText } from '$lib/firmware/flash-copy';
	import FirmwareUpdateFlow from './FirmwareUpdateFlow.svelte';

	let { app }: { app: CremaApp | null } = $props();
	const machine = getMachineReadout();
	const flow = $derived(app?.firmware ?? null);
</script>

{#if flow && flow.step !== 'closed'}
	<FirmwareUpdateFlow {flow} installedBuild={machine.firmwareBuild} />
{:else if flow && flow.holdsLink}
	<button type="button" class="fu-held" aria-live="polite" onclick={() => void flow.open()}>
		<Icon cls="ph ph-arrows-clockwise" aria-hidden="true" />
		<span>{heldBannerText(flow.status)}</span>
		<span class="fu-held-view">View</span>
	</button>
{/if}

<style>
	.fu-held {
		position: fixed;
		top: max(12px, env(safe-area-inset-top));
		left: 50%;
		transform: translateX(-50%);
		z-index: 900;
		display: flex;
		align-items: center;
		gap: 10px;
		max-width: min(640px, calc(100vw - 32px));
		min-height: 44px;
		padding: 10px 18px;
		border-radius: 999px;
		border: 1px solid rgba(var(--tint-rgb), 0.35);
		background: var(--bg-surface);
		color: var(--fg-1);
		font: inherit;
		font-size: 14px;
		text-align: left;
		cursor: pointer;
		box-shadow: 0 6px 24px rgba(0, 0, 0, 0.35);
	}
	.fu-held:focus-visible {
		outline: 2px solid var(--copper-400);
		outline-offset: 2px;
	}
	.fu-held-view {
		color: var(--copper-400);
		font-weight: 600;
		white-space: nowrap;
	}
</style>
