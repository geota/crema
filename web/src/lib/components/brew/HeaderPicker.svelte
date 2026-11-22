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

	/** One of the picker's tabs (Brew's Espresso | Manual). */
	export type PickerTab = { id: string; label: string };
</script>

<script lang="ts">
	/**
	 * `HeaderPicker` — the dropdown a brew-header block's ▾ opens: a search box, a
	 * scrollable all-items list (pinned/favourites first, the active one marked),
	 * and a `[+ Add] [Open library]` footer. Presentational; the parent owns the
	 * data + the activate/route handlers and the open state.
	 *
	 * With `tabs`, a segmented tab row sits beside the search box (Brew's
	 * Espresso | Manual, brew modes §2): the parent swaps `items` per tab, so
	 * the picker keeps its height. `locked` disables the rows, with its note
	 * above them; the tabs still browse (§4).
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
		tabs,
		tab,
		onTab,
		locked = null
	}: {
		open?: boolean;
		/** Dialog name, e.g. "Choose profile" (final review 07 N5). */
		label?: string;
		items: PickerItem[];
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
		onSelect: (id: string) => void;
		onAdd?: () => void;
		/** Edit the active item (the footer ✎). */
		onEdit?: () => void;
		onLibrary?: () => void;
		onClose?: () => void;
		/** Tabs beside the search box; omit for a plain picker. */
		tabs?: readonly PickerTab[];
		/** The tab on show. */
		tab?: string;
		onTab?: (id: string) => void;
		/** Rows disabled, with this note above them (`null` = enabled). */
		locked?: string | null;
	} = $props();

	const listId = $props.id();

	/** Arrow keys move between the tabs (the WAI-ARIA tabs pattern). */
	function onTabKeydown(e: KeyboardEvent): void {
		if (!tabs || (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight')) return;
		e.preventDefault();
		const i = tabs.findIndex((t) => t.id === tab);
		const next = tabs[(i + (e.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length];
		onTab?.(next.id);
		(e.currentTarget as HTMLElement).parentElement
			?.querySelector<HTMLElement>(`[data-tab="${next.id}"]`)
			?.focus();
	}

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
		requestAnimationFrame(() => inputEl?.focus());
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

	const filtered = $derived.by(() => {
		const q = query.trim().toLowerCase();
		if (q === '') {
			// Active item first, then pinned/favourites — shared library ordering
			// (same primitive as the beans/profiles pages).
			return pinActiveThenFavourite(items, activeId, (it) => it.pinned ?? false);
		}
		const scores = rank?.(query.trim()) ?? null;
		if (scores) {
			// Ranked: relevance decides, and the pinning is suspended — the same
			// rule the library page follows, so a search behaves identically
			// wherever it is run.
			return items
				.filter((it) => scores.has(it.id))
				.sort((a, b) => (scores.get(b.id) ?? 0) - (scores.get(a.id) ?? 0));
		}
		const base = items.filter((it) =>
			(it.search ?? `${it.primary} ${it.secondary ?? ''}`).toLowerCase().includes(q)
		);
		return pinActiveThenFavourite(base, activeId, (it) => it.pinned ?? false);
	});

	function choose(id: string): void {
		if (locked) return;
		onSelect(id);
		onClose?.();
	}
</script>

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
			{#if tabs}
				<div class="hpick-tabs" role="tablist" aria-label="Brew mode">
					{#each tabs as t (t.id)}
						<button
							type="button"
							role="tab"
							class={['hpick-tab', { 'is-on': t.id === tab }]}
							data-tab={t.id}
							aria-selected={t.id === tab}
							aria-controls={listId}
							tabindex={t.id === tab ? 0 : -1}
							onclick={() => onTab?.(t.id)}
							onkeydown={onTabKeydown}>{t.label}</button
						>
					{/each}
				</div>
			{/if}
			<MagnifyingGlassIcon aria-hidden="true" />
			<input
				bind:this={inputEl}
				bind:value={query}
				type="text"
				placeholder={searchPlaceholder}
				aria-label={searchPlaceholder.replace(/…$/, '')}
			/>
		</div>

		{#if locked}<div class="hpick-locked" role="note">{locked}</div>{/if}
		<div class="hpick-list" id={listId} role={tabs ? 'tabpanel' : undefined}>
			{#each filtered as it (it.id)}
				<button
					type="button"
					class="hpick-item"
					class:is-active={it.id === activeId}
					disabled={locked != null}
					onclick={() => choose(it.id)}
				>
					{#if it.method}
						<span class="hpick-graph hpick-method"><MethodMark method={it.method} size={18} /></span>
					{:else if it.segments}
						<span class="hpick-graph">
							<ProfilePreview segments={it.segments} active={it.id === activeId} compact />
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
					{#if it.id === activeId}<CheckIcon class="hpick-check" aria-hidden="true" />{/if}
				</button>
			{:else}
				<div class="hpick-empty">No matches</div>
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
