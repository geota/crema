<script lang="ts" module>
	import BooksIcon from 'phosphor-svelte/lib/BooksIcon';
	import { pinActiveThenFavourite } from '#lib/library-order.js';
	import CheckIcon from 'phosphor-svelte/lib/CheckIcon';
	import MagnifyingGlassIcon from 'phosphor-svelte/lib/MagnifyingGlassIcon';
	import PencilSimpleIcon from 'phosphor-svelte/lib/PencilSimpleIcon';
	import PlusIcon from 'phosphor-svelte/lib/PlusIcon';
	import LinkSimpleIcon from 'phosphor-svelte/lib/LinkSimpleIcon';
	import StarIcon from 'phosphor-svelte/lib/StarIcon';
	import type { ProfileSegment } from '#lib/profiles/index.js';

	/** One row in a header picker — a profile or a bean, normalised. */
	export type PickerItem = {
		/** Stable id (passed back to `onSelect`). */
		id: string;
		/** Primary line — profile name, or "Roaster · Bean". */
		primary: string;
		/** Secondary line — author / origin / roast, etc. */
		secondary?: string;
		/** Pinned/favourite items sort to the top. */
		pinned?: boolean;
		/** Avatar glyph (bean roaster mark); omit for a plain row. */
		mark?: string;
		/** Avatar colour for `mark`. */
		tone?: string;
		/** Profile segments — renders a mini curve preview instead of an avatar. */
		segments?: ProfileSegment[];
		/** Lowercased haystack for search; falls back to primary + secondary. */
		search?: string;
		/** Bean rows: has a linked profile (renders a small link glyph). */
		linked?: boolean;
		/** Recipe rows: the brew method, drawn as its mark. */
		method?: string;
	};

	/** One section of a sectioned picker (Brew's profiles, then recipes). */
	export type PickerSection = {
		id: string;
		/** "Espresso profiles". */
		label: string;
		items: PickerItem[];
		activeId?: string | null;
		/** The rows are disabled, with this note under the heading. */
		locked?: string | null;
	};
</script>

<script lang="ts">
	/**
	 * `HeaderPicker` — the dropdown a brew-header block's ▾ opens: a search box, a
	 * scrollable all-items list (pinned/favourites first, the active one marked),
	 * and a `[+ Add] [Open library]` footer. Presentational; the parent owns the
	 * data + the activate/route handlers and the open state.
	 *
	 * With `sections`, the list is one scroll of headed sections (Brew's
	 * Espresso profiles, then Manual recipes; brew modes §2) and the search
	 * filters each. A section's `locked` note disables its rows (§4).
	 */
	import MethodMark from '#lib/components/brewlog/MethodMark.svelte';
	import ProfilePreview from '#lib/components/profiles/ProfilePreview.svelte';
	import { restoreFocus } from '#lib/a11y/focus.js';

	let {
		open = false,
		label = 'Choose an item',
		items,
		activeId = null,
		searchPlaceholder = 'Search…',
		addLabel,
		canEdit = true,
		onSelect,
		onAdd,
		onEdit,
		onLibrary,
		onClose,
		rank,
		sections
	}: {
		open?: boolean;
		/** Dialog name, e.g. "Choose profile" (final review 07 N5). */
		label?: string;
		/** The rows of a plain picker; ignored with `sections`. */
		items?: PickerItem[];
		activeId?: string | null;
		searchPlaceholder?: string;
		/**
		 * Optional relevance ranker: given the typed query, return `id → score`
		 * for the items that match (higher = better), or `null` to fall back to
		 * the built-in substring filter. The bean picker passes the core's fuzzy
		 * matcher through here so the dropdown finds the same bags the library
		 * page does; profiles keep the substring path.
		 */
		rank?: (query: string) => Map<string, number> | null;
		/** Footer add label, e.g. "Add profile". */
		addLabel: string;
		/** Whether the active item can be edited (disables the ✎ when false). */
		canEdit?: boolean;
		/** A row was picked; `section` is its section's id in a sectioned picker. */
		onSelect: (id: string, section?: string) => void;
		onAdd?: () => void;
		/** Edit the active item (the footer ✎). */
		onEdit?: () => void;
		onLibrary?: () => void;
		onClose?: () => void;
		/** Headed sections in place of `items`. */
		sections?: readonly PickerSection[];
	} = $props();

	let query = $state('');
	let inputEl = $state<HTMLInputElement | null>(null);

	let panelEl = $state<HTMLDivElement | null>(null);

	// Reset + focus the search each time the picker opens. On close, hand
	// focus back to the opener when it was inside the picker or dropped to
	// <body> (Escape, picking an item), never when the user clicked
	// elsewhere (final review 07 N5: Escape left focus on BODY).
	$effect(() => {
		if (!open) return;
		query = '';
		const opener = document.activeElement;
		requestAnimationFrame(() => {
			inputEl?.focus();
			// A sectioned list opens on the selection, wherever it sits.
			if (sections) panelEl?.querySelector('.hpick-item.is-active')?.scrollIntoView?.({ block: 'nearest' });
		});
		return () => {
			const active = document.activeElement;
			if (active == null || active === document.body || panelEl?.contains(active)) {
				restoreFocus(opener);
			}
		};
	});

	function onPanelKeydown(e: KeyboardEvent): void {
		if (e.key !== 'Escape') return;
		e.preventDefault();
		e.stopPropagation();
		onClose?.();
	}

	/** `list` as the picker shows it: ordered, or filtered by the query. */
	function shown(list: PickerItem[], active: string | null, ranked: boolean): PickerItem[] {
		const q = query.trim().toLowerCase();
		if (q === '') {
			// Active item first, then pinned/favourites — shared library ordering
			// (same primitive as the beans/profiles pages).
			return pinActiveThenFavourite(list, active, (it) => it.pinned ?? false);
		}
		const scores = ranked ? (rank?.(query.trim()) ?? null) : null;
		if (scores) {
			// Ranked: relevance decides, and the pinning is suspended — the same
			// rule the library page follows, so a search behaves identically
			// wherever it is run.
			return list
				.filter((it) => scores.has(it.id))
				.sort((a, b) => (scores.get(b.id) ?? 0) - (scores.get(a.id) ?? 0));
		}
		const base = list.filter((it) =>
			(it.search ?? `${it.primary} ${it.secondary ?? ''}`).toLowerCase().includes(q)
		);
		return pinActiveThenFavourite(base, active, (it) => it.pinned ?? false);
	}

	/** A plain picker is one untitled section. */
	const groups = $derived(
		(sections ?? [{ id: '', label: '', items: items ?? [], activeId, locked: null }]).map((sec) => ({
			...sec,
			rows: shown(sec.items, sec.activeId ?? null, sections == null)
		}))
	);

	function choose(id: string, section: string): void {
		onSelect(id, sections ? section : undefined);
		onClose?.();
	}
