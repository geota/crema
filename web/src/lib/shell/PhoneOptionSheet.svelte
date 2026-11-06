<script lang="ts" module>
	/** One row of a {@link PhoneOptionSheet}. */
	export interface PhoneOption {
		id: string;
		label: string;
		title?: string;
	}
</script>

<script lang="ts">
	/**
	 * `PhoneOptionSheet` — pick one of a list, the bottom-sheet form of Android's
	 * `CremaMenuItem` dropdowns. A dropdown can't hang from a pill inside a
	 * sideways scroller, so the sort and filter pills open this instead. The
	 * picked row carries a check.
	 */
	import CheckIcon from 'phosphor-svelte/lib/CheckIcon';
	import PhoneSheet from './PhoneSheet.svelte';

	let {
		open,
		title,
		options,
		value,
		onpick,
		onClose
	}: {
		open: boolean;
		title: string;
		options: readonly PhoneOption[];
		value: string;
		onpick: (id: string) => void;
		onClose: () => void;
	} = $props();

	function pick(id: string): void {
		onClose();
		onpick(id);
	}
</script>

<PhoneSheet {open} {onClose} {title} compact>
	<ul class="poption">
		{#each options as o (o.id)}
			<li>
				<button
					type="button"
					class={['poption-item', { 'is-on': o.id === value }]}
					aria-pressed={o.id === value}
					title={o.title}
					onclick={() => pick(o.id)}
				>
					<span class="poption-label">{o.label}</span>
					{#if o.id === value}<CheckIcon aria-hidden="true" />{/if}
				</button>
			</li>
		{/each}
	</ul>
</PhoneSheet>

<style>
	.poption {
		margin: 0 -6px;
		padding: 0;
		list-style: none;
	}
	.poption-item {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 12px;
		width: 100%;
		min-height: 48px;
		padding: 12px 14px;
		border: 0;
		border-radius: 12px;
		background: transparent;
		color: var(--fg-1);
		font-family: var(--font-sans);
		font-size: 16px;
		text-align: left;
		cursor: pointer;
	}
	.poption-item.is-on {
		background: var(--ph-tonal);
		color: var(--ph-on-tonal);
	}
	.poption-label {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.poption-item :global(svg) {
		flex: none;
		font-size: 18px;
	}
	.poption-item:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
</style>
