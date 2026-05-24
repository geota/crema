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
	import { getHistoryStore } from '$lib/history';
	import { downloadBlob } from '$lib/utils/download';
	import StSectionHead from '../StSectionHead.svelte';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StButton from '../StButton.svelte';
	import BeanSyncSection from './BeanSyncSection.svelte';

	const history = getHistoryStore();

	/** Export the local shot history as a JSON download — genuinely real. */
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

<!-- Visualizer connection + sync controls live in BeanSyncSection — it
     owns the OAuth flow, sync log, and (future) per-shot upload toggles.
     The previous stand-alone Visualizer card + Upload / Pending stubs
     were removed when OAuth landed (their stubbed buttons collided
     visually with the real Sign-in button — both said "Sign in"). -->
<BeanSyncSection />

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
