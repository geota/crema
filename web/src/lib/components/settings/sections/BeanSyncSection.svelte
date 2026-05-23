<script lang="ts">
	/**
	 * `BeanSyncSection` — the Settings → Sharing card for Visualizer bean
	 * library sync.
	 *
	 * Two-way per the coordinator's direction: enter Visualizer credentials,
	 * test the connection, then tap "Sync now" to pull every remote bag +
	 * roaster and push every local one. Premium gating is surfaced
	 * gracefully — free-tier users see "Connected (read-only)" with an
	 * upgrade link, not an angry banner.
	 *
	 * Auth credentials live in `crema.beans.sync.v1` (separate from app
	 * settings — they're library-scoped). HTTP Basic needs the cleartext
	 * password, so we store it as-is and surface the storage warning in the
	 * help text per docs/28 §settings.
	 */
	import {
		getBeanStore,
		readSyncSettings,
		writeSyncSettings,
		runSync,
		testConnection,
		type SyncLogEntry,
		type SyncResult
	} from '$lib/bean';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';

	const library = getBeanStore();
	let settings = $state(readSyncSettings());

	let connectStatus = $state<
		| { kind: 'idle' }
		| { kind: 'testing' }
		| { kind: 'ok'; message: string }
		| { kind: 'error'; message: string }
	>({ kind: 'idle' });
	let syncing = $state(false);
	let lastResult = $state<SyncResult | null>(null);

	function bind(field: 'username' | 'password', value: string): void {
		settings = { ...settings, [field]: value };
		writeSyncSettings({ [field]: value });
		// Any credentials change invalidates the connect status.
		connectStatus = { kind: 'idle' };
	}

	async function onTest(): Promise<void> {
		connectStatus = { kind: 'testing' };
		const r = await testConnection(settings);
		if (r.ok) {
			connectStatus = {
				kind: 'ok',
				message:
					r.premium === false
						? 'Connected (read-only — free tier).'
						: 'Connected.'
			};
			settings = readSyncSettings();
		} else {
			connectStatus = { kind: 'error', message: r.error };
		}
	}

	async function onSync(): Promise<void> {
		if (syncing) return;
		syncing = true;
		lastResult = await runSync(library);
		settings = readSyncSettings();
		syncing = false;
		if (lastResult.ok && lastResult.premiumLocked) {
			connectStatus = {
				kind: 'ok',
				message: 'Connected (read-only — free tier).'
			};
		} else if (lastResult.ok) {
			connectStatus = { kind: 'ok', message: 'Connected.' };
		} else {
			connectStatus = {
				kind: 'error',
				message: lastResult.error ?? 'Sync failed.'
			};
		}
	}

	function fmtTime(at: number | null): string {
		if (!at) return 'never';
		const elapsed = (Date.now() - at) / 1000;
		if (elapsed < 60) return 'just now';
		if (elapsed < 3600) return `${Math.round(elapsed / 60)} min ago`;
		if (elapsed < 86_400) return `${Math.round(elapsed / 3600)} h ago`;
		return new Date(at).toLocaleString('en-US', {
			month: 'short',
			day: 'numeric',
			hour: 'numeric',
			minute: '2-digit'
		});
	}

	function fmtLogEntry(entry: SyncLogEntry): string {
		const arrow =
			entry.direction === 'push'
				? '↑'
				: entry.direction === 'pull'
					? '↓'
					: entry.direction === 'delete'
						? '✕'
						: '·';
		return `${arrow} ${entry.kind === 'bean' ? 'Bag' : 'Roaster'} "${entry.name}"${entry.error ? ` — ${entry.error}` : ''}`;
	}

	const recentLog = $derived(lastResult?.log.slice(0, 6) ?? []);
</script>

<StGroup
	title="Bean library sync"
	sub="Sync your bean library with Visualizer so a second device picks up where you left off."
