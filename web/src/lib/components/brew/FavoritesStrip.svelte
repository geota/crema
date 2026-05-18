<script lang="ts">
	/**
	 * `FavoritesStrip` — the pinned-profile strip at the top of the Quick Sheet,
	 * ported from the design's `FavoritesStrip` in `web-dashboard-v2.jsx`.
	 *
	 * An inline fuzzy search filters the list; left / right arrows nudge the
	 * horizontal scroll and disable at the ends. The profile data is the
	 * placeholder `SAMPLE_FAVORITES` set (see `favorites.ts`) — the real
	 * library lands when the core exposes a profile model.
	 */
	import type { Attachment } from 'svelte/attachments';
	import QSparkline from './QSparkline.svelte';
	import { SAMPLE_FAVORITES, type FavoriteProfile } from './favorites';

	let {
		favorite,
		onSelect
	}: {
		/** The selected favorite's id. */
		favorite: string;
		/** Called with the chosen favorite. */
		onSelect: (profile: FavoriteProfile) => void;
	} = $props();

	/** The live search query. */
	let query = $state('');
	/** The scroll container — captured by the attachment for the nudge buttons. */
	let scrollEl: HTMLDivElement | null = null;
	/** Whether the strip can scroll further left / right. */
	let canScrollLeft = $state(false);
	let canScrollRight = $state(false);

	/** Filtered list: the full sample set when searching, all of it otherwise. */
	const items = $derived.by(() => {
		const q = query.trim().toLowerCase();
		if (q === '') return SAMPLE_FAVORITES;
		return SAMPLE_FAVORITES.filter(
			(p) => p.name.toLowerCase().includes(q) || p.bean.toLowerCase().includes(q)
		);
	});

	/**
	 * Recompute the arrow-enabled flags from the scroll geometry. The arrow
	 * state genuinely depends on imperative DOM measurements (`scrollLeft`,
	 * `clientWidth`, `scrollWidth`), so it cannot be a pure `$derived`.
	 */
	function syncArrows(): void {
		const el = scrollEl;
		if (!el) return;
		canScrollLeft = el.scrollLeft > 4;
		canScrollRight = el.scrollLeft + el.clientWidth < el.scrollWidth - 4;
	}

	/**
	 * Attachment on the scroll container: captures the element, syncs the
	 * arrow state on mount and on resize, and re-syncs when the filtered list
	 * changes (the `items.length` read makes the attachment reactive to it).
	 */
	const trackScroll: Attachment<HTMLDivElement> = (el) => {
		scrollEl = el;
		void items.length;
		syncArrows();
		const observer = new ResizeObserver(syncArrows);
		observer.observe(el);
		return () => {
			observer.disconnect();
			scrollEl = null;
		};
	};

	/** Nudge the strip one card-group left (`-1`) or right (`+1`). */
	function nudge(dir: number): void {
		scrollEl?.scrollBy({ left: dir * 240, behavior: 'smooth' });
	}
</script>

<div class="qfstrip-wrap">
	<div class="qfstrip-search-inline">
		<i class="ph ph-magnifying-glass" aria-hidden="true"></i>
		<input bind:value={query} placeholder="Search profiles" aria-label="Search profiles" />
		{#if query}
			<button class="qfstrip-search-clear" onclick={() => (query = '')} aria-label="Clear search">
				<i class="ph ph-x" aria-hidden="true"></i>
			</button>
		{/if}
	</div>
	<button
		class="qfstrip-arrow qfstrip-arrow-l"
		class:is-off={!canScrollLeft}
		onclick={() => nudge(-1)}
		tabindex={canScrollLeft ? 0 : -1}
		aria-label="Scroll favorites left"
	>
		<i class="ph ph-caret-left" aria-hidden="true"></i>
	</button>
	<div class="qfstrip" {@attach trackScroll} onscroll={syncArrows}>
		{#each items as profile (profile.id)}
			<button
				class="qfstrip-item"
				class:is-active={favorite === profile.id}
				onclick={() => onSelect(profile)}
			>
				<QSparkline
					shape={profile.shape}
					width={28}
					height={14}
					color={favorite === profile.id ? 'var(--copper-400)' : 'rgba(244,237,224,0.55)'}
				/>
				<span class="qfstrip-name">{profile.name}</span>
				<span class="qfstrip-ratio">{profile.ratio}</span>
			</button>
		{/each}
		{#if items.length === 0}
			<div class="qfstrip-empty">No profiles match "{query}"</div>
		{/if}
	</div>
	<button
		class="qfstrip-arrow qfstrip-arrow-r"
		class:is-off={!canScrollRight}
		onclick={() => nudge(1)}
		tabindex={canScrollRight ? 0 : -1}
		aria-label="Scroll favorites right"
	>
		<i class="ph ph-caret-right" aria-hidden="true"></i>
	</button>
</div>
