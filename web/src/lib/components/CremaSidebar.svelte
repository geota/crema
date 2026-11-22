<script lang="ts">
	import Icon from '#lib/icons/Icon.svelte';
	/**
	 * `CremaSidebar` — the fixed 72px left rail, ported from the design's
	 * `CremaSidebar` in `web-dashboard-v2.jsx` and the `.cside` rules in
	 * `quick-controls-v2.css`.
	 *
	 * The nav items (`NAV_ITEMS`, shared with `PhoneTabBar`) render as
	 * SvelteKit `<a href>` links; the active item is derived from the current
	 * route. The DE1 + scale status buttons at the bottom
	 * (`RailDeviceButton`) follow the shared `CremaApp`'s live link state:
	 * connect when disconnected, Retry now while reconnecting, a small menu
	 * with Disconnect when connected. Number keys jump between the items, top
	 * to bottom.
	 *
	 * Devices shows as two items, Scale and Grinder, when the window is tall
	 * enough (`railHasRoom`), else as one item opening the tab used last.
	 */
	import { page } from '$app/state';
	import { afterNavigate, goto } from '$app/navigation';
	import type { CremaApp } from '#lib/state/index.js';
	import CremaMark from './CremaMark.svelte';
	import RailDevices from './RailDevices.svelte';
	import { railItemActive, railItems } from '#lib/shell/nav-items.js';
	import { lastDevicesTab, noteDevicesVisit, railHasRoom } from '#lib/shell/rail.svelte.js';

	let { app }: { app: CremaApp | null } = $props();

	const items = $derived(railItems(railHasRoom.current, lastDevicesTab()));

	afterNavigate(({ to }) => {
		if (to) noteDevicesVisit(to.url);
	});

	/** Digit keys navigate to the matching item — design parity. */
	function onKeydown(event: KeyboardEvent) {
		// Ignore digits typed into inputs so forms keep working.
		const target = event.target as HTMLElement | null;
		if (
			target &&
			(target.tagName === 'INPUT' ||
				target.tagName === 'TEXTAREA' ||
				target.isContentEditable)
		) {
			return;
		}
		if (event.metaKey || event.ctrlKey || event.altKey) return;
		const item = items.find((it) => it.kbd === event.key);
		if (item) {
			event.preventDefault();
			void goto(item.href);
		}
	}
</script>

<svelte:window onkeydown={onKeydown}></svelte:window>

<nav class="cside" aria-label="Primary">
	<div class="cside-mark">
		<CremaMark size={34} />
	</div>
	<div class="cside-items">
		{#each items as it (it.id)}
			{@const active = railItemActive(it, page.url)}
			<a
				class={['cside-item', { 'is-active': active }]}
				href={it.href}
				aria-current={active ? 'page' : undefined}
			>
				<Icon cls={'ph-duotone ph-' + it.icon} aria-hidden="true" />
				<span>{it.label}</span>
			</a>
		{/each}
		<div style="flex: 1 1 auto"></div>
	</div>
	<div class="cside-bottom">
		<RailDevices {app} />
	</div>
</nav>

<style>
	/* The `.cside*` visuals live in the global `quick-controls-v2.css` — the
	   source of truth. Only two component-level overrides are needed:
	   1. the rail is `fixed` (a true app-shell rail, not the prototype's
	      absolutely-positioned artboard child);
	   2. the nav items are `<a>` links here, so the anchor underline is killed
	      (the global rule styles `<button class="cside-item">`). */
	.cside {
		position: fixed;
		/* The primary nav must always be the topmost UI element. Without an
		   explicit z-index the rail loses to viewport-fixed overlays — most
		   notably the ProfileCard overflow-menu scrim (`.pp-menu-scrim`,
		   z-index 20) — which silently swallows clicks on the rail and is the
		   "half my clicks do nothing" bug. The scrims top out at 31, so 50 is
		   comfortably above with room for future flyouts. */
		z-index: 50;
		/* `viewport-fit=cover`: a landscape phone's notch and home indicator
		   sit over the rail's edges. Both are 0 on non-notched displays. */
		padding-left: env(safe-area-inset-left, 0px);
		padding-bottom: calc(16px + env(safe-area-inset-bottom, 0px));
	}
	.cside-item {
		text-decoration: none;
	}
	/* Phone width: `PhoneTopBar` + `PhoneTabBar` take over. */
	@media (max-width: 600px) {
		.cside {
			display: none;
		}
	}
</style>
