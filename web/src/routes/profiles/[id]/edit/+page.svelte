<script lang="ts">
	/**
	 * `/profiles/[id]/edit` — edit an existing profile.
	 *
	 * Renders the shared {@link ProfileEditor} in `edit` mode. Editing a custom
	 * profile updates it in place; editing a built-in (or arriving with
	 * `?duplicate=1`) forks a fresh custom copy — built-ins are read-only.
	 */
	import { page } from '$app/state';
	import { ProfileEditor } from '$lib/components/profiles';

	/** The profile id from the route. */
	const id = $derived(page.params.id);
</script>

{#key id}
	<ProfileEditor mode="edit" sourceId={id} />
{/key}
