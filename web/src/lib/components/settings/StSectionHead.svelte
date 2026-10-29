<script lang="ts">
	/**
	 * `StSectionHead` — a settings section's title block: optional eyebrow, the
	 * serif title, and an optional sub-line. Ported from the design's
	 * `StSectionHead` (`settings-page.jsx`).
	 */
	let {
		eyebrow,
		title,
		sub
	}: { eyebrow?: string; title: string; sub?: string } = $props();
</script>

<div class="st-shead">
	{#if eyebrow}
		<div class="t-eyebrow" style="color:var(--fg-3)">{eyebrow}</div>
	{/if}
	<h1 class="t-page-title st-shead-title">{title}</h1>
	{#if sub}
		<div class="st-shead-sub">{sub}</div>
	{/if}
</div>
