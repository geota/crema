<script lang="ts">
	/**
	 * `PhoneStat` — a small labelled value in a phone Brew card (Android
	 * `ReadyParam`). With `children` in place of a value it holds a lone glyph,
	 * centred under its label: the Last-shot card's STOPPED cell.
	 */
	import type { Snippet } from 'svelte';

	let {
		label,
		value = '',
		unit = '',
		children
	}: { label: string; value?: string; unit?: string; children?: Snippet } = $props();
</script>

<div class={['pstat', { 'is-glyph': children }]}>
	<span class="pstat-l">{label}</span>
	<span class="pstat-v">
		{#if children}{@render children()}{:else}{value}{#if unit}<em>{unit}</em>{/if}{/if}
	</span>
</div>

<style>
	.pstat {
		display: flex;
		flex-direction: column;
		gap: 2px;
		min-width: 0;
	}
	.pstat.is-glyph {
		width: fit-content;
		align-items: center;
	}
	.pstat-l {
		font-family: var(--font-sans);
		font-size: 8.5px;
		font-weight: 600;
		letter-spacing: 0.07em;
		text-transform: uppercase;
		white-space: nowrap;
		color: var(--fg-3);
	}
	.pstat-v {
		font-family: var(--font-mono);
		font-size: 15px;
		font-variant-numeric: tabular-nums;
		line-height: 1.25;
		white-space: nowrap;
		color: var(--fg-1);
	}
	.pstat-v em {
		margin-left: 3px;
		font-size: 10px;
		font-style: normal;
		color: var(--fg-muted);
	}
	.pstat-v :global(svg) {
		display: block;
		font-size: 15px;
		color: var(--fg-3);
	}
</style>
