<script lang="ts">
	/**
	 * `/auth/visualizer/callback` — the OAuth redirect target.
	 *
	 * Visualizer (Doorkeeper) sends the browser here after the user grants
	 * or denies access. We:
	 *   1. Parse `code` + `state` from the URL.
	 *   2. Hand them to `exchangeCodeForToken`, which double-checks `state`
	 *      against the value `startVisualizerLogin` stashed in
	 *      `sessionStorage`, then POSTs to `/oauth/token` with the
	 *      `code_verifier` (PKCE).
	 *   3. Persist the returned token set and bounce to the page the user
	 *      came from (defaults to `/settings`).
	 *
	 * Three failure modes worth handling explicitly:
	 *   - The user denied access (`?error=access_denied`) — show a
	 *     friendly notice + a "Try again" link.
	 *   - There's no `code` at all (someone refreshed this page after the
	 *     callback completed, or landed via a bad redirect) — if we
	 *     already have tokens, "Already connected"; otherwise nudge back
	 *     to Settings.
	 *   - The exchange itself failed (network, server, state mismatch) —
	 *     surface the message + a retry link.
	 */
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import {
		exchangeCodeForToken,
		getStoredTokens,
		storeTokens,
		takeReturnPath
	} from '$lib/visualizer';

	type Status =
		| { kind: 'working' }
		| { kind: 'done'; returnTo: string }
		| { kind: 'denied'; detail: string }
		| { kind: 'already' }
		| { kind: 'error'; message: string };

	let status = $state<Status>({ kind: 'working' });

	onMount(async () => {
		const params = page.url.searchParams;
		const code = params.get('code');
		const state = params.get('state');
		const error = params.get('error');
		const errorDesc = params.get('error_description');

		// 1. User-denied or other authorize-side error.
		if (error) {
			status = {
				kind: 'denied',
				detail: errorDesc ?? error
			};
			return;
		}

		// 2. No code at all — likely a refresh after the dance already
		// completed.
		if (!code || !state) {
			if (getStoredTokens()) {
				status = { kind: 'already' };
				return;
			}
			status = {
				kind: 'error',
				message:
					'Missing authorization code. Start the sign-in again from Settings.'
			};
			return;
		}

		try {
			const tokens = await exchangeCodeForToken({ code, state });
			storeTokens(tokens);
			const returnTo = takeReturnPath('/settings');
			status = { kind: 'done', returnTo };
			// Tiny delay so the user sees the success state before navigating.
			setTimeout(() => {
				void goto(returnTo, { replaceState: true });
			}, 600);
		} catch (e) {
			status = {
				kind: 'error',
				message: e instanceof Error ? e.message : String(e)
			};
		}
	});
</script>

<svelte:head>
	<title>Connecting to Visualizer · Crema</title>
</svelte:head>

<main class="cb-shell">
	<div class="cb-card">
		{#if status.kind === 'working'}
			<i class="ph ph-spinner-gap cb-spinner" aria-hidden="true"></i>
			<h1 class="t-h3">Connecting to Visualizer…</h1>
			<p class="t-body-sm">Exchanging the authorization code for an access token.</p>
		{:else if status.kind === 'done'}
			<i
				class="ph-fill ph-check-circle"
				aria-hidden="true"
				style:color="var(--success)"
				style:font-size="32px"
			></i>
			<h1 class="t-h3">Connected to Visualizer</h1>
			<p class="t-body-sm">Returning to {status.returnTo}…</p>
		{:else if status.kind === 'already'}
			<i
				class="ph ph-plugs-connected"
				aria-hidden="true"
				style:color="var(--copper-400)"
				style:font-size="32px"
			></i>
			<h1 class="t-h3">Already connected</h1>
			<p class="t-body-sm">
				You're already signed in to Visualizer. Open
				<a href="/settings">Settings</a> to manage the connection.
			</p>
		{:else if status.kind === 'denied'}
			<i
				class="ph ph-warning"
				aria-hidden="true"
				style:color="var(--warn)"
				style:font-size="32px"
			></i>
			<h1 class="t-h3">Sign-in cancelled</h1>
			<p class="t-body-sm">Visualizer reported: {status.detail}</p>
			<a class="cb-link" href="/settings">Back to Settings</a>
		{:else}
			<i
				class="ph ph-warning-circle"
				aria-hidden="true"
				style:color="var(--danger)"
				style:font-size="32px"
			></i>
			<h1 class="t-h3">Sign-in failed</h1>
			<p class="t-body-sm">{status.message}</p>
			<a class="cb-link" href="/settings">Try again</a>
		{/if}
	</div>
</main>

<style>
	.cb-shell {
		display: flex;
		align-items: center;
		justify-content: center;
		min-height: 100vh;
		padding: var(--space-6);
	}
	.cb-card {
		max-width: 420px;
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--space-3);
		text-align: center;
		background: var(--bg-surface);
		border: 1px solid var(--hairline);
		border-radius: var(--radius-lg);
		box-shadow: var(--shadow-md);
		padding: var(--space-6);
	}
	.cb-spinner {
		font-size: 32px;
		color: var(--copper-400);
		animation: cb-spin 1.1s linear infinite;
	}
	@keyframes cb-spin {
		from { transform: rotate(0); }
		to { transform: rotate(360deg); }
	}
	.cb-link {
		font-family: var(--font-sans);
		font-size: 13px;
		color: var(--copper-400);
		text-decoration: underline;
		margin-top: var(--space-2);
	}
</style>
