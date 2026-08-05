<script lang="ts">
	import type { SearchSegment } from '$lib/bean/search';

	let {
		segments,
		fallback = '',
		className = ''
	}: {
		/** Pre-split match runs from the core's search. `null` renders [fallback]. */
		segments: SearchSegment[] | null | undefined;
		/** Plain text to render when there is no match to highlight. */
		fallback?: string;
		/** Extra class on the wrapping span. */
		className?: string;
	} = $props();
</script>

<!--
	Renders a core `SearchHit` snippet with the matched runs emphasised
	(issue 62). The core hands back already-split `(text, hit)` segments —
	including any ellipses — precisely so no shell has to do offset
	arithmetic across a UTF-8 / UTF-16 boundary. This component therefore
	only concatenates; if it ever starts slicing, that is the bug.
-->
{#if segments && segments.length > 0}
	<span class={className}
		>{#each segments as seg, i (i)}{#if seg.hit}<mark class="hl">{seg.text}</mark>{:else}{seg.text}{/if}{/each}</span
	>
{:else}
	<span class={className}>{fallback}</span>
{/if}

<style>
	/*
	 * A tint wash rather than the browser default yellow — the library grid is
	 * dense and a saturated block per tile reads as an error state. Inherits
	 * the surrounding colour so it works on a tile, in the drawer, and in
	 * both themes.
	 */
	.hl {
		background: rgba(var(--copper-rgb), 0.24);
		color: inherit;
		border-radius: 3px;
		padding: 0 1px;
		box-decoration-break: clone;
		-webkit-box-decoration-break: clone;
	}
</style>
