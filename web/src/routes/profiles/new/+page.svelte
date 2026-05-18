<script lang="ts">
	/**
	 * `/profiles/new` — create a brand-new profile.
	 *
	 * Renders the shared {@link ProfileEditor} in `new` mode: the draft starts
	 * from a blank custom profile with the default segment list, and Save
	 * creates a new entry in the `localStorage`-backed library.
	 */
	import { ProfileEditor } from '$lib/components/profiles';
</script>

<ProfileEditor mode="new" />
