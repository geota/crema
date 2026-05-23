<script lang="ts">
	/**
	 * Beans — the `/beans` route: the bean library + roaster directory.
	 *
	 * Two tabs: **Bags** (default) shows the bean library with search /
	 * filter / sort; **Roasters** shows the roastery directory. Bag cards
	 * mirror the visual + interaction pattern of the profile cards on
	 * `/profiles` — favourite star top-right, full-width "Set active"
	 * primary button at the bottom, and a row of Edit / Archive / Delete
	 * icon buttons. Clicking the card body opens the inline editor drawer.
	 *
	 * Cards live in `$lib/components/beans/BeanCard.svelte`; this page
	 * holds the header (search + import + new), the filter strip (facet
	 * pills + tag chips + sort), and the grid + roaster tab.
	 *
	 * Built per docs/28 §UX and §library-view; card pattern mirrors
	 * `$lib/components/profiles/ProfileCard.svelte`.
	 */
	import {
		getBeanStore,
		blankBean,
		blankRoaster,
		type Bean
	} from '$lib/bean';
	import BeanEditor from '$lib/components/beans/BeanEditor.svelte';
	import BeanImportDialog from '$lib/components/beans/BeanImportDialog.svelte';
	import BeanCard from '$lib/components/beans/BeanCard.svelte';
	import { getHistoryStore } from '$lib/history';

	const library = getBeanStore();
	const history = getHistoryStore();

	// ── UI state ───────────────────────────────────────────────────────
	let tab = $state<'bags' | 'roasters'>('bags');
	let q = $state('');
	type FacetId = 'all' | 'favourite' | 'active' | 'frozen' | 'decaf';
	let facet = $state<FacetId>('all');
	/**
	 * "Show archived" filter — independent of the main facet pills, since
	 * archived is a mode (visible/hidden) rather than another filter. Off
	 * by default so archived bags stay out of sight after a roast finishes.
	 */
	let showArchived = $state(false);
	/**
	 * Tag-filter selection — the set of tags the user has clicked on the
	 * filter chip row. AND-semantics: a bean must contain every selected
	 * tag to pass through. Empty set = all beans pass.
	 */
	let selectedTags = $state<string[]>([]);
	let sort = $state<'recent' | 'name' | 'roastedOn' | 'rating'>('recent');
	let sortDir = $state<'asc' | 'desc'>('desc');
	let selectedBeanId = $state<string | null>(null);
	let showImport = $state(false);
	/**
	 * Draft bean for the "New bag" flow. Held off the store until the user
	 * hits Save in the editor, so an abandoned form leaves no trace (and a
	 * partially-typed roaster name doesn't pollute the roaster directory).
	 * When set, the editor mounts in `isNew` mode.
	 */
	let newDraft = $state<Bean | null>(null);

	// ── Derived lists ──────────────────────────────────────────────────
	const allBeans = $derived(library.beans);
	const allRoasters = $derived(library.roasters);

	/**
	 * Bean counts per facet for the chip badges. The `archived` count is
	 * independent of the show-archived toggle (so the toggle's badge keeps
	 * its true number); the other facets follow what the grid would render
	 * given the current `showArchived` mode.
	 */
	const counts = $derived.by(() => {
		const c = {
			all: 0,
			favourite: 0,
			active: 0,
			archived: 0,
			frozen: 0,
			decaf: 0
		};
		for (const b of allBeans) {
			const archived = b.archivedAt != null;
			if (archived) c.archived += 1;
			else c.active += 1;
			const visible = !archived || showArchived;
			if (visible) c.all += 1;
			if (visible && b.favourite) c.favourite += 1;
			if (visible && b.frozenOn && !b.defrostedOn) c.frozen += 1;
			if (visible && b.decaf) c.decaf += 1;
		}
		return c;
	});

	/**
	 * Union of every tag used across the library — drives the tag-filter
	 * chip row, sorted by usage frequency (most-used first) so the chip
	 * order is stable across renders. Mirrors the profile page's
	 * `customTags` $derived.
	 */
	const tagFacets = $derived.by(() => {
		const counts = new Map<string, number>();
		for (const b of allBeans) {
			if (b.archivedAt != null && !showArchived) continue;
			for (const t of b.tags) counts.set(t, (counts.get(t) ?? 0) + 1);
		}
		return [...counts.entries()]
			.map(([tag, count]) => ({ tag, count }))
			.sort((a, b) => b.count - a.count || a.tag.localeCompare(b.tag));
	});

	/** All tags ever seen in the library — feeds the editor's autocomplete. */
	const allTagSuggestions = $derived.by(() => {
		const set = new Set<string>();
		for (const b of allBeans) for (const t of b.tags) set.add(t);
		return [...set].sort((a, b) => a.localeCompare(b));
	});

	function matchesFacet(b: Bean, f: FacetId): boolean {
		switch (f) {
			case 'all':
				return true;
			case 'favourite':
				return b.favourite;
			case 'active':
				return b.archivedAt == null;
			case 'frozen':
				return !!b.frozenOn && !b.defrostedOn;
			case 'decaf':
				return b.decaf;
			default:
				return true;
		}
	}

	const filtered = $derived.by(() => {
		const query = q.trim().toLowerCase();
		const r = allBeans.filter((b) => {
			const archived = b.archivedAt != null;
			// Archived gate — the dedicated toggle owns visibility.
			if (archived && !showArchived) return false;
			if (!matchesFacet(b, facet)) return false;
			// Tag AND-filter — bean must contain every selected tag.
			if (selectedTags.length > 0) {
				for (const t of selectedTags) {
					if (!b.tags.includes(t)) return false;
				}
			}
			if (!query) return true;
			const roaster = b.roasterId ? library.getRoaster(b.roasterId) : null;
			return (
				b.name.toLowerCase().includes(query) ||
				roaster?.name.toLowerCase().includes(query) ||
				b.origin.country?.toLowerCase().includes(query) ||
				b.origin.region?.toLowerCase().includes(query) ||
				b.tastingNotes.toLowerCase().includes(query) ||
				b.notes.toLowerCase().includes(query) ||
				b.tags.some((t) => t.toLowerCase().includes(query))
			);
		});
		const keyCmp = (a: Bean, b: Bean): number => {
			switch (sort) {
				case 'recent':
					return a.updatedAt - b.updatedAt;
				case 'name':
					return a.name.localeCompare(b.name);
				case 'roastedOn':
					// Unset dates sink.
					if (!a.roastedOn && !b.roastedOn) return 0;
					if (!a.roastedOn) return 1;
					if (!b.roastedOn) return -1;
					return a.roastedOn.localeCompare(b.roastedOn);
				case 'rating':
					return a.rating - b.rating;
				default:
					return 0;
			}
		};
		const dir = sortDir === 'desc' ? -1 : 1;
		return [...r].sort((a, b) => keyCmp(a, b) * dir || a.name.localeCompare(b.name));
	});

	// Shot counts per bean — drives the "shots brewed" subline + the
	// per-bean profile recommendation (innovation #1).
	const shotCounts = $derived.by(() => {
		const counts = new Map<string, number>();
		for (const s of history.all) {
			const id = s.bean?.beanId;
			if (id) counts.set(id, (counts.get(id) ?? 0) + 1);
		}
		return counts;
	});

	// ── Per-bean profile recommendation (innovation #1) ────────────────
	function recommendedProfile(beanId: string): string | null {
		// Average rating per (beanId, profileName). Need ≥3 shots for signal.
		const scores = new Map<string, { sum: number; n: number }>();
		for (const s of history.all) {
			if (s.bean?.beanId !== beanId) continue;
			if (!s.profileName || s.rating <= 0) continue;
			const cur = scores.get(s.profileName) ?? { sum: 0, n: 0 };
			cur.sum += s.rating;
			cur.n += 1;
			scores.set(s.profileName, cur);
		}
		let best: { name: string; avg: number; n: number } | null = null;
		for (const [name, { sum, n }] of scores) {
			if (n < 3) continue;
			const avg = sum / n;
			if (!best || avg > best.avg) best = { name, avg, n };
		}
		if (!best) return null;
		return `${best.name} · avg ★${best.avg.toFixed(1)} over ${best.n} shots`;
	}

	// ── Card actions ───────────────────────────────────────────────────
	function newBean(): void {
		// Don't upsert yet — open the editor in `isNew` mode so the draft
		// only hits the store on Save. Prevents abandoned-form pollution
		// (which the legacy `upsertBean(fresh)` here caused — a "New bag"
		// row appeared the moment the button was clicked, even if the
		// user closed the drawer right after).
		newDraft = blankBean();
	}

	function makeActive(id: string): void {
		library.setActiveBean(id);
	}

	function archive(id: string): void {
		library.toggleArchived(id);
	}

	function pin(id: string): void {
		library.toggleFavourite(id);
	}

	function del(id: string): void {
		const b = library.getBean(id);
		if (!b) return;
		if (confirm(`Delete "${b.name || 'this bag'}"? This cannot be undone.`)) {
			library.deleteBean(id);
			if (selectedBeanId === id) selectedBeanId = null;
		}
	}

	function newRoaster(): void {
		const name = prompt('Roaster name')?.trim();
		if (!name) return;
		const r = blankRoaster(name);
		library.upsertRoaster(r);
	}

	function toggleSelectedTag(t: string): void {
		selectedTags = selectedTags.includes(t)
			? selectedTags.filter((x) => x !== t)
			: [...selectedTags, t];
	}

	// ── Roaster directory derived ──────────────────────────────────────
	const roasterRows = $derived.by(() => {
		return [...allRoasters]
			.map((r) => ({
				roaster: r,
				count: allBeans.filter((b) => b.roasterId === r.id).length
			}))
			.sort((a, b) => b.count - a.count || a.roaster.name.localeCompare(b.roaster.name));
	});
