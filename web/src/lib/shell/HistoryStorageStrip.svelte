<script lang="ts">
	/**
	 * `HistoryStorageStrip` — stays up while the shot history's storage needs
	 * the user (#310 P3w, SPEC §1 and §6):
	 *
	 * - **lost**: the store was made and is gone. Nothing migrates silently and
	 *   nothing writes to it; each way back is an explicit choice: the daily
	 *   rows snapshot, a backup, the old copy (with its date and count), or
	 *   starting empty.
	 * - **unavailable**: the store won't open now. New shots are kept aside in
	 *   the pending journal; Try again opens it again.
	 * - **unsaved**: a shot's curve couldn't be written (storage full). It's
	 *   in memory and goes with each later save; Download keeps a copy now.
	 * - **moved**: this tab ran on the old copy and another tab moved the
	 *   history; it no longer writes, and must reload.
	 *
	 * While anything is unsaved, closing the tab asks first.
	 */
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import Icon from '#lib/icons/Icon.svelte';
	import { getHistoryStore } from '#lib/history/index.js';
	import { exportStoredShotAsV2Json } from '#lib/history/v2-export.js';
	import { shotFilename } from '#lib/history/model.js';
	import { downloadBlob } from '#lib/utils/download.js';
	import { confirmDialog } from '#lib/components/shared/confirm-dialog.svelte.js';
	import { toast } from '#lib/components/shared/toast.svelte.js';

	const history = getHistoryStore();
	let busy = $state(false);
	let snapshots = $state<{ name: string; at: number; rows: number }[]>([]);

	$effect(() => {
		if (history.health !== 'lost') return;
		void history.snapshotInfo().then((s) => (snapshots = s));
	});

	onMount(() => {
		const warn = (e: BeforeUnloadEvent) => {
			if (!history.hasUnsaved) return;
			e.preventDefault();
			e.returnValue = '';
		};
		window.addEventListener('beforeunload', warn);
		return () => window.removeEventListener('beforeunload', warn);
	});

	const dateOf = (ms: number) => (ms > 0 ? new Date(ms).toLocaleDateString() : 'an earlier day');

	async function run(what: () => Promise<unknown>, done: string): Promise<unknown> {
		if (busy) return false;
		busy = true;
		try {
			const result = await what();
			if (result === 'reopened') toast.info('Another tab had already brought your shot history back. Nothing was changed.');
			else if (history.health === 'ok') toast.success(done);
			else toast.error(history.storageError ?? 'That didn’t work. Try another way.');
			return result;
		} catch (e) {
			toast.error(e instanceof Error ? e.message : String(e));
			return false;
		} finally {
			busy = false;
		}
	}

	async function startEmpty(): Promise<void> {
		const ok = await confirmDialog({
			message: 'Start with an empty history? The old copy and any backups stay where they are.',
			confirmLabel: 'Start empty',
			danger: true
		});
		if (ok) await run(() => history.startEmpty(), 'History starts empty.');
	}

	async function restoreBackup(): Promise<void> {
		const ok = await confirmDialog({
			message: 'This starts an empty history, then opens Settings to restore a backup into it.',
			confirmLabel: 'Continue'
		});
		if (!ok) return;
		const result = await run(() => history.startEmpty(), 'Choose a backup to restore.');
		// Another tab's recovery is the history now: nothing to restore into.
		if (result !== 'reopened' && history.health === 'ok') void goto('/settings#sharing');
	}

	/** What the curve archive holds, as far as this browser allows one (#420 FC-N3). */
	const archiveLine = $derived.by(() => {
		const a = history.archiveStatus;
		if (!a) return 'A download holds the curves only of shots this tab opened or recorded last.';
		return `Curves are also kept in the curve archive, where this browser allows: ${a.files} of ${a.curves} shots’ curves are there, and whichever you choose takes them back.`;
	});

	function downloadUnsaved(): void {
		for (const shot of history.unsavedRows()) {
			const blob = new Blob([exportStoredShotAsV2Json(shot, { fullSeries: true })], { type: 'application/json' });
			downloadBlob(shotFilename(shot), blob);
		}
	}
</script>

