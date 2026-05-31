<script lang="ts">
	/**
	 * `BeanSyncSection` — Settings → Sharing → "Sync" card.
	 *
	 * This card holds the unified per-entity sync matrix:
	 * beans / roasters / shots each get a direction selector (off /
	 * backup / pull / two-way), with a master `Auto-sync` toggle, a
	 * single "Sync now" button, and a collapsible activity log spanning
	 * all three entity types.
	 *
	 * The name predates the unification — kept for git-blame continuity.
	 */
	import { onMount } from 'svelte';
	import { getBeanStore, readSyncSettings, type SyncResult as BeanSyncResult } from '$lib/bean';
	import { getHistoryStore } from '$lib/history';
	import {
		appendSyncLog,
		directionPulls,
		directionPushes,
		onSyncConfigChange,
		readSyncConfig,
		updateSyncConfig,
		type SyncDirection
	} from '$lib/visualizer';
	import { getCremaAppContext } from '$lib/shell/app-context';
	import type { PullOptions } from '$lib/services/shot-sync';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StSegment from '../StSegment.svelte';
	import StToggle from '../StToggle.svelte';

	const library = getBeanStore();
	const history = getHistoryStore();
	const appCtx = getCremaAppContext();

	// ── Service seams (Option 3, T-16) ────────────────────────────────────
	// Shot pull / push / queue drain run on the app runtime. The card only
	// renders when connected (which requires the runtime), so a missing runtime
	// is the defensive unsupported-browser case.
	async function svcPullAndReconcile(
		sinceMs: number,
		opts: PullOptions
	): Promise<{ pulled: number; truncated: boolean }> {
		const api = appCtx().services;
		if (!api) throw new Error('Visualizer runtime unavailable');
		return api.shots.pullAndReconcile(history, sinceMs, opts);
	}
	async function svcUploadUnsynced(): Promise<void> {
		await appCtx().services?.shots.uploadUnsynced(history);
	}
	async function svcDrain(): Promise<void> {
		await appCtx().services?.queue.drain();
	}
	async function svcRunBeanSync(): Promise<BeanSyncResult> {
		const api = appCtx().services;
		if (!api) throw new Error('Visualizer runtime unavailable');
		return api.beans.runSync(library);
	}

	let config = $state(readSyncConfig());
	let beanLastSync = $state(readSyncSettings().lastSyncAt);
	let syncing = $state(false);
	let resyncing = $state(false);
	let lastResult = $state<BeanSyncResult | null>(null);
	let logCollapsed = $state(true);
	/** Pull-pagination progress — non-null only while shots are streaming in. */
	let pullProgress = $state<{ fetched: number; page: number } | null>(null);

	onMount(() => {
		void refreshConnected();
		// Refresh in case another tab edited it while this one was open.
		config = readSyncConfig();
		// Pick up writes from elsewhere in the app — most notably the
		// Sharing card's Test handler probing the premium tier, which
		// needs to immediately unlock the bean/roaster push pills here.
		return onSyncConfigChange((next) => {
			config = next;
		});
	});

	/** Every direction selector lists the same four modes. */
	const DIRECTION_OPTIONS: { value: SyncDirection; label: string }[] = [
		{ value: 'off', label: 'Off' },
		{ value: 'backup', label: 'Backup' },
		{ value: 'pull', label: 'Pull' },
		{ value: 'two-way', label: 'Two-way' }
	];

	/**
	 * Free-tier users can't push beans / roasters (Visualizer's premium
	 * gate). The segment shows the full four options but
	 * `Backup` / `Two-way` are visually disabled for those entities. We
	 * still allow Off / Pull. Shots stay unrestricted.
	 */
	const premiumLocked = $derived(config.premium === false);

	/**
	 * Beans + roasters use the same option list, but with the push-side
	 * modes ("backup" + "two-way") greyed out on free tier — the user
	 * sees they exist but can't pick them without upgrading.
	 */
	const beanRoasterDirectionOptions = $derived(
		DIRECTION_OPTIONS.map((o) => ({
			...o,
			disabled: premiumLocked && (o.value === 'backup' || o.value === 'two-way')
		}))
	);

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
		// Surface any step failure in the activity log instead of letting it
		// bubble out as a silent unhandled rejection — a failed pull/push used
		// to do nothing AND log nothing, leaving the user with no signal.
		const logError = (direction: 'push' | 'pull', entity: 'shot' | 'bean', err: unknown): void =>
			appendSyncLog({
				direction,
				entity,
				id: '',
				name: 'sync',
				at: Date.now(),
				error: err instanceof Error ? err.message : String(err)
			});
		try {
			// 1. Beans + roasters share the existing runSync(library) call.
			//    We respect the direction selector by skipping the call when
			//    both bean + roaster modes are "off".
			const beansOn = config.direction.beans !== 'off';
			const roastersOn = config.direction.roasters !== 'off';
			if (beansOn || roastersOn) {
				try {
					lastResult = await svcRunBeanSync();
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
				} catch (err) {
					logError('pull', 'bean', err);
				}
			}
			// 2a. Shot pull — paginated. The visualizer module walks
			//     `next_cursor` and reconciles each page against the local
			//     history store. Progress drives a status line below.
			if (directionPulls(config.direction.shots)) {
				// Capture the cursor BEFORE fetching so shots updated mid-pull
				// aren't skipped next time. Minus a 60s slop for client/server
				// clock skew — re-pulling a row is a no-op (reconcile is
				// idempotent), missing one is data loss.
				const pullStartedAt = Date.now() - 60_000;
				try {
					const { pulled, truncated } = await svcPullAndReconcile(
						config.shotPullCursor ?? 0,
						{
							itemsPerPage: 50,
							onProgress: (p) =>
								(pullProgress = { fetched: p.fetched, page: p.page })
						}
					);
					if (truncated) {
						// Hit the page cap — older shots remain unpulled, so DON'T
						// advance the cursor or the next run would skip them.
						appendSyncLog({
							direction: 'skip',
							entity: 'shot',
							id: '',
							name: 'pull',
							at: Date.now(),
							error: 'Pull truncated at safety cap — re-run Sync to continue.'
						});
					} else {
						// Caught up to the end — advance the cursor (added rows log
						// themselves via reconciliation).
						config = updateSyncConfig({ shotPullCursor: pullStartedAt });
						if (pulled === 0) {
							// A zero-row pull otherwise leaves no trace, which reads
							// as "nothing happened" — log it so every run has a result.
							appendSyncLog({
								direction: 'pull',
								entity: 'shot',
								id: '',
								name: 'No new shots from Visualizer',
								at: Date.now()
							});
						}
					}
				} catch (err) {
					logError('pull', 'shot', err);
				} finally {
					pullProgress = null;
				}
			}
			// 2b. Shot push — upload any local shots that aren't on Visualizer.
			if (directionPushes(config.direction.shots)) {
				try {
					await svcUploadUnsynced();
					config = updateSyncConfig({
						lastSyncAt: { ...config.lastSyncAt, shots: Date.now() }
					});
				} catch (err) {
					logError('push', 'shot', err);
				}
			}
			// 3. Drain the retry queue so anything backlogged flushes.
			try {
				await svcDrain();
			} catch (err) {
				logError('push', 'shot', err);
			}
			config = readSyncConfig();
		} finally {
			syncing = false;
		}
	}

	/**
	 * Re-pull EVERY shot from Visualizer, ignoring the saved cursor (starts
	 * from time 0). Reconciliation still de-dupes by signature, so this only
	 * fills gaps — e.g. after clearing local history or switching devices —
	 * rather than creating duplicates. On a clean (non-truncated) finish the
	 * cursor is advanced to now so the next incremental "Sync now" is cheap.
	 */
	async function resyncAllShots(): Promise<void> {
		if (syncing || resyncing) return;
		if (
			!confirm(
				'Re-pull every shot from Visualizer from the beginning? Existing shots are de-duplicated, so this only adds ones missing locally.'
			)
		) {
			return;
		}
		resyncing = true;
		const pullStartedAt = Date.now() - 60_000;
		try {
			const { pulled, truncated } = await svcPullAndReconcile(0, {
				itemsPerPage: 50,
				onProgress: (p) => (pullProgress = { fetched: p.fetched, page: p.page })
			});
			if (truncated) {
				appendSyncLog({
					direction: 'skip',
					entity: 'shot',
					id: '',
					name: 'Full re-pull',
					at: Date.now(),
					error: 'Pull truncated at safety cap — run again to continue.'
				});
			} else {
				config = updateSyncConfig({ shotPullCursor: pullStartedAt });
				appendSyncLog({
					direction: 'pull',
					entity: 'shot',
					id: '',
					name: `Full re-pull — ${pulled} shot${pulled === 1 ? '' : 's'} reconciled`,
					at: Date.now()
				});
			}
		} catch (err) {
			appendSyncLog({
				direction: 'pull',
				entity: 'shot',
				id: '',
				name: 'Full re-pull',
				at: Date.now(),
				error: err instanceof Error ? err.message : String(err)
			});
		} finally {
			pullProgress = null;
			resyncing = false;
			config = readSyncConfig();
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

	// Connection gate (Option 3): `TokenVault.getTokens !== null`, read through
	// the runtime into a `$state`. Refreshed on mount (the card is already
	// gated by SharingSection's connected check, so this is rarely false here).
	let connected = $state(false);
	async function refreshConnected(): Promise<void> {
		connected = (await appCtx().services?.tokens.isConnected()) ?? false;
	}
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
				<div class="bs-direction">
					{#if premiumLocked}
						<a
							class="bs-premium-tag"
							href="https://visualizer.coffee/premium"
							target="_blank"
							rel="noopener noreferrer"
							title="Visualizer Premium required for push. Upgrade at visualizer.coffee/premium."
						>
							<i class="ph ph-lock-key" aria-hidden="true"></i>
							<span>Premium required for push</span>
						</a>
					{/if}
					<StSegment
						value={config.direction.beans}
						options={beanRoasterDirectionOptions}
						onChange={(v) => setEntityDirection('beans', v as SyncDirection)}
					/>
				</div>
			{/snippet}
		</StRow>

		<StRow
			title="Roasters"
			sub={`${library.roasters.length} roaster(s). Last sync: ${fmtTime(config.lastSyncAt.roasters ?? beanLastSync)}.`}
		>
			{#snippet control()}
				<div class="bs-direction">
					{#if premiumLocked}
						<a
							class="bs-premium-tag"
							href="https://visualizer.coffee/premium"
							target="_blank"
							rel="noopener noreferrer"
							title="Visualizer Premium required for push. Upgrade at visualizer.coffee/premium."
						>
							<i class="ph ph-lock-key" aria-hidden="true"></i>
							<span>Premium required for push</span>
						</a>
					{/if}
					<StSegment
						value={config.direction.roasters}
						options={beanRoasterDirectionOptions}
						onChange={(v) => setEntityDirection('roasters', v as SyncDirection)}
					/>
				</div>
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
			title="Re-sync shots"
			sub="Re-pull every shot from Visualizer from the start. De-duplicated against what you already have — use it to fill gaps after clearing history or on a new device."
		>
			{#snippet control()}
				<button
					type="button"
					class="bs-btn"
					disabled={syncing || resyncing}
					onclick={resyncAllShots}
				>
					<i
						class={resyncing ? 'ph ph-spinner-gap bs-spin' : 'ph ph-clock-counter-clockwise'}
						aria-hidden="true"
					></i>
					{resyncing ? 'Re-syncing…' : 'Re-sync all'}
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
	/* ── Direction control with optional premium tag ─────────────────────
	   Inline layout: [premium tag (if locked)] [segment]. Living in the
	   row's right slot rather than below the segment means a free-tier
	   layout doesn't shift the rows below — the tag steals horizontal
	   room from the title's flex grow instead of stacking vertically. */
	.bs-direction {
		display: inline-flex;
		align-items: center;
		gap: 10px;
		flex-wrap: wrap;
		justify-content: flex-end;
	}
	.bs-premium-tag {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.55);
		text-decoration: none;
	}
	.bs-premium-tag i {
		font-size: 12px;
		opacity: 0.75;
	}
	.bs-premium-tag:hover {
		color: var(--copper-400);
	}
	.bs-premium-tag:hover i {
		opacity: 1;
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
