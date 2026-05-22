<script lang="ts">
	/**
	 * `DebugPanel` — a floating bottom-right overlay that mirrors the shared
	 * `UiSnapshot.eventLog`, shown when `prefs.showDebugPanel` is on
	 * (Settings → Advanced).
	 *
	 * The shell's event log is the same source the Scale page renders as its
	 * full-page Activity panel; this corner overlay is the lightweight
	 * cross-route version, available on every screen while debugging. The
	 * close X flips the pref back off — symmetric with the Settings toggle.
	 *
	 * Stays above the page content but below the sidebar (z-index 40 < the
	 * sidebar's 50) so the rail's tap targets always win.
	 */
	import type { CremaApp } from '$lib/state';
	import { getSettingsStore } from '$lib/settings';

	let { app }: { app: CremaApp | null } = $props();

	const settings = getSettingsStore();
	const open = $derived(settings.current.showDebugPanel);
	const snapshot = $derived(app?.state.current);
	const eventLog = $derived(snapshot?.eventLog ?? []);
	/** The most-recent N entries — the panel renders newest-first. */
	const rows = $derived(eventLog.slice(0, 30));

	function close(): void {
		settings.set('showDebugPanel', false);
	}
</script>

{#if open}
	<aside
		class="dbg"
		role="region"
		aria-label="Debug event log"
	>
		<div class="dbg-head">
			<div class="dbg-title">
				<i class="ph ph-bug" aria-hidden="true"></i>
				Debug
			</div>
			<div class="dbg-meta">{eventLog.length}</div>
			<button
				type="button"
				class="dbg-x"
				onclick={close}
				aria-label="Hide debug panel"
				title="Hide (Settings → Advanced → Show debug panel)"
			>
				<i class="ph ph-x" aria-hidden="true"></i>
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

<style>
	.dbg {
		position: fixed;
		right: 16px;
		bottom: 16px;
		z-index: 40;
		width: 320px;
		max-height: 360px;
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
</style>
