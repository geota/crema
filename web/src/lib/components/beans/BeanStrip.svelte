<script lang="ts">
	/**
	 * `BeanStrip` — the brew-page bean picker, mirroring `FavoritesStrip`'s
	 * profile-pinning idiom (docs/28 §UX §brew-page-integration). Shows
	 * favourited bags as scrolling chips; tap one to set the active bean.
	 * A trailing "+ All beans" chip jumps to `/beans`. Freshness dot per
	 * chip so the user sees the band-coloured signal at a glance.
	 *
	 * Search filters the chip list inline. Left / right arrow buttons nudge
	 * the horizontal scroll (same pattern as the profile strip).
	 */
	import { untrack } from 'svelte';
	import type { Attachment } from 'svelte/attachments';
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import {
		daysOffRoast,
		roastBand,
		roastFreshness,
		type Bean,
		type Roaster
	} from '$lib/bean';

	let {
		beans,
		roasters,
		activeBeanId,
		onSelect
	}: {
		beans: readonly Bean[];
		roasters: readonly Roaster[];
		activeBeanId: string | null;
		onSelect: (bean: Bean) => void;
	} = $props();

	let query = $state('');
	let scrollEl = $state<HTMLDivElement | null>(null);
	let canScrollLeft = $state(false);
	let canScrollRight = $state(false);

	const roasterById = $derived.by(() => {
		const m = new Map<string, Roaster>();
		for (const r of roasters) m.set(r.id, r);
		return m;
	});

	const items = $derived.by(() => {
		const q = query.trim().toLowerCase();
		if (!q) return beans;
		return beans.filter((b) => {
			const r = b.roasterId ? roasterById.get(b.roasterId) : null;
			return (
				b.name.toLowerCase().includes(q) ||
				r?.name.toLowerCase().includes(q) ||
				b.origin.country?.toLowerCase().includes(q)
			);
		});
	});

	function syncArrows(): void {
		const el = scrollEl;
		if (!el) return;
		canScrollLeft = el.scrollLeft > 4;
		canScrollRight = el.scrollLeft + el.clientWidth < el.scrollWidth - 4;
	}

	const trackScroll: Attachment<HTMLDivElement> = (el) => {
		scrollEl = el;
		untrack(syncArrows);
		const observer = new ResizeObserver(syncArrows);
		observer.observe(el);
		return () => {
			observer.disconnect();
			scrollEl = null;
		};
	};

	$effect(() => {
		void items.length;
		syncArrows();
	});

	function nudge(dir: number): void {
		scrollEl?.scrollBy({ left: dir * 240, behavior: 'smooth' });
	}

	function freshnessColor(b: Bean): string {
		const days = daysOffRoast(b.roastedOn);
		const band = roastBand(b.roastLevel);
		const f = roastFreshness(band, days);
		return f === 'best'
			? 'var(--success)'
			: f === 'ok'
				? 'var(--warning)'
				: f === 'bad'
					? 'var(--danger)'
					: 'rgba(var(--tint-rgb), 0.4)';
	}

	function daysLabel(b: Bean): string {
		const d = daysOffRoast(b.roastedOn);
		return d == null ? '' : `${d}d`;
	}
</script>

