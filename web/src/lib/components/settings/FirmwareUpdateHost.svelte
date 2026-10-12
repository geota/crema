<script lang="ts">
	/**
	 * `FirmwareUpdateHost` — mounts the firmware-update flow app-wide (in the
	 * root layout), so leaving Settings → Machine never hides a running flash
	 * or its power-cycle step (audit L4). Renders nothing while closed.
	 */
	import type { CremaApp } from '$lib/state';
	import { getMachineReadout } from '$lib/state';
	import FirmwareUpdateFlow from './FirmwareUpdateFlow.svelte';

	let { app }: { app: CremaApp | null } = $props();
	const machine = getMachineReadout();
	const flow = $derived(app?.firmware ?? null);
</script>

{#if flow && flow.step !== 'closed'}
	<FirmwareUpdateFlow {flow} installedBuild={machine.firmwareBuild} />
{/if}
