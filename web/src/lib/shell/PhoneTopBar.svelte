<script lang="ts">
	/**
	 * `PhoneTopBar` — the phone-width app bar (≤ 600 px). A destination gets
	 * Android's `CremaPhoneTopBar`: the Crema mark, the screen's title and its
	 * actions. A pushed screen gets `CremaPhoneBackBar` in its place: ← plus the
	 * screen's name. A route picks one with `usePhoneBar`.
	 *
	 * A destination's title is presentation: its `<h1>` stays in the page (see
	 * `.phone-bar-title`). On a pushed screen the title here is the `<h1>` and
	 * takes focus on mount, so a screen reader announces where you landed.
	 *
	 * Hidden above 600 px by CSS.
	 */
	import { page } from '$app/state';
	import { resolve } from '$app/paths';
	import ArrowLeftIcon from 'phosphor-svelte/lib/ArrowLeftIcon';
	import BluetoothConnectedIcon from 'phosphor-svelte/lib/BluetoothConnectedIcon';
	import BluetoothIcon from 'phosphor-svelte/lib/BluetoothIcon';
	import GearSixIcon from 'phosphor-svelte/lib/GearSixIcon';
	import type { CremaApp } from '#lib/state/index.js';
	import CremaMark from '#lib/components/CremaMark.svelte';
	import { railStatus } from '#lib/components/rail-status.js';
	import { NAV_ITEMS, tabIsActive } from './nav-items';
	import {
		DEFAULT_PHONE_BAR_ACTIONS,
		phoneBarConfig,
		phoneDevicesOpen,
		setPhoneDevicesOpen,
		type PhoneBarAction
	} from './phone-bar.svelte';
	import { focusOnMount } from './phone-nav';
	import PhoneDevicesSheet from './PhoneDevicesSheet.svelte';

	let { app }: { app: CremaApp | null } = $props();

	const pathname = $derived(page.url.pathname);
	const onSettings = $derived(pathname === resolve('/settings'));
	const connected = $derived(railStatus(app?.state.current.de1State ?? 'idle') === 'connected');

	const config = $derived(phoneBarConfig());
	const back = $derived(config?.back);
	const title = $derived(
		config?.title ?? NAV_ITEMS.find((item) => tabIsActive(item, pathname))?.label ?? 'Crema'
	);

	const devices = $derived<PhoneBarAction>({
		id: 'devices',
		label: connected ? 'Devices, DE1 connected' : 'Devices',
		icon: connected ? BluetoothConnectedIcon : BluetoothIcon,
		accent: connected,
		haspopup: 'dialog',
		onclick: () => setPhoneDevicesOpen(true)
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

<header class={['ptop', { 'is-pushed': back }]}>
	{#if back}
		<button type="button" class="ptop-action ptop-back" aria-label={back.label} onclick={back.onBack}>
			<ArrowLeftIcon aria-hidden="true" />
		</button>
		<div class="ptop-pushed">
			{#if back.breadcrumb}<span class="ptop-sub">{back.breadcrumb} ›</span>{/if}
			<h1 class="ptop-name" tabindex="-1" {@attach focusOnMount}>{title}</h1>
			{#if back.subtitle}<span class="ptop-sub">{back.subtitle}</span>{/if}
		</div>
	{:else}
		<a class="ptop-mark" href={resolve('/')} aria-label="Crema — Brew">
			<CremaMark size={26} />
		</a>
		<span class="ptop-title" aria-hidden="true">{title}</span>
	{/if}
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
			{:else if action.text}
				<button
					type="button"
					class={['ptop-word', { 'is-primary': action.primary }]}
					aria-label={action.label === action.text ? undefined : action.label}
					disabled={action.disabled}
					onclick={action.onclick}
				>
					{action.text}
				</button>
			{:else}
				<button
					type="button"
					class={['ptop-action', { 'is-accent': action.accent }]}
					aria-label={action.label}
					aria-haspopup={action.haspopup}
					disabled={action.disabled}
					onclick={action.onclick}
				>
					<ActionIcon aria-hidden="true" />
				</button>
			{/if}
		{/each}
	</div>
</header>
<PhoneDevicesSheet {app} open={phoneDevicesOpen()} onClose={() => setPhoneDevicesOpen(false)} />

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
	.ptop.is-pushed {
		gap: 4px;
		padding-left: calc(4px + env(safe-area-inset-left, 0px));
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
	.ptop-pushed {
		flex: 1 1 auto;
		display: flex;
		flex-direction: column;
		min-width: 0;
		padding-left: 8px;
	}
	.ptop-name {
		margin: 0;
		overflow: hidden;
		font-family: var(--font-sans);
		font-size: 16px;
		font-weight: 500;
		line-height: 24px;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--fg-1);
	}
	/* Focused on open for screen readers; a heading shows no ring. */
	.ptop-name:focus,
	.ptop-name:focus-visible {
		outline: none;
		box-shadow: none;
	}
	.ptop-sub {
		overflow: hidden;
		font-family: var(--font-sans);
		font-size: 12px;
		line-height: 16px;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--fg-3);
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
	.ptop-action:disabled,
	.ptop-word:disabled {
		opacity: 0.4;
		cursor: default;
	}
	.ptop-back {
		flex: none;
		color: var(--fg-1);
		font-size: 24px;
	}
	.ptop-word {
		min-height: 44px;
		padding: 0 12px;
		border: 0;
		border-radius: 999px;
		background: transparent;
		color: var(--fg-accent);
		font-family: var(--font-sans);
		font-size: 14px;
		font-weight: 500;
		cursor: pointer;
	}
	.ptop-word.is-primary {
		position: relative;
		margin: 0 8px 0 4px;
		padding: 0 18px;
		color: var(--fg-on-accent);
		background: transparent;
		z-index: 0;
	}
	.ptop-word.is-primary::before {
		content: '';
		position: absolute;
		inset: 2px 0;
		z-index: -1;
		border-radius: 999px;
		background: var(--copper-500);
	}
	/* History's four actions leave a small phone little for the title. */
	@media (max-width: 360px) {
		.ptop:not(.is-pushed) {
			gap: 6px;
			padding-left: calc(12px + env(safe-area-inset-left, 0px));
		}
		.ptop-title {
			font-size: 23px;
		}
	}
	.ptop-mark:focus-visible,
	.ptop-action:focus-visible,
	.ptop-word:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
</style>
