<script lang="ts">
	/**
	 * History — the `/history` route: the shot library, ported from
	 * `HistoryPage` in `history-page.jsx`.
	 *
	 * The stats strip, the filter strip (per-profile chips + a range selector)
	 * and the split pane — a scrollable `ShotRow` list and a `ShotDetail` pane.
	 *
	 * ## Real vs. stubbed
	 *
	 * The history is **real**: shots are recorded to `localStorage` by the
	 * orchestrator's `ShotCompleted` handler (`lib/state/app.svelte.ts`) and
	 * read here from the `lib/history` store — there is no mock data. The
	 * `ShotDetail` curve chart redraws the **stored** telemetry series. Tasting
	 * notes and the star rating are editable and persist to the same store.
	 *
	 * Until a shot has been pulled the page shows a genuine empty state.
	 *
	 * Save-as-profile from a history row is a planned follow-on.
	 */
	import {
		getHistoryStore,
		exportStoredShotAsV2Json,
		shotFilename,
		peaksOf
	} from '$lib/history';
	import type { StoredShot } from '$lib/history';
	import { ShotRow, ShotDetail, CompareOverlay } from '$lib/components/history';
	import ShotImportDialog from '$lib/components/history/ShotImportDialog.svelte';
	import { getCremaAppContext } from '$lib/shell/app-context';
	import { getSettingsStore, convertWeight } from '$lib/settings';
	import { getCaptureStore, captureJsonl } from '$lib/capture';
	import { downloadBlob, filenameStamp } from '$lib/utils/download';
	import { zip as fflateZip, strToU8 } from 'fflate';
	import FilterPills from '$lib/components/shared/FilterPills.svelte';
	import SortPill from '$lib/components/shared/SortPill.svelte';
	import SplitButton from '$lib/components/shared/SplitButton.svelte';
	import { getBeanStore } from '$lib/bean';
	import { onMount } from 'svelte';
	import { appendSyncLog, directionPushes, readSyncConfig } from '$lib/visualizer';
	import { readQueue } from '$lib/services/queue-store';
	import { toast } from '$lib/components/shared/toast.svelte';
	import { confirmDialog } from '$lib/components/shared/confirm-dialog.svelte';

	const store = getHistoryStore();
	const appCtx = getCremaAppContext();
	const settings = getSettingsStore();
	const beanLibrary = getBeanStore();

	/**
	 * Resolve a shot's effective tag list for filter facets / matching.
	 * Reads `shot.tags` first (the persisted single-source-of-truth),
	 * then falls back to the live bean's tags via the snapshot's
	 * `beanId` FK. Historical shots that completed before bean tags
	 * propagated to `shot.tags` (and shots whose bean tags were edited
	 * later) still surface their bean tags in the /history filter —
	 * read-only, no persistence side effect.
	 *
	 * Live-bean lookup is acceptable here ONLY because this is a pure
	 * UI filter — it never writes the result back into the snapshot,
	 * so a later bean rename / retag doesn't rewrite history.
	 */
	function effectiveShotTags(shot: StoredShot): readonly string[] {
		if (shot.tags && shot.tags.length > 0) return shot.tags;
		const beanId = shot.bean?.beanId ?? null;
		if (!beanId) return [];
		return beanLibrary.getBean(beanId)?.tags ?? [];
	}

	/**
	 * Outcome of the most recent import — `null` when no import has been
	 * attempted in this session, a banner-shaped object otherwise. Picked
	 * up by the toast strip below the page header.
	 */
	let importBanner = $state<
		| { kind: 'success'; message: string }
		| { kind: 'error'; message: string }
		| null
	>(null);
	/** Whether an import is currently running — disables the picker. */
	let importing = $state(false);

	/**
	 * Hand-off from the hidden `<input type="file">` to the CremaApp
	 * importer. Iterates the picked files so the user can drop a whole
	 * `history/` folder once and add every shot in one click.
	 */
	/**
	 * Whether a bulk export is in flight — the zip path is async (each
	 * shot's raw capture is an IndexedDB lookup) so the button shows a
	 * spinner while the archive builds.
	 */
	let exporting = $state(false);

	/**
	 * Bulk Export — two formats surfaced via the Export split-button:
	 *
	 * - **Community v2 (default click)** — every shot collapses into
	 *   one `.jsonl` file with one community-v2 JSON per line. Small,
	 *   single-file, re-importable by every DE1 app.
	 * - **Replayable capture** — `.zip` of per-shot `.jsonl` raw BLE
	 *   captures. Shots without raw bytes (imports, pre-recorder)
	 *   are skipped with a count logged.
	 */
	async function exportAllCommunity(): Promise<void> {
		if (shots.length === 0 || exporting) return;
		exporting = true;
		try {
			exportAllAsV2Jsonl();
		} catch (err) {
			console.error('History export failed', err);
			toast.error(`Export failed: ${err instanceof Error ? err.message : String(err)}`);
		} finally {
			exporting = false;
		}
	}
	async function exportAllReplay(): Promise<void> {
		if (shots.length === 0 || exporting) return;
		exporting = true;
		try {
			await exportAllAsReplayZip();
		} catch (err) {
			console.error('History replay export failed', err);
			toast.error(`Export failed: ${err instanceof Error ? err.message : String(err)}`);
		} finally {
			exporting = false;
		}
	}

	function exportAllAsV2Jsonl(): void {
		const jsonl = shots
			.map((s) => exportStoredShotAsV2Json(s))
			.map((s) => JSON.stringify(JSON.parse(s)))
			.join('\n');
		const blob = new Blob([jsonl], { type: 'application/x-ndjson' });
		downloadBlob(`crema-history-${filenameStamp()}.jsonl`, blob);
	}

	/**
	 * Walk every recorded shot, fetch its raw BLE capture from
	 * IndexedDB, and pack the captured ones into a single `.zip` of
	 * `.jsonl` files. Shots with no capture (imports + pre-recorder
	 * shots) are SKIPPED so the archive stays a pure replay bundle —
	 * mixing v2 JSON in would confuse downstream tools that expect
	 * `.jsonl` everywhere. The skipped count surfaces in the export
	 * banner so the user knows which shots are missing.
	 *
	 * Filename inside the zip is `{shotStamp}.jsonl`, where the stamp
	 * comes from `shotFilename` minus its `.shot.json` suffix.
	 */
	async function exportAllAsReplayZip(): Promise<void> {
		const captureStore = getCaptureStore();
		// fflate's `zip` takes a plain `{ filename: Uint8Array }` map +
		// a callback. Pre-resolve every shot's bytes serially so the
		// IndexedDB awaits don't fight the zip's worker.
		const files: Record<string, Uint8Array> = {};
		let skipped = 0;
		for (const shot of shots) {
			const entries = await captureStore.get(shot.id).catch(() => null);
			if (!entries || entries.length === 0) {
				skipped += 1;
				continue;
			}
			const base = shotFilename(shot).replace(/\.shot\.json$/, '');
			files[`${base}.jsonl`] = strToU8(captureJsonl(entries));
		}
		const included = Object.keys(files).length;
		if (included === 0) {
			console.log(
				`[history export] No replayable captures found. ${skipped} shot${
					skipped === 1 ? '' : 's'
				} have no raw bytes — use the Export dropdown's "Community v2" option to export them.`
			);
			return;
		}
		const zipped: Uint8Array = await new Promise((resolve, reject) => {
			fflateZip(files, (err, data) => (err ? reject(err) : resolve(data)));
		});
		const blob = new Blob([new Uint8Array(zipped)], { type: 'application/zip' });
		downloadBlob(`crema-history-${filenameStamp()}.zip`, blob);
		console.log(
			`[history export] Exported ${included} replayable shot${included === 1 ? '' : 's'}` +
				(skipped > 0
					? `; skipped ${skipped} without raw bytes (imported or pre-recorder).`
					: '.')
		);
	}

	let importOpen = $state(false);

	async function runImport(files: File[]): Promise<void> {
		const app = appCtx().app;
		if (!app || files.length === 0) return;
		importing = true;
		importBanner = null;
		let imported = 0;
		const errors: string[] = [];
		for (const file of files) {
			const { record, error } = await app.importShotFile(file);
			if (record) {
				imported += 1;
			} else if (error) {
				errors.push(error);
			}
		}
		importing = false;
		if (imported > 0 && errors.length === 0) {
			importBanner = {
				kind: 'success',
				message: `Imported ${imported} shot${imported === 1 ? '' : 's'}.`
			};
		} else if (imported > 0) {
			importBanner = {
				kind: 'success',
				message: `Imported ${imported} of ${imported + errors.length}. ${errors[0]}`
			};
		} else {
			importBanner = {
				kind: 'error',
				message: errors[0] ?? 'No shots imported.'
			};
		}
	}

	/** Every recorded shot, newest first. Reactive. */
	const shots = $derived(store.all);

	// ── Visualizer upload state ──────────────────────────────────────────
	let syncConfig = $state(readSyncConfig());
	let uploadingAll = $state(false);
	/**
	 * Connection gate (Option 3, T-16). `TokenVault.getTokens !== null`, read
	 * through the runtime into a `$state` — the old `isConnected()` was likewise
	 * a non-reactive read evaluated at mount + on each sync-state refresh.
	 */
	let connected = $state(false);
	/** Reactive count of queued shot ops — drives the pip + Upload-all label. */
	let pendingShotIds = $state<Set<string>>(
		new Set(readQueue().entries.filter((q) => q.entity === 'shot').map((q) => q.id))
	);
	async function refreshConnected(): Promise<void> {
		connected = (await appCtx().services?.tokens.isConnected()) ?? false;
	}
	// SV1: a sign-in / sign-out from any surface propagates here (the
	// subscription's first emission also seeds the current state).
	$effect(() => appCtx().services?.tokens.onConnectionChange((c) => (connected = c)));
	function refreshSyncState(): void {
		syncConfig = readSyncConfig();
		pendingShotIds = new Set(
			readQueue().entries.filter((q) => q.entity === 'shot').map((q) => q.id)
		);
		void refreshConnected();
	}
	onMount(() => {
		void refreshConnected();
	});
	/** Pip kind for the per-row indicator. */
	function pipFor(shot: { id: string; visualizerId?: string | null }): 'uploaded' | 'pending' | 'local' {
		if (shot.visualizerId) return 'uploaded';
		if (pendingShotIds.has(shot.id)) return 'pending';
		return 'local';
	}
	/** Are we connected + push-enabled? Gates the "Upload all" button. */
	const canPushShots = $derived(connected && directionPushes(syncConfig.direction.shots));
	/** Shots that need an upload — no `visualizerId` and not soft-deleted. */
	const unsyncedShots = $derived(shots.filter((s) => !s.visualizerId));

	async function uploadAll(): Promise<void> {
		if (uploadingAll || !canPushShots) return;
		const api = appCtx().services;
		if (!api) return;
		uploadingAll = true;
		try {
			// `shots.uploadUnsynced` reproduces the old per-shot loop: upload +
			// bind + sync-log each unsynced shot, routing recoverable failures to
			// the retry queue; then drain whatever it enqueued.
			await api.shots.uploadUnsynced(store);
			await api.queue.drain();
		} finally {
			refreshSyncState();
			uploadingAll = false;
		}
	}

	/**
	 * Delete a shot. `remote: false` removes it from the local history only
	 * (a synced shot is tombstoned so it never re-pulls; an unsynced one is
	 * hard-removed). `remote: true` additionally enqueues a Visualizer DELETE
	 * and drains it now, so the uploaded copy is removed too.
	 */
	async function handleDelete(shot: StoredShot, opts: { remote: boolean }): Promise<void> {
		const label = shot.profileName ?? 'this shot';
		const msg = opts.remote
			? `Delete "${label}" from this device and Visualizer? This cannot be undone.`
			: `Delete "${label}" from this device? This cannot be undone.`;
		if (!(await confirmDialog({ message: msg, confirmLabel: 'Delete', danger: true }))) return;
		const visualizerId = shot.visualizerId ?? null;
		const wasSelected = selected?.id === shot.id;
		store.delete(shot.id);
		const api = appCtx().services;
		if (opts.remote && visualizerId && api) {
			try {
				await api.queue.enqueue({ entity: 'shot', id: shot.id, op: 'delete', visualizerId });
				await api.queue.drain();
			} catch (e) {
				console.error('[history] Visualizer delete failed', e);
			}
		}
		// Drop the selection so the detail pane falls back to the next shot.
		if (wasSelected) selectedId = null;
		refreshSyncState();
	}

	// ── Sort state ───────────────────────────────────────────────────────
	type SortField = 'completedAt' | 'rating' | 'profileName' | 'beanName';
	type SortDir = 'asc' | 'desc';
	let sortField = $state<SortField>('completedAt');
	let sortDir = $state<SortDir>('desc');
	const sortOptions: { field: SortField; label: string; icon: string }[] = [
		{ field: 'completedAt', label: 'Date', icon: 'calendar' },
		{ field: 'rating', label: 'Rating', icon: 'star' },
		{ field: 'profileName', label: 'Profile', icon: 'circle' },
		{ field: 'beanName', label: 'Bean', icon: 'coffee' }
	];
	/**
	 * Default direction per field so a fresh pick lands on the obvious
	 * choice — newest-first for date, highest-first for rating, a-z for
	 * the strings — instead of inheriting the previous field's direction.
	 */
	const DEFAULT_DIR: Record<SortField, SortDir> = {
		completedAt: 'desc',
		rating: 'desc',
		profileName: 'asc',
		beanName: 'asc'
	};

	// ── Filter / search state ────────────────────────────────────────────
	/** The search query. */
	let q = $state('');
	/** The active profile filter — `all` or a profile name. */
	let filterProfile = $state('all');
	/**
	 * Tags the user has pinned on. AND semantics (a shot matches when
	 * every selected tag is in its `tags` array) — mirrors `/profiles`
	 * and `/beans`. An empty list means "no tag filter applied".
	 */
	let selectedTags = $state<string[]>([]);
	/** The active date-range filter — `30d` keeps only the last 30 days. */
	let range = $state<'30d' | 'all'>('all');
	/** The selected shot's id. */
	let selectedId = $state<string | null>(null);

	/** Milliseconds in a day — shared by the range filter and the stats. */
	const dayMs = 24 * 60 * 60 * 1000;

	/** Distinct profile names across the history, for the filter chips. */
	const profilesInUse = $derived([
		...new Set(shots.map((s) => s.profileName).filter((n): n is string => !!n))
	]);

	/**
	 * Distinct tags across the history, counted, sorted by frequency
	 * then alphabetically. Drives the tag-pill facets in the filter
	 * rail. Mirrors `/beans` `tagFacets` so the visual rhythm matches.
	 *
	 * Reads through `effectiveShotTags` so shots that pre-date the
	 * bean→shot tag copy (and the rare retroactive bean retag) still
	 * surface their bean's tags in the facet list.
	 */
	const tagFacets = $derived.by(() => {
		const m = new Map<string, number>();
		for (const s of shots) {
			for (const t of effectiveShotTags(s)) m.set(t, (m.get(t) ?? 0) + 1);
		}
		return [...m.entries()]
			.map(([tag, count]) => ({ tag, count }))
			.sort((a, b) => b.count - a.count || a.tag.localeCompare(b.tag));
	});

	/**
	 * Pill list for the filter rail — wired through the shared
	 * `FilterPills` component. Two id-prefixed groups: `p:` (profile,
	 * single-select) and `t:` (custom tag, multi-select AND). The
	 * dispatcher routes by prefix; same shape as `/beans`.
	 */
	interface FilterPillItem {
		id: string;
		label?: string;
		count?: number;
		selected?: boolean;
		icon?: string;
		iconStyle?: string;
		divider?: boolean;
		groupLabel?: string;
		custom?: boolean;
		title?: string;
	}
	const filterPills = $derived.by(() => {
		const items: FilterPillItem[] = [
			{
				id: 'p:all',
				label: 'All profiles',
				count: shots.length,
				selected: filterProfile === 'all'
			}
		];
		for (const name of profilesInUse) {
			items.push({
				id: `p:${name}`,
				label: name,
				count: shots.filter((s) => s.profileName === name).length,
				selected: filterProfile === name
			});
		}
		if (tagFacets.length > 0) {
			items.push({ id: '__divtags', divider: true });
			items.push({ id: '__tags', groupLabel: 'Tags' });
			for (const t of tagFacets) {
				items.push({
					id: `t:${t.tag}`,
					label: t.tag,
					count: t.count,
					selected: selectedTags.includes(t.tag),
					custom: true
				});
			}
		}
		return items;
	});

	function onFilterPillClick(id: string): void {
		if (id.startsWith('p:')) {
			const v = id.slice(2);
			filterProfile = v === 'all' ? 'all' : v;
		} else if (id.startsWith('t:')) {
			const t = id.slice(2);
			selectedTags = selectedTags.includes(t)
				? selectedTags.filter((x) => x !== t)
				: [...selectedTags, t];
		}
	}

	/** The filtered shot list the list pane renders. */
	const filtered = $derived.by(() => {
		const query = q.trim().toLowerCase();
		// The "Last 30 days" range cuts off shots older than the window.
		const cutoff = range === '30d' ? Date.now() - 30 * dayMs : null;
		const base = shots.filter((s) => {
			if (cutoff != null && s.completedAt < cutoff) return false;
			if (filterProfile !== 'all' && s.profileName !== filterProfile) return false;
			// AND semantics — a shot must carry every selected tag.
			// Pulls through `effectiveShotTags` so legacy / retroactive
			// bean tags filter just like persisted shot tags.
			if (selectedTags.length > 0) {
				const shotTags = effectiveShotTags(s);
				for (const t of selectedTags) {
					if (!shotTags.includes(t)) return false;
				}
			}
			if (query === '') return true;
			return (
				(s.profileName ?? '').toLowerCase().includes(query) ||
				(s.metadata.notes ?? '').toLowerCase().includes(query) ||
				effectiveShotTags(s).some((t) => t.toLowerCase().includes(query))
			);
		});
		// Sort AFTER filtering. The default `recent / desc` reproduces the
		// store's native newest-first order; flipping to `asc` reverses;
		// other fields stable-sort within the filtered subset.
		const dir = sortDir === 'asc' ? 1 : -1;
		const cmpStr = (a: string | null, b: string | null): number => {
			const ax = (a ?? '').toLowerCase();
			const bx = (b ?? '').toLowerCase();
			return ax < bx ? -1 : ax > bx ? 1 : 0;
		};
		const cmpNum = (a: number, b: number): number => a - b;
		const arr = [...base];
		arr.sort((a, b) => {
			let k = 0;
			switch (sortField) {
				case 'completedAt':
					k = cmpNum(a.completedAt, b.completedAt);
					break;
				case 'rating':
					k = cmpNum(a.metadata.rating ?? 0, b.metadata.rating ?? 0);
					break;
				case 'profileName':
					k = cmpStr(a.profileName, b.profileName);
					break;
				case 'beanName':
					k = cmpStr(a.bean?.name ?? null, b.bean?.name ?? null);
					break;
			}
			// Tie-break on completedAt desc so equal-keyed rows stay
			// chronologically ordered (newest-first) within their bucket.
			return k * dir || b.completedAt - a.completedAt;
		});
		return arr;
	});

	/** The selected shot record — the first filtered shot when none is picked. */
	const selected = $derived(
		(selectedId ? store.get(selectedId) : undefined) ?? filtered[0]
	);

	// ── Stats ────────────────────────────────────────────────────────────
	/** Shots pulled today. */
	const todayCount = $derived(
		shots.filter((s) => Date.now() - s.completedAt < dayMs).length
	);
	/** Shots pulled in the last 7 days. */
	const weekCount = $derived(
		shots.filter((s) => Date.now() - s.completedAt < 7 * dayMs).length
	);
	/**
	 * Mean yield weight (grams) across the history. Reported as a weight, not a
	 * ratio: a `yield ÷ dose` ratio would have to assume one fixed dose and
	 * mislead for every other (pre-`dose` records aside, the dose varies per
	 * shot).
	 */
	const avgYield = $derived.by(() => {
		const yields = shots
			.map((s) => {
				const p = peaksOf(s);
				return p.finalWeight ?? p.peakWeight;
			})
			.filter((y): y is number => y != null && y > 0);
		if (yields.length === 0) return null;
		const mean = yields.reduce((a, y) => a + y, 0) / yields.length;
		return convertWeight(mean, settings.current.weightUnit);
	});
	/** Mean shot duration, seconds. */
	const avgTime = $derived.by(() => {
		if (shots.length === 0) return null;
		const mean =
			shots.reduce((a, s) => a + s.record.duration, 0) / shots.length / 1000;
		return Math.round(mean);
	});
	/** Mean star rating across rated shots. */
	const avgRating = $derived.by(() => {
		const rated = shots.filter((s) => (s.metadata.rating ?? 0) > 0);
		if (rated.length === 0) return null;
		return (
			rated.reduce((a, s) => a + (s.metadata.rating ?? 0), 0) / rated.length
		).toFixed(1);
	});

	/** Select a shot. */
	function select(id: string): void {
		selectedId = id;
	}

	// ── Compare state ────────────────────────────────────────────────────
	/**
	 * Hard cap on the number of shots that may be overlaid at once. Beyond
	 * five the lines stop being distinguishable; this matches the colour
	 * palette in `CompareOverlay`.
	 */
	const COMPARE_MAX = 5;
	/** Whether the list is in compare-select mode. */
	let selectMode = $state(false);
	/**
	 * Ids picked for compare. Insertion order is preserved (it drives the
	 * compare overlay's colour assignment), and stays sorted by selection
	 * time — first-picked = first colour.
	 */
	let selectedIds = $state<string[]>([]);
	/** Whether the compare overlay is open. */
	let compareOpen = $state(false);

	const selectedIdSet = $derived(new Set(selectedIds));

	/** Enter compare-select mode (or exit it if already in). */
	function toggleSelectMode(): void {
		if (selectMode) {
			selectMode = false;
			selectedIds = [];
		} else if (shots.length >= 2) {
			selectMode = true;
		}
	}

	/** Pick or unpick a shot for compare, capped at {@link COMPARE_MAX}. */
	function toggleCompareSelection(id: string): void {
		if (selectedIdSet.has(id)) {
			selectedIds = selectedIds.filter((x) => x !== id);
		} else if (selectedIds.length < COMPARE_MAX) {
			selectedIds = [...selectedIds, id];
		}
	}

	/** Open the compare overlay — only meaningful with ≥2 selected. */
	function openCompare(): void {
		if (selectedIds.length < 2) return;
		compareOpen = true;
	}

	function closeCompare(): void {
		compareOpen = false;
	}

	/** The selected shots, in selection order, resolved from the store. */
	const compareShots = $derived(
		selectedIds
			.map((id) => store.get(id))
			.filter((s): s is NonNullable<typeof s> => s != null)
	);
