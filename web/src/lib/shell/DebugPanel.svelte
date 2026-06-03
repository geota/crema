<script lang="ts">
	import BugIcon from 'phosphor-svelte/lib/BugIcon';
	import MinusIcon from 'phosphor-svelte/lib/MinusIcon';
	import XIcon from 'phosphor-svelte/lib/XIcon';
	/**
	 * `DebugPanel` — middle-right floating overlay that mirrors the shared
	 * `UiSnapshot.eventLog`, shown when `prefs.showDebugPanel` is on
	 * (Settings → Advanced).
	 *
	 * Two states: **expanded** (the full event-log panel) and **minimized**
	 * (a small icon chip on the right edge). The chip lets the user re-open
	 * the panel without re-opening Settings. Both states live behind the
	 * Settings toggle — when `showDebugPanel` is false, neither renders.
	 *
	 * Minimize state is session-only (not persisted): the user usually wants
	 * the panel open when debugging, so a fresh page load starts expanded.
	 * The Settings toggle is the master enable / disable.
	 *
	 * Z-index 40 — below the sidebar (50) so its tap-targets always win.
	 */
	import type { CremaApp } from '$lib/state';
	import { getSettingsStore } from '$lib/settings';

	let { app }: { app: CremaApp | null } = $props();

	const settings = getSettingsStore();
	const enabled = $derived(settings.current.showDebugPanel);
	const snapshot = $derived(app?.state.current);
	const eventLog = $derived(snapshot?.eventLog ?? []);
	/** The most-recent N entries — the panel renders newest-first. */
	const rows = $derived(eventLog.slice(0, 30));

	/** Session-level minimize state — resets on page load. */
	let minimized = $state(false);

	function minimize(): void {
		minimized = true;
	}
	function expand(): void {
		minimized = false;
	}
	function disable(): void {
		settings.set('showDebugPanel', false);
	}
</script>

{#if enabled}
	{#if minimized}
		<button
			type="button"
			class="dbg-chip"
			onclick={expand}
			aria-label="Open debug panel"
			title="Open debug panel"
		>
			<BugIcon aria-hidden="true" />
		</button>
	{:else}
		<aside class="dbg" role="region" aria-label="Debug event log">
			<div class="dbg-head">
				<div class="dbg-title">
					<BugIcon aria-hidden="true" />
					Debug
				</div>
				<div class="dbg-meta">{eventLog.length}</div>
				<button
					type="button"
					class="dbg-x"
					onclick={minimize}
					aria-label="Minimize"
					title="Minimize (click the chip on the right edge to re-open)"
				>
					<MinusIcon aria-hidden="true" />
				</button>
				<button
					type="button"
					class="dbg-x"
					onclick={disable}
					aria-label="Disable debug panel"
					title="Disable (Settings → Advanced → Show debug panel re-enables)"
				>
					<XIcon aria-hidden="true" />
				</button>
			</div>
			<div class="dbg-body">
				{#if rows.length === 0}
					<div class="dbg-empty">No events yet.</div>
				{:else}
					{#each rows as line (line.id)}
						<div class="dbg-row">{line.text}</div>
					{/each}
				{/if}
			</div>
		</aside>
	{/if}
{/if}

<style>
	.dbg {
		position: fixed;
		right: 16px;
		top: 50%;
		transform: translateY(-50%);
		z-index: 40;
		width: 320px;
		max-height: 60vh;
		display: flex;
		flex-direction: column;
		background: var(--bg-surface);
		border: 1px solid var(--hairline-strong);
		border-radius: var(--radius-md, 10px);
		box-shadow: var(--shadow-lg);
		overflow: hidden;
		font-family: var(--font-mono);
		font-size: 11px;
		color: var(--fg-1);
	}
	.dbg-head {
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 8px 10px;
		background: rgba(var(--tint-rgb), 0.04);
		border-bottom: 1px solid var(--hairline);
	}
	.dbg-title {
		font-family: var(--font-sans);
		font-size: 11px;
		font-weight: 600;
		letter-spacing: var(--track-allcaps, 0.06em);
		text-transform: uppercase;
		color: var(--copper-400);
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.dbg-meta {
		font-family: var(--font-mono);
		font-size: 10px;
		color: rgba(var(--tint-rgb), 0.5);
		margin-left: auto;
	}
	.dbg-x {
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.6);
		cursor: pointer;
		padding: 2px 4px;
		border-radius: 4px;
		font-size: 12px;
	}
	.dbg-x:hover {
		background: rgba(var(--tint-rgb), 0.08);
		color: var(--fg-1);
	}
	.dbg-body {
		overflow-y: auto;
		padding: 6px 10px 10px;
		flex: 1 1 auto;
	}
	.dbg-row {
		padding: 3px 0;
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.04);
		word-break: break-word;
		font-variant-numeric: tabular-nums;
		color: rgba(var(--tint-rgb), 0.78);
	}
	.dbg-row:last-child {
		border-bottom: 0;
	}
	.dbg-empty {
		color: rgba(var(--tint-rgb), 0.45);
		font-family: var(--font-sans);
		font-size: 11px;
		text-align: center;
		padding: 18px 0;
	}

	/* Minimized state — small chip on the right edge, vertically centered. */
	.dbg-chip {
		position: fixed;
		right: 0;
		top: 50%;
		transform: translateY(-50%);
		z-index: 40;
		width: 32px;
		height: 36px;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		background: var(--bg-surface);
		border: 1px solid var(--hairline-strong);
		border-right: 0;
		border-radius: var(--radius-md, 10px) 0 0 var(--radius-md, 10px);
		color: var(--copper-400);
		font-size: 16px;
		cursor: pointer;
		box-shadow: var(--shadow-md, 0 2px 6px rgba(0, 0, 0, 0.18));
		transition:
			background var(--dur-1, 140ms) var(--ease, ease),
			color var(--dur-1, 140ms) var(--ease, ease);
	}
	.dbg-chip:hover {
		background: rgba(var(--copper-rgb), 0.1);
		color: var(--copper-300, var(--copper-400));
	}
</style>
