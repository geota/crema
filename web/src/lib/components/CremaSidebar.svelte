<script lang="ts">
	import Icon from '$lib/icons/Icon.svelte';
	/**
	 * `CremaSidebar` — the fixed 72px left rail, ported from the design's
	 * `CremaSidebar` in `web-dashboard-v2.jsx` and the `.cside` rules in
	 * `quick-controls-v2.css`.
	 *
	 * Five nav items (Brew / Profiles / History / Scale / Settings) render as
	 * SvelteKit `<a href>` links; the active item is derived from the current
	 * route. The DE1 + scale connection-status dots at the bottom are wired to
	 * the shared `CremaApp`'s live state. Number keys `1`–`5` jump between the
	 * five routes.
	 */
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import type { CremaApp } from '$lib/state';
	import CremaMark from './CremaMark.svelte';
	import type { De1State, ScaleState } from '$lib/ble';

	let { app }: { app: CremaApp | null } = $props();

	/**
	 * Nav model — id, icon (Phosphor name), resolved route, label, keyboard digit.
	 * `href` is the resolved pathname (via `resolve(...)`) so the rail honours any
	 * future `paths.base` config and stays correct under SvelteKit 2's typed
	 * route table.
	 */
	const items = [
		{ id: 'brew', icon: 'coffee', label: 'Brew', href: resolve('/'), kbd: '1' },
		{ id: 'profiles', icon: 'list-bullets', label: 'Profiles', href: resolve('/profiles'), kbd: '2' },
		{ id: 'beans', icon: 'coffee-bean', label: 'Beans', href: resolve('/beans'), kbd: '3' },
		{ id: 'history', icon: 'chart-line', label: 'History', href: resolve('/history'), kbd: '4' },
		{ id: 'scale', icon: 'scales', label: 'Scale', href: resolve('/scale'), kbd: '5' },
		{ id: 'settings', icon: 'gear-six', label: 'Settings', href: resolve('/settings'), kbd: '6' }
	];

	/** The active item is the one whose href matches the current path. */
	const activeHref = $derived(page.url.pathname);

	/** Live snapshot from the shared orchestrator, or null before it loads. */
	const ui = $derived(app?.state.current ?? null);

	/** A connection state counts as "connected" once it is up or recovering. */
	const liveStates: readonly (De1State | ScaleState)[] = [
		'connecting',
		'subscribing',
		'ready',
		'reconnecting'
	];

	const machineConnected = $derived(ui !== null && liveStates.includes(ui.de1State));
	const scaleConnected = $derived(ui !== null && liveStates.includes(ui.scaleState));

	/**
	 * Toggle the DE1 connection — the bottom status button. Connecting needs a
	 * Web-Bluetooth user gesture, which a click satisfies.
	 */
	function toggleMachine(): void {
		if (!app) return;
		void (machineConnected ? app.disconnectDe1() : app.connectDe1());
	}

	/** Toggle the scale connection — the other bottom status button. */
	function toggleScale(): void {
		if (!app) return;
		void (scaleConnected ? app.disconnectScale() : app.connectScale());
	}

	/** Keys 1–5 navigate to the matching route — design parity. */
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

<svelte:window onkeydown={onKeydown} />

<nav class="cside" aria-label="Primary">
	<div class="cside-mark">
		<CremaMark size={34} />
	</div>
	<div class="cside-items">
		{#each items as it (it.id)}
			<a
				class="cside-item"
				class:is-active={activeHref === it.href}
				href={it.href}
				aria-current={activeHref === it.href ? 'page' : undefined}
			>
				<Icon cls={'ph-duotone ph-' + it.icon} aria-hidden="true" />
				<span>{it.label}</span>
			</a>
		{/each}
		<div style="flex: 1 1 auto"></div>
	</div>
	<div class="cside-bottom">
		<button
			type="button"
			class="cside-status is-button"
			class:is-connected={machineConnected}
			onclick={toggleMachine}
			disabled={!app}
			title={machineConnected ? 'DE1 connected — click to disconnect' : 'Click to connect DE1'}
		>
			<span class="cside-status-dot" class:off={!machineConnected}></span>
			<span class="cside-status-label">DE1</span>
			<span class="cside-status-cta">
				<Icon cls={'ph ph-' + (machineConnected ? 'check' : 'bluetooth')} aria-hidden="true" />
			</span>
		</button>
		<button
			type="button"
			class="cside-status is-button"
			class:is-connected={scaleConnected}
			onclick={toggleScale}
			disabled={!app}
			title={scaleConnected ? 'Scale connected — click to disconnect' : 'Click to connect scale'}
		>
			<span class="cside-status-dot" class:off={!scaleConnected}></span>
			<span class="cside-status-label">Scale</span>
			<span class="cside-status-cta">
				<Icon cls={'ph ph-' + (scaleConnected ? 'check' : 'bluetooth')} aria-hidden="true" />
			</span>
		</button>
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
	}
	.cside-item {
		text-decoration: none;
	}
</style>
