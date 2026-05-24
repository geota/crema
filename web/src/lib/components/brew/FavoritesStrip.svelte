<script lang="ts">
	/**
	 * `FavoritesStrip` — the unified pinned-favourites strip at the top of the
	 * Quick Sheet. Renders **both** pinned profiles and pinned beans as
	 * same-height pill chips, separated by a thin divider, and shares a
	 * single search box that filters across both halves.
	 *
	 * Layout (left → right):
	 *
	 *   [ search ] [ ◀ ] [ profiles… │ beans… ] [ ▶ ]
	 *
	 * Active-state styling is consistent across both halves — the same
	 * `.qfstrip-item.is-active` rule paints a copper border + soft glow
	 * whether the active item is a profile or a bean — so "this one is
	 * selected" reads identically.
	 *
	 * Search matches profile name + tags + author, and bean name +
	 * roaster name + tags + origin country. A section with no matches
	 * just shows empty; a strip with no matches at all shows a single
	 * "No matches" placeholder.
	 */
	import { untrack } from 'svelte';
	import type { Attachment } from 'svelte/attachments';
	import ProfilePreview from '$lib/components/profiles/ProfilePreview.svelte';
	import BeanPill from './BeanPill.svelte';
	import { ratioLabel, type CremaProfile } from '$lib/profiles';
	import type { Bean, Roaster } from '$lib/bean';

	let {
		profiles,
		selectedProfileId,
		onSelectProfile,
		beans,
		roasters,
		activeBeanId,
		onSelectBean
	}: {
		/** The pinned profiles to show as favourite chips. */
		profiles: readonly CremaProfile[];
		/** The active profile's id (the highlighted chip), or `null`. */
		selectedProfileId: string | null;
		/** Called with the chosen profile. */
		onSelectProfile: (profile: CremaProfile) => void;
		/** The pinned beans to show as favourite chips (favourite && !archived). */
		beans: readonly Bean[];
		/** The roaster directory — needed to render the bean chip's mark + tone. */
		roasters: readonly Roaster[];
		/** The active bean's id (the highlighted chip), or `null`. */
		activeBeanId: string | null;
		/** Called with the chosen bean. */
		onSelectBean: (bean: Bean) => void;
	} = $props();

	/** The live search query — applies to both halves of the strip. */
	let query = $state('');
	// The scroll container — captured by the attachment for the nudge buttons.
	// `$state` because it is read inside the list-change `$effect` below: the
	// effect must re-run once the element is captured.
	let scrollEl = $state<HTMLDivElement | null>(null);
	/** Whether the strip can scroll further left / right. */
	let canScrollLeft = $state(false);
	let canScrollRight = $state(false);

	/** Roaster lookup map — by-id, rebuilt only when the directory changes. */
	const roasterById = $derived.by(() => {
		const m = new Map<string, Roaster>();
		for (const r of roasters) m.set(r.id, r);
		return m;
	});

	/** Filtered profiles: full pinned set when not searching. */
	const profileItems = $derived.by(() => {
		const q = query.trim().toLowerCase();
		if (q === '') return profiles;
		return profiles.filter((p) => {
			if (p.name.toLowerCase().includes(q)) return true;
			if (p.author && p.author.toLowerCase().includes(q)) return true;
			for (const t of p.tags) if (t.toLowerCase().includes(q)) return true;
			return false;
		});
	});

	/** Filtered beans: full pinned set when not searching. */
	const beanItems = $derived.by(() => {
		const q = query.trim().toLowerCase();
		if (q === '') return beans;
		return beans.filter((b) => {
			if (b.name.toLowerCase().includes(q)) return true;
			const r = b.roasterId ? roasterById.get(b.roasterId) : null;
			if (r && r.name.toLowerCase().includes(q)) return true;
			for (const t of b.tags) if (t.toLowerCase().includes(q)) return true;
			const country = b.origin.country?.toLowerCase();
			if (country && country.includes(q)) return true;
			return false;
		});
	});

	/** Whether either half rendered anything — drives the empty-state row. */
	const hasAny = $derived(profileItems.length > 0 || beanItems.length > 0);
	const hasAnyPinned = $derived(profiles.length > 0 || beans.length > 0);

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
	 * item lists — re-measuring when they change is the separate `$effect`
	 * below, so a search keystroke does not tear the observer down on every
	 * character.
	 */
	const trackScroll: Attachment<HTMLDivElement> = (el) => {
		scrollEl = el;
		// Attachments run as effects in Svelte 5. We *write* `scrollEl` above —
		// reading it back inside the same attachment (via `syncArrows`) would
		// make this effect track a piece of state it also writes, which
		// throws `effect_update_depth_exceeded`. `untrack` runs the initial
		// sync without subscribing to `scrollEl`; subsequent syncs happen
		// outside any effect (ResizeObserver callback, scroll event) and the
		// list-change `$effect` below.
		untrack(syncArrows);
		const observer = new ResizeObserver(syncArrows);
		observer.observe(el);
		return () => {
			observer.disconnect();
			scrollEl = null;
		};
	};

	// Re-measure the arrows whenever either rendered list changes (a search
	// filters them, or a pinned set updates). List-dependent state, so it
	// lives here rather than in the element-lifecycle attachment above.
	$effect(() => {
		void profileItems.length;
		void beanItems.length;
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
		<input
			bind:value={query}
			placeholder="Search profiles + beans"
			aria-label="Search profiles and beans"
		/>
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
		{#if profileItems.length > 0}
			<i
				class="ph ph-list-bullets qfstrip-section-icon"
				aria-label="Profiles"
				title="Profiles"
			></i>
		{/if}
		{#each profileItems as profile (profile.id)}
			<button
				class="qfstrip-item"
				class:is-active={selectedProfileId === profile.id}
				onclick={() => onSelectProfile(profile)}
			>
				<ProfilePreview
					segments={profile.segments}
					active={selectedProfileId === profile.id}
					compact
				/>
				<span class="qfstrip-name">{profile.name || 'Untitled profile'}</span>
				<span class="qfstrip-ratio">{ratioLabel(profile)}</span>
			</button>
		{/each}
		{#if profileItems.length > 0 && beanItems.length > 0}
			<span class="qfstrip-divider" aria-hidden="true"></span>
		{/if}
		{#if beanItems.length > 0}
			<i
				class="ph ph-coffee-bean qfstrip-section-icon"
				aria-label="Beans"
				title="Beans"
			></i>
		{/if}
		{#each beanItems as bean (bean.id)}
			{@const roaster = bean.roasterId ? (roasterById.get(bean.roasterId) ?? null) : null}
			<button
				class="qfstrip-item"
				class:is-active={activeBeanId === bean.id}
				onclick={() => onSelectBean(bean)}
				title={bean.tastingNotes || bean.notes || undefined}
			>
				<BeanPill {bean} {roaster} active={activeBeanId === bean.id} />
			</button>
		{/each}
		{#if !hasAny}
			<div class="qfstrip-empty">
				{#if !hasAnyPinned}
					No pinned profiles or beans — pin one from Profiles or Beans
				{:else if query}
					No matches for "{query}"
				{:else}
					No pinned favourites
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

<style>
	/*
	 * Thin vertical divider between the profile and bean halves of the strip.
	 * Same hairline treatment as the foot's `.qsheet-chart-div` so the eye
	 * groups them as "two sections of the same row".
	 */
	.qfstrip-divider {
		flex: 0 0 auto;
		align-self: center;
		display: inline-block;
		width: 1px;
		height: 28px;
		background: rgba(var(--tint-rgb), 0.12);
		margin: 0 4px;
	}
	/* Section-leader glyph — the sidebar's Profiles / Beans icons,
	   reused here so the strip's two halves are recognisable at a glance
	   even when chips look similar (e.g. small viewport, search-filtered). */
	.qfstrip-section-icon {
		flex: 0 0 auto;
		align-self: center;
		font-size: 14px;
		color: rgba(var(--tint-rgb), 0.45);
		margin-right: 4px;
	}
</style>