>
	<StRow
		title="Visualizer email"
		sub="HTTP Basic auth — same credentials you use on visualizer.coffee."
	>
		{#snippet control()}
			<input
				type="email"
				class="bs-input"
				placeholder="you@example.com"
				value={settings.username}
				oninput={(e) => bind('username', e.currentTarget.value)}
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Password"
		sub="Stored locally on this device. Crema has no server — your password never leaves your browser except to talk to Visualizer."
	>
		{#snippet control()}
			<input
				type="password"
				class="bs-input"
				placeholder="••••••••"
				value={settings.password}
				oninput={(e) => bind('password', e.currentTarget.value)}
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Connection"
		sub={connectStatus.kind === 'ok'
			? connectStatus.message
			: connectStatus.kind === 'error'
				? connectStatus.message
				: connectStatus.kind === 'testing'
					? 'Testing…'
					: settings.premium === false
						? 'Free tier — read-only sync.'
						: 'Not tested yet.'}
	>
		{#snippet control()}
			<button
				type="button"
				class="bs-btn"
				disabled={connectStatus.kind === 'testing'}
				onclick={onTest}
			>
				<i class="ph ph-plugs-connected" aria-hidden="true"></i>
				Test
			</button>
		{/snippet}
	</StRow>
	<StRow
		title="Sync now"
		sub={`${library.beans.length} bag(s), ${library.roasters.length} roaster(s) in your library. Last sync: ${fmtTime(settings.lastSyncAt)}.`}
	>
		{#snippet control()}
			<button
				type="button"
				class="bs-btn bs-btn-primary"
				disabled={syncing || !settings.username || !settings.password}
				onclick={onSync}
			>
				<i
					class={syncing ? 'ph ph-spinner-gap bs-spinner' : 'ph ph-arrows-clockwise'}
					aria-hidden="true"
				></i>
				{syncing ? 'Syncing…' : 'Sync now'}
			</button>
		{/snippet}
	</StRow>
	{#if lastResult}
		<div class="bs-result">
			<div class="bs-result-head">
				{#if lastResult.ok}
					<i class="ph-fill ph-check-circle" style:color="var(--success)" aria-hidden="true"></i>
					Synced ↓{lastResult.pulled} pulled · ↑{lastResult.pushed} pushed{#if lastResult.skipped > 0}
						· {lastResult.skipped} skipped{/if}
				{:else}
					<i class="ph ph-warning" style:color="var(--danger)" aria-hidden="true"></i>
					Sync failed: {lastResult.error}
				{/if}
			</div>
			{#if lastResult.premiumLocked}
				<div class="bs-premium">
					Bag and roaster writes need Visualizer Premium (€5/mo). Reads still
					work — your library will pick up remote changes on every sync.
					<a href="https://visualizer.coffee/upgrade" target="_blank" rel="noopener noreferrer">
						Upgrade →
					</a>
				</div>
			{/if}
			{#if recentLog.length > 0}
				<ul class="bs-log">
					{#each recentLog as entry (entry.at + entry.id)}
						<li>{fmtLogEntry(entry)}</li>
					{/each}
				</ul>
			{/if}
		</div>
	{/if}
</StGroup>

<style>
	.bs-input {
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: var(--radius-sm);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 13px;
		padding: 6px 10px;
		outline: 0;
		width: 240px;
	}
	.bs-input:focus {
		border-color: var(--copper-400);
	}
	.bs-btn {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: var(--radius-pill);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 12px;
		padding: 6px 14px;
		cursor: pointer;
	}
	.bs-btn[disabled] {
		opacity: 0.5;
		cursor: not-allowed;
	}
	.bs-btn-primary {
		background: var(--copper-500);
		color: var(--fg-on-accent);
		border-color: transparent;
		font-weight: 600;
	}
	.bs-btn-primary:hover:not([disabled]) {
		background: var(--copper-600);
	}
	.bs-spinner {
		animation: bs-spin 1.1s linear infinite;
	}
	@keyframes bs-spin {
		from { transform: rotate(0); }
		to { transform: rotate(360deg); }
	}
	.bs-result {
		grid-column: 1 / -1;
		margin: 8px 0 4px;
		padding: 12px 14px;
		background: rgba(var(--tint-rgb), 0.04);
		border-radius: var(--radius-sm);
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.bs-result-head {
		display: inline-flex;
		gap: 8px;
		align-items: center;
		font-family: var(--font-sans);
		font-size: 13px;
		color: var(--fg-1);
	}
	.bs-premium {
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.7);
	}
	.bs-premium a {
		color: var(--copper-400);
	}
	.bs-log {
		list-style: none;
		padding: 0;
		margin: 0;
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.6);
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
</style>
