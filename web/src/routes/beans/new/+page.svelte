<script lang="ts">
	/**
	 * `/beans/new` — create a fresh bag of coffee in the full editor.
	 *
	 * Hands a draft {@link Bean} to {@link BeanEditPage} in `isNew` mode;
	 * Save persists + navigates back to `/beans`. The draft is local to
	 * this route (Page component holds it), so abandoning the form
	 * leaves no trace in the library.
	 */
	import { blankBean, getBeanStore } from '$lib/bean';
	import BeanEditPage from '$lib/components/beans/BeanEditPage.svelte';

	const library = getBeanStore();

	// One-shot draft. Re-mounted whenever the route is re-entered.
	const draft = blankBean();
	// Seed remaining to bag size so the first Save lands a "full" bag.
	if (draft.bagSizeG === 0) {
		draft.bagSizeG = 250;
		draft.remainingG = 250;
	}

	// Seed roasted-on to today — the most common case is logging a bag
	// the user picked up that day; they can backdate if needed.
	if (!draft.roastedOn) {
		draft.roastedOn = new Date().toISOString().slice(0, 10);
	}

	const tagSuggestions = (() => {
		const set = new Set<string>();
		for (const b of library.beans) for (const t of b.tags) set.add(t);
		return [...set].sort((a, b) => a.localeCompare(b));
	})();
</script>

<svelte:head>
	<title>Crema — New bean</title>
</svelte:head>

<BeanEditPage bean={draft} isNew tagSuggestions={tagSuggestions} />
