<script lang="ts">
	/**
	 * `FavoritesStrip` — the pinned-profile strip at the top of the Quick Sheet,
	 * ported from the design's `FavoritesStrip` in `web-dashboard-v2.jsx`.
	 *
	 * An inline fuzzy search filters the list; left / right arrows nudge the
	 * horizontal scroll and disable at the ends. The strip shows the **real**
	 * pinned profiles from the `lib/profiles` library store — the caller passes
	 * them in, already filtered to `pinned` (see `BrewDashboard`).
	 */
	import type { Attachment } from 'svelte/attachments';
	import QSparkline from './QSparkline.svelte';
	import { ratioLabel, sparkShape, type CremaProfile } from '$lib/profiles';

	let {
		profiles,
		selectedId,
		onSelect
	}: {
		/** The pinned profiles to show as favorite chips. */
		profiles: readonly CremaProfile[];
		/** The active profile's id (the highlighted chip), or `null`. */
		selectedId: string | null;
		/** Called with the chosen profile. */
		onSelect: (profile: CremaProfile) => void;
	} = $props();

	/** The live search query. */
	let query = $state('');
	/** The scroll container — captured by the attachment for the nudge buttons. */
	let scrollEl: HTMLDivElement | null = null;
	/** Whether the strip can scroll further left / right. */
	let canScrollLeft = $state(false);
	let canScrollRight = $state(false);

	/** Filtered list: the full pinned set when searching, all of it otherwise. */
	const items = $derived.by(() => {
		const q = query.trim().toLowerCase();
		if (q === '') return profiles;
		return profiles.filter(
			(p) =>
				p.name.toLowerCase().includes(q) || p.bean.toLowerCase().includes(q)
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
	 * Attachment on the scroll container: a *one-time* element capture plus the
	 * `ResizeObserver` set-up / tear-down. It deliberately does **not** read the
	 * item list — re-measuring when the list changes is the separate `$effect`
	 * below, so a search keystroke does not tear the observer down and rebuild
	 * it on every character.
	 */
	const trackScroll: Attachment<HTMLDivElement> = (el) => {
		scrollEl = el;
		syncArrows();
		const observer = new ResizeObserver(syncArrows);
		observer.observe(el);
		return () => {
			observer.disconnect();
			scrollEl = null;
		};
	};

	// Re-measure the arrows whenever the rendered list changes (a search
	// filters it, or the pinned set updates). This is list-dependent state, so
	// it lives here rather than in the element-lifecycle attachment above.
	$effect(() => {
		void items.length;
		syncArrows();
	});

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
				class:is-active={selectedId === profile.id}
				onclick={() => onSelect(profile)}
			>
				<QSparkline
					shape={sparkShape(profile.segments)}
					width={28}
					height={14}
					color={selectedId === profile.id
						? 'var(--copper-400)'
						: 'rgba(244,237,224,0.55)'}
				/>
				<span class="qfstrip-name">{profile.name || 'Untitled profile'}</span>
				<span class="qfstrip-ratio">{ratioLabel(profile)}</span>
			</button>
		{/each}
		{#if items.length === 0}
			<div class="qfstrip-empty">
				{#if profiles.length === 0}
					No pinned profiles — pin one from the Profiles page
				{:else}
					No profiles match "{query}"
				{/if}
			</div>
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
