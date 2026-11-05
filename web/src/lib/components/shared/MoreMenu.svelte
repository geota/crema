<script lang="ts">
	/**
	 * `MoreMenu` — a ⋯ button that opens a small `role="menu"` of actions, for a
	 * phone header with no room to show them in a row. Items match the
	 * `SplitButton` menu; the keyboard contract is `menuKeyboard`'s.
	 */
	import DotsThreeIcon from 'phosphor-svelte/lib/DotsThreeIcon';
	import Icon from '#lib/icons/Icon.svelte';
	import { menuKeyboard } from '#lib/a11y/focus.js';

	export interface MoreMenuItem {
		/** Phosphor icon class, e.g. `ph-duotone ph-file-text`. */
		icon: string;
		title: string;
		sub?: string;
		onclick: () => void;
		disabled?: boolean;
	}

	let { label, items }: { label: string; items: MoreMenuItem[] } = $props();

	let open = $state(false);
	let trigger = $state<HTMLButtonElement>();

	function pick(item: MoreMenuItem): void {
		open = false;
		item.onclick();
	}
</script>

<svelte:window onclick={() => (open = false)} />

<!-- svelte-ignore a11y_click_events_have_key_events -->
<!-- svelte-ignore a11y_no_static_element_interactions -->
<div class="more" onclick={(e) => e.stopPropagation()}>
	<button
		bind:this={trigger}
		type="button"
		class="more-btn"
		aria-label={label}
		aria-haspopup="menu"
		aria-expanded={open}
		onclick={() => (open = !open)}
	>
		<DotsThreeIcon weight="bold" aria-hidden="true" />
	</button>
	{#if open}
		<div
			class="more-menu"
			role="menu"
			aria-label={label}
			{@attach menuKeyboard({ onClose: () => (open = false), trigger: () => trigger })}
		>
			{#each items as it (it.title)}
				<button
					type="button"
					class="more-item"
					role="menuitem"
					disabled={it.disabled}
					onclick={() => pick(it)}
				>
					<Icon cls={it.icon} aria-hidden="true" />
					<span class="more-text">
						<span class="more-title">{it.title}</span>
						{#if it.sub}<span class="more-sub">{it.sub}</span>{/if}
					</span>
				</button>
			{/each}
		</div>
	{/if}
</div>

<style>
	.more {
		position: relative;
		display: inline-flex;
	}
	.more-btn {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 44px;
		height: 44px;
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: 50%;
		background: rgba(var(--tint-rgb), 0.04);
		color: var(--fg-1);
		font-size: 18px;
		cursor: pointer;
		transition: background var(--dur-1) var(--ease);
	}
	.more-btn:hover,
	.more-btn[aria-expanded='true'] {
		background: rgba(var(--tint-rgb), 0.08);
	}
	.more-btn:focus-visible,
	.more-item:focus-visible {
		outline: none;
		box-shadow: var(--shadow-focus);
	}
	.more-menu {
		position: absolute;
		top: calc(100% + 6px);
		right: 0;
		z-index: 60;
		width: min(300px, calc(100vw - 2 * var(--page-pad-x)));
		background: var(--bg-page);
		border: 1px solid rgba(var(--tint-rgb), 0.14);
		border-radius: var(--radius-md);
		padding: 6px;
		box-shadow: var(--shadow-lg);
	}
	.more-item {
		display: flex;
		align-items: flex-start;
		gap: 10px;
		width: 100%;
		min-height: 44px;
		padding: 10px 8px;
		border: 0;
		border-radius: var(--radius-sm);
		background: transparent;
		color: var(--fg-1);
		font: inherit;
		text-align: left;
		cursor: pointer;
	}
	.more-item:hover:not(:disabled) {
		background: rgba(var(--tint-rgb), 0.06);
	}
	.more-item:disabled {
		opacity: 0.45;
		cursor: not-allowed;
	}
	.more-item :global(svg) {
		flex: none;
		margin-top: 1px;
		font-size: 18px;
		color: var(--fg-accent);
	}
	.more-text {
		display: flex;
		flex-direction: column;
		gap: 2px;
		min-width: 0;
	}
	.more-title {
		font-family: var(--font-sans);
		font-size: 13px;
		font-weight: 600;
	}
	.more-sub {
		font-family: var(--font-sans);
		font-size: 11px;
		line-height: 1.4;
		color: var(--fg-3);
	}
</style>