</script>

<svelte:head>
	<title>Crema — History</title>
</svelte:head>

<div class="qcontain hi-page">
	<!-- Header -->
	<div class="hi-head">
		<div>
			<div class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.55)">Library</div>
			<div class="t-page-title hi-title">Shot history</div>
			<div class="hi-sub">
				{shots.length}
				{shots.length === 1 ? 'shot' : 'shots'} on this device
			</div>
		</div>
		<div class="hi-head-r">
			<div class="hi-search">
				<i class="ph ph-magnifying-glass" aria-hidden="true"></i>
				<input bind:value={q} placeholder="Search profile, notes…" />
			</div>
			<!-- Import: opens the ShotImportDialog so users get a
			     draggable picker that matches the BeanImportDialog
			     pattern. The dialog calls `runImport` with the chosen
			     files; the banner below the header reports the result. -->
			<button
				class="st-btn st-btn-secondary"
				disabled={importing}
				onclick={() => (importOpen = true)}
				title="Import legacy de1app .shot or .shot.json files"
			>
				<i class="ph ph-upload-simple" aria-hidden="true"></i>
				<span>{importing ? 'Importing…' : 'Import'}</span>
			</button>
			<!-- Export split-button — primary fires community v2 bulk
			     export; caret surfaces the replay-capture zip variant.
			     Shares geometry with /beans via the SplitButton primitive. -->
			<SplitButton
				icon="ph ph-download-simple"
				label={exporting ? 'Exporting…' : 'Export'}
				title="Download every shot as one .jsonl file — community-v2 JSON, one shot per line"
				disabled={shots.length === 0 || exporting}
				onPrimary={() => void exportAllCommunity()}
				caretAriaLabel="Choose export format"
				menuHead="Export as"
				items={[
					{
						icon: 'ph-duotone ph-file-text',
						title: 'Community v2 (.jsonl)',
						sub: 'One JSON per shot, one shot per line. Portable across reaprime / Visualizer / de1app.',
						onclick: () => void exportAllCommunity()
					},
					{
						icon: 'ph-duotone ph-file-zip',
						title: 'Replayable captures (.zip)',
						sub: 'Per-shot raw BLE bytes. Imports / pre-recorder shots are skipped (no raw bytes).',
						onclick: () => void exportAllReplay()
					}
				]}
			/>
			{#if canPushShots && unsyncedShots.length > 0}
				<button
					class="st-btn st-btn-secondary"
					disabled={uploadingAll}
					onclick={uploadAll}
					title={`Upload ${unsyncedShots.length} unsynced shot${unsyncedShots.length === 1 ? '' : 's'} to Visualizer`}
				>
					<i
						class={uploadingAll ? 'ph ph-spinner-gap hi-spin' : 'ph ph-cloud-arrow-up'}
						aria-hidden="true"
					></i>
					{uploadingAll
						? 'Uploading…'
						: `Upload all (${unsyncedShots.length})`}
				</button>
			{/if}
			{#if selectMode}
				<button
					class="st-btn st-btn-secondary"
					onclick={toggleSelectMode}
					title="Cancel compare selection"
				>
					<i class="ph ph-x" aria-hidden="true"></i> Cancel
				</button>
				<button
					class="st-btn st-btn-primary"
					disabled={selectedIds.length < 2}
					onclick={openCompare}
					title={selectedIds.length < 2
						? 'Pick at least 2 shots to compare'
						: `Open compare overlay (${selectedIds.length} shots)`}
				>
					<i class="ph ph-arrows-left-right" aria-hidden="true"></i>
					Compare ({selectedIds.length})
				</button>
			{:else}
				<button
					class="st-btn st-btn-secondary"
					disabled={shots.length < 2}
					onclick={toggleSelectMode}
					title={shots.length < 2
						? 'Need at least 2 shots to compare'
						: 'Pick 2–5 shots to overlay on one chart'}
				>
					<i class="ph ph-arrows-left-right" aria-hidden="true"></i> Compare
				</button>
			{/if}
		</div>
	</div>

	{#if importBanner}
		<div
			class="hi-import-banner"
			class:hi-import-banner-ok={importBanner.kind === 'success'}
			class:hi-import-banner-err={importBanner.kind === 'error'}
			role="status"
		>
			<i
				class={importBanner.kind === 'success' ? 'ph ph-check-circle' : 'ph ph-warning'}
				aria-hidden="true"
			></i>
			<span>{importBanner.message}</span>
			<button
				type="button"
				class="hi-import-banner-close"
				aria-label="Dismiss"
				onclick={() => (importBanner = null)}
			>
				<i class="ph ph-x" aria-hidden="true"></i>
			</button>
		</div>
	{/if}


	{#if shots.length === 0}
		<!-- Empty state — no shots recorded yet. -->
		<div class="hi-empty-page">
			<div class="hi-empty-glyph"><i class="ph ph-chart-line" aria-hidden="true"></i></div>
			<div class="hi-empty-title">No shots recorded yet</div>
			<div class="hi-empty-sub">
				Pull a shot on the Brew page with a connected DE1 — when it completes it is
				saved here with its full telemetry curve.
			</div>
			<!-- Empty-state import CTA: the first time a user lands here
			     with no shots, surface the import path next to the
			     "pull a shot" instructions. Auto-disappears the moment
			     there's one shot in history. -->
			<button
				class="st-btn st-btn-secondary hi-empty-import"
				disabled={importing}
				onclick={() => (importOpen = true)}
				title="Import legacy de1app .shot or .shot.json files"
			>
				<i class="ph ph-upload-simple" aria-hidden="true"></i>
				<span>{importing ? 'Importing…' : 'Import from de1app'}</span>
			</button>
		</div>
	{:else}
		<!-- Stats strip -->
		<div class="hi-stats">
			<div class="hi-stat">
				<div class="hi-stat-label">Today</div>
				<div class="hi-stat-val"><span>{todayCount}</span><em>shots</em></div>
			</div>
			<div class="hi-stat">
				<div class="hi-stat-label">This week</div>
				<div class="hi-stat-val"><span>{weekCount}</span><em>shots</em></div>
			</div>
			<div class="hi-stat">
				<div class="hi-stat-label">Total</div>
				<div class="hi-stat-val"><span>{shots.length}</span><em>shots</em></div>
			</div>
			<div class="hi-stat">
				<div class="hi-stat-label">Avg yield</div>
				<div class="hi-stat-val">
					<span>{avgYield?.value ?? '—'}</span>{#if avgYield != null}<em>{avgYield.unit}</em>{/if}
				</div>
			</div>
			<div class="hi-stat">
				<div class="hi-stat-label">Avg time</div>
				<div class="hi-stat-val">
					<span>{avgTime != null ? avgTime : '—'}</span>{#if avgTime != null}<em
							>s</em
						>{/if}
				</div>
			</div>
			<div class="hi-stat">
				<div class="hi-stat-label">Avg rating</div>
				<div class="hi-stat-val"><span>{avgRating ?? '—'}</span></div>
			</div>
		</div>

		<!-- Filter strip — pills on the left, clear-tags chip + range
		     + SortPill on the right. Layout mirrors /profiles and /beans
		     so the visual rhythm matches across the three library pages. -->
		<div class="hi-filters">
			<div class="hi-prof-filters">
				<FilterPills pills={filterPills} onclick={onFilterPillClick} />
			</div>
			{#if selectedTags.length > 0}
				<button
					class="hi-chip-clear"
					onclick={() => (selectedTags = [])}
					title="Clear all selected tag filters"
				>
					<i class="ph ph-x"></i> Clear tags
				</button>
			{/if}
			<div class="pp-sort hi-range">
				<span class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.45)">Range</span>
				<button
					class="pp-sort-opt"
					class:is-active={range === '30d'}
					onclick={() => (range = '30d')}>Last 30 days</button
				>
				<button
					class="pp-sort-opt"
					class:is-active={range === 'all'}
					onclick={() => (range = 'all')}>All time</button
				>
			</div>
			<div class="pp-sort">
				<SortPill
					value={{ field: sortField, direction: sortDir }}
					options={sortOptions}
					onchange={(next) => {
						const previousField = sortField;
						sortField = next.field as SortField;
						// Picking a NEW field reapplies that field's default
						// direction so e.g. "Bean" defaults to A→Z instead of
						// inheriting the previous field's descending preference.
						// Flipping direction on the same field threads through.
						if (next.field !== previousField) {
							sortDir = DEFAULT_DIR[next.field as SortField] ?? next.direction;
						} else {
							sortDir = next.direction;
						}
					}}
				/>
			</div>
		</div>

		<!-- Compare-select hint banner -->
		{#if selectMode}
			<div class="hi-select-banner" role="status">
				<i class="ph ph-arrows-left-right" aria-hidden="true"></i>
				<span>
					Pick 2–{COMPARE_MAX} shots to overlay on one chart.
					{selectedIds.length === 0
						? 'Tap a row below.'
						: selectedIds.length < 2
							? `1 picked — tap another.`
							: `${selectedIds.length} picked — Compare when ready.`}
				</span>
			</div>
		{/if}

		<!-- Split pane: list + detail -->
		<div class="hi-split">
			<div class="hi-list">
				{#each filtered as s (s.id)}
					<ShotRow
						shot={s}
						active={selected?.id === s.id}
						selectable={selectMode}
						selected={selectedIdSet.has(s.id)}
						selectionDisabled={selectedIds.length >= COMPARE_MAX}
						syncPip={pipFor(s)}
						onclick={() => (selectMode ? toggleCompareSelection(s.id) : select(s.id))}
					/>
				{/each}
				{#if filtered.length === 0}
					<div class="hi-empty">No shots match.</div>
				{/if}
			</div>
			{#if selectMode}
				<!-- Compare-select staging pane (replaces ShotDetail in select mode) -->
				<div class="hi-detail-empty hi-compare-staging">
					{#if selectedIds.length === 0}
						<div class="hi-compare-staging-title">No shots picked yet</div>
						<div class="hi-compare-staging-sub">
							Tap any row in the list to add it.
						</div>
					{:else}
						<div class="hi-compare-staging-title">
							{selectedIds.length} of {COMPARE_MAX}
						</div>
						<div class="hi-compare-staging-sub">
							{#if selectedIds.length < 2}
								Pick at least one more shot.
							{:else}
								Ready — hit Compare in the header.
							{/if}
						</div>
						<ol class="hi-compare-list">
							{#each compareShots as cs (cs.id)}
								<li>
									<span class="hi-compare-list-name"
										>{cs.profileName ?? 'Untitled shot'}</span
									>
									<button
										type="button"
										class="hi-compare-list-x"
										aria-label="Remove from compare"
										onclick={() => toggleCompareSelection(cs.id)}
									>
										<i class="ph ph-x"></i>
									</button>
								</li>
							{/each}
						</ol>
					{/if}
				</div>
			{:else if selected}
				<!-- Keyed on the shot id: `ShotDetail` holds a local notes draft
				     in component state, so reusing one instance across a shot
				     change could save shot A's open draft onto shot B. The
				     `{#key}` forces a fresh instance — and a fresh draft —
				     whenever the selection moves. -->
				{#key selected.id}
					<ShotDetail
						shot={selected}
						onNotesChange={(notes) => store.setNotes(selected.id, notes)}
						onRatingChange={(rating) => store.setRating(selected.id, rating)}
						onGrinderModelChange={(grinderModel) =>
							store.setGrinderModel(selected.id, grinderModel)}
						onTagsChange={(tags) => store.setTags(selected.id, tags)}
						onBeanChange={(bean, roaster) =>
							store.setBeanFromLive(selected.id, bean, roaster)}
						onDelete={(opts) => handleDelete(selected, opts)}
						canDeleteRemote={canPushShots && !!selected.visualizerId}
					/>
				{/key}
			{:else}
				<div class="hi-detail-empty">Select a shot to see its detail.</div>
			{/if}
		</div>
	{/if}
</div>

{#if compareOpen && compareShots.length >= 2}
	<CompareOverlay shots={compareShots} onClose={closeCompare} />
{/if}

{#if importOpen}
	<ShotImportDialog
		{importing}
		onClose={() => (importOpen = false)}
		onImport={runImport}
	/>
{/if}

<style>
	.hi-page {
		background: var(--bg-page);
		color: var(--fg-1);
		display: flex;
		flex-direction: column;
		min-height: 100vh;
		/* Shared page-header rhythm — see --page-pad-* in app.css. */
		padding: var(--page-pad-top) var(--page-pad-x) 40px;
		gap: 20px;
		overflow-y: auto;
	}

	/* Header */
	.hi-head {
		display: flex;
		align-items: flex-end;
		justify-content: space-between;
		gap: 24px;
	}
	/* Page title uses the shared .t-page-title role; only the layout nudge
	   is screen-specific. */
	.hi-title {
		margin-top: 2px;
	}
	.hi-sub {
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.5);
		margin-top: 4px;
	}
	.hi-head-r {
		display: flex;
		align-items: center;
		gap: 10px;
	}
	.hi-search {
		position: relative;
		display: flex;
		align-items: center;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
		border-radius: var(--radius-pill);
		padding: 0 12px;
		width: 260px;
	}
	.hi-search:focus-within {
		border-color: rgba(var(--tint-rgb), 0.2);
	}
	.hi-search i {
		color: rgba(var(--tint-rgb), 0.4);
		font-size: 13px;
	}
	.hi-search input {
		flex: 1 1 auto;
		min-width: 0;
		background: transparent;
		border: 0;
		outline: 0;
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 12px;
		padding: 8px 6px;
	}
	.hi-search input::placeholder {
		color: rgba(var(--tint-rgb), 0.35);
	}

	/* The .st-btn family is shared globally — see settings-page.css. */

	/* Stats strip */
	.hi-stats {
		display: grid;
		grid-template-columns: repeat(6, minmax(0, 1fr));
		gap: 12px;
	}
	.hi-stat {
		background: var(--bg-surface);
		border: 1px solid rgba(var(--tint-rgb), 0.05);
		border-radius: var(--radius-md);
		padding: 14px 16px;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.hi-stat-label {
		font-family: var(--font-sans);
		font-size: 10px;
		font-weight: 700;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.45);
	}
	.hi-stat-val {
		display: flex;
		align-items: baseline;
		gap: 4px;
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 22px;
		color: var(--fg-1);
		letter-spacing: -0.01em;
	}
	.hi-stat-val em {
		font-style: normal;
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.5);
		margin-left: 1px;
	}

	/* Filter row */
	.hi-filters {
		display: flex;
		justify-content: space-between;
		align-items: center;
		gap: 16px;
		padding-bottom: 14px;
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.05);
	}
	.hi-prof-filters {
		display: flex;
		gap: 4px;
		overflow-x: auto;
	}
	/* `.pp-tag*` styles come from `styles/profiles-page.css` once
	   `FilterPills` is in use; the local duplicates were removed. */
	.pp-sort {
		display: flex;
		align-items: center;
		gap: 10px;
		flex: 0 0 auto;
	}
	.pp-sort-opt {
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.5);
		font-family: var(--font-sans);
		font-size: 12px;
		cursor: pointer;
		padding: 4px 2px;
		transition: color var(--dur-1) var(--ease);
	}
	.pp-sort-opt:hover {
		color: var(--fg-1);
	}
	.pp-sort-opt.is-active {
		color: var(--fg-1);
		text-decoration: underline;
		text-decoration-color: var(--copper-500);
		text-decoration-thickness: 1.5px;
		text-underline-offset: 4px;
	}

	/* Clear-tags chip — surfaces alongside the profile / tag pill rail
	   when at least one tag is selected. Mirrors `.bn-chip-clear` in
	   `/beans` so the visual rhythm matches. */
	.hi-chip-clear {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.5);
		font-family: var(--font-sans);
		font-size: 11px;
		cursor: pointer;
		padding: 4px 8px;
		border-radius: var(--radius-pill);
		flex-shrink: 0;
	}
	.hi-chip-clear:hover {
		color: var(--fg-1);
	}

	/* Split */
	.hi-split {
		display: grid;
		grid-template-columns: minmax(360px, 480px) 1fr;
		gap: 18px;
		flex: 1 1 auto;
		min-height: 0;
		padding-bottom: 8px;
	}
	.hi-list {
		display: flex;
		flex-direction: column;
		gap: 2px;
		background: var(--bg-surface);
		border: 1px solid rgba(var(--tint-rgb), 0.05);
		border-radius: var(--radius-lg, 14px);
		padding: 8px;
		overflow-y: auto;
		align-self: start;
		max-height: 70vh;
	}
	.hi-empty {
		padding: 32px 16px;
		text-align: center;
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.4);
	}
	.hi-detail-empty {
		display: flex;
		align-items: center;
		justify-content: center;
		background: var(--bg-surface);
		border: 1px solid rgba(var(--tint-rgb), 0.05);
		border-radius: var(--radius-lg, 14px);
		color: rgba(var(--tint-rgb), 0.4);
		font-family: var(--font-sans);
		font-size: 13px;
		min-height: 200px;
	}

	/* Compare-select banner — above the split pane while in select mode. */
	.hi-select-banner {
		display: inline-flex;
		align-items: center;
		gap: 10px;
		padding: 10px 14px;
		border-radius: var(--radius-sm, 6px);
		background: rgba(var(--copper-rgb), 0.1);
		border: 1px solid rgba(var(--copper-rgb), 0.22);
		color: var(--copper-400);
		font-family: var(--font-sans);
		font-size: 12px;
		align-self: flex-start;
	}

	/* Compare-staging pane — replaces ShotDetail while in select mode. */
	.hi-compare-staging {
		flex-direction: column;
		justify-content: flex-start;
		padding: 28px 22px;
		text-align: center;
		gap: 6px;
	}
	.hi-compare-staging-title {
		font-family: var(--font-serif, var(--font-sans));
		font-size: 18px;
		color: var(--fg-1);
	}
	.hi-compare-staging-sub {
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.5);
	}
	.hi-compare-list {
		list-style: none;
		padding: 14px 0 0;
		margin: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
		align-self: stretch;
	}
	.hi-compare-list li {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 8px 12px;
		background: rgba(var(--tint-rgb), 0.04);
		border-radius: var(--radius-sm, 6px);
		font-family: var(--font-sans);
		font-size: 13px;
		color: var(--fg-1);
	}
	.hi-compare-list-name {
		text-align: left;
		flex: 1 1 auto;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.hi-compare-list-x {
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.55);
		cursor: pointer;
		padding: 2px 6px;
		border-radius: 4px;
	}
	.hi-compare-list-x:hover {
		background: rgba(var(--tint-rgb), 0.08);
		color: var(--fg-1);
	}

	/* Empty page state */
	.hi-empty-page {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		text-align: center;
		gap: 10px;
		padding: 80px 40px;
		flex: 1 1 auto;
	}
	.hi-empty-glyph {
		width: 64px;
		height: 64px;
		border-radius: 50%;
		background: rgba(var(--tint-rgb), 0.05);
		display: flex;
		align-items: center;
		justify-content: center;
		color: rgba(var(--tint-rgb), 0.5);
		font-size: 30px;
		margin-bottom: 6px;
	}
	.hi-empty-title {
		font-family: var(--font-serif);
		font-size: 22px;
		color: var(--fg-1);
	}
	.hi-empty-sub {
		font-family: var(--font-sans);
		font-size: 13px;
		line-height: 1.55;
		color: rgba(var(--tint-rgb), 0.5);
		max-width: 420px;
	}

	/* ── Import (empty-state CTA + result banner) ──────── */
	.hi-empty-import {
		margin-top: 18px;
	}
	.hi-import-banner {
		display: flex;
		align-items: center;
		gap: 10px;
		margin: 12px 0 0;
		padding: 10px 14px;
		border-radius: var(--radius-sm);
		font-family: var(--font-sans);
		font-size: 12.5px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
	}
	.hi-import-banner-ok {
		background: rgba(var(--success-rgb), 0.08);
		border-color: rgba(var(--success-rgb), 0.4);
		color: rgba(var(--success-rgb), 0.95);
	}
	.hi-import-banner-err {
		background: rgba(var(--danger-rgb), 0.08);
		border-color: rgba(var(--danger-rgb), 0.4);
		color: rgba(var(--danger-rgb), 0.95);
	}
	.hi-import-banner-close {
		margin-left: auto;
		background: transparent;
		border: none;
		cursor: pointer;
		color: inherit;
		opacity: 0.7;
		padding: 4px;
	}
	.hi-import-banner-close:hover {
		opacity: 1;
	}

	.hi-spin {
		animation: hi-spin 1.1s linear infinite;
		display: inline-block;
	}
	@keyframes hi-spin {
		from { transform: rotate(0); }
		to { transform: rotate(360deg); }
	}

	@media (max-width: 1100px) {
		.hi-stats {
			grid-template-columns: repeat(3, minmax(0, 1fr));
		}
		.hi-split {
			grid-template-columns: minmax(0, 1fr);
		}
	}
</style>
