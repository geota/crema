<script lang="ts">
	/**
	 * `BeanSyncSection` — the Settings → Sharing card for Visualizer bean
	 * library sync.
	 *
	 * OAuth-only: the user clicks "Sign in with Visualizer", lands on
	 * `/oauth/authorize`, returns to `/auth/visualizer/callback` with a
	 * code, and we exchange it for a bearer (PKCE). Once connected the
	 * card surfaces the linked account, lets the user sync, and exposes
	 * "Disconnect" (revoke + clear tokens). HTTP-Basic was removed in
	 * the 2026-05-24 cut.
	 *
	 * Premium gating is surfaced gracefully — free-tier users see
	 * "Connected (read-only)" with an upgrade link, not an angry banner.
	 */
	import { onMount } from 'svelte';
	import {
		clearVisualizerTokens,
		fetchVisualizerAccount,
		getBeanStore,
		getStoredVisualizerTokens,
		isVisualizerConnected,
		isVisualizerOauthConfigured,
		onVisualizerTokenChange,
		readSyncSettings,
		revokeVisualizerToken,
		runSync,
		startVisualizerLogin,
		testConnection,
		type SyncLogEntry,
		type SyncResult,
		type VisualizerAccount
	} from '$lib/bean';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';

	const library = getBeanStore();
	const oauthConfigured = isVisualizerOauthConfigured();

	let settings = $state(readSyncSettings());
	let connected = $state(isVisualizerConnected());
	let account = $state<VisualizerAccount | null>(null);
	let accountError = $state<string | null>(null);

	let connectStatus = $state<
		| { kind: 'idle' }
		| { kind: 'starting' }
		| { kind: 'testing' }
		| { kind: 'ok'; message: string }
		| { kind: 'error'; message: string }
	>({ kind: 'idle' });
	let syncing = $state(false);
	let lastResult = $state<SyncResult | null>(null);

	// Live-update if another tab signs in / disconnects.
	onMount(() => {
		const off = onVisualizerTokenChange((set) => {
			connected = set !== null;
			if (!connected) {
				account = null;
				accountError = null;
			} else {
				void loadAccount();
			}
		});
		if (connected) void loadAccount();
		return off;
	});

	async function loadAccount(): Promise<void> {
		accountError = null;
		try {
			account = await fetchVisualizerAccount();
		} catch (e) {
			accountError = e instanceof Error ? e.message : String(e);
		}
	}

	async function onSignIn(): Promise<void> {
		if (!oauthConfigured) {
			connectStatus = {
				kind: 'error',
				message:
					'OAuth client_id not configured. Set VITE_VISUALIZER_CLIENT_ID in web/.env.local and restart `pnpm dev`.'
			};
			return;
		}
		connectStatus = { kind: 'starting' };
		try {
			await startVisualizerLogin({ returnTo: '/settings' });
			// `startVisualizerLogin` navigates away — control rarely
			// returns to this function.
		} catch (e) {
			console.error('[Crema] startVisualizerLogin failed:', e);
			connectStatus = {
				kind: 'error',
				message: e instanceof Error ? e.message : String(e)
			};
		}
	}

	async function onDisconnect(): Promise<void> {
		const tokens = getStoredVisualizerTokens();
		if (tokens?.accessToken) {
			// Best-effort revoke; we clear locally regardless.
			await revokeVisualizerToken(tokens.accessToken);
		}
		clearVisualizerTokens();
		connected = false;
		account = null;
		accountError = null;
		connectStatus = { kind: 'idle' };
		lastResult = null;
	}

	async function onTest(): Promise<void> {
		connectStatus = { kind: 'testing' };
		const r = await testConnection();
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
	const accountLabel = $derived(account ? account.name : 'Visualizer account');
</script>

<StGroup
	title="Bean library sync"
	sub="Sync your bean library with Visualizer so a second device picks up where you left off."
>
	{#if !oauthConfigured}
		<StRow
			title="OAuth client not configured"
			sub="This build of Crema doesn't have a Visualizer OAuth client ID set. See docs/35-visualizer-oauth-setup.md to register a Doorkeeper application and re-build with VITE_VISUALIZER_CLIENT_ID."
		>
			{#snippet control()}
				<a
					class="bs-btn"
					href="https://github.com/geota/crema/blob/main/docs/35-visualizer-oauth-setup.md"
					target="_blank"
					rel="noopener noreferrer"
				>
					<i class="ph ph-book-open-text" aria-hidden="true"></i>
					Setup docs
				</a>
			{/snippet}
		</StRow>
	{:else if !connected}
		<StRow
			title="Sign in with Visualizer"
			sub="Crema redirects you to visualizer.coffee to grant access. No password ever leaves Visualizer's own login page."
		>
			{#snippet control()}
				<button
					type="button"
					class="bs-btn bs-btn-primary"
					disabled={connectStatus.kind === 'starting'}
					onclick={onSignIn}
				>
					<i class="ph ph-sign-in" aria-hidden="true"></i>
					{connectStatus.kind === 'starting' ? 'Redirecting…' : 'Sign in with Visualizer'}
				</button>
			{/snippet}
		</StRow>
		{#if connectStatus.kind === 'error'}
			<div class="bs-result">
				<div class="bs-result-head">
					<i class="ph ph-warning" style:color="var(--danger)" aria-hidden="true"></i>
					{connectStatus.message}
				</div>
			</div>
		{/if}
	{:else}
		<StRow
			title={accountLabel}
			sub={accountError
				? `Signed in, but couldn't fetch your profile: ${accountError}`
				: connectStatus.kind === 'ok'
					? connectStatus.message
					: connectStatus.kind === 'error'
						? connectStatus.message
						: connectStatus.kind === 'testing'
							? 'Testing…'
							: settings.premium === false
								? 'Connected (free tier — read-only sync).'
								: 'Connected.'}
		>
			{#snippet control()}
				<div class="bs-row-actions">
					<button
						type="button"
						class="bs-btn"
						disabled={connectStatus.kind === 'testing'}
						onclick={onTest}
					>
						<i class="ph ph-plugs-connected" aria-hidden="true"></i>
						Test
					</button>
					<button
						type="button"
						class="bs-btn bs-btn-danger"
						onclick={onDisconnect}
					>
						<i class="ph ph-sign-out" aria-hidden="true"></i>
						Disconnect
					</button>
				</div>
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
					disabled={syncing}
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
	{/if}
</StGroup>

<style>
	.bs-row-actions {
		display: inline-flex;
		gap: 8px;
		align-items: center;
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
		text-decoration: none;
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
	.bs-btn-danger {
		color: var(--danger);
		border-color: rgba(var(--danger-rgb, 200, 80, 80), 0.3);
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
