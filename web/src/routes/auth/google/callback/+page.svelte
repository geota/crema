<script lang="ts">
	/**
	 * `/auth/google/callback` — the Google Drive OAuth redirect target.
	 *
	 *   1. Read `?code=…&state=…` (or `?error=…`) off the URL.
	 *   2. Exchange the code for a {@link DriveTokenSet} (PKCE verifier from
	 *      sessionStorage), persist it via the Drive auth store.
	 *   3. Bounce back to the page the user started from (Settings).
	 *
	 * Mirrors `/auth/visualizer/callback`. Everything runs in `onMount` so it's
	 * client-only (sessionStorage + the token POST never touch SSR).
	 */
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import CheckCircleIcon from 'phosphor-svelte/lib/CheckCircleIcon';
	import SpinnerGapIcon from 'phosphor-svelte/lib/SpinnerGapIcon';
	import WarningCircleIcon from 'phosphor-svelte/lib/WarningCircleIcon';
	import { exchangeGoogleDriveCode, takeReturnPath } from '$lib/drive/oauth';
	import { getDriveAuthStore } from '$lib/drive/store.svelte';

	let status = $state<'working' | 'ok' | 'error'>('working');
	let message = $state('Connecting Google Drive…');

	onMount(async () => {
		const params = new URLSearchParams(window.location.search);
		const error = params.get('error');
		const code = params.get('code');
		const state = params.get('state');
		if (error) {
			status = 'error';
			message = `Google sign-in was cancelled or failed (${error}).`;
			return;
		}
		if (!code || !state) {
			status = 'error';
			message = 'Missing authorization code — restart the sign-in from Settings.';
			return;
		}
		try {
			getDriveAuthStore().set(await exchangeGoogleDriveCode(code, state));
			status = 'ok';
			message = 'Google Drive connected. Returning…';
			await goto(takeReturnPath(), { replaceState: true });
		} catch (err) {
			status = 'error';
			message = err instanceof Error ? err.message : 'Could not complete Google sign-in.';
		}
	});
</script>

<div class="cb">
	<div class="cb-glyph" class:err={status === 'error'} class:ok={status === 'ok'}>
		{#if status === 'working'}
			<span class="spin"><SpinnerGapIcon /></span>
		{:else if status === 'ok'}
			<CheckCircleIcon weight="fill" />
		{:else}
			<WarningCircleIcon weight="fill" />
		{/if}
	</div>
	<p>{message}</p>
	{#if status === 'error'}
		<a class="cb-link" href="/settings">Back to Settings</a>
	{/if}
</div>

<style>
	.cb {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 1rem;
		min-height: 60vh;
		padding: 2rem;
		text-align: center;
		color: var(--ink, inherit);
	}
	.cb-glyph {
		font-size: 2.5rem;
		line-height: 0;
		color: var(--copper-400, currentColor);
	}
	.cb-glyph.err {
		color: var(--warning, #c0392b);
	}
	.spin {
		display: inline-block;
		animation: cb-spin 1s linear infinite;
	}
	@keyframes cb-spin {
		to {
			transform: rotate(360deg);
		}
	}
	.cb-link {
		color: var(--copper-400, inherit);
		text-decoration: underline;
	}
</style>