</script>

<svelte:head>
	<title>Crema — Beans</title>
</svelte:head>

<div class="pp-page">
	<div class="pp-head">
		<div class="pp-head-l">
			<div class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.55)">Library</div>
			<div class="t-page-title pp-title">Beans</div>
			<div class="pp-sub">
				{counts.active} active · {counts.favourite} pinned · {allRoasters.length} roasters
			</div>
		</div>
		<div class="pp-head-r">
			<div class="pp-search">
				<i class="ph ph-magnifying-glass" aria-hidden="true"></i>
				<input bind:value={q} placeholder="Search beans, roasters, origin, tags…" />
				{#if q}
					<button class="pp-search-clear" aria-label="Clear search" onclick={() => (q = '')}>
						<i class="ph ph-x" aria-hidden="true"></i>
					</button>
				{/if}
			</div>
			<button
				class="pp-btn pp-btn-secondary"
				onclick={() => (showImport = true)}
				title="Import from a Beanconqueror .zip export"
			>
				<i class="ph ph-upload-simple" aria-hidden="true"></i>
				Import
			</button>
			<button class="pp-btn pp-btn-primary" onclick={newBean}>
				<i class="ph ph-plus" aria-hidden="true"></i>
				New bag
			</button>
		</div>
	</div>

	<!-- Tabs -->
	<div class="bn-tabs">
		<button class="bn-tab" class:is-active={tab === 'bags'} onclick={() => (tab = 'bags')}>
			<i class="ph ph-coffee-bean" aria-hidden="true"></i>
			<span>Bags</span>
			<span class="pp-tag-count">{counts.active}</span>
		</button>
		<button
			class="bn-tab"
			class:is-active={tab === 'roasters'}
			onclick={() => (tab = 'roasters')}
		>
			<i class="ph ph-buildings" aria-hidden="true"></i>
			<span>Roasters</span>
			<span class="pp-tag-count">{allRoasters.length}</span>
		</button>
	</div>

	{#if tab === 'bags'}
		<!-- Filter strip -->
		<div class="pp-filters">
			<div class="pp-tags">
				<button class="pp-tag" class:is-active={facet === 'all'} onclick={() => (facet = 'all')}>
					<span>All</span><span class="pp-tag-count">{counts.all}</span>
				</button>
				<button
					class="pp-tag"
					class:is-active={facet === 'favourite'}
					onclick={() => (facet = 'favourite')}
				>
					<i class="ph-fill ph-star" style="font-size:11px;color:var(--copper-400)"></i>
					<span>Pinned</span><span class="pp-tag-count">{counts.favourite}</span>
				</button>
				<button
					class="pp-tag"
					class:is-active={facet === 'frozen'}
					onclick={() => (facet = 'frozen')}
				>
					<i class="ph ph-snowflake" style="font-size:11px"></i>
					<span>Frozen</span><span class="pp-tag-count">{counts.frozen}</span>
				</button>
				<button
					class="pp-tag"
					class:is-active={facet === 'decaf'}
					onclick={() => (facet = 'decaf')}
				>
					<span>Decaf</span><span class="pp-tag-count">{counts.decaf}</span>
				</button>
				{#if counts.archived > 0}
					<span class="pp-tag-divider"></span>
					<button
						class="pp-tag"
						class:is-active={showArchived}
						onclick={() => (showArchived = !showArchived)}
						title={showArchived
							? 'Hide archived bags from the grid'
							: 'Show archived bags in the grid'}
					>
						<i
							class={showArchived ? 'ph-fill ph-eye' : 'ph ph-eye-slash'}
							style="font-size:11px"
						></i>
						<span>Show archived</span><span class="pp-tag-count">{counts.archived}</span>
					</button>
				{/if}
			</div>
			<div class="pp-sort">
				<span class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.45)">Sort</span>
				<button
					class="pp-sort-opt"
					class:is-active={sort === 'recent'}
					onclick={() => (sort = 'recent')}
				>
					Recent
				</button>
				<button
					class="pp-sort-opt"
					class:is-active={sort === 'roastedOn'}
					onclick={() => (sort = 'roastedOn')}
				>
					Roast date
				</button>
				<button
					class="pp-sort-opt"
					class:is-active={sort === 'name'}
					onclick={() => (sort = 'name')}
				>
					Name
				</button>
				<button
					class="pp-sort-opt"
					class:is-active={sort === 'rating'}
					onclick={() => (sort = 'rating')}
				>
					Rating
				</button>
				<span class="pp-sort-divider"></span>
				<button
					class="pp-sort-opt"
					class:is-active={sortDir === 'desc'}
					onclick={() => (sortDir = 'desc')}
				>
					Desc
				</button>
				<button
					class="pp-sort-opt"
					class:is-active={sortDir === 'asc'}
					onclick={() => (sortDir = 'asc')}
				>
					Asc
				</button>
			</div>
		</div>

		{#if tagFacets.length > 0}
			<div class="bn-tag-strip">
				<span class="pp-tag-grouplabel">Tags</span>
				{#each tagFacets as t (t.tag)}
					<button
						class="pp-tag pp-tag-custom"
						class:is-active={selectedTags.includes(t.tag)}
						onclick={() => toggleSelectedTag(t.tag)}
					>
						<span>{t.tag}</span><span class="pp-tag-count">{t.count}</span>
					</button>
				{/each}
				{#if selectedTags.length > 0}
					<button class="bn-tag-clear" onclick={() => (selectedTags = [])}>
						<i class="ph ph-x" aria-hidden="true"></i> Clear
					</button>
				{/if}
			</div>
		{/if}

		<!-- Bean grid -->
		<div class="bn-grid">
			{#each filtered as bean (bean.id)}
				<BeanCard
					{bean}
					roaster={bean.roasterId ? library.getRoaster(bean.roasterId) : null}
					isActive={library.activeBeanId === bean.id}
					shots={shotCounts.get(bean.id) ?? 0}
					recommendation={recommendedProfile(bean.id)}
					onOpen={(id) => (selectedBeanId = id)}
					onSetActive={makeActive}
					onToggleFavourite={pin}
					onToggleArchived={archive}
					onDelete={del}
				/>
			{/each}
			<button class="bn-card-new" onclick={newBean}>
				<div class="bn-card-new-glyph"><i class="ph ph-plus" aria-hidden="true"></i></div>
				<div class="bn-card-new-label">New bag</div>
				<div class="bn-card-new-sub">Track a fresh roast</div>
			</button>
			{#if filtered.length === 0}
				<div class="bn-empty">
					{#if allBeans.length === 0}
						<i class="ph-duotone ph-coffee-bean" aria-hidden="true"></i>
						<div class="bn-empty-title">No beans in your library yet</div>
						<div class="bn-empty-sub">
							Add your first bag — or import one from Beanconqueror.
						</div>
						<div class="bn-empty-actions">
							<button class="pp-btn pp-btn-primary" onclick={newBean}>
								<i class="ph ph-plus" aria-hidden="true"></i> Add your first bag
							</button>
							<button class="pp-btn pp-btn-secondary" onclick={() => (showImport = true)}>
								<i class="ph ph-upload-simple" aria-hidden="true"></i> Import from Beanconqueror
							</button>
						</div>
					{:else}
						No bags match the current filters.
						{#if q}<button class="pp-empty-link" onclick={() => (q = '')}>Clear search</button
							>{/if}
						{#if selectedTags.length > 0}<button
								class="pp-empty-link"
								onclick={() => (selectedTags = [])}>Clear tags</button
							>{/if}
					{/if}
				</div>
			{/if}
		</div>
	{:else}
		<!-- Roasters tab -->
		<div class="pp-filters">
			<div class="pp-tags">
				<span class="pp-tag-grouplabel">Sort</span>
				<span class="pp-tag is-active"><span>By bag count</span></span>
			</div>
			<div class="pp-sort">
				<button class="pp-sort-opt is-active" onclick={newRoaster}>+ New roaster</button>
			</div>
		</div>
		<div class="bn-grid">
			{#each roasterRows as { roaster, count } (roaster.id)}
				<div class="bn-roastcard">
					<div class="bn-card-head">
						<div class="bn-card-eyebrow">Roastery</div>
					</div>
					<div class="bn-card-name">{roaster.name}</div>
					<div class="bn-card-meta">
						{[roaster.country, roaster.website].filter(Boolean).join(' · ') || 'No location'}
					</div>
					<div class="bn-card-row">
						<span class="bn-shots">{count} bag{count === 1 ? '' : 's'}</span>
					</div>
					<div class="bn-card-actions">
						{#if roaster.website}
							<a
								class="bn-iconbtn"
								href={roaster.website}
								target="_blank"
								rel="noopener noreferrer"
								title="Open roastery website"
							>
								<i class="ph ph-arrow-square-out" aria-hidden="true"></i>
							</a>
						{/if}
						<button
							class="bn-iconbtn bn-iconbtn-danger"
							onclick={() => {
								if (
									confirm(
										`Delete "${roaster.name}"? Their ${count} bag(s) will stay but lose the roaster link.`
									)
								) {
									library.deleteRoaster(roaster.id);
								}
							}}
							title="Delete roastery"
						>
							<i class="ph ph-trash" aria-hidden="true"></i>
						</button>
					</div>
				</div>
			{/each}
			{#if roasterRows.length === 0}
				<div class="bn-empty">
					<i class="ph-duotone ph-buildings" aria-hidden="true"></i>
					<div class="bn-empty-title">No roasters yet</div>
					<div class="bn-empty-sub">
						Add a bag — a roaster row is created when you type a roastery name.
					</div>
				</div>
			{/if}
		</div>
	{/if}
</div>

<!-- Bean editor drawer — new-bag draft mode -->
{#if newDraft}
	<BeanEditor
		bean={newDraft}
		isNew
		isActive={false}
		tagSuggestions={allTagSuggestions}
		onClose={() => (newDraft = null)}
		onMakeActive={() => {}}
		onSaved={(id) => {
			newDraft = null;
			// Re-open in edit mode so the user lands on the persisted record
			// and can refine without re-typing — matches the "save and stay"
			// convention from ProfileEditor.
			selectedBeanId = id;
		}}
	/>
{:else if selectedBeanId}
	{@const sb = library.getBean(selectedBeanId)}
	{#if sb}
		<BeanEditor
			bean={sb}
			tagSuggestions={allTagSuggestions}
			onClose={() => (selectedBeanId = null)}
			onMakeActive={() => makeActive(sb.id)}
			isActive={library.activeBeanId === sb.id}
		/>
	{/if}
{/if}

<!-- Import dialog -->
{#if showImport}
	<BeanImportDialog onClose={() => (showImport = false)} />
{/if}

<style>
	.pp-page {
		background: var(--bg-page);
		color: var(--fg-1);
		display: flex;
		flex-direction: column;
		min-height: 100vh;
	}
	.pp-head {
		display: flex;
		align-items: flex-end;
		justify-content: space-between;
		gap: 24px;
		padding: var(--page-pad-top) var(--page-pad-x) 18px;
		flex: 0 0 auto;
	}
	.pp-head-l {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.pp-title {
		margin-top: 2px;
	}
	.pp-sub {
		font-family: var(--font-sans);
		font-size: 13px;
		color: rgba(var(--tint-rgb), 0.55);
		margin-top: 2px;
	}
	.pp-head-r {
		display: flex;
		align-items: center;
		gap: 10px;
	}
	.pp-search {
		position: relative;
		display: flex;
		align-items: center;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
		border-radius: var(--radius-pill);
		padding: 0 12px;
		width: 300px;
		transition: border-color var(--dur-1) var(--ease);
	}
	.pp-search:focus-within {
		border-color: rgba(var(--tint-rgb), 0.2);
	}
	.pp-search i {
		color: rgba(var(--tint-rgb), 0.45);
		font-size: 14px;
	}
	.pp-search input {
		flex: 1 1 auto;
		min-width: 0;
		background: transparent;
		border: 0;
		outline: 0;
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 13px;
		padding: 9px 8px;
	}
	.pp-search input::placeholder {
		color: rgba(var(--tint-rgb), 0.35);
	}
	.pp-search-clear {
		background: transparent;
		border: 0;
		padding: 4px;
		color: rgba(var(--tint-rgb), 0.4);
		cursor: pointer;
	}

	.pp-btn {
		display: inline-flex;
		align-items: center;
		gap: 7px;
		padding: 9px 16px;
		border-radius: var(--radius-pill);
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 500;
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
		border: 1px solid transparent;
		white-space: nowrap;
	}
	.pp-btn i {
		font-size: 14px;
	}
	.pp-btn-secondary {
		background: rgba(var(--tint-rgb), 0.04);
		border-color: rgba(var(--tint-rgb), 0.1);
		color: var(--fg-1);
	}
	.pp-btn-secondary:hover {
		background: rgba(var(--tint-rgb), 0.07);
	}
	.pp-btn-primary {
		background: var(--copper-500);
		color: var(--fg-on-accent);
		font-weight: 600;
	}
	.pp-btn-primary:hover {
		background: var(--copper-600);
	}

	/* Tabs */
	.bn-tabs {
		display: flex;
		gap: 8px;
		padding: 0 var(--page-pad-x) 12px;
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.05);
	}
	.bn-tab {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		padding: 8px 16px;
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.6);
		font-family: var(--font-sans);
		font-size: 13px;
		cursor: pointer;
		border-radius: var(--radius-pill);
		transition: all var(--dur-1) var(--ease);
	}
	.bn-tab i {
		font-size: 14px;
	}
	.bn-tab:hover {
		color: var(--fg-1);
	}
	.bn-tab.is-active {
		color: var(--fg-1);
		background: rgba(var(--tint-rgb), 0.06);
	}

	/* Filters (re-use the profile-page filter classes) */
	.pp-filters {
		display: flex;
		justify-content: space-between;
		align-items: center;
		gap: 24px;
		padding: 12px var(--page-pad-x) 16px;
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.05);
	}
	.pp-tags {
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
		color: rgba(var(--tint-rgb), 0.6);
		font-family: var(--font-sans);
		font-size: 13px;
		cursor: pointer;
		border-radius: var(--radius-pill);
		transition: all var(--dur-1) var(--ease);
		white-space: nowrap;
	}
	.pp-tag:hover {
		color: var(--fg-1);
	}
	.pp-tag.is-active {
		color: var(--fg-1);
		background: rgba(var(--tint-rgb), 0.06);
	}
	.pp-tag-count {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 10px;
		color: rgba(var(--tint-rgb), 0.4);
		padding: 1px 6px;
		background: rgba(var(--tint-rgb), 0.04);
		border-radius: 999px;
	}
	.pp-tag.is-active .pp-tag-count {
		background: var(--copper-500);
		color: var(--fg-on-accent);
	}
	.pp-tag-custom .pp-tag-count {
		background: rgba(193, 116, 75, 0.1);
		color: var(--copper-400);
	}
	.pp-tag-custom.is-active {
		background: rgba(193, 116, 75, 0.12);
		color: var(--copper-400);
	}
	.pp-tag-custom.is-active .pp-tag-count {
		background: var(--copper-500);
		color: var(--fg-on-accent);
	}
	.pp-tag-divider {
		width: 1px;
		height: 16px;
		background: rgba(var(--tint-rgb), 0.1);
		margin: 0 6px;
		align-self: center;
	}
	.pp-tag-grouplabel {
		font-family: var(--font-sans);
		font-size: 10px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		font-weight: 700;
		color: rgba(var(--tint-rgb), 0.4);
		padding: 0 2px;
		align-self: center;
		white-space: nowrap;
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
	.pp-sort-divider {
		display: inline-block;
		width: 1px;
		height: 16px;
		margin: 0 4px;
		background: rgba(var(--tint-rgb), 0.15);
	}

	/* Tag filter row — lives between the facet pills and the grid. */
	.bn-tag-strip {
		display: flex;
		align-items: center;
		gap: 6px;
		flex-wrap: wrap;
		padding: 12px var(--page-pad-x) 0;
	}
	.bn-tag-clear {
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
	}
	.bn-tag-clear:hover {
		color: var(--fg-1);
	}

	/* Bean grid + roaster card */
	.bn-grid {
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
		gap: 18px;
		padding: 24px var(--page-pad-x) 40px;
	}
	@media (max-width: 1100px) {
		.bn-grid {
			grid-template-columns: repeat(2, minmax(0, 1fr));
		}
	}
	@media (max-width: 720px) {
		.bn-grid {
			grid-template-columns: minmax(0, 1fr);
		}
	}
	.bn-roastcard {
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
		border-radius: var(--radius-lg, 14px);
		padding: 16px;
		display: flex;
		flex-direction: column;
		gap: 8px;
		text-align: left;
		color: var(--fg-1);
		font: inherit;
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	.bn-roastcard:hover {
		background: rgba(var(--tint-rgb), 0.06);
		border-color: rgba(var(--tint-rgb), 0.16);
	}
	.bn-card-head {
		display: flex;
		justify-content: space-between;
		align-items: center;
	}
	.bn-card-eyebrow {
		font-family: var(--font-sans);
		font-size: 10px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		font-weight: 600;
		color: rgba(var(--tint-rgb), 0.5);
		display: inline-flex;
		gap: 8px;
		align-items: center;
	}
	.bn-card-name {
		font-family: var(--font-sans);
		font-size: 16px;
		font-weight: 600;
	}
	.bn-card-meta {
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.bn-card-row {
		display: flex;
		justify-content: space-between;
		align-items: center;
		font-family: var(--font-sans);
		font-size: 11px;
	}
	.bn-shots {
		color: rgba(var(--tint-rgb), 0.45);
	}
	.bn-card-actions {
		display: flex;
		gap: 4px;
		margin-top: 4px;
	}
	.bn-iconbtn {
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.55);
		font-size: 14px;
		padding: 6px;
		border-radius: var(--radius-sm);
		cursor: pointer;
		text-decoration: none;
		display: inline-flex;
		align-items: center;
		justify-content: center;
	}
	.bn-iconbtn:hover {
		background: rgba(var(--tint-rgb), 0.08);
		color: var(--fg-1);
	}
	.bn-iconbtn-danger:hover {
		color: var(--danger);
	}

	/* "New bag" dashed tile */
	.bn-card-new {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 8px;
		background: transparent;
		border: 1px dashed rgba(var(--tint-rgb), 0.15);
		border-radius: var(--radius-lg, 14px);
		color: var(--fg-1);
		cursor: pointer;
		min-height: 300px;
		padding: 24px;
		font-family: var(--font-sans);
		transition: all var(--dur-1) var(--ease);
	}
	.bn-card-new:hover {
		border-color: rgba(var(--tint-rgb), 0.3);
		background: rgba(var(--tint-rgb), 0.02);
	}
	.bn-card-new-glyph {
		width: 48px;
		height: 48px;
		border-radius: 50%;
		background: rgba(var(--tint-rgb), 0.05);
		display: flex;
		align-items: center;
		justify-content: center;
		color: rgba(var(--tint-rgb), 0.6);
		font-size: 22px;
		margin-bottom: 4px;
		transition: all var(--dur-1) var(--ease);
	}
	.bn-card-new:hover .bn-card-new-glyph {
		background: var(--copper-500);
		color: var(--fg-on-accent);
	}
	.bn-card-new-label {
		font-size: 14px;
		font-weight: 500;
	}
	.bn-card-new-sub {
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.45);
	}

	.bn-empty {
		grid-column: 1 / -1;
		text-align: center;
		padding: 60px 20px;
		color: rgba(var(--tint-rgb), 0.55);
		font-family: var(--font-sans);
	}
	.bn-empty i {
		font-size: 64px;
		color: rgba(var(--tint-rgb), 0.25);
	}
	.bn-empty-title {
		font-size: 18px;
		font-weight: 600;
		color: var(--fg-1);
		margin-top: 12px;
	}
	.bn-empty-sub {
		font-size: 13px;
		margin-top: 4px;
	}
	.bn-empty-actions {
		display: inline-flex;
		gap: 10px;
		margin-top: 16px;
	}
	.pp-empty-link {
		background: transparent;
		border: 0;
		color: var(--copper-400);
		cursor: pointer;
		font-size: 13px;
		text-decoration: underline;
		margin-left: 8px;
	}
</style>
