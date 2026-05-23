<script lang="ts">
	/**
	 * `/beans` — the bean library + roaster directory.
	 *
	 * Top-level layout adopted from the Claude-designed canvas
	 * (`crema-beans-svelte-export/BeansPage.svelte`):
	 *
	 * - Header with eyebrow + title + status line; right side has Search,
	 *   Import (ghost), and a split "Add bean" button (primary = Quick
	 *   add, caret = dropdown with Full editor / Import).
	 * - Two tabs: **Bags** (default) and **Roasters**.
	 * - On Bags: chip-rail filter row (status: all/active/frozen/archived/
	 *   favourite + roast bands), sort dropdown, grid sectioned by
	 *   Active / Frozen / Archived.
	 * - Tag chips below the chip-rail (drawn from union of library tags).
	 * - Click a tile → opens the right-side drawer ({@link BeanDrawer}).
	 * - "Full editor" navigates to `/beans/new` or `/beans/[id]/edit`.
	 *
	 * The old card / facet / inline-editor flow is gone — the new
	 * design's tile + drawer + dedicated editor route replaces it.
	 */
	import { goto } from '$app/navigation';
	import {
		getBeanStore,
		bagState,
		blankBean,
		mintBeanId,
		type Bean,
		type Roaster
	} from '$lib/bean';
	import BeanTile from '$lib/components/beans/BeanTile.svelte';
	import BeanDrawer from '$lib/components/beans/BeanDrawer.svelte';
	import BeanQuickAdd from '$lib/components/beans/BeanQuickAdd.svelte';
	import BeanImportDialog from '$lib/components/beans/BeanImportDialog.svelte';
	import BeansEmptyState from '$lib/components/beans/BeansEmptyState.svelte';
	import SortPill from '$lib/components/shared/SortPill.svelte';
	import FilterPills from '$lib/components/shared/FilterPills.svelte';
	import { roasterMarkTone } from '$lib/bean/roaster-mark';

	const library = getBeanStore();

	// ── UI state ───────────────────────────────────────────────────────
	type Tab = 'bags' | 'roasters';
	let tab = $state<Tab>('bags');

	type StatusFilter =
		| 'all'
		| 'active'
		| 'frozen'
		| 'archived'
		| 'favourite';
	type RoastFilter = 'any' | 'light' | 'medium' | 'dark';

	let status = $state<StatusFilter>('all');
	let roast = $state<RoastFilter>('any');
	let q = $state('');
	let selectedTags = $state<string[]>([]);

	// Sort state has collapsed: each field carries a direction (asc/desc)
	// rather than baking direction into the field id. `SortPill` exposes
	// this as a single segmented value + a direction toggle.
	type SortField = 'recent' | 'roast' | 'name' | 'rating' | 'remaining' | 'burn';
	type SortDir = 'asc' | 'desc';
	let sortField = $state<SortField>('recent');
	let sortDir = $state<SortDir>('desc');

	// Add-button split
	let addMenuOpen = $state(false);

	let drawerBeanId = $state<string | null>(null);
	let quickAddOpen = $state(false);
	let importOpen = $state(false);

	// ── Derived ────────────────────────────────────────────────────────
	const allBeans = $derived(library.beans);
	const allRoasters = $derived(library.roasters);

	const drawerBean = $derived(drawerBeanId ? library.getBean(drawerBeanId) : null);

	function matchesStatus(b: Bean, f: StatusFilter): boolean {
		const state = bagState(b);
		switch (f) {
			case 'all':
				return true;
			case 'active':
				return state === 'active';
			case 'frozen':
				return state === 'frozen';
			case 'archived':
				return state === 'archived';
			case 'favourite':
				return b.favourite;
			default:
				return true;
		}
	}

	function matchesRoast(b: Bean, f: RoastFilter): boolean {
		if (f === 'any' || b.roastLevel == null) return f === 'any';
		if (f === 'light') return b.roastLevel <= 4;
		if (f === 'medium') return b.roastLevel >= 5 && b.roastLevel <= 7;
		return b.roastLevel >= 8;
	}

	const filtered = $derived.by(() => {
		const query = q.trim().toLowerCase();
		return allBeans.filter((b) => {
			if (!matchesStatus(b, status)) return false;
			if (!matchesRoast(b, roast)) return false;
			if (selectedTags.length > 0) {
				for (const t of selectedTags) {
					if (!b.tags.includes(t)) return false;
				}
			}
			if (!query) return true;
			const r = b.roasterId ? library.getRoaster(b.roasterId) : null;
			return (
				b.name.toLowerCase().includes(query) ||
				r?.name.toLowerCase().includes(query) ||
				b.origin.country?.toLowerCase().includes(query) ||
				b.origin.region?.toLowerCase().includes(query) ||
				b.tastingNotes.toLowerCase().includes(query) ||
				b.tags.some((t) => t.toLowerCase().includes(query))
			);
		});
	});

	const sorted = $derived.by(() => {
		const arr = [...filtered];
		// Field-only comparators — direction is applied uniformly via the
		// sortDir multiplier at the end so every field respects the
		// SortPill direction toggle. Each comparator returns "ascending"
		// order; descending flips the sign.
		const cmpName = (a: Bean, b: Bean) => a.name.localeCompare(b.name);
		const cmpRoast = (a: Bean, b: Bean) => {
			// Ascending = oldest first; empty roastedOn always sinks to
			// the end regardless of direction.
			if (!a.roastedOn && !b.roastedOn) return 0;
			if (!a.roastedOn) return 1;
			if (!b.roastedOn) return -1;
			return a.roastedOn.localeCompare(b.roastedOn);
		};
		const keyCmp = (a: Bean, b: Bean): number => {
			switch (sortField) {
				case 'recent':
					return a.updatedAt - b.updatedAt;
				case 'roast':
					return cmpRoast(a, b);
				case 'name':
					return cmpName(a, b);
				case 'rating':
					return a.rating - b.rating;
				case 'remaining':
					return a.remainingG - b.remainingG;
				case 'burn': {
					// Ascending = least-consumed first.
					const consumed = (x: Bean) =>
						x.bagSizeG > 0 ? 1 - x.remainingG / x.bagSizeG : -1;
					return consumed(a) - consumed(b);
				}
				default:
					return 0;
			}
		};
		const dir = sortDir === 'asc' ? 1 : -1;
		return arr.sort(
			(a, b) => keyCmp(a, b) * dir || cmpName(a, b)
		);
	});

	const sectionedBags = $derived.by(() => {
		const active: Bean[] = [];
		const frozen: Bean[] = [];
		const archived: Bean[] = [];
		for (const b of sorted) {
			const s = bagState(b);
			if (s === 'archived') archived.push(b);
			else if (s === 'frozen') frozen.push(b);
			else active.push(b);
		}
		return { active, frozen, archived };
	});

	const counts = $derived.by(() => {
		const c = { all: 0, active: 0, frozen: 0, archived: 0, favourite: 0 };
		for (const b of allBeans) {
			c.all += 1;
			const s = bagState(b);
			if (s === 'active') c.active += 1;
			if (s === 'frozen') c.frozen += 1;
			if (s === 'archived') c.archived += 1;
			if (b.favourite) c.favourite += 1;
		}
		return c;
	});

	const tagFacets = $derived.by(() => {
		const m = new Map<string, number>();
		for (const b of allBeans) {
			for (const t of b.tags) m.set(t, (m.get(t) ?? 0) + 1);
		}
		return [...m.entries()]
			.map(([tag, count]) => ({ tag, count }))
			.sort((a, b) => b.count - a.count || a.tag.localeCompare(b.tag));
	});

	/**
	 * The full pill list for the Bags-tab filter rail. Three id-prefixed
	 * groups — `s:` (status), `r:` (roast), `t:` (custom tag) — flow into a
	 * single `FilterPills` instance and are dispatched by prefix in
	 * `onBagPillClick` below. The same id namespace lets `selected` and the
	 * click handler agree without a per-group fan-out.
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

	const bagsFilterPills = $derived.by(() => {
		const items: FilterPillItem[] = [];
		items.push({ id: '__status', groupLabel: 'Status' });
		const statusEntries: { f: StatusFilter; label: string; icon?: string }[] = [
			{ f: 'all', label: 'All' },
			{ f: 'active', label: 'Active' },
			{ f: 'frozen', label: 'Frozen', icon: 'ph ph-snowflake' },
			{ f: 'archived', label: 'Archived', icon: 'ph ph-archive' },
			{ f: 'favourite', label: 'Favourite', icon: 'ph-fill ph-star' }
		];
		for (const s of statusEntries) {
			items.push({
				id: `s:${s.f}`,
				label: s.label,
				icon: s.icon,
				count: counts[s.f as keyof typeof counts],
				selected: status === s.f
			});
		}
		items.push({ id: '__div1', divider: true });
		items.push({ id: '__roast', groupLabel: 'Roast' });
		const roastEntries: { f: RoastFilter; label: string }[] = [
			{ f: 'any', label: 'Any' },
			{ f: 'light', label: 'Light' },
			{ f: 'medium', label: 'Medium' },
			{ f: 'dark', label: 'Dark' }
		];
		for (const r of roastEntries) {
			items.push({
				id: `r:${r.f}`,
				label: r.label,
				selected: roast === r.f
			});
		}
		if (tagFacets.length > 0) {
			items.push({ id: '__div2', divider: true });
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

	function onBagPillClick(id: string): void {
		if (id.startsWith('s:')) status = id.slice(2) as StatusFilter;
		else if (id.startsWith('r:')) roast = id.slice(2) as RoastFilter;
		else if (id.startsWith('t:')) toggleSelectedTag(id.slice(2));
	}

	/**
	 * Region pills for the Roasters tab. `region:<id>` prefix mirrors the
	 * bags-tab dispatcher; the empty `'all'` row is the catch-all reset.
	 */
	const roasterFilterPills = $derived.by(() => {
		const items: FilterPillItem[] = [];
		items.push({ id: '__region', groupLabel: 'Region' });
		for (const r of roasterRegionOptions) {
			items.push({
				id: `region:${r}`,
				label: r === 'all' ? 'All' : r,
				selected: roasterRegion === r
			});
		}
		return items;
	});

	function onRoasterPillClick(id: string): void {
		if (id.startsWith('region:')) roasterRegion = id.slice('region:'.length);
	}

	const statusLine = $derived(
		allBeans.length === 0
			? 'No beans yet — add your first bag to start tracking.'
			: `${counts.active} active · ${counts.frozen} frozen · ${counts.archived} archived · ${allRoasters.length} roasters`
	);

	// ── Roaster directory rows ─────────────────────────────────────────
	// Roaster sort uses the same SortPill control as the bags tab.
	type RoasterSortField = 'beans' | 'name' | 'recent';
	let roasterSortField = $state<RoasterSortField>('beans');
	let roasterSortDir = $state<SortDir>('desc');
	const roasterSortOptions: { field: RoasterSortField; label: string; icon: string }[] = [
		{ field: 'beans', label: 'Bag count', icon: 'coffee-bean' },
		{ field: 'name', label: 'Name', icon: 'sort-ascending' },
		{ field: 'recent', label: 'Recent', icon: 'clock' }
	];
	let roasterRegion = $state<string>('all');

	const roasterRegionOptions = $derived.by(() => {
		const set = new Set<string>();
		for (const r of allRoasters) {
			const c = r.country?.trim();
			if (c) set.add(c);
		}
		return ['all', ...[...set].sort()];
	});

	const roasterRows = $derived.by(() => {
		const counts = new Map<string, number>();
		for (const b of allBeans) {
			const id = b.roasterId;
			if (id) counts.set(id, (counts.get(id) ?? 0) + 1);
		}
		let rows = allRoasters.map((r) => ({
			roaster: r,
			count: counts.get(r.id) ?? 0
		}));
		if (roasterRegion !== 'all') {
			rows = rows.filter(
				(x) =>
					(x.roaster.country ?? '').toLowerCase() === roasterRegion.toLowerCase()
			);
		}
		const cmpName = (a: typeof rows[number], b: typeof rows[number]) =>
			a.roaster.name.localeCompare(b.roaster.name);
		const keyCmp = (a: typeof rows[number], b: typeof rows[number]): number => {
			switch (roasterSortField) {
				case 'name':
					return cmpName(a, b);
				case 'recent':
					return a.roaster.updatedAt - b.roaster.updatedAt;
				default:
					return a.count - b.count;
			}
		};
		const dir = roasterSortDir === 'asc' ? 1 : -1;
		return rows.sort((a, b) => keyCmp(a, b) * dir || cmpName(a, b));
	});

	// Detect probable duplicates by normalized roaster name (case-insensitive,
	// stripped of common stopwords).
	const dupes = $derived.by(() => {
		const norm = (s: string) =>
			s
				.toLowerCase()
				.replace(/\b(coffee|coffees|cafe|roasters?|roastery|company|the|and|&)\b/g, '')
				.replace(/\s+/g, ' ')
				.trim();
		const buckets = new Map<string, typeof allRoasters>();
		for (const r of allRoasters) {
			const key = norm(r.name);
			if (!key) continue;
			const arr = buckets.get(key) ?? [];
			arr.push(r);
			buckets.set(key, arr);
		}
		const result: { canonical: typeof allRoasters[number]; dupe: typeof allRoasters[number] }[] = [];
		for (const arr of buckets.values()) {
			if (arr.length < 2) continue;
			// Canonical = roaster with the most beans; first one otherwise.
			const beanCount = (r: typeof allRoasters[number]) =>
				allBeans.filter((b) => b.roasterId === r.id).length;
			const sorted = [...arr].sort((a, b) => beanCount(b) - beanCount(a));
			const [canonical, ...rest] = sorted;
			for (const dupe of rest) result.push({ canonical, dupe });
		}
		return result;
	});

	// SortPill options — direction is now a separate axis (toggled by the
	// pill's left half), so each entry maps to exactly one comparator.
	const sortOptions: { field: SortField; label: string; icon: string }[] = [
		{ field: 'recent', label: 'Recently added', icon: 'plus-circle' },
		{ field: 'roast', label: 'Roast date', icon: 'fire' },
		{ field: 'name', label: 'Name', icon: 'sort-ascending' },
		{ field: 'rating', label: 'Rating', icon: 'star' },
		{ field: 'remaining', label: 'Remaining', icon: 'gauge' },
		{ field: 'burn', label: 'Burn rate', icon: 'flame' }
	];

	// ── Actions ────────────────────────────────────────────────────────
	function openTile(id: string): void {
		drawerBeanId = id;
	}
	function closeDrawer(): void {
		drawerBeanId = null;
	}
	function toggleSelectedTag(t: string): void {
		selectedTags = selectedTags.includes(t)
			? selectedTags.filter((x) => x !== t)
			: [...selectedTags, t];
	}
	function clearFilters(): void {
		status = 'all';
		roast = 'any';
		selectedTags = [];
	}
	function gotoNew(): void {
		goto('/beans/new');
	}
	function gotoEdit(id: string): void {
		goto(`/beans/${encodeURIComponent(id)}/edit`);
	}
	function setActive(id: string): void {
		library.setActiveBean(id);
	}
	function toggleFavourite(id: string): void {
		library.toggleFavourite(id);
	}
	function toggleArchived(id: string): void {
		library.toggleArchived(id);
	}
	function deleteBean(id: string): void {
		const bean = library.getBean(id);
		if (!bean) return;
		if (!confirm(`Delete "${bean.name || 'this bag'}"? This cannot be undone.`)) return;
		library.deleteBean(id);
		if (drawerBeanId === id) drawerBeanId = null;
	}

	/**
	 * Clone a bag inline. The duplicate keeps the original's metadata
	 * (roaster, origin, processing, tags…) but gets a fresh id, a
	 * `" (copy)"` suffix on the name, and is bumped to the front of the
	 * library (newest-first sort). The new bag is **not** made active.
	 */
	function duplicateBean(id: string): void {
		const src = library.getBean(id);
		if (!src) return;
		const copy: Bean = {
			...src,
			id: mintBeanId(),
			name: `${src.name || 'Untitled bag'} (copy)`,
			origin: { ...src.origin },
			tags: [...src.tags],
			metadata: { ...src.metadata },
			visualizerId: null,
			beanconquerorId: null,
			favourite: false,
			archivedAt: null,
			createdAt: Date.now(),
			updatedAt: Date.now()
		};
		library.upsertBean(copy);
	}

	/** Clone a roaster row. Bags pointing at the original are not moved. */
	function duplicateRoaster(id: string): void {
		const src = library.getRoaster(id);
		if (!src) return;
		// Pick a unique name — append " (copy)" then " 2", " 3"… until clear.
		let name = `${src.name} (copy)`;
		let n = 2;
		while (library.findRoasterByName(name)) {
			name = `${src.name} (copy ${n})`;
			n += 1;
		}
		library.upsertRoaster({
			...src,
			id: `roaster:${crypto.randomUUID?.() ?? Math.random().toString(36).slice(2)}`,
			name,
			visualizerId: null,
			metadata: { ...src.metadata },
			createdAt: Date.now(),
			updatedAt: Date.now()
		});
	}

	function deleteRoaster(r: Roaster, count: number): void {
		if (
			!confirm(
				`Delete "${r.name}"? Their ${count} bag(s) will keep but lose the roaster link.`
			)
		)
			return;
		library.deleteRoaster(r.id);
	}

	function editRoaster(id: string): void {
		// No dedicated roaster editor route yet — open the first bag from
		// that roastery in the bean editor as a temporary affordance. If
		// the roaster has no bags, fall through silently.
		const firstBag = allBeans.find((b) => b.roasterId === id);
		if (firstBag) goto(`/beans/${encodeURIComponent(firstBag.id)}/edit`);
	}

	function mergeRoaster(
		canonical: (typeof allRoasters)[number],
		dupe: (typeof allRoasters)[number]
	): void {
		if (
			!confirm(
				`Move ${allBeans.filter((b) => b.roasterId === dupe.id).length} bag(s) from "${dupe.name}" to "${canonical.name}", then delete "${dupe.name}"?`
			)
		)
			return;
		for (const b of allBeans) {
			if (b.roasterId === dupe.id) {
				library.updateBean(b.id, { roasterId: canonical.id });
			}
		}
		library.deleteRoaster(dupe.id);
	}

	// Clicking outside the add-menu closes it. The SortPill owns its own
	// outside-click dismissal — no shared state needed.
	function closeMenusOnDocClick(): void {
		addMenuOpen = false;
	}