</script>

{#snippet rows(list: PickerItem[], active: string | null, locked: string | null, section: string)}
	{#each list as it (it.id)}
		<button
			type="button"
			class="hpick-item"
			class:is-active={it.id === active}
			disabled={locked != null}
			onclick={() => choose(it.id, section)}
		>
			{#if it.method}
				<span class="hpick-graph hpick-method"><MethodMark method={it.method} size={18} /></span>
			{:else if it.segments}
				<span class="hpick-graph">
					<ProfilePreview segments={it.segments} active={it.id === active} compact />
				</span>
			{:else if it.mark}
				<span class="hpick-av" style="background:{it.tone ?? 'var(--copper-500)'}">{it.mark}</span>
			{/if}
			<span class="hpick-text">
				<span class="hpick-primary">{it.primary}</span>
				{#if it.secondary}<span class="hpick-secondary">{it.secondary}</span>{/if}
			</span>
			{#if it.linked}<LinkSimpleIcon class="hpick-link" aria-hidden="true" />{/if}
			{#if it.pinned}<StarIcon weight="fill" class="hpick-pin" aria-hidden="true" />{/if}
			{#if it.id === active}<CheckIcon class="hpick-check" aria-hidden="true" />{/if}
		</button>
	{:else}
		<div class="hpick-empty">No matches</div>
	{/each}
{/snippet}

{#if open}
	<div
		bind:this={panelEl}
		class="hpick"
		role="dialog"
		aria-label={label}
		tabindex="-1"
		onkeydown={onPanelKeydown}
	>
		<div class="hpick-search">
			<MagnifyingGlassIcon aria-hidden="true" />
			<input
				bind:this={inputEl}
				bind:value={query}
				type="text"
				placeholder={searchPlaceholder}
				aria-label={searchPlaceholder.replace(/…$/, '')}
			/>
		</div>

		<div class="hpick-list">
			{#each groups as g (g.id)}
				{#if sections}
					<div class="hpick-section" role="group" aria-labelledby="hpick-sec-{g.id}">
						<div class="hpick-section-head" id="hpick-sec-{g.id}">{g.label}</div>
						{#if g.locked}<div class="hpick-locked" role="note">{g.locked}</div>{/if}
						{@render rows(g.rows, g.activeId ?? null, g.locked ?? null, g.id)}
					</div>
				{:else}
					{@render rows(g.rows, g.activeId ?? null, null, g.id)}
				{/if}
			{/each}
		</div>

		<div class="hpick-foot">
			<button type="button" class="hpick-foot-lib" onclick={() => { onLibrary?.(); onClose?.(); }}>
				<BooksIcon aria-hidden="true" />Open library
			</button>
			<button
				type="button"
				class="hpick-foot-icon"
				aria-label={addLabel}
				title={addLabel}
				onclick={() => { onAdd?.(); onClose?.(); }}
			>
				<PlusIcon aria-hidden="true" />
			</button>
			<button
				type="button"
				class="hpick-foot-icon"
				aria-label="Edit"
				title="Edit"
				disabled={!canEdit}
				onclick={() => { onEdit?.(); onClose?.(); }}
			>
				<PencilSimpleIcon aria-hidden="true" />
			</button>
		</div>
	</div>
{/if}
