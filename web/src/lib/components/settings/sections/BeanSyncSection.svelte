<script lang="ts">
	/**
	 * `BeanSyncSection` — Settings → Sharing → "Sync" card.
	 *
	 * Per docs/36 §8, this card holds the unified per-entity sync matrix:
	 * beans / roasters / shots each get a direction selector (off /
	 * backup / pull / two-way), with a master `Auto-sync` toggle, a
	 * single "Sync now" button, and a collapsible activity log spanning
	 * all three entity types.
	 *
	 * The name predates the unification — kept for git-blame continuity.
	 */
	import { onMount } from 'svelte';
	import {
		getBeanStore,
		readSyncSettings,
		runSync as runBeanSync,
		type SyncResult as BeanSyncResult
	} from '$lib/bean';
	import { getHistoryStore } from '$lib/history';
	import {
		appendSyncLog,
		directionPulls,
		directionPushes,
		drainQueue,
		enqueue as enqueueSyncOp,
		isConnected as isVisualizerConnected,
		pullAllShots,
		readSyncConfig,
		reconcileShots,
		storedShotFromWire,
		updateSyncConfig,
		uploadShot,
		type SyncDirection,
		type WireShot
	} from '$lib/visualizer';
	import { VisualizerError } from '$lib/bean';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StSegment from '../StSegment.svelte';
	import StToggle from '../StToggle.svelte';

	const library = getBeanStore();
	const history = getHistoryStore();

	let config = $state(readSyncConfig());
	let beanLastSync = $state(readSyncSettings().lastSyncAt);
	let syncing = $state(false);
	let lastResult = $state<BeanSyncResult | null>(null);
	let logCollapsed = $state(true);
	/** Pull-pagination progress — non-null only while shots are streaming in. */
	let pullProgress = $state<{ fetched: number; page: number } | null>(null);

	onMount(() => {
		// Refresh in case another tab edited it while this one was open.
		config = readSyncConfig();
	});

	/** docs/36 §8: every direction selector lists the same four modes. */
	const DIRECTION_OPTIONS: { value: SyncDirection; label: string }[] = [
		{ value: 'off', label: 'Off' },
		{ value: 'backup', label: 'Backup' },
		{ value: 'pull', label: 'Pull' },
		{ value: 'two-way', label: 'Two-way' }
	];

	/**
	 * Free-tier users can't push beans / roasters (Visualizer's premium
	 * gate). docs/36 §8: the segment shows the full four options but
	 * `Backup` / `Two-way` are visually disabled for those entities. We
	 * still allow Off / Pull. Shots stay unrestricted.
	 */
	const premiumLocked = $derived(config.premium === false);

	function setEntityDirection(
		entity: 'beans' | 'roasters' | 'shots',
		direction: SyncDirection
	): void {
		// Block premium-gated pushes for beans + roasters when on free tier.
		if (
			premiumLocked &&
			entity !== 'shots' &&
			(direction === 'backup' || direction === 'two-way')
		) {
			return;
		}
		config = updateSyncConfig({
			direction: { ...config.direction, [entity]: direction }
		});
	}

	function setAutoSync(on: boolean): void {
		config = updateSyncConfig({ autoSync: on });
	}

	async function syncNow(): Promise<void> {
		if (syncing) return;
		syncing = true;
		try {
			// 1. Beans + roasters share the existing runSync(library) call.
			//    We respect the direction selector by skipping the call when
			//    both bean + roaster modes are "off".
			const beansOn = config.direction.beans !== 'off';
			const roastersOn = config.direction.roasters !== 'off';
			if (beansOn || roastersOn) {
				lastResult = await runBeanSync(library);
				beanLastSync = readSyncSettings().lastSyncAt;
				config = updateSyncConfig({
					lastSyncAt: {
						...config.lastSyncAt,
						beans: beansOn ? Date.now() : config.lastSyncAt.beans,
						roasters: roastersOn ? Date.now() : config.lastSyncAt.roasters
					},
					premium: lastResult.premiumLocked ? false : (config.premium ?? true)
				});
				// Mirror the bean log into the unified log (one tag per entry
				// so the user can filter mentally).
				for (const entry of lastResult.log.slice(0, 10).reverse()) {
					appendSyncLog({
						direction: entry.direction,
						entity: entry.kind,
						id: entry.id,
						name: entry.name,
						at: entry.at,
						error: entry.error
					});
				}
			}
			// 2a. Shot pull — paginated. Walks `next_cursor` until exhausted
			//     (page size 50 default). Progress drives a status line below.
			if (directionPulls(config.direction.shots)) {
				try {
					const { shots: remote, truncated } = await pullAllShots(
						config.lastSyncAt.shots ?? 0,
						{
							perPage: 50,
							onProgress: (p) =>
								(pullProgress = { fetched: p.fetched, page: p.page })
						}
					);
					if (remote.length > 0) {
						applyShotReconciliation(remote);
					}
					if (truncated) {
						appendSyncLog({
							direction: 'skip',
							entity: 'shot',
							id: '',
							name: 'pull',
							at: Date.now(),
							error: 'Pull truncated at safety cap — re-run Sync to continue.'
						});
					}
				} finally {
					pullProgress = null;
				}
			}
			// 2b. Shot push — upload any local shots that aren't on Visualizer.
			if (directionPushes(config.direction.shots)) {
				await uploadUnsyncedShots();
				config = updateSyncConfig({
					lastSyncAt: { ...config.lastSyncAt, shots: Date.now() }
				});
			}
			// 3. Drain the retry queue so anything backlogged flushes.
			await drainQueue();
			config = readSyncConfig();
		} finally {
			syncing = false;
		}
	}

	/**
	 * Apply the {@link reconcileShots} plan against the local history store.
	 * - `add` → insertPulled (brand-new shot from remote)
	 * - `bind` → bindVisualizerId (local shot existed pre-sign-in; adopt
	 *   the remote id without creating a duplicate; de-dup signature flow
	 *   from docs/36 §3)
	 * - `update` → flow the editable annotations Visualizer mutates server-
	 *   side (currently `tags`) onto the bound local row. Notes / rating
	 *   are still owner-edited locally only — those don't loop back from
	 *   the remote in v1 (we don't surface remote authorship), so we
	 *   restrict the LWW patch to `tags` for now. The bind itself is
	 *   already in place (this branch fires when `visualizerId` matches),
	 *   so no extra binding step is needed.
	 */
	function applyShotReconciliation(remote: WireShot[]): void {
		const local = history.all;
		const actions = reconcileShots(local, remote);
		for (const action of actions) {
			if (action.kind === 'add') {
				const stored = storedShotFromWire(action.remote);
				if (stored) history.insertPulled(stored);
				appendSyncLog({
					direction: 'pull',
					entity: 'shot',
					id: stored?.id ?? action.remote.id ?? '',
					name: stored?.profileName ?? 'Shot',
					at: Date.now()
				});
			} else if (action.kind === 'bind') {
				history.bindVisualizerId(action.localId, action.visualizerId);
				appendSyncLog({
					direction: 'pull',
					entity: 'shot',
					id: action.localId,
					name: 'Shot (bound)',
					at: Date.now()
				});
			} else if (action.kind === 'update') {
				// Flow remote-side tag edits down. The wire `tag_list` is
				// always present (defaults to `[]` server-omit), so this
				// patch is idempotent — re-applying an empty list to a
				// local that has none is a no-op write.
				history.setTags(action.localId, action.remote.tag_list ?? []);
			}
		}
	}

	async function uploadUnsyncedShots(): Promise<void> {
		for (const shot of history.all.filter((s) => !s.visualizerId)) {
			try {
				const { visualizerId } = await uploadShot(shot);
				history.bindVisualizerId(shot.id, visualizerId);
				appendSyncLog({
					direction: 'push',
					entity: 'shot',
					id: shot.id,
					name: shot.profileName ?? 'Shot',
					at: Date.now()
				});
			} catch (e) {
				const recoverable =
					e instanceof VisualizerError
						? e.kind === 'network' ||
							(e.status >= 500 && e.status < 600) ||
							e.status === 408
						: true;
				if (recoverable) {
					enqueueSyncOp({
						entity: 'shot',
						id: shot.id,
						op: 'create',
						error: e instanceof Error ? e.message : String(e)
					});
				}
				appendSyncLog({
					direction: 'skip',
					entity: 'shot',
					id: shot.id,
					name: shot.profileName ?? 'Shot',
					at: Date.now(),
					error: e instanceof Error ? e.message : String(e)
				});
				if (e instanceof VisualizerError && (e.kind === 'auth' || e.kind === 'premium')) {
					// Don't keep hammering for the rest of the loop.
					return;
				}
			}
		}
	}

	function fmtTime(at: number | null): string {
		if (!at) return 'never';
		const elapsed = (Date.now() - at) / 1000;
		if (elapsed < 60) return 'just now';
		if (elapsed < 3600) return `${Math.round(elapsed / 60)} min ago`;
		if (elapsed < 86_400) return `${Math.round(elapsed / 3600)} h ago`;
		return new Date(at).toLocaleString('en-US', {
			month: 'short',
			day: 'numeric',
			hour: 'numeric',
			minute: '2-digit'
		});
	}

	function logArrow(direction: string): string {
		if (direction === 'push') return '↑';
		if (direction === 'pull') return '↓';
		if (direction === 'delete') return '✕';
		return '·';
	}

	function entityLabel(entity: string): string {
		if (entity === 'bean') return 'Bag';
		if (entity === 'roaster') return 'Roaster';
		return 'Shot';
	}

	const connected = $derived(isVisualizerConnected());
	const unsyncedShotCount = $derived(history.all.filter((s) => !s.visualizerId).length);