{#if history.health === 'lost'}
	<div class="hs-strip" role="alert">
		<Icon cls="ph ph-warning-circle" aria-hidden="true" />
		<span class="hs-label">
			{#if history.markerUnreadable}
				Crema can’t tell where your shot history is kept in this browser. Nothing has been changed. Choose how to go on.
			{:else}
				Your shot history’s storage is missing from this browser. Nothing has been changed. Choose how to get it back.
			{/if}
			{#if history.unsavedShots.length > 0}
				{history.unsavedShots.length === 1 ? 'One shot is' : `${history.unsavedShots.length} shots are`} only in this tab: keep it open until you choose, and they go into what you choose. {archiveLine}
			{/if}
		</span>
		<div class="hs-actions">
			{#each snapshots as snap (snap.name)}
				<button type="button" class="hs-btn" disabled={busy} onclick={() => void run(() => history.recoverSnapshot(snap.name), 'History restored from the daily copy.')}>
					Daily copy ({dateOf(snap.at)}, {snap.rows} shots; curves from the archive where it has them)
				</button>
			{/each}
			{#if snapshots.length > 1}
				<button type="button" class="hs-btn" disabled={busy} onclick={() => void run(() => history.recoverSnapshot('all'), 'History restored from every daily copy.')}>
					Recover from all copies (merged, newest first; curves from the archive where it has them)
				</button>
			{/if}
			<button type="button" class="hs-btn" disabled={busy} onclick={() => void restoreBackup()}>Restore a backup</button>
			{#if history.oldCopy && history.oldCopy.rows > 0}
				<button type="button" class="hs-btn" disabled={busy} onclick={() => void run(() => history.recoverOldCopy(), 'History recovered from the old copy.')}>
					Old copy ({dateOf(history.oldCopy.at)}, {history.oldCopy.rows} shots)
				</button>
			{/if}
			<button type="button" class="hs-btn" disabled={busy} onclick={() => void startEmpty()}>Start empty</button>
			{#if history.unsavedShots.length > 0}
				<button type="button" class="hs-btn" onclick={downloadUnsaved}>
					{history.unsavedShots.length === 1 ? 'Download this shot' : 'Download these shots'}
				</button>
			{/if}
		</div>
	</div>
{:else if history.health === 'unavailable'}
	<div class="hs-strip" role="alert">
		<Icon cls="ph ph-warning-circle" aria-hidden="true" />
		<span class="hs-label">
			Your shot history can’t be opened right now. New shots are kept aside until it can.
		</span>
		<button type="button" class="hs-btn" disabled={busy} onclick={() => void run(() => history.retryOpen(), 'Shot history is back.')}>
			Try again
		</button>
	</div>
{:else if history.movedElsewhere}
	<div class="hs-strip" role="alert">
		<Icon cls="ph ph-warning-circle" aria-hidden="true" />
		<span class="hs-label">
			Your shot history moved to its new storage in another tab. Reload this tab to keep saving here.
		</span>
		<button type="button" class="hs-btn" onclick={() => location.reload()}>Reload</button>
	</div>
{:else if history.unsavedShots.length > 0}
	<div class="hs-strip" role="alert">
		<Icon cls="ph ph-warning-circle" aria-hidden="true" />
		<span class="hs-label">
			{history.unsavedShots.length === 1
				? 'This shot’s curve isn’t saved: storage is full.'
				: `${history.unsavedShots.length} shots’ curves aren’t saved: storage is full.`}
			It will save once there’s room.
		</span>
		<div class="hs-actions">
			<button type="button" class="hs-btn" onclick={downloadUnsaved}>
				{history.unsavedShots.length === 1 ? 'Download this shot' : 'Download these shots'}
			</button>
			<button type="button" class="hs-btn" onclick={() => void goto('/settings#sharing')}>Free up space</button>
		</div>
	</div>
{/if}

<style>
	.hs-strip {
		position: sticky;
		top: var(--ptop-clear, 0px);
		z-index: 30;
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 10px;
		padding: 8px 16px;
		background: var(--bg-surface-2);
		border-bottom: 1px solid var(--hairline);
		color: var(--fg-2);
		font-family: var(--font-sans);
		font-size: 13px;
	}
	.hs-label {
		flex: 1 1 240px;
		min-width: 0;
	}
	.hs-actions {
		display: flex;
		flex-wrap: wrap;
		gap: 8px;
	}
	.hs-btn {
		flex: 0 0 auto;
		min-height: 32px;
		padding: 4px 12px;
		border: 1px solid var(--hairline-strong);
		border-radius: 999px;
		background: var(--bg-surface);
		color: var(--fg-1);
		font: inherit;
		font-weight: 600;
		cursor: pointer;
	}
	.hs-btn:hover {
		border-color: var(--copper-400);
	}
	.hs-btn:focus-visible {
		outline: 2px solid var(--copper-400);
		outline-offset: 2px;
	}
	.hs-btn:disabled {
		opacity: 0.6;
		cursor: default;
	}
</style>
