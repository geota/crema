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
		text,
		title = 'Machine error',
		onDismiss
	}: {
		/** The fault text — `null` when the machine is healthy. */
		text: string;
		/**
		 * The banner's bolded header — defaults to "Machine error" for
		 * the original firmware-fault use; overridden ("Can't start
		 * shot", …) for shot-start blockers that re-use this banner's
		 * shape.
		 */
		title?: string;
		/**
		 * Optional dismiss callback — when present, renders a small `✕`
		 * button on the right of the banner. Firmware faults clear
		 * themselves when the substate resolves, so the original use
		 * site omits this; transient banners (e.g. the no-profile
		 * blocker) pass a setter that clears the parent's
		 * `shotStartError` state.
		 */
		onDismiss?: () => void;
	} = $props();
</script>

<div class="mc-head-status is-error" role="alert">
	<span class="mc-head-status-icon">
		<i class="ph-duotone ph-warning" aria-hidden="true"></i>
	</span>
	<span class="mc-head-status-text">
		<span class="mc-head-status-name">{title}</span>
		<span class="mc-head-status-meta">{text}</span>
	</span>
	{#if onDismiss}
		<button
			type="button"
			class="mc-head-status-dismiss"
			onclick={onDismiss}
			aria-label="Dismiss"
		>
			<i class="ph ph-x" aria-hidden="true"></i>
		</button>
	{/if}
</div>

<style>
	.mc-head-status-dismiss {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 22px;
		height: 22px;
		margin-left: 6px;
		border: 0;
		background: transparent;
		color: inherit;
		opacity: 0.7;
		cursor: pointer;
		border-radius: 999px;
		transition: opacity 120ms ease, background 120ms ease;
	}
	.mc-head-status-dismiss:hover {
		opacity: 1;
		background: rgba(var(--danger-rgb), 0.18);
	}
</style>
