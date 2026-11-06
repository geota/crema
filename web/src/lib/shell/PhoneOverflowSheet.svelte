<script lang="ts" module>
	import type { Component } from 'svelte';

	/** One row of an overflow sheet (Android `SheetItem`). */
	export interface OverflowItem {
		id: string;
		/** A `phosphor-svelte` icon component. */
		icon: Component;
		label: string;
		sub?: string;
		onclick: () => void;
		/** Visible but inert, with `sub` saying why: a hidden item can't be found. */
		disabled?: boolean;
		/** Draw a separator above this row. */
		dividerAbove?: boolean;
	}
</script>

<script lang="ts">
	/**
	 * `PhoneOverflowSheet` — the ⋯ actions as a bottom sheet (Android
	 * `CremaOverflowSheet`). A row closes the sheet, then runs its action.
	 */
	import PhoneSheet from './PhoneSheet.svelte';

	let {
		open,
		title,
		items,
		onClose
	}: { open: boolean; title: string; items: readonly OverflowItem[]; onClose: () => void } =
		$props();

	function pick(item: OverflowItem): void {
		onClose();
		item.onclick();
	}
</script>

<PhoneSheet {open} {onClose} {title} compact>
	<ul class="poverflow">
		{#each items as item (item.id)}
			{@const ItemIcon = item.icon}
			{#if item.dividerAbove}<li class="poverflow-rule" aria-hidden="true"></li>{/if}
			<li>
				<button
					type="button"
					class="poverflow-item"
					disabled={item.disabled}
					onclick={() => pick(item)}
				>
					<ItemIcon aria-hidden="true" />
					<span class="poverflow-text">
						<span class="poverflow-label">{item.label}</span>
						{#if item.sub}<span class="poverflow-sub">{item.sub}</span>{/if}
					</span>
				</button>
			</li>
		{/each}
	</ul>
</PhoneSheet>

<style>
	.poverflow {
		margin: 0 -6px;
		padding: 0;
		list-style: none;
	}
	.poverflow-rule {
		height: 1px;
		margin: 6px 14px;
		background: rgba(var(--tint-rgb), 0.12);
	}
	.poverflow-item {
		display: flex;
		align-items: center;
		gap: 15px;
		width: 100%;
		min-height: 48px;
		padding: 12px 14px;
		border: 0;
		border-radius: 12px;
		background: transparent;
		color: var(--fg-1);
		font: inherit;
		text-align: left;
		cursor: pointer;
	}
	.poverflow-item:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
	.poverflow-item:disabled {
		cursor: not-allowed;
	}
	.poverflow-item:disabled .poverflow-label,
	.poverflow-item:disabled :global(svg) {
		color: var(--fg-muted);
	}
	.poverflow-item :global(svg) {
		flex: none;
		font-size: 21px;
		color: var(--fg-3);
	}
	.poverflow-text {
		display: flex;
		flex-direction: column;
		min-width: 0;
	}
	.poverflow-label {
		font-family: var(--font-sans);
		font-size: 16px;
	}
	.poverflow-sub {
		font-family: var(--font-sans);
		font-size: 12px;
		color: var(--fg-3);
	}
</style>
