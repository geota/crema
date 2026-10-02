<script lang="ts">
	import MagnifyingGlassIcon from 'phosphor-svelte/lib/MagnifyingGlassIcon';
	import CircleNotchIcon from 'phosphor-svelte/lib/CircleNotchIcon';
	import LinkIcon from 'phosphor-svelte/lib/LinkIcon';
	import XIcon from 'phosphor-svelte/lib/XIcon';
	/**
	 * `CatalogueSearch` — the bean form's "Search Visualizer catalogue" field.
	 *
	 * An inline typeahead (same input + popover look as
	 * {@link RoasterAutocomplete}) over Visualizer's canonical coffee-bag
	 * catalogue. Typing is debounced through {@link createCatalogueSearch};
	 * each result shows the bag name, its roaster and — when the catalogue has
	 * them — country and process. Picking a row hands it to `onPick` with the
	 * "replace filled fields" choice; the parent applies the core autofill rule
	 * (empty fields only unless replacing).
	 *
	 * Keyboard: ↓ / ↑ move the active row, Enter picks, Esc closes.
	 */
	import { onDestroy } from 'svelte';
	import {
		createCatalogueSearch,
		type CatalogueCoffeeBag,
		type CataloguePage,
		type CatalogueSearchState
	} from '$lib/bean/catalogue';

	let {
		search,
		onPick,
		linkedLabel = null,
		onUnlink,
		status = null
	}: {
		/** Run one catalogue search (the `beans.searchCatalogue` bridge). */
		search: (query: string) => Promise<CataloguePage>;
		/** A row was picked; `replaceAll` = overwrite fields the user filled. */
		onPick: (entry: CatalogueCoffeeBag, replaceAll: boolean) => void;
		/** The catalogue entry this bean is linked to (shown as a chip), if any. */
		linkedLabel?: string | null;
		/** Clear the catalogue link. */
		onUnlink?: () => void;
		/** One-line outcome of the last pick ("Filled 6 fields"). */
		status?: string | null;
	} = $props();

	let query = $state('');
	let focused = $state(false);
	let activeIdx = $state(0);
	let replaceAll = $state(false);
	let st = $state<CatalogueSearchState>({ query: '', loading: false, results: [], error: null });
	let inputEl = $state<HTMLInputElement | null>(null);

	const controller = createCatalogueSearch({
		search: (q) => search(q),
		onState: (next) => {
			st = next;
			activeIdx = 0;
		}
	});
	onDestroy(() => controller.dispose());

	const showPopover = $derived(focused && st.query.length >= 2);
	const listId = 'catalogue-search-results';

	function pick(entry: CatalogueCoffeeBag): void {
		onPick(entry, replaceAll);
		query = '';
		controller.clear();
		focused = false;
		inputEl?.blur();
	}

	function onKeyDown(e: KeyboardEvent): void {
		if (e.key === 'Escape') {
			e.preventDefault();
			focused = false;
			return;
		}
		if (!showPopover || st.results.length === 0) return;
		if (e.key === 'ArrowDown') {
			e.preventDefault();
			activeIdx = (activeIdx + 1) % st.results.length;
		} else if (e.key === 'ArrowUp') {
			e.preventDefault();
			activeIdx = (activeIdx - 1 + st.results.length) % st.results.length;
		} else if (e.key === 'Enter') {
			const entry = st.results[activeIdx];
			if (entry) {
				e.preventDefault();
				pick(entry);
			}
		}
	}

	function subline(e: CatalogueCoffeeBag): string {
		return [e.roasterName, e.meta].filter((s) => s && s.trim()).join(' · ');
	}
</script>

