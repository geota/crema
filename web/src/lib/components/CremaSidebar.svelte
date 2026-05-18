<script lang="ts">
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
	import type { CremaApp } from '$lib/state';
	import type { De1State, ScaleState } from '$lib/ble';

	let { app }: { app: CremaApp | null } = $props();

	/** Nav model — id, icon (Phosphor name), label, route, keyboard digit. */
	const items = [
		{ id: 'brew', icon: 'coffee', label: 'Brew', href: '/', kbd: '1' },
		{ id: 'profiles', icon: 'list-bullets', label: 'Profiles', href: '/profiles', kbd: '2' },
		{ id: 'history', icon: 'chart-line', label: 'History', href: '/history', kbd: '3' },
		{ id: 'scale', icon: 'scales', label: 'Scale', href: '/scale', kbd: '4' },
		{ id: 'settings', icon: 'gear-six', label: 'Settings', href: '/settings', kbd: '5' }
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
		<img class="cside-mark-glyph" src="/bean-mark.svg" alt="Crema" width="34" height="34" />
	</div>
	<div class="cside-items">
		{#each items as it (it.id)}
			<a
				class="cside-item"
				class:is-active={activeHref === it.href}
				href={it.href}
				aria-current={activeHref === it.href ? 'page' : undefined}
			>
				<i class={'ph-duotone ph-' + it.icon} aria-hidden="true"></i>
				<span>{it.label}</span>
				<span class="kbd">{it.kbd}</span>
			</a>
		{/each}
		<div style="flex: 1 1 auto"></div>
	</div>
	<div class="cside-bottom">
		<div class="cside-status">
			<span
				class="cside-status-dot"
				class:off={!machineConnected}
				title={machineConnected ? 'DE1 connected' : 'DE1 disconnected'}
			></span>
			<span>DE1</span>
		</div>
		<div class="cside-status">
			<span
				class="cside-status-dot"
				class:off={!scaleConnected}
				title={scaleConnected ? 'Scale connected' : 'Scale disconnected'}
			></span>
			<span>Scale</span>
		</div>
	</div>
</nav>

<style>
	/* Ported verbatim from quick-controls-v2.css `.cside*`, with the prototype's
	   absolute positioning swapped for `fixed` (it is now a true app-shell
	   rail, not an artboard child). */
	.cside {
		position: fixed;
		left: 0;
		top: 0;
		bottom: 0;
		width: 72px;
		background: var(--espresso-950);
		border-right: 1px solid rgba(244, 237, 224, 0.04);
		display: flex;
		flex-direction: column;
		z-index: 8;
		padding: 16px 0 16px;
	}
	.cside-mark {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 72px;
		height: 56px;
		margin-bottom: 4px;
	}
	.cside-mark-glyph {
		width: 34px;
		height: 34px;
		border-radius: 50%;
		box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.2);
		display: block;
	}
	.cside-items {
		display: flex;
		flex-direction: column;
		gap: 2px;
		flex: 1 1 auto;
		padding-top: 8px;
	}
	.cside-item {
		position: relative;
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 4px;
		background: transparent;
		border: 0;
		padding: 12px 0 10px;
		cursor: pointer;
		color: rgba(244, 237, 224, 0.5);
		font-family: var(--font-sans);
		font-size: 10px;
		letter-spacing: 0.02em;
		text-decoration: none;
		transition: color var(--dur-1) var(--ease);
	}
	.cside-item i {
		font-size: 22px;
	}
	.cside-item:hover {
		color: var(--ink-50);
	}
	.cside-item.is-active {
		color: var(--ink-50);
	}
	.cside-item.is-active i {
		color: var(--copper-400);
	}
	.cside-item.is-active::before {
		content: '';
		position: absolute;
		left: 0;
		top: 14px;
		bottom: 14px;
		width: 2px;
		background: var(--copper-500);
		border-radius: 0 2px 2px 0;
	}
	.cside-item .kbd {
		position: absolute;
		top: 8px;
		right: 14px;
		font-family: var(--font-mono);
		font-size: 9px;
		color: rgba(244, 237, 224, 0.3);
	}
	.cside-bottom {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 10px;
		padding: 0 8px;
	}
	.cside-status {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 4px;
		font-family: var(--font-sans);
		font-size: 9px;
		color: rgba(244, 237, 224, 0.45);
	}
	.cside-status-dot {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		background: var(--success, #6b8c5f);
		box-shadow: 0 0 0 2px rgba(107, 140, 95, 0.18);
	}
	.cside-status-dot.off {
		background: rgba(244, 237, 224, 0.2);
		box-shadow: none;
	}
</style>
