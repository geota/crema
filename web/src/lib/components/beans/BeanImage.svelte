<script lang="ts">
	/**
	 * `BeanImage` — render a `Bean.imageRef` blob from IndexedDB, with
	 * a caller-supplied fallback when there's no photo (or the blob
	 * doesn't resolve — e.g. after a hand-edit cleared IDB).
	 *
	 * Three surfaces (`BeanTile` avatar, `BeanDrawer` hero,
	 * `BeanEditPage` photo slot) all do the same load dance:
	 *  1. async-fetch the blob from {@link BeanImageStore},
	 *  2. `URL.createObjectURL`,
	 *  3. revoke on unmount or when `ref` changes,
	 *  4. cancel the in-flight load when the component unmounts mid-fetch.
	 *
	 * Each call site supplies the wrapper, the CSS class, and a
	 * `fallback` snippet (typically the roaster-initial mono circle).
	 * Returning the fallback for both "no ref" and "blob missing" means
	 * a corrupted IDB never leaves the UI with a blank square.
	 */
	import { untrack, type Snippet } from 'svelte';
	import { getBeanImageStore } from '$lib/bean';

	let {
		ref,
		className = '',
		alt = '',
		fallback
	}: {
		/** The bean's `imageRef`; `null` skips the load. */
		ref: string | null | undefined;
		/** CSS class for the `<img>` — `object-fit`, sizing etc. */
		className?: string;
		/**
		 * Alt text. Default empty — most call sites have a textual
		 * caption beside the image, so the photo is decorative; a11y
		 * linter prefers `""` over `"photo"` to avoid the redundant
		 * "image of photo" announcement.
		 */
		alt?: string;
		/** Rendered while there is no `ref` or the blob isn't loaded yet. */
		fallback?: Snippet;
	} = $props();

	const store = getBeanImageStore();
	let url = $state<string | null>(null);

	$effect(() => {
		const r = ref ?? null;
		if (!r) {
			url = null;
			return;
		}
		let cancelled = false;
		let created: string | null = null;
		void (async () => {
			const blob = await store.get(r);
			if (cancelled) return;
			if (blob) {
				created = URL.createObjectURL(blob);
				url = created;
			} else {
				url = null;
			}
		})();
		return () => {
			cancelled = true;
			untrack(() => {
				if (created) URL.revokeObjectURL(created);
			});
		};
	});
</script>

{#if url}
	<img class={className} src={url} {alt} />
{:else if fallback}
	{@render fallback()}
{/if}