<div class="cs-wrap">
	<div class="cs-field">
		<MagnifyingGlassIcon class="cs-lead" aria-hidden="true" />
		<input
			bind:this={inputEl}
			class="cs-input"
			type="search"
			placeholder="Roaster or bag name"
			aria-label="Search Visualizer catalogue"
			role="combobox"
			aria-expanded={showPopover}
			aria-controls={listId}
			aria-autocomplete="list"
			value={query}
			oninput={(e) => {
				query = (e.currentTarget as HTMLInputElement).value;
				controller.setQuery(query);
			}}
			onfocus={() => (focused = true)}
			onblur={() => setTimeout(() => (focused = false), 120)}
			onkeydown={onKeyDown}
			autocomplete="off"
			spellcheck="false"
		/>
		{#if st.loading}
			<CircleNotchIcon class="cs-spin" aria-label="Searching" />
		{/if}
	</div>

	{#if showPopover}
		<ul class="cs-popover" id={listId} role="listbox" aria-label="Catalogue results">
			{#each st.results as r, i (r.id)}
				<li role="option" aria-selected={i === activeIdx}>
					<button
						type="button"
						class="cs-item"
						class:is-active={i === activeIdx}
						onmousedown={(e) => {
							e.preventDefault();
							pick(r);
						}}
						onmouseenter={() => (activeIdx = i)}
					>
						<span class="cs-name">{r.name}</span>
						<span class="cs-sub">{subline(r)}</span>
					</button>
				</li>
			{/each}
			{#if st.error}
				<li class="cs-hint is-error">{st.error}</li>
			{:else if !st.loading && st.results.length === 0}
				<li class="cs-hint">No catalogue matches for “{st.query}”.</li>
			{:else if st.loading && st.results.length === 0}
				<li class="cs-hint">Searching…</li>
			{/if}
		</ul>
	{/if}

	<div class="cs-foot">
		<label class="cs-check">
			<input type="checkbox" bind:checked={replaceAll} />
			<span>Replace fields I've already filled</span>
		</label>
		{#if linkedLabel}
			<span class="cs-linked" title="Synced bags link to this Visualizer catalogue entry">
				<LinkIcon aria-hidden="true" />
				<span class="cs-linked-name">{linkedLabel}</span>
				{#if onUnlink}
					<button type="button" class="cs-unlink" onclick={onUnlink} aria-label="Unlink catalogue entry">
						<XIcon aria-hidden="true" />
					</button>
				{/if}
			</span>
		{/if}
	</div>
	{#if status}
		<div class="cs-status" role="status">{status}</div>
	{/if}
</div>

<style>
	.cs-wrap {
		position: relative;
		width: 100%;
	}
	.cs-field {
		position: relative;
		display: flex;
		align-items: center;
	}
	.cs-field :global(.cs-lead) {
		position: absolute;
		left: 10px;
		font-size: 14px;
		color: rgba(var(--tint-rgb), 0.45);
		pointer-events: none;
	}
	.cs-field :global(.cs-spin) {
		position: absolute;
		right: 10px;
		font-size: 14px;
		color: var(--copper-300);
		animation: cs-spin 1s linear infinite;
	}
	@keyframes cs-spin {
		to {
			transform: rotate(360deg);
		}
	}
	/* Mirrors `.be-input` / `.ra-input` (scoped CSS can't share the class). */
	.cs-input {
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: var(--radius-sm);
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 13px;
		padding: 8px 30px 8px 32px;
		outline: 0;
		color-scheme: dark;
		width: 100%;
		box-sizing: border-box;
	}
	.cs-input:focus {
		border-color: var(--copper-400);
	}
	.cs-input::-webkit-search-cancel-button {
		display: none;
	}
	.cs-popover {
		position: absolute;
		top: 38px;
		left: 0;
		right: 0;
		z-index: 70;
		list-style: none;
		padding: 4px;
		margin: 0;
		background: var(--bg-card, rgba(20, 20, 22, 0.98));
		backdrop-filter: blur(8px);
		border: 1px solid rgba(var(--tint-rgb), 0.16);
		border-radius: var(--radius-md, 8px);
		box-shadow: 0 12px 32px rgba(0, 0, 0, 0.5);
		max-height: 280px;
		overflow-y: auto;
	}
	.cs-item {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: 2px;
		width: 100%;
		background: transparent;
		border: 0;
		text-align: left;
		color: var(--fg-1);
		font-family: var(--font-sans);
		padding: 7px 10px;
		border-radius: var(--radius-sm, 6px);
		cursor: pointer;
	}
	.cs-name {
		font-size: 13px;
	}
	.cs-sub {
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.5);
	}
	.cs-item.is-active {
		background: rgba(193, 124, 79, 0.16);
	}
	.cs-item.is-active .cs-name {
		color: var(--copper-300);
	}
	.cs-hint {
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.5);
		padding: 7px 10px;
	}
	.cs-hint.is-error {
		color: var(--danger, #e3553c);
	}
	.cs-foot {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		justify-content: space-between;
		gap: 8px;
		margin-top: 8px;
	}
	.cs-check {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.6);
		cursor: pointer;
	}
	.cs-check input {
		accent-color: var(--copper-400);
		margin: 0;
	}
	.cs-linked {
		display: inline-flex;
		align-items: center;
		gap: 5px;
		max-width: 100%;
		font-family: var(--font-sans);
		font-size: 11px;
		color: var(--copper-300);
		background: rgba(193, 124, 79, 0.12);
		border: 1px solid rgba(193, 124, 79, 0.28);
		border-radius: 999px;
		padding: 2px 4px 2px 8px;
	}
	.cs-linked-name {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.cs-unlink {
		display: inline-flex;
		background: transparent;
		border: 0;
		padding: 2px;
		color: inherit;
		cursor: pointer;
		border-radius: 999px;
	}
	.cs-unlink:hover {
		background: rgba(193, 124, 79, 0.2);
	}
	.cs-status {
		margin-top: 6px;
		font-family: var(--font-sans);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.55);
	}
</style>
