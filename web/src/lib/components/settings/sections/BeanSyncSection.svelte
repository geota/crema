<script lang="ts">
	import Icon from '#lib/icons/Icon.svelte';
	import LockKeyIcon from 'phosphor-svelte/lib/LockKeyIcon';
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
	import { getBeanStore, readSyncSettings, type SyncResult as BeanSyncResult } from '#lib/bean/index.js';
	import { getHistoryStore } from '#lib/history/index.js';
	import { historyFullNotice } from '#lib/history/brew-date.js';
	import {
		appendSyncLog,
		directionPulls,
		directionPushes,
		onSyncConfigChange,
		readSyncConfig,
		updateSyncConfig,
		type SyncDirection
	} from '#lib/visualizer/index.js';
	import { getCremaAppContext } from '#lib/shell/app-context.js';
	import { useVisualizerConnection } from '#lib/visualizer/useVisualizerConnection.svelte.js';
	import { describeUnsyncedStop, pullRepairPending, type PullApplied, type PullOptions } from '#lib/services/shot-sync.js';
	import { toast } from '#lib/components/shared/toast.svelte.js';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StSegment from '../StSegment.svelte';
	import StToggle from '../StToggle.svelte';
	import { confirmDialog } from '#lib/components/shared/confirm-dialog.svelte.js';
	import { syncTimeLabel } from '#lib/utils/relative-time.js';

	let {
		onShotPushEnabled
	}: {
		/** The shot direction just changed to one that pushes (backup / two-way). */
		onShotPushEnabled?: () => void;
	} = $props();

	const library = getBeanStore();
	const history = getHistoryStore();
	const appCtx = getCremaAppContext();
	const viz = useVisualizerConnection();

	// ── Service seams (Option 3, T-16) ────────────────────────────────────
	// Shot pull / push / queue drain run on the app runtime. The card only
	// renders when connected (which requires the runtime), so a missing runtime
	// is the defensive unsupported-browser case.
	async function svcPullAndReconcile(opts: PullOptions): Promise<PullApplied> {
		const api = appCtx().services;
		if (!api) throw new Error('Visualizer runtime unavailable');
		// Said before it starts: the first pull after this update can take long.
		if (pullRepairPending() || opts.fromStart) toast.info(FULL_WALK_NOTICE);
		const result = await api.shots.pullAndReconcile(history, {
			...opts,
			onProgress: (p) => (pullProgress = { fetched: p.fetched, page: p.page }),
			onWaiting: (ms) => (pullWaitingUntil = Date.now() + ms)
		});
		pullWaitingUntil = null;
		if (result.outcome.kind === 'Busy') toast.info('Another tab is syncing with Visualizer.');
		if (result.refused > 0) toast.error(historyFullNotice(result.refused, history.capacity));
		return result;
	}

	/** What the first pull after the update, or a re-pull, says before it starts. */
	const FULL_WALK_NOTICE =
		'Crema is checking your whole Visualizer account once. On a large account this takes a while, and shots it was missing come back. You can keep using the app.';
	async function svcUploadUnsynced(): Promise<void> {
		const result = await appCtx().services?.shots.uploadUnsynced(history);
		// The free-plan daily cap / rate limit stopped the pass — say why.
		const notice = describeUnsyncedStop(result);
		if (notice) toast[notice.kind](notice.message);
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
	/** Pull-pagination progress — non-null only while shots are streaming in. */
	let pullProgress = $state<{ fetched: number; page: number } | null>(null);
	/** Set while the pull sits out Visualizer's rate limit: when it resumes. */
	let pullWaitingUntil = $state<number | null>(null);

	onMount(() => {
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
		const wasPushing = directionPushes(config.direction.shots);
		config = updateSyncConfig({
			direction: { ...config.direction, [entity]: direction }
		});
		if (entity === 'shots' && !wasPushing && directionPushes(direction)) onShotPushEnabled?.();
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
				// The run moves the cursor itself, only when it walked everything,
				// and writes its own line to the sync log.
				try {
					await svcPullAndReconcile({});
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
	 * from time 0), and try again the rows marked as failing. Rows already
	 * here bind or skip, so this only fills gaps — e.g. after clearing local
	 * history or switching devices — rather than creating duplicates.
	 */
	async function resyncAllShots(): Promise<void> {
		if (syncing || resyncing) return;
		if (
			!(await confirmDialog({
				message:
					'Re-pull every shot from Visualizer from the beginning? Existing shots are de-duplicated, so this only adds ones missing locally.',
				confirmLabel: 'Re-pull'
			}))
		) {
			return;
		}
		resyncing = true;
		try {
			await svcPullAndReconcile({ fromStart: true });
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

	const unsyncedShotCount = $derived(history.visualizerBacklog.length);
	const noTelemetryCount = $derived(history.withoutTelemetry.length);
</script>

{#if viz.connected}
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
			sub={`${library.beans.length} bag(s). Last sync: ${syncTimeLabel(config.lastSyncAt.beans ?? beanLastSync)}.`}
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
							<LockKeyIcon aria-hidden="true" />
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
			sub={`${library.roasters.length} roaster(s). Last sync: ${syncTimeLabel(config.lastSyncAt.roasters ?? beanLastSync)}.`}
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
							<LockKeyIcon aria-hidden="true" />
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
			sub={`${history.all.length} shot(s) on this device${unsyncedShotCount > 0 ? `, ${unsyncedShotCount} unsynced` : ''}${noTelemetryCount > 0 ? `, ${noTelemetryCount} without telemetry` : ''}. Last sync: ${syncTimeLabel(config.lastSyncAt.shots)}.`}
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
			sub={pullWaitingUntil
				? `Visualizer asked Crema to slow down. The pull goes on at ${new Date(pullWaitingUntil).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}.`
				: pullProgress
					? `Pulling shots — ${pullProgress.fetched} pulled (page ${pullProgress.page})…`
					: 'Run a one-shot full sync across every enabled entity.'}
		>
			{#snippet control()}
				<button
					type="button"
					class="bs-btn bs-btn-primary"
					disabled={syncing}
					onclick={syncNow}
				>
					<Icon
						cls={syncing ? 'ph ph-spinner-gap bs-spin' : 'ph ph-arrows-clockwise'}
						aria-hidden="true"
					 />
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
					<Icon
						cls={resyncing ? 'ph ph-spinner-gap bs-spin' : 'ph ph-clock-counter-clockwise'}
						aria-hidden="true"
					 />
					{resyncing ? 'Re-syncing…' : 'Re-sync all'}
				</button>
			{/snippet}
		</StRow>

		<!-- The activity log moved to the shared `ActivityLog` (Sharing section) — one log across destinations. -->

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
	:global(.bs-spin) {
		animation: bs-spin 1.1s linear infinite;
	}
	@keyframes bs-spin {
		from { transform: rotate(0); }
		to { transform: rotate(360deg); }
	}
	.bs-hint-dim {
		font-family: var(--font-sans);
		font-size: 11px;
		color: var(--fg-muted);
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
		color: var(--fg-3);
		text-decoration: none;
	}
	.bs-premium-tag :global(svg) {
		font-size: 12px;
		opacity: 0.75;
	}
	.bs-premium-tag:hover {
		color: var(--fg-accent);
	}
	.bs-premium-tag:hover :global(svg) {
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
		color: var(--fg-accent);
	}
</style>
