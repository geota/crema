<script lang="ts">
	import CheckCircleIcon from 'phosphor-svelte/lib/CheckCircleIcon';
	import PlugsConnectedIcon from 'phosphor-svelte/lib/PlugsConnectedIcon';
	import SpinnerGapIcon from 'phosphor-svelte/lib/SpinnerGapIcon';
	import WarningCircleIcon from 'phosphor-svelte/lib/WarningCircleIcon';
	import WarningIcon from 'phosphor-svelte/lib/WarningIcon';
	/**
	 * `/auth/visualizer/callback` — the OAuth redirect target.
	 *
	 * Visualizer (Doorkeeper) sends the browser here after the user grants
	 * or denies access. We:
	 *   1. Parse `code` + `state` from the URL.
	 *   2. Hand them to the Effect `OAuth.exchangeCode` service, which
	 *      double-checks `state` against the value stashed in `sessionStorage`,
	 *      then POSTs to `/oauth/token` with the `code_verifier` (PKCE) via the
	 *      HttpClient service. Run on a dedicated short-lived runtime.
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
	import { Cause, Effect, Exit, Layer, ManagedRuntime } from 'effect';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { OAuth, OAuthLive } from '$lib/services/oauth';
	import { HttpClientLive } from '$lib/services/http-client';
	import { TokenVault, TokenVaultLive } from '$lib/services/token-vault';
	import { takeReturnPath } from '$lib/visualizer';

	type Status =
		| { kind: 'working' }
		| { kind: 'done'; returnTo: string }
		| { kind: 'denied'; detail: string }
		| { kind: 'already' }
		| { kind: 'error'; message: string };

	let status = $state<Status>({ kind: 'working' });

	/**
	 * SEC5: build a safe, short denial message from the OAuth redirect's `error`
	 * code + optional `error_description`. The description is arbitrary text from
	 * the URL — strip control chars, collapse whitespace, and clip to 140 chars
	 * so it can't render long phishing copy (Svelte already escapes the markup).
	 */
	function sanitizeOAuthError(code: string, desc: string | null): string {
		const cleanCode = code.replace(/[^\w-]/g, '').slice(0, 64) || 'error';
		if (!desc) return cleanCode;
		const cleanDesc = desc
			.replace(/\p{Cc}+/gu, ' ')
			.replace(/\s+/g, ' ')
			.trim()
			.slice(0, 140);
		return cleanDesc ? `${cleanCode}: ${cleanDesc}` : cleanCode;
	}

	onMount(async () => {
		const params = page.url.searchParams;
		const code = params.get('code');
		const state = params.get('state');
		const error = params.get('error');
		const errorDesc = params.get('error_description');

		// 1. User-denied or other authorize-side error.
		if (error) {
			// SEC5: prefer the short, known `error` code; clip + sanitise the
			// arbitrary `error_description` so it can't render phishing copy.
			status = { kind: 'denied', detail: sanitizeOAuthError(error, errorDesc) };
			return;
		}

		// Dedicated short-lived runtime — independent of the app shell's runtime
		// (this page runs at redirect time before the shell has mounted). It
		// carries OAuth (-> HttpClient) for the exchange AND TokenVault for
		// persistence: TokenVault.storeTokens writes the shared localStorage key,
		// so the shell's TokenVault reads it fresh on the next navigation.
		const base = Layer.provide(OAuthLive, HttpClientLive);
		const runtime = ManagedRuntime.make(Layer.merge(base, Layer.provide(TokenVaultLive, base)));
		try {
			// 2. No code at all — likely a refresh after the dance already completed.
			if (!code || !state) {
				const existing = await runtime.runPromise(Effect.flatMap(TokenVault, (v) => v.getTokens));
				status = existing
					? { kind: 'already' }
					: {
							kind: 'error',
							message: 'Missing authorization code. Start the sign-in again from Settings.'
						};
				return;
			}

			// 3. Exchange the code via the OAuth service, then persist via TokenVault.
			const exit = await runtime.runPromiseExit(
				Effect.flatMap(OAuth, (o) => o.exchangeCode(code, state))
			);
			if (Exit.isFailure(exit)) throw Cause.squash(exit.cause);
			const tokens = exit.value;
			await runtime.runPromise(Effect.flatMap(TokenVault, (v) => v.storeTokens(tokens)));
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
		} finally {
			void runtime.dispose();
		}
	});
</script>

<svelte:head>
	<title>Connecting to Visualizer · Crema</title>
</svelte:head>

<main class="cb-shell">
	<div class="cb-card">
		{#if status.kind === 'working'}
			<SpinnerGapIcon class="cb-spinner" aria-hidden="true" />
			<h1 class="t-h3">Connecting to Visualizer…</h1>
			<p class="t-body-sm">Exchanging the authorization code for an access token.</p>
		{:else if status.kind === 'done'}
			<CheckCircleIcon weight="fill" aria-hidden="true"
				style="color: var(--success); font-size: 32px" />
			<h1 class="t-h3">Connected to Visualizer</h1>
			<p class="t-body-sm">Returning to {status.returnTo}…</p>
		{:else if status.kind === 'already'}
			<PlugsConnectedIcon aria-hidden="true"
				style="color: var(--copper-400); font-size: 32px" />
			<h1 class="t-h3">Already connected</h1>
			<p class="t-body-sm">
				You're already signed in to Visualizer. Open
				<a href="/settings">Settings</a> to manage the connection.
			</p>
		{:else if status.kind === 'denied'}
			<WarningIcon aria-hidden="true"
				style="color: var(--warn); font-size: 32px" />
			<h1 class="t-h3">Sign-in cancelled</h1>
			<p class="t-body-sm">Visualizer reported: {status.detail}</p>
			<a class="cb-link" href="/settings">Back to Settings</a>
		{:else}
			<WarningCircleIcon aria-hidden="true"
				style="color: var(--danger); font-size: 32px" />
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
	:global(.cb-spinner) {
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
