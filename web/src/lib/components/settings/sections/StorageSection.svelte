<script lang="ts">
	/**
	 * Storage and backups (#311): how many raw captures this device keeps, and
	 * what it holds. Device-local: not in backups, not relayed. Lowering the
	 * limit shows what it would delete before it does, because a raw capture
	 * can't be rebuilt. Automatic Drive backups and their copies are Android's
	 * for now: web Drive renewal needs a click, so it has nothing unattended to
	 * prune.
	 */
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StSelect from '../StSelect.svelte';
	import StButton from '../StButton.svelte';
	import { storagePersisted } from '#lib/history/storage-persist.svelte.js';
	import { retryMigrationSoon } from '#lib/history/store-marker.js';
	import { confirmDialog } from '#lib/components/shared/confirm-dialog.svelte.js';
	import { getHistoryStore } from '#lib/history/index.js';
	import { lastBackupAt } from '#lib/backup/index.js';
	import {
		CAPTURE_KEEP_CHOICES,
		captureKeepLabel,
		readCaptureKeep,
		writeCaptureKeep,
		peekCaptureKeep,
		type CaptureKeepChoice
	} from '#lib/capture/limit.js';
	import { capturesOverLimit, pruneCaptures } from '#lib/capture/prune.js';

	const history = getHistoryStore();
	// The history stores the choice once its rows load; before that this only
	// reads it, as a first read decides by whether the history has rows.
	let keep = $state<CaptureKeepChoice>(history.loaded ? readCaptureKeep(() => history.rawAll.length > 0) : peekCaptureKeep());
	$effect(() => {
		if (history.loaded) keep = readCaptureKeep(() => history.rawAll.length > 0);
	});
	let note = $state<string | null>(null);
	let used = $state<string | null>(null);
	// Every live row, past the view too (#310).
	const pinned = $derived(history.loaded ? history.allLive.filter((s) => s.pinned === true).length : 0);
	const persisted = $derived(storagePersisted());
	const historySub = $derived.by(() => {
		switch (history.health) {
			case 'ok':
				return 'Kept in this browser’s database, every shot.';
			case 'loading':
				return 'Loading…';
			case 'legacy':
				return history.storageError
					? `In the older storage, which keeps the newest 300 shots. Moving it didn’t work: ${history.storageError}`
					: 'In the older storage, which keeps the newest 300 shots.';
			case 'unavailable':
				return 'The history’s storage can’t be opened right now. New shots are kept aside until it can.';
			case 'lost':
				return 'The history’s storage is missing. See the banner at the top to choose what to do.';
		}
	});

	/** The curve archive, on its own line (#420): its files and size against the shots with a curve. */
	const archive = $derived(history.archiveStatus);
	$effect(() => {
		if (history.loaded) void history.countArchiveNow();
	});
	const archiveTitle = $derived(
		archive && (archive.waiting || archive.notRestored > 0 || (history.archiveCaughtUp && archive.files < archive.curves))
			? 'Curve archive incomplete'
			: 'Curve archive'
	);
	const archiveSub = $derived.by(() => {
		const a = archive;
		if (a === undefined) return 'Counting…';
		if (!a) return 'This browser keeps no curve archive, so a lost history would get back only its rows.';
		const missing = Math.max(0, a.curves - a.files);
		const damaged =
			a.notRestored > 0
				? ` ${a.notRestored === 1 ? 'One curve' : `${a.notRestored} curves`} couldn’t be restored from it: ${a.notRestored === 1 ? 'its file was' : 'their files were'} damaged.`
				: '';
		const held = `${a.files} of ${a.curves} shots’ curves are archived.${damaged}`;
		if (a.waiting) {
			return `${held} ${missing} wait: the last write failed, most likely because storage is full. Crema tries again with each save.`;
		}
		if (!history.archiveCaughtUp && missing > 0) return `${held} The rest are being archived now.`;
		if (missing > 0) return `${held} ${missing} aren’t yet; Crema tries again at the next start.`;
		return `${held} A second copy of each curve, so a lost history gets its curves back.`;
	});

	function tryMoveAgain(): void {
		retryMigrationSoon();
		note = 'Shot history will try to move again the next time Crema opens.';
	}
	const last = lastBackupAt();

	$effect(() => {
		void navigator.storage?.estimate?.().then((e) => {
			if (e.usage != null) used = formatBytes(e.usage);
		});
	});

	function formatBytes(n: number): string {
		if (n < 1024 * 1024) return `${Math.max(1, Math.round(n / 1024))} KB`;
		if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`;
		return `${(n / (1024 * 1024 * 1024)).toFixed(2)} GB`;
	}

	const options = CAPTURE_KEEP_CHOICES.map((c) => ({ value: c, label: captureKeepLabel(c) }));

	/**
	 * Change the limit. One that deletes captures says how many first, and
	 * only then saves and prunes.
	 */
	async function choose(next: string): Promise<void> {
		const choice = next as CaptureKeepChoice;
		if (choice === keep) return;
		const doomed = await capturesOverLimit(history, choice);
		if (doomed.length > 0) {
			const n = doomed.length;
			const ok = await confirmDialog({
				message:
					`This deletes the raw captures of ${n} older shot${n === 1 ? '' : 's'} now. ` +
					"Raw captures can't be rebuilt; the shots and their curves stay. Pinned shots keep theirs.",
				confirmLabel: 'Delete captures',
				danger: true
			});
			if (!ok) return;
		}
		writeCaptureKeep(choice);
		keep = choice;
		const gone = await pruneCaptures(history, choice);
		note = gone.length > 0 ? `Deleted ${gone.length} raw capture${gone.length === 1 ? '' : 's'}.` : null;
	}
</script>

<StGroup
	title="Storage and backups"
	sub="What this device keeps. Pinned shots are never cleaned up."
>
	<StRow
		title="Raw captures to keep"
		sub={note ??
			'The newest shots keep their raw Bluetooth capture, for replays and bug reports. Pinned shots keep theirs whatever the limit.'}
	>
		{#snippet control()}
			<StSelect value={keep} {options} onChange={(v) => void choose(v)} />
		{/snippet}
	</StRow>
	<StRow title="Shot history" sub={historySub}>
		{#snippet control()}
			{#if history.health === 'legacy'}
				<StButton label="Try again" onClick={tryMoveAgain} />
			{:else if history.health === 'unavailable'}
				<StButton label="Try again" onClick={() => void history.retryOpen()} />
			{/if}
		{/snippet}
	</StRow>
	<StRow
		title="Browser storage"
		sub={persisted === true
			? 'Protected: the browser won’t clear Crema’s data on its own.'
			: persisted === false
				? 'Not protected: the browser may clear Crema’s data when the disk is low. Keep a recent backup.'
				: 'The browser didn’t say whether it keeps Crema’s data. Keep a recent backup.'}
	>
		{#snippet control()}
			<span class="st-storage-value">{persisted === true ? 'Protected' : persisted === false ? 'Not protected' : '—'}</span>
		{/snippet}
	</StRow>
	<StRow title="Storage used" sub="Everything Crema stores in this browser.">
		{#snippet control()}
			<span class="st-storage-value">{used ?? '—'}</span>
		{/snippet}
	</StRow>
	{#if history.health !== 'legacy'}
		<!-- The older storage keeps its curves in its own database. -->
		<StRow title={archiveTitle} sub={archiveSub}>
			{#snippet control()}
				<span class="st-storage-value" data-testid="curve-archive-size">{archive ? formatBytes(archive.bytes) : '—'}</span>
			{/snippet}
		</StRow>
	{/if}
	<StRow title="Pinned shots" sub="Pin a shot from its detail in History.">
		{#snippet control()}
			<span class="st-storage-value">{pinned}</span>
		{/snippet}
	</StRow>
	<StRow title="Last backup" sub="A backup made on this device, to a file or to Google Drive.">
		{#snippet control()}
			<span class="st-storage-value">{last ? new Date(last).toLocaleString() : 'Never'}</span>
		{/snippet}
	</StRow>
</StGroup>

<style>
	.st-storage-value {
		font-family: var(--font-mono);
		font-size: 13px;
		color: var(--fg-2);
	}
</style>
