<script lang="ts">
	import Icon from '$lib/icons/Icon.svelte';
	import { pinActiveThenFavourite } from '$lib/library-order';
	import DownloadSimpleIcon from 'phosphor-svelte/lib/DownloadSimpleIcon';
	import MagnifyingGlassIcon from 'phosphor-svelte/lib/MagnifyingGlassIcon';
	import PlusIcon from 'phosphor-svelte/lib/PlusIcon';
	import UploadSimpleIcon from 'phosphor-svelte/lib/UploadSimpleIcon';
	import XIcon from 'phosphor-svelte/lib/XIcon';
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
	 * sorting, pinning, duplicating, deleting, "Load on Brew" (which now also
	 * eagerly uploads to a connected DE1 via the orchestrator's `onActivate`
	 * hook), Import (legacy `.tcl` + community-v2 `.json` + `.jsonl` bundles)
	 * and Export are all wired.
	 */
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import {
		getProfileStore,
		toCoreProfile,
		fromCoreProfile,
		newProfileId,
		type CremaProfile
	} from '$lib/profiles';
	import {
		getRecipeStore,
		isBuiltinRecipe,
		newRecipeFor,
		recipeEstimateLabel
	} from '$lib/brew/recipes.svelte';
	import { methodLabel } from '$lib/brew/methods';
	import MethodMark from '$lib/components/brewlog/MethodMark.svelte';
	import RecipeCredit from '$lib/components/brewlog/RecipeCredit.svelte';
	import RecipeNotes from '$lib/components/brewlog/RecipeNotes.svelte';
	import RecipeEditor from '$lib/components/brewlog/RecipeEditor.svelte';
	import TrashIcon from 'phosphor-svelte/lib/TrashIcon';
	import type { BrewRecipe } from '$lib/core/crema-core';
	import { BrewStepKind } from '$lib/core/crema-core';
	import { getCremaAppContext } from '$lib/shell/app-context';
	import { ProfileCard } from '$lib/components/profiles';
	import SortPill from '$lib/components/shared/SortPill.svelte';
	import FilterPills from '$lib/components/shared/FilterPills.svelte';
	import {
		filterProfiles,
		profileFacetCount,
		profileStatusCounts,
		type ProfileFilterInput,
		type ProfileStatus
	} from '$lib/profiles/library-filter';
	import { downloadBlob, filenameStamp } from '$lib/utils/download';
	import { confirmDialog } from '$lib/components/shared/confirm-dialog.svelte';
	import CustomMethodDialog from '$lib/components/brewlog/CustomMethodDialog.svelte';
	import type { CustomBrewMethod } from '$lib/core/crema-core';
	import {
		METHOD_STYLES,
		getCustomMethodStore
	} from '$lib/brew/custom-methods.svelte';

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
	/**
	 * The filter rail is two independent axes (#124): `status` picks the
	 * subset (All / Pinned / Hidden built-ins) and `facet` narrows it (a
	 * roast, `b:<beverage>` or `t:<custom tag>`, or null). Hidden used to
	 * share one single-select value with the facets, so browsing the hidden
	 * built-ins dropped the roast / beverage / tag filter.
	 */
	let status = $state<ProfileStatus>('all');
	let facet = $state<string | null>(null);
	/** The inputs every filter + count reads. */
	const filterInput = $derived<ProfileFilterInput>({
		visible: profiles,
		hidden: store.hiddenBuiltinProfiles,
		status,
		facet,
		query: q
	});
	/** The active sort key. */
	type SortKey = 'recent' | 'name' | 'dose' | 'roast' | 'beverage' | 'author';
	type SortDir = 'asc' | 'desc';
	let sort = $state<SortKey>('recent');
	let sortDir = $state<SortDir>('desc');

	/**
	 * Inline clickable sort options. Click the active one to flip
	 * direction; click another to switch keys (with its default
	 * direction reapplied).
	 */
	const SORT_OPTIONS: ReadonlyArray<{ id: SortKey; label: string; defaultDir: SortDir }> = [
		{ id: 'recent', label: 'Recent', defaultDir: 'desc' },
		{ id: 'name', label: 'Name', defaultDir: 'asc' },
		{ id: 'dose', label: 'Dose', defaultDir: 'asc' },
		{ id: 'roast', label: 'Roast', defaultDir: 'asc' },
		{ id: 'beverage', label: 'Beverage', defaultDir: 'asc' },
		{ id: 'author', label: 'Author', defaultDir: 'asc' }
	];

	/**
	 * Fixed roast ordering for the `'roast'` sort key. Light → dark
	 * matches the design's roast-bar gradient; unknown roasts sink
	 * (so the sorted grid groups "known roast" cards first).
	 */
	const ROAST_ORDER: Record<string, number> = { light: 0, medium: 1, dark: 2 };

	/**
	 * Beverage-type ordering for the `'beverage'` sort + the filter
	 * pills. Espresso first (the default), then the others in a stable
	 * order so the UI is deterministic across renders.
	 */
	const BEVERAGE_TYPES = ['espresso', 'pourover', 'manual', 'cleaning', 'calibrate'] as const;

	/** Roast facet counts. */
	const roasts = $derived(
		(['light', 'medium', 'dark'] as const).map((id) => ({
			id,
			label: id[0].toUpperCase() + id.slice(1),
			count: profileFacetCount(filterInput, id)
		}))
	);

	/**
	 * Beverage-type facets — one chip per beverage type that's actually
	 * represented in the library, with a count. Order follows the
	 * `BEVERAGE_TYPES` constant for deterministic UI.
	 */
	const beverageFacets = $derived(
		BEVERAGE_TYPES.filter((bt) =>
			// A chip per beverage the library holds (visible or hidden), so
			// chips don't vanish while filtering; the count is faceted.
			[...profiles, ...store.hiddenBuiltinProfiles].some((p) => p.beverageType === bt)
		).map((bt) => ({
			id: `b:${bt}`,
			label: bt.charAt(0).toUpperCase() + bt.slice(1),
			count: profileFacetCount(filterInput, `b:${bt}`)
		}))
	);

	/** Custom tag facets — derived from the library so new tags appear here. */
	const customTags = $derived.by(() => {
		// Library-wide frequency orders the chips (stable while filtering);
		// the badge is the faceted count.
		const freq = new Map<string, number>();
		for (const p of [...profiles, ...store.hiddenBuiltinProfiles]) {
			for (const t of p.tags) freq.set(t, (freq.get(t) ?? 0) + 1);
		}
		return [...freq.entries()]
			.sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
			.map(([tag]) => ({
				id: `t:${tag}`,
				// Display label is title-cased so a stored tag like `tea` reads
				// consistently next to `Built-in` in the facet strip; the filter
				// still keys on the raw tag string via `id`.
				label: tag.charAt(0).toUpperCase() + tag.slice(1),
				count: profileFacetCount(filterInput, `t:${tag}`)
			}));
	});

	/** The pinned count, for the header sub-line. */
	const pinnedCount = $derived(profiles.filter((p) => p.pinned).length);

	/** Whether the "Hidden" status is active. */
	const showingHidden = $derived(status === 'hidden');
	/** Status chip counts — given the facet and the search. */
	const statusCounts = $derived(profileStatusCounts(filterInput));

	/**
	 * The flat pill list passed to `FilterPills`. Built from the same
	 * `roasts` / `beverageFacets` / `customTags` derivations the inline
	 * markup used; just funnels through `divider` / `groupLabel` sentinel
	 * entries to keep the visual rhythm.
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
				id: 'all',
				label: 'All',
				count: statusCounts.all,
				selected: status === 'all'
			},
			{
				id: 'pinned',
				label: 'Pinned',
				icon: 'ph-fill ph-star',
				iconStyle: 'font-size:11px;color:var(--copper-400)',
				count: statusCounts.pinned,
				selected: status === 'pinned'
			}
		];
		if (store.hiddenBuiltinCount > 0) {
			items.push({
				id: 'hidden',
				label: 'Hidden',
				icon: 'ph ph-eye-slash',
				iconStyle: 'font-size:11px',
				count: statusCounts.hidden,
				selected: status === 'hidden',
				title: "Show built-in profiles you've hidden — click their eye icon to restore"
			});
		}
		items.push({ id: '__div1', divider: true });
		items.push({ id: '__roast', groupLabel: 'Roast' });
		for (const r of roasts) {
			items.push({
				id: r.id,
				label: r.label,
				count: r.count,
				selected: facet === r.id
			});
		}
		if (beverageFacets.length > 0) {
			items.push({ id: '__div2', divider: true });
			items.push({ id: '__beverage', groupLabel: 'Beverage' });
			for (const b of beverageFacets) {
				items.push({
					id: b.id,
					label: b.label,
					count: b.count,
					selected: facet === b.id
				});
			}
		}
		if (customTags.length > 0) {
			items.push({ id: '__div3', divider: true });
			items.push({ id: '__tags', groupLabel: 'Tags' });
			for (const t of customTags) {
				items.push({
					id: t.id,
					label: t.label,
					count: t.count,
					selected: facet === t.id,
					custom: true
				});
			}
		}
		return items;
	});

	/** The filtered + sorted profile list the grid renders. */
	const filtered = $derived.by(() => {
		// Status + facet + search compose (#124); the Hidden status draws
		// from the hidden built-ins, which `store.all` leaves out.
		let r = filterProfiles(filterInput);
		// Two-tier sort: unset values always sink to the end (regardless
		// of direction), then the active key's comparator decides among
		// the set values. Stable tiebreak on name for every key.
		const isUnset = (p: CremaProfile): boolean => {
			switch (sort) {
				case 'name':
					return p.name.trim() === '';
				case 'dose':
					return !(p.dose > 0);
				case 'roast':
					return p.roast == null;
				case 'author':
					return p.author.trim() === '';
				case 'recent':
					return p.lastUsed == null;
				case 'beverage':
					// Every profile carries a beverageType (Espresso default
					// on import); no "unset" state to sink.
					return false;
				default:
					return false;
			}
		};
		const keyCmp = (a: CremaProfile, b: CremaProfile): number => {
			switch (sort) {
				case 'name':
					return a.name.localeCompare(b.name);
				case 'dose':
					return a.dose - b.dose;
				case 'roast': {
					const rank = (p: CremaProfile): number =>
						p.roast != null ? (ROAST_ORDER[p.roast] ?? 99) : 99;
					return rank(a) - rank(b);
				}
				case 'beverage':
					return (
						BEVERAGE_TYPES.indexOf(a.beverageType) -
						BEVERAGE_TYPES.indexOf(b.beverageType)
					);
				case 'author':
					return (a.author || '').localeCompare(b.author || '');
				case 'recent':
					return (
						(a.lastUsed != null ? Date.parse(a.lastUsed) : 0) -
						(b.lastUsed != null ? Date.parse(b.lastUsed) : 0)
					);
				default:
					return 0;
			}
		};
		r = [...r].sort((a, b) => {
			const ua = isUnset(a);
			const ub = isUnset(b);
			if (ua !== ub) return ua ? 1 : -1;
			// Both set (or both unset) — apply the key comparator with
			// the chosen direction; tiebreak on name (alphabetical) so
			// unset-vs-unset ordering is also deterministic.
			const cmp = keyCmp(a, b);
			const dir = sortDir === 'desc' ? -1 : 1;
			return cmp * dir || a.name.localeCompare(b.name);
		});
		// Pin the loaded profile to the top, then pinned favourites — primary
		// grouping applied on EVERY sort now (the chosen sort above is the
		// within-group order). `store.activeId` may be filtered out (wrong facet,
		// hidden built-in, search miss), in which case nothing floats. Shared
		// with the brew picker + the beans page.
		return pinActiveThenFavourite(r, store.activeId, (p) => p.pinned);
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
			// `syncActiveProfile` (rather than the bare `uploadProfile`)
			// so the fingerprint cache is kept in sync with the upload —
			// without this, the next Coffee tap would see a stale cache
			// and re-upload even though the bytes already match.
			void app.syncActiveProfile(profile, {});
		}
	}

	/** Duplicate a profile into a new custom profile, then open it for editing. */
	function duplicate(id: string): void {
		void goto(resolve(`/profiles/${encodeURIComponent(id)}/edit?duplicate=1`));
	}

	/** Open a profile in the editor. */
	function edit(id: string): void {
		void goto(resolve(`/profiles/${encodeURIComponent(id)}/edit`));
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
	async function remove(id: string): Promise<void> {
		const profile = store.get(id) ?? store.hiddenBuiltinProfiles.find((p) => p.id === id);
		if (!profile) return;
		if (showingHidden && profile.source === 'builtin') {
			store.unhideBuiltin(id);
			// When the user empties the hide-set, the "Hidden" filter
			// pill disappears — and a stale `status === 'hidden'` would
			// leave them staring at an empty grid. Jump back to All so
			// the just-restored profile is visible in context.
			if (store.hiddenBuiltinCount === 0) status = 'all';
			return;
		}
		if (profile.source === 'builtin') {
			if (await confirmDialog({ message: `Hide "${profile.name}" from the library?`, confirmLabel: 'Hide' })) {
				store.delete(id);
			}
			return;
		}
		if (await confirmDialog({ message: `Delete "${profile.name}"? This cannot be undone.`, confirmLabel: 'Delete', danger: true })) {
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
			id: newProfileId(),
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
		const blob = new Blob([lines.join('\n')], { type: 'application/x-ndjson' });
		downloadBlob(`crema-profiles-${filenameStamp()}.jsonl`, blob);
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
			downloadBlob(`${safeName}.json`, blob);
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

	// ── Brew recipes — the guided-brew plans (issue #10) ─────────────────
	// A second library section: unlike machine profiles these are
	// followed by hand, never uploaded to the DE1. Authored here; the
	// Scale page's Brew tab picks + runs them and edits the selected one
	// in place (its own RecipeEditor modal — no hop back here).
	const recipeStore = getRecipeStore();

	/** Reveal the hidden built-in recipes (with an Unhide door). */
	let showHiddenRecipes = $state(false);
	const hiddenRecipeCount = $derived(recipeStore.hiddenBuiltinIds.length);
	$effect(() => {
		if (hiddenRecipeCount === 0) showHiddenRecipes = false;
	});

	/**
	 * The library — the user's recipes plus the credited built-ins (hidden
	 * ones only when revealed), grouped by method in label order: within a
	 * group the user's own first (favourites, then most recent), then the
	 * built-ins in catalogue order. Honors the page search.
	 */
	const recipeGroups = $derived.by(() => {
		const query = q.trim().toLowerCase();
		const builtins = showHiddenRecipes ? recipeStore.builtins : recipeStore.visibleBuiltins;
		const all = [...recipeStore.all, ...builtins];
		const list =
			query === ''
				? all
				: all.filter(
						(r) =>
							r.name.toLowerCase().includes(query) ||
							methodLabel(r.method).toLowerCase().includes(query) ||
							r.method.toLowerCase().includes(query) ||
							(r.credit ?? '').toLowerCase().includes(query)
					);
		const groups = new Map<string, BrewRecipe[]>();
		for (const r of list) groups.set(r.method, [...(groups.get(r.method) ?? []), r]);
		return [...groups.entries()]
			.sort(([a], [b]) => methodLabel(a).localeCompare(methodLabel(b)))
			.map(([method, recipes]) => ({ method, recipes }));
	});

	let recipeEditing = $state<BrewRecipe | null>(null);
	let recipeEditingNew = $state(false);

	// ── Your methods — the user's own brewers (issue #10 feedback) ───────
	const customMethods = getCustomMethodStore();
	/** The add / edit dialog: `null` closed, `'new'` adding, else the method being edited. */
	let methodDialog = $state<CustomBrewMethod | 'new' | null>(null);

	function styleLabel(m: CustomBrewMethod): string {
		return METHOD_STYLES.find((s) => s.id === m.style)?.label ?? 'Pour-over';
	}

	/**
	 * Delete (tombstone) a method. Past brews keep its name; the user's
	 * recipes for it are kept, still runnable and labelled — deleting them
	 * too is one more door in the recipe list, not a second prompt here.
	 */
	async function removeMethod(m: CustomBrewMethod): Promise<void> {
		const n = recipeStore.all.filter((r) => r.method === m.id).length;
		const recipesNote =
			n === 0 ? '' : ` Your ${n === 1 ? 'recipe' : `${n} recipes`} for it stay${n === 1 ? 's' : ''} and still run${n === 1 ? 's' : ''}.`;
		if (
			await confirmDialog({
				message: `Delete the "${m.label}" method? Past brews keep the name.${recipesNote}`,
				confirmLabel: 'Delete',
				danger: true
			})
		) {
			customMethods.remove(m.id);
		}
	}

	function newRecipe(): void {
		recipeEditing = newRecipeFor('pourover');
		recipeEditingNew = true;
	}

	/** "Duplicate" (a user recipe) / "Duplicate to edit" (a built-in):
	 *  save a credited copy and open it in the editor. */
	function duplicateRecipe(r: BrewRecipe): void {
		const copy = recipeStore.duplicate(r.id);
		if (copy) {
			recipeEditing = copy;
			recipeEditingNew = false;
		}
	}

	async function removeRecipe(r: BrewRecipe): Promise<void> {
		if (
			await confirmDialog({
				message: `Delete the "${r.name}" recipe?`,
				confirmLabel: 'Delete',
				danger: true
			})
		) {
			recipeStore.remove(r.id);
		}
	}

	function saveRecipe(r: BrewRecipe): void {
		// Becomes the method's default only when it has none (no pick, no
		// built-in) — see RecipeStore.saveEdit.
		recipeStore.saveEdit(r);
		closeRecipeEditor();
	}

	function closeRecipeEditor(): void {
		recipeEditing = null;
		recipeEditingNew = false;
	}

	/** Whether `r` is what the Scale page opens for its method. */
	function isDefaultRecipe(r: BrewRecipe): boolean {
		return recipeStore.isDefault(r);
	}

	const RECIPE_KIND_LABEL: Record<string, string> = {
		[BrewStepKind.Bloom]: 'Bloom',
		[BrewStepKind.Pour]: 'Pour',
		[BrewStepKind.Wait]: 'Wait',
		[BrewStepKind.Steep]: 'Steep',
		[BrewStepKind.Stir]: 'Stir',
		[BrewStepKind.Press]: 'Press',
		[BrewStepKind.Drawdown]: 'Drawdown',
		[BrewStepKind.Other]: 'Step'
	};

	/** "Bloom → Pour → Wait → Pour → Drawdown" — the card's plan line. */
	function stepChain(r: BrewRecipe): string {
		return (r.steps ?? []).map((s) => RECIPE_KIND_LABEL[s.kind] ?? 'Step').join(' → ');
	}

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
				<MagnifyingGlassIcon aria-hidden="true" />
				<input bind:value={q} placeholder="Search profiles, beans, notes…" />
				{#if q}
					<button class="pp-search-clear" aria-label="Clear search" onclick={() => (q = '')}>
						<XIcon aria-hidden="true" />
					</button>
				{/if}
			</div>
			<!-- Import: pick one-or-many community v2 .json or legacy
			     .tcl profile files. The hidden <input> is wrapped in a
			     label styled like the other header buttons so it
			     matches `.pp-btn-secondary`. -->
			<label
				class="pp-btn pp-btn-secondary pp-import"
				class:pp-import-disabled={importing}
				title="Import community v2 .json or legacy .tcl files, or a .jsonl bundle (one v2 profile per line)"
			>
				<UploadSimpleIcon aria-hidden="true" />
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
				<DownloadSimpleIcon aria-hidden="true" /> Export
			</button>
			<button class="pp-btn pp-btn-primary" onclick={() => goto(resolve('/profiles/new'))}>
				<PlusIcon aria-hidden="true" /> New profile
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
			<Icon
				cls={importBanner.kind === 'success' ? 'ph ph-check-circle' : 'ph ph-warning'}
				aria-hidden="true"
			 />
			<span>{importBanner.message}</span>
			<button
				type="button"
				class="pp-import-banner-close"
				aria-label="Dismiss"
				onclick={() => (importBanner = null)}
			>
				<XIcon aria-hidden="true" />
			</button>
		</div>
	{/if}

	<!-- Filter strip -->
	<div class="pp-filters">
		<FilterPills
			pills={filterPills}
			onclick={(id) => {
				// Two axes (#124). Status pills: re-clicking the active one
				// resets to the `all` catch-all (which stays pinned). Facet
				// pills: re-clicking the active one clears the facet.
				if (id === 'all' || id === 'pinned' || id === 'hidden') {
					status = status === id && id !== 'all' ? 'all' : id;
				} else {
					facet = facet === id ? null : id;
				}
			}}
		/>
		<div class="pp-sort">
			<SortPill
				value={{ field: sort, direction: sortDir }}
				options={SORT_OPTIONS.map((s) => ({ field: s.id, label: s.label }))}
				onchange={(next) => {
					const previousField = sort;
					sort = next.field as SortKey;
					// When the user picks a new field (not just flipping
					// direction), reapply that field's default direction so
					// e.g. "Name" defaults to A→Z instead of inheriting the
					// previous field's descending preference.
					if (next.field !== previousField) {
						const opt = SORT_OPTIONS.find((s) => s.id === next.field);
						sortDir = opt?.defaultDir ?? next.direction;
					} else {
						sortDir = next.direction;
					}
				}}
			/>
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
		<button class="pp-card-new" onclick={() => goto(resolve('/profiles/new'))}>
			<div class="pp-card-new-glyph"><PlusIcon aria-hidden="true" /></div>
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

	<!-- ── Brew recipes — guided plans, followed by hand (issue #10).
	     A separate section, not rows in the machine grid: selecting a
	     profile uploads it to the DE1; a recipe never touches the
	     machine. Hidden while browsing the Hidden archive. -->
	{#if !showingHidden}
		<div class="pp-recipes">
			<div class="pp-recipes-head">
				<div>
					<div class="t-eyebrow" style="color:rgba(var(--tint-rgb), 0.55)">Guided brews</div>
					<div class="pp-recipes-title">Brew recipes</div>
					<div class="pp-sub">
						Step plans you follow by hand — run them from the Scale page's Brew tab.
					</div>
				</div>
				<button class="pp-btn pp-btn-secondary" onclick={newRecipe}>
					<PlusIcon aria-hidden="true" /> New recipe
				</button>
			</div>
			<div class="pp-methods" aria-label="Your methods">
				<div class="pp-methods-head">
					<span class="pp-recipe-group-head" style="margin:0">Your methods</span>
					<button class="pp-link" onclick={() => (methodDialog = 'new')}>+ Add method</button>
				</div>
				{#if customMethods.live.length === 0}
					<p class="pp-sub" style="margin:0">
						Brew with something that isn't listed? Add it — it joins every method picker.
					</p>
				{:else}
					<ul class="pp-methods-list">
						{#each customMethods.live as m (m.id)}
							<li class="pp-method">
								<MethodMark method={m.id} tile />
								<span class="pp-method-id">
									<span class="pp-method-name">{m.label}</span>
									<span class="pp-method-meta">{styleLabel(m)}</span>
								</span>
								<span class="pp-method-actions">
									<button class="pp-link" onclick={() => (methodDialog = m)}>Rename</button>
									<button class="pp-link" onclick={() => (methodDialog = m)}>Edit defaults</button>
									<button class="pp-link pp-link-danger" onclick={() => void removeMethod(m)}
										>Delete</button
									>
								</span>
							</li>
						{/each}
					</ul>
				{/if}
			</div>
			{#if hiddenRecipeCount > 0}
				<div class="pp-recipes-tools">
					<button
						class="pp-link"
						aria-pressed={showHiddenRecipes}
						onclick={() => (showHiddenRecipes = !showHiddenRecipes)}
					>
						{showHiddenRecipes ? 'Hide hidden built-ins' : `Show hidden (${hiddenRecipeCount})`}
					</button>
				</div>
			{/if}
			{#if recipeGroups.length > 0}
				{#each recipeGroups as g (g.method)}
					<div class="pp-recipe-group">
						<div class="pp-recipe-group-head">
							<MethodMark method={g.method} size={14} />
							{methodLabel(g.method)}
						</div>
						<div class="pp-recipes-grid">
							{#each g.recipes as r (r.id)}
								{@const builtin = isBuiltinRecipe(r.id)}
								{@const hidden = builtin && recipeStore.isHidden(r.id)}
								{@const estimate = recipeEstimateLabel(r)}
								<div class="pp-recipe-card" class:is-hidden={hidden}>
									<div class="pp-recipe-top">
										<span class="pp-recipe-badges">
											{#if builtin}
												<span
													class="pp-recipe-builtin"
													title="Ships with Crema — read-only; duplicate it to make your own version"
													>BUILT-IN</span
												>
											{/if}
											{#if hidden}<span class="pp-recipe-builtin">HIDDEN</span>{/if}
										</span>
										{#if isDefaultRecipe(r)}
											<span
												class="pp-recipe-default"
												title="The Scale page opens this recipe for {methodLabel(r.method)}"
												>DEFAULT</span
											>
										{/if}
									</div>
									<div class="pp-recipe-name">{r.name}</div>
									<RecipeCredit credit={r.credit} sourceUrl={r.sourceUrl} />
									<div class="pp-recipe-meta">
										{r.doseG} g · {r.waterG} g water{#if r.tempC != null}
											· {Math.round(r.tempC)} °C{/if}{#if estimate} · ~{estimate}{/if}
									</div>
									<div class="pp-recipe-steps">{stepChain(r)}</div>
									<RecipeNotes notes={r.notes} lines={2} />
									<div class="pp-recipe-actions">
										{#if builtin}
											<button class="pp-recipe-btn" onclick={() => duplicateRecipe(r)}
												>Duplicate to edit</button
											>
										{:else}
											<button
												class="pp-recipe-btn"
												onclick={() => {
													recipeEditingNew = false;
													recipeEditing = r;
												}}>Edit</button
											>
											<button class="pp-recipe-btn" onclick={() => duplicateRecipe(r)}
												>Duplicate</button
											>
										{/if}
										{#if !isDefaultRecipe(r)}
											<button
												class="pp-recipe-btn"
												onclick={() => recipeStore.touch(r)}
												title="The Scale page opens the default recipe for {methodLabel(r.method)}"
												>Make default</button
											>
										{/if}
										{#if builtin}
											<button
												class="pp-recipe-btn pp-recipe-btn-quiet"
												onclick={() => (hidden ? recipeStore.unhide(r.id) : recipeStore.hide(r.id))}
												title={hidden
													? 'Show this built-in in the library and the Scale picker again'
													: 'Hide this built-in from the library and the Scale picker'}
												>{hidden ? 'Unhide' : 'Hide'}</button
											>
										{:else}
											<button
												class="pp-recipe-del"
												aria-label="Delete recipe"
												onclick={() => void removeRecipe(r)}
											>
												<TrashIcon aria-hidden="true" />
											</button>
										{/if}
									</div>
								</div>
							{/each}
						</div>
					</div>
				{/each}
			{:else}
				<div class="pp-recipes-empty">No recipes match the current search.</div>
			{/if}
		</div>
	{/if}

</div>

{#if methodDialog}
	<CustomMethodDialog
		editing={methodDialog === 'new' ? null : methodDialog}
		onSave={() => (methodDialog = null)}
		onClose={() => (methodDialog = null)}
	/>
{/if}

{#if recipeEditing}
	<RecipeEditor
		recipe={recipeEditing}
		heading={recipeEditingNew ? 'New recipe' : 'Edit recipe'}
		reseedOnMethodChange={recipeEditingNew}
		onSave={saveRecipe}
		onClose={closeRecipeEditor}
	/>
{/if}

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
	.pp-search :global(svg) {
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
	.pp-btn :global(svg) {
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
	/* `.pp-tag*` styles live in `styles/profiles-page.css` so the shared
	   FilterPills component reads them from one canonical source. The
	   page-local duplicates were removed when FilterPills replaced the
	   inline markup. */
	.pp-sort {
		display: flex;
		align-items: center;
		gap: 10px;
		flex: 0 0 auto;
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

	/* ── Brew recipes section ──────────────────────────────────────────── */
	.pp-recipes {
		padding: 8px var(--page-pad-x) 48px;
		border-top: 1px solid rgba(var(--tint-rgb), 0.05);
	}
	.pp-recipes-head {
		display: flex;
		align-items: flex-end;
		justify-content: space-between;
		gap: 24px;
		padding: 18px 0 16px;
	}
	.pp-recipes-title {
		font-family: var(--font-serif);
		font-size: 22px;
		font-weight: 500;
		margin-top: 2px;
	}
	.pp-recipes-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(290px, 1fr));
		gap: 14px;
	}
	.pp-recipe-card {
		display: flex;
		flex-direction: column;
		gap: 6px;
		background: rgba(var(--tint-rgb), 0.03);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: var(--radius-md);
		padding: 14px 16px;
	}
	.pp-recipe-top {
		display: flex;
		justify-content: space-between;
		align-items: center;
		gap: 10px;
	}
	.pp-recipe-default {
		font-family: var(--font-sans);
		font-size: 9px;
		font-weight: 700;
		letter-spacing: 0.06em;
		color: var(--copper-400);
		border: 1px solid var(--copper-400);
		border-radius: var(--radius-pill);
		padding: 2px 8px;
	}
	.pp-recipe-name {
		font-family: var(--font-serif);
		font-size: 18px;
	}
	.pp-recipe-meta {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 11.5px;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.pp-recipe-steps {
		font-family: var(--font-sans);
		font-size: 11.5px;
		color: rgba(var(--tint-rgb), 0.5);
	}
	.pp-recipe-actions {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 6px;
		margin-top: auto;
		padding-top: 8px;
	}
	.pp-link {
		background: transparent;
		border: 0;
		padding: 2px 0;
		color: var(--copper-400);
		font-family: var(--font-sans);
		font-size: 12.5px;
		text-decoration: underline;
		text-underline-offset: 2px;
		cursor: pointer;
	}
	.pp-recipe-btn {
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 12px;
		font-weight: 500;
		padding: 6px 11px;
		border-radius: var(--radius-pill);
		cursor: pointer;
	}
	.pp-recipe-btn:hover {
		background: rgba(var(--tint-rgb), 0.08);
	}
	.pp-recipe-del {
		margin-left: auto;
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.4);
		cursor: pointer;
		padding: 4px;
	}
	.pp-recipe-del:hover {
		color: var(--danger);
	}
	.pp-methods {
		display: flex;
		flex-direction: column;
		gap: 8px;
		padding: 12px 14px;
		margin-bottom: 18px;
		border-radius: var(--radius-md, 12px);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		background: rgba(var(--tint-rgb), 0.025);
	}
	.pp-methods-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 12px;
	}
	.pp-methods-list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.pp-method {
		display: flex;
		align-items: center;
		gap: 10px;
		flex-wrap: wrap;
	}
	.pp-method-id {
		display: flex;
		flex-direction: column;
		min-width: 0;
		flex: 1 1 120px;
	}
	.pp-method-name {
		font-family: var(--font-sans);
		font-size: 14px;
		font-weight: 600;
		color: var(--fg-1);
	}
	.pp-method-meta {
		font-size: 11.5px;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.pp-method-actions {
		display: inline-flex;
		gap: 12px;
	}
	.pp-link-danger:hover {
		color: var(--danger);
	}
	.pp-recipes-tools {
		display: flex;
		justify-content: flex-end;
		margin: -6px 0 6px;
	}
	.pp-recipe-group + .pp-recipe-group {
		margin-top: 22px;
	}
	.pp-recipe-group-head {
		display: flex;
		align-items: center;
		gap: 7px;
		font-family: var(--font-sans);
		font-size: 11px;
		font-weight: 600;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(var(--tint-rgb), 0.6);
		margin-bottom: 10px;
	}
	.pp-recipe-badges {
		display: inline-flex;
		gap: 6px;
		min-height: 18px;
	}
	.pp-recipe-builtin {
		font-family: var(--font-sans);
		font-size: 9px;
		font-weight: 700;
		letter-spacing: 0.06em;
		color: rgba(var(--tint-rgb), 0.6);
		border: 1px solid rgba(var(--tint-rgb), 0.2);
		border-radius: var(--radius-pill);
		padding: 2px 8px;
	}
	.pp-recipe-card.is-hidden {
		opacity: 0.6;
	}
	.pp-recipe-btn-quiet {
		margin-left: auto;
		background: transparent;
		color: rgba(var(--tint-rgb), 0.6);
	}
	.pp-recipes-empty {
		padding: 26px 0 8px;
		color: rgba(var(--tint-rgb), 0.5);
		font-family: var(--font-sans);
		font-size: 13px;
	}
	@media (max-width: 720px) {
		.pp-recipes {
			padding-left: 20px;
			padding-right: 20px;
		}
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
