<script lang="ts">
	/**
	 * `FirmwareUpdateHost` — mounts the firmware-update flow app-wide (in the
	 * root layout), so leaving Settings → Machine never hides a running flash
	 * or its power-cycle step (audit L4). While the flash holds the DE1 (a
	 * transfer, an erase that may still run, the power-cycle wait, a lock
	 * restored after a reload) the flow can't be closed: it blocks the app
	 * until the DE1 restarts or the user Exits.
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
