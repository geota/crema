<script lang="ts" module>
	import type { ProfileSegment } from '#lib/profiles/index.js';

	export interface StripProfileRow {
		id: string;
		name: string;
		/** `1:1.9 · 18.0 g · 90.0 °C`. */
		meta: string;
		segments: readonly ProfileSegment[];
	}

	export interface StripRecipeRow {
		id: string;
		name: string;
		/** `V60 · 15 g · 250 g · 100 °C`. */
		meta: string;
		/** The brew method, drawn as its mark. */
		method: string;
		/** Lowercased text the search matches, besides the name (the method). */
		search?: string;
	}

	export interface StripBeanRow {
		id: string;
		/** `Roaster · Bean`. */
		name: string;
		/** `64d off roast · 169.0 g left`. */
		meta: string;
		mark: string;
		tone: string;
	}
</script>

<script lang="ts">
	/**
	 * `PhoneBrewStrip` — the one tappable profile + bean strip on the phone Brew
	 * screen, and the swap dropdown anchored under it (Android `ProfileStrip` +
	 * `SwapDropdown`): the loaded profile and bag lead, each section links to
	 * its library. Presentational; `BrewDashboard` owns the data and handlers.
	 *
	 * Brew modes (§2, owner revision): a search box first, then Espresso
	 * profiles and Manual recipes (three recent each, with "All … →"), then
	 * Bean and Grinder. Typing searches all four, grouped by type: the top
	 * three of each with "n more →", the matches highlighted; ✕ goes back to
	 * the sections. Picking a profile means Espresso, a recipe Manual. While a
	 * shot or brew runs the locked sections' rows are disabled (§4).
	 */
	import MagnifyingGlassIcon from 'phosphor-svelte/lib/MagnifyingGlassIcon';
	import XIcon from 'phosphor-svelte/lib/XIcon';
	import MethodMark from '#lib/components/brewlog/MethodMark.svelte';
	import ArrowRightIcon from 'phosphor-svelte/lib/ArrowRightIcon';
	import ArrowsClockwiseIcon from 'phosphor-svelte/lib/ArrowsClockwiseIcon';
	import CaretDownIcon from 'phosphor-svelte/lib/CaretDownIcon';
	import CheckCircleIcon from 'phosphor-svelte/lib/CheckCircleIcon';
	import { restoreFocus, trapTab } from '#lib/a11y/focus.js';
	import StarIcon from 'phosphor-svelte/lib/StarIcon';
	import ProfileSpark from './ProfileSpark.svelte';
	import GrindMarker from '#lib/components/grinders/GrindMarker.svelte';
	import type { GrinderPickRow } from '#lib/components/grinders/GrinderPicker.svelte';

	let {
		title,
		beanLine,
		specLine = null,
		freshLabel = null,
		freshColor = 'rgba(var(--tint-rgb), 0.4)',
		syncLabel = null,
		profiles,
		beans,
		activeProfileId = null,
		activeBeanId = null,
		onSelectProfile,
		onSelectBean,
		onAllProfiles,
		onAllBeans,
		specMarker = null,
		specMarkerTitle = '',
		grinders = [],
		activeGrinderId = null,
		onSelectGrinder,
		onManageGrinders,
		recipes = [],
		allProfiles = profiles,
		allRecipes = recipes,
		allBeans = beans,
		activeRecipeId = null,
		onSelectRecipe,
		onAllRecipes,
		profilesLocked = null,
		recipesLocked = null
	}: {
		title: string;
		/** `Roaster · Bean`, or the empty-state line. */
		beanLine: string;
		/** `Grind 5.9 · Niche`, fainter, on its own row. */
		specLine?: string | null;
		freshLabel?: string | null;
		freshColor?: string;
		/** "Uploading… 3/9" while a profile goes to the DE1. */
		syncLabel?: string | null;
		profiles: readonly StripProfileRow[];
		beans: readonly StripBeanRow[];
		activeProfileId?: string | null;
		activeBeanId?: string | null;
		onSelectProfile: (id: string) => void;
		onSelectBean: (id: string) => void;
		onAllProfiles: () => void;
		onAllBeans: () => void;
		/** The unsaved-grind marker after the spec line ("Bag 4.2"), or null (#488). */
		specMarker?: string | null;
		specMarkerTitle?: string;
		/** The swap sheet's Grinder section (#488, board PhoneBrew2). */
		grinders?: readonly GrinderPickRow[];
		activeGrinderId?: string | null;
		onSelectGrinder?: (id: string | null) => void;
		onManageGrinders?: () => void;
		/** Manual recipes: the three recent ones. */
		recipes?: readonly StripRecipeRow[];
		/** Everything the search looks through. */
		allProfiles?: readonly StripProfileRow[];
		allRecipes?: readonly StripRecipeRow[];
		allBeans?: readonly StripBeanRow[];
		/** The selected recipe (Manual), else null. */
		activeRecipeId?: string | null;
		onSelectRecipe?: (id: string) => void;
		onAllRecipes?: () => void;
		/** The lock note for the profile / recipe rows (§4), or null. */
		profilesLocked?: string | null;
		recipesLocked?: string | null;
	} = $props();

	/** The search box; empty shows the sections. */
	let query = $state('');
	const q = $derived(query.trim().toLowerCase());
	/** Result groups opened past their top three. */
	let expanded = $state<Record<string, boolean>>({});

	const matches = (text: string, extra = '') => `${text} ${extra}`.toLowerCase().includes(q);
	/** The search's groups, in the sections' order; empty ones are named below them. */
	const results = $derived.by(() => {
		if (q === '') return [];
		return [
			{ id: 'profiles', label: 'Espresso profiles', noun: 'profile', rows: allProfiles.filter((p) => matches(p.name)) },
			{ id: 'recipes', label: 'Manual recipes', noun: 'recipe', rows: allRecipes.filter((r) => matches(r.name, r.search)) },
			{ id: 'beans', label: 'Beans', noun: 'bean', rows: allBeans.filter((b) => matches(b.name)) },
			{
				id: 'grinders',
				label: 'Grinders',
				noun: 'grinder',
				rows: onSelectGrinder ? grinders.filter((g) => matches(g.name)) : []
			}
		];
	});
	const emptyGroups = $derived(
		results.filter((g) => g.rows.length === 0 && (g.id !== 'grinders' || onSelectGrinder)).map((g) => `${g.noun}s`)
	);

	/** `name` split around the first match of the query, for the highlight. */
	function hit(name: string): [string, string, string] {
		const i = q === '' ? -1 : name.toLowerCase().indexOf(q);
		if (i < 0) return [name, '', ''];
		return [name.slice(0, i), name.slice(i, i + q.length), name.slice(i + q.length)];
	}

	function clearSearch(): void {
		query = '';
		expanded = {};
	}

	let open = $state(false);
	let panelEl = $state<HTMLDivElement>();

	$effect(() => {
		if (!open) return;
		const opener = document.activeElement;
		panelEl?.focus({ preventScroll: true });
		return () => {
			const active = document.activeElement;
			if (active == null || active === document.body) restoreFocus(opener);
		};
	});

	function pick(select: (id: string) => void, id: string): void {
		open = false;
		select(id);
	}

	function onKeydown(e: KeyboardEvent): void {
		if (e.key === 'Escape') {
			e.preventDefault();
			e.stopPropagation();
			// Escape clears a search first, then closes.
			if (query !== '') clearSearch();
			else open = false;
			return;
		}
		if (panelEl) trapTab(e, panelEl);
	}
