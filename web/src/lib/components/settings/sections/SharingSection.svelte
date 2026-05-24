<script lang="ts">
	/**
	 * Sharing section — the Visualizer integration.
	 *
	 * Crema is **local-only — there is no Crema account**. Shots and profiles
	 * live on this device (in `localStorage`). Visualizer is the only external
	 * sync surface, and connecting it is opt-in. The section copy keeps that
	 * framing front and centre.
	 *
	 * The big visual Visualizer card lives here and owns connect / disconnect.
	 * Once connected, `BeanSyncSection` renders below to surface the account
	 * identity, Test, Sync now, and the sync log.
	 */
	import { onMount } from 'svelte';
	import { getHistoryStore } from '$lib/history';
	import { downloadBlob } from '$lib/utils/download';
	import {
		isVisualizerConnected,
		isVisualizerOauthConfigured,
		onVisualizerTokenChange,
		startVisualizerLogin,
		revokeVisualizerToken,
		getStoredVisualizerTokens
	} from '$lib/bean';
	import StSectionHead from '../StSectionHead.svelte';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StButton from '../StButton.svelte';
	import BeanSyncSection from './BeanSyncSection.svelte';

	const history = getHistoryStore();
	const oauthConfigured = isVisualizerOauthConfigured();
	let connected = $state(isVisualizerConnected());
	let signingIn = $state(false);

	onMount(() => {
		const off = onVisualizerTokenChange((set) => {
			connected = set !== null;
		});
		return off;
	});

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
		const tokens = getStoredVisualizerTokens();
		if (tokens?.accessToken) {
			await revokeVisualizerToken(tokens.accessToken);
		}
	}

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

<!-- Visualizer card — the visual anchor of the Sharing tab. Wired to the
     real OAuth flow ($lib/visualizer via $lib/bean re-exports). The
     button does the actual sign-in / disconnect; details (account
     identity, Test, Sync, log) are surfaced by BeanSyncSection below
     once connected. -->
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
			style="color:{connected ? 'var(--copper-400)' : 'rgba(var(--tint-rgb), 0.5)'}"
		>
			{connected ? 'Connected' : oauthConfigured ? 'Not connected' : 'OAuth not configured'}
		</div>
		<div class="st-visualizer-name">visualizer.coffee</div>
		<div class="st-visualizer-meta">
			{#if connected}
				Signed in · bean library + shots sync on demand
			{:else if oauthConfigured}
				Free community service for sharing and comparing espresso shots
			{:else}
				Set <code>VITE_VISUALIZER_CLIENT_ID</code> in <code>web/.env.local</code> and restart the dev server. See <a
					href="https://github.com/geota/crema/blob/main/docs/35-visualizer-oauth-setup.md"
					target="_blank"
					rel="noopener noreferrer">setup docs</a
				>.
			{/if}
		</div>
		<div class="st-visualizer-meta-row">
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
				label="Open library"
				icon="arrow-square-out"
				onClick={() => window.open('https://visualizer.coffee', '_blank', 'noopener,noreferrer')}
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

<!-- Connected-state details: account identity, Test, Sync now, log. -->
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
