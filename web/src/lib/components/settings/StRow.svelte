<script lang="ts">
	/**
	 * `StRow` — one settings row: a title / sub text block on the left, a
	 * control (and optional hint) on the right. The control + hint are passed as
	 * snippets so callers compose any of the `St*` controls. Ported from the
	 * design's `StRow` (`settings-page.jsx`).
	 */
	import type { Snippet } from 'svelte';

	let {
		title,
		sub,
		control,
		hint
	}: { title: string; sub?: string; control: Snippet; hint?: Snippet } = $props();
</script>

<div class="st-row">
	<div class="st-row-text">
		<div class="st-row-title">{title}</div>
		{#if sub}<div class="st-row-sub">{sub}</div>{/if}
	</div>
	<div class="st-row-control">
		{@render control()}
		{#if hint}<div class="st-row-hint">{@render hint()}</div>{/if}
	</div>
</div>
