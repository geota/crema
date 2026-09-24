<script lang="ts">
	/**
	 * Decent account card (#84) — the owner's shot history on
	 * decentespresso.com. Same card grammar as the Visualizer hero in
	 * `SharingSection`: glyph · identity/status · actions. Once linked, the
	 * "Decent shot history" group below it holds the auto-upload toggle and
	 * whatever the parent renders into it (the catch-up offer).
	 *
	 * Email + password are exchanged once for a server token (`login_test`);
	 * the password is never stored, and the token is wrapped at rest by
	 * `linkDecentAccount`. The parent owns the account state (it needs it for
	 * the catch-up offer) and hears about the two edges that make Decent able
	 * to receive shots: a successful sign-in, and auto-upload turning on.
	 */
	import type { Snippet } from 'svelte';
	import ArrowSquareOutIcon from 'phosphor-svelte/lib/ArrowSquareOutIcon';
	import CheckCircleIcon from 'phosphor-svelte/lib/CheckCircleIcon';
	import SignInIcon from 'phosphor-svelte/lib/SignInIcon';
	import SignOutIcon from 'phosphor-svelte/lib/SignOutIcon';
	import WarningIcon from 'phosphor-svelte/lib/WarningIcon';
	import {
		DECENT_HISTORY_URL,
		decentFetchMachines,
		decentLogin,
		isDecentLinked,
		linkDecentAccount,
		unlinkDecentAccount,
		updateDecentAccount,
		type DecentAccountState
	} from '$lib/decent';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StToggle from '../StToggle.svelte';

	let {
		account,
		liveSerial,
		onLinked,
		onAutoUploadEnabled,
		children
	}: {
		/** The persisted account state (kept live by the parent's subscription). */
		account: DecentAccountState;
		/** The connected DE1's serial, or `null` when none is connected / read yet. */
		liveSerial: number | null;
		/** Sign-in succeeded — the account can now receive shots. */
		onLinked?: () => void;
		/** The "Upload finished shots" toggle was turned on. */
		onAutoUploadEnabled?: () => void;
		/** Extra rows for the "Decent shot history" group (the catch-up offer). */
		children?: Snippet;
	} = $props();

	let email = $state('');
	let password = $state('');
	let linking = $state(false);
	let error = $state<string | null>(null);

	const linked = $derived(isDecentLinked(account));
	/** Is the connected DE1 registered on the linked account? `null` = no DE1 / no account. */
	const serialOnAccount = $derived(
		liveSerial == null || !linked ? null : account.serials.includes(String(liveSerial))
	);
	const status = $derived<'ok' | 'reauth' | 'off'>(
		linked ? (account.needsReauth ? 'reauth' : 'ok') : 'off'
	);
	const statusLabel = $derived(
		status === 'ok' ? 'Linked' : status === 'reauth' ? 'Sign in again' : 'Not linked'
	);

	async function signIn(e: SubmitEvent): Promise<void> {
		e.preventDefault();
		const trimmed = email.trim();
		if (!trimmed || !password || linking) return;
		linking = true;
		error = null;
		try {
			const token = await decentLogin(trimmed, password);
			if (!token) {
				error = 'Decent didn’t accept that email + password.';
				return;
			}
			let serials: string[] = [];
			try {
				serials = (await decentFetchMachines({ email: trimmed, token })).map((m) => m.serial);
			} catch (err) {
				console.warn('[Crema] Decent machine list failed:', err);
			}
			// Linking is the affirmative choice (de1app semantics): auto-upload
			// turns on with it; the toggle below opts out.
			await linkDecentAccount({ email: trimmed, token, serials });
			password = '';
			email = '';
			onLinked?.();
		} catch (err) {
			error = err instanceof Error ? err.message : String(err);
		} finally {
			linking = false;
		}
	}

	function signOut(): void {
		unlinkDecentAccount();
		error = null;
	}

	function setAutoUpload(on: boolean): void {
		updateDecentAccount({ autoUpload: on });
		if (on) onAutoUploadEnabled?.();
	}
</script>

