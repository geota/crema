<script lang="ts">
	import ArrowSquareOutIcon from 'phosphor-svelte/lib/ArrowSquareOutIcon';
	import ChatsCircleIcon from 'phosphor-svelte/lib/ChatsCircleIcon';
	import CubeIcon from 'phosphor-svelte/lib/CubeIcon';
	import GearIcon from 'phosphor-svelte/lib/GearIcon';
	import HouseIcon from 'phosphor-svelte/lib/HouseIcon';
	import LinkIcon from 'phosphor-svelte/lib/LinkIcon';
	import SignOutIcon from 'phosphor-svelte/lib/SignOutIcon';
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
	import { getHistoryStore } from '$lib/history';
	import { downloadBlob } from '$lib/utils/download';
	import {
		clearVisualizerPremiumCache,
		isVisualizerOauthConfigured,
		revokeVisualizerToken,
		startVisualizerLogin,
		type VisualizerAccount
	} from '$lib/bean';
	import { onSyncConfigChange, readSyncConfig, updateSyncConfig } from '$lib/visualizer';
	import { useVisualizerConnection } from '$lib/visualizer/useVisualizerConnection.svelte';
	import { getCremaAppContext } from '$lib/shell/app-context';
	import StSectionHead from '../StSectionHead.svelte';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StButton from '../StButton.svelte';
	import StSelect from '../StSelect.svelte';
	import StToggle from '../StToggle.svelte';
	import BeanSyncSection from './BeanSyncSection.svelte';
	import { type SharingPrivacy } from '$lib/settings';
	import {
		backupFileName,
		buildBackupJsonl,
		exportBackup,
		restoreBackup,
		type RestoreMode
	} from '$lib/backup';
	import { getDriveAuthStore } from '$lib/drive/store.svelte';
	import { downloadBackup, listBackups, uploadBackup } from '$lib/drive/rest';

	const history = getHistoryStore();
	const appCtx = getCremaAppContext();
	const viz = useVisualizerConnection();
	const oauthConfigured = isVisualizerOauthConfigured();

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
	/** Reactive snapshot of the sync-config, so the Upload-options controls
	   reflect a change made elsewhere (the prefs now live in the sync-config). */
	let syncCfg = $state(readSyncConfig());

	// Fetch the account the first time we observe a connection — the helper's
	// mount seed or a later sign-in. Guarded on account/error so it loads once
	// per connect and never retries a failure into a loop. (Replaces the old
	// onMount seed-then-load; the connection gate itself now lives in
	// `useVisualizerConnection`.)
	$effect(() => {
		if (viz.connected && account === null && accountError === null) void loadAccount();
	});

	onMount(() =>
		onSyncConfigChange((next) => {
			premium = next.premium;
			syncCfg = next;
		})
	);

	async function loadAccount(): Promise<void> {
		const api = appCtx().services;
		if (!api) return;
		accountError = null;
		try {
			account = await api.beans.fetchAccount();
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
		const api = appCtx().services;
		if (api) {
			const tokens = await api.tokens.getTokens();
			if (tokens?.accessToken) await revokeVisualizerToken(tokens.accessToken);
			await api.tokens.clearTokens();
		}
		// Tier flag is per-account — a fresh sign-in (or the same account
		// after a tier change on visualizer.coffee) shouldn't inherit it.
		clearVisualizerPremiumCache();
		// Reflect the disconnect optimistically. `clearTokens` above also fires
		// the connection subscription, but that echoes back a tick later; setting
		// it here keeps the card snappy (the echo is then a redundant no-op).
		viz.connected = false;
		account = null;
		accountError = null;
		testStatus = { kind: 'idle' };
	}

	async function testNow(): Promise<void> {
		const api = appCtx().services;
		if (!api) return;
		testStatus = { kind: 'testing' };
		const r = await api.beans.testConnection();
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
		if (!viz.connected) {
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
		if (!viz.connected) {
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

	/** Brief inline feedback for the Backup & restore actions. */
	let backupNote = $state<string | null>(null);

	/** Build + download a full `crema-backup/v1` bundle of everything local. */
	function backUp(): void {
		const result = exportBackup();
		backupNote = result
			? `Backed up ${result.profiles} profile(s), ${result.beans} bean(s), ${result.roasters} roaster(s), ${result.shots} shot(s).`
			: 'Nothing to back up yet.';
	}

	let restoreInput = $state<HTMLInputElement | null>(null);
	let pendingRestoreMode = $state<RestoreMode>('merge');

	/** Open the file picker for a restore. A REPLACE (wipe) is gated by a
	 *  confirm first — it deletes everything before loading the backup. */
	function pickRestore(mode: RestoreMode): void {
		if (
			mode === 'wipe' &&
			!window.confirm(
				"Replace everything on this device with a backup? Your current profiles, beans, roasters and shots are deleted first. This can't be undone."
			)
		) {
			return;
		}
		pendingRestoreMode = mode;
		if (restoreInput) {
			restoreInput.value = '';
			restoreInput.click();
		}
	}

	/** Read the chosen `.crema` file + apply it (merge or replace). */
	async function onRestoreFile(event: Event): Promise<void> {
		const file = (event.target as HTMLInputElement).files?.[0];
		if (!file) return;
		try {
			const text = await file.text();
			const r = restoreBackup(text, pendingRestoreMode);
			backupNote =
				`Restored ${r.profiles} profile(s), ${r.beans} bean(s), ` +
				`${r.roasters} roaster(s), ${r.shots} shot(s)` +
				(r.settingsApplied ? ' + settings.' : '.');
		} catch (err) {
			backupNote = err instanceof Error ? `Restore failed: ${err.message}` : 'Restore failed.';
		}
	}

	// ── Google Drive backup ──────────────────────────────────────────────
	const drive = getDriveAuthStore();
	let driveNote = $state<string | null>(null);
	let driveBusy = $state(false);

	async function driveConnect(): Promise<void> {
		try {
			await drive.signIn('/settings');
		} catch (err) {
			driveNote = err instanceof Error ? err.message : 'Could not start Google sign-in.';
		}
	}

	/** Build the bundle + upload a fresh (timestamped) copy to Drive. */
	async function driveBackup(): Promise<void> {
		const built = buildBackupJsonl();
		if (!built) {
			driveNote = 'Nothing to back up yet.';
			return;
		}
		driveBusy = true;
		driveNote = 'Backing up to Drive…';
		try {
			const token = await drive.accessToken();
			await uploadBackup(token, backupFileName(), built.jsonl);
			const c = built.contents;
			driveNote = `Backed up to Drive — ${c.profiles} profile(s), ${c.beans} bean(s), ${c.shots} shot(s).`;
		} catch (err) {
			driveNote = err instanceof Error ? `Drive backup failed: ${err.message}` : 'Drive backup failed.';
		} finally {
			driveBusy = false;
		}
	}

	/** Restore the most-recent Drive backup (merge). */
	async function driveRestoreLatest(): Promise<void> {
		driveBusy = true;
		driveNote = 'Fetching from Drive…';
		try {
			const token = await drive.accessToken();
			const files = await listBackups(token);
			if (files.length === 0) {
				driveNote = 'No backups found in Drive.';
				return;
			}
			const text = await downloadBackup(token, files[0].id);
			const r = restoreBackup(text, 'merge');
			driveNote =
				`Restored from Drive (${files[0].name}) — ${r.profiles} profile(s), ` +
				`${r.beans} bean(s), ${r.shots} shot(s)${r.settingsApplied ? ' + settings.' : '.'}`;
		} catch (err) {
			driveNote = err instanceof Error ? `Drive restore failed: ${err.message}` : 'Drive restore failed.';
		} finally {
			driveBusy = false;
		}
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
			style="color:{viz.connected
				? 'var(--copper-400)'
				: oauthConfigured
					? 'rgba(var(--tint-rgb), 0.5)'
					: 'var(--warning)'}"
		>
			{cardEyebrow}
		</div>
		<div class="st-visualizer-name">visualizer.coffee</div>
		<div class="st-visualizer-meta">
			{#if !viz.connected && !oauthConfigured}
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
				visualizer.coffee <ArrowSquareOutIcon aria-hidden="true" />
			</a>
		</div>
	</div>
	<div class="st-visualizer-actions">
		{#if viz.connected}
			<StButton
				label={testStatus.kind === 'testing' ? 'Testing…' : 'Test'}
				icon="plugs-connected"
				disabled={testStatus.kind === 'testing'}
				onClick={testNow}
			/>
			<button type="button" class="st-btn st-btn-danger" onclick={disconnect}>
				<SignOutIcon aria-hidden="true" />Disconnect
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
{#if viz.connected}
	<BeanSyncSection />

	<!-- Upload options — the three settings buildShotPayload has always
	     applied on the wire (privacy / include profile / include notes);
	     surfaced here so they're no longer invisible defaults. The privacy
	     value is the DEFAULT for new uploads — each shot can override it
	     from the History detail's Sharing block. -->
	<StGroup title="Upload options" sub="Defaults applied to every shot upload.">
		<StRow
			title="Auto-upload finished shots"
			sub="Push each shot to Visualizer when it completes. Off = upload manually from History."
		>
			{#snippet control()}
				<StToggle
					on={syncCfg.autoUpload}
					onChange={(v) => updateSyncConfig({ autoUpload: v })}
				/>
			{/snippet}
		</StRow>
		<StRow
			title="Default privacy"
			sub="Who can see uploaded shots. Public = community feed; unlisted = direct link only; private = just you. Individual shots can override this in History."
		>
			{#snippet control()}
				<StSelect
					value={syncCfg.privacy}
					options={[
						{ value: 'public', label: 'Public' },
						{ value: 'unlisted', label: 'Unlisted' },
						{ value: 'private', label: 'Private' }
					]}
					onChange={(v) => updateSyncConfig({ privacy: v as SharingPrivacy })}
				/>
			{/snippet}
		</StRow>
		<StRow
			title="Include profile"
			sub="Attach the full recipe (every segment) to uploads. Off = share the telemetry, keep the recipe."
		>
			{#snippet control()}
				<StToggle
					on={syncCfg.includeProfile}
					onChange={(v) => updateSyncConfig({ includeProfile: v })}
				/>
			{/snippet}
		</StRow>
		<StRow
			title="Include tasting notes"
			sub="Attach your journal text to uploads. Ratings and numbers always ride along."
		>
			{#snippet control()}
				<StToggle
					on={syncCfg.includeNotes}
					onChange={(v) => updateSyncConfig({ includeNotes: v })}
				/>
			{/snippet}
		</StRow>
	</StGroup>
{/if}

<StGroup
	title="Backup & restore"
	sub="One file with all your custom profiles, beans, roasters, shots and settings — keep it safe or move it to another device."
>
	<StRow
		title="Back up everything"
		sub={backupNote ?? 'Download a .crema bundle of everything on this device. Photos stay local.'}
	>
		{#snippet control()}
			<StButton label="Back up" icon="download-simple" variant="primary" onClick={backUp} />
		{/snippet}
	</StRow>
	<StRow
		title="Restore — merge"
		sub="Add anything from a backup you don't already have; keeps your current data."
	>
		{#snippet control()}
			<StButton label="Merge" icon="upload-simple" onClick={() => pickRestore('merge')} />
		{/snippet}
	</StRow>
	<StRow
		title="Restore — replace"
		sub="Wipe this device, then restore fully from a backup. Can't be undone."
	>
		{#snippet control()}
			<StButton label="Replace" icon="trash" onClick={() => pickRestore('wipe')} />
		{/snippet}
	</StRow>
</StGroup>

<input
	bind:this={restoreInput}
	type="file"
	accept=".crema,application/x-ndjson,application/json"
	onchange={onRestoreFile}
	style="display: none"
/>

<StGroup
	title="Google Drive"
	sub={driveNote ??
		(drive.connected
			? 'Back up to your Drive or restore your most recent backup. Crema only sees the files it created.'
			: drive.configured
				? 'Connect Google Drive to back up online (drive.file — Crema only sees its own backups).'
				: 'Set VITE_GOOGLE_DRIVE_CLIENT_ID in web/.env.local to enable Drive backup.')}
>
	{#if drive.connected}
		<StRow title="Back up to Drive" sub="Upload a fresh bundle to your Google Drive.">
			{#snippet control()}
				<StButton
					label={driveBusy ? 'Working…' : 'Back up'}
					icon="cloud-arrow-up"
					variant="primary"
					disabled={driveBusy}
					onClick={driveBackup}
				/>
			{/snippet}
		</StRow>
		<StRow
			title="Restore latest from Drive"
			sub="Merge your most-recent Drive backup into this device."
		>
			{#snippet control()}
				<StButton
					label={driveBusy ? 'Working…' : 'Restore'}
					icon="cloud-arrow-down"
					disabled={driveBusy}
					onClick={driveRestoreLatest}
				/>
			{/snippet}
		</StRow>
		<StRow title="Disconnect Google Drive" sub="Forget the Drive sign-in on this device.">
			{#snippet control()}
				<StButton label="Disconnect" icon="sign-out" onClick={() => drive.signOut()} />
			{/snippet}
		</StRow>
	{:else}
		<StRow title="Connect Google Drive" sub="Sign in to enable online backup.">
			{#snippet control()}
				<StButton
					label="Connect"
					icon="sign-in"
					variant="primary"
					disabled={!drive.configured}
					onClick={driveConnect}
				/>
			{/snippet}
		</StRow>
	{/if}
</StGroup>

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
			<ChatsCircleIcon weight="duotone" aria-hidden="true" />
			<div class="st-otherint-title">DecentForum</div>
			<div class="st-otherint-sub">
				Share a profile from the library straight to a forum post.
			</div>
			<button type="button" class="st-btn st-btn-secondary" disabled>
				<LinkIcon aria-hidden="true" />Connect
			</button>
		</div>
		<div class="st-otherint-card">
			<CubeIcon weight="duotone" aria-hidden="true" />
			<div class="st-otherint-title">Insight</div>
			<div class="st-otherint-sub">
				Decent's profile marketplace. Import and rate profiles.
			</div>
			<button type="button" class="st-btn st-btn-secondary" disabled>
				<LinkIcon aria-hidden="true" />Connect
			</button>
		</div>
		<div class="st-otherint-card">
			<HouseIcon weight="duotone" aria-hidden="true" />
			<div class="st-otherint-title">Home Assistant</div>
			<div class="st-otherint-sub">Expose shot events as MQTT topics.</div>
			<button type="button" class="st-btn st-btn-secondary" disabled>
				<GearIcon aria-hidden="true" />Configure in Advanced
			</button>
		</div>
	</div>
</div>
