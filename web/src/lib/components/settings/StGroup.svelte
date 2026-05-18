<script lang="ts">
	/**
	 * `StGroup` — a labelled group of settings rows: an optional title / sub
	 * header above a bordered, rounded container of `StRow`s. Ported from the
	 * design's `StGroup` (`settings-page.jsx`).
	 */
	import type { Snippet } from 'svelte';

	let {
		title,
		sub,
		children
	}: { title?: string; sub?: string; children: Snippet } = $props();
</script>

<div class="st-group">
	{#if title || sub}
		<div class="st-group-head">
			{#if title}<div class="st-group-title">{title}</div>{/if}
			{#if sub}<div class="st-group-sub">{sub}</div>{/if}
		</div>
	{/if}
	<div class="st-group-rows">{@render children()}</div>
</div>
