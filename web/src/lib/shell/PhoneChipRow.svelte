<script lang="ts" module>
	/** One chip of a {@link PhoneChipRow}. */
	export interface PhoneChipSpec {
		id: string;
		label?: string;
		count?: number;
		selected?: boolean;
		/** Phosphor icon class, e.g. `ph ph-star`. */
		icon?: string;
		title?: string;
	}
</script>

<script lang="ts">
	/**
	 * `PhoneChipRow` — the sideways-scrolling filter strip (Android
	 * `CremaFilterChipRow`). It runs to the screen's edges; `trailing` holds the
	 * sort and dropdown pills, which scroll with the chips.
	 */
	import type { Snippet } from 'svelte';
	import PhoneChip from './PhoneChip.svelte';

	let {
		chips,
		onselect,
		label,
		trailing
	}: {
		chips: readonly PhoneChipSpec[];
		onselect: (id: string) => void;
		/** Accessible name of the strip. */
		label: string;
		trailing?: Snippet;
	} = $props();
</script>

<div class="pchips" role="group" aria-label={label}>
	{#each chips as c (c.id)}
		<PhoneChip
			label={c.label ?? ''}
			count={c.count}
			selected={c.selected}
			icon={c.icon}
			title={c.title}
			onclick={() => onselect(c.id)}
		/>
	{/each}
	{@render trailing?.()}
</div>

<style>
	.pchips {
		display: flex;
		align-items: center;
		gap: 8px;
		min-width: 0;
		padding: 6px var(--page-pad-x);
		overflow-x: auto;
		scrollbar-width: none;
		scroll-padding-inline: var(--page-pad-x);
	}
	.pchips::-webkit-scrollbar {
		display: none;
	}
</style>