</script>

{#snippet name(text: string)}
	{@const [a, m, b] = hit(text)}
	<span class="pdd-name">{a}{#if m}<b class="pdd-hit">{m}</b>{/if}{b}</span>
{/snippet}

{#snippet profileRow(p: StripProfileRow)}
	{@const active = p.id === activeProfileId && activeRecipeId == null}
	<button
		type="button"
		class={['pdd-row', { 'is-active': active }]}
		aria-pressed={active}
		disabled={profilesLocked != null}
		onclick={() => pick(onSelectProfile, p.id)}
	>
		<span class="pdd-spark"><ProfileSpark segments={p.segments} {active} /></span>
		<span class="pdd-text">
			{@render name(p.name)}
			<span class="pdd-meta">{p.meta}</span>
		</span>
		{#if active}<CheckCircleIcon aria-hidden="true" />{/if}
	</button>
{/snippet}

{#snippet recipeRow(r: StripRecipeRow)}
	{@const active = r.id === activeRecipeId}
	<button
		type="button"
		class={['pdd-row', { 'is-active': active }]}
		aria-pressed={active}
		disabled={recipesLocked != null}
		onclick={() => pick((id) => onSelectRecipe?.(id), r.id)}
	>
		<span class="pdd-spark pdd-method"><MethodMark method={r.method} size={16} /></span>
		<span class="pdd-text">
			{@render name(r.name)}
			<span class="pdd-meta">{r.meta}</span>
		</span>
		{#if active}<CheckCircleIcon aria-hidden="true" />{/if}
	</button>
{/snippet}

{#snippet beanRow(b: StripBeanRow)}
	{@const active = b.id === activeBeanId}
	<button
		type="button"
		class={['pdd-row', { 'is-active': active }]}
		aria-pressed={active}
		onclick={() => pick(onSelectBean, b.id)}
	>
		<span class="pdd-av" style:background={b.tone}>{b.mark}</span>
		<span class="pdd-text">
			{@render name(b.name)}
			<span class="pdd-meta">{b.meta}</span>
		</span>
		{#if active}<CheckCircleIcon aria-hidden="true" />{/if}
	</button>
{/snippet}

{#snippet grinderRow(g: GrinderPickRow)}
	{@const active = g.id === activeGrinderId}
	<button
		type="button"
		class={['pdd-row', { 'is-active': active }]}
		aria-pressed={active}
		onclick={() => pick((id) => onSelectGrinder?.(id), g.id)}
	>
		<span class="pdd-text">
			<span class="pdd-name-row">
				{@render name(g.name)}
				{#if g.starred}<StarIcon weight="fill" class="pdd-star" aria-label="this bag's grinder" />{/if}
			</span>
			<span class="pdd-meta">{g.line}</span>
		</span>
		{#if active}<CheckCircleIcon aria-hidden="true" />{/if}
	</button>
{/snippet}

<div class="pstrip-wrap">
	<button
		type="button"
		class="pstrip"
		aria-haspopup="dialog"
		aria-expanded={open}
		aria-label="Change profile, recipe or bean. {title}, {beanLine}"
		onclick={() => {
			clearSearch();
			open = true;
		}}
	>
		<span class="pstrip-text">
			<span class="pstrip-top">
				<span class="pstrip-title">{title}</span>
				{#if syncLabel}
					<span class="pstrip-sync"><ArrowsClockwiseIcon aria-hidden="true" />{syncLabel}</span>
				{:else if freshLabel}
					<span class="pstrip-fresh" style:color={freshColor}>
						<span class="pstrip-pip"></span>{freshLabel}
					</span>
				{/if}
			</span>
			<span class="pstrip-line">{beanLine}</span>
			{#if specLine}
				<span class="pstrip-line is-spec">
					{specLine}
					{#if specMarker}<GrindMarker text={specMarker} title={specMarkerTitle} interactive={false} />{/if}
				</span>
			{/if}
		</span>
		<CaretDownIcon aria-hidden="true" />
	</button>

	{#if open}
		<button
			type="button"
			class="pstrip-scrim"
			tabindex="-1"
			aria-hidden="true"
			onclick={() => (open = false)}
		></button>
		<div
			bind:this={panelEl}
			class="pstrip-dd"
			role="dialog"
			aria-label="Choose profile, bean and grinder"
			tabindex="-1"
			onkeydown={onKeydown}
		>
			<label class="pdd-search">
				<MagnifyingGlassIcon aria-hidden="true" />
				<input
					type="search"
					bind:value={query}
					placeholder="Search profiles, recipes, beans, grinders"
					aria-label="Search profiles, recipes, beans and grinders"
				/>
				{#if query !== ''}
					<button type="button" class="pdd-clear" aria-label="Clear search" onclick={clearSearch}>
						<XIcon aria-hidden="true" />
					</button>
				{/if}
			</label>

			{#if q !== ''}
				{#each results.filter((g) => g.rows.length > 0) as g, gi (g.id)}
					{#if gi > 0}<div class="pdd-rule" aria-hidden="true"></div>{/if}
					<div class="pdd-head">
						<h2 class="pdd-title">{g.label} · {g.rows.length}</h2>
					</div>
					{@const shown = expanded[g.id] ? g.rows : g.rows.slice(0, 3)}
					{#if g.id === 'profiles'}
						{#if profilesLocked}<p class="pdd-locked">{profilesLocked}</p>{/if}
						{#each shown as p (p.id)}{@render profileRow(p as StripProfileRow)}{/each}
					{:else if g.id === 'recipes'}
						{#if recipesLocked}<p class="pdd-locked">{recipesLocked}</p>{/if}
						{#each shown as r (r.id)}{@render recipeRow(r as StripRecipeRow)}{/each}
					{:else if g.id === 'beans'}
						{#each shown as b (b.id)}{@render beanRow(b as StripBeanRow)}{/each}
					{:else}
						{#each shown as gr (gr.id)}{@render grinderRow(gr as GrinderPickRow)}{/each}
					{/if}
					{#if !expanded[g.id] && g.rows.length > 3}
						<button type="button" class="pdd-more" onclick={() => (expanded = { ...expanded, [g.id]: true })}>
							{g.rows.length - 3} more {g.rows.length - 3 === 1 ? g.noun : `${g.noun}s`}
							<ArrowRightIcon aria-hidden="true" />
						</button>
					{/if}
				{/each}
				{#if emptyGroups.length > 0}
					<p class="pdd-empty pdd-nomatch">
						No {emptyGroups.length === 1
							? emptyGroups[0]
							: `${emptyGroups.slice(0, -1).join(', ')} or ${emptyGroups.at(-1)}`} match “{query.trim()}”
					</p>
				{/if}
			{:else}
			<div class="pdd-head">
				<h2 class="pdd-title">Espresso profiles</h2>
				<button type="button" class="pdd-link" onclick={() => pick(onAllProfiles, '')}>
					All profiles <ArrowRightIcon aria-hidden="true" />
				</button>
			</div>
			{#if profilesLocked}<p class="pdd-locked">{profilesLocked}</p>{/if}
			{#each profiles as p (p.id)}{@render profileRow(p)}{/each}

			{#if onSelectRecipe}
				<div class="pdd-rule" aria-hidden="true"></div>
				<div class="pdd-head">
					<h2 class="pdd-title">Manual recipes</h2>
					<button type="button" class="pdd-link" onclick={() => { open = false; onAllRecipes?.(); }}>
						All recipes <ArrowRightIcon aria-hidden="true" />
					</button>
				</div>
				{#if recipesLocked}<p class="pdd-locked">{recipesLocked}</p>{/if}
				{#each recipes as r (r.id)}{@render recipeRow(r)}{/each}
			{/if}

			<div class="pdd-rule" aria-hidden="true"></div>

			<div class="pdd-head">
				<h2 class="pdd-title">Bean</h2>
				<button type="button" class="pdd-link" onclick={() => pick(onAllBeans, '')}>
					All beans <ArrowRightIcon aria-hidden="true" />
				</button>
			</div>
			{#each beans as b (b.id)}
				{@render beanRow(b)}
			{:else}
				<p class="pdd-empty">No beans yet — add a bag in Beans.</p>
			{/each}

			{#if onSelectGrinder}
				<div class="pdd-rule" aria-hidden="true"></div>
				<div class="pdd-head">
					<h2 class="pdd-title">Grinder</h2>
					<button type="button" class="pdd-link" onclick={() => { open = false; onManageGrinders?.(); }}>
						Manage grinders <ArrowRightIcon aria-hidden="true" />
					</button>
				</div>
				{#each grinders as g (g.id)}
					{@render grinderRow(g)}
				{:else}
					<p class="pdd-empty">No grinders yet — add yours in Devices.</p>
				{/each}
			{/if}
			{/if}
		</div>
	{/if}
</div>

<style>
	.pdd-name-row :global(.pdd-star) {
		color: var(--fg-accent);
		font-size: 12px;
		vertical-align: -1px;
		margin-left: 4px;
	}
	.pstrip-wrap {
		position: relative;
		flex: none;
	}
	.pstrip {
		display: flex;
		align-items: center;
		gap: 10px;
		width: 100%;
		padding: 10px 14px;
		border: 0;
		border-radius: var(--radius-md);
		background: var(--bg-surface);
		color: var(--fg-3);
		font: inherit;
		text-align: left;
		cursor: pointer;
	}
	.pstrip > :global(svg) {
		flex: none;
		font-size: 18px;
	}
	.pstrip:focus-visible,
	.pdd-row:focus-visible,
	.pdd-link:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
	.pstrip-text {
		flex: 1 1 auto;
		display: flex;
		flex-direction: column;
		min-width: 0;
	}
	.pstrip-top {
		display: flex;
		align-items: center;
		gap: 8px;
		min-width: 0;
	}
	.pstrip-title {
		flex: 1 1 auto;
		min-width: 0;
		overflow: hidden;
		font-family: var(--font-sans);
		font-size: 17px;
		font-weight: 500;
		line-height: 1.3;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--fg-1);
	}
	.pstrip-fresh,
	.pstrip-sync {
		flex: none;
		display: inline-flex;
		align-items: center;
		gap: 4px;
		font-family: var(--font-sans);
		font-size: 11px;
		font-weight: 500;
		white-space: nowrap;
	}
	.pstrip-sync {
		color: var(--fg-accent);
	}
	.pstrip-pip {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		background: currentColor;
	}
	.pstrip-line {
		overflow: hidden;
		font-family: var(--font-mono);
		font-size: 12.5px;
		line-height: 1.35;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--fg-3);
	}
	.pstrip-line.is-spec {
		color: var(--fg-muted);
	}

	.pstrip-scrim {
		position: fixed;
		inset: 0;
		z-index: 40;
		border: 0;
		padding: 0;
		background: transparent;
	}
	.pstrip-dd {
		position: absolute;
		top: calc(100% + 8px);
		left: 0;
		right: 0;
		z-index: 41;
		max-height: min(560px, calc(100dvh - var(--ptop-clear) - var(--ptab-clear) - 92px));
		overflow-y: auto;
		overscroll-behavior: contain;
		padding: 8px;
		border-radius: 20px;
		background: var(--bg-surface-2);
		box-shadow: var(--shadow-lg);
		outline: none;
	}
	.pdd-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 8px 8px 6px;
	}
	.pdd-title {
		margin: 0;
		font-family: var(--font-sans);
		font-size: 11px;
		font-weight: 600;
		letter-spacing: 0.04em;
		text-transform: uppercase;
		color: var(--fg-3);
	}
	.pdd-link {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		min-height: 44px;
		margin: -12px -4px -12px 0;
		padding: 0 4px;
		border: 0;
		border-radius: 6px;
		background: transparent;
		color: var(--fg-accent);
		font-family: var(--font-sans);
		font-size: 12px;
		font-weight: 500;
		cursor: pointer;
	}
	.pdd-row {
		display: flex;
		align-items: center;
		gap: 11px;
		width: 100%;
		min-height: 44px;
		padding: 6px 8px;
		border: 0;
		border-radius: 12px;
		background: transparent;
		color: var(--fg-1);
		font: inherit;
		text-align: left;
		cursor: pointer;
	}
	.pdd-row.is-active {
		background: rgba(var(--copper-rgb), 0.18);
	}
	.pdd-row > :global(svg) {
		flex: none;
		font-size: 18px;
		color: var(--fg-accent);
	}
	.pdd-spark {
		flex: none;
		width: 44px;
		height: 26px;
		padding: 3px;
		border-radius: 7px;
		background: var(--bg-page);
	}
	.pdd-av {
		flex: none;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 30px;
		height: 30px;
		border-radius: 8px;
		color: #1f140b;
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 600;
	}
	.pdd-text {
		flex: 1 1 auto;
		display: flex;
		flex-direction: column;
		min-width: 0;
	}
	.pdd-name {
		overflow: hidden;
		font-family: var(--font-sans);
		font-size: 14px;
		font-weight: 500;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.pdd-meta {
		overflow: hidden;
		font-family: var(--font-mono);
		font-size: 11px;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--fg-3);
	}
	.pdd-rule {
		height: 1px;
		margin: 6px 8px;
		background: rgba(var(--tint-rgb), 0.12);
	}
	.pdd-search {
		display: flex;
		align-items: center;
		gap: 8px;
		margin: 4px 4px 6px;
		padding: 0 4px 0 12px;
		min-height: 44px;
		border-radius: 10px;
		background: var(--bg-page);
		border: 1px solid rgba(var(--tint-rgb), 0.16);
		color: var(--fg-muted);
	}
	.pdd-search:focus-within {
		border-color: var(--copper-500);
	}
	.pdd-search > :global(svg) {
		flex: none;
		font-size: 16px;
	}
	.pdd-search input {
		flex: 1;
		min-width: 0;
		border: 0;
		background: transparent;
		color: var(--fg-1);
		font: 400 15px var(--font-sans);
		outline: none;
	}
	.pdd-search input::-webkit-search-cancel-button {
		display: none;
	}
	.pdd-clear {
		flex: none;
		width: 36px;
		height: 36px;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		border: 0;
		border-radius: 8px;
		background: transparent;
		color: var(--fg-3);
		font-size: 16px;
		cursor: pointer;
	}
	.pdd-hit {
		font-weight: 600;
		color: var(--fg-accent);
	}
	.pdd-name-row {
		display: flex;
		align-items: center;
		min-width: 0;
	}
	.pdd-method {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		color: var(--fg-accent);
	}
	.pdd-row:disabled {
		opacity: 0.45;
		cursor: default;
	}
	.pdd-locked {
		margin: 0;
		padding: 0 8px 4px;
		font-family: var(--font-sans);
		font-size: 12px;
		color: var(--warning);
	}
	.pdd-more {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		min-height: 44px;
		padding: 0 8px 0 63px;
		border: 0;
		background: transparent;
		color: var(--fg-accent);
		font-family: var(--font-sans);
		font-size: 13px;
		cursor: pointer;
	}
	.pdd-clear:focus-visible,
	.pdd-more:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
	.pdd-empty {
		margin: 0;
		padding: 8px 10px;
		font-family: var(--font-sans);
		font-size: 12px;
		color: var(--fg-3);
	}
</style>
