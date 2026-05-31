<script lang="ts">
	/**
	 * Sharing section — the Visualizer integration.
	 *
	 * The big visual Visualizer card lives here and owns the entire
	 * connection life-cycle: sign in / out, fetching the user's account
	 * identity, the "Test" health-check. Once connected,
	 * `BeanSyncSection` renders below with the actual sync controls
	 * (Sync now + last-result log).
	 */
	import { onMount } from 'svelte';
	import { Effect } from 'effect';
	import { getHistoryStore } from '$lib/history';
	import { downloadBlob } from '$lib/utils/download';
	import {
		clearVisualizerPremiumCache,
		isVisualizerOauthConfigured,
		revokeVisualizerToken,
		startVisualizerLogin,
		type VisualizerAccount
	} from '$lib/bean';
	import { onSyncConfigChange, readSyncConfig } from '$lib/visualizer';
	import { getCremaAppContext } from '$lib/shell/app-context';
	import { runtimePromise } from '$lib/effect/bridge';
	import { TokenVault } from '$lib/services/token-vault';
	import { BeanSync } from '$lib/services/bean-sync';
	import StSectionHead from '../StSectionHead.svelte';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StButton from '../StButton.svelte';
	import BeanSyncSection from './BeanSyncSection.svelte';

	const history = getHistoryStore();
	const appCtx = getCremaAppContext();
	const oauthConfigured = isVisualizerOauthConfigured();

	let connected = $state(false);
	let account = $state<VisualizerAccount | null>(null);
	let accountError = $state<string | null>(null);
	let signingIn = $state(false);
	let testStatus = $state<
		| { kind: 'idle' }
		| { kind: 'testing' }
		| { kind: 'ok'; message: string }
		| { kind: 'error'; message: string }
	>({ kind: 'idle' });
	/** Cached premium-tier flag, kept in sync with the persisted config so
	   the eyebrow line picks up Test results without a reload. */
	let premium = $state<boolean | null>(readSyncConfig().premium);

	/** Connection gate (Option 3): `TokenVault.getTokens !== null`, read once at
	 *  mount. Sign-in arrives via a full-page OAuth redirect → this remounts and
	 *  re-reads; sign-out is handled locally in `disconnect`. */
	async function refreshConnected(): Promise<void> {
		const runtime = appCtx().runtime;
		connected = runtime
			? (await runtimePromise(runtime, TokenVault.pipe(Effect.flatMap((v) => v.getTokens)))) !== null
			: false;
	}

	onMount(() => {
		void (async () => {
			await refreshConnected();
			if (connected) void loadAccount();
		})();
		const offConfig = onSyncConfigChange((next) => {
			premium = next.premium;
		});
		return offConfig;
	});

	async function loadAccount(): Promise<void> {
		const runtime = appCtx().runtime;
		if (!runtime) return;
		accountError = null;
		try {
			account = await runtimePromise(runtime, BeanSync.pipe(Effect.flatMap((b) => b.fetchAccount)));
		} catch (e) {
			accountError = e instanceof Error ? e.message : String(e);
		}
	}

	async function signIn(): Promise<void> {
		if (!oauthConfigured) return;
		signingIn = true;
		try {
			await startVisualizerLogin({ returnTo: '/settings' });
		} catch (e) {
			console.error('[Crema] startVisualizerLogin failed:', e);
			signingIn = false;
		}
	}

	async function disconnect(): Promise<void> {
		const runtime = appCtx().runtime;
		if (runtime) {
			const tokens = await runtimePromise(
				runtime,
				TokenVault.pipe(Effect.flatMap((v) => v.getTokens))
			);
			if (tokens?.accessToken) await revokeVisualizerToken(tokens.accessToken);
			await runtimePromise(runtime, TokenVault.pipe(Effect.flatMap((v) => v.clearTokens)));
		}
		// Tier flag is per-account — a fresh sign-in (or the same account
		// after a tier change on visualizer.coffee) shouldn't inherit it.
		clearVisualizerPremiumCache();
		// Reflect the disconnect locally (we own this transition; no listener).
		connected = false;
		account = null;
		accountError = null;
		testStatus = { kind: 'idle' };
	}

	async function testNow(): Promise<void> {
		const runtime = appCtx().runtime;
		if (!runtime) return;
		testStatus = { kind: 'testing' };
		const r = await runtimePromise(runtime, BeanSync.pipe(Effect.flatMap((b) => b.testConnection)));
		if (r.ok) {
			testStatus = {
				kind: 'ok',
				message:
					r.premium === false
						? 'Connected — free tier (read-only sync for beans / roasters).'
						: r.premium === true
							? 'Connected — Supporter tier (full sync).'
							: 'Connected — tier could not be verified (will retry on next Sync).'
			};
		} else {
			testStatus = { kind: 'error', message: r.error };
		}
	}

	/** Card eyebrow — connection state + account identity + public/private
	   profile state + cached tier. Stays put when transient status
	   (test results, errors) takes over the meta line.

	   - public/private comes from `MeResponse.public`; the API has no
	     `PATCH /me`, so it's display-only — the user toggles it on
	     visualizer.coffee. Omitted when an older `/me` cache lacks the
	     field.
	   - The tier segment reads the cached `premium` flag. It's
	     `null` until the first probe (initial load on a fresh sign-in)
	     and we omit the suffix in that case rather than show a
	     potentially-wrong label. Click "Test" to populate it. */
	const cardEyebrow = $derived.by(() => {
		if (!connected) {
			return oauthConfigured ? 'Not connected' : 'OAuth not configured';
		}
		if (!account) {
			return accountError ? 'Connected · profile unavailable' : 'Connected';
		}
		const visibility =
			typeof account.public === 'boolean'
				? account.public
					? ' · public profile'
					: ' · private'
				: '';
		const tier =
			premium === true ? ' · Premium' : premium === false ? ' · Free' : '';
		return `Connected · ${account.name}${visibility}${tier}`;
	});

	/** Status message shown in the card meta line — transient. */
	const cardStatus = $derived.by(() => {
		if (!connected) {
			if (!oauthConfigured) {
				return 'Set `VITE_VISUALIZER_CLIENT_ID` in `web/.env.local` and restart the dev server.';
			}
			return 'Free community service for sharing and comparing espresso shots';
		}
		if (testStatus.kind === 'testing') return 'Testing…';
		if (testStatus.kind === 'ok') return testStatus.message;
		if (testStatus.kind === 'error') return testStatus.message;
		if (accountError) return `Couldn't fetch your profile: ${accountError}`;
		return 'Signed in';
	});

	/** Export the local shot history as a JSON download. */
	function exportHistory(): void {
		const blob = new Blob([JSON.stringify(history.all, null, 2)], {
			type: 'application/json'
		});
		downloadBlob('crema-history.json', blob);
	}
