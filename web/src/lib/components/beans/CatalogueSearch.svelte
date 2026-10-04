<script lang="ts" generics="T extends { id: string; name: string }">
	import MagnifyingGlassIcon from 'phosphor-svelte/lib/MagnifyingGlassIcon';
	import CircleNotchIcon from 'phosphor-svelte/lib/CircleNotchIcon';
	import LinkIcon from 'phosphor-svelte/lib/LinkIcon';
	import XIcon from 'phosphor-svelte/lib/XIcon';
	/**
	 * `CatalogueSearch` — the bean and roaster forms' "Visualizer catalogue"
	 * field.
	 *
	 * An inline typeahead (same input + popover look as
	 * {@link RoasterAutocomplete}) over Visualizer's canonical catalogue —
	 * coffee bags on the bean form, roasters on the roaster form. Typing is
	 * debounced through {@link createCatalogueSearch}; each result shows the
	 * name and a `subline` (roaster · country · process for a bag, country for
	 * a roaster). Picking a row hands it to `onPick`; the parent runs the clash
	 * check (fill empty fields at once, or ask "Keep mine" / "Use catalogue").
	 *
	 * Keyboard: ↓ / ↑ move the active row, Enter picks, Esc closes.
	 */
	import { onDestroy } from 'svelte';
	import { createCatalogueSearch, type CatalogueSearchState } from '$lib/bean/catalogue';

	let {
		search,
		onPick,
		subline,
		placeholder = 'Roaster or bag name',
		listId = 'catalogue-search-results',
		linkedLabel = null,
		linkedTitle = 'Synced bags link to this Visualizer catalogue entry',
		onUnlink,
		status = null
	}: {
		/** Run one catalogue search (a `beans.searchCatalogue*` bridge). */
		search: (query: string) => Promise<{ entries: T[] }>;
		/** A row was picked. */
		onPick: (entry: T) => void;
		/** A result row's secondary line. */
		subline: (entry: T) => string;
		/** Input placeholder. */
		placeholder?: string;
		/** DOM id of the results listbox. */
		listId?: string;
		/** Tooltip on the link chip. */
		linkedTitle?: string;
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
	let st = $state<CatalogueSearchState<T>>({ query: '', loading: false, results: [], error: null });
	let inputEl = $state<HTMLInputElement | null>(null);

	const controller = createCatalogueSearch<T>({
		search: (q) => search(q),
		onState: (next) => {
			st = next;
			activeIdx = 0;
		}
	});
	onDestroy(() => controller.dispose());

	const showPopover = $derived(focused && st.query.length >= 2);

	function pick(entry: T): void {
		onPick(entry);
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
</script>

<div class="cs-wrap">
	<div class="cs-field">
		<MagnifyingGlassIcon class="cs-lead" aria-hidden="true" />
		<input
			bind:this={inputEl}
			class="cs-input"
			type="search"
			{placeholder}
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

	{#if linkedLabel}
		<div class="cs-foot">
			<span class="cs-linked" title={linkedTitle}>
				<LinkIcon aria-hidden="true" />
				<span class="cs-linked-name">{linkedLabel}</span>
				{#if onUnlink}
					<button
						type="button"
						class="cs-unlink"
						onclick={onUnlink}
						aria-label="Unlink catalogue entry"
					>
						<XIcon aria-hidden="true" />
					</button>
				{/if}
			</span>
		</div>
	{/if}
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
		gap: 8px;
		margin-top: 8px;
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
