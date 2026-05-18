<script lang="ts">
	import type { UiSnapshot } from '$lib/state';
	import Field from './Field.svelte';

	/**
	 * The DE1 readout: BLE-connection state, status line, and the latest
	 * decoded machine state / shot phase / telemetry. The web mirror of the
	 * Android shell's `ReadoutCard`.
	 */
	let { ui }: { ui: UiSnapshot } = $props();
</script>

<div class="card bg-base-100 shadow-md">
	<div class="card-body gap-2">
		<div class="grid grid-cols-2 gap-3">
			<Field label="BLE" value={ui.de1State} />
			<Field label="Status" value={ui.status} />
		</div>
		<div class="divider my-0"></div>
		<Field label="Machine state" value={ui.machineState} />
		<Field label="Shot phase" value={ui.shotPhase} />
		<Field label="Telemetry" value={ui.telemetry} />
	</div>
</div>
