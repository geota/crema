<script lang="ts">
	/**
	 * `RailDeviceButton` — one of the rail's bottom DE1 / scale status buttons.
	 *
	 * The look and the tap follow the link state (see `rail-status.ts`):
	 * disconnected → connect; connecting → nothing; reconnecting → Retry now;
	 * connected → a small menu anchored to the button with a status line and
	 * Disconnect, so disconnecting is two deliberate taps. The menu takes focus
	 * on open; Esc, a click outside or tabbing away closes it.
	 */
	import Icon from '$lib/icons/Icon.svelte';
	import { tick } from 'svelte';
	import type { BleConnectionState } from '$lib/ble/connection-state';
	import {
		railAction,
		railCtaIcon,
		railDotClass,
		railLabel,
		railStatus,
		type RailDevice
	} from './rail-status';

	interface Props {
		device: RailDevice;
		linkState: BleConnectionState;
		/** The connected device's advertised name, shown in the menu. */
		deviceName?: string | null;
		disabled?: boolean;
		onConnect: () => void;
		onRetry: () => void;
		onDisconnect: () => void;
	}

	let {
		device,
		linkState,
		deviceName = null,
		disabled = false,
		onConnect,
		onRetry,
		onDisconnect
	}: Props = $props();

	const status = $derived(railStatus(linkState));
	const dot = $derived(railDotClass(status));
	const label = $derived(railLabel(device, status));
	const short = $derived(device === 'de1' ? 'DE1' : 'Scale');
	const menuId = $derived(`cside-menu-${device}`);

	let open = $state(false);
	let wrap = $state<HTMLDivElement | null>(null);
	let trigger = $state<HTMLButtonElement | null>(null);
	let firstItem = $state<HTMLButtonElement | null>(null);

	// The menu only makes sense while connected — drop it if the link changes.
	$effect(() => {
		if (status !== 'connected') open = false;
	});

	async function openMenu(): Promise<void> {
		open = true;
		await tick();
		firstItem?.focus();
	}

	function closeMenu(refocus: boolean): void {
		open = false;
		if (refocus) trigger?.focus();
	}

	function onclick(): void {
		switch (railAction(status)) {
			case 'connect':
				onConnect();
				break;
			case 'retry':
				onRetry();
				break;
			case 'menu':
				if (open) closeMenu(false);
				else void openMenu();
				break;
			case 'none':
				break;
		}
	}

	function pickDisconnect(): void {
		closeMenu(true);
		onDisconnect();
	}

	function onMenuKeydown(event: KeyboardEvent): void {
		if (event.key === 'Escape') {
			event.preventDefault();
			event.stopPropagation();
			closeMenu(true);
		}
	}

	function onWindowClick(event: MouseEvent): void {
		if (!open || !wrap) return;
		if (event.target instanceof Node && wrap.contains(event.target)) return;
		closeMenu(false);
	}

	function onFocusOut(event: FocusEvent): void {
		if (!open || !wrap) return;
		const next = event.relatedTarget;
		if (next instanceof Node && wrap.contains(next)) return;
		// Focus left the button + menu (Tab away) — close without stealing it back.
		if (next !== null) closeMenu(false);
	}
</script>

<svelte:window onclick={onWindowClick} />

<div class="cside-status-wrap" bind:this={wrap} onfocusout={onFocusOut}>
	<button
		bind:this={trigger}
		type="button"
		class="cside-status is-button"
		class:is-connected={status === 'connected'}
		data-status={status}
		{onclick}
		{disabled}
		title={label}
		aria-label={label}
		aria-disabled={status === 'connecting' ? 'true' : undefined}
		aria-haspopup={status === 'connected' ? 'menu' : undefined}
		aria-expanded={status === 'connected' ? open : undefined}
		aria-controls={status === 'connected' && open ? menuId : undefined}
	>
		<span class="cside-status-dot" class:off={dot === 'off'} class:is-busy={dot === 'is-busy'}
		></span>
		<span class="cside-status-label">{short}</span>
		<span class="cside-status-cta">
			<Icon cls={'ph ph-' + railCtaIcon(status)} aria-hidden="true" />
		</span>
	</button>
	{#if open}
		<!-- svelte-ignore a11y_interactive_supports_focus -->
		<div
			class="cside-menu"
			id={menuId}
			role="menu"
			aria-label="{short} options"
			onkeydown={onMenuKeydown}
		>
			<div class="cside-menu-status">
				<span class="cside-status-dot" aria-hidden="true"></span>
				<span class="cside-menu-name">{deviceName ?? short}</span>
				<span class="cside-menu-state">Connected</span>
			</div>
			<button
				bind:this={firstItem}
				type="button"
				class="cside-menu-item"
				role="menuitem"
				onclick={pickDisconnect}
			>
				<Icon cls="ph ph-link-break" aria-hidden="true" />
				<span>Disconnect</span>
			</button>
		</div>
	{/if}
</div>

<style>
	.cside-status-wrap {
		position: relative;
		width: 100%;
	}
	.cside-status[aria-disabled='true'] {
		cursor: default;
	}
	.cside-status:focus-visible,
	.cside-menu-item:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
	/* Anchored to the button, opening to the right of the rail. */
	.cside-menu {
		position: absolute;
		left: calc(100% + 8px);
		bottom: 0;
		min-width: 200px;
		background: var(--bg-page);
		border: 1px solid rgba(var(--tint-rgb), 0.14);
		border-radius: var(--radius-md);
		padding: 6px;
		box-shadow: var(--shadow-lg);
		font-family: var(--font-sans);
		color: var(--fg-1);
	}
	.cside-menu-status {
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 6px 8px 8px;
		font-size: 12px;
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.07);
		margin-bottom: 4px;
		white-space: nowrap;
	}
	.cside-menu-name {
		font-weight: 600;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.cside-menu-state {
		color: rgba(var(--tint-rgb), 0.55);
		margin-left: auto;
		padding-left: 8px;
	}
	.cside-menu-item {
		display: flex;
		align-items: center;
		gap: 8px;
		width: 100%;
		text-align: left;
		background: transparent;
		border: 0;
		padding: 8px;
		border-radius: var(--radius-sm);
		cursor: pointer;
		color: var(--fg-1);
		font: inherit;
		font-size: 13px;
	}
	.cside-menu-item:hover,
	.cside-menu-item:focus-visible {
		background: rgba(var(--tint-rgb), 0.06);
	}
	.cside-menu-item :global(svg) {
		font-size: 16px;
		color: var(--copper-400);
	}
</style>
