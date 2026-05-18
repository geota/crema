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
		<div class="t-eyebrow" style="color:rgba(244,237,224,0.55)">{eyebrow}</div>
	{/if}
	<div class="st-shead-title">{title}</div>
	{#if sub}
		<div class="st-shead-sub">{sub}</div>
	{/if}
</div>
