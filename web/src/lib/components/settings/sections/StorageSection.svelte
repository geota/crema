<script lang="ts">
	/**
	 * Storage and backups (#311): how many raw captures this device keeps, and
	 * what it holds. Free up space (#310 P6w) deletes shots older than a date,
	 * only when asked: pinned shots are exempt, it offers a backup first and
	 * previews the count and size, and nothing reaches Visualizer. Device-local: not in backups, not relayed. Lowering the
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
	import { choiceDialog, confirmDialog } from '#lib/components/shared/confirm-dialog.svelte.js';
	import { freeUpPlan, rowBytes } from '#lib/history/free-up.js';
	import { HISTORY_DESIGN_SIZE } from '#lib/history/storage-watch.svelte.js';
	import { getHistoryStore } from '#lib/history/index.js';
	import { describeBackup, describeBackupGaps, exportBackup, lastBackupAt, noteBackupMade } from '#lib/backup/index.js';
	import {
		CAPTURE_KEEP_CHOICES,
		captureKeepLabel,
		readCaptureKeep,
		writeCaptureKeep,
		peekCaptureKeep,
		type CaptureKeepChoice
	} from '#lib/capture/limit.js';
	import { capturesOverLimit, pruneCaptures } from '#lib/capture/prune.js';
	import { getHistoryReads, liveCount, liveRows } from '#lib/history/reads.svelte.js';

	const history = getHistoryStore();
	const reads = getHistoryReads();
	// The history stores the choice once its rows load; before that this only
	// reads it, as a first read decides by whether the history has rows.
	let keep = $state<CaptureKeepChoice>(history.loaded ? readCaptureKeep(() => history.holdsAnyRow) : peekCaptureKeep());
	$effect(() => {
		if (history.loaded) keep = readCaptureKeep(() => history.holdsAnyRow);
	});
	let note = $state<string | null>(null);
	let used = $state<string | null>(null);
	// Every live row, past the view too (#310), counted by a query (W3).
	const pinnedCount = reads.watch(() => liveCount({ pinnedOnly: true }), 0);
	const liveTotal = reads.watch(() => liveCount(), 0);
	const pinned = $derived(history.loaded ? pinnedCount.value : 0);
	const persisted = $derived(storagePersisted());
	const historySub = $derived.by(() => {
		switch (history.health) {
			case 'ok': {
				const n = history.loaded ? liveTotal.value : 0;
				return n > HISTORY_DESIGN_SIZE
					? `Kept in this browser’s database, every shot: ${n.toLocaleString()}, past the ${HISTORY_DESIGN_SIZE.toLocaleString()} Crema is tuned for, so History may be slower. Nothing is removed on its own.`
					: 'Kept in this browser’s database, every shot.';
			}
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

	/** Free up space: the date as `yyyy-mm-dd`, a year ago to start with. */
	const isoDay = (ms: number) => {
		const d = new Date(ms);
		return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
	};
	let freeBefore = $state(isoDay(Date.now() - 365 * 24 * 3_600_000));
	let freeNote = $state<string | null>(null);
	const rowBytesOf = (id: string) => {
		const row = history.get(id);
		return row ? rowBytes(row) : 0;
	};
	let freeing = $state(false);

	/**
	 * Free up space: the shots older than the date, pinned ones kept. It offers
	 * a backup, then says how many and about how much before it deletes.
	 */
	async function freeUp(): Promise<void> {
		if (freeing || !history.loaded) return;
		const [y, m, d] = freeBefore.split('-').map(Number);
		if (!y || !m || !d) {
			freeNote = 'Pick a date.';
			return;
		}
		const before = new Date(y, m - 1, d).getTime();
		freeing = true;
		// Every live row, whole and from one state (#310 W3): the plan deletes by id.
		let rows;
		try {
			rows = await reads.answer(liveRows());
		} catch {
			freeing = false;
			freeNote = 'The shot history is still loading. Try again in a moment.';
			return;
		}
		const plan = freeUpPlan(rows, before);
		const day = new Date(before).toLocaleDateString();
		if (plan.ids.length === 0) {
			freeing = false;
			freeNote =
				plan.pinnedKept > 0
					? `Every shot older than ${day} is pinned, so there is nothing to delete.`
					: `No shots are older than ${day}.`;
			return;
		}
		try {
			const lastAt = lastBackupAt();
			const backUpFirst = await choiceDialog({
				title: 'Back up first?',
				message: `A backup keeps the shots this deletes, with their curves. Last backup: ${lastAt ? new Date(lastAt).toLocaleString() : 'never'}.`,
				primaryLabel: 'Back up first',
				secondaryLabel: 'Continue without'
			});
			if (backUpFirst === null) return;
			let ids = plan.ids;
			let backedUp = '';
			let notInBackup = '';
			if (backUpFirst === 'primary') {
				freeNote = 'Building backup…';
				const made = await exportBackup().catch(() => null);
				if (!made) {
					freeNote = 'The backup didn’t finish, so nothing was deleted.';
					return;
				}
				noteBackupMade();
				backedUp = `Backed up — ${describeBackup(made)}.${describeBackupGaps(made)}`;
				freeNote = backedUp;
				// Only what the backup holds whole may go (#437 B1): a shot it left
				// out, or wrote without its curve, stays.
				const omittedShots = (made.omitted ?? []).filter((o) => o.kind === 'shot');
				const noCurveIds = made.noCurveIds ?? [];
				if (
					(made.missing ?? []).includes('shot history') ||
					omittedShots.some((o) => !o.id) ||
					(made.noCurves ?? 0) > noCurveIds.length
				) {
					freeNote = `${backedUp} It couldn’t say which shots it holds whole, so nothing was deleted.`;
					return;
				}
				const gaps = new Set([...omittedShots.map((o) => o.id!), ...noCurveIds]);
				ids = plan.ids.filter((id) => !gaps.has(id));
				const held = plan.ids.length - ids.length;
				if (held > 0) {
					notInBackup = ` ${held === 1 ? 'One of these shots' : `${held} of these shots`} couldn’t be backed up and ${held === 1 ? 'was' : 'were'} kept.`;
				}
				if (ids.length === 0) {
					freeNote = `${backedUp}${notInBackup} Nothing was deleted.`;
					return;
				}
			}
			const n = ids.length;
			const kept =
				(plan.pinnedKept > 0 ? ` ${plan.pinnedKept} pinned shot${plan.pinnedKept === 1 ? '' : 's'} older than that stay.` : '') +
				notInBackup;
			const ok = await confirmDialog({
				title: 'Free up space',
				message:
					`Delete ${n.toLocaleString()} shot${n === 1 ? '' : 's'} older than ${day} from this device, about ${formatBytes(n === plan.ids.length ? plan.bytes : ids.reduce((b, id) => b + rowBytesOf(id), 0))}?${kept} ` +
					'Copies on Visualizer stay there and won’t come back with a sync. Bag weights don’t change. This can’t be undone.',
				confirmLabel: `Delete ${n.toLocaleString()} shot${n === 1 ? '' : 's'}`,
				danger: true
			});
			if (!ok) return;
			const { removed, skipped } = await history.freeUpSpace(ids);
			freeNote =
				(backedUp ? `${backedUp} ` : '') +
				`Deleted ${removed.toLocaleString()} shot${removed === 1 ? '' : 's'}.` +
				notInBackup +
				(skipped > 0 ? ` ${skipped} waiting to upload or changed meanwhile were kept.` : '');
		} catch (e) {
			freeNote = e instanceof Error ? e.message : String(e);
		} finally {
			freeing = false;
		}
	}

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
	{#if history.health === 'ok' || history.health === 'legacy'}
		<StRow
			title="Free up space"
			sub={freeNote ??
				'Delete shots older than a date from this device. Pinned shots stay, Visualizer copies stay, and nothing is ever deleted on its own.'}
		>
			{#snippet control()}
				<input
					class="st-free-date"
					type="date"
					aria-label="Delete shots older than"
					bind:value={freeBefore}
					max={isoDay(Date.now())}
				/>
				<StButton label={freeing ? 'Working…' : 'Free up space…'} disabled={freeing} onClick={() => void freeUp()} />
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
	.st-free-date {
		min-height: 32px;
		padding: 4px 8px;
		border: 1px solid var(--hairline-strong);
		border-radius: 8px;
		background: var(--bg-surface);
		color: var(--fg-1);
		font: inherit;
		font-size: 13px;
	}
	.st-storage-value {
		font-family: var(--font-mono);
		font-size: 13px;
		color: var(--fg-2);
	}
</style>