<div class="st-visualizer st-decent">
	<div class="st-visualizer-glyph">
		<svg viewBox="0 0 48 48" width="40" height="40" aria-hidden="true">
			<!-- A cup-and-chart mark: the DE1's pressure hump over a saucer line. -->
			<path
				d="M 6 34 H 42"
				fill="none"
				stroke="rgba(var(--tint-rgb), 0.6)"
				stroke-width="2"
				stroke-linecap="round"
			/>
			<path
				d="M 8 30 C 14 30, 15 12, 22 12 C 30 12, 30 26, 40 28"
				fill="none"
				stroke="var(--copper-400)"
				stroke-width="2.5"
				stroke-linecap="round"
			/>
			<circle cx="22" cy="12" r="3" fill="var(--copper-400)" />
		</svg>
	</div>
	<div class="st-visualizer-info">
		<div class="t-eyebrow dc-state dc-state-{status}" role="status" aria-label="Decent account: {statusLabel}">
			{#if status === 'ok'}
				<CheckCircleIcon aria-hidden="true" />
			{:else if status === 'reauth'}
				<WarningIcon aria-hidden="true" />
			{/if}
			{statusLabel}
		</div>
		<div class="st-visualizer-name">Decent account</div>
		<div class="st-visualizer-meta">
			{#if linked}
				Signed in as <strong>{account.email}</strong>.
				{account.serials.length} machine{account.serials.length === 1 ? '' : 's'} on the account{#if liveSerial != null}
					· the connected DE1 <strong>#{liveSerial}</strong>
					{#if serialOnAccount}is registered{:else}<span class="dc-warn"
							><WarningIcon aria-hidden="true" /> is not on this account — uploads will be refused</span
						>{/if}{/if}.
				{#if account.needsReauth}
					<span class="dc-warn"
						><WarningIcon aria-hidden="true" /> Your saved login no longer works — sign out and sign in again.</span
					>
				{/if}
				Signing out only forgets the login on this device; if you lose a device, change your
				Decent password.
				{#if account.lastUpload && !account.lastUpload.ok}
					<div class="dc-warn">
						<WarningIcon aria-hidden="true" /> Last upload failed: {account.lastUpload.message}
					</div>
				{/if}
			{:else}
				Upload every shot to your own Decent Espresso account — the shot history and charts at
				decentespresso.com, the same place the tablet app and decaid upload to. Sign in with the
				email + password of your Decent account; only a server token is kept on this device.
			{/if}
			{#if error}
				<div class="dc-warn" role="alert"><WarningIcon aria-hidden="true" /> {error}</div>
			{/if}
		</div>
		<div class="st-visualizer-meta-row">
			<a class="st-visualizer-link" href={DECENT_HISTORY_URL} target="_blank" rel="noreferrer noopener">
				decentespresso.com shot history <ArrowSquareOutIcon aria-hidden="true" />
			</a>
		</div>
	</div>
	<div class="st-visualizer-actions">
		{#if linked}
			<button type="button" class="st-btn st-btn-danger" onclick={signOut}>
				<SignOutIcon aria-hidden="true" />Sign out
			</button>
		{:else}
			<form class="dc-form" onsubmit={signIn} aria-label="Sign in to your Decent account">
				<label class="dc-label">
					Email
					<input
						class="dc-input"
						type="email"
						name="email"
						autocomplete="email"
						placeholder="email@example.com"
						required
						bind:value={email}
						disabled={linking}
					/>
				</label>
				<label class="dc-label">
					Password
					<input
						class="dc-input"
						type="password"
						name="password"
						autocomplete="current-password"
						required
						bind:value={password}
						disabled={linking}
					/>
				</label>
				<button
					type="submit"
					class="st-btn st-btn-primary"
					disabled={linking || !email.trim() || !password}
				>
					<SignInIcon aria-hidden="true" />{linking ? 'Signing in…' : 'Sign in'}
				</button>
			</form>
		{/if}
	</div>
</div>

{#if linked}
	<StGroup
		title="Decent shot history"
		sub="Shots are sent in the same format the Decent tablet app and decaid use, so they appear alongside them."
	>
		<StRow
			title="Upload finished shots"
			sub="Push each shot to your Decent account when it completes. Flushes under 5 s are skipped."
		>
			{#snippet control()}
				<StToggle on={account.autoUpload} onChange={setAutoUpload} label="Upload finished shots to Decent" />
			{/snippet}
		</StRow>
		{@render children?.()}
	</StGroup>
{/if}