</script>

{#if connected}
	<StGroup
		title="Sync"
		sub="Per-entity direction. Shots can backup or two-way sync regardless of premium; beans and roasters need Visualizer Premium for the push side."
	>
		<StRow
			title="Auto-sync"
			sub="When on, mutations push to Visualizer in the background and the queue drains on app launch."
		>
			{#snippet control()}
				<StToggle on={config.autoSync} onChange={setAutoSync} />
			{/snippet}
		</StRow>

		<StRow
			title="Beans"
			sub={`${library.beans.length} bag(s). Last sync: ${fmtTime(config.lastSyncAt.beans ?? beanLastSync)}.`}
		>
			{#snippet control()}
				<StSegment
					value={config.direction.beans}
					options={DIRECTION_OPTIONS}
					onChange={(v) => setEntityDirection('beans', v as SyncDirection)}
				/>
			{/snippet}
			{#snippet hint()}
				{#if premiumLocked}
					<span
						class="bs-hint-dim bs-hint-premium"
						title="Visualizer Premium required for push. Upgrade at visualizer.coffee/premium."
					>
						<i class="ph ph-lock-key" aria-hidden="true"></i>
						<a
							href="https://visualizer.coffee/premium"
							target="_blank"
							rel="noopener noreferrer"
						>Visualizer Premium required for push.</a>
					</span>
				{/if}
			{/snippet}
		</StRow>

		<StRow
			title="Roasters"
			sub={`${library.roasters.length} roaster(s). Last sync: ${fmtTime(config.lastSyncAt.roasters ?? beanLastSync)}.`}
		>
			{#snippet control()}
				<StSegment
					value={config.direction.roasters}
					options={DIRECTION_OPTIONS}
					onChange={(v) => setEntityDirection('roasters', v as SyncDirection)}
				/>
			{/snippet}
			{#snippet hint()}
				{#if premiumLocked}
					<span
						class="bs-hint-dim bs-hint-premium"
						title="Visualizer Premium required for push. Upgrade at visualizer.coffee/premium."
					>
						<i class="ph ph-lock-key" aria-hidden="true"></i>
						<a
							href="https://visualizer.coffee/premium"
							target="_blank"
							rel="noopener noreferrer"
						>Visualizer Premium required for push.</a>
					</span>
				{/if}
			{/snippet}
		</StRow>

		<StRow
			title="Shots"
			sub={`${history.all.length} shot(s) on this device${unsyncedShotCount > 0 ? `, ${unsyncedShotCount} unsynced` : ''}. Last sync: ${fmtTime(config.lastSyncAt.shots)}.`}
		>
			{#snippet control()}
				<StSegment
					value={config.direction.shots}
					options={DIRECTION_OPTIONS}
					onChange={(v) => setEntityDirection('shots', v as SyncDirection)}
				/>
			{/snippet}
			{#snippet hint()}
				<span class="bs-hint-dim">Free.</span>
			{/snippet}
		</StRow>

		<StRow
			title="Sync now"
			sub={pullProgress
				? `Pulling shots — ${pullProgress.fetched} fetched (page ${pullProgress.page + 1})…`
				: 'Run a one-shot full sync across every enabled entity.'}
		>
			{#snippet control()}
				<button
					type="button"
					class="bs-btn bs-btn-primary"
					disabled={syncing}
					onclick={syncNow}
				>
					<i
						class={syncing ? 'ph ph-spinner-gap bs-spin' : 'ph ph-arrows-clockwise'}
						aria-hidden="true"
					></i>
					{syncing ? 'Syncing…' : 'Sync now'}
				</button>
			{/snippet}
		</StRow>

		<StRow
			title="Recent activity"
			sub={`${config.log.length} entr${config.log.length === 1 ? 'y' : 'ies'} logged.`}
		>
			{#snippet control()}
				<button
					type="button"
					class="bs-btn"
					onclick={() => (logCollapsed = !logCollapsed)}
				>
					<i
						class={logCollapsed ? 'ph ph-caret-down' : 'ph ph-caret-up'}
						aria-hidden="true"
					></i>
					{logCollapsed ? 'Show log' : 'Hide log'}
				</button>
			{/snippet}
		</StRow>

		{#if !logCollapsed && config.log.length > 0}
			<ul class="bs-log">
				{#each config.log as entry (entry.at + entry.id)}
					<li class={entry.error ? 'bs-log-err' : ''}>
						<span class="bs-log-arrow">{logArrow(entry.direction)}</span>
						<span class="bs-log-kind">{entityLabel(entry.entity)}</span>
						<span class="bs-log-name">"{entry.name}"</span>
						<span class="bs-log-time">{fmtTime(entry.at)}</span>
						{#if entry.error}
							<span class="bs-log-msg">— {entry.error}</span>
						{/if}
					</li>
				{/each}
			</ul>
		{/if}

		{#if lastResult?.premiumLocked}
			<div class="bs-premium">
				Bag and roaster pushes need Visualizer Premium (€5/mo). Reads still work
				— your library will pick up remote changes on every sync. Shots are
				unrestricted.
				<a
					href="https://visualizer.coffee/premium"
					target="_blank"
					rel="noopener noreferrer"
				>
					Upgrade →
				</a>
			</div>
		{/if}
	</StGroup>
{/if}

<style>
	.bs-btn {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: var(--radius-pill);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 12px;
		padding: 6px 14px;
		cursor: pointer;
		text-decoration: none;
	}
	.bs-btn[disabled] {
		opacity: 0.5;
		cursor: not-allowed;
	}
	.bs-btn-primary {
		background: var(--copper-500);
		color: var(--fg-on-accent);
		border-color: transparent;
		font-weight: 600;
	}
	.bs-btn-primary:hover:not([disabled]) {
		background: var(--copper-600);
	}
	.bs-spin {
		animation: bs-spin 1.1s linear infinite;
	}
	@keyframes bs-spin {
		from { transform: rotate(0); }
		to { transform: rotate(360deg); }
	}
	.bs-hint-dim {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.45);
	}
	.bs-hint-premium {
		display: inline-flex;
		align-items: center;
		gap: 4px;
	}
	.bs-hint-premium i {
		font-size: 12px;
		opacity: 0.7;
	}
	.bs-hint-premium a {
		color: rgba(var(--tint-rgb), 0.55);
		text-decoration: underline;
		text-decoration-color: rgba(var(--tint-rgb), 0.2);
	}
	.bs-hint-premium a:hover {
		color: var(--copper-400);
		text-decoration-color: var(--copper-400);
	}
	.bs-premium {
		grid-column: 1 / -1;
		margin: 8px 0 4px;
		padding: 12px 14px;
		background: rgba(var(--tint-rgb), 0.04);
		border-radius: var(--radius-sm);
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.7);
	}
	.bs-premium a {
		color: var(--copper-400);
	}
	.bs-log {
		list-style: none;
		padding: 0;
		margin: 4px 0 8px;
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.65);
		grid-column: 1 / -1;
		max-height: 280px;
		overflow-y: auto;
	}
	.bs-log li {
		display: flex;
		align-items: baseline;
		gap: 6px;
		padding: 4px 0;
		border-bottom: 1px dashed rgba(var(--tint-rgb), 0.06);
	}
	.bs-log li:last-child {
		border-bottom: 0;
	}
	.bs-log-err {
		color: rgba(var(--danger-rgb, 204 76 76), 0.95);
	}
	.bs-log-arrow {
		font-family: var(--font-mono);
		color: var(--copper-400);
		width: 12px;
		text-align: center;
	}
	.bs-log-kind {
		color: rgba(var(--tint-rgb), 0.5);
		min-width: 50px;
	}
	.bs-log-name {
		color: var(--fg-1);
		max-width: 240px;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.bs-log-time {
		color: rgba(var(--tint-rgb), 0.4);
		margin-left: auto;
	}
	.bs-log-msg {
		color: rgba(var(--danger-rgb, 204 76 76), 0.95);
	}
</style>
