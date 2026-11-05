<script lang="ts">
	/**
	 * `PhoneTopBar` — the slim phone-width app bar (≤ 600 px): the Crema mark,
	 * the DE1 and scale link buttons that sit at the foot of the rail on wider
	 * screens, and the Settings gear (Android's top-bar gear). Page titles stay
	 * in each page's own header, so the bar carries none.
	 *
	 * Hidden above 600 px by CSS.
	 */
	import { page } from '$app/state';
	import { resolve } from '$app/paths';
	import GearSixIcon from 'phosphor-svelte/lib/GearSixIcon';
	import type { CremaApp } from '#lib/state/index.js';
	import CremaMark from '#lib/components/CremaMark.svelte';
	import RailDevices from '#lib/components/RailDevices.svelte';

	let { app }: { app: CremaApp | null } = $props();

	const onSettings = $derived(page.url.pathname === resolve('/settings'));
</script>

<header class="ptop">
	<a class="ptop-mark" href={resolve('/')} aria-label="Crema — Brew">
		<CremaMark size={28} />
	</a>
	<div class="ptop-devices">
		<RailDevices {app} touch />
	</div>
	<a
		class={['ptop-gear', { 'is-active': onSettings }]}
		href={resolve('/settings')}
		aria-label="Settings"
		aria-current={onSettings ? 'page' : undefined}
	>
		<GearSixIcon weight={onSettings ? 'fill' : 'duotone'} aria-hidden="true" />
	</a>
</header>

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
			gap: 4px;
			height: var(--ptop-clear);
			padding: env(safe-area-inset-top, 0px) calc(8px + env(safe-area-inset-right, 0px)) 0
				calc(16px + env(safe-area-inset-left, 0px));
			background: var(--bg-page);
			border-bottom: 1px solid rgba(var(--tint-rgb), 0.06);
		}
	}
	.ptop-mark {
		display: inline-flex;
		border-radius: 50%;
		margin-right: auto;
	}
	.ptop-mark:focus-visible,
	.ptop-gear:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
	/* The menu anchors here, not to its button, so it stays on screen at 320 px. */
	.ptop-devices {
		position: relative;
		display: flex;
		align-items: center;
		gap: 2px;
	}
	/* The rail's device buttons, laid out horizontally: dot + label + the
	   Bluetooth "tap to connect" glyph, inline, in a 44 px-tall pill. */
	.ptop-devices :global(.cside-status-wrap) {
		position: static;
		width: auto;
	}
	.ptop-devices :global(.cside-status.is-button) {
		flex-direction: row;
		gap: 6px;
		height: 44px;
		padding: 0 10px;
		border-radius: var(--radius-pill);
	}
	.ptop-devices :global(.cside-status-label) {
		font-size: 10px;
	}
	.ptop-devices :global(.cside-status-cta) {
		position: static;
	}
	.ptop-devices :global(.cside-status.is-connected .cside-status-cta) {
		display: none;
	}
	/* The connected menu drops below the bar instead of opening rightwards. */
	.ptop-devices :global(.cside-menu) {
		left: auto;
		right: 0;
		bottom: auto;
		top: calc(100% + 6px);
	}
	.ptop-gear {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 44px;
		height: 44px;
		border-radius: 50%;
		color: var(--fg-3);
		font-size: 22px;
	}
	.ptop-gear.is-active {
		color: var(--fg-accent);
	}
</style>