</script>

<svelte:head>
	<title>Crema — Beans</title>
</svelte:head>

<svelte:window onclick={closeMenusOnDocClick} />

<div class="bn-page">
	<!-- Header -->
	<div class="bn-head">
		<div class="bn-head-l">
			<div class="t-eyebrow">Library</div>
			<h1 class="t-page-title bn-head-title">Beans</h1>
			<div class="bn-head-sub">{statusLine}</div>
		</div>
		<div class="bn-head-r">
			<label class="bn-search">
				<i class="ph ph-magnifying-glass" aria-hidden="true"></i>
				<input bind:value={q} placeholder="Search beans, roasters, origin…" />
				{#if q}
					<button
						class="bn-search-clear"
						onclick={() => (q = '')}
						aria-label="Clear"
					>
						<i class="ph ph-x"></i>
					</button>
				{/if}
			</label>
			<button
				class="bn-btn bn-btn-ghost"
				onclick={() => (importOpen = true)}
				title="Import from a Beanconqueror .zip export"
			>
				<i class="ph ph-upload-simple"></i> Import
			</button>

			<!-- Split: Quick add (primary click) + caret menu -->
			<!-- The wrapper's only purpose is to stop the window-level click
			     listener from closing the menu when clicks land on inner
			     buttons. The handler reads `e.stopPropagation()` and never
			     opens a non-keyboard interaction, so no keyboard handler is
			     needed; suppress the lint accordingly. -->
			<!-- svelte-ignore a11y_click_events_have_key_events -->
			<!-- svelte-ignore a11y_no_static_element_interactions -->
			<div class="bn-split" onclick={(e) => e.stopPropagation()}>
				<button
					class="bn-btn bn-btn-primary bn-split-main"
					onclick={() => (quickAddOpen = true)}
					title="Quick add a bean (10 seconds)"
				>
					<i class="ph ph-plus"></i> Add bean
				</button>
				<button
					class="bn-btn bn-btn-primary bn-split-chev"
					onclick={(e) => {
						e.stopPropagation();
						addMenuOpen = !addMenuOpen;
					}}
					title="More ways to add"
					aria-label="More ways to add"
				>
					<i class="ph ph-caret-down"></i>
				</button>
				{#if addMenuOpen}
					<div class="bn-split-menu" role="menu">
						<div class="bn-split-menu-head">Add a bean</div>
						<button
							class="bn-split-menu-item"
							role="menuitem"
							onclick={() => {
								addMenuOpen = false;
								quickAddOpen = true;
							}}
						>
							<i class="ph-duotone ph-lightning" aria-hidden="true"></i>
							<div class="bn-split-menu-text">
								<div class="bn-split-menu-title">Quick add</div>
								<div class="bn-split-menu-sub">
									Name, roaster, roast date — ~10 seconds. Refine later.
								</div>
							</div>
							<span class="bn-split-menu-default">Default</span>
						</button>
						<button
							class="bn-split-menu-item"
							role="menuitem"
							onclick={() => {
								addMenuOpen = false;
								gotoNew();
							}}
						>
							<i class="ph-duotone ph-list-checks" aria-hidden="true"></i>
							<div class="bn-split-menu-text">
								<div class="bn-split-menu-title">Full editor</div>
								<div class="bn-split-menu-sub">
									All fields — origin, processing, grinder, tasting notes.
								</div>
							</div>
						</button>
						<div class="bn-split-menu-rule"></div>
						<button
							class="bn-split-menu-item"
							role="menuitem"
							onclick={() => {
								addMenuOpen = false;
								importOpen = true;
							}}
						>
							<i class="ph-duotone ph-upload-simple" aria-hidden="true"></i>
							<div class="bn-split-menu-text">
								<div class="bn-split-menu-title">Import from Beanconqueror</div>
								<div class="bn-split-menu-sub">
									Drop the export <code>.zip</code> — bags, roasters, brews.
								</div>
							</div>
						</button>
					</div>
				{/if}
			</div>
		</div>
	</div>

	<!-- Tabs -->
	<div class="bn-tabs">
		<div class="bn-tabs-l">
			<button
				class="bn-tab"
				class:is-active={tab === 'bags'}
				onclick={() => (tab = 'bags')}
			>
				<i class="ph-duotone ph-coffee-bean" aria-hidden="true"></i>
				<span>Bags</span>
				<span class="bn-tab-count">{counts.all}</span>
			</button>
			<button
				class="bn-tab"
				class:is-active={tab === 'roasters'}
				onclick={() => (tab = 'roasters')}
			>
				<i class="ph-duotone ph-storefront" aria-hidden="true"></i>
				<span>Roasters</span>
				<span class="bn-tab-count">{allRoasters.length}</span>
			</button>
		</div>
		{#if tab === 'bags' && allBeans.length > 0}
			<div class="bn-toolbar-r">
				<SortPill
					value={{ field: sortField, direction: sortDir }}
					options={sortOptions}
					onchange={(next) => {
						sortField = next.field as SortField;
						sortDir = next.direction;
					}}
				/>
			</div>
		{/if}
	</div>

	<!-- Chip rail (Bags tab) — adopts the shared FilterPills component so
	     this rail looks identical to /profiles. Clear-filters chip lives
	     outside the FilterPills group since it's not a selection state. -->
	{#if tab === 'bags' && allBeans.length > 0}
		<div class="bn-filterstrip">
			<FilterPills pills={bagsFilterPills} onclick={onBagPillClick} />
			{#if status !== 'all' || roast !== 'any' || selectedTags.length > 0}
				<button class="bn-chip-clear" onclick={clearFilters}>
					<i class="ph ph-x"></i> Clear
				</button>
			{/if}
		</div>
	{/if}

	<!-- Body -->
	{#if tab === 'bags'}
		{#if allBeans.length === 0}
			<BeansEmptyState
				onQuickAdd={() => (quickAddOpen = true)}
				onFullEditor={gotoNew}
				onImport={() => (importOpen = true)}
			/>
		{:else}
			<div class="bn-grid-scroll">
				{#if sectionedBags.active.length > 0}
					<section class="bn-section">
						<header class="bn-section-head">
							<h2 class="bn-section-title">Active</h2>
							<span class="bn-section-count">{sectionedBags.active.length}</span>
							<span class="bn-section-rule"></span>
						</header>
						<div class="bn-grid">
							{#each sectionedBags.active as b (b.id)}
								<BeanTile
									bean={b}
									roaster={b.roasterId ? library.getRoaster(b.roasterId) : null}
									isActive={library.activeBeanId === b.id}
									onOpen={openTile}
									onToggleFavourite={toggleFavourite}
									onSetActive={setActive}
									onDuplicate={duplicateBean}
									onEdit={gotoEdit}
									onDelete={deleteBean}
								/>
							{/each}
							<button class="bn-tile-new" onclick={() => (quickAddOpen = true)}>
								<div class="bn-tile-new-glyph"><i class="ph ph-plus"></i></div>
								<div class="bn-tile-new-label">Add a bean</div>
								<div class="bn-tile-new-sub">
									Or paste a Beanconqueror link, drop a label photo.
								</div>
							</button>
						</div>
					</section>
				{/if}
				{#if sectionedBags.frozen.length > 0}
					<section class="bn-section">
						<header class="bn-section-head">
							<h2 class="bn-section-title">
								<i class="ph ph-snowflake" style="color: var(--info)"></i> Frozen
							</h2>
							<span class="bn-section-count">{sectionedBags.frozen.length}</span>
							<span class="bn-section-rule"></span>
							<span class="bn-section-help">90 days frozen ≈ 1 day off roast</span>
						</header>
						<div class="bn-grid">
							{#each sectionedBags.frozen as b (b.id)}
								<BeanTile
									bean={b}
									roaster={b.roasterId ? library.getRoaster(b.roasterId) : null}
									isActive={library.activeBeanId === b.id}
									onOpen={openTile}
									onToggleFavourite={toggleFavourite}
									onSetActive={setActive}
									onDuplicate={duplicateBean}
									onEdit={gotoEdit}
									onDelete={deleteBean}
								/>
							{/each}
						</div>
					</section>
				{/if}
				{#if sectionedBags.archived.length > 0}
					<section class="bn-section bn-section-archived">
						<header class="bn-section-head">
							<h2 class="bn-section-title">
								<i class="ph ph-archive" style="color: rgba(var(--tint-rgb), 0.4)"
								></i>
								Archived
							</h2>
							<span class="bn-section-count">{sectionedBags.archived.length}</span>
							<span class="bn-section-rule"></span>
						</header>
						<div class="bn-grid bn-grid-archived">
							{#each sectionedBags.archived as b (b.id)}
								<BeanTile
									bean={b}
									roaster={b.roasterId ? library.getRoaster(b.roasterId) : null}
									isActive={library.activeBeanId === b.id}
									onOpen={openTile}
									onToggleFavourite={toggleFavourite}
									onSetActive={setActive}
									onDuplicate={duplicateBean}
									onEdit={gotoEdit}
									onDelete={deleteBean}
								/>
							{/each}
						</div>
					</section>
				{/if}
				{#if filtered.length === 0}
					<div class="bn-empty-filter">
						<i class="ph-duotone ph-funnel-x" aria-hidden="true"></i>
						<div>No bags match these filters.</div>
						<button class="bn-btn bn-btn-ghost" onclick={clearFilters}>
							<i class="ph ph-x"></i> Clear filters
						</button>
					</div>
				{/if}
			</div>
		{/if}
	{:else}
		<!-- Roasters tab -->
		<div class="bn-grid-scroll">
			{#if dupes.length > 0}
				{#each dupes as d (d.dupe.id)}
					<section class="bn-merge-banner">
						<div class="bn-merge-banner-mark">
							<i class="ph ph-link"></i>
						</div>
						<div class="bn-merge-banner-text">
							<div class="bn-merge-banner-title">
								<strong>{d.dupe.name}</strong> looks like <strong>{d.canonical.name}</strong>.
							</div>
							<div class="bn-merge-banner-sub">
								{allBeans.filter((b) => b.roasterId === d.dupe.id).length} bag(s) from
								{d.dupe.name}. Merging will move them to {d.canonical.name} and
								delete the duplicate row.
							</div>
						</div>
						<div class="bn-merge-banner-actions">
							<button class="bn-btn bn-btn-ghost">Keep separate</button>
							<button
								class="bn-btn bn-btn-primary"
								onclick={() => mergeRoaster(d.canonical, d.dupe)}
							>
								Merge
							</button>
						</div>
					</section>
				{/each}
			{/if}
			<section class="bn-section">
				<header class="bn-section-head">
					<h2 class="bn-section-title">Roasters</h2>
					<span class="bn-section-count">{roasterRows.length}</span>
					<span class="bn-section-rule"></span>
					<div class="bn-section-tools">
						<SortPill
							value={{ field: roasterSortField, direction: roasterSortDir }}
							options={roasterSortOptions}
							onchange={(next) => {
								roasterSortField = next.field as RoasterSortField;
								roasterSortDir = next.direction;
							}}
						/>
					</div>
				</header>
				{#if roasterRegionOptions.length > 1}
					<div class="bn-filterstrip bn-filterstrip-inline">
						<FilterPills
							pills={roasterFilterPills}
							onclick={onRoasterPillClick}
						/>
					</div>
				{/if}
				<div class="bn-roaster-grid">
					{#each roasterRows as { roaster, count } (roaster.id)}
						{@const mt = roasterMarkTone(roaster)}
						<div class="bn-roaster-card">
							<div class="bn-roaster-card-row">
								<div class="bn-roaster-mark" style="--tone: {mt.tone}">{mt.mark}</div>
								<div class="bn-roaster-body">
									<div class="bn-roaster-name">{roaster.name}</div>
									<div class="bn-roaster-loc">{roaster.country || '—'}</div>
									<div class="bn-roaster-meta">
										{#if roaster.website}
											<span class="bn-roaster-site">
												<i class="ph ph-globe"></i>{roaster.website}
											</span>
										{/if}
										<span class="bn-roaster-count">
											{count} bag{count === 1 ? '' : 's'}
										</span>
									</div>
								</div>
							</div>
							<div class="bn-roaster-actions">
								<button
									type="button"
									class="bn-tile-action-icon"
									title="Duplicate"
									aria-label="Duplicate"
									onclick={(e) => {
										e.stopPropagation();
										duplicateRoaster(roaster.id);
									}}
								>
									<i class="ph ph-copy"></i>
								</button>
								<button
									type="button"
									class="bn-tile-action-icon"
									title={count > 0 ? 'Edit (opens first bag for now)' : 'No bags to edit'}
									aria-label="Edit"
									disabled={count === 0}
									onclick={(e) => {
										e.stopPropagation();
										editRoaster(roaster.id);
									}}
								>
									<i class="ph ph-pencil"></i>
								</button>
								<button
									type="button"
									class="bn-tile-action-icon bn-tile-action-icon-danger"
									title="Delete roastery"
									aria-label="Delete roastery"
									onclick={(e) => {
										e.stopPropagation();
										deleteRoaster(roaster, count);
									}}
								>
									<i class="ph ph-trash"></i>
								</button>
							</div>
						</div>
					{/each}
				</div>
				{#if roasterRows.length === 0}
					<div class="bn-empty-filter">
						<i class="ph-duotone ph-storefront" aria-hidden="true"></i>
						<div>
							{allRoasters.length === 0
								? "No roasters yet. Add a bag — a roaster row is created when you type a roastery name."
								: 'No roasters match this region.'}
						</div>
					</div>
				{/if}
			</section>
		</div>
	{/if}
</div>

<!-- Drawer -->
{#if drawerBean}
	<BeanDrawer
		bean={drawerBean}
		roaster={drawerBean.roasterId ? library.getRoaster(drawerBean.roasterId) : null}
		isActive={library.activeBeanId === drawerBean.id}
		onClose={closeDrawer}
		onSetActive={setActive}
		onEdit={(id) => {
			closeDrawer();
			gotoEdit(id);
		}}
		onToggleArchived={toggleArchived}
		onDelete={deleteBean}
		onToggleFavourite={toggleFavourite}
	/>
{/if}

<!-- Quick add -->
{#if quickAddOpen}
	<BeanQuickAdd
		onClose={() => (quickAddOpen = false)}
		onOpenFull={() => {
			quickAddOpen = false;
			gotoNew();
		}}
	/>
{/if}

<!-- Import -->
{#if importOpen}
	<BeanImportDialog onClose={() => (importOpen = false)} />
{/if}

<style>
	.bn-page {
		background: var(--bg-page);
		color: var(--fg-1);
		display: flex;
		flex-direction: column;
		min-height: 100vh;
	}
	/* Header */
	.bn-head {
		display: flex;
		align-items: flex-end;
		justify-content: space-between;
		gap: 24px;
		padding: var(--page-pad-top, 32px) var(--page-pad-x, 24px) 16px;
		flex-wrap: wrap;
	}
	.bn-head-l {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.bn-head-title {
		margin-top: 2px;
	}
	.bn-head-sub {
		font-family: var(--font-sans);
		font-size: 13px;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.bn-head-r {
		display: flex;
		align-items: center;
		gap: 10px;
		flex-wrap: wrap;
	}
	.bn-search {
		position: relative;
		display: flex;
		align-items: center;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
		border-radius: var(--radius-pill);
		padding: 0 12px;
		width: 280px;
		transition: border-color var(--dur-1) var(--ease);
	}
	.bn-search:focus-within {
		border-color: rgba(var(--tint-rgb), 0.2);
	}
	.bn-search i {
		color: rgba(var(--tint-rgb), 0.45);
		font-size: 14px;
	}
	.bn-search input {
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
	.bn-search input::placeholder {
		color: rgba(var(--tint-rgb), 0.35);
	}
	.bn-search-clear {
		background: transparent;
		border: 0;
		padding: 4px;
		color: rgba(var(--tint-rgb), 0.4);
		cursor: pointer;
	}

	/* Buttons */
	.bn-btn {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 9px 16px;
		border-radius: var(--radius-pill);
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 500;
		cursor: pointer;
		border: 1px solid transparent;
		transition: all var(--dur-1) var(--ease);
		white-space: nowrap;
	}
	.bn-btn-ghost {
		background: rgba(var(--tint-rgb), 0.04);
		border-color: rgba(var(--tint-rgb), 0.1);
		color: var(--fg-1);
	}
	.bn-btn-ghost:hover {
		background: rgba(var(--tint-rgb), 0.08);
	}
	.bn-btn-primary {
		background: var(--copper-500);
		color: var(--fg-on-accent);
		font-weight: 600;
	}
	.bn-btn-primary:hover {
		background: var(--copper-600);
	}

	/* Split add button */
	.bn-split {
		position: relative;
		display: inline-flex;
	}
	.bn-split-main {
		border-radius: var(--radius-pill) 0 0 var(--radius-pill);
	}
	.bn-split-chev {
		border-radius: 0 var(--radius-pill) var(--radius-pill) 0;
		padding: 9px 11px;
		border-left: 1px solid rgba(0, 0, 0, 0.18);
	}
	.bn-split-main + .bn-split-chev {
		margin-left: -1px;
	}
	.bn-split-menu {
		position: absolute;
		top: calc(100% + 6px);
		right: 0;
		z-index: 60;
		min-width: 320px;
		background: var(--bg-page);
		border: 1px solid rgba(var(--tint-rgb), 0.14);
		border-radius: var(--radius-md);
		padding: 8px;
		box-shadow: var(--shadow-lg);
	}
	.bn-split-menu-head {
		font-family: var(--font-sans);
		font-size: 10px;
		font-weight: 700;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.5);
		padding: 6px 8px 4px;
	}
	.bn-split-menu-item {
		display: flex;
		gap: 10px;
		align-items: flex-start;
		width: 100%;
		text-align: left;
		background: transparent;
		border: 0;
		padding: 8px;
		border-radius: var(--radius-sm);
		cursor: pointer;
		color: var(--fg-1);
		font: inherit;
		position: relative;
	}
	.bn-split-menu-item:hover {
		background: rgba(var(--tint-rgb), 0.06);
	}
	.bn-split-menu-item i {
		font-size: 18px;
		color: var(--copper-400);
		margin-top: 2px;
	}
	.bn-split-menu-text {
		flex: 1 1 auto;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.bn-split-menu-title {
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 600;
		color: var(--fg-1);
	}
	.bn-split-menu-sub {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.55);
		line-height: 1.4;
	}
	.bn-split-menu-sub code {
		font-family: var(--font-mono);
		font-size: 10.5px;
		padding: 1px 4px;
		background: rgba(var(--tint-rgb), 0.06);
		border-radius: 4px;
	}
	.bn-split-menu-default {
		font-family: var(--font-sans);
		font-size: 9px;
		font-weight: 700;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: var(--copper-400);
		background: rgba(var(--copper-rgb), 0.12);
		border-radius: var(--radius-pill);
		padding: 2px 7px;
		align-self: center;
	}
	.bn-split-menu-rule {
		height: 1px;
		background: rgba(var(--tint-rgb), 0.08);
		margin: 6px 4px;
	}

	/* Tabs */
	.bn-tabs {
		display: flex;
		justify-content: space-between;
		align-items: center;
		gap: 16px;
		padding: 8px var(--page-pad-x, 24px) 12px;
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.06);
	}
	.bn-tabs-l {
		display: inline-flex;
		gap: 6px;
	}
	.bn-tab {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		padding: 8px 14px;
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.55);
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 500;
		cursor: pointer;
		border-radius: var(--radius-pill);
		transition: all var(--dur-1) var(--ease);
	}
	.bn-tab i {
		font-size: 16px;
	}
	.bn-tab:hover {
		color: var(--fg-1);
	}
	.bn-tab.is-active {
		color: var(--fg-1);
		background: rgba(var(--tint-rgb), 0.06);
	}
	.bn-tab-count {
		font-family: var(--font-mono);
		font-size: 10px;
		color: rgba(var(--tint-rgb), 0.4);
		padding: 1px 6px;
		background: rgba(var(--tint-rgb), 0.06);
		border-radius: 999px;
	}
	.bn-tab.is-active .bn-tab-count {
		background: var(--copper-500);
		color: var(--fg-on-accent);
	}

	/* Filter strip — `.bn-filterstrip` is the page-level shell that hosts
	   the shared FilterPills + Clear-filters chip. The pills themselves
	   inherit the `.pp-tag*` rules from `styles/profiles-page.css`, so the
	   visuals match /profiles exactly. */
	.bn-filterstrip {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 10px;
		padding: 12px var(--page-pad-x, 24px);
		border-bottom: 1px solid rgba(var(--tint-rgb), 0.04);
	}
	.bn-filterstrip-inline {
		padding: 0 0 12px;
		border-bottom: 0;
	}
	.bn-chip-clear {
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
	.bn-chip-clear:hover {
		color: var(--fg-1);
	}

	/* Grid + sections */
	.bn-grid-scroll {
		padding: 18px var(--page-pad-x, 24px) 40px;
		display: flex;
		flex-direction: column;
		gap: 24px;
		flex: 1 1 auto;
	}
	.bn-section {
		display: flex;
		flex-direction: column;
		gap: 12px;
	}
	.bn-section-head {
		display: flex;
		align-items: center;
		gap: 10px;
	}
	.bn-section-title {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		font-family: var(--font-sans);
		font-size: 12px;
		font-weight: 700;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: var(--fg-1);
		margin: 0;
	}
	.bn-section-title i {
		font-size: 13px;
	}
	.bn-section-count {
		font-family: var(--font-mono);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.5);
		font-weight: 500;
	}
	.bn-section-rule {
		flex: 1 1 auto;
		height: 1px;
		background: rgba(var(--tint-rgb), 0.06);
	}
	.bn-section-help {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.4);
	}
	.bn-section-tools {
		display: inline-flex;
		gap: 10px;
		align-items: center;
	}
	.bn-section-archived {
		opacity: 0.85;
	}
	.bn-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
		gap: 14px;
	}
	.bn-grid-archived {
		opacity: 0.85;
	}
	.bn-tile-new {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 6px;
		background: transparent;
		border: 1px dashed rgba(var(--tint-rgb), 0.15);
		border-radius: var(--radius-lg);
		color: var(--fg-1);
		cursor: pointer;
		min-height: 168px;
		padding: 24px;
		font-family: var(--font-sans);
		transition: all var(--dur-1) var(--ease);
	}
	.bn-tile-new:hover {
		border-color: var(--copper-400);
		background: rgba(var(--copper-rgb), 0.03);
	}
	.bn-tile-new-glyph {
		width: 44px;
		height: 44px;
		border-radius: 50%;
		background: rgba(var(--tint-rgb), 0.05);
		display: flex;
		align-items: center;
		justify-content: center;
		color: rgba(var(--tint-rgb), 0.55);
		font-size: 20px;
		transition: all var(--dur-1) var(--ease);
	}
	.bn-tile-new:hover .bn-tile-new-glyph {
		background: var(--copper-500);
		color: var(--fg-on-accent);
	}
	.bn-tile-new-label {
		font-size: 13px;
		font-weight: 600;
	}
	.bn-tile-new-sub {
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.5);
		text-align: center;
		max-width: 240px;
		line-height: 1.4;
	}

	/* Empty-filter inline message */
	.bn-empty-filter {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 8px;
		padding: 48px 24px;
		text-align: center;
		font-family: var(--font-sans);
		font-size: 13px;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.bn-empty-filter i {
		font-size: 48px;
		color: rgba(var(--tint-rgb), 0.3);
		margin-bottom: 4px;
	}

	/* Roaster cards */
	.bn-merge-banner {
		display: flex;
		gap: 14px;
		align-items: center;
		padding: 14px 16px;
		background: rgba(var(--warning-rgb), 0.08);
		border: 1px solid rgba(var(--warning-rgb), 0.32);
		border-radius: var(--radius-md);
	}
	.bn-merge-banner-mark {
		width: 36px;
		height: 36px;
		border-radius: 50%;
		background: rgba(var(--warning-rgb), 0.2);
		color: var(--warning);
		display: inline-flex;
		align-items: center;
		justify-content: center;
		font-size: 16px;
		flex-shrink: 0;
	}
	.bn-merge-banner-text {
		flex: 1 1 auto;
	}
	.bn-merge-banner-title {
		font-family: var(--font-sans);
		font-size: 13px;
		color: var(--fg-1);
	}
	.bn-merge-banner-sub {
		font-family: var(--font-sans);
		font-size: 11.5px;
		color: rgba(var(--tint-rgb), 0.6);
		margin-top: 2px;
		line-height: 1.4;
	}
	.bn-merge-banner-actions {
		display: inline-flex;
		gap: 6px;
		flex-shrink: 0;
	}
	.bn-roaster-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
		gap: 12px;
	}
	.bn-roaster-card {
		display: flex;
		flex-direction: column;
		gap: 12px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
		border-radius: var(--radius-md);
		padding: 14px;
		transition: all var(--dur-1) var(--ease);
	}
	.bn-roaster-card:hover {
		background: rgba(var(--tint-rgb), 0.06);
		border-color: rgba(var(--tint-rgb), 0.16);
	}
	.bn-roaster-card-row {
		display: grid;
		grid-template-columns: 48px 1fr;
		align-items: center;
		gap: 14px;
	}
	.bn-roaster-actions {
		display: flex;
		gap: 6px;
		justify-content: flex-end;
		align-items: center;
	}
	/* Icon-button styling for the roaster action row. Mirrors
	   `BeanTile.svelte`'s `.bn-tile-action-icon` so the two cards read as
	   one design family. */
	.bn-tile-action-icon {
		width: 30px;
		height: 30px;
		flex: 0 0 30px;
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		background: rgba(var(--tint-rgb), 0.03);
		border-radius: var(--radius-sm);
		color: rgba(var(--tint-rgb), 0.6);
		cursor: pointer;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		font-size: 13px;
		padding: 0;
		transition: all var(--dur-1) var(--ease);
	}
	.bn-tile-action-icon:hover:not(:disabled) {
		color: var(--fg-1);
		background: rgba(var(--tint-rgb), 0.07);
	}
	.bn-tile-action-icon:disabled {
		opacity: 0.35;
		cursor: not-allowed;
	}
	.bn-tile-action-icon-danger {
		color: var(--danger);
	}
	.bn-tile-action-icon-danger:hover:not(:disabled) {
		color: var(--danger);
		background: rgba(var(--danger-rgb), 0.12);
	}
	.bn-roaster-mark {
		width: 48px;
		height: 48px;
		border-radius: var(--radius-md);
		background: var(--tone, var(--copper-500));
		display: inline-flex;
		align-items: center;
		justify-content: center;
		color: #fff;
		font-family: var(--font-serif);
		font-weight: 500;
		font-size: 22px;
		letter-spacing: -0.02em;
	}
	.bn-roaster-body {
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.bn-roaster-name {
		font-family: var(--font-serif);
		font-size: 15px;
		font-weight: 500;
		color: var(--fg-1);
		letter-spacing: -0.01em;
	}
	.bn-roaster-loc {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.5);
	}
	.bn-roaster-meta {
		display: flex;
		flex-wrap: wrap;
		gap: 8px;
		margin-top: 2px;
		font-family: var(--font-sans);
		font-size: 10.5px;
		color: rgba(var(--tint-rgb), 0.5);
	}
	.bn-roaster-site {
		display: inline-flex;
		align-items: center;
		gap: 4px;
	}
	.bn-roaster-site i {
		font-size: 11px;
	}
	.bn-roaster-count {
		font-family: var(--font-mono);
		color: var(--copper-400);
	}
</style>
