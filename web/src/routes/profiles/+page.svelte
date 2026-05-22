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
	import {
		getProfileStore,
		toCoreProfile,
		fromCoreProfile,
		uid,
		type CremaProfile
	} from '$lib/profiles';
	import { getCremaAppContext } from '$lib/shell/app-context';
	import { ProfileCard } from '$lib/components/profiles';

	const store = getProfileStore();
	const ctx = getCremaAppContext();

	/**
	 * Outcome of the most recent import — `null` when no import has been
	 * attempted in this session. Drives a dismissible banner below the
	 * page header (mirrors the History page pattern).
	 */
	let importBanner = $state<
		| { kind: 'success'; message: string }
		| { kind: 'error'; message: string }
		| null
	>(null);
	let importing = $state(false);

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
	type SortKey = 'recent' | 'name' | 'dose' | 'roast' | 'beverage' | 'author' | 'tags';
	let sort = $state<SortKey>('recent');
	/** Whether the sort dropdown is open. Click-away closes via the scrim. */
	let sortOpen = $state(false);

	/**
	 * Ordered sort options the dropdown renders. Label is shown in the
	 * button (current selection) + each menu row; `id` is the internal
	 * key the `filtered` derive reads.
	 */
	const SORT_OPTIONS = [
		{ id: 'recent', label: 'Recent' },
		{ id: 'name', label: 'Name' },
		{ id: 'dose', label: 'Dose' },
		{ id: 'roast', label: 'Roast' },
		{ id: 'beverage', label: 'Beverage type' },
		{ id: 'author', label: 'Author' },
		{ id: 'tags', label: 'Tags' }
	] as const;
	const sortLabel = $derived(
		SORT_OPTIONS.find((s) => s.id === sort)?.label ?? 'Recent'
	);

	/**
	 * The fixed roast ordering for the `'roast'` sort key. Light → dark
	 * matches the design's roast-bar gradient; unknown roasts sink to
	 * the end (so the sorted grid groups "known dose" cards first).
	 */
	const ROAST_ORDER: Record<string, number> = { light: 0, medium: 1, dark: 2 };

	/**
	 * Beverage-type ordering for the `'beverage'` sort + the filter
	 * pills. Espresso lives first (the default), the others trail in
	 * a stable order so the UI is deterministic across renders.
	 */
	const BEVERAGE_TYPES = ['espresso', 'pourover', 'manual', 'cleaning', 'calibrate'] as const;

	/** Roast facet counts. */
	const roasts = $derived(
		(['light', 'medium', 'dark'] as const).map((id) => ({
			id,
			label: id[0].toUpperCase() + id.slice(1),
			count: profiles.filter((p) => p.roast === id).length
		}))
	);

	/**
	 * Beverage-type facets — one chip per beverage type that's actually
	 * represented in the library, with a count. Order follows the
	 * `BEVERAGE_TYPES` constant for deterministic UI.
	 */
	const beverageFacets = $derived(
		BEVERAGE_TYPES.map((bt) => ({
			id: `b:${bt}`,
			label: bt.charAt(0).toUpperCase() + bt.slice(1),
			count: profiles.filter((p) => p.beverageType === bt).length
		})).filter((f) => f.count > 0)
	);

	/** Custom tag facets — derived from the library so new tags appear here. */
	const customTags = $derived.by(() => {
		const counts = new Map<string, number>();
		for (const p of profiles) {
			for (const t of p.tags) counts.set(t, (counts.get(t) ?? 0) + 1);
		}
		return [...counts.entries()]
			.map(([tag, count]) => ({
				id: `t:${tag}`,
				// Display label is title-cased so a stored tag like `tea` reads
				// consistently next to `Built-in` in the facet strip; the filter
				// still keys on the raw tag string via `id`.
				label: tag.charAt(0).toUpperCase() + tag.slice(1),
				count
			}))
			.sort((a, b) => b.count - a.count || a.label.localeCompare(b.label));
	});

	/** The pinned count, for the header sub-line. */
	const pinnedCount = $derived(profiles.filter((p) => p.pinned).length);

	/** Whether the "Hidden" filter is the active facet. */
	const showingHidden = $derived(tag === 'hidden');

	/** The filtered + sorted profile list the grid renders. */
	const filtered = $derived.by(() => {
		const query = q.trim().toLowerCase();
		// The Hidden facet draws from a different list — hidden built-ins
		// don't appear in `profiles` (which is `store.all`). Switch
		// sources before applying search / sort.
		const source = showingHidden ? store.hiddenBuiltinProfiles : profiles;
		let r = source.filter((p) => {
			// Tag facet — only the search/roast/custom-tag filters apply
			// once `source` is in hand; `pinned` and `hidden` are
			// list-level so they're already accounted for.
			if (['light', 'medium', 'dark'].includes(tag) && p.roast !== tag) return false;
			if (tag.startsWith('t:') && !p.tags.includes(tag.slice(2))) return false;
			if (tag.startsWith('b:') && p.beverageType !== tag.slice(2)) return false;
			if (tag === 'pinned' && !p.pinned) return false;
			// Search.
			if (query === '') return true;
			return (
				p.name.toLowerCase().includes(query) ||
				p.notes.toLowerCase().includes(query) ||
				p.tags.some((t) => t.toLowerCase().includes(query)) ||
				p.author.toLowerCase().includes(query)
			);
		});
		switch (sort) {
			case 'name':
				r = [...r].sort((a, b) => a.name.localeCompare(b.name));
				break;
			case 'dose':
				r = [...r].sort((a, b) => a.dose - b.dose);
				break;
			case 'roast': {
				// Light → medium → dark → unknown; break ties on name so the
				// order is stable across renders.
				const rank = (p: CremaProfile): number =>
					p.roast != null ? ROAST_ORDER[p.roast] ?? 99 : 99;
				r = [...r].sort(
					(a, b) => rank(a) - rank(b) || a.name.localeCompare(b.name)
				);
				break;
			}
			case 'beverage':
				r = [...r].sort(
					(a, b) =>
						BEVERAGE_TYPES.indexOf(a.beverageType) -
							BEVERAGE_TYPES.indexOf(b.beverageType) ||
						a.name.localeCompare(b.name)
				);
				break;
			case 'author':
				r = [...r].sort(
					(a, b) =>
						(a.author || '').localeCompare(b.author || '') ||
						a.name.localeCompare(b.name)
				);
				break;
			case 'tags': {
				// Group by the first user tag; ties (no tags or same first
				// tag) fall back to name.
				const firstTag = (p: CremaProfile): string =>
					p.tags.find((t) => t !== 'Built-in') ?? '';
				r = [...r].sort(
					(a, b) =>
						firstTag(a).localeCompare(firstTag(b)) ||
						a.name.localeCompare(b.name)
				);
				break;
			}
			default:
				// 'recent' — leave in `source` order (the store already
				// emits newest first via lastUsed under the hood).
				break;
		}
		return r;
	});

	// ── Card actions ─────────────────────────────────────────────────────
	/**
	 * Load a profile on Brew — mark it active and upload it to the DE1.
	 *
	 * Two pieces of state move:
	 * - `store.setActive(id)` flips the local UI selection (the active outline
	 *   on the profile card tracks this immediately, so a click feels
	 *   responsive). The brew dashboard's header also reads
	 *   `activeProfile?.name` as a fallback during the upload window for
	 *   the same instant-feedback reason.
	 * - `app.uploadProfile(...)` ships the profile to the DE1 over BLE; on
	 *   success the orchestrator emits `ProfileUploadCompleted`, which sets
	 *   `ui.activeProfileName` so the brew page header pins to what's
	 *   *actually* on the machine. On failure the field stays at the prior
	 *   value — the DE1 didn't accept this one, so the previous profile is
	 *   still loaded.
	 *
	 * If no DE1 is connected the upload is a no-op (the core builds the
	 * commands; `de1.writeCharacteristic` short-circuits with a "not
	 * connected" status log). The user can still see the UI-level active
	 * outline; the upload retries on the next click.
	 */
	function loadOnBrew(id: string): void {
		store.setActive(id);
		const profile = store.get(id);
		const app = ctx().app;
		if (profile && app) {
			void app.uploadProfile(toCoreProfile(profile));
		}
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

	/**
	 * Three-mode handler routed by both the profile's source and
	 * whether the user is viewing the "Hidden" filter:
	 *
	 * - Hidden filter active + built-in → restore (un-hide).
	 * - Custom profile → hard delete (confirm).
	 * - Built-in (visible) → hide.
	 *
	 * Built-ins compile into the wasm binary so they can't be truly
	 * removed; hide-then-restore handles tidying. Hidden profiles
	 * surface via the "Hidden" filter pill, where this same handler
	 * runs the unhide branch.
	 */
	function remove(id: string): void {
		const profile = store.get(id) ?? store.hiddenBuiltinProfiles.find((p) => p.id === id);
		if (!profile) return;
		if (showingHidden && profile.source === 'builtin') {
			store.unhideBuiltin(id);
			return;
		}
		if (profile.source === 'builtin') {
			if (confirm(`Hide "${profile.name}" from the library?`)) {
				store.delete(id);
			}
			return;
		}
		if (confirm(`Delete "${profile.name}"? This cannot be undone.`)) {
			store.delete(id);
		}
	}

	/**
	 * Hand-off from the hidden `<input type="file">` to the wasm-bridged
	 * importers. Routes by extension: `.tcl` → legacy importer; anything
	 * else (`.json`, `.shot.json`) → community-v2 JSON importer. Each
	 * parsed `Profile` becomes a custom `CremaProfile` (fresh UUID, the
	 * file's own title preserved) saved into the local library.
	 */
	/**
	 * Adopt a Rust-shape `Profile` into the local store as a custom
	 * CremaProfile (fresh UUID, source `custom`, `Built-in` tag stripped).
	 * Used by both the per-file and per-line import paths.
	 */
	function adoptImportedProfile(core: import('$lib/core').Profile): void {
		const adapted = fromCoreProfile(core, -1);
		const custom: CremaProfile = {
			...adapted,
			id: uid('custom'),
			source: 'custom',
			tags: adapted.tags.filter((t) => t !== 'Built-in')
		};
		store.save(custom);
	}

	/**
	 * Export every profile in the library as one `.jsonl` file — one
	 * community-v2 profile JSON per line. Counterpart to the bulk
	 * Import path which also recognises `.jsonl`. Built-ins + custom
	 * profiles are both included; round-trips back through the same
	 * page's Import button.
	 */
	async function exportAllAsV2Jsonl(): Promise<void> {
		if (profiles.length === 0) return;
		const app = ctx().app;
		if (!app) return;
		const lines: string[] = [];
		for (const p of profiles) {
			try {
				const v2 = await app.exportProfileAsV2Json(toCoreProfile(p));
				// `exportProfileAsV2Json` returns pretty-printed JSON;
				// flatten to one line per profile for true JSONL.
				lines.push(JSON.stringify(JSON.parse(v2)));
			} catch (e) {
				console.warn(`Skipped "${p.name}" in bulk export:`, e);
			}
		}
		const d = new Date();
		const p = (n: number): string => String(n).padStart(2, '0');
		const stamp =
			`${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}T` +
			`${p(d.getHours())}${p(d.getMinutes())}`;
		const blob = new Blob([lines.join('\n')], { type: 'application/x-ndjson' });
		const url = URL.createObjectURL(blob);
		const a = document.createElement('a');
		a.href = url;
		a.download = `crema-profiles-${stamp}.jsonl`;
		a.click();
		URL.revokeObjectURL(url);
	}

	/**
	 * Export a single profile as a community-v2 `.json` file. Looks up
	 * the profile by id, asks the app to encode it via the wasm bridge,
	 * and triggers a browser download.
	 */
	async function exportProfile(id: string): Promise<void> {
		const profile = store.get(id);
		const app = ctx().app;
		if (!profile || !app) return;
		try {
			const v2 = await app.exportProfileAsV2Json(toCoreProfile(profile));
			const safeName = (profile.name || 'profile').replace(/[^\w.-]+/g, '_');
			const blob = new Blob([v2], { type: 'application/json' });
			const url = URL.createObjectURL(blob);
			const a = document.createElement('a');
			a.href = url;
			a.download = `${safeName}.json`;
			a.click();
			URL.revokeObjectURL(url);
		} catch (e) {
			importBanner = {
				kind: 'error',
				message: `Could not export "${profile.name}": ${e instanceof Error ? e.message : String(e)}`
			};
		}
	}

	async function onImportFilesChosen(event: Event): Promise<void> {
		const input = event.currentTarget as HTMLInputElement;
		const files = input.files;
		input.value = '';
		const app = ctx().app;
		if (!app || !files || files.length === 0) return;
		importing = true;
		importBanner = null;
		let imported = 0;
		const errors: string[] = [];
		for (const file of Array.from(files)) {
			// `.jsonl` is a multi-profile bundle: one v2 profile JSON per
			// line. Split locally, then route each non-empty line through
			// the v2-JSON importer. `.json` / `.tcl` stay one-profile per
			// file via `importProfileFile`.
			const isJsonl = file.name.toLowerCase().endsWith('.jsonl');
			if (isJsonl) {
				try {
					const text = await file.text();
					const lines = text.split('\n').filter((l) => l.trim().length > 0);
					for (let i = 0; i < lines.length; i++) {
						try {
							const core = await app.parseV2JsonProfile(lines[i]);
							adoptImportedProfile(core);
							imported += 1;
						} catch (e) {
							errors.push(
								`Line ${i + 1} of ${file.name}: ${e instanceof Error ? e.message : String(e)}`
							);
						}
					}
				} catch (e) {
					errors.push(
						`Could not read ${file.name}: ${e instanceof Error ? e.message : String(e)}`
					);
				}
				continue;
			}
			const { profile: core, error } = await app.importProfileFile(file);
			if (!core) {
				errors.push(error ?? `Could not import ${file.name}.`);
				continue;
			}
			adoptImportedProfile(core);
			imported += 1;
		}
		importing = false;
		if (imported > 0 && errors.length === 0) {
			importBanner = {
				kind: 'success',
				message: `Imported ${imported} profile${imported === 1 ? '' : 's'}.`
			};
		} else if (imported > 0) {
			importBanner = {
				kind: 'success',
				message: `Imported ${imported} of ${imported + errors.length}. ${errors[0]}`
			};
		} else {
			importBanner = {
				kind: 'error',
				message: errors[0] ?? 'No profiles imported.'
			};
		}
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
			<div class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.55)">Library</div>
			<div class="t-page-title pp-title">Profiles</div>
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
			<!-- Import: pick one-or-many community v2 .json or legacy
			     .tcl profile files. The hidden <input> is wrapped in a
			     label styled like the other header buttons so it
			     matches `.pp-btn-secondary`. docs/22 task #66. -->
			<label
				class="pp-btn pp-btn-secondary pp-import"
				class:pp-import-disabled={importing}
				title="Import community v2 .json or legacy .tcl files, or a .jsonl bundle (one v2 profile per line)"
			>
				<i class="ph ph-upload-simple" aria-hidden="true"></i>
				<span>{importing ? 'Importing…' : 'Import'}</span>
				<input
					type="file"
					accept=".tcl,.json,.jsonl"
					multiple
					disabled={importing}
					onchange={onImportFilesChosen}
				/>
			</label>
			<button
				class="pp-btn pp-btn-secondary"
				disabled={profiles.length === 0}
				onclick={exportAllAsV2Jsonl}
				title="Download every profile as one .jsonl file — one community-v2 profile per line"
			>
				<i class="ph ph-download-simple" aria-hidden="true"></i> Export
			</button>
			<button class="pp-btn pp-btn-primary" onclick={() => goto('/profiles/new')}>
				<i class="ph ph-plus" aria-hidden="true"></i> New profile
			</button>
		</div>
	</div>

	{#if importBanner}
		<div
			class="pp-import-banner"
			class:pp-import-banner-ok={importBanner.kind === 'success'}
			class:pp-import-banner-err={importBanner.kind === 'error'}
			role="status"
		>
			<i
				class={importBanner.kind === 'success' ? 'ph ph-check-circle' : 'ph ph-warning'}
				aria-hidden="true"
			></i>
			<span>{importBanner.message}</span>
			<button
				type="button"
				class="pp-import-banner-close"
				aria-label="Dismiss"
				onclick={() => (importBanner = null)}
			>
				<i class="ph ph-x" aria-hidden="true"></i>
			</button>
		</div>
	{/if}

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
			{#if store.hiddenBuiltinCount > 0}
				<button
					class="pp-tag"
					class:is-active={tag === 'hidden'}
					onclick={() => (tag = 'hidden')}
					title="Show built-in profiles you've hidden — click their eye icon to restore"
				>
					<i class="ph ph-eye-slash" style="font-size:11px"></i>
					<span>Hidden</span>
					<span class="pp-tag-count">{store.hiddenBuiltinCount}</span>
				</button>
			{/if}
			<span class="pp-tag-divider"></span>
			<span class="pp-tag-grouplabel">Roast</span>
			{#each roasts as r (r.id)}
				<button class="pp-tag" class:is-active={tag === r.id} onclick={() => (tag = r.id)}>
					<span>{r.label}</span><span class="pp-tag-count">{r.count}</span>
				</button>
			{/each}
			{#if beverageFacets.length > 0}
				<span class="pp-tag-divider"></span>
				<span class="pp-tag-grouplabel">Beverage</span>
				{#each beverageFacets as b (b.id)}
					<button
						class="pp-tag"
						class:is-active={tag === b.id}
						onclick={() => (tag = b.id)}
					>
						<span>{b.label}</span><span class="pp-tag-count">{b.count}</span>
					</button>
				{/each}
			{/if}
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
			<span class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.45)">Sort</span>
			<!-- Dropdown — the old inline chip row hit ~9 options once
			     roast / beverage / author / tags joined recent/name/dose.
			     A combo button collapses that into one slot. -->
			<div class="pp-sort-menu">
				<button
					class="pp-sort-btn"
					aria-haspopup="menu"
					aria-expanded={sortOpen}
					onclick={() => (sortOpen = !sortOpen)}
				>
					<span>{sortLabel}</span>
					<i class="ph ph-caret-down" aria-hidden="true"></i>
				</button>
				{#if sortOpen}
					<button
						type="button"
						class="pp-sort-scrim"
						aria-label="Close sort menu"
						onclick={() => (sortOpen = false)}
					></button>
					<div class="pp-sort-list" role="menu">
						{#each SORT_OPTIONS as s (s.id)}
							<button
								class="pp-sort-opt"
								class:is-active={sort === s.id}
								role="menuitem"
								onclick={() => {
									sort = s.id;
									sortOpen = false;
								}}>{s.label}</button
							>
						{/each}
					</div>
				{/if}
			</div>
		</div>
	</div>

	<!-- Grid -->
	<div class="pp-grid">
		{#each filtered as p (p.id)}
			<ProfileCard
				profile={p}
				active={p.id === activeId}
				hidden={showingHidden}
				onLoad={loadOnBrew}
				onDuplicate={duplicate}
				onEdit={edit}
				onTogglePin={togglePin}
				onDelete={remove}
				onExport={exportProfile}
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
		background: var(--bg-page);
		color: var(--fg-1);
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
		/* Shared page-header rhythm — see --page-pad-* in app.css. */
		padding: var(--page-pad-top) var(--page-pad-x) 18px;
		flex: 0 0 auto;
	}
	.pp-head-l {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	/* Page title uses the shared .t-page-title role; only the layout nudge
	   is screen-specific. */
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

	/* Filters */
	.pp-filters {
		display: flex;
		justify-content: space-between;
		align-items: center;
		gap: 24px;
		padding: 0 var(--page-pad-x) 16px;
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
	.pp-sort {
		display: flex;
		align-items: center;
		gap: 10px;
		flex: 0 0 auto;
	}
	.pp-sort-menu {
		position: relative;
	}
	.pp-sort-btn {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: var(--radius-sm);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 12px;
		cursor: pointer;
		padding: 6px 10px;
		transition: background var(--dur-1) var(--ease);
	}
	.pp-sort-btn:hover {
		background: rgba(var(--tint-rgb), 0.08);
	}
	.pp-sort-btn i {
		font-size: 11px;
		opacity: 0.6;
	}
	.pp-sort-scrim {
		position: fixed;
		inset: 0;
		background: transparent;
		border: 0;
		cursor: default;
		z-index: 20;
	}
	.pp-sort-list {
		position: absolute;
		right: 0;
		top: calc(100% + 6px);
		z-index: 21;
		min-width: 160px;
		background: var(--bg-surface-2);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: var(--radius-sm);
		box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
		padding: 4px;
		display: flex;
		flex-direction: column;
	}
	.pp-sort-opt {
		background: transparent;
		border: 0;
		border-radius: 6px;
		color: rgba(var(--tint-rgb), 0.7);
		font-family: var(--font-sans);
		font-size: 12px;
		cursor: pointer;
		padding: 8px 10px;
		text-align: left;
		transition: background var(--dur-1) var(--ease);
	}
	.pp-sort-opt:hover {
		background: rgba(var(--tint-rgb), 0.06);
		color: var(--fg-1);
	}
	.pp-sort-opt.is-active {
		color: var(--fg-1);
		background: rgba(var(--copper-rgb, 199, 118, 59), 0.12);
	}

	/* Grid */
	.pp-grid {
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
		gap: 18px;
		padding: 24px var(--page-pad-x) 40px;
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
		border: 1px dashed rgba(var(--tint-rgb), 0.15);
		border-radius: var(--radius-lg, 14px);
		color: var(--fg-1);
		cursor: pointer;
		min-height: 320px;
		padding: 24px;
		font-family: var(--font-sans);
		transition: all var(--dur-1) var(--ease);
	}
	.pp-card-new:hover {
		border-color: rgba(var(--tint-rgb), 0.3);
		background: rgba(var(--tint-rgb), 0.02);
	}
	.pp-card-new-glyph {
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
	.pp-card-new:hover .pp-card-new-glyph {
		background: var(--copper-500);
		color: var(--fg-on-accent);
	}
	.pp-card-new-label {
		font-size: 14px;
		font-weight: 500;
	}
	.pp-card-new-sub {
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.45);
	}

	.pp-empty {
		grid-column: 1 / -1;
		text-align: center;
		padding: 40px;
		color: rgba(var(--tint-rgb), 0.5);
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

	/* ── Import (header button + result banner) ────────────────────────── */
	.pp-import {
		position: relative;
		cursor: pointer;
	}
	.pp-import input[type='file'] {
		position: absolute;
		inset: 0;
		opacity: 0;
		cursor: pointer;
	}
	.pp-import-disabled,
	.pp-import-disabled input[type='file'] {
		opacity: 0.5;
		cursor: not-allowed;
		pointer-events: none;
	}
	.pp-import-banner {
		display: flex;
		align-items: center;
		gap: 10px;
		margin: 0 var(--page-pad-x) 12px;
		padding: 10px 14px;
		border-radius: var(--radius-sm);
		font-family: var(--font-sans);
		font-size: 12.5px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
	}
	.pp-import-banner-ok {
		background: rgba(var(--success-rgb), 0.08);
		border-color: rgba(var(--success-rgb), 0.4);
		color: rgba(var(--success-rgb), 0.95);
	}
	.pp-import-banner-err {
		background: rgba(var(--danger-rgb), 0.08);
		border-color: rgba(var(--danger-rgb), 0.4);
		color: rgba(var(--danger-rgb), 0.95);
	}
	.pp-import-banner-close {
		margin-left: auto;
		background: transparent;
		border: none;
		cursor: pointer;
		color: inherit;
		opacity: 0.7;
		padding: 4px;
	}
	.pp-import-banner-close:hover {
		opacity: 1;
	}
</style>
