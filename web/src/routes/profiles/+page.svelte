<script lang="ts">
	/**
	 * Profiles — the `/profiles` route: the profile library, ported from
	 * `ProfilesPage` in `profiles-page.jsx`.
	 *
	 * The header (title, saved / pinned counts, search, Import, "+ New"), the
	 * filter strip (All / Pinned, roast filters, derived tag chips with count
	 * badges, sort) and a responsive grid of `ProfileCard`s + a dashed "New
	 * profile" tile.
	 *
	 * ## Real vs. stubbed
	 *
	 * The library is **real**: built-in profiles come from the wasm core, custom
	 * profiles from `localStorage` — both via `lib/profiles`. Search, filtering,
	 * sorting, pinning, duplicating, deleting and "Load on Brew" are all wired.
	 * "Load on Brew" marks the profile active in shared UI state (the Brew
	 * header reflects it); it does **not** upload the profile to the DE1 — the
	 * core has no profile-upload path yet. Import is a stub (`// TODO`).
	 */
	import { goto } from '$app/navigation';
	import { getProfileStore, type CremaProfile } from '$lib/profiles';
	import { getCremaAppContext } from '$lib/shell/app-context';
	import { ProfileCard } from '$lib/components/profiles';

	const store = getProfileStore();
	const ctx = getCremaAppContext();

	// Kick off the one-time built-in load (no-op if already loaded).
	void store.ensureLoaded();

	/** The full library — built-ins (core) + custom (localStorage). */
	const profiles = $derived(store.all);

	// ── Filter / sort state ──────────────────────────────────────────────
	/** The search query. */
	let q = $state('');
	/** The active tag filter: `all`, `pinned`, a roast, or `t:<custom tag>`. */
	let tag = $state('all');
	/** The active sort key. */
	let sort = $state<'recent' | 'name' | 'dose'>('recent');

	/** Roast facet counts. */
	const roasts = $derived(
		(['light', 'medium', 'dark'] as const).map((id) => ({
			id,
			label: id[0].toUpperCase() + id.slice(1),
			count: profiles.filter((p) => p.roast === id).length
		}))
	);

	/** Custom tag facets — derived from the library so new tags appear here. */
	const customTags = $derived.by(() => {
		const counts = new Map<string, number>();
		for (const p of profiles) {
			for (const t of p.tags) counts.set(t, (counts.get(t) ?? 0) + 1);
		}
		return [...counts.entries()]
			.map(([label, count]) => ({ id: `t:${label}`, label, count }))
			.sort((a, b) => b.count - a.count || a.label.localeCompare(b.label));
	});

	/** The pinned count, for the header sub-line. */
	const pinnedCount = $derived(profiles.filter((p) => p.pinned).length);

	/** The filtered + sorted profile list the grid renders. */
	const filtered = $derived.by(() => {
		const query = q.trim().toLowerCase();
		let r = profiles.filter((p) => {
			// Tag facet.
			if (tag === 'pinned' && !p.pinned) return false;
			if (['light', 'medium', 'dark'].includes(tag) && p.roast !== tag) return false;
			if (tag.startsWith('t:') && !p.tags.includes(tag.slice(2))) return false;
			// Search.
			if (query === '') return true;
			return (
				p.name.toLowerCase().includes(query) ||
				p.bean.toLowerCase().includes(query) ||
				p.notes.toLowerCase().includes(query) ||
				p.tags.some((t) => t.toLowerCase().includes(query))
			);
		});
		if (sort === 'name') r = [...r].sort((a, b) => a.name.localeCompare(b.name));
		if (sort === 'dose') r = [...r].sort((a, b) => a.dose - b.dose);
		return r;
	});

	// ── Card actions ─────────────────────────────────────────────────────
	/**
	 * Load a profile on Brew — mark it active. Updates the persisted active id
	 * and pushes the name into the shared UI state so the Brew dashboard's
	 * header reflects it.
	 *
	 * TODO: wire to DE1 profile upload — this is UI-level only; the profile is
	 * not written to the machine (the core has no profile-upload command).
	 */
	function loadOnBrew(id: string): void {
		store.setActive(id);
		const profile = store.get(id);
		ctx().app?.state.patch({ activeProfileName: profile?.name ?? null });
	}

	/** Duplicate a profile into a new custom profile, then open it for editing. */
	function duplicate(id: string): void {
		void goto(`/profiles/${encodeURIComponent(id)}/edit?duplicate=1`);
	}

	/** Open a profile in the editor. */
	function edit(id: string): void {
		void goto(`/profiles/${encodeURIComponent(id)}/edit`);
	}

	/** Toggle a profile's pinned state. */
	function togglePin(id: string): void {
		store.togglePin(id);
	}

	/** Delete a custom profile (with a confirm). */
	function remove(id: string): void {
		const profile = store.get(id);
		if (profile && confirm(`Delete "${profile.name}"? This cannot be undone.`)) {
			store.delete(id);
		}
	}

	/** Import — not yet wired. */
	function importProfile(): void {
		// TODO: wire profile import — parse a `.json` / legacy `.tcl` file into a
		// CremaProfile and `store.save` it. The core's `import_legacy_tcl` is the
		// natural backend once the bridge surfaces it.
		alert('Profile import is coming in a later step.');
	}

	/** Whether a card is the active profile. */
	const activeId = $derived(store.activeId);
