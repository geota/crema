<script lang="ts">
	/**
	 * `RefusedStoresStrip` — stays up while a store's saves are being refused,
	 * because its data couldn't be read: the app looks normal, but changes to
	 * that store only last until the tab closes. "Try again" reloads the page,
	 * which reads every store again, so it waits when a reload would interrupt
	 * the machine. There's no "Set this aside" here, unlike Android: a key can
	 * only be moved by reading it, and reading is what failed.
	 */
	import { onMount } from 'svelte';
	import Icon from '#lib/icons/Icon.svelte';
	import { onRefusedStoresChange, refusedStores } from '#lib/utils/storage.js';
	import { refusedMessage } from './storage-notices';
	import { toast } from '#lib/components/shared/toast.svelte.js';

	let {
		canReload,
		reload = () => location.reload()
	}: { canReload: () => boolean; reload?: () => void } = $props();

	let stores = $state(refusedStores());

	onMount(() => {
		stores = refusedStores();
		return onRefusedStoresChange(() => {
			stores = refusedStores();
		});
	});

	function tryAgain(): void {
		if (canReload()) reload();
		else toast.info('The machine is busy. Try again once it’s idle.', 6000);
	}
</script>

{#if stores.length > 0}
	<div class="rs-strip" role="alert">
		<Icon cls="ph ph-warning-circle" aria-hidden="true" />
		<span class="rs-label">{refusedMessage(stores)}</span>
		<button type="button" class="rs-retry" onclick={tryAgain}>Try again</button>
	</div>
{/if}

<style>
	.rs-strip {
		position: sticky;
		top: var(--ptop-clear, 0px);
		z-index: 30;
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 8px 16px;
		background: var(--bg-surface-2);
		border-bottom: 1px solid var(--hairline);
		color: var(--fg-2);
		font-family: var(--font-sans);
		font-size: 13px;
	}
	.rs-label {
		flex: 1 1 auto;
		min-width: 0;
	}
	.rs-retry {
		flex: 0 0 auto;
		min-height: 32px;
		padding: 4px 12px;
		border: 1px solid var(--hairline-strong);
		border-radius: 999px;
		background: var(--bg-surface);
		color: var(--fg-1);
		font: inherit;
		font-weight: 600;
		cursor: pointer;
	}
	.rs-retry:hover {
		border-color: var(--copper-400);
	}
	.rs-retry:focus-visible {
		outline: 2px solid var(--copper-400);
		outline-offset: 2px;
	}
</style>
