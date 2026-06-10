<script lang="ts" module>
	import BooksIcon from 'phosphor-svelte/lib/BooksIcon';
	import CheckIcon from 'phosphor-svelte/lib/CheckIcon';
	import MagnifyingGlassIcon from 'phosphor-svelte/lib/MagnifyingGlassIcon';
	import PencilSimpleIcon from 'phosphor-svelte/lib/PencilSimpleIcon';
	import PlusIcon from 'phosphor-svelte/lib/PlusIcon';
	import LinkSimpleIcon from 'phosphor-svelte/lib/LinkSimpleIcon';
	import StarIcon from 'phosphor-svelte/lib/StarIcon';
	import type { ProfileSegment } from '$lib/profiles';

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
	};
</script>

<script lang="ts">
	/**
	 * `HeaderPicker` — the dropdown a brew-header block's ▾ opens: a search box, a
	 * scrollable all-items list (pinned/favourites first, the active one marked),
	 * and a `[+ Add] [Open library]` footer. Presentational; the parent owns the
	 * data + the activate/route handlers and the open state.
	 */
	import ProfilePreview from '$lib/components/profiles/ProfilePreview.svelte';

	let {
		open = false,
		items,
		activeId = null,
		searchPlaceholder = 'Search…',
		addLabel,
		canEdit = true,
		onSelect,
		onAdd,
		onEdit,
		onLibrary,
		onClose
	}: {
		open?: boolean;
		items: PickerItem[];
		activeId?: string | null;
		searchPlaceholder?: string;
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
	} = $props();

	let query = $state('');
	let inputEl = $state<HTMLInputElement | null>(null);

	// Reset + focus the search each time the picker opens.
	$effect(() => {
		if (open) {
			query = '';
			requestAnimationFrame(() => inputEl?.focus());
		}
	});

	const filtered = $derived.by(() => {
		const q = query.trim().toLowerCase();
		const base =
			q === ''
				? items
				: items.filter((it) =>
						(it.search ?? `${it.primary} ${it.secondary ?? ''}`).toLowerCase().includes(q)
					);
		// Active item first, then pinned/favourites; stable sort keeps store order.
		const rank = (it: PickerItem) => (it.id === activeId ? 2 : 0) + (it.pinned ? 1 : 0);
		return [...base].sort((x, y) => rank(y) - rank(x));
	});

	function choose(id: string): void {
		onSelect(id);
		onClose?.();
	}
</script>

{#if open}
	<div class="hpick" role="dialog" aria-label="Quick picker">
		<div class="hpick-search">
			<MagnifyingGlassIcon aria-hidden="true" />
			<input
				bind:this={inputEl}
				bind:value={query}
				type="text"
				placeholder={searchPlaceholder}
				onkeydown={(e) => e.key === 'Escape' && onClose?.()}
			/>
		</div>

		<div class="hpick-list">
			{#each filtered as it (it.id)}
				<button
					type="button"
					class="hpick-item"
					class:is-active={it.id === activeId}
					onclick={() => choose(it.id)}
				>
					{#if it.segments}
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