<div class="bs-wrap">
	<div class="bs-search-inline">
		<i class="ph ph-magnifying-glass" aria-hidden="true"></i>
		<input bind:value={query} placeholder="Search beans" aria-label="Search beans" />
		{#if query}
			<button class="bs-search-clear" onclick={() => (query = '')} aria-label="Clear search">
				<i class="ph ph-x" aria-hidden="true"></i>
			</button>
		{/if}
	</div>
	<button
		class="bs-arrow bs-arrow-l"
		class:is-off={!canScrollLeft}
		onclick={() => nudge(-1)}
		tabindex={canScrollLeft ? 0 : -1}
		aria-label="Scroll beans left"
	>
		<i class="ph ph-caret-left" aria-hidden="true"></i>
	</button>
	<div class="bs-track" {@attach trackScroll} onscroll={syncArrows}>
		{#each items as bean (bean.id)}
			{@const roaster = bean.roasterId ? roasterById.get(bean.roasterId) : null}
			<button
				class="bs-item"
				class:is-active={activeBeanId === bean.id}
				onclick={() => onSelect(bean)}
				title={bean.tastingNotes || bean.notes || undefined}
			>
				<div class="bs-item-eyebrow">
					{roaster?.name ?? 'Roaster'}
				</div>
				<div class="bs-item-name">{bean.name || 'Untitled'}</div>
				<div class="bs-item-meta">
					<span class="bs-fresh" style:color={freshnessColor(bean)}>
						<span
							class="bs-fresh-dot"
							style:background={freshnessColor(bean)}
						></span>
						{daysLabel(bean) || '—'}
					</span>
					{#if bean.bagSizeG > 0}
						<span class="bs-grams">{bean.remainingG.toFixed(0)} g</span>
					{/if}
				</div>
			</button>
		{/each}
		<button
			class="bs-item bs-item-more"
			onclick={() => goto(resolve('/beans'))}
		>
			<i class="ph ph-arrows-out" aria-hidden="true"></i>
			<span>All beans</span>
		</button>
		{#if items.length === 0 && beans.length > 0}
			<div class="bs-empty">No beans match "{query}"</div>
		{:else if beans.length === 0}
			<div class="bs-empty">
				No pinned beans —
				<a href={resolve('/beans')} class="bs-empty-link">add or pin one</a>
			</div>
		{/if}
	</div>
	<button
		class="bs-arrow bs-arrow-r"
		class:is-off={!canScrollRight}
		onclick={() => nudge(1)}
		tabindex={canScrollRight ? 0 : -1}
		aria-label="Scroll beans right"
	>
		<i class="ph ph-caret-right" aria-hidden="true"></i>
	</button>
</div>

<style>
	.bs-wrap {
		display: grid;
		grid-template-columns: 220px auto 1fr auto;
		align-items: center;
		gap: 8px;
		padding: 8px 12px;
		background: rgba(var(--tint-rgb), 0.02);
		border: 1px solid rgba(var(--tint-rgb), 0.06);
		border-radius: var(--radius-lg, 14px);
	}
	.bs-search-inline {
		display: flex;
		align-items: center;
		gap: 8px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
		border-radius: var(--radius-pill);
		padding: 0 10px;
	}
	.bs-search-inline i {
		color: rgba(var(--tint-rgb), 0.5);
		font-size: 12px;
	}
	.bs-search-inline input {
		flex: 1 1 auto;
		min-width: 0;
		background: transparent;
		border: 0;
		outline: 0;
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 12px;
		padding: 6px 0;
	}
	.bs-search-clear {
		background: transparent;
		border: 0;
		color: rgba(var(--tint-rgb), 0.5);
		cursor: pointer;
		padding: 2px;
	}
	.bs-arrow {
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
		border-radius: 50%;
		color: rgba(var(--tint-rgb), 0.6);
		cursor: pointer;
		width: 24px;
		height: 24px;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		font-size: 11px;
		transition: all var(--dur-1) var(--ease);
	}
	.bs-arrow:hover {
		background: rgba(var(--tint-rgb), 0.08);
		color: var(--fg-1);
	}
	.bs-arrow.is-off {
		opacity: 0.3;
		pointer-events: none;
	}
	.bs-track {
		display: flex;
		gap: 8px;
		overflow-x: auto;
		scrollbar-width: none;
	}
	.bs-track::-webkit-scrollbar {
		display: none;
	}
	.bs-item {
		flex: 0 0 auto;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.08);
		border-radius: var(--radius-sm);
		color: var(--fg-1);
		font-family: var(--font-sans);
		text-align: left;
		padding: 8px 12px;
		cursor: pointer;
		min-width: 130px;
		display: flex;
		flex-direction: column;
		gap: 2px;
		transition: all var(--dur-1) var(--ease);
	}
	.bs-item:hover {
		background: rgba(var(--tint-rgb), 0.08);
	}
	.bs-item.is-active {
		border-color: var(--copper-400);
		background: rgba(193, 124, 79, 0.08);
	}
	.bs-item-eyebrow {
		font-size: 9px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		font-weight: 600;
		color: rgba(var(--tint-rgb), 0.5);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		max-width: 150px;
	}
	.bs-item-name {
		font-size: 12px;
		font-weight: 600;
		color: var(--fg-1);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		max-width: 150px;
	}
	.bs-item-meta {
		display: flex;
		gap: 8px;
		align-items: center;
		font-size: 10px;
	}
	.bs-fresh {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		font-weight: 500;
	}
	.bs-fresh-dot {
		width: 5px;
		height: 5px;
		border-radius: 50%;
	}
	.bs-grams {
		color: rgba(var(--tint-rgb), 0.4);
		font-family: var(--font-mono);
	}
	.bs-item-more {
		flex-direction: row;
		gap: 6px;
		align-items: center;
		justify-content: center;
		min-width: auto;
		padding: 8px 14px;
		color: var(--copper-300);
		font-size: 11px;
	}
	.bs-empty {
		padding: 8px;
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.4);
	}
	.bs-empty-link {
		color: var(--copper-400);
		text-decoration: underline;
	}
</style>