</script>

<svelte:head>
	<title>Crema — Profiles</title>
</svelte:head>

<div class="pp-page">
	<!-- Header -->
	<div class="pp-head">
		<div class="pp-head-l">
			<div class="t-eyebrow" style="color:rgba(244,237,224,0.55)">Library</div>
			<div class="pp-title">Profiles</div>
			<div class="pp-sub">
				{#if store.loaded}
					{profiles.length} saved · {pinnedCount} pinned to favorites
				{:else}
					Loading built-in profiles…
				{/if}
			</div>
		</div>
		<div class="pp-head-r">
			<div class="pp-search">
				<i class="ph ph-magnifying-glass" aria-hidden="true"></i>
				<input bind:value={q} placeholder="Search profiles, beans, notes…" />
				{#if q}
					<button class="pp-search-clear" aria-label="Clear search" onclick={() => (q = '')}>
						<i class="ph ph-x" aria-hidden="true"></i>
					</button>
				{/if}
			</div>
			<button class="pp-btn pp-btn-secondary" onclick={importProfile}>
				<i class="ph ph-upload-simple" aria-hidden="true"></i> Import
			</button>
			<button class="pp-btn pp-btn-primary" onclick={() => goto('/profiles/new')}>
				<i class="ph ph-plus" aria-hidden="true"></i> New profile
			</button>
		</div>
	</div>

	<!-- Filter strip -->
	<div class="pp-filters">
		<div class="pp-tags">
			<button class="pp-tag" class:is-active={tag === 'all'} onclick={() => (tag = 'all')}>
				<span>All</span><span class="pp-tag-count">{profiles.length}</span>
			</button>
			<button
				class="pp-tag"
				class:is-active={tag === 'pinned'}
				onclick={() => (tag = 'pinned')}
			>
				<i class="ph-fill ph-star" style="font-size:11px;color:var(--copper-400)"></i>
				<span>Pinned</span><span class="pp-tag-count">{pinnedCount}</span>
			</button>
			<span class="pp-tag-divider"></span>
			<span class="pp-tag-grouplabel">Roast</span>
			{#each roasts as r (r.id)}
				<button class="pp-tag" class:is-active={tag === r.id} onclick={() => (tag = r.id)}>
					<span>{r.label}</span><span class="pp-tag-count">{r.count}</span>
				</button>
			{/each}
			{#if customTags.length > 0}
				<span class="pp-tag-divider"></span>
				<span class="pp-tag-grouplabel">Tags</span>
				{#each customTags as t (t.id)}
					<button
						class="pp-tag pp-tag-custom"
						class:is-active={tag === t.id}
						onclick={() => (tag = t.id)}
					>
						<span>{t.label}</span><span class="pp-tag-count">{t.count}</span>
					</button>
				{/each}
			{/if}
		</div>
		<div class="pp-sort">
			<span class="t-eyebrow" style="color:rgba(244,237,224,0.45)">Sort</span>
			{#each [{ id: 'recent', label: 'Recent' }, { id: 'name', label: 'Name' }, { id: 'dose', label: 'Dose' }] as const as s (s.id)}
				<button
					class="pp-sort-opt"
					class:is-active={sort === s.id}
					onclick={() => (sort = s.id)}>{s.label}</button
				>
			{/each}
		</div>
	</div>

	<!-- Grid -->
	<div class="pp-grid">
		{#each filtered as p (p.id)}
			<ProfileCard
				profile={p}
				active={p.id === activeId}
				onLoad={loadOnBrew}
				onDuplicate={duplicate}
				onEdit={edit}
				onTogglePin={togglePin}
				onDelete={remove}
			/>
		{/each}
		<button class="pp-card-new" onclick={() => goto('/profiles/new')}>
			<div class="pp-card-new-glyph"><i class="ph ph-plus" aria-hidden="true"></i></div>
			<div class="pp-card-new-label">New profile</div>
			<div class="pp-card-new-sub">Start from a template or scratch</div>
		</button>
		{#if store.loaded && filtered.length === 0}
			<div class="pp-empty">
				No profiles match the current filters.
				{#if q}<button class="pp-empty-link" onclick={() => (q = '')}>Clear search</button
					>{/if}
			</div>
		{/if}
	</div>
</div>

<style>
	.pp-page {
		background: var(--espresso-950);
		color: var(--ink-50);
		display: flex;
		flex-direction: column;
		min-height: 100vh;
	}

	/* Header */
	.pp-head {
		display: flex;
		align-items: flex-end;
		justify-content: space-between;
		gap: 24px;
		padding: 32px 40px 18px;
		flex: 0 0 auto;
	}
	.pp-head-l {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.pp-title {
		font-family: var(--font-serif);
		font-size: 40px;
		letter-spacing: -0.02em;
		color: var(--ink-50);
		margin-top: 2px;
	}
	.pp-sub {
		font-family: var(--font-sans);
		font-size: 13px;
		color: rgba(244, 237, 224, 0.55);
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
		background: rgba(244, 237, 224, 0.04);
		border: 1px solid rgba(244, 237, 224, 0.08);
		border-radius: var(--radius-pill);
		padding: 0 12px;
		width: 300px;
		transition: border-color var(--dur-1) var(--ease);
	}
	.pp-search:focus-within {
		border-color: rgba(244, 237, 224, 0.2);
	}
	.pp-search i {
		color: rgba(244, 237, 224, 0.45);
		font-size: 14px;
	}
	.pp-search input {
		flex: 1 1 auto;
		min-width: 0;
		background: transparent;
		border: 0;
		outline: 0;
		color: var(--ink-50);
		font-family: var(--font-sans);
		font-size: 13px;
		padding: 9px 8px;
	}
	.pp-search input::placeholder {
		color: rgba(244, 237, 224, 0.35);
	}
	.pp-search-clear {
		background: transparent;
		border: 0;
		padding: 4px;
		color: rgba(244, 237, 224, 0.4);
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
		background: rgba(244, 237, 224, 0.04);
		border-color: rgba(244, 237, 224, 0.1);
		color: var(--ink-50);
	}
	.pp-btn-secondary:hover {
		background: rgba(244, 237, 224, 0.07);
	}
	.pp-btn-primary {
		background: var(--copper-500);
		color: #1a120c;
		font-weight: 600;
	}
	.pp-btn-primary:hover {
		background: var(--copper-600);
	}

	/* Filters */
	.pp-filters {
		display: flex;
		justify-content: space-between;
		align-items: center;
		gap: 24px;
		padding: 0 40px 16px;
		border-bottom: 1px solid rgba(244, 237, 224, 0.05);
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
	.pp-tag-divider {
		width: 1px;
		height: 16px;
		background: rgba(244, 237, 224, 0.1);
		margin: 0 6px;
		align-self: center;
	}
	.pp-tag-grouplabel {
		font-family: var(--font-sans);
		font-size: 10px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		font-weight: 700;
		color: rgba(244, 237, 224, 0.4);
		padding: 0 2px;
		align-self: center;
		white-space: nowrap;
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

	/* Grid */
	.pp-grid {
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
		gap: 18px;
		padding: 24px 40px 40px;
	}
	@media (max-width: 1100px) {
		.pp-grid {
			grid-template-columns: repeat(2, minmax(0, 1fr));
		}
	}
	@media (max-width: 720px) {
		.pp-grid {
			grid-template-columns: minmax(0, 1fr);
		}
		.pp-head,
		.pp-filters {
			padding-left: 20px;
			padding-right: 20px;
		}
		.pp-grid {
			padding-left: 20px;
			padding-right: 20px;
		}
	}

	.pp-card-new {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 8px;
		background: transparent;
		border: 1px dashed rgba(244, 237, 224, 0.15);
		border-radius: var(--radius-lg, 14px);
		color: var(--ink-50);
		cursor: pointer;
		min-height: 320px;
		padding: 24px;
		font-family: var(--font-sans);
		transition: all var(--dur-1) var(--ease);
	}
	.pp-card-new:hover {
		border-color: rgba(244, 237, 224, 0.3);
		background: rgba(244, 237, 224, 0.02);
	}
	.pp-card-new-glyph {
		width: 48px;
		height: 48px;
		border-radius: 50%;
		background: rgba(244, 237, 224, 0.05);
		display: flex;
		align-items: center;
		justify-content: center;
		color: rgba(244, 237, 224, 0.6);
		font-size: 22px;
		margin-bottom: 4px;
		transition: all var(--dur-1) var(--ease);
	}
	.pp-card-new:hover .pp-card-new-glyph {
		background: var(--copper-500);
		color: #1a120c;
	}
	.pp-card-new-label {
		font-size: 14px;
		font-weight: 500;
	}
	.pp-card-new-sub {
		font-size: 11px;
		color: rgba(244, 237, 224, 0.45);
	}

	.pp-empty {
		grid-column: 1 / -1;
		text-align: center;
		padding: 40px;
		color: rgba(244, 237, 224, 0.5);
		font-family: var(--font-sans);
		font-size: 13px;
	}
	.pp-empty-link {
		background: transparent;
		border: 0;
		color: var(--copper-400);
		cursor: pointer;
		font-size: 13px;
		text-decoration: underline;
	}
</style>
