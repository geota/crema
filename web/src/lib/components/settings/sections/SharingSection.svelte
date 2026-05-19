<script lang="ts">
	/**
	 * Sharing section — the Visualizer integration.
	 *
	 * Crema is **local-only — there is no Crema account**. Shots and profiles
	 * live on this device (in `localStorage`). Visualizer is the only external
	 * sync surface, and connecting it is opt-in. The section copy keeps that
	 * framing front and centre.
	 *
	 * ## Real vs. stubbed
	 *
	 * **Real persistence** — the upload preferences (auto-upload, default
	 * privacy, include-profile, include-notes) are persisted app preferences in
	 * `lib/settings`: they are how the app *would* behave when a sync runs.
	 *
	 * **UI-only** — the Visualizer connection itself, signing in, the upload
	 * queue and history export need the external visualizer.coffee service the
	 * shell does not talk to; the connection is faithful local UI (a `// TODO`
	 * marks the missing service), shown as "Not connected" out of the box.
	 */
	import { getSettingsStore } from '$lib/settings';
	import { getHistoryStore } from '$lib/history';
	import StSectionHead from '../StSectionHead.svelte';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StToggle from '../StToggle.svelte';
	import StSegment from '../StSegment.svelte';
	import StButton from '../StButton.svelte';

	const settings = getSettingsStore();
	const prefs = $derived(settings.current);
	const history = getHistoryStore();

	/** The allowed Visualizer privacy values — the `StSegment` option set. */
	type VisualizerPrivacy = 'public' | 'unlisted' | 'private';
	const PRIVACY_VALUES: readonly VisualizerPrivacy[] = ['public', 'unlisted', 'private'];

	/** Narrow an `StSegment` value to a {@link VisualizerPrivacy}, or `null`. */
	function asPrivacy(v: string): VisualizerPrivacy | null {
		return (PRIVACY_VALUES as readonly string[]).includes(v)
			? (v as VisualizerPrivacy)
			: null;
	}

	// TODO: the Visualizer link needs the visualizer.coffee service (OAuth +
	// upload API). The shell has no network/account layer, so the connection is
	// local UI state only — it starts disconnected and "Sign in" is a stub.
	let connected = $state(false);

	/** Number of recorded shots — the genuine local count, for the queue copy. */
	const shotCount = $derived(history.all.length);

	function signIn(): void {
		// TODO: wire to visualizer.coffee OAuth when a network layer exists.
	}
	function disconnect(): void {
		connected = false;
	}
	function uploadNow(): void {
		// TODO: wire to the Visualizer upload API when a network layer exists.
	}

	/** Export the local shot history as a JSON download — genuinely real. */
	function exportHistory(): void {
		if (typeof document === 'undefined') return;
		const blob = new Blob([JSON.stringify(history.all, null, 2)], {
			type: 'application/json'
		});
		const url = URL.createObjectURL(blob);
		const a = document.createElement('a');
		a.href = url;
		a.download = 'crema-history.json';
		a.click();
		URL.revokeObjectURL(url);
	}
</script>

<StSectionHead
	eyebrow="Third-party"
	title="Sharing"
	sub="Crema is local-only — there's no Crema account. Shots and profiles live on this device. Connect Visualizer if you want to back up, share, or compare shots online."
/>

<!-- Visualizer integration card -->
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
			{connected ? 'Connected' : 'Not connected'}
		</div>
		<div class="st-visualizer-name">visualizer.coffee</div>
		<div class="st-visualizer-meta">
			{#if connected}
				Signed in · shots upload in the background
			{:else}
				Free community service for sharing and comparing espresso shots
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
			<StButton label="Open library" icon="arrow-square-out" />
			<button type="button" class="st-btn st-btn-danger" onclick={disconnect}>
				<i class="ph ph-sign-out" aria-hidden="true"></i>Disconnect
			</button>
		{:else}
			<StButton label="Sign in" icon="sign-in" variant="primary" onClick={signIn} />
		{/if}
	</div>
</div>

<StGroup title="Upload" sub="When and how new shots are sent to Visualizer.">
	<StRow
		title="Auto-upload after every shot"
		sub="Shots upload in the background once you stop the extraction. Failed uploads retry on next connection."
	>
		{#snippet control()}
			<StToggle
				on={prefs.visualizerAutoUpload}
				onChange={(v) => settings.set('visualizerAutoUpload', v)}
				label="Auto-upload after every shot"
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Default privacy"
		sub="Public — discoverable in the global feed. Unlisted — link-only. Private — visible only to you."
	>
		{#snippet control()}
			<StSegment
				value={prefs.visualizerPrivacy}
				options={[
					{ value: 'public', label: 'Public' },
					{ value: 'unlisted', label: 'Unlisted' },
					{ value: 'private', label: 'Private' }
				]}
				onChange={(v) => {
					const privacy = asPrivacy(v);
					if (privacy) settings.set('visualizerPrivacy', privacy);
				}}
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Include profile JSON"
		sub="Lets others import the exact profile from your shot. Recommended."
	>
		{#snippet control()}
			<StToggle
				on={prefs.visualizerIncludeProfile}
				onChange={(v) => settings.set('visualizerIncludeProfile', v)}
				label="Include profile JSON"
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Include your notes"
		sub="If you tag the shot with tasting notes, send those too."
	>
		{#snippet control()}
			<StToggle
				on={prefs.visualizerIncludeNotes}
				onChange={(v) => settings.set('visualizerIncludeNotes', v)}
				label="Include your notes"
			/>
		{/snippet}
	</StRow>
</StGroup>

<StGroup title="Pending" sub="Shots not yet synced to Visualizer.">
	<StRow
		title={connected
			? 'Up to date'
			: `${shotCount} shot${shotCount === 1 ? '' : 's'} on this device`}
		sub={connected
			? 'All recorded shots are synced.'
			: 'Connect Visualizer to back these up online.'}
	>
		{#snippet control()}
			<StButton
				label="Upload now"
				icon="cloud-arrow-up"
				disabled={!connected}
				onClick={uploadNow}
			/>
		{/snippet}
	</StRow>
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
			<!-- TODO: needs a forum API + a network layer. Stub. -->
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
