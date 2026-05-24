<script lang="ts">
	/**
	 * `/beans/roasters/new` — create a fresh roaster in the standalone editor.
	 *
	 * Hands a draft {@link Roaster} to {@link RoasterEditor} in `isNew` mode;
	 * Save persists + navigates back to `/beans`. The draft is local to
	 * this route, so abandoning the form leaves no trace in the library.
	 */
	import { blankRoaster } from '$lib/bean';
	import RoasterEditor from '$lib/components/beans/RoasterEditor.svelte';

	// One-shot draft. Re-mounted whenever the route is re-entered.
	const draft = blankRoaster('');
</script>

<svelte:head>
	<title>Crema — New roaster</title>
</svelte:head>

<RoasterEditor roaster={draft} isNew />
