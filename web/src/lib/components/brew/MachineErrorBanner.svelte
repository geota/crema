<script lang="ts">
	import WarningIcon from 'phosphor-svelte/lib/WarningIcon';
	import XIcon from 'phosphor-svelte/lib/XIcon';
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
		variant = 'error',
		onClick,
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
		 * Visual palette: `'error'` (red, the original firmware-fault
		 * use) or `'warning'` (yellow — used by the maintenance banner
		 * for "needs attention but not a fault" cues like filter due).
		 */
		variant?: 'error' | 'warning';
		/**
		 * Optional click handler — when present, the banner's body
		 * becomes a button that triggers it (e.g. the maintenance
		 * banner routes to `/settings#water`). The dismiss `✕`
		 * propagation-stops so a tap on it doesn't also fire `onClick`.
		 */
		onClick?: () => void;
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

<div
	class="mc-head-status"
	class:is-error={variant === 'error'}
	class:is-warning={variant === 'warning'}
	class:is-clickable={onClick !== undefined}
	role="alert"
>
	<span class="mc-head-status-icon">
		<WarningIcon weight="duotone" aria-hidden="true" />
	</span>
	{#if onClick}
		<button
			type="button"
			class="mc-head-status-text mc-head-status-text-btn"
			onclick={onClick}
		>
			<span class="mc-head-status-name">{title}</span>
			<span class="mc-head-status-meta">{text}</span>
		</button>
	{:else}
		<span class="mc-head-status-text">
			<span class="mc-head-status-name">{title}</span>
			<span class="mc-head-status-meta">{text}</span>
		</span>
	{/if}
	{#if onDismiss}
		<button
			type="button"
			class="mc-head-status-dismiss"
			onclick={(e) => {
				e.stopPropagation();
				onDismiss();
			}}
			aria-label="Dismiss"
		>
			<XIcon aria-hidden="true" />
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
	/* When the banner body is a clickable button (e.g. the maintenance
	   banner routing to /settings#water), strip the native button chrome
	   so it matches the non-clickable variant exactly, but keep a
	   pointer cursor + faint hover tint so the affordance is discoverable. */
	.mc-head-status-text-btn {
		appearance: none;
		background: transparent;
		border: 0;
		padding: 0;
		margin: 0;
		font: inherit;
		color: inherit;
		text-align: left;
		cursor: pointer;
	}
	.mc-head-status-text-btn:hover .mc-head-status-name {
		text-decoration: underline;
	}
</style>
