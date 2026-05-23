<script lang="ts">
	/**
	 * `MachineErrorBanner` — the red dashboard header pill that surfaces a
	 * DE1 firmware fault. Reuses the `.mc-head-status` shape from
	 * `ModeHeadStatus` so an error and a service-mode banner occupy the
	 * same slot and read with the same visual language; the `.is-error`
	 * CSS variant in `mode-controls.css` swaps in the danger palette.
	 *
	 * Renders nothing when `text` is `null` — caller wraps with `{#if}`.
	 *
	 * The firmware reports faults via the `Error*` substates of
	 * `MachineStateChanged` (e.g. `ErrorFlowFast`, `ErrorPressureFast`,
	 * `ErrorTempOutOfRange`); the shell-side `MACHINE_ERROR_TEXT` map
	 * turns each variant into the readable string this component takes.
	 * The firmware clears the substate itself when the condition
	 * resolves, so there is no `onDismiss` — the banner disappears on
	 * its own when `text` flips back to `null`.
	 */

	let {
		text
	}: {
		/** The fault text — `null` when the machine is healthy. */
		text: string;
	} = $props();
</script>

<div class="mc-head-status is-error" role="alert">
	<span class="mc-head-status-icon">
		<i class="ph-duotone ph-warning" aria-hidden="true"></i>
	</span>
	<span class="mc-head-status-text">
		<span class="mc-head-status-name">Machine error</span>
		<span class="mc-head-status-meta">{text}</span>
	</span>
</div>
