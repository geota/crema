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
	 * **Stubbed** — Export, Compare and Save-as-profile are `// TODO`s.
	 */
	import { getHistoryStore } from '$lib/history';
	import { ShotRow, ShotDetail } from '$lib/components/history';

	const store = getHistoryStore();

	/** Every recorded shot, newest first. Reactive. */
	const shots = $derived(store.all);

	// ── Filter / search state ────────────────────────────────────────────
	/** The search query. */
	let q = $state('');
	/** The active profile filter — `all` or a profile name. */
	let filterProfile = $state('all');
	/** The active range selector — UI-only for now. */
	let range = $state<'30d' | 'all'>('all');
	/** The selected shot's id. */
	let selectedId = $state<string | null>(null);

	/** Milliseconds in a day — shared by the range filter and the stats. */
	const dayMs = 24 * 60 * 60 * 1000;

	/** Distinct profile names across the history, for the filter chips. */
	const profilesInUse = $derived([
		...new Set(shots.map((s) => s.profileName).filter((n): n is string => !!n))
	]);

	/** The filtered shot list the list pane renders. */
	const filtered = $derived.by(() => {
		const query = q.trim().toLowerCase();
		// The "Last 30 days" range cuts off shots older than the window.
		const cutoff = range === '30d' ? Date.now() - 30 * dayMs : null;
		return shots.filter((s) => {
			if (cutoff != null && s.completedAt < cutoff) return false;
			if (filterProfile !== 'all' && s.profileName !== filterProfile) return false;
			if (query === '') return true;
			return (
				(s.profileName ?? '').toLowerCase().includes(query) ||
				s.notes.toLowerCase().includes(query)
			);
		});
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
	 * ratio: `ShotRecord` carries no dose, so a `yield ÷ dose` ratio would have
	 * to assume one fixed dose and mislead for every other.
	 */
	const avgYield = $derived.by(() => {
		const yields = shots
			.map((s) => s.finalWeightG ?? s.peakWeightG)
			.filter((y): y is number => y != null && y > 0);
		if (yields.length === 0) return null;
		const mean = yields.reduce((a, y) => a + y, 0) / yields.length;
		return mean.toFixed(1);
	});
	/** Mean shot duration, seconds. */
	const avgTime = $derived.by(() => {
		if (shots.length === 0) return null;
		const mean = shots.reduce((a, s) => a + s.durationMs, 0) / shots.length / 1000;
		return Math.round(mean);
	});
	/** Mean star rating across rated shots. */
	const avgRating = $derived.by(() => {
		const rated = shots.filter((s) => s.rating > 0);
		if (rated.length === 0) return null;
		return (rated.reduce((a, s) => a + s.rating, 0) / rated.length).toFixed(1);
	});

	/** Select a shot. */
	function select(id: string): void {
		selectedId = id;
	}
</script>

<svelte:head>
	<title>Crema — History</title>
</svelte:head>

<div class="qcontain hi-page">
	<!-- Header -->
	<div class="hi-head">
		<div>
			<div class="t-eyebrow" style="color:rgba(244,237,224,0.55)">Library</div>
			<div class="hi-title">Shot history</div>
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
			<button class="st-btn st-btn-secondary" disabled={shots.length === 0}>
				<i class="ph ph-download-simple" aria-hidden="true"></i> Export
			</button>
			<button class="st-btn st-btn-secondary" disabled={shots.length === 0}>
				<i class="ph ph-arrows-left-right" aria-hidden="true"></i> Compare
			</button>
		</div>
	</div>

	{#if shots.length === 0}
		<!-- Empty state — no shots recorded yet. -->
		<div class="hi-empty-page">
			<div class="hi-empty-glyph"><i class="ph ph-chart-line" aria-hidden="true"></i></div>
			<div class="hi-empty-title">No shots recorded yet</div>
			<div class="hi-empty-sub">
				Pull a shot on the Brew page with a connected DE1 — when it completes it is
				saved here with its full telemetry curve.
			</div>
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
					<span>{avgYield ?? '—'}</span>{#if avgYield != null}<em>g</em>{/if}
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

		<!-- Filter strip -->
		<div class="hi-filters">
			<div class="hi-prof-filters">
				<button
					class="pp-tag"
					class:is-active={filterProfile === 'all'}
					onclick={() => (filterProfile = 'all')}
				>
					<span>All profiles</span>
					<span class="pp-tag-count">{shots.length}</span>
				</button>
				{#each profilesInUse as name (name)}
					<button
						class="pp-tag"
						class:is-active={filterProfile === name}
						onclick={() => (filterProfile = name)}
					>
						<span>{name}</span>
						<span class="pp-tag-count">
							{shots.filter((s) => s.profileName === name).length}
						</span>
					</button>
				{/each}
			</div>
			<div class="pp-sort">
				<span class="t-eyebrow" style="color:rgba(244,237,224,0.45)">Range</span>
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
		</div>

		<!-- Split pane: list + detail -->
		<div class="hi-split">
			<div class="hi-list">
				{#each filtered as s (s.id)}
					<ShotRow
						shot={s}
						active={selected?.id === s.id}
						onclick={() => select(s.id)}
					/>
				{/each}
				{#if filtered.length === 0}
					<div class="hi-empty">No shots match.</div>
				{/if}
			</div>
			{#if selected}
				<ShotDetail
					shot={selected}
					onNotesChange={(notes) => store.setNotes(selected.id, notes)}
					onRatingChange={(rating) => store.setRating(selected.id, rating)}
				/>
			{:else}
				<div class="hi-detail-empty">Select a shot to see its detail.</div>
			{/if}
		</div>
	{/if}
</div>

<style>
	.hi-page {
		background: var(--espresso-950);
		color: var(--ink-50);
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
	.hi-title {
		font-family: var(--font-serif);
		font-size: 36px;
		letter-spacing: -0.02em;
		color: var(--ink-50);
		margin-top: 2px;
	}
	.hi-sub {
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(244, 237, 224, 0.5);
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
		background: rgba(244, 237, 224, 0.04);
		border: 1px solid rgba(244, 237, 224, 0.08);
		border-radius: var(--radius-pill);
		padding: 0 12px;
		width: 260px;
	}
	.hi-search:focus-within {
		border-color: rgba(244, 237, 224, 0.2);
	}
	.hi-search i {
		color: rgba(244, 237, 224, 0.4);
		font-size: 13px;
	}
	.hi-search input {
		flex: 1 1 auto;
		min-width: 0;
		background: transparent;
		border: 0;
		outline: 0;
		color: var(--ink-50);
		font-family: var(--font-sans);
		font-size: 12px;
		padding: 8px 6px;
	}
	.hi-search input::placeholder {
		color: rgba(244, 237, 224, 0.35);
	}

	/* The .st-btn family is shared globally — see settings-page.css. */

	/* Stats strip */
	.hi-stats {
		display: grid;
		grid-template-columns: repeat(6, minmax(0, 1fr));
		gap: 12px;
	}
	.hi-stat {
		background: var(--espresso-900);
		border: 1px solid rgba(244, 237, 224, 0.05);
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
		color: rgba(244, 237, 224, 0.45);
	}
	.hi-stat-val {
		display: flex;
		align-items: baseline;
		gap: 4px;
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 22px;
		color: var(--ink-50);
		letter-spacing: -0.01em;
	}
	.hi-stat-val em {
		font-style: normal;
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(244, 237, 224, 0.5);
		margin-left: 1px;
	}

	/* Filter row */
	.hi-filters {
		display: flex;
		justify-content: space-between;
		align-items: center;
		gap: 16px;
		padding-bottom: 14px;
		border-bottom: 1px solid rgba(244, 237, 224, 0.05);
	}
	.hi-prof-filters {
		display: flex;
		gap: 4px;
		overflow-x: auto;
	}
	.pp-tag {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		padding: 7px 14px;
		background: transparent;
		border: 0;
		color: rgba(244, 237, 224, 0.6);
		font-family: var(--font-sans);
		font-size: 13px;
		cursor: pointer;
		border-radius: var(--radius-pill);
		transition: all var(--dur-1) var(--ease);
		white-space: nowrap;
	}
	.pp-tag:hover {
		color: var(--ink-50);
	}
	.pp-tag.is-active {
		color: var(--ink-50);
		background: rgba(244, 237, 224, 0.06);
	}
	.pp-tag-count {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 10px;
		color: rgba(244, 237, 224, 0.4);
		padding: 1px 6px;
		background: rgba(244, 237, 224, 0.04);
		border-radius: 999px;
	}
	.pp-tag.is-active .pp-tag-count {
		background: var(--copper-500);
		color: #1a120c;
	}
	.pp-sort {
		display: flex;
		align-items: center;
		gap: 10px;
		flex: 0 0 auto;
	}
	.pp-sort-opt {
		background: transparent;
		border: 0;
		color: rgba(244, 237, 224, 0.5);
		font-family: var(--font-sans);
		font-size: 12px;
		cursor: pointer;
		padding: 4px 2px;
		transition: color var(--dur-1) var(--ease);
	}
	.pp-sort-opt:hover {
		color: var(--ink-50);
	}
	.pp-sort-opt.is-active {
		color: var(--ink-50);
		text-decoration: underline;
		text-decoration-color: var(--copper-500);
		text-decoration-thickness: 1.5px;
		text-underline-offset: 4px;
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
		background: var(--espresso-900);
		border: 1px solid rgba(244, 237, 224, 0.05);
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
		color: rgba(244, 237, 224, 0.4);
	}
	.hi-detail-empty {
		display: flex;
		align-items: center;
		justify-content: center;
		background: var(--espresso-900);
		border: 1px solid rgba(244, 237, 224, 0.05);
		border-radius: var(--radius-lg, 14px);
		color: rgba(244, 237, 224, 0.4);
		font-family: var(--font-sans);
		font-size: 13px;
		min-height: 200px;
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
		background: rgba(244, 237, 224, 0.05);
		display: flex;
		align-items: center;
		justify-content: center;
		color: rgba(244, 237, 224, 0.5);
		font-size: 30px;
		margin-bottom: 6px;
	}
	.hi-empty-title {
		font-family: var(--font-serif);
		font-size: 22px;
		color: var(--ink-50);
	}
	.hi-empty-sub {
		font-family: var(--font-sans);
		font-size: 13px;
		line-height: 1.55;
		color: rgba(244, 237, 224, 0.5);
		max-width: 420px;
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