</script>

<StSectionHead
	eyebrow="Third-party"
	title="Sharing"
	sub="Crema is local-only — there's no Crema account. Shots and profiles live on this device. Connect Visualizer if you want to back up, share, or compare shots online."
/>

<!-- Visualizer card — owns the full connection life-cycle (sign in / out,
     account identity, Test). Once `connected`, BeanSyncSection below
     handles the actual sync controls + log. -->
<div class="st-visualizer">
	<div class="st-visualizer-glyph">
		<svg viewBox="0 0 48 48" width="40" height="40" aria-hidden="true">
			<path
				d="M 4 36 Q 12 8, 24 24 T 44 12"
				fill="none"
				stroke="var(--copper-400)"
				stroke-width="2.5"
				stroke-linecap="round"
			/>
			<circle cx="24" cy="24" r="3.5" fill="var(--copper-400)" />
			<circle cx="44" cy="12" r="2.5" fill="rgba(var(--tint-rgb), 0.6)" />
			<circle cx="4" cy="36" r="2.5" fill="rgba(var(--tint-rgb), 0.6)" />
		</svg>
	</div>
	<div class="st-visualizer-info">
		<div
			class="t-eyebrow"
			style="color:{connected
				? 'var(--copper-400)'
				: oauthConfigured
					? 'rgba(var(--tint-rgb), 0.5)'
					: 'var(--warning)'}"
		>
			{cardEyebrow}
		</div>
		<div class="st-visualizer-name">visualizer.coffee</div>
		<div class="st-visualizer-meta">
			{#if !connected && !oauthConfigured}
				Set <code>VITE_VISUALIZER_CLIENT_ID</code> in <code>web/.env.local</code> and restart the dev server.
			{:else}
				{cardStatus}
			{/if}
		</div>
		<div class="st-visualizer-meta-row">
			<!-- Public/private is settable only on visualizer.coffee — the
			     spec has no `PATCH /me`, so Crema only displays the state.
			     The visualizer.coffee link below is the change path. -->
			<a
				class="st-visualizer-link"
				href="https://visualizer.coffee"
				target="_blank"
				rel="noreferrer noopener"
			>
				visualizer.coffee <i class="ph ph-arrow-square-out" aria-hidden="true"></i>
			</a>
		</div>
	</div>
	<div class="st-visualizer-actions">
		{#if connected}
			<StButton
				label={testStatus.kind === 'testing' ? 'Testing…' : 'Test'}
				icon="plugs-connected"
				disabled={testStatus.kind === 'testing'}
				onClick={testNow}
			/>
			<button type="button" class="st-btn st-btn-danger" onclick={disconnect}>
				<i class="ph ph-sign-out" aria-hidden="true"></i>Disconnect
			</button>
		{:else}
			<StButton
				label={signingIn ? 'Redirecting…' : 'Sign in'}
				icon="sign-in"
				variant="primary"
				disabled={!oauthConfigured || signingIn}
				onClick={signIn}
			/>
		{/if}
	</div>
</div>

<!-- Connected-state sync controls + log. -->
{#if connected}
	<BeanSyncSection />
{/if}

<StGroup title="Local export" sub="Download a copy of your local data.">
	<StRow
		title="History export"
		sub="One-shot download of your entire shot history as JSON. Useful for spreadsheets and other tools."
	>
		{#snippet control()}
			<StButton label="Export" icon="download-simple" onClick={exportHistory} />
		{/snippet}
	</StRow>
</StGroup>

<div class="st-otherint">
	<div class="st-group-title">Other integrations</div>
	<div class="st-otherint-grid">
		<div class="st-otherint-card">
			<i class="ph-duotone ph-chats-circle" aria-hidden="true"></i>
			<div class="st-otherint-title">DecentForum</div>
			<div class="st-otherint-sub">
				Share a profile from the library straight to a forum post.
			</div>
			<button type="button" class="st-btn st-btn-secondary" disabled>
				<i class="ph ph-link" aria-hidden="true"></i>Connect
			</button>
		</div>
		<div class="st-otherint-card">
			<i class="ph-duotone ph-cube" aria-hidden="true"></i>
			<div class="st-otherint-title">Insight</div>
			<div class="st-otherint-sub">
				Decent's profile marketplace. Import and rate profiles.
			</div>
			<button type="button" class="st-btn st-btn-secondary" disabled>
				<i class="ph ph-link" aria-hidden="true"></i>Connect
			</button>
		</div>
		<div class="st-otherint-card">
			<i class="ph-duotone ph-house" aria-hidden="true"></i>
			<div class="st-otherint-title">Home Assistant</div>
			<div class="st-otherint-sub">Expose shot events as MQTT topics.</div>
			<button type="button" class="st-btn st-btn-secondary" disabled>
				<i class="ph ph-gear" aria-hidden="true"></i>Configure in Advanced
			</button>
		</div>
	</div>
</div>
