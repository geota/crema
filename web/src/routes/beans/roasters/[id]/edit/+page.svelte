<script lang="ts">
	/**
	 * `/beans/roasters/[id]/edit` — edit an existing roaster in the
	 * standalone editor.
	 *
	 * Reads `id` from the route, resolves it via the library store, and
	 * passes the live record to {@link RoasterEditor} (which writes each
	 * patch through to the store). If the id is unknown the user is
	 * bounced back to `/beans`.
	 */
	import { page } from '$app/stores';
	import { goto } from '$app/navigation';
	import { getBeanStore } from '$lib/bean';
	import RoasterEditor from '$lib/components/beans/RoasterEditor.svelte';

	const library = getBeanStore();

	const id = $derived($page.params.id ?? '');
	const roaster = $derived(id ? library.getRoaster(id) : null);

	$effect(() => {
		if (id && !roaster) {
			queueMicrotask(() => goto('/beans'));
		}
	});
</script>

<svelte:head>
	<title>Crema — Edit roaster</title>
</svelte:head>

{#if roaster}
	<RoasterEditor {roaster} />
{:else}
	<div class="rd-loading">Loading…</div>
{/if}

<style>
	.rd-loading {
		display: flex;
		align-items: center;
		justify-content: center;
		min-height: 50vh;
		font-family: var(--font-sans);
		color: rgba(var(--tint-rgb), 0.55);
	}
</style>
