<script lang="ts">
	/**
	 * `/beans/[id]/edit` — edit an existing bean in the full editor.
	 *
	 * Reads `id` from the route, resolves it via the library store, and
	 * passes the live record to {@link BeanEditPage} (which writes each
	 * patch through to the store). If the id is unknown the user is
	 * bounced back to `/beans` (only on arrival, not after a delete here).
	 */
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { getBeanStore } from '#lib/bean/index.js';
	import { missingRecordRedirect } from '#lib/beans/missing-record-redirect.js';
	import BeanEditPage from '#lib/components/beans/BeanEditPage.svelte';

	const library = getBeanStore();

	// `$page.params.id` is decoded for us by SvelteKit. The param can be
	// `undefined` if SvelteKit hasn't hydrated the route yet — defensively
	// coerce so the lookup never throws.
	const id = $derived(page.params.id ?? '');
	const bean = $derived(id ? library.getBean(id) : null);

	// Bounce an unknown id to the list, but not after the editor deletes the
	// bean it was showing: the editor's own exit navigates then (review 03 P3-6).
	const shouldRedirect = missingRecordRedirect();
	$effect(() => {
		if (shouldRedirect(id, bean != null)) {
			// Defer one tick so the navigation isn't fighting the route load.
			queueMicrotask(() => goto(resolve('beans'), { replaceState: true }));
		}
	});

	const tagSuggestions = $derived.by(() => {
		const set = new Set<string>();
		for (const b of library.beans) for (const t of b.tags) set.add(t);
		return [...set].sort((a, b) => a.localeCompare(b));
	});
</script>

<svelte:head>
	<title>Crema — Edit bean</title>
</svelte:head>

{#if bean}
	<BeanEditPage bean={bean} tagSuggestions={tagSuggestions} />
{:else}
	<div class="be-loading">Loading…</div>
{/if}

<style>
	.be-loading {
		display: flex;
		align-items: center;
		justify-content: center;
		min-height: 50vh;
		font-family: var(--font-sans);
		color: var(--fg-3);
	}
</style>
