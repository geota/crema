<script lang="ts">
	/**
	 * `PhoneTopBar` — the phone-width app bar (≤ 600 px, Android
	 * `CremaPhoneTopBar`): the Crema mark, the screen's title, and the screen's
	 * actions. A route sets the title and actions with `usePhoneBar`; the
	 * Bluetooth action opens the Devices sheet from any screen.
	 *
	 * The title is presentation: the screen's `<h1>` stays in the page (see
	 * `.phone-bar-title`) or the `PhoneBackBar`, so a screen has exactly one
	 * heading whichever of the two is showing.
	 *
	 * Hidden above 600 px by CSS.
	 */
	import { page } from '$app/state';
	import { resolve } from '$app/paths';
	import BluetoothConnectedIcon from 'phosphor-svelte/lib/BluetoothConnectedIcon';
	import BluetoothIcon from 'phosphor-svelte/lib/BluetoothIcon';
	import GearSixIcon from 'phosphor-svelte/lib/GearSixIcon';
	import type { CremaApp } from '#lib/state/index.js';
	import CremaMark from '#lib/components/CremaMark.svelte';
	import { railStatus } from '#lib/components/rail-status.js';
	import { NAV_ITEMS, tabIsActive } from './nav-items';
	import { DEFAULT_PHONE_BAR_ACTIONS, phoneBarConfig, type PhoneBarAction } from './phone-bar.svelte';
	import PhoneDevicesSheet from './PhoneDevicesSheet.svelte';

	let { app }: { app: CremaApp | null } = $props();

	let devicesOpen = $state(false);

	const pathname = $derived(page.url.pathname);
	const onSettings = $derived(pathname === resolve('/settings'));
	const connected = $derived(railStatus(app?.state.current.de1State ?? 'idle') === 'connected');

	const config = $derived(phoneBarConfig());
	const title = $derived(
		config?.title ?? NAV_ITEMS.find((item) => tabIsActive(item, pathname))?.label ?? 'Crema'
	);

	const devices = $derived<PhoneBarAction>({
		id: 'devices',
		label: connected ? 'Devices, DE1 connected' : 'Devices',
		icon: connected ? BluetoothConnectedIcon : BluetoothIcon,
		accent: connected,
		haspopup: 'dialog',
		onclick: () => (devicesOpen = true)
	});
	const settings = $derived<PhoneBarAction>({
		id: 'settings',
		label: 'Settings',
		icon: GearSixIcon,
		href: resolve('/settings'),
		accent: onSettings
	});
	const actions = $derived(
		(config?.actions ?? DEFAULT_PHONE_BAR_ACTIONS).map((a) =>
			a === 'devices' ? devices : a === 'settings' ? settings : a
		)
	);
</script>

<header class="ptop">
	<a class="ptop-mark" href={resolve('/')} aria-label="Crema — Brew">
		<CremaMark size={26} />
	</a>
	<span class="ptop-title" aria-hidden="true">{title}</span>
	<div class="ptop-actions">
		{#each actions as action (action.id)}
			{@const ActionIcon = action.icon}
			{#if action.href}
				<a
					class={['ptop-action', { 'is-accent': action.accent }]}
					href={action.href}
					aria-label={action.label}
					aria-current={action.id === 'settings' && onSettings ? 'page' : undefined}
				>
					<ActionIcon aria-hidden="true" />
				</a>
			{:else}
				<button
					type="button"
					class={['ptop-action', { 'is-accent': action.accent }]}
					aria-label={action.label}
					aria-haspopup={action.haspopup}
					onclick={action.onclick}
				>
					<ActionIcon aria-hidden="true" />
				</button>
			{/if}
		{/each}
	</div>
</header>
<PhoneDevicesSheet {app} open={devicesOpen} onClose={() => (devicesOpen = false)} />

<style>
	.ptop {
		display: none;
	}
	@media (max-width: 600px) {
		.ptop {
			position: fixed;
			top: 0;
			left: 0;
			right: 0;
			z-index: 50;
			display: flex;
			align-items: center;
			gap: 10px;
			height: var(--ptop-clear);
			padding: env(safe-area-inset-top, 0px) calc(4px + env(safe-area-inset-right, 0px)) 0
				calc(16px + env(safe-area-inset-left, 0px));
			background: var(--bg-page);
		}
	}
	.ptop-mark {
		flex: none;
		display: inline-flex;
		align-items: center;
		height: 44px;
		border-radius: 8px;
	}
	.ptop-title {
		flex: 1 1 auto;
		min-width: 0;
		overflow: hidden;
		font-family: var(--font-serif);
		font-size: 26px;
		font-weight: 400;
		line-height: 30px;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--fg-1);
	}
	.ptop-actions {
		flex: none;
		display: flex;
		align-items: center;
	}
	.ptop-action {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 44px;
		height: 44px;
		border: 0;
		border-radius: 50%;
		background: transparent;
		color: var(--fg-2);
		font-size: 22px;
		cursor: pointer;
	}
	.ptop-action.is-accent {
		color: var(--fg-accent);
	}
	.ptop-mark:focus-visible,
	.ptop-action:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
</style>
